package org.telegram.svipe;

/**
 * Policy for the persistent offline ready-queue that backs instant reel playback on app open.
 *
 * The queue holds fully-downloaded, not-yet-watched reels serialized to disk so a cold start can
 * play position 0 with ZERO network (no auth, no /v1/feed, no MTProto resolve). While the user
 * watches, we keep at least {@link #TARGET_AHEAD} fully-downloaded unwatched reels ahead, bounded
 * by {@link #MAX_ENTRIES} and a {@link #MAX_QUEUE_BYTES} disk budget so the cache never runs away.
 *
 * Pure Java (no Android imports) so it can be unit-tested on the JVM, like {@link SvipePreloadPlan}.
 */
public class SvipeQueuePlan {

    /**
     * Keep this many fully-downloaded, unwatched reels ready ahead of the current one. Kept modest
     * so a burst of FULL downloads (videos run up to ~50MB) doesn't saturate the pipe and starve the
     * current/next reel's playback buffer — live instant-start now leans on the prepared-next-player
     * ({@link SvipePreloadPlan#shouldPrepareNextPlayer}); this queue is mainly the offline cold-start
     * cushion, for which a handful is plenty. Speculative FULL downloads are additionally gated to
     * Wi-Fi in ReelsActivity#ensureFullDownloadsAhead, so cellular users are never charged for them.
     */
    public static final int TARGET_AHEAD = 3;
    /** Hard cap on persisted entries — a backstop above TARGET_AHEAD to absorb churn. */
    public static final int MAX_ENTRIES = 24;
    /** Disk budget for queued video bytes (~600MB). Downloading stops once this would be exceeded. */
    public static final long MAX_QUEUE_BYTES = 600L * 1024 * 1024;
    /** A SWIPE_AWAY only counts as "watched" past this dwell — a glance shouldn't burn a reel. */
    public static final long MIN_WATCHED_MS = 3000L;

    /** Canonical identity for a feed item. channel+message is unique; a document can be reposted. */
    public static String compositeKey(long channelId, int messageId) {
        return channelId + ":" + messageId;
    }

    /** How many entries past the cap — drop this many from the front. Clamped >= 0. */
    public static int overflowCount(int size, int cap) {
        return Math.max(0, size - cap);
    }

    /** Whether adding {@code add} bytes keeps total queued bytes within the disk budget. */
    public static boolean withinByteBudget(long currentBytes, long addBytes, long budget) {
        return currentBytes + addBytes <= budget;
    }

    /** Whether we still need to start more full downloads to satisfy the ahead target. */
    public static boolean needsMoreDownloads(int downloadedUnwatchedAhead) {
        return downloadedUnwatchedAhead < TARGET_AHEAD;
    }

    /**
     * Whether leaving a reel should mark it watched (so it never re-enters the offline queue).
     * REPLAY/VIDEO_END always count; a SWIPE_AWAY counts only once the user dwelled past minWatchedMs.
     */
    public static boolean countsAsWatched(String classification, long watchedMs, long minWatchedMs) {
        if ("REPLAY".equals(classification) || "VIDEO_END".equals(classification)) {
            return true;
        }
        return watchedMs >= minWatchedMs;
    }
}
