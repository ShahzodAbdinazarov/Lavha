package org.telegram.svipe;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tiny JSON HTTP client for the Svipe backend. All requests run on a background queue;
 * callbacks are delivered on the UI thread.
 */
public class SvipeApi {

    /**
     * Capability level this build declares to the backend, sent on EVERY request.
     *
     * Not a version code — deliberately. The backend does not care which release this is, only which
     * server-visible behaviours the build can handle, and the two are not the same thing (an afat APK
     * reports versionCode {@code 72*10+abi} while the Play bundle reports {@code 72}, so version
     * numbers are not even comparable across our own artifacts).
     *
     * Bump this ONLY when the client gains a capability the server must know about, and gate the new
     * server behaviour behind it. Builds already in users' hands send a lower level (or, before this
     * header existed, none at all) and must keep getting the old behaviour.
     *
     *   1 — implicit: any build predating this header.
     *   2 — mixed-orientation discover grid: can lay out horizontal/16:9 entries and guards long-form
     *       playback (no looping, no full-file download, never persisted to the offline queue).
     *       Serving long-form to a level-1 build would loop a 40-minute video forever and pull the
     *       whole file down after 3 seconds of dwell.
     */
    public static final int CLIENT_LEVEL = 2;
    public static final String CLIENT_LEVEL_HEADER = "X-Svipe-Client";

    /**
     * What this build actually is: {@code <versionCode>/<versionName>/<package>}.
     *
     * Distinct from the capability level above and never a substitute for it — nothing server-side
     * may branch on a version number, because that is how you end up with behaviour nobody can
     * reason about. This is for knowing which builds are in use: how a release is spreading, and
     * whether an old one is still out there. The package tells the three builds apart (Play, .web,
     * .beta), which the version code alone cannot.
     */
    public static final String CLIENT_VERSION_HEADER = "X-Svipe-Version";

    private static String clientVersion;

    private static String clientVersion() {
        if (clientVersion == null) {
            String value = "";
            try {
                android.content.Context context = ApplicationLoader.applicationContext;
                android.content.pm.PackageInfo info = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0);
                value = info.versionCode + "/" + info.versionName + "/" + context.getPackageName();
            } catch (Exception e) {
                FileLog.e(e);
            }
            clientVersion = value;
        }
        return clientVersion;
    }

    public interface JsonCallback {
        void run(JSONObject result, int httpCode, String error);
    }

    /** Result of a raw (non-JSON) transfer such as a presigned upload. */
    public interface RawCallback {
        void run(int httpCode, String error);
    }

    public static void get(String path, String bearer, JsonCallback cb) {
        request("GET", path, null, bearer, cb);
    }

    public static void post(String path, JSONObject body, String bearer, JsonCallback cb) {
        request("POST", path, body, bearer, cb);
    }

    public static void put(String path, JSONObject body, String bearer, JsonCallback cb) {
        request("PUT", path, body, bearer, cb);
    }

    public static void delete(String path, String bearer, JsonCallback cb) {
        request("DELETE", path, null, bearer, cb);
    }

    /**
     * PUT a file to an ABSOLUTE, already-signed URL — the presigned upload the backend hands out for
     * the avatar archive. Deliberately unlike the calls above: no base-url prefix and no bearer, since
     * the signature in the URL is the whole authorization and storage would reject a stray auth header.
     * Streams from disk with a fixed length, so a large photo never has to sit in memory.
     */
    public static void putFile(String absoluteUrl, java.io.File file, String contentType, RawCallback cb) {
        submit(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(absoluteUrl).openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(file.length());
                if (contentType != null) {
                    conn.setRequestProperty("Content-Type", contentType);
                }
                java.io.FileInputStream in = new java.io.FileInputStream(file);
                java.io.OutputStream out = conn.getOutputStream();
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                } finally {
                    try { in.close(); } catch (Exception ignore) {}
                    try { out.close(); } catch (Exception ignore) {}
                }
                final int code = conn.getResponseCode();
                AndroidUtilities.runOnUIThread(() -> cb.run(code, null));
            } catch (Exception e) {
                FileLog.e(e);
                final String err = e.getMessage();
                AndroidUtilities.runOnUIThread(() -> cb.run(0, err));
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignore) {}
                }
            }
        });
    }

    /**
     * GET an ABSOLUTE, already-signed URL straight to a file — the presigned download side of the
     * avatar archive. Writes to a sibling {@code .tmp} and renames on success, so a interrupted
     * transfer can never leave a half-written image where the UI would try to render it.
     */
    public static void getFile(String absoluteUrl, java.io.File dest, RawCallback cb) {
        submit(() -> {
            HttpURLConnection conn = null;
            java.io.File tmp = new java.io.File(dest.getAbsolutePath() + ".tmp");
            try {
                conn = (HttpURLConnection) new URL(absoluteUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                final int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    AndroidUtilities.runOnUIThread(() -> cb.run(code, null));
                    return;
                }
                java.io.File parent = dest.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                InputStream in = conn.getInputStream();
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                } finally {
                    try { in.close(); } catch (Exception ignore) {}
                    try { out.close(); } catch (Exception ignore) {}
                }
                final boolean ok = tmp.length() > 0 && tmp.renameTo(dest);
                if (!ok) {
                    tmp.delete();
                }
                AndroidUtilities.runOnUIThread(() -> cb.run(ok ? code : 0, ok ? null : "write failed"));
            } catch (Exception e) {
                FileLog.e(e);
                try { tmp.delete(); } catch (Exception ignore) {}
                final String err = e.getMessage();
                AndroidUtilities.runOnUIThread(() -> cb.run(0, err));
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignore) {}
                }
            }
        });
    }

    /**
     * Our own network threads. Every call in this class used to go through
     * {@code Utilities.globalQueue}, which is ONE thread — so every request to our backend queued
     * behind every other one, whatever the user was looking at.
     *
     * <p>Measured on a cold start: each call began within a millisecond of the previous one ending.
     * Opening the Music tab first, the music screen's own {@code /v1/music/home} still waited for the
     * reels feed to finish; opening Reels first, music waited 10.9 s. It was never a priority
     * decision — whichever request got in first held the wire.
     *
     * <p>Four threads, not more: on a cold start the calls that matter are the open tab's own fetch,
     * the token, and the two or three warm-ups behind it. Beyond that they would only compete for the
     * same radio. Daemon threads at background priority, so this pool can never hold the process open
     * and can never outrank the UI.
     *
     * <p>All four are CORE threads on purpose. A ThreadPoolExecutor grows past its core size only
     * when the queue REFUSES a task, and an unbounded LinkedBlockingQueue never refuses — so a
     * (2, 4) pool behind one is a two-thread pool wearing a four-thread label. Measured on the Video
     * tab's cold open: three calls left together, two ran, and /v1/videos sat in the queue until one
     * of them finished 16 s later — then answered in 345 ms. Core threads time out like the others,
     * so an idle app still holds none of them.
     */
    private static final java.util.concurrent.ExecutorService NET = buildPool();

    private static java.util.concurrent.ExecutorService buildPool() {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                4, 4, 30L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "svipe-net");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY + 2);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static void submit(Runnable work) {
        try {
            NET.execute(work);
        } catch (Throwable t) {
            // A rejected task must still run rather than vanish: fall back to the old queue.
            FileLog.e(t);
            Utilities.globalQueue.postRunnable(work);
        }
    }

    private static void request(String method, String path, JSONObject body, String bearer, JsonCallback cb) {
        submit(() -> {
            HttpURLConnection conn = null;
            // Every call to our own backend is timed and logged. Without this the cold start can only
            // be measured in brackets — "somewhere between the webview result and the first resolve"
            // — because MTProto logs itself and our HTTP did not, so the two legs of the chain could
            // not be told apart.
            final long t0 = System.currentTimeMillis();
            boolean failed = false;
            try {
                URL url = new URL(SvipeConfig.baseUrl() + path);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(25000);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty(CLIENT_LEVEL_HEADER, String.valueOf(CLIENT_LEVEL));
                final String version = clientVersion();
                if (version.length() > 0) {
                    conn.setRequestProperty(CLIENT_VERSION_HEADER, version);
                }
                if (bearer != null) {
                    conn.setRequestProperty("Authorization", "Bearer " + bearer);
                }
                if (body != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
                }
                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String resp = readStream(is);
                JSONObject json = null;
                if (resp != null && resp.startsWith("{")) {
                    try { json = new JSONObject(resp); } catch (Exception ignore) {}
                }
                final JSONObject fjson = json;
                final int fcode = code;
                FileLog.d("svipe-net: " + method + " " + path + " -> " + fcode
                        + " in " + (System.currentTimeMillis() - t0) + "ms"
                        + (resp != null ? " " + resp.length() + "B" : ""));
                AndroidUtilities.runOnUIThread(() -> cb.run(fjson, fcode, null));
            } catch (Exception e) {
                failed = true;
                FileLog.d("svipe-net: " + method + " " + path + " -> FAILED in "
                        + (System.currentTimeMillis() - t0) + "ms: " + e);
                FileLog.e(e);
                final String err = e.getMessage();
                AndroidUtilities.runOnUIThread(() -> cb.run(null, 0, err));
            } finally {
                // NOT disconnect() on the happy path. HttpURLConnection keeps the socket alive for
                // the next call to the same host, and disconnect() throws it away — so every request
                // to our backend paid a fresh TCP + TLS handshake to Cloudflare. Reading the body to
                // the end (readStream does) is what hands the connection back to the pool. A
                // connection that failed mid-flight is a different matter: that one is not reusable.
                if (conn != null && failed) {
                    try { conn.disconnect(); } catch (Exception ignore) {}
                }
            }
        });
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
