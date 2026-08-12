package org.telegram.svipe;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Report back what this device learned about a post, so the next person does not have to learn it.
 *
 * <p>Our index is written by one crawler account and sees a post once. A client that opens the post
 * gets the current truth for free — views, reactions, caption, whether the media was replaced,
 * whether the post is gone at all — as a side effect of playing it. Sending that home keeps the
 * catalog fresh at no cost to anyone.
 *
 * <p><b>This is the lowest-priority thing in the app, and the code is shaped to guarantee it.</b>
 * Nothing here is on a path the user waits for:
 *
 * <ul>
 *   <li>{@link #note} only writes to a map in memory — no I/O, no allocation of consequence, safe to
 *       call from the middle of playback;</li>
 *   <li>the batch is flushed only when the app has gone to the BACKGROUND, or after a long idle
 *       interval, never while something is being watched;</li>
 *   <li>the flush runs on a min-priority background thread, as ONE request, and its failure is
 *       silent — the batch is simply kept for the next opportunity;</li>
 *   <li>the queue is capped, and the oldest entries are dropped first. Losing observations is
 *       completely acceptable: another device will make the same one tomorrow.</li>
 * </ul>
 */
public final class SvipeObserved {

    private SvipeObserved() {
    }

    /** Beyond this the oldest observations are dropped — this is a trickle, not a log. */
    private static final int MAX_QUEUED = 200;
    /** The server takes this many per request; the rest waits for the next flush. */
    private static final int BATCH = 60;
    /** Idle flush cadence. Long on purpose: freshness here is measured in days, not minutes. */
    private static final long IDLE_FLUSH_MS = 20 * 60 * 1000L;
    /** Nothing is sent until the app has been quiet this long, so a flush never shares the pipe. */
    private static final long QUIET_MS = 15_000L;

    private static final String PREFS = "svipe_observed";
    private static final String KEY_QUEUE = "queue";

    /** channel:message -> the observation, newest write wins. Insertion-ordered so drops are FIFO. */
    private static final LinkedHashMap<String, JSONObject> pending = new LinkedHashMap<>();
    private static boolean scheduled;
    private static boolean sending;
    private static long lastActivityAt;

    /** Called from anywhere the user is actively doing something, so a flush can stay out of the way. */
    public static void touch() {
        lastActivityAt = System.currentTimeMillis();
    }

    /**
     * Record what a resolved post turned out to be. Cheap enough to call on every resolve.
     *
     * @param mo the real message from the channel, or the one built from a link preview — both are
     *           worth reporting, they just carry different fields
     */
    public static void note(int account, long channelId, int messageId, MessageObject mo) {
        if (mo == null || channelId == 0 || messageId <= 0) {
            return;
        }
        try {
            final JSONObject o = new JSONObject();
            o.put("channel_id", channelId);
            o.put("message_id", messageId);
            final TLRPC.Document doc = mo.getDocument();
            if (doc != null) {
                o.put("doc_id", doc.id);
                if (doc.size > 0) o.put("size", doc.size);
                for (int i = 0; i < doc.attributes.size(); i++) {
                    TLRPC.DocumentAttribute a = doc.attributes.get(i);
                    if (a instanceof TLRPC.TL_documentAttributeVideo) {
                        final TLRPC.TL_documentAttributeVideo v = (TLRPC.TL_documentAttributeVideo) a;
                        if (v.duration > 0) o.put("duration_ms", (int) (v.duration * 1000));
                        if (v.w > 0) o.put("width", v.w);
                        if (v.h > 0) o.put("height", v.h);
                    }
                }
            }
            final TLRPC.Message m = mo.messageOwner;
            if (m != null) {
                if (m.views > 0) o.put("views", m.views);
                if (m.forwards > 0) o.put("forwards", m.forwards);
                if (m.edit_date > 0) o.put("edit_date", m.edit_date);
                if (m.message != null && !m.message.isEmpty()) o.put("caption", m.message);
                if (m.reactions != null && m.reactions.results != null) {
                    int total = 0;
                    for (int i = 0; i < m.reactions.results.size(); i++) {
                        total += m.reactions.results.get(i).count;
                    }
                    if (total > 0) o.put("reactions", total);
                }
            }
            enqueue(account, channelId + ":" + messageId, o);
        } catch (Exception e) {
            FileLog.e(e);   // an observation is never worth an exception reaching a caller
        }
    }

    /** Record that a post could not be had at all — deleted, or its channel went private. */
    public static void noteGone(int account, long channelId, int messageId) {
        if (channelId == 0 || messageId <= 0) {
            return;
        }
        try {
            final JSONObject o = new JSONObject();
            o.put("channel_id", channelId);
            o.put("message_id", messageId);
            o.put("gone", true);
            enqueue(account, channelId + ":" + messageId, o);
        } catch (Exception ignore) {
        }
    }

    private static synchronized void enqueue(int account, String key, JSONObject o) {
        pending.remove(key);        // newest observation of the same post wins, and moves to the back
        pending.put(key, o);
        while (pending.size() > MAX_QUEUED) {
            final String oldest = pending.keySet().iterator().next();
            pending.remove(oldest);
        }
        schedule(account);
    }

    /** One pending idle timer, ever. It fires long after the user has stopped caring. */
    private static void schedule(final int account) {
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
     * Send one batch, if this is a good moment.
     *
     * @param background true when the app has just gone to the background — then there is nothing to
     *                   stay out of the way of, and the quiet check is skipped
     */
    public static synchronized void flush(final int account, final boolean background) {
        if (sending || pending.isEmpty()) {
            return;
        }
        if (!background && System.currentTimeMillis() - lastActivityAt < QUIET_MS) {
            schedule(account);   // somebody is watching something: try again much later
            return;
        }
        final ArrayList<JSONObject> batch = new ArrayList<>();
        for (Map.Entry<String, JSONObject> e : pending.entrySet()) {
            batch.add(e.getValue());
            if (batch.size() >= BATCH) break;
        }
        if (batch.isEmpty()) {
            return;
        }
        sending = true;
        final Thread t = new Thread(() -> {
            try {
                final JSONObject body = new JSONObject();
                body.put("items", new JSONArray(batch));
                // A token we already hold, never a fresh auth chain: this call is not worth waking
                // the bot flow for. No token -> put it back and wait for a moment when there is one.
                final String token = SvipeAuth.getStoredToken(account);
                if (token == null) {
                    synchronized (SvipeObserved.class) {
                        sending = false;
                    }
                    schedule(account);
                    return;
                }
                SvipeApi.post("/v1/media/observed", body, token, (res, code, err) -> {
                    synchronized (SvipeObserved.class) {
                        sending = false;
                        if (err == null && code >= 200 && code < 300) {
                            for (int i = 0; i < batch.size(); i++) {
                                pending.values().remove(batch.get(i));
                            }
                            FileLog.d("svipe: observed flushed " + batch.size()
                                    + ", queued " + pending.size());
                        }
                        // On failure the batch simply stays queued. There is no retry policy on
                        // purpose: the next flush IS the retry, twenty minutes from now.
                        persist();
                    }
                });
            } catch (Exception e) {
                synchronized (SvipeObserved.class) {
                    sending = false;
                }
            }
        }, "svipe-observed");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Keep the queue across a restart — an observation the app closed on is still worth sending. */
    public static synchronized void persist() {
        try {
            final JSONArray arr = new JSONArray();
            for (JSONObject o : pending.values()) {
                arr.put(o);
            }
            prefs().edit().putString(KEY_QUEUE, arr.toString()).apply();
        } catch (Exception ignore) {
        }
    }

    /** Reload what the last run could not send. Called once, from the app's own warm-up. */
    public static synchronized void restore() {
        try {
            final String raw = prefs().getString(KEY_QUEUE, null);
            if (raw == null || raw.isEmpty()) {
                return;
            }
            final JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.getJSONObject(i);
                pending.put(o.optLong("channel_id") + ":" + o.optInt("message_id"), o);
            }
        } catch (Exception ignore) {
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }
}
