package org.telegram.svipe;

/**
 * Preload policy for the reels pager. Priorities, top to bottom:
 *   1. the CURRENT reel — streamed by the player at Telegram's PRIORITY_STREAM (handled by
 *      FileLoader itself once the stream opens);
 *   2. the reel NEXT in line — head-preloaded at NORMAL priority, bypassing the data-saving gate
 *      (it is ~2MB and the user will almost certainly reach it);
 *   3. the rest of the ahead window — head-preloaded at LOW priority behind the gate;
 *   4. reels BEHIND — never downloaded again; their bytes simply stay in Telegram's cache, so
 *      swiping back is instant. Cache eviction stays Telegram's size-based job.
 * Anything that falls out of the ahead window gets its download cancelled (bytes are kept).
 * Pure Java so it can be unit-tested on the JVM.
 */
public class SvipePreloadPlan {

    /** Mirrors FileLoader.PRIORITY_NORMAL / PRIORITY_LOW without importing Android code. */
    public static final int NORMAL = 1;
    public static final int LOW = 0;

    public static boolean inAheadWindow(int index, int currentPos, int ahead) {
        return index > currentPos && index <= currentPos + ahead;
    }

    public static int priorityFor(int index, int currentPos) {
        return index == currentPos + 1 ? NORMAL : LOW;
    }

    /** The very next reel skips the canPreloadStories data-saving gate — it's tiny and imminent. */
    public static boolean bypassesGate(int index, int currentPos) {
        return index == currentPos + 1;
    }

    /** Cancel a started preload that no longer serves the window (current reel is never touched). */
    public static boolean shouldCancelPreload(int index, int currentPos, int ahead, boolean started) {
        return started && index != currentPos && !inAheadWindow(index, currentPos, ahead);
    }

    /**
     * Whether to keep ONE fully prepared (buffering, paused) player for the next reel — the
     * Stories/TikTok trick that makes a swipe start instantly. Enabled on every device (only bounds
     * are checked): buffering every swipe from scratch is the single biggest source of the loading
     * spinner, and our users skew low-end — the previous HIGH-only gate disabled it for exactly the
     * devices that need it most (measured: 0/17 swipes served from a prepared player). A second
     * short-reel decoder is the standard cost; a device that can't spare one degrades to on-demand
     * creation via prepareNextPlayer's try/catch + the player's onError, never a crash.
     */
    public static boolean shouldPrepareNextPlayer(int nextIndex, int itemCount) {
        return nextIndex >= 0 && nextIndex < itemCount;
    }
}
