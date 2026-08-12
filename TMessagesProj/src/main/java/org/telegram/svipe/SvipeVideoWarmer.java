package org.telegram.svipe;

import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.svipe.video.SvipeRefResolver;
import org.telegram.svipe.video.SvipeVideoLadder;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.VideoPlayer;

import java.util.List;

/**
 * Has the Video tab's first screen ready before it is opened.
 *
 * The browse grid is fed by two independent pipes ({@code /v1/discover} for shorts,
 * {@code /v1/videos} for long-form) and composes a screen only once BOTH have answered — so the tab
 * opens on a skeleton for as long as the slower of two round-trips takes. Fetching both pages at
 * app start makes that composition immediate.
 *
 * Two deliberate limits:
 *   - {@code refresh=false}. Passing true rotates the server's grid window, and that rotation
 *     belongs to the user's pull-to-refresh; a background warm-up must not spend it.
 *   - it runs only for someone who has opened the Video tab before, for the same reason music does
 *     (see {@link SvipeMusicWarmer}): a tab you never visit should cost you nothing.
 *
 * Only the FIRST SCREEN is taken all the way. One resolveUsername per item is the biggest single
 * draw on the budget the whole Svipe layer depends on, so the warm-up resolves what the user will
 * see without scrolling — the lead long card and the first row of shorts — and head-preloads the
 * lead card so tapping it starts playing rather than buffering. Everything below the fold is
 * resolved by the grid as it composes, exactly as before.
 */
public final class SvipeVideoWarmer {

    /** A warmed page is worth using for this long; both pipes are personalised and move on. */
    private static final long FRESH_FOR_MS = 10 * 60 * 1000L;
    /** Match the grid's own page sizes, or the composer would start from a page it cannot reuse. */
    private static final int SHORTS_PAGE = 60;
    private static final int LONGS_PAGE = 10;
    /** The first visible row of the grid: one long card above three shorts. */
    private static final int RESOLVE_LONGS = 1;
    private static final int RESOLVE_SHORTS = 3;

    /** The two page-0s, held until the grid asks for them. */
    public static class Warm {
        public List<SvipeDiscover.Item> shorts;
        public List<SvipeDiscover.Item> longs;
        public Integer shortsNext;
        public Integer longsNext;
    }

    private static boolean started;
    private static long warmedAtMs;
    private static Warm warm;

    private SvipeVideoWarmer() {}

    /** Remember that this user does open the Video tab — the gate for warming it at all. */
    public static void markUsed(int account) {
        try {
            MessagesController.getMainSettings(account).edit()
                    .putBoolean(SvipeConfig.PREF_VIDEO_USED, true).apply();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    public static boolean isUsed(int account) {
        try {
            return MessagesController.getMainSettings(account).getBoolean(SvipeConfig.PREF_VIDEO_USED, false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Run the warm-up, reporting completion so {@link SvipeWarmup} can move on. Both pipes are
     * fetched at once — they are independent services and the grid needs both before it can compose,
     * so serialising them here would only add the slower one's latency to the faster one's.
     */
    public static void warm(final int account, final Runnable done) {
        try {
            if (started) { done.run(); return; }
            started = true;
            if (!UserConfig.getInstance(account).isClientActivated()) { done.run(); return; }
            if (!isUsed(account)) { done.run(); return; } // never opened Video — spend nothing
            final Warm w = new Warm();
            final int[] pending = {2};
            final Runnable settle = () -> {
                if (--pending[0] != 0) return;
                if ((w.shorts == null || w.shorts.isEmpty()) && (w.longs == null || w.longs.isEmpty())) {
                    FileLog.d("svipe: video warm-up got nothing");
                    done.run();
                    return;
                }
                warm = w;
                warmedAtMs = System.currentTimeMillis();
                FileLog.d("svipe: video warm-up holds " + (w.shorts == null ? 0 : w.shorts.size())
                        + " shorts + " + (w.longs == null ? 0 : w.longs.size()) + " long");
                resolveFirstScreen(account, w, done);
            };
            SvipeDiscover.load(account, null, 0, SHORTS_PAGE, false, (result, next, error) -> {
                w.shorts = result;
                w.shortsNext = next;
                settle.run();
            });
            SvipeDiscover.videos(account, null, 0, LONGS_PAGE, false, (result, next, error) -> {
                w.longs = result;
                w.longsNext = next;
                settle.run();
            });
        } catch (Exception e) {
            FileLog.e(e);
            done.run();
        }
    }

    /**
     * Resolve what the first screen shows and preload the lead card, one item at a time — serial on
     * purpose, the same discipline the reels warm-up follows to stay off the flood ceiling.
     */
    private static void resolveFirstScreen(final int account, final Warm w, final Runnable done) {
        final java.util.ArrayList<SvipeDiscover.Item> head = new java.util.ArrayList<>();
        if (w.longs != null) {
            for (int i = 0; i < Math.min(RESOLVE_LONGS, w.longs.size()); i++) head.add(w.longs.get(i));
        }
        if (w.shorts != null) {
            for (int i = 0; i < Math.min(RESOLVE_SHORTS, w.shorts.size()); i++) head.add(w.shorts.get(i));
        }
        resolveNext(account, head, 0, done);
    }

    private static void resolveNext(final int account, final List<SvipeDiscover.Item> head,
                                    final int index, final Runnable done) {
        if (index >= head.size()) {
            done.run();
            return;
        }
        final SvipeRefResolver.VideoRef ref = SvipeRefResolver.VideoRef.of(head.get(index));
        SvipeRefResolver.resolve(account, ref, () -> {
            if (index == 0) preloadLead(account, ref);
            resolveNext(account, head, index + 1, done);
        }, null, true);   // warm-up: nothing is on screen waiting, so it queues behind the user
    }

    /**
     * Head-preload the lead long card (cacheType 10 — ~2MB plus the moov atom, LOW priority). A
     * long-form file is far too big to fetch whole behind someone's back; the head is all it takes
     * for the first frame, and the player reuses every byte of it when it streams the rest.
     */
    private static void preloadLead(int account, SvipeRefResolver.VideoRef ref) {
        try {
            if (ref.mo == null) return;
            if (!DownloadController.getInstance(account).canPreloadStories()) return;
            java.util.ArrayList<VideoPlayer.Quality> qualities = SvipeVideoLadder.qualitiesFor(account, ref.mo);
            if (qualities != null) {
                VideoPlayer.VideoUri target = SvipeVideoLadder.targetRendition(qualities);
                if (target != null && target.document != null && !target.isCached()) {
                    if (target.manifestDocument != null && !target.isManifestCached()) {
                        FileLoader.getInstance(account).loadFile(target.manifestDocument, ref.mo, FileLoader.PRIORITY_LOW, 0);
                    }
                    FileLoader.getInstance(account).loadFile(target.document, ref.mo, FileLoader.PRIORITY_LOW, 10);
                }
                return;
            }
            TLRPC.Document doc = ref.mo.getDocument();
            if (doc != null) {
                FileLoader.getInstance(account).loadFile(doc, ref.mo, FileLoader.PRIORITY_LOW, 10);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Hand the warmed pages over — once. Null when nothing fresh is waiting. */
    public static Warm take() {
        Warm w = warm;
        warm = null;
        if (w == null) return null;
        if (System.currentTimeMillis() - warmedAtMs > FRESH_FOR_MS) {
            FileLog.d("svipe: video warm-up page went stale, dropping it");
            return null;
        }
        return w;
    }
}
