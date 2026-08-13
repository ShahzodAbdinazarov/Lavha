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
    /** How many batches one background flush may send before it leaves the rest for next time. */
    private static final int MAX_BACKGROUND_BATCHES = 5;
    private static int drained;

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
    /**
     * The reference-aware entry point: an observation is queued only when the SERVER asked for one.
     *
     * <p>Everything the public web can read is read there, for the whole catalog. What is left is the
     * exact byte size and the document id, and one device answering that is enough for everybody —
     * so the server marks the references it still needs ({@code obs} on the feed item) and every
     * other device stays quiet. Before this, the thousandth device to open a post reported it too.
     */
    public static void note(int account, SvipeDiscover.Item ref, MessageObject mo) {
        if (ref == null || !ref.needsObserve) {
            return;
        }
        note(account, ref.channelId, ref.messageId, mo);
    }

    public static void note(int account, long channelId, int messageId, MessageObject mo) {
        note(account, channelId, messageId, mo, "video");
    }

    /**
     * @param kind which catalog this reference belongs to — {@code "video"} or {@code "music"}. The
     *             two live in different tables on the server and carry different fields: an audio
     *             document has the tags a track list shows, a video has dimensions and streaming.
     */
    public static void note(int account, long channelId, int messageId, MessageObject mo, String kind) {
        if (mo == null || channelId == 0 || messageId <= 0) {
            return;
        }
        // Music has no public web page to read from — an audio post renders as a card with no file
        // and no counters — so a music observation still carries everything it always did.
        final boolean music = "music".equals(kind);
        try {
            final JSONObject o = new JSONObject();
            o.put("kind", kind);
            o.put("channel_id", channelId);
            o.put("message_id", messageId);
            final TLRPC.Document doc = mo.getDocument();
            if (doc != null) {
                o.put("doc_id", doc.id);
                if (doc.size > 0) o.put("size", doc.size);
                if (doc.mime_type != null && !doc.mime_type.isEmpty()) o.put("mime_type", doc.mime_type);
                // The inline placeholder is reported for MUSIC only. For video the server reads it
                // off the channel's public web page for the whole catalog, continuously, instead of
                // waiting for somebody to watch something — and it was the largest thing in this
                // payload as well as the only part that cost the device any real work (base64 of
                // every post it saw).
                if (music) {
                    for (int i = 0; doc.thumbs != null && i < doc.thumbs.size(); i++) {
                        final TLRPC.PhotoSize ps = doc.thumbs.get(i);
                        if (ps instanceof TLRPC.TL_photoStrippedSize && ps.bytes != null
                                && ps.bytes.length > 0 && ps.bytes.length <= 2048) {
                            o.put("thumb_b64", android.util.Base64.encodeToString(
                                    ps.bytes, android.util.Base64.NO_WRAP));
                            break;
                        }
                    }
                }
                for (int i = 0; i < doc.attributes.size(); i++) {
                    TLRPC.DocumentAttribute a = doc.attributes.get(i);
                    if (a instanceof TLRPC.TL_documentAttributeVideo) {
                        final TLRPC.TL_documentAttributeVideo v = (TLRPC.TL_documentAttributeVideo) a;
                        if (v.duration > 0) o.put("duration_ms", (int) (v.duration * 1000));
                        if (v.w > 0) o.put("width", v.w);
                        if (v.h > 0) o.put("height", v.h);
                        o.put("supports_streaming", v.supports_streaming);
                    } else if (a instanceof TLRPC.TL_documentAttributeAudio) {
                        final TLRPC.TL_documentAttributeAudio au = (TLRPC.TL_documentAttributeAudio) a;
                        if (au.duration > 0) o.put("duration_ms", au.duration * 1000);
                        if (au.title != null && !au.title.isEmpty()) o.put("title", au.title);
                        if (au.performer != null && !au.performer.isEmpty()) o.put("performer", au.performer);
                    } else if (a instanceof TLRPC.TL_documentAttributeFilename) {
                        final String fn = ((TLRPC.TL_documentAttributeFilename) a).file_name;
                        if (fn != null && !fn.isEmpty()) o.put("file_name", fn);
                    }
                }
            }
            final TLRPC.Message m = mo.messageOwner;
            if (m != null) {
                // Views, reactions, the caption and the publish time are read off the channel's own
                // public page now — for every post, several times an hour, whether anyone watched it
                // or not. A device repeating them can only be older than what the server already has,
                // so it stops sending them. Forwards is the one counter the web does not show.
                if (m.forwards > 0) o.put("forwards", m.forwards);
                if (m.edit_date > 0) o.put("edit_date", m.edit_date);
                if (music) {
                    if (m.views > 0) o.put("views", m.views);
                    if (m.date > 0) o.put("posted_at", m.date);
                    if (m.message != null && !m.message.isEmpty()) o.put("caption", m.message);
                    if (m.reactions != null && m.reactions.results != null) {
                        int total = 0;
                        for (int i = 0; i < m.reactions.results.size(); i++) {
                            total += m.reactions.results.get(i).count;
                        }
                        if (total > 0) o.put("reactions", total);
                    }
                }
            }
            enqueue(account, channelId + ":" + messageId, o);
        } catch (Exception e) {
            FileLog.e(e);   // an observation is never worth an exception reaching a caller
        }
    }

    /**
     * Record what a resolved CHANNEL turned out to be — its name and how many people follow it.
     *
     * <p>Called from the one funnel every resolve passes through ({@link SvipeChannelResolve#remember}),
     * so no surface has to remember to do it. The avatar is deliberately not reported: a photo's
     * access_hash and file_reference are minted per account, so one device's copy is unusable to
     * anybody else — the name and the count are the parts that travel.
     */
    public static void noteChannel(int account, TLRPC.Chat chat) {
        if (chat == null || chat.id == 0 || chat.min) {
            return;   // a "min" chat carries a title but not a trustworthy one
        }
        try {
            final JSONObject o = new JSONObject();
            o.put("channel_id", chat.id);
            if (chat.title != null && !chat.title.isEmpty()) o.put("title", chat.title);
            if (chat.username != null && !chat.username.isEmpty()) o.put("username", chat.username);
            if (chat.participants_count > 0) o.put("subscribers", chat.participants_count);
            if (o.length() <= 1) {
                return;   // nothing but the id: not worth a row
            }
            enqueueChannel(account, chat.id, o);
        } catch (Exception ignore) {
        }
    }

    /** Record that a post could not be had at all — deleted, or its channel went private. */
    public static void noteGone(int account, long channelId, int messageId) {
        noteGone(account, channelId, messageId, "video");
    }

    public static void noteGone(int account, long channelId, int messageId, String kind) {
        if (channelId == 0 || messageId <= 0) {
            return;
        }
        try {
            final JSONObject o = new JSONObject();
            o.put("kind", kind);
            o.put("channel_id", channelId);
            o.put("message_id", messageId);
            o.put("gone", true);
            enqueue(account, channelId + ":" + messageId, o);
        } catch (Exception ignore) {
        }
    }

    /** Channel observations, same discipline as the post ones and flushed in the same request. */
    private static final LinkedHashMap<Long, JSONObject> pendingChannels = new LinkedHashMap<>();

    private static synchronized void enqueueChannel(int account, long channelId, JSONObject o) {
        pendingChannels.remove(channelId);
        pendingChannels.put(channelId, o);
        while (pendingChannels.size() > MAX_QUEUED) {
            pendingChannels.remove(pendingChannels.keySet().iterator().next());
        }
        schedule(account);
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
        if (sending || (pending.isEmpty() && pendingChannels.isEmpty())) {
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
        final ArrayList<JSONObject> channelBatch = new ArrayList<>();
        for (JSONObject o : pendingChannels.values()) {
            channelBatch.add(o);
            if (channelBatch.size() >= BATCH) break;
        }
        if (batch.isEmpty() && channelBatch.isEmpty()) {
            return;
        }
        sending = true;
        final Thread t = new Thread(() -> {
            try {
                final JSONObject body = new JSONObject();
                body.put("items", new JSONArray(batch));
                body.put("channels", new JSONArray(channelBatch));
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
                            for (int i = 0; i < channelBatch.size(); i++) {
                                pendingChannels.values().remove(channelBatch.get(i));
                            }
                            FileLog.d("svipe: observed flushed " + batch.size()
                                    + ", queued " + pending.size());
                            if (background && !pending.isEmpty() && drained < MAX_BACKGROUND_BATCHES) {
                                // Nothing is competing for the connection now, and a queue that only
                                // ever drains 60 at a time takes days to catch up after a busy
                                // session. Keep going while the app is away, but bounded.
                                drained++;
                                flush(account, true);
                            } else {
                                drained = 0;
                            }
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
