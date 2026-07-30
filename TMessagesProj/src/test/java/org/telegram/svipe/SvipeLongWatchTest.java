package org.telegram.svipe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SvipeLongWatchTest {

    private static final long FORTY_MIN = 40 * 60 * 1000L;

    @Test
    public void videoEndWhenThePlayerReachedTheEnd() {
        assertEquals("VIDEO_END", SvipeLongWatch.classify(FORTY_MIN, FORTY_MIN, FORTY_MIN, true, 0, 800));
        // endedNaturally wins even when the position lags (a looping/seeking edge case).
        assertEquals("VIDEO_END", SvipeLongWatch.classify(1_000, FORTY_MIN, 0, true, 0, 0));
    }

    @Test
    public void videoEndWhenPositionIsWithinTwoPercentOfTheEnd() {
        final long near = (long) (FORTY_MIN * 0.985);
        assertEquals("VIDEO_END", SvipeLongWatch.classify(near, FORTY_MIN, near, false, 0, 500));
        final long notNear = (long) (FORTY_MIN * 0.97);
        assertEquals("HEARTBEAT", SvipeLongWatch.classify(notNear, FORTY_MIN, notNear, false, 0, 500));
    }

    /** THE regression this class exists for: reels' classifier would have said SWIPE_AWAY. */
    @Test
    public void goodPartialWatchIsNeutralNotASwipeAway() {
        assertEquals("HEARTBEAT", SvipeLongWatch.classify(8 * 60_000, FORTY_MIN, 8 * 60_000, false, 0, 700));
        assertEquals("SWIPE_AWAY", SvipeWatchEvent.classify(8 * 60_000, FORTY_MIN));
    }

    @Test
    public void shortWatchIsAGenuineAbandon() {
        assertEquals("SWIPE_AWAY", SvipeLongWatch.classify(0, FORTY_MIN, 0, false, 0, 0));
        assertEquals("SWIPE_AWAY", SvipeLongWatch.classify(4_000, FORTY_MIN, 4_000, false, 0, 400));
        assertEquals("SWIPE_AWAY", SvipeLongWatch.classify(9_999, FORTY_MIN, 9_999, false, 0, 400));
        assertEquals("HEARTBEAT", SvipeLongWatch.classify(10_000, FORTY_MIN, 10_000, false, 0, 400));
    }

    @Test
    public void shortWatchSpentStallingIsANetworkBailNotARejection() {
        // Waited nine seconds for a first frame that never came, then left.
        assertEquals("HEARTBEAT", SvipeLongWatch.classify(0, FORTY_MIN, 0, false, 0, 9_000));
        // Four seconds of picture, six of spinner.
        assertEquals("HEARTBEAT", SvipeLongWatch.classify(4_000, FORTY_MIN, 4_000, false, 6_000, 500));
        // Mostly picture with a brief hiccup: still a rejection.
        assertEquals("SWIPE_AWAY", SvipeLongWatch.classify(8_000, FORTY_MIN, 8_000, false, 500, 300));
    }

    @Test
    public void unknownDurationFallsBackToWatchTimeAlone() {
        assertEquals("SWIPE_AWAY", SvipeLongWatch.classify(3_000, 0, 3_000, false, 0, 0));
        assertEquals("HEARTBEAT", SvipeLongWatch.classify(60_000, 0, 60_000, false, 0, 0));
    }

    @Test
    public void negativeCountersCannotFlipTheVerdict() {
        // Clocks can come back negative if a position read races a release; they must not excuse a bail.
        assertEquals("SWIPE_AWAY", SvipeLongWatch.classify(2_000, FORTY_MIN, -1, false, -5_000, -5_000));
    }
}
