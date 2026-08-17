package org.telegram.svipe;

import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Svipe without an account: what somebody who has not signed in may see, and how they see it.
 *
 * <p><b>Why this is a separate client and not a flag on the rest.</b> Everything else in the app
 * reaches Telegram before it reaches us — a reel is resolved with {@code contacts.resolveUsername}
 * and downloaded with {@code FileLoader}, and both need an account's auth key. A guest has no
 * account, so none of that path exists for them. What exists instead is a plain HTTPS mp4 on
 * Telegram's public CDN, handed over by our backend, which any player can stream. Two different
 * routes to the same video; trying to express them as one would put "is this a guest" branches
 * through the whole reels stack.
 *
 * <p><b>The token grants nothing.</b> {@code POST /v1/guest/device} mints one carrying an opaque
 * device id and the claim {@code type: "guest"}; the server's user-token decoder refuses that type,
 * so this token cannot reach a single signed-in endpoint. It exists so a device can be rate-limited
 * and keep its place in the feed, not to identify anybody. It is kept in its own preferences file,
 * away from {@link SvipeConfig#PREF_TOKEN}, because those belong to a Telegram account and this
 * deliberately belongs to no one.
 *
 * <p><b>Only what a guest can actually play.</b> The server offers reels whose size is known and
 * under ~20 MB — above that the public embed exposes no mp4 at all, so a bigger card would be one
 * the guest taps and that can never play. The app does not need to enforce this; it needs to not
 * work around it.
 */
public class SvipeGuest {

    private static final String PREFS = "svipe_guest";
    private static final String PREF_DEVICE_ID = "device_id";
    private static final String PREF_TOKEN = "token";
    /** Re-mint well before the 30-day expiry — a token that dies mid-scroll reads as a broken app. */
    private static final String PREF_MINTED_AT = "minted_at";
    private static final long REMINT_AFTER_MS = 20L * 24 * 3600 * 1000;

    /** One reel as the guest surface sees it: drawable from this alone, playable after one more call. */
    public static class Item {
        public String code = "";
        public long channelId;
        public int messageId;
        public String username = "";
        public String title = "";
        public String caption = "";
        public String viewsText = "";
        public String durationText = "";
        public String posterUrl = "";
        public boolean portrait = true;
        /** Filled by {@link #media}; empty until then. Expires — resolve again rather than cache. */
        public String mediaUrl = "";
        public int width, height;

        /** The page a share opens: plays with no install, which is the whole point of sharing it. */
        public String shareUrl() {
            return SvipeConfig.baseUrl() + "/" + code;
        }
    }

    public interface FeedCallback {
        void onResult(List<Item> items, Integer nextCursor, String error);
    }

    public interface MediaCallback {
        void onResult(Item item, String error);
    }

    public interface TokenCallback {
        void run(String token);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    /** Has this device ever been registered? Used to decide whether the guest entry is resumable. */
    public static boolean hasDevice() {
        return prefs().getString(PREF_DEVICE_ID, "").length() > 0;
    }

    /** Forget the guest identity. Called after a real sign-in: the account replaces the device. */
    public static void forget() {
        try {
            prefs().edit().clear().apply();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static final Object LOCK = new Object();
    private static final ArrayList<TokenCallback> waiting = new ArrayList<>();
    private static boolean minting;

    /**
     * A usable guest token, minting one if needed. Single-flight: a cold guest start fires the feed
     * and the first media resolve at once, and two mints would race to write the device id.
     */
    public static void ensureToken(TokenCallback cb) {
        final SharedPreferences p = prefs();
        final String token = p.getString(PREF_TOKEN, "");
        final long mintedAt = p.getLong(PREF_MINTED_AT, 0);
        if (token.length() > 0 && System.currentTimeMillis() - mintedAt < REMINT_AFTER_MS) {
            cb.run(token);
            return;
        }
        synchronized (LOCK) {
            waiting.add(cb);
            if (minting) {
                return;
            }
            minting = true;
        }
        JSONObject body = new JSONObject();
        try {
            // Sent back so a returning guest keeps the same device — and therefore its place in the
            // feed. A device id the server does not like is simply replaced by one it minted.
            String known = p.getString(PREF_DEVICE_ID, "");
            if (known.length() > 0) body.put("device_id", known);
        } catch (Exception ignore) {
            // best-effort
        }
        SvipeApi.post("/v1/guest/device", body, null, (res, code, err) -> {
            String minted = res != null ? res.optString("token", "") : "";
            if (minted.length() > 0) {
                try {
                    p.edit()
                            .putString(PREF_TOKEN, minted)
                            .putString(PREF_DEVICE_ID, res.optString("device_id", ""))
                            .putLong(PREF_MINTED_AT, System.currentTimeMillis())
                            .apply();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            } else {
                FileLog.d("svipe: guest device mint failed (" + code + ") " + err);
            }
            final ArrayList<TokenCallback> release;
            synchronized (LOCK) {
                minting = false;
                release = new ArrayList<>(waiting);
                waiting.clear();
            }
            final String out = minted.length() > 0 ? minted : null;
            AndroidUtilities.runOnUIThread(() -> {
                for (TokenCallback w : release) {
                    try { w.run(out); } catch (Exception ignore) {}
                }
            });
        });
    }

    /**
     * A page of reels a guest may be shown. Empty is a legitimate answer, not a failure: the server
     * fails closed when its safety gate has nothing to say, and an empty page must draw an honest
     * end rather than a spinner.
     */
    public static void reels(int cursor, FeedCallback cb) {
        ensureToken(token -> {
            if (token == null) {
                cb.onResult(null, null, "no_guest_token");
                return;
            }
            SvipeApi.get("/v1/guest/reels?cursor=" + Math.max(0, cursor), token, (res, code, err) -> {
                if (res == null) {
                    cb.onResult(null, null, err != null ? err : ("http_" + code));
                    return;
                }
                final ArrayList<Item> out = new ArrayList<>();
                JSONArray arr = res.optJSONArray("items");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Item it = new Item();
                    it.code = o.optString("code", "");
                    it.channelId = o.optLong("channel_id");
                    it.messageId = o.optInt("message_id");
                    it.username = o.optString("username", "");
                    it.title = o.optString("title", "");
                    it.caption = o.optString("caption", "");
                    it.viewsText = o.optString("views_h", "");
                    it.durationText = o.optString("duration_h", "");
                    it.posterUrl = o.optString("poster", "");
                    it.portrait = o.optBoolean("portrait", true);
                    if (it.code.length() > 0) out.add(it);
                }
                Integer next = res.isNull("cursor") ? null : Integer.valueOf(res.optInt("cursor"));
                cb.onResult(out, next, null);
            });
        });
    }

    /**
     * Resolve one reel's playable URL, just before it is needed.
     *
     * <p>One at a time, and never for a whole page: the server resolves these by scraping the public
     * embed and rate-limits itself process-wide, so asking for eight up front turns into seconds of
     * queue. The right shape is the one a pager already wants — resolve what is on screen and the
     * one after it.
     *
     * <p>The URL is tokenised and expires. Callers must resolve again rather than remember it, which
     * is why nothing here writes it to disk.
     */
    public static void media(Item item, MediaCallback cb) {
        if (item == null || item.code.length() == 0) {
            cb.onResult(null, "no_code");
            return;
        }
        if (item.mediaUrl.length() > 0) {
            cb.onResult(item, null);
            return;
        }
        SvipeApi.get("/v1/guest/media/" + Uri.encode(item.code), null, (res, code, err) -> {
            if (res == null) {
                cb.onResult(null, err != null ? err : ("http_" + code));
                return;
            }
            item.mediaUrl = res.optString("url", "");
            item.width = res.optInt("width", 0);
            item.height = res.optInt("height", 0);
            if (item.mediaUrl.length() == 0) {
                // Not an error worth showing: over the ceiling, previews off, or the post is gone.
                // The pager skips it, which is the only sane thing to do with a card that cannot play.
                cb.onResult(null, "no_media");
                return;
            }
            cb.onResult(item, null);
        });
    }
}
