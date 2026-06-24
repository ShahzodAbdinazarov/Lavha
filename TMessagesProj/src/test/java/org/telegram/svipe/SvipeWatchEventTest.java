package org.telegram.svipe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SvipeWatchEventTest {

    @Test
    public void replayWhenWatchedWellPastDuration() {
        assertEquals("REPLAY", SvipeWatchEvent.classify(15_000, 10_000));
        assertEquals("REPLAY", SvipeWatchEvent.classify(30_000, 10_000));
    }

    @Test
    public void videoEndNearCompletion() {
        assertEquals("VIDEO_END", SvipeWatchEvent.classify(9_000, 10_000));
        assertEquals("VIDEO_END", SvipeWatchEvent.classify(10_000, 10_000));
        assertEquals("VIDEO_END", SvipeWatchEvent.classify(14_999, 10_000));
    }

    @Test
    public void swipeAwayWhenPartial() {
        assertEquals("SWIPE_AWAY", SvipeWatchEvent.classify(0, 10_000));
        assertEquals("SWIPE_AWAY", SvipeWatchEvent.classify(8_999, 10_000));
    }

    @Test
    public void swipeAwayWhenDurationUnknown() {
        // Without a duration we cannot claim completion; backend still grades by dwell.
        assertEquals("SWIPE_AWAY", SvipeWatchEvent.classify(60_000, 0));
        assertEquals("SWIPE_AWAY", SvipeWatchEvent.classify(60_000, -1));
    }
}
