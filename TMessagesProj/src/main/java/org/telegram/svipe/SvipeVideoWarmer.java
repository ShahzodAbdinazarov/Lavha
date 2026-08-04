package org.telegram.svipe;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

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
 * Thumbnails are NOT resolved here. The grid resolves them off its own pipe as it composes, and one
 * resolveUsername per visible item is the biggest single draw on the budget the whole Svipe layer
 * depends on — pre-spending it for a screen that may never be looked at is the opposite of the
 * point.
 */
public final class SvipeVideoWarmer {

    /** A warmed page is worth using for this long; both pipes are personalised and move on. */
    private static final long FRESH_FOR_MS = 10 * 60 * 1000L;
    /** Match the grid's own page sizes, or the composer would start from a page it cannot reuse. */
    private static final int SHORTS_PAGE = 60;
    private static final int LONGS_PAGE = 10;

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
                done.run();
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
