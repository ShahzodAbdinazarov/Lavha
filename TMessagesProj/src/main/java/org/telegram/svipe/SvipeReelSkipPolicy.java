package org.telegram.svipe;

/**
 * When may the reels pager move the viewer off the reel they are looking at?
 *
 * <p>Exactly one answer: when there is no way left to show it. Not "when a resolve failed" — a
 * resolve is what the LIKE button needs, not what the video needs. The screen used to conflate the
 * two, because for a long time an MTProto resolve was the only route to a frame; once the
 * sessionless https route landed, a reel could be playing perfectly while its background resolve
 * failed, and the failure scrolled the viewer away from a video they were watching. The same video,
 * every time, because both of the permanent resolve verdicts (message gone, "not a video document" —
 * which is false for round messages and for documents with no video attribute) are properties of the
 * item and never change.
 *
 * <p>So the rule is stated in terms of PLAYBACK, and a resolve verdict is not an input to it at all.
 * The caller says whether playback has actually failed; this class says whether anything else could
 * still carry the reel.
 *
 * <p>Deliberately free of every Android type. Robolectric cannot run in this project (Theme ->
 * Utilities pulls a native cascade), so a policy that lives inside the fragment is a policy that is
 * never tested. Booleans in, an int out — see SvipeReelSkipPolicyTest.
 */
public final class SvipeReelSkipPolicy {

    /** Nothing can play this reel: move forward. */
    public static final int SKIP = 1;
    /** Would skip, but this is the last item held — ask for another page instead. */
    public static final int LOAD_MORE = 2;

    /** The reel is not the one on screen. A page the viewer already left is never rewritten. */
    public static final int KEEP_NOT_CURRENT = 10;
    /** A message is in hand, so MTProto can still play it. */
    public static final int KEEP_HAS_MESSAGE = 11;
    /** A player exists — whatever else failed, the viewer is watching. */
    public static final int KEEP_PLAYING = 12;
    /** A sessionless URL is still live; it has not been tried and lost yet. */
    public static final int KEEP_URL_ALIVE = 13;
    /** Nothing has actually failed to play. A background disappointment is not a dead reel. */
    public static final int KEEP_NO_FAILURE = 14;

    private SvipeReelSkipPolicy() {}

    /**
     * @param isCurrent      the reel is the page the viewer is on right now
     * @param hasMessage     a resolved MessageObject is attached (the MTProto route is open)
     * @param hasPlayUrl     a sessionless https URL is attached and has not been withdrawn
     * @param playerAlive    a player exists for the current page (it is playing or buffering)
     * @param playbackFailed a real playback route was tried and lost: an ExoPlayer error with no
     *                       fallback left, a watchdog that withdrew the URL and found nothing else,
     *                       or a play-time resolve that failed when it WAS the only route. A failed
     *                       background resolve must never be passed as true here.
     * @param hasNext        another item exists after this one in the pager
     * @return one of the constants above
     */
    public static int decide(boolean isCurrent, boolean hasMessage, boolean hasPlayUrl,
                             boolean playerAlive, boolean playbackFailed, boolean hasNext) {
        if (!isCurrent) return KEEP_NOT_CURRENT;
        if (playerAlive) return KEEP_PLAYING;      // watching beats every verdict about this item
        if (hasMessage) return KEEP_HAS_MESSAGE;
        if (hasPlayUrl) return KEEP_URL_ALIVE;
        if (!playbackFailed) return KEEP_NO_FAILURE;
        return hasNext ? SKIP : LOAD_MORE;
    }

    /** True only for {@link #SKIP}. {@link #LOAD_MORE} is not a skip — nobody is moved anywhere. */
    public static boolean shouldSkip(boolean isCurrent, boolean hasMessage, boolean hasPlayUrl,
                                     boolean playerAlive, boolean playbackFailed, boolean hasNext) {
        return decide(isCurrent, hasMessage, hasPlayUrl, playerAlive, playbackFailed, hasNext) == SKIP;
    }

    /** The verdict in words, for the one FileLog line every skip and every veto writes. */
    public static String reasonName(int verdict) {
        switch (verdict) {
            case SKIP:             return "no_route_left";
            case LOAD_MORE:        return "end_of_feed";
            case KEEP_NOT_CURRENT: return "not_current";
            case KEEP_HAS_MESSAGE: return "has_message";
            case KEEP_PLAYING:     return "player_alive";
            case KEEP_URL_ALIVE:   return "url_alive";
            case KEEP_NO_FAILURE:  return "no_playback_failure";
            default:               return "unknown(" + verdict + ")";
        }
    }
}
