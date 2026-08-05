package org.telegram.svipe;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Tell the server about ONE public video the user just opened in our player (POST /v1/videos/submit).
 *
 * <p>The twin of {@link SvipeChannelSync} one level down: that one says "this channel exists", this
 * one says "this VIDEO exists". Discovery by crawl budget can only reach what it already knows about,
 * while users run into public posts we have never seen every day — and a video someone is about to
 * watch is by definition one worth having in the index.
 *
 * <p><b>What is sent, and what is not.</b> A public channel's handle plus the post id, and nothing
 * else. Never a private chat, never a group, never a user, never any message content: the caller
 * ({@code SvipeVideoOpen}) makes that decision and only reaches this class for a post anyone can open
 * at {@code t.me/<handle>/<id>}. The server deliberately stores no submitter identity, exactly as
 * {@code /v1/channels/submit} does not — which is why this can run silently: the payload says "this
 * video exists", not "this person watched it".
 */
public final class SvipeVideoIndex {

    private static final String PREFS = "svipe_video_index";
    private static final String KEY_SENT = "sent_videos";

    /**
     * Cap on the "already submitted" memory. It exists so that re-opening the same video does not
     * re-post it, not to be authoritative — the server dedups anyway, so forgetting is harmless.
     */
    private static final int SENT_MEMORY_MAX = 2000;

    private SvipeVideoIndex() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    /**
     * Fire-and-forget: never blocks the caller, never shows anything. Opening a video is the user's
     * action and indexing it is ours, so a failure here is nothing they could act on — and nothing is
     * lost either, because the next person to open the same post submits it again.
     */
    public static void submit(int account, long channelId, int messageId, String username) {
        if (channelId == 0 || messageId <= 0 || username == null) {
            return;
        }
        // Locale.ROOT, not the device locale: a Turkish/Azeri phone lowercases 'I' to 'ı' (U+0131),
        // which the server's ^[a-z][a-z0-9_]{4,31}$ handle check then rejects — silently, for every
        // channel with an I in its name, on those devices only.
        final String handle = username.trim().replace("@", "").toLowerCase(java.util.Locale.ROOT);
        if (handle.isEmpty()) {
            return;
        }
        final String key = channelId + ":" + messageId;
        if (prefs().getStringSet(KEY_SENT, new HashSet<>()).contains(key)) {
            return;
        }
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                return;
            }
            post(channelId, messageId, handle, token, () -> remember(key));
        });
    }

    private static void post(long channelId, int messageId, String username, String token, Runnable onOk) {
        try {
            final JSONObject video = new JSONObject();
            video.put("channel_id", channelId);
            video.put("message_id", messageId);
            video.put("username", username);
            final JSONArray items = new JSONArray();
            items.put(video);
            final JSONObject body = new JSONObject();
            // A list of one, because the endpoint takes a batch: nothing here needs to change the day
            // some other surface submits several posts at once.
            body.put("items", items);
            SvipeApi.post("/v1/videos/submit", body, token, (res, code, err) -> {
                // Remembered only once the server says it TOOK the video, so a submit lost to an
                // outage is retried the next time this video is opened. The HTTP code alone is not
                // that answer: the endpoint answers 200 for a batch it rejected outright (a handle it
                // could not parse), and treating that as done would retire the video on this device
                // forever without a single row ever being queued.
                if (code >= 200 && code < 300 && res != null
                        && res.optInt("queued") + res.optInt("known") > 0) {
                    onOk.run();
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void remember(String key) {
        Set<String> sent = new HashSet<>(prefs().getStringSet(KEY_SENT, new HashSet<>()));
        sent.add(key);
        if (sent.size() > SENT_MEMORY_MAX) {
            sent = new HashSet<>(new ArrayList<>(sent).subList(0, SENT_MEMORY_MAX));
        }
        prefs().edit().putStringSet(KEY_SENT, sent).apply();
    }
}
