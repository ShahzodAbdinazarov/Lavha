package org.telegram.svipe.video;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A playable https URL for a PUBLIC post, minted on the device with no Telegram session at all.
 *
 * <p>{@code https://t.me/<username>/<id>?embed=1} is the public embed widget, and for a video post it
 * carries a direct, tokenised {@code cdn*.telesco.pe} URL that serves the file over ordinary HTTPS
 * with Range support. No session, no {@code access_hash}, no {@code contacts.resolveUsername}, and
 * nothing spent from the flood budget that this whole app depends on.
 *
 * <p><b>Why this lives here rather than on the backend.</b> The server used to scrape the same page
 * on a worker lane and store the result in {@code videos.play_url}. Measured on prod on 2026-08-18:
 * the lane filled 861 URLs in 24 h, 119 distinct videos were actually watched, and only 93 URLs were
 * still inside the token's ~3-hour life at any moment. Roughly seven URLs fetched per video watched,
 * and nine in ten expired before anyone could see them — because a three-hour cache cannot be kept
 * warm across millions of references by any background sweep, and does not need to be. Only the
 * twenty in front of a viewer matter, and only at the moment they are played. Fetched here, the URL
 * is minted seconds before it is used and the staleness problem simply does not exist.
 *
 * <p><b>The ceiling is real and it is low.</b> Measured against prod content on 2026-08-13: 8.3 MB
 * served, 18.7 MB served (as a 4.75 MB re-encode), 23 MB and up -> no mp4 at all. That is Telegram's
 * ~20 MB web-preview limit ({@link #CEILING_BYTES}). A reel is comfortably under it; long-form never
 * is. So a miss is ordinary, not an error — an over-ceiling video, a photo post, a channel with
 * previews off and a deleted post all land there — and it costs nothing but the fallback to MTProto.
 *
 * <p>Audio is NOT available this way and no amount of parsing changes that: audio posts render as a
 * document card with no file URL anywhere in the page. Only {@code <video src>} is parsed here.
 *
 * <p>Ported from the backend's {@code app/content/public_media.py}, which is where the markup, the
 * ceiling and the miss/failure distinction were all learned the hard way.
 */
public final class SvipePublicUrl {

    /** What a public post looks like once the embed page has been read. */
    public static class Media {
        public final String url;
        /** Pixel size derived from the widget's aspect box; 0 when the page did not say. */
        public final int width;
        public final int height;

        Media(String url, int width, int height) {
            this.url = url;
            this.width = width;
            this.height = height;
        }
    }

    /** Receives the media, or null when this post has none we can play. Always on the UI thread. */
    public interface Callback {
        void run(Media media);
    }

    /** What the embed widget will not serve. Callers with a document in hand can skip the fetch. */
    public static final long CEILING_BYTES = 20L * 1024 * 1024;

    /** The embed serves the {@code <video>} tag to browsers only. */
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";
    private static final Pattern VIDEO_SRC = Pattern.compile(
            "<video[^>]+\\bsrc=\"([^\"]+\\.mp4[^\"]*)\"", Pattern.CASE_INSENSITIVE);
    /** The widget frames the video in a box whose padding IS its aspect ratio. */
    private static final Pattern RATIO = Pattern.compile(
            "padding-top:\\s*([\\d.]+)%", Pattern.CASE_INSENSITIVE);

    /**
     * How long a minted URL may still be handed to the player.
     *
     * <p>One token measured on 2026-08-14 was still serving after 7 h 40 min, and the backend trusted
     * three hours of that. This trusts ten minutes, because the trade is not symmetric on a device:
     * a miss costs one 17 KB refetch of a page we know how to read, while a stale URL costs a reel
     * that starts and then dies in the viewer's hands.
     */
    private static final long HIT_TTL_MS = 10 * 60 * 1000L;
    /**
     * How long "this post has no public media" is remembered. Stable — over the ceiling today is
     * over the ceiling this evening — so it is worth remembering, but not for hours: the backend's
     * six-hour default is what pinned "unavailable" onto a live page while t.me was serving the mp4
     * perfectly (dev, 2026-08-14).
     */
    private static final long MISS_TTL_MS = 20 * 60 * 1000L;
    /**
     * How long a NETWORK failure is remembered. Deliberately short and deliberately not the same as
     * a miss: a timeout says nothing about the post, only about the last few seconds of the radio.
     */
    private static final long FAIL_TTL_MS = 30 * 1000L;

    /** Enough to cover the whole ahead-window plus everything the warm-up minted; ~17 KB per page. */
    private static final int CACHE_ENTRIES = 256;
    /** The embed page measured 17 KB. Anything an order of magnitude past that is not it. */
    private static final int MAX_BODY_BYTES = 512 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    private static class Answer {
        Media media;        // null = nothing to play
        boolean transient_; // the miss was a network failure, not an answer from t.me
        long atMs;
    }

    /** Access-ordered so the oldest key falls out first; every read and write holds this monitor. */
    private static final LinkedHashMap<String, Answer> cache =
            new LinkedHashMap<String, Answer>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Answer> eldest) {
                    return size() > CACHE_ENTRIES;
                }
            };
    /** Callers waiting on a fetch that is already in the air, keyed the same way as the cache. */
    private static final HashMap<String, ArrayList<Callback>> pending = new HashMap<>();

    /**
     * Three threads. The window this serves is small — the reel on screen plus the two or three
     * ahead of it — and single-flight already collapses the common duplicate, so the only thing more
     * threads would buy is contention for the same radio. Three is enough that a viewer's own reel
     * never queues behind the whole prefetch window. Daemon and below normal priority: this can
     * neither hold the process open nor outrank what is being drawn.
     *
     * <p>No artificial pacing, unlike the backend's copy of this: that one grinds a quarter-million
     * rows and must not look like an attack on t.me. A person watching reels asks for one page per
     * reel, which is exactly what their browser would do.
     */
    private static final java.util.concurrent.ExecutorService NET = buildPool();

    private static java.util.concurrent.ExecutorService buildPool() {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                3, 3, 30L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "svipe-embed");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY + 2);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private SvipePublicUrl() {}

    private static String keyOf(String username, int messageId) {
        if (username == null || username.isEmpty() || messageId <= 0) return null;
        return username.toLowerCase(Locale.US) + "/" + messageId;
    }

    private static long ttlOf(Answer e) {
        if (e.media != null) return HIT_TTL_MS;
        return e.transient_ ? FAIL_TTL_MS : MISS_TTL_MS;
    }

    /** A fresh answer we already hold, or null when we would have to go and ask. Never blocks. */
    public static Media cached(String username, int messageId) {
        String key = keyOf(username, messageId);
        if (key == null) return null;
        synchronized (cache) {
            Answer e = cache.get(key);
            if (e == null || System.currentTimeMillis() - e.atMs >= ttlOf(e)) return null;
            return e.media;
        }
    }

    /**
     * True when we KNOW this post has no playable public URL — t.me answered and the page had no
     * video. Callers use it to stop asking; a network failure deliberately does not count, since it
     * says nothing about the post.
     */
    public static boolean knownMiss(String username, int messageId) {
        String key = keyOf(username, messageId);
        if (key == null) return false;
        synchronized (cache) {
            Answer e = cache.get(key);
            return e != null && e.media == null && !e.transient_
                    && System.currentTimeMillis() - e.atMs < MISS_TTL_MS;
        }
    }

    /**
     * Mint a URL for a public post. The callback runs on the UI thread, with null when there is
     * nothing to play — which is a normal answer and never an error the caller must handle as one.
     *
     * <p>Idempotent and cheap to over-call: a fresh answer returns without touching the network, and
     * a caller arriving while the same post is already in the air rides along on that fetch instead
     * of sending its own.
     */
    public static void resolve(final String username, final int messageId, final Callback cb) {
        final String key = keyOf(username, messageId);
        if (key == null) {
            if (cb != null) AndroidUtilities.runOnUIThread(() -> cb.run(null));
            return;
        }
        Media hit = cached(username, messageId);
        if (hit != null) {
            if (cb != null) AndroidUtilities.runOnUIThread(() -> cb.run(hit));
            return;
        }
        synchronized (cache) {
            Answer e = cache.get(key);
            if (e != null && System.currentTimeMillis() - e.atMs < ttlOf(e)) {
                // A fresh miss. Answer it now rather than re-asking t.me for the same nothing.
                if (cb != null) AndroidUtilities.runOnUIThread(() -> cb.run(null));
                return;
            }
        }
        synchronized (pending) {
            ArrayList<Callback> waiters = pending.get(key);
            if (waiters != null) {
                if (cb != null) waiters.add(cb);
                return;
            }
            waiters = new ArrayList<>();
            if (cb != null) waiters.add(cb);
            pending.put(key, waiters);
        }
        submit(() -> fetch(key, username, messageId));
    }

    /** True while a fetch for this post is in the air, so a caller can wait instead of racing it. */
    public static boolean inFlight(String username, int messageId) {
        String key = keyOf(username, messageId);
        if (key == null) return false;
        synchronized (pending) {
            return pending.containsKey(key);
        }
    }

    /** Mint ahead of the viewer and keep the answer. Fire and forget — nobody is waiting on it. */
    public static void warm(String username, int messageId) {
        resolve(username, messageId, null);
    }

    private static void submit(Runnable work) {
        try {
            NET.execute(work);
        } catch (Throwable t) {
            // A rejected task must still run rather than vanish, or its waiters wait forever.
            FileLog.e(t);
            Utilities.globalQueue.postRunnable(work);
        }
    }

    private static void fetch(String key, String username, int messageId) {
        final long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        boolean failed = false;
        String html = null;
        try {
            URL url = new URL("https://t.me/" + username + "/" + messageId + "?embed=1");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", BROWSER_UA);
            conn.setRequestProperty("Accept", "text/html");
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                html = readBody(conn.getInputStream());
            } else {
                // 404 is a real answer about the post (deleted, or the channel went private), not a
                // network failure — remembered as a miss so the window stops asking.
                FileLog.d("svipe: embed " + key + " -> HTTP " + code);
            }
        } catch (Exception e) {
            failed = true;
            FileLog.d("svipe: embed " + key + " failed in " + (System.currentTimeMillis() - t0)
                    + "ms: " + e);
        } finally {
            // Not disconnect() on the happy path: HttpURLConnection keeps the socket alive for the
            // next post on the same host, and every reel in the window is on that same host.
            if (conn != null && failed) {
                try { conn.disconnect(); } catch (Exception ignore) {}
            }
        }
        Media media = html != null ? parse(html) : null;
        Answer e = new Answer();
        e.media = media;
        e.transient_ = failed;
        e.atMs = System.currentTimeMillis();
        synchronized (cache) {
            cache.put(key, e);
        }
        if (!failed) {
            FileLog.d("svipe: embed " + key + " -> " + (media != null ? "url" : "no video")
                    + " in " + (System.currentTimeMillis() - t0) + "ms");
        }
        drain(key, media);
    }

    /** Pull the media out of the embed page. Null when the widget did not offer any. */
    static Media parse(String html) {
        Matcher m = VIDEO_SRC.matcher(html);
        if (!m.find()) return null;
        // The src is an HTML attribute, so it arrives escaped. Telegram's tokens have not carried a
        // '&' so far, but a URL that plays only because of that is a URL waiting to break.
        String url = m.group(1).replace("&amp;", "&");
        int width = 0, height = 0;
        Matcher r = RATIO.matcher(html);
        if (r.find()) {
            try {
                float pct = Float.parseFloat(r.group(1));
                // The same sanity window the backend uses: a padding box outside it is some other
                // element's, not the video's.
                if (pct >= 40f && pct <= 320f) {
                    width = 720;
                    height = Math.max(1, Math.round(720 * pct / 100f));
                }
            } catch (NumberFormatException ignore) {}
        }
        return new Media(url, width, height);
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

    private static void drain(String key, Media media) {
        final ArrayList<Callback> waiters;
        synchronized (pending) {
            waiters = pending.remove(key);
        }
        if (waiters == null || waiters.isEmpty()) return;
        AndroidUtilities.runOnUIThread(() -> {
            for (int i = 0; i < waiters.size(); i++) {
                try { waiters.get(i).run(media); } catch (Exception e) { FileLog.e(e); }
            }
        });
    }
}
