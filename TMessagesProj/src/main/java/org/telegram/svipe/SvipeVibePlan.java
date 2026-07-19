package org.telegram.svipe;

/**
 * The two decisions behind a seeded vibe, kept free of Android types so they can be tested.
 *
 * <p>Both are easy to get subtly wrong and impossible to notice from a screenshot: one drops the
 * listener into the middle of a song they never started, the other stops the music.
 */
public final class SvipeVibePlan {

    private SvipeVibePlan() {
    }

    /**
     * Whether the playback position of the outgoing track may be carried onto the queue's first entry.
     *
     * <p>Only when that entry really is the seed. The seed is prepended, but it can fail to resolve
     * (an unavailable post) or be folded into a copy the vibe page returned as well, and either way
     * the queue then opens on a different song — inheriting a position onto that one would start it
     * halfway through.
     *
     * @param includeSeed    whether the seed was prepended at all
     * @param hasResumeSource whether there is an outgoing position to inherit
     * @param firstKey       "channelId:messageId" of the queue's first entry, or null
     * @param seedKey        "channelId:messageId" of the seed, or null
     */
    public static boolean carriesProgress(boolean includeSeed, boolean hasResumeSource,
                                          String firstKey, String seedKey) {
        if (!includeSeed || !hasResumeSource) {
            return false;
        }
        return firstKey != null && firstKey.equals(seedKey);
    }

    /**
     * Whether a playlist that just ended should hand over to a vibe seeded by its last track.
     *
     * <p>Finite Svipe queues (favourites, a search, a section) should: falling silent at the end of a
     * short list is the thing this feature exists to avoid.
     *
     * <p>So should a self-paging queue that ran out for any reason short of the backend saying so —
     * a page load that failed on a flaky connection leaves an infinite queue sitting on its last
     * track, and before repeat stopped being forced on, that case quietly wrapped instead of ending
     * the session. Only a queue the backend has actually exhausted stops, because there asking again
     * would put the same question to the same empty answer.
     *
     * <p>Nothing here inspects the repeat setting: this is only ever reached on the repeat-off path,
     * because with repeat on the playlist wraps and never ends.
     *
     * @param hasSvipeQueue whether the installed playlist is a Svipe catalog queue
     * @param exhausted     whether the backend has said this queue has no more pages
     * @param hasSeedTrack  whether the finished entry maps to a catalog track to seed with
     */
    public static boolean handsOffToVibe(boolean hasSvipeQueue, boolean exhausted, boolean hasSeedTrack) {
        return hasSvipeQueue && !exhausted && hasSeedTrack;
    }
}
