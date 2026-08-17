package org.telegram.svipe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.svipe.video.SvipeRefResolver;
import org.telegram.svipe.video.SvipeVideoLadder;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.VideoPlayer;

import java.util.ArrayList;

/**
 * Gets reels ready BEFORE the user asks for them.
 *
 * The reels cold start is a chain of network steps — auth, /v1/feed, an MTProto resolve per item,
 * then bytes — and a user with nothing on disk pays for all of it while staring at a status label.
 * That is the whole reason the "Loading" screen exists. Running the same chain quietly a few seconds
 * after the app opens moves the entire cost off the moment the user taps the tab: by then the token
 * is stored, the feed page is parsed, the first reels are resolved and the first one's opening
 * seconds are on disk, so {@link org.telegram.ui.ReelsActivity} can play position 0 immediately —
 * the same instant path the persisted offline queue gives returning users.
 *
 * Rules it lives by:
 *   - it must never compete with what the user IS looking at: it starts after a settle delay and
 *     preloads at LOW priority (Telegram's own 2MB head-preload, not a full file);
 *   - it must never resolve more usernames than it needs — contacts.resolveUsername is flood-limited
 *     and a fresh account has little budget (see {@link SvipeAuth});
 *   - it is best-effort by construction: everything it produces is a bonus, and every failure just
 *     leaves the normal cold start to do its job.
 */
public final class SvipeReelWarmer {

    /** How long a warmed page stays worth using. The feed is personalised and moves on. */
    private static final long FRESH_FOR_MS = 10 * 60 * 1000L;
    /** Resolve only the head of the page — enough for an instant first reel plus the swipe after it. */
    private static final int RESOLVE_AHEAD = 2;
    /** Below this many ready reels on disk the warm-up is worth running at all. */
    private static final int QUEUE_COMFORTABLE = 3;

    /** What the warm-up produced, handed to the reels screen once and then forgotten. */
    public static class Warm {
        public final ArrayList<SvipeRefResolver.VideoRef> items = new ArrayList<>();
        public String recommendationId;
        public String cursor;
    }

    private static boolean started;
    private static long warmedAtMs;
    private static Warm warm;

    private SvipeReelWarmer() {}

    /**
     * Run the warm-up, reporting completion so {@link SvipeWarmup} can start the next one. Every
     * exit below ends in {@code done} — a warm-up that quietly returns would hold up the queue
     * until its deadline, which is exactly the class of bug this codebase keeps meeting.
     */
    public static void warm(final int account, final Runnable done) {
        if (started) { done.run(); return; }
        started = true;
        if (!UserConfig.getInstance(account).isClientActivated()) { done.run(); return; }
        // The queue blob and the watched ledger are parsed off the UI thread — they are the two
        // biggest JSON blobs this app keeps in preferences, and this runs during app start, when
        // the main thread has better things to do (the reels cold start reads them the same way).
        Utilities.globalQueue.postRunnable(() -> {
            try {
                // A returning user already has reels on disk and their cold start is instant — leave
                // their bandwidth alone. This exists for the empty case, which is where it hurts.
                // Counted the way the cold start counts them — by whether the FILE is really there.
                // The entry's `downloaded` flag outlives the bytes (Telegram's cache eviction knows
                // nothing about our queue), and trusting it once left a user whose cache had been
                // cleared with neither a warm page nor a queue to restore.
                int ready = 0;
                for (SvipeReelQueue.Entry e : new SvipeReelQueue(account).list()) {
                    if (e.downloaded && isOnDisk(account, e)) ready++;
                }
                if (ready >= QUEUE_COMFORTABLE) {
                    FileLog.d("svipe: warm-up skipped, " + ready + " reels already on disk");
                    done.run();
                    return;
                }
                final SvipeWatchedSet watched = new SvipeWatchedSet(account);
                final SvipeBlockedChannels blocked = new SvipeBlockedChannels(account);
                // Do we already have a token, or is the auth chain about to run? The answer decides
                // whether anybody is waiting on it. Measured on this account: with a stored token
                // ensureToken returns in microseconds; without one the chain took 1,386-4,580 ms,
                // and 3,134 ms on a cold install.
                final boolean haveToken = SvipeAuth.getStoredToken(account) != null;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!haveToken) fetchGuestPage(account, watched, blocked);
                    SvipeAuth.ensureToken(account, token -> {
                        if (token == null) { // auth will be retried by whoever needs it next
                            done.run();
                            return;
                        }
                        fetchPage(account, token, watched, blocked, done);
                    });
                });
            } catch (Exception e) {
                FileLog.e(e);
                done.run();
            }
        });
    }

    /**
     * Is this queue entry's video actually on disk? Mirrors the reels cold start's validate-on-load
     * check: a laddered entry stores ONE rendition, so any cached rung counts; otherwise the
     * original file must exist.
     */
    private static boolean isOnDisk(int account, SvipeReelQueue.Entry e) {
        try {
            MessageObject mo = SvipeReelQueue.messageOf(account, e);
            if (mo == null || mo.getDocument() == null) return false;
            ArrayList<VideoPlayer.Quality> qualities = SvipeVideoLadder.qualitiesFor(account, mo);
            if (qualities != null) {
                return SvipeVideoLadder.cachedQualityOf(qualities) != null;
            }
            java.io.File f = FileLoader.getInstance(account).getPathToAttach(mo.getDocument(), null, false, false);
            return f != null && f.exists();
        } catch (Exception ex) {
            return false;
        }
    }

    private static void fetchPage(final int account, String token,
                                  final SvipeWatchedSet watched, final SvipeBlockedChannels blocked,
                                  final Runnable done) {
        SvipeApi.get("/v1/feed", token, (res, code, err) -> {
            if (res == null || !res.has("items")) {
                FileLog.d("svipe: warm-up feed failed (" + code + ")");
                done.run();
                return;
            }
            final Warm w = new Warm();
            w.recommendationId = res.isNull("recommendation_id") ? null : res.optString("recommendation_id", null);
            w.cursor = res.isNull("next_cursor") ? null : res.optString("next_cursor", null);
            JSONArray arr = res.optJSONArray("items");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String username = o.isNull("username") ? null : o.optString("username", null);
                if (username == null || username.isEmpty()) continue;
                long channelId = o.optLong("channel_id");
                int messageId = o.optInt("message_id");
                if (watched.isWatched(channelId, messageId) || blocked.contains(channelId)) continue;
                SvipeRefResolver.VideoRef ref = new SvipeRefResolver.VideoRef();
                ref.channelId = channelId;
                ref.messageId = messageId;
                ref.username = username;
                ref.shareUrl = o.isNull("share_url") ? null : o.optString("share_url", null);
                ref.topicId = o.isNull("topic_id") ? null : o.optInt("topic_id");
                ref.recId = w.recommendationId;
                // The sessionless route, carried straight through to the pager. Without this the
                // whole warm page arrived with no URL and every reel on it went back to MTProto —
                // which is how a warm-up meant to SAVE the flood budget ended up spending it.
                ref.playUrl = o.isNull("play_url") ? null : o.optString("play_url", null);
                ref.width = o.optInt("width", 0);
                ref.height = o.optInt("height", 0);
                ref.durationMs = o.optInt("duration_ms", 0);
                w.items.add(ref);
            }
            if (w.items.isEmpty()) { done.run(); return; }
            warm = w;
            warmedAtMs = System.currentTimeMillis();
            FileLog.d("svipe: warm-up holds " + w.items.size() + " reels, resolving the head");
            resolveHead(account, w, 0, done);
        });
    }

    /**
     * A page fetched with NO Telegram identity, while the auth chain is still running.
     *
     * <p>The reason this exists: the chain that mints our token measured 1,386-4,580 ms on this
     * account, and 3,134 ms on a cold install. Until it finishes there is no token, so there is no
     * {@code /v1/feed} — and the person opened the app to watch something. The guest feed needs no
     * token at all (a device id mints its own), and since playback stopped resolving anything, what
     * comes back is playable on arrival. So the wait for auth stops being a wait for a video.
     *
     * <p>Nothing here gates {@code done}: this is a bonus running beside the real warm-up, and the
     * token path owns the completion callback. Nor does it overwrite a personalised page — if auth
     * won the race, its page is strictly better and stays.
     *
     * <p>The items are marked {@code tokenless} so the reels screen can retire the ones the viewer
     * never reached once the personalised page lands. Everything they play in the meantime is real,
     * and the events they generate are held and sent the moment the token exists.
     */
    private static void fetchGuestPage(final int account, final SvipeWatchedSet watched,
                                       final SvipeBlockedChannels blocked) {
        final long startedAt = System.currentTimeMillis();
        SvipeGuest.reels(0, (items, next, err) -> {
            if (items == null || items.isEmpty()) {
                FileLog.d("svipe: tokenless warm-up got nothing (" + err + ")");
                return;
            }
            if (warm != null) return;   // auth won the race; its page is the better one
            final Warm w = new Warm();
            for (SvipeGuest.Item g : items) {
                if (g.username == null || g.username.isEmpty()) continue;
                if (g.mediaUrl == null || g.mediaUrl.isEmpty()) continue;   // nothing to play yet
                if (watched.isWatched(g.channelId, g.messageId) || blocked.contains(g.channelId)) continue;
                SvipeRefResolver.VideoRef ref = new SvipeRefResolver.VideoRef();
                ref.channelId = g.channelId;
                ref.messageId = g.messageId;
                ref.username = g.username;
                ref.shareUrl = g.shareUrl();
                ref.playUrl = g.mediaUrl;
                ref.durationMs = g.durationMs;
                ref.tokenless = true;
                w.items.add(ref);
            }
            if (w.items.isEmpty()) return;
            if (warm != null) return;   // checked again: the fetch above was not instantaneous
            warm = w;
            warmedAtMs = System.currentTimeMillis();
            FileLog.d("svipe: tokenless warm-up holds " + w.items.size() + " playable reels in "
                    + (System.currentTimeMillis() - startedAt) + "ms, auth still running");
        });
    }

    /**
     * Resolve the first few items one at a time — serial on purpose, to stay off the flood ceiling.
     *
     * <p>Skipped entirely for a reel that carries a public URL: it can already be played, and the
     * only reason to resolve it is the action rail, which the player fills behind the video when the
     * user actually reaches it. Warming a page used to cost one contacts.resolveUsername per head
     * item before anything was on screen; now it costs none for the reels that do not need it.
     */
    private static void resolveHead(final int account, final Warm w, final int index, final Runnable done) {
        if (index >= RESOLVE_AHEAD || index >= w.items.size()) {
            done.run();
            return;
        }
        final SvipeRefResolver.VideoRef ref = w.items.get(index);
        if (ref.playUrl != null && !ref.playUrl.isEmpty()) {
            resolveHead(account, w, index + 1, done);
            return;
        }
        SvipeRefResolver.resolve(account, ref, () -> {
            if (index == 0) preloadHead(account, ref);
            resolveHead(account, w, index + 1, done);
        }, null, true);   // warm-up: nothing is on screen waiting, so it queues behind the user
    }

    /**
     * Telegram's head-preload (cacheType 10): ~2MB plus the moov atom, at LOW priority. Enough for a
     * first frame the moment the tab opens, and every byte is reused when the player streams the
     * same file — nothing is downloaded twice and no full file is pulled behind the user's back.
     */
    private static void preloadHead(int account, SvipeRefResolver.VideoRef ref) {
        try {
            if (ref.mo == null) return;
            if (!DownloadController.getInstance(account).canPreloadStories()) return;
            ArrayList<VideoPlayer.Quality> qualities = SvipeVideoLadder.qualitiesFor(account, ref.mo);
            if (qualities != null) {
                VideoPlayer.VideoUri target = SvipeVideoLadder.targetRendition(qualities);
                if (target != null && target.document != null && !target.isCached()) {
                    // The rung's HLS manifest is fetched synchronously at prepare time, so having it
                    // on disk is what actually makes the adaptive start instant.
                    if (target.manifestDocument != null && !target.isManifestCached()) {
                        FileLoader.getInstance(account).loadFile(target.manifestDocument, ref.mo, FileLoader.PRIORITY_LOW, 0);
                    }
                    FileLoader.getInstance(account).loadFile(target.document, ref.mo, FileLoader.PRIORITY_LOW, 10);
                }
                return;
            }
            TLRPC.Document doc = ref.mo.getDocument();
            if (doc == null || SvipeVideoLadder.isLongForm(doc)) return;
            FileLoader.getInstance(account).loadFile(doc, ref.mo, FileLoader.PRIORITY_LOW, 10);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Hand the warmed page over — once. Returns null when there is nothing fresh, which is the
     * normal case for a returning user (their offline queue is the faster path anyway).
     */
    public static Warm take() {
        Warm w = warm;
        warm = null;
        if (w == null) return null;
        if (System.currentTimeMillis() - warmedAtMs > FRESH_FOR_MS) {
            FileLog.d("svipe: warm-up page went stale, dropping it");
            return null;
        }
        return w;
    }
}
