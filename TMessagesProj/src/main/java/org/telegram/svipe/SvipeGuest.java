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
    /** SHA-256 of the App Set ID: stable across a reinstall, and not the id itself. */
    private static final String PREF_STABLE_ID = "stable_id";
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
        /** Length in ms as the server knows it. The watch classifier needs it for a reel that is
         *  playing over its public URL and has never been resolved. */
        public int durationMs;
        /** The playable URL. Arrives WITH the page when the server holds a warm one, otherwise
         *  filled by {@link #media} just before the reel is needed. Expires — resolve again rather
         *  than cache it anywhere that outlives the page. */
        public String mediaUrl = "";
        public int width, height;

        /** The page a share opens: plays with no install, which is the whole point of sharing it. */
        public String shareUrl() {
            return SvipeConfig.baseUrl() + "/" + code;
        }
    }

    /**
     * A page fetched before anybody asked for it, held for the screen that will.
     *
     * <p>The guest feed is the first thing a stranger sees, and every millisecond of it is spent
     * before they have any reason to be patient. Two round-trips stand between the app process
     * starting and the first pixel: mint a device token, then ask for a page with it — strictly
     * serial, because the page needs the token. Both can happen while the UI is still being built.
     *
     * <p>Measured warm on dev: 347 ms for the token and 857 ms for the page, so this is most of a
     * second removed from the only wait a first-time visitor experiences.
     */
    public static class Warm {
        public List<Item> items;
        public Integer next;
    }

    private static boolean warmStarted, warmSettled;
    private static long warmedAtMs;
    private static Warm warm;
    private static final ArrayList<WarmCallback> warmWaiters = new ArrayList<>();

    public interface WarmCallback {
        /** The warmed page, or null when there is none and the caller should fetch for itself. */
        void run(Warm warm);
    }

    /** A held page goes stale rather than being served indefinitely — the feed rotates. */
    private static final long WARM_TTL_MS = 5 * 60 * 1000L;

    /**
     * Start warming. Safe to call more than once and from any thread; the first call wins.
     *
     * <p>Deliberately gated on there being no account: for a signed-in user this would mint a guest
     * token they will never use and ask for a feed they will never see.
     */
    public static void warmUp() {
        if (warmStarted) {
            return;
        }
        warmStarted = true;
        reels(0, (result, next, error) -> {
            if (result != null && !result.isEmpty()) {
                Warm w = new Warm();
                w.items = result;
                w.next = next;
                warm = w;
                warmedAtMs = System.currentTimeMillis();
                FileLog.d("svipe-g: warm-up holds " + result.size() + " reels");
            }
            settleWarm();
        });
    }

    /**
     * Take the warmed page, once — WAITING for it if the warm-up is still in flight.
     *
     * <p>Waiting is the point. The first version answered null while the warm-up was mid-request, so
     * the screen fired its own and the same page was fetched twice: two requests, and the screen
     * still waiting on the slower one. A warm-up that is already asking the question is the reason
     * not to ask it again.
     *
     * <p>Null still means "fetch for yourself": no warm-up ran, it came back empty, or what it holds
     * has gone stale.
     */
    public static void takeWarm(WarmCallback cb) {
        if (!warmStarted || warmSettled) {
            cb.run(takeFresh());
            return;
        }
        synchronized (warmWaiters) {
            if (!warmSettled) {
                warmWaiters.add(cb);
                return;
            }
        }
        cb.run(takeFresh());
    }

    private static Warm takeFresh() {
        Warm w = warm;
        warm = null;
        if (w == null || System.currentTimeMillis() - warmedAtMs > WARM_TTL_MS) {
            return null;
        }
        return w;
    }

    private static void settleWarm() {
        final ArrayList<WarmCallback> release;
        synchronized (warmWaiters) {
            warmSettled = true;
            release = new ArrayList<>(warmWaiters);
            warmWaiters.clear();
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (WarmCallback cb : release) {
                try { cb.run(takeFresh()); } catch (Exception ignore) {}
            }
        });
    }

    /**
     * Tell the server what was watched. This is the only reason the guest feed can adapt at all.
     *
     * <p>The same event shapes a signed-in client sends, into the same ingestion path on the server:
     * the reward reaches the same bandit and the same session wave, and what was shown is remembered
     * so it is not shown again. A guest surface that reported nothing would be a recommender with no
     * input — a fixed shelf wearing a recommender's name.
     *
     * <p>Fire and forget. Nothing on screen waits for it, and a lost event costs a little learning,
     * never a frame.
     */
    public static void report(String eventType, Item item, long watchedMs, long durationMs) {
        if (item == null || eventType == null) {
            return;
        }
        ensureToken(token -> {
            if (token == null) {
                return;
            }
            try {
                JSONObject payload = new JSONObject();
                payload.put("watch_ms", Math.max(0, watchedMs));
                payload.put("duration_ms", Math.max(0, durationMs));
                JSONObject ev = new JSONObject();
                ev.put("channel_id", item.channelId);
                ev.put("message_id", item.messageId);
                ev.put("event_type", eventType);
                ev.put("payload", payload);
                JSONObject body = new JSONObject();
                body.put("events", new JSONArray().put(ev));
                SvipeApi.post("/v1/guest/events", body, token, (res, code, err) -> {});
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
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

    /**
     * The id this device is known by, resolved once and remembered.
     *
     * <p><b>Why not just keep the random one.</b> The device id we mint ourselves lives in this app's
     * preferences, so uninstalling — or "clear data" — throws away everything a guest taught the feed.
     * The App Set ID does not: it is per device and per developer account, it needs no permission,
     * and Google offers it for exactly this (analytics and abuse prevention inside one developer's
     * apps). It resets when the user removes every app of ours or factory-resets, which is the right
     * escape hatch to leave them.
     *
     * <p><b>Why the hash.</b> What reaches our server is SHA-256 of the App Set ID, never the id
     * itself. We need something stable, not something identifying, and hashing means a leak of our
     * database cannot be joined against anybody else's copy of the same identifier. The shape also
     * matches what the server already validates: hex, 8..64 characters.
     *
     * <p>Best-effort throughout. No Play services, an old device, a user who denied it — any of those
     * and the random id stands, which is exactly what shipped before this existed.
     */
    private static void resolveStableId(final Runnable done) {
        // ANDROID_ID first. It is the ONLY identifier that survives this app being uninstalled and
        // installed again — App Set ID resets when every app from a developer is gone, and Svipe is
        // our only app on a device, so for us "uninstall" is always "every app" (measured: the App Set
        // ID changed across a reinstall, and held across a clear-data).
        //
        // WHAT IT IS USED FOR, and nothing else: a guest has no account, so the recommender has no
        // one to attribute a taste to. This id IS that identity — the user id of somebody who has not
        // registered. It is not joined to a phone number, a Telegram account, an advertising id or
        // any other identifier, and the moment a guest signs in the account replaces it entirely.
        //
        // Hashed before it leaves the device, so what we store is stable but not the identifier: our
        // database cannot be joined against anybody else's copy of the same value.
        try {
            String ssaid = android.provider.Settings.Secure.getString(
                    ApplicationLoader.applicationContext.getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID);
            // "9774d56d682e549c" is the famous broken value some devices share; treat it as absent.
            if (ssaid != null && ssaid.length() >= 8 && !"9774d56d682e549c".equals(ssaid)) {
                prefs().edit().putString(PREF_STABLE_ID, sha256Hex(ssaid)).apply();
                done.run();
                return;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        // No usable ANDROID_ID: fall back to the App Set ID, which at least survives a clear-data.
        try {
            com.google.android.gms.appset.AppSet
                    .getClient(ApplicationLoader.applicationContext)
                    .getAppSetIdInfo()
                    .addOnSuccessListener(info -> {
                        try {
                            String id = info != null ? info.getId() : null;
                            if (id != null && id.length() > 0) {
                                prefs().edit().putString(PREF_STABLE_ID, sha256Hex(id)).apply();
                            }
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                        done.run();
                    })
                    .addOnFailureListener(e -> done.run());
        } catch (Throwable t) {
            // No Play services at all: nothing to resolve, and nothing broken.
            done.run();
        }
    }

    private static String sha256Hex(String value) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(value.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
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
        resolveStableId(() -> mint(p));
    }

    private static void mint(final SharedPreferences p) {
        JSONObject body = new JSONObject();
        try {
            // Prefer the id that survives a reinstall; fall back to the one this install minted.
            // Either way the server is told which device this is, so a returning guest keeps the
            // feed it taught — and a device id the server does not like is simply replaced.
            String known = p.getString(PREF_STABLE_ID, "");
            if (known.length() == 0) known = p.getString(PREF_DEVICE_ID, "");
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
                    it.durationMs = o.optInt("duration_ms", 0);
                    it.posterUrl = o.optString("poster", "");
                    it.portrait = o.optBoolean("portrait", true);
                    // The page may already carry its playable URL. The server keeps warm ones for
                    // the references it serves, so a card that arrives with this needs NO
                    // /v1/guest/media call at all — and that endpoint is the expensive one: it
                    // scrapes the public embed behind a process-wide lock with a 0.4s floor, so a
                    // page of eight used to serialise into seconds of waiting. media() short-circuits
                    // on a non-empty mediaUrl, so filling it here is the whole change.
                    it.mediaUrl = o.isNull("play_url") ? "" : o.optString("play_url", "");
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
