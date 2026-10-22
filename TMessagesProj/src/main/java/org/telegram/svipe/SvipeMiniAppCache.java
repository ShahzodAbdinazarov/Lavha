package org.telegram.svipe;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mini apps kept on the device (svipe.uz/bots/fast-mini-apps).
 *
 * Two things are served from disk instead of the network while a mini app loads:
 *  - svipe-web-app.js, our own helper: shipped in the app's assets, refreshed in the background
 *    against svipe.uz (ETag), so a page that loads it waits for nothing;
 *  - a bot's own files, when the bot asks for it with a manifest at its origin,
 *    {@code https://<origin>/svipe-cache.json}: {"version": "...", "files": ["/", "/app.js", ...],
 *    "update": "background" | "before_open"}. The listed files are downloaded the first time the mini
 *    app is opened and served from disk afterwards. A new {@code version} downloads the set again, whole,
 *    and switches to it at once: on the next open ("background", the default, the open itself never
 *    waits), or before this open ("before_open": the manifest is fetched first, so a changed version is
 *    never shown late). The page is told with the event {@code svipe_cache_updated {version}}.
 *
 * Only GETs of listed paths on the manifest's own https origin are served; everything else, API calls
 * included, goes to the network as before. Limits: 300 files and 30 MB per origin, 150 MB in all,
 * the least recently opened origin goes first.
 */
public final class SvipeMiniAppCache {

    private static final String MANIFEST = "/svipe-cache.json";
    private static final int MAX_FILES = 300;
    private static final long MAX_ORIGIN_BYTES = 30L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 150L * 1024 * 1024;
    private static final long NO_MANIFEST_RECHECK_MS = 24L * 60 * 60 * 1000;
    private static final long HELPER_RECHECK_MS = 30L * 60 * 1000;
    private static final int BEFORE_OPEN_WAIT_MS = 1500;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    /** origin -> the live bundle. */
    private static final ConcurrentHashMap<String, Bundle> live = new ConcurrentHashMap<>();
    private static final HashSet<String> checking = new HashSet<>();
    private static boolean loaded;

    private static class Bundle {
        String origin;
        String version;
        File dir;
        boolean beforeOpen;
        final HashMap<String, String[]> files = new HashMap<>(); // path -> {file name, content type}
    }

    // ---------------------------------------------------------------------------------------------
    // Serving

    /**
     * From WebViewClient.shouldInterceptRequest: the response from disk, or null for the network.
     * Runs on a WebView thread; never touches the network itself.
     */
    public static WebResourceResponse intercept(Uri url, String method) {
        try {
            if (url == null || !"GET".equalsIgnoreCase(method) || !"https".equals(url.getScheme())) {
                return null;
            }
            if (isHelper(url)) {
                FileLog.d("svipe mini app cache: helper from the device " + url.getHost());
                return helper(url);
            }
            ensureLoaded();
            Bundle bundle = live.get(origin(url));
            if (bundle == null) {
                return null;
            }
            String[] entry = bundle.files.get(key(url));
            if (entry == null && url.getQuery() != null) {
                entry = bundle.files.get(pathOnly(url));
            }
            if (entry == null) {
                return null;
            }
            File file = new File(bundle.dir, entry[0]);
            if (!file.exists()) {
                return null;
            }
            FileLog.d("svipe mini app cache: " + bundle.version + " " + url.getEncodedPath() + " from the device");
            HashMap<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            headers.put("X-Svipe-Cache", bundle.version);
            String type = entry[1];
            String mime = type;
            String charset = null;
            int semi = type.indexOf(';');
            if (semi >= 0) {
                mime = type.substring(0, semi).trim();
                int cs = type.toLowerCase().indexOf("charset=");
                if (cs >= 0) charset = type.substring(cs + 8).trim();
            }
            if (charset == null && (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json"))) {
                charset = "utf-8";
            }
            return new WebResourceResponse(mime, charset, 200, "OK", headers, new FileInputStream(file));
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    /**
     * A mini app is about to load {@code pageUrl}. With "before_open" the manifest is checked first, for at
     * most 1.5 s; otherwise the load goes at once and the check runs behind it. {@code onUpdated} gets the
     * new version when one was switched to while the page was open (for svipe_cache_updated).
     */
    public static void beforeLoad(String pageUrl, Runnable load, Utilities.Callback<String> onUpdated) {
        Uri url;
        try {
            url = Uri.parse(pageUrl);
        } catch (Throwable t) {
            load.run();
            return;
        }
        if (url == null || !"https".equals(url.getScheme()) || TextUtils.isEmpty(url.getHost())) {
            load.run();
            return;
        }
        final String origin = origin(url);
        IO.execute(() -> {
            ensureLoaded();
            touch(origin);
            Bundle bundle = live.get(origin);
            if (bundle != null && bundle.beforeOpen) {
                final boolean[] done = new boolean[1];
                final boolean[] timedOut = new boolean[1];
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    synchronized (done) {
                        if (done[0]) return;
                        done[0] = timedOut[0] = true;
                    }
                    load.run();
                }, BEFORE_OPEN_WAIT_MS);
                String fresh = check(origin);
                boolean late;
                synchronized (done) {
                    late = timedOut[0];
                    done[0] = true;
                }
                if (!late) {
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(load);
                } else if (fresh != null && onUpdated != null) {
                    // Arrived after the wait ran out: the page that loaded is the old one.
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> onUpdated.run(fresh));
                }
                return;
            }
            org.telegram.messenger.AndroidUtilities.runOnUIThread(load);
            String fresh = check(origin);
            if (fresh != null && bundle != null && onUpdated != null) {
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> onUpdated.run(fresh));
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // The manifest

    /** Fetches the origin's manifest and downloads a new version. Returns the version switched to, or null. */
    private static String check(String origin) {
        synchronized (checking) {
            if (!checking.add(origin)) return null;
        }
        try {
            SharedPreferences prefs = prefs();
            long noneUntil = prefs.getLong("none_" + origin, 0);
            if (noneUntil > System.currentTimeMillis() && !live.containsKey(origin)) {
                return null;
            }
            HttpURLConnection conn = open(origin + MANIFEST);
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                if (code == 404 || code == 410) {
                    prefs.edit().putLong("none_" + origin, System.currentTimeMillis() + NO_MANIFEST_RECHECK_MS).apply();
                    drop(origin); // the bot stopped asking: serve from the network again
                }
                return null;
            }
            JSONObject manifest = new JSONObject(new String(readAll(conn.getInputStream(), 256 * 1024), "UTF-8"));
            conn.disconnect();
            String version = manifest.optString("version", "");
            JSONArray files = manifest.optJSONArray("files");
            if (TextUtils.isEmpty(version) || version.length() > 128 || files == null || files.length() == 0) {
                return null;
            }
            boolean beforeOpen = "before_open".equals(manifest.optString("update"));
            Bundle current = live.get(origin);
            if (current != null && version.equals(current.version)) {
                if (current.beforeOpen != beforeOpen) {
                    current.beforeOpen = beforeOpen;
                    save(current);
                }
                return null;
            }
            Bundle next = download(origin, version, beforeOpen, files);
            if (next == null) {
                return null;
            }
            save(next);
            live.put(origin, next);
            FileLog.d("svipe mini app cache: " + origin + " now " + version + " (" + next.files.size() + " files)");
            if (current != null) {
                deleteDir(current.dir);
            }
            evict();
            return version;
        } catch (Throwable t) {
            FileLog.e("svipe mini app cache: " + origin + " " + t);
            return null;
        } finally {
            synchronized (checking) {
                checking.remove(origin);
            }
        }
    }

    private static Bundle download(String origin, String version, boolean beforeOpen, JSONArray files) throws Exception {
        File dir = new File(root(), hash(origin) + "/" + hash(version + System.nanoTime()));
        if (!dir.mkdirs()) {
            return null;
        }
        Bundle bundle = new Bundle();
        bundle.origin = origin;
        bundle.version = version;
        bundle.dir = dir;
        bundle.beforeOpen = beforeOpen;
        long total = 0;
        int n = Math.min(files.length(), MAX_FILES);
        for (int i = 0; i < n; i++) {
            String path = files.optString(i, "");
            if (!path.startsWith("/") || path.startsWith("//") || path.length() > 1024 || bundle.files.containsKey(path)) {
                continue;
            }
            HttpURLConnection conn = open(origin + path);
            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                deleteDir(dir);
                FileLog.e("svipe mini app cache: " + origin + path + " -> " + conn.getResponseCode());
                return null; // a version is kept whole or not at all
            }
            String type = conn.getContentType();
            String name = "f" + i;
            long size;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(new File(dir, name))) {
                size = copy(in, out, MAX_ORIGIN_BYTES - total);
            } finally {
                conn.disconnect();
            }
            if (size < 0) {
                deleteDir(dir);
                FileLog.e("svipe mini app cache: " + origin + " over " + MAX_ORIGIN_BYTES + " bytes");
                return null;
            }
            total += size;
            bundle.files.put(path, new String[]{name, type != null ? type : guessType(path)});
        }
        return bundle;
    }

    // ---------------------------------------------------------------------------------------------
    // svipe-web-app.js

    private static boolean isHelper(Uri url) {
        String host = url.getHost();
        return "/js/svipe-web-app.js".equals(url.getPath()) && ("svipe.uz".equals(host) || "dev.svipe.uz".equals(host));
    }

    private static WebResourceResponse helper(Uri url) throws Exception {
        final String host = url.getHost();
        File local = new File(root(), "helper-" + host + ".js");
        InputStream in = local.exists() ? new FileInputStream(local)
                : ApplicationLoader.applicationContext.getAssets().open("svipe/svipe-web-app.js");
        long last = prefs().getLong("helper_checked_" + host, 0);
        if (System.currentTimeMillis() - last > HELPER_RECHECK_MS) {
            prefs().edit().putLong("helper_checked_" + host, System.currentTimeMillis()).apply();
            IO.execute(() -> refreshHelper(host, local));
        }
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse("application/javascript", "utf-8", 200, "OK", headers, in);
    }

    private static void refreshHelper(String host, File local) {
        HttpURLConnection conn = null;
        try {
            conn = open("https://" + host + "/js/svipe-web-app.js");
            String etag = prefs().getString("helper_etag_" + host, null);
            if (etag != null && local.exists()) {
                conn.setRequestProperty("If-None-Match", etag);
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                return;
            }
            File tmp = new File(local.getPath() + ".tmp");
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                if (copy(in, out, 1024 * 1024) < 0) return;
            }
            if (tmp.renameTo(local)) {
                prefs().edit().putString("helper_etag_" + host, conn.getHeaderField("ETag")).apply();
            }
        } catch (Throwable t) {
            FileLog.e("svipe mini app cache: helper " + t);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Storage

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            JSONObject all = new JSONObject(prefs().getString("bundles", "{}"));
            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                String origin = it.next();
                JSONObject o = all.getJSONObject(origin);
                Bundle b = new Bundle();
                b.origin = origin;
                b.version = o.getString("version");
                b.dir = new File(o.getString("dir"));
                b.beforeOpen = o.optBoolean("before_open");
                JSONObject files = o.getJSONObject("files");
                for (java.util.Iterator<String> f = files.keys(); f.hasNext(); ) {
                    String path = f.next();
                    JSONArray e = files.getJSONArray(path);
                    b.files.put(path, new String[]{e.getString(0), e.getString(1)});
                }
                if (b.dir.isDirectory()) live.put(origin, b);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static synchronized void save(Bundle changed) {
        try {
            JSONObject all = new JSONObject();
            HashMap<String, Bundle> bundles = new HashMap<>(live);
            if (changed != null) bundles.put(changed.origin, changed);
            for (Bundle b : bundles.values()) {
                JSONObject files = new JSONObject();
                for (java.util.Map.Entry<String, String[]> e : b.files.entrySet()) {
                    files.put(e.getKey(), new JSONArray(Arrays.asList(e.getValue())));
                }
                all.put(b.origin, new JSONObject().put("version", b.version).put("dir", b.dir.getPath())
                        .put("before_open", b.beforeOpen).put("files", files));
            }
            prefs().edit().putString("bundles", all.toString()).apply();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void drop(String origin) {
        Bundle b = live.remove(origin);
        if (b != null) {
            deleteDir(b.dir);
            save(null);
        }
    }

    private static void touch(String origin) {
        prefs().edit().putLong("opened_" + origin, System.currentTimeMillis()).apply();
    }

    /** Over the total limit: the least recently opened origins go. */
    private static void evict() {
        ArrayList<Bundle> all = new ArrayList<>(live.values());
        long total = 0;
        for (Bundle b : all) total += size(b.dir);
        if (total <= MAX_TOTAL_BYTES) return;
        SharedPreferences prefs = prefs();
        all.sort((a, b) -> Long.compare(prefs.getLong("opened_" + a.origin, 0), prefs.getLong("opened_" + b.origin, 0)));
        for (Bundle b : all) {
            if (total <= MAX_TOTAL_BYTES) break;
            total -= size(b.dir);
            drop(b.origin);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    private static String origin(Uri url) {
        int port = url.getPort();
        return "https://" + url.getHost() + (port > 0 && port != 443 ? ":" + port : "");
    }

    private static String key(Uri url) {
        String path = TextUtils.isEmpty(url.getEncodedPath()) ? "/" : url.getEncodedPath();
        return url.getEncodedQuery() != null ? path + "?" + url.getEncodedQuery() : path;
    }

    private static String pathOnly(Uri url) {
        return TextUtils.isEmpty(url.getEncodedPath()) ? "/" : url.getEncodedPath();
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(false); // a listed file must live where it is listed
        conn.setRequestProperty("Cache-Control", "no-cache");
        return conn;
    }

    private static String guessType(String path) {
        String p = path.toLowerCase();
        if (p.endsWith("/") || p.endsWith(".html") || p.endsWith(".htm")) return "text/html; charset=utf-8";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    /** Bytes copied, or -1 when over {@code limit}. */
    private static long copy(InputStream in, OutputStream out, long limit) throws Exception {
        byte[] buf = new byte[16384];
        long n = 0;
        int r;
        while ((r = in.read(buf)) > 0) {
            n += r;
            if (n > limit) return -1;
            out.write(buf, 0, r);
        }
        return n;
    }

    private static byte[] readAll(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (copy(in, out, limit) < 0) throw new IllegalStateException("too large");
        return out.toByteArray();
    }

    private static long size(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += size(k);
        return n;
    }

    private static void deleteDir(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteDir(k);
        f.delete();
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 12; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private static File root() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "svipe_miniapps");
        dir.mkdirs();
        return dir;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("svipe_miniapp_cache", Context.MODE_PRIVATE);
    }
}
