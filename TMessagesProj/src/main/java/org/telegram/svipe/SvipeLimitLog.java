package org.telegram.svipe;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;

/**
 * The ledger of Telegram rate limits <b>Svipe spends out of the user's own account</b>.
 *
 * <p>Everything this app plays is a public post, but it is played through the session of the person
 * holding the phone. So every reference we turn into a real message costs a rate-limited MTProto
 * call on THEIR account — and when it runs out the damage is not confined to us: Telegram answers
 * FLOOD_WAIT in hours and, until it expires, nothing resolves for that person anywhere, in any
 * client. Measured on real accounts: FLOOD_WAIT_13101 (three and a half hours), FLOOD_WAIT_22135 on
 * an indexer account.
 *
 * <p>We already defend against that ({@link SvipeChannelResolve} paces, budgets and remembers the
 * window; the sessionless paths avoid the call entirely). What was missing is the bill: which calls
 * we actually made on someone's behalf, when, why, and what Telegram said. That is what this class
 * records and ships home — one row per call, read one user at a time on their admin page.
 *
 * <p><b>Cost discipline is identical to {@link SvipeObserved}</b>, and for the same reason: an
 * accounting layer that slows down what it accounts for is worse than no accounting.
 * {@link #note} only touches memory; the queue is persisted on a debounce (immediately for a flood,
 * which is rare and is the row nobody may lose); the upload happens when the app has gone to the
 * background or after a long idle, on a min-priority thread, as one request, and its failure is
 * silent — the rows simply wait for the next chance.
 *
 * <p><b>What is recorded is the call, not the user.</b> A subject is a PUBLIC channel handle (or an
 * id when that is all we had) — the same string that is already in the feed we served. A private
 * chat is never a subject here, because the app never resolves one.
 */
public final class SvipeLimitLog {

    private SvipeLimitLog() {
    }

    // ---- methods, as Telegram writes them -------------------------------------------------------

    public static final String RESOLVE_USERNAME = "contacts.resolveUsername";
    public static final String RESOLVE_PHONE = "contacts.resolvePhone";
    public static final String GET_MESSAGES = "channels.getMessages";
    public static final String GET_WEB_PAGE = "messages.getWebPage";

    // ---- reasons: what the app was doing FOR the user when it spent the call ---------------------

    public static final String REEL_PLAY = "reel_play";
    public static final String VIDEO_PLAY = "video_play";
    public static final String MUSIC_PLAY = "music_play";
    public static final String GRID_TILE = "grid_tile";
    public static final String WATCH_GROUP = "watch_group";
    public static final String AUTH_BOT = "auth_bot";
    public static final String PHONE_LOOKUP = "phone_lookup";
    public static final String WEB_REF = "web_ref";
    public static final String RAIL_ENRICH = "rail_enrich";

    // ---- outcomes --------------------------------------------------------------------------------

    public static final String OK = "ok";
    /** Telegram answered 420: the account now waits, and so does everything else it does. */
    public static final String FLOOD = "flood";
    /** Our own gate refused: a flood window is still open. Recorded because a call we did NOT make
     *  is the clearest evidence the user is sitting at a limit — counting only successes would make
     *  the worst moment the one moment with no row. */
    public static final String BLOCKED = "blocked";
    /** The hourly budget in {@link SvipeChannelResolve} was spent. Same reasoning as BLOCKED. */
    public static final String BUDGET = "budget";
    public static final String ERROR = "error";

    /** Beyond this the oldest rows go. A ledger this long already tells the story. */
    private static final int MAX_QUEUED = 1000;
    /** Rows per upload; the rest waits for the next one. */
    private static final int BATCH = 200;
    /** Idle upload cadence. Long on purpose — nothing here is urgent to anybody. */
    private static final long IDLE_FLUSH_MS = 10 * 60 * 1000L;
    /** Writes to disk are coalesced this far apart, so a burst of resolves is one write, not twenty. */
    private static final long PERSIST_DEBOUNCE_MS = 5_000L;

    private static final String PREFS = "svipe_limit_log";
    private static final String KEY_QUEUE = "queue";

    private static final ArrayDeque<JSONObject> pending = new ArrayDeque<>();

    private static boolean scheduled;
    private static boolean sending;
    private static boolean persistScheduled;
    private static boolean restored;

    /**
     * Record one rate-limited call we made (or refused to make) on the user's account.
     *
     * @param method  the MTProto method, e.g. {@link #RESOLVE_USERNAME}
     * @param reason  why the app needed it, e.g. {@link #REEL_PLAY}
     * @param outcome {@link #OK}, {@link #FLOOD}, {@link #BLOCKED}, {@link #BUDGET} or {@link #ERROR}
     * @param waitSeconds seconds Telegram told the account to wait, 0 when it did not
     * @param errorText   Telegram's own error string, or null — never anything the user typed
     * @param subject a PUBLIC channel handle, or {@code id:<n>}; null when there is nothing public
     * @param surface where in the app this happened: {@code reels}, {@code video}, {@code music}…
     */
    public static void note(int account, String method, String reason, String outcome,
                            int waitSeconds, String errorText, String subject, String surface) {
        final JSONObject o = row(method, reason, outcome, waitSeconds, errorText, subject, surface,
                System.currentTimeMillis());
        if (o == null) {
            return;
        }
        try {
            enqueue(account, o, FLOOD.equals(outcome));
        } catch (Exception ignore) {
        }
    }

    /**
     * Build the row that goes on the wire. Pure — no Android, no clock of its own — because this is
     * the part with rules in it (what is trimmed, what is dropped, how a time is written) and the
     * rules are what a test can hold still.
     *
     * @param atMs when the call happened; stamped as UTC ISO, seconds precision
     * @return the row, or null when there is nothing worth recording
     */
    static JSONObject row(String method, String reason, String outcome, int waitSeconds,
                          String errorText, String subject, String surface, long atMs) {
        if (method == null || reason == null || method.isEmpty() || reason.isEmpty()) {
            return null;
        }
        try {
            final JSONObject o = new JSONObject();
            o.put("method", method);
            o.put("reason", reason);
            o.put("outcome", outcome == null || outcome.isEmpty() ? OK : outcome);
            // A wait is only ever the size of the damage. Past a day it is a clock that jumped, and
            // the server would refuse the whole batch for it — so it is clamped here, not there.
            if (waitSeconds > 0) o.put("wait_s", Math.min(waitSeconds, 86_400));
            if (errorText != null && !errorText.isEmpty()) {
                o.put("error_text", errorText.length() > 64 ? errorText.substring(0, 64) : errorText);
            }
            if (subject != null && !subject.isEmpty()) {
                o.put("subject", subject.length() > 64 ? subject.substring(0, 64) : subject);
            }
            if (surface != null && !surface.isEmpty()) o.put("surface", surface);
            // The moment of the CALL, in UTC seconds-precision ISO — the upload happens hours later
            // and a row stamped at upload time would answer the wrong question entirely.
            o.put("client_ts", iso(atMs));
            return o;
        } catch (Exception ignore) {
            return null;
        }
    }

    /** A call that went out and came back clean. */
    public static void ok(int account, String method, String reason, String subject, String surface) {
        note(account, method, reason, OK, 0, null, subject, surface);
    }

    /**
     * A call that failed. Classifies itself: a 420 is the row that matters, anything else is an
     * ordinary error and is recorded as one — conflating the two would make every dead handle look
     * like a limit.
     */
    public static void failed(int account, String method, String reason, TLRPC.TL_error error,
                              String subject, String surface) {
        final String text = error == null ? null : error.text;
        note(account, method, reason, outcomeFor(text), waitFor(text), text, subject, surface);
    }

    /** A 420 is the row that matters; anything else is an ordinary error. Conflating the two would
     *  make every dead handle look like a limit. */
    static String outcomeFor(String errorText) {
        return waitFor(errorText) > 0 ? FLOOD : ERROR;
    }

    static int waitFor(String errorText) {
        return errorText == null ? 0 : SvipeFloodWait.secondsIn(errorText);
    }

    /**
     * A call we did NOT make because our own gate stopped it — the window is open, or the hourly
     * budget is gone. The user is paying for it all the same: this is the moment the app stops
     * working for them.
     */
    public static void denied(int account, String method, String reason, boolean budget,
                              int secondsLeft, String subject, String surface) {
        note(account, method, reason, budget ? BUDGET : BLOCKED, secondsLeft, null, subject, surface);
    }

    /** The handle for a subject, or the id when the reference did not carry one. */
    public static String subject(String username, long channelId) {
        if (username != null && !username.isEmpty()) {
            return username;
        }
        return channelId != 0 ? "id:" + channelId : null;
    }

    private static String iso(long ms) {
        final java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    private static synchronized void enqueue(int account, JSONObject o, boolean urgentPersist) {
        pending.addLast(o);
        while (pending.size() > MAX_QUEUED) {
            pending.pollFirst();
        }
        if (urgentPersist) {
            persist();      // a flood is the one row that must survive the app dying right after it
        } else {
            schedulePersist();
        }
        scheduleFlush(account);
    }

    private static void schedulePersist() {
        if (persistScheduled) {
            return;
        }
        persistScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            persistScheduled = false;
            persist();
        }, PERSIST_DEBOUNCE_MS);
    }

    /** One pending idle timer, ever. */
    private static void scheduleFlush(final int account) {
        if (scheduled) {
            return;
        }
        scheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            scheduled = false;
            flush(account, false);
        }, IDLE_FLUSH_MS);
    }

    /**
     * Send one batch.
     *
     * @param background true when the app has just left the foreground — then there is nothing to
     *                   stay out of the way of
     */
    public static synchronized void flush(final int account, final boolean background) {
        if (sending || pending.isEmpty()) {
            return;
        }
        final ArrayList<JSONObject> batch = new ArrayList<>();
        for (JSONObject o : pending) {
            batch.add(o);
            if (batch.size() >= BATCH) break;
        }
        sending = true;
        final Thread t = new Thread(() -> {
            try {
                final JSONObject body = new JSONObject();
                final JSONObject client = new JSONObject();
                try {
                    final android.content.Context ctx = ApplicationLoader.applicationContext;
                    final android.content.pm.PackageInfo pi =
                            ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                    client.put("app_build", android.os.Build.VERSION.SDK_INT >= 28
                            ? (int) pi.getLongVersionCode() : pi.versionCode);
                } catch (Exception ignore) {
                }
                body.put("client", client);
                body.put("touches", new JSONArray(batch));
                // A token we already hold, never a fresh auth chain: the ledger is not worth waking
                // the bot flow for — and waking it would itself spend a resolve.
                final String token = SvipeAuth.getStoredToken(account);
                if (token == null) {
                    synchronized (SvipeLimitLog.class) {
                        sending = false;
                    }
                    scheduleFlush(account);
                    return;
                }
                SvipeApi.post("/v1/limits/touch", body, token, (res, code, err) -> {
                    synchronized (SvipeLimitLog.class) {
                        sending = false;
                        if (err == null && code >= 200 && code < 300) {
                            pending.removeAll(batch);
                            FileLog.d("svipe: limit ledger sent " + batch.size()
                                    + ", queued " + pending.size());
                            persist();
                            if (background && !pending.isEmpty()) {
                                flush(account, true);
                            }
                        } else {
                            // No retry policy on purpose: the next flush IS the retry. The rows stay.
                            persist();
                        }
                    }
                });
            } catch (Exception e) {
                synchronized (SvipeLimitLog.class) {
                    sending = false;
                }
            }
        }, "svipe-limit-log");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Keep the ledger across a restart — a call already paid for is still a call that was paid. */
    public static synchronized void persist() {
        try {
            final JSONArray arr = new JSONArray();
            for (JSONObject o : pending) {
                arr.put(o);
            }
            prefs().edit().putString(KEY_QUEUE, arr.toString()).apply();
        } catch (Exception ignore) {
        }
    }

    /** Reload what the last run could not send. Called once, from the app's own warm-up. */
    public static synchronized void restore() {
        if (restored) {
            return;
        }
        restored = true;
        try {
            final String raw = prefs().getString(KEY_QUEUE, null);
            if (raw == null || raw.isEmpty()) {
                return;
            }
            final JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                pending.addLast(arr.getJSONObject(i));
            }
            while (pending.size() > MAX_QUEUED) {
                pending.pollFirst();
            }
        } catch (Exception ignore) {
        }
    }

    /** How many calls are waiting to be reported — the diagnostic, and what the tests read. */
    public static synchronized int queued() {
        return pending.size();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }
}
