package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rule the reels pager forgot: a reel is skipped when it cannot be shown, not when something
 * about it failed to load.
 */
public class SvipeReelSkipPolicyTest {

    // Named for readability at the call sites below.
    private static final boolean CURRENT = true, NOT_CURRENT = false;
    private static final boolean HAS_MO = true, NO_MO = false;
    private static final boolean HAS_URL = true, NO_URL = false;
    private static final boolean PLAYING = true, NO_PLAYER = false;
    private static final boolean FAILED = true, NOT_FAILED = false;
    private static final boolean HAS_NEXT = true, LAST = false;

    /** The bug this whole change exists to kill: a reel plays over https, its background resolve
     *  fails, and the viewer is scrolled off a video they were watching. */
    @Test
    public void playingReelWithFailedResolveIsNeverSkipped() {
        assertFalse(SvipeReelSkipPolicy.shouldSkip(CURRENT, NO_MO, HAS_URL, PLAYING, NOT_FAILED, HAS_NEXT));
        assertEquals(SvipeReelSkipPolicy.KEEP_PLAYING,
                SvipeReelSkipPolicy.decide(CURRENT, NO_MO, HAS_URL, PLAYING, NOT_FAILED, HAS_NEXT));
        // Even if the caller wrongly reports a failure, a live player still vetoes.
        assertEquals(SvipeReelSkipPolicy.KEEP_PLAYING,
                SvipeReelSkipPolicy.decide(CURRENT, NO_MO, HAS_URL, PLAYING, FAILED, HAS_NEXT));
    }

    /** No sessionless URL, no player, and playback actually failed — the reel is genuinely dead. */
    @Test
    public void noUrlNoPlayerAndPlaybackErrorSkips() {
        assertTrue(SvipeReelSkipPolicy.shouldSkip(CURRENT, NO_MO, NO_URL, NO_PLAYER, FAILED, HAS_NEXT));
        assertEquals(SvipeReelSkipPolicy.SKIP,
                SvipeReelSkipPolicy.decide(CURRENT, NO_MO, NO_URL, NO_PLAYER, FAILED, HAS_NEXT));
    }

    /** The stall watchdog withdrew a dead CDN URL and nothing replaced it. */
    @Test
    public void urlWithdrawnByWatchdogWithNoPlayerSkips() {
        assertTrue(SvipeReelSkipPolicy.shouldSkip(CURRENT, NO_MO, NO_URL, NO_PLAYER, FAILED, HAS_NEXT));
    }

    /** A reel the viewer has already left is never rewritten under them. */
    @Test
    public void itemThatIsNotCurrentIsNeverSkipped() {
        assertFalse(SvipeReelSkipPolicy.shouldSkip(NOT_CURRENT, NO_MO, NO_URL, NO_PLAYER, FAILED, HAS_NEXT));
        assertEquals(SvipeReelSkipPolicy.KEEP_NOT_CURRENT,
                SvipeReelSkipPolicy.decide(NOT_CURRENT, NO_MO, NO_URL, NO_PLAYER, FAILED, HAS_NEXT));
    }

    /** A message in hand means MTProto can still carry the reel, whatever the URL did. */
    @Test
    public void resolvedMessageVetoesTheSkip() {
        assertEquals(SvipeReelSkipPolicy.KEEP_HAS_MESSAGE,
                SvipeReelSkipPolicy.decide(CURRENT, HAS_MO, NO_URL, NO_PLAYER, FAILED, HAS_NEXT));
        assertFalse(SvipeReelSkipPolicy.shouldSkip(CURRENT, HAS_MO, NO_URL, NO_PLAYER, FAILED, HAS_NEXT));
    }

    /** A live URL that has not been tried and lost yet still owes the reel an attempt. */
    @Test
    public void liveUrlVetoesTheSkip() {
        assertEquals(SvipeReelSkipPolicy.KEEP_URL_ALIVE,
                SvipeReelSkipPolicy.decide(CURRENT, NO_MO, HAS_URL, NO_PLAYER, FAILED, HAS_NEXT));
        assertFalse(SvipeReelSkipPolicy.shouldSkip(CURRENT, NO_MO, HAS_URL, NO_PLAYER, FAILED, HAS_NEXT));
    }

    /** Nothing failed to PLAY — a background disappointment on its own moves nobody. */
    @Test
    public void backgroundFailureWithoutPlaybackFailureKeeps() {
        assertEquals(SvipeReelSkipPolicy.KEEP_NO_FAILURE,
                SvipeReelSkipPolicy.decide(CURRENT, NO_MO, NO_URL, NO_PLAYER, NOT_FAILED, HAS_NEXT));
        assertFalse(SvipeReelSkipPolicy.shouldSkip(CURRENT, NO_MO, NO_URL, NO_PLAYER, NOT_FAILED, HAS_NEXT));
    }

    /** At the end of the feed there is nowhere to move to: ask for another page, do not scroll. */
    @Test
    public void deadReelAtTheEndOfTheFeedAsksForMoreInsteadOfSkipping() {
        assertEquals(SvipeReelSkipPolicy.LOAD_MORE,
                SvipeReelSkipPolicy.decide(CURRENT, NO_MO, NO_URL, NO_PLAYER, FAILED, LAST));
        assertFalse("LOAD_MORE must not read as a skip",
                SvipeReelSkipPolicy.shouldSkip(CURRENT, NO_MO, NO_URL, NO_PLAYER, FAILED, LAST));
    }

    /** Every verdict the class can return names itself — the device log must never print a number. */
    @Test
    public void everyVerdictHasAName() {
        int[] all = {
                SvipeReelSkipPolicy.SKIP, SvipeReelSkipPolicy.LOAD_MORE,
                SvipeReelSkipPolicy.KEEP_NOT_CURRENT, SvipeReelSkipPolicy.KEEP_HAS_MESSAGE,
                SvipeReelSkipPolicy.KEEP_PLAYING, SvipeReelSkipPolicy.KEEP_URL_ALIVE,
                SvipeReelSkipPolicy.KEEP_NO_FAILURE,
        };
        for (int v : all) {
            String name = SvipeReelSkipPolicy.reasonName(v);
            assertFalse("verdict " + v + " has no name", name.startsWith("unknown"));
        }
    }
}
