package org.telegram.svipe.video;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The one road to {@code t.me} from this device: one thread pool, one browser identity, one reader.
 *
 * <p>Three separate things now read public Telegram pages on the phone — {@link SvipePublicUrl} for a
 * playable mp4, {@link SvipeChannelAvatar} for a channel's picture and {@link SvipePosterSource} for
 * a post's video frame. They are the same traffic to the same host, and if each opened its own pool
 * the device would look like three clients arguing over one radio while t.me counted them as three
 * visitors. They share this one instead.
 *
 * <p><b>Three threads, and that is a ceiling not a target.</b> The window any of these serves is
 * small — the reel on screen and the two ahead of it, or the page of cards a thumb is resting on —
 * and every caller above single-flights its own duplicates before it gets here. More threads would
 * buy contention for the same radio and nothing else. Daemon and below normal priority: this can
 * neither hold the process open nor outrank what is being drawn.
 *
 * <p>No artificial pacing, unlike the backend's copy of this machinery: that one grinds a
 * quarter-million rows and must not look like an attack. A person scrolling a feed asks for one page
 * per screenful, which is exactly what their browser would do on the same pages.
 */
public final class SvipeTme {

    private SvipeTme() {}

    /** The public pages serve their real markup to browsers only; a bare UA gets a stub. */
    public static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";

    /** The embed page measured 17 KB and a channel page 130 KB. An order past that is not it. */
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    /** Nothing plausible as a poster or an avatar is this big — a guard against following a link
     *  to something that is not a picture at all. */
    public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    private static final ExecutorService NET = buildPool();

    private static ExecutorService buildPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                3, 3, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "svipe-tme");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY + 2);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** Run off the UI thread on the shared pool. A rejected task still runs — never vanishes. */
    public static void submit(Runnable work) {
        try {
            NET.execute(work);
        } catch (Throwable t) {
            // A dropped task would leave its waiters waiting forever, which is worse than slow.
            FileLog.e(t);
            Utilities.globalQueue.postRunnable(work);
        }
    }

    /**
     * GET one public page as text, or null when the host did not answer with one.
     *
     * <p>A non-2xx is NOT an exception here: 404 is a real answer about a channel or a post (it was
     * deleted, or went private) and the caller must be able to tell it apart from a timeout, which
     * says nothing about anything. Null with {@code ok=false} is the failure; see {@link Page}.
     */
    public static Page html(String url) {
        HttpURLConnection conn = null;
        boolean failed = false;
        int code = 0;
        String body = null;
        final long t0 = System.currentTimeMillis();
        try {
            conn = open(url);
            code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                body = readBody(conn.getInputStream());
            }
        } catch (Exception e) {
            failed = true;
            FileLog.d("svipe-tme: " + url + " failed in " + (System.currentTimeMillis() - t0)
                    + "ms: " + e);
        } finally {
            // Not disconnect() on the happy path: HttpURLConnection keeps the socket alive for the
            // next page, and every page any of these callers wants is on the same host.
            if (conn != null && failed) {
                try { conn.disconnect(); } catch (Exception ignore) {}
            }
        }
        return new Page(body, code, !failed);
    }

    /** What one page fetch produced, keeping "t.me said no" apart from "the radio said nothing". */
    public static final class Page {
        public final String body;      // null unless the host answered 2xx
        public final int code;         // 0 when the request never completed
        /** True when t.me answered at all — even with a 404. False only for a network failure. */
        public final boolean answered;

        Page(String body, int code, boolean answered) {
            this.body = body;
            this.code = code;
            this.answered = answered;
        }
    }

    /**
     * Download one image straight to {@code dest}, atomically.
     *
     * <p>Writes a sibling {@code .tmp} and renames on success, so an interrupted transfer can never
     * leave a half-written JPEG where a view would try to decode it — the same discipline
     * {@code SvipeApi.getFile} uses for the avatar archive. Returns false on anything at all going
     * wrong, because for a picture there is no failure worth distinguishing: the caller re-scrapes.
     */
    public static boolean download(String url, File dest) {
        if (url == null || dest == null) return false;
        HttpURLConnection conn = null;
        final File tmp = new File(dest.getAbsolutePath() + ".tmp");
        try {
            final File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            conn = open(url);
            final int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                // Overwhelmingly this is 403: the CDN token in the URL has expired. Nothing to log
                // loudly about — the caller's answer is to read the page again for a fresh one.
                FileLog.d("svipe-tme: image -> HTTP " + code);
                return false;
            }
            long written = 0;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                final byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    written += n;
                    if (written > MAX_IMAGE_BYTES) {
                        return false;
                    }
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            if (written <= 0) return false;
            if (dest.exists() && !dest.delete()) {
                // A rename onto an existing file fails on some filesystems; losing the old copy is
                // fine, it is cache.
                FileLog.d("svipe-tme: could not replace " + dest.getName());
            }
            return tmp.renameTo(dest);
        } catch (Exception e) {
            FileLog.d("svipe-tme: image download failed: " + e);
            return false;
        } finally {
            try { if (tmp.exists()) tmp.delete(); } catch (Exception ignore) {}
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignore) {}
            }
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", BROWSER_UA);
        return conn;
    }

    private static String readBody(InputStream is) throws Exception {
        if (is == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024);
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
            if (bos.size() >= MAX_BODY_BYTES) break;
        }
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
