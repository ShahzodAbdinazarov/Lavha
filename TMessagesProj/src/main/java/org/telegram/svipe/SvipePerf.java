package org.telegram.svipe;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.StatsController;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.List;

/**
 * How fast the app felt, measured where it is actually felt — on the device.
 *
 * Until now the only record of a slow first frame was a log line on whichever phone happened to be
 * plugged into a laptop. That makes every performance decision an anecdote: we cannot tell whether a
 * change helped, or whether "it's slow" means 400ms for most people and 6s for someone on a 3G link
 * in another country. Samples are buffered on disk and uploaded in batches to {@code /v1/perf}.
 *
 * Rules, in the order they matter:
 *   - <b>never cost what it measures.</b> Recording is a couple of field writes on the caller's
 *     thread and nothing else; persistence and upload happen on a background queue, later.
 *   - <b>never send on the critical path.</b> Uploads wait for a full-ish batch or a quiet moment,
 *     and a failure just leaves the samples on disk for next time.
 *   - <b>bounded.</b> A device that never gets a successful upload must not grow a file forever, so
 *     the buffer is capped and the oldest samples are the ones dropped.
 *   - <b>no personal data.</b> The device tells us what kind of device it is and what kind of link
 *     it is on. Country is derived on the server from the connection; location is never read.
 */
public final class SvipePerf {

    /** Upload once this many samples have piled up — one radio wake-up for twenty measurements. */
    private static final int BATCH_SIZE = 20;
    /** …or when this long has passed with something waiting, so a quiet user still reports. */
    private static final long FLUSH_INTERVAL_MS = 5 * 60 * 1000L;
    /** Hard cap on what we keep. Beyond this the oldest samples go: fresh data is worth more. */
    private static final int MAX_BUFFERED = 300;
    /** Per-request cap, matching the backend's. */
    private static final int MAX_PER_UPLOAD = 200;
    /** After a failed upload, wait at least this long before trying again. */
    private static final long RETRY_BACKOFF_MS = 60 * 1000L;

    private static final Object LOCK = new Object();
    private static final List<JSONObject> buffer = new ArrayList<>();
    private static boolean loaded;
    private static boolean uploading;
    private static boolean heartbeatStarted;
    private static long nextAllowedFlushMs;

    private SvipePerf() {}

    // ---- recording ----

    /**
     * One measurement. Built with a tiny builder rather than a ten-argument call because these are
     * written inside dense playback code, where an unreadable call site is a bug waiting to happen.
     */
    public static final class Sample {
        private final String metric;
        private final long valueMs;
        private String surface;
        private String context;
        private String source;
        private Boolean prepared;
        private Integer rungHeight;
        private Boolean hadLadder;
        private JSONObject extra;

        private Sample(String metric, long valueMs) {
            this.metric = metric;
            this.valueMs = valueMs;
        }

        public Sample surface(String v) { surface = v; return this; }
        public Sample context(String v) { context = v; return this; }
        public Sample source(String v) { source = v; return this; }
        public Sample prepared(boolean v) { prepared = v; return this; }
        public Sample rung(int heightPx) { rungHeight = heightPx; return this; }
        public Sample ladder(boolean v) { hadLadder = v; return this; }

        public Sample extra(String key, Object value) {
            try {
                if (extra == null) extra = new JSONObject();
                extra.put(key, value);
            } catch (Exception ignore) {}
            return this;
        }

        /** Hand the sample over. Returns immediately; everything after this is someone else's turn. */
        public void submit(int accountId) {
            SvipePerf.submit(accountId, this);
        }
    }

    public static Sample sample(String metric, long valueMs) {
        return new Sample(metric, valueMs);
    }

    private static void submit(int accountId, Sample s) {
        // A measurement that took longer than an hour is a device that slept mid-measurement, not a
        // slow app; the backend refuses those anyway, so don't spend a row on it.
        if (s.metric == null || s.valueMs < 0 || s.valueMs > 3_600_000L) return;
        final JSONObject row = new JSONObject();
        try {
            row.put("metric", s.metric);
            row.put("value_ms", s.valueMs);
            if (s.surface != null) row.put("surface", s.surface);
            if (s.context != null) row.put("context", s.context);
            if (s.source != null) row.put("source", s.source);
            if (s.prepared != null) row.put("prepared", s.prepared.booleanValue());
            if (s.rungHeight != null) row.put("rung_height", s.rungHeight.intValue());
            if (s.hadLadder != null) row.put("had_ladder", s.hadLadder.booleanValue());
            if (s.extra != null) row.put("extra", s.extra);
            row.put("network", networkName());
            row.put("client_ts", nowIso());
        } catch (Exception e) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            boolean flushNow;
            synchronized (LOCK) {
                ensureLoadedLocked(accountId);
                buffer.add(row);
                while (buffer.size() > MAX_BUFFERED) {
                    buffer.remove(0);
                }
                persistLocked(accountId);
                flushNow = buffer.size() >= BATCH_SIZE;
            }
            if (flushNow) flush(accountId);
        });
    }

    // ---- flushing ----

    /**
     * Upload whatever is waiting. Called on a timer, when a batch fills up, and once at app start so
     * a session that ended offline still reports. Safe to call at any time from any thread.
     */
    public static void flush(final int accountId) {
        Utilities.globalQueue.postRunnable(() -> flushInternal(accountId));
    }

    private static void flushInternal(final int accountId) {
        final JSONArray events = new JSONArray();
        final int taken;
        synchronized (LOCK) {
            ensureLoadedLocked(accountId);
            if (uploading || buffer.isEmpty()) return;
            if (System.currentTimeMillis() < nextAllowedFlushMs) return;
            uploading = true;
            taken = Math.min(buffer.size(), MAX_PER_UPLOAD);
            for (int i = 0; i < taken; i++) {
                events.put(buffer.get(i));
            }
        }
        final JSONObject body = new JSONObject();
        try {
            body.put("client", deviceFacts());
            body.put("events", events);
        } catch (Exception e) {
            synchronized (LOCK) { uploading = false; }
            return;
        }
        SvipeAuth.ensureToken(accountId, token -> {
            if (token == null) {
                // No token today (a flood window, no network, a fresh install). The samples stay on
                // disk — losing them here would silently blind us to exactly the sessions that were
                // having the worst time.
                synchronized (LOCK) {
                    uploading = false;
                    nextAllowedFlushMs = System.currentTimeMillis() + RETRY_BACKOFF_MS;
                }
                return;
            }
            SvipeApi.post("/v1/perf", body, token, (res, code, err) -> {
                final boolean ok = code >= 200 && code < 300;
                synchronized (LOCK) {
                    uploading = false;
                    if (ok) {
                        for (int i = 0; i < taken && !buffer.isEmpty(); i++) {
                            buffer.remove(0);
                        }
                        persistLocked(accountId);
                    } else {
                        // 4xx would repeat forever, so drop the batch rather than wedge the buffer;
                        // anything else is transient and worth keeping.
                        if (code >= 400 && code < 500) {
                            FileLog.d("svipe: perf batch rejected (" + code + "), dropping " + taken);
                            for (int i = 0; i < taken && !buffer.isEmpty(); i++) {
                                buffer.remove(0);
                            }
                            persistLocked(accountId);
                        }
                        nextAllowedFlushMs = System.currentTimeMillis() + RETRY_BACKOFF_MS;
                    }
                }
                if (ok) FileLog.d("svipe: perf uploaded " + taken + " samples");
            });
        });
    }

    /**
     * Start the slow heartbeat that drains the buffer for a user who is not producing new samples
     * (they left the app on the chat list). Idempotent; call it once per process.
     */
    public static void start(final int accountId) {
        synchronized (LOCK) {
            if (heartbeatStarted) return;
            heartbeatStarted = true;
        }
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                flush(accountId);
                AndroidUtilities.runOnUIThread(this, FLUSH_INTERVAL_MS);
            }
        }, FLUSH_INTERVAL_MS);
    }

    // ---- device + environment ----

    private static JSONObject deviceFacts() {
        JSONObject c = new JSONObject();
        try {
            Context ctx = ApplicationLoader.applicationContext;
            c.put("client_level", SvipeApi.CLIENT_LEVEL);
            c.put("android_sdk", Build.VERSION.SDK_INT);
            String model = (Build.MANUFACTURER + " " + Build.MODEL).trim();
            if (model.length() > 64) model = model.substring(0, 64);
            c.put("device_model", model);
            if (ctx != null) {
                try {
                    PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                    c.put("app_build", Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode);
                } catch (Exception ignore) {}
                try {
                    ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                    if (am != null) {
                        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                        am.getMemoryInfo(mi);
                        c.put("ram_mb", (int) (mi.totalMem / (1024L * 1024L)));
                    }
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        return c;
    }

    /** The kind of link, from Telegram's own detection — no extra permission, no radio poll. */
    private static String networkName() {
        try {
            if (!ApplicationLoader.isNetworkOnline()) return "offline";
            switch (ApplicationLoader.getAutodownloadNetworkType()) {
                case StatsController.TYPE_WIFI: return "wifi";
                case StatsController.TYPE_ROAMING: return "roaming";
                default: return "mobile";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** UTC ISO-8601, because a client clock in a local zone is unreadable next to server_ts. */
    private static String nowIso() {
        try {
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return fmt.format(new java.util.Date());
        } catch (Exception e) {
            return null;
        }
    }

    // ---- persistence (buffer survives a kill; an unsent sample is still worth having) ----

    private static SharedPreferences prefs(int accountId) {
        return MessagesController.getMainSettings(accountId);
    }

    private static void ensureLoadedLocked(int accountId) {
        if (loaded) return;
        loaded = true;
        try {
            String blob = prefs(accountId).getString(SvipeConfig.PREF_PERF_BUFFER, null);
            if (blob == null || blob.isEmpty()) return;
            JSONArray arr = new JSONArray(blob);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) buffer.add(o);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void persistLocked(int accountId) {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : buffer) arr.put(o);
            prefs(accountId).edit().putString(SvipeConfig.PREF_PERF_BUFFER, arr.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
