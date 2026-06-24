package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SvipeQueuePlanTest {

    @Test
    public void compositeKeyIsChannelColonMessage() {
        assertEquals("123:45", SvipeQueuePlan.compositeKey(123L, 45));
        // Distinct messages on the same channel are distinct identities.
        assertFalse(SvipeQueuePlan.compositeKey(1L, 2).equals(SvipeQueuePlan.compositeKey(1L, 3)));
    }

    @Test
    public void overflowCountClampsAtZero() {
        assertEquals(0, SvipeQueuePlan.overflowCount(3, 10));
        assertEquals(0, SvipeQueuePlan.overflowCount(10, 10));
        assertEquals(5, SvipeQueuePlan.overflowCount(15, 10));
    }

    @Test
    public void byteBudgetAllowsUpToEdgeAndRejectsBeyond() {
        assertTrue(SvipeQueuePlan.withinByteBudget(0, 100, 100));
        assertTrue(SvipeQueuePlan.withinByteBudget(40, 60, 100));
        assertFalse(SvipeQueuePlan.withinByteBudget(40, 61, 100));
    }

    @Test
    public void needsMoreDownloadsUntilTargetReached() {
        assertTrue(SvipeQueuePlan.needsMoreDownloads(0));
        assertTrue(SvipeQueuePlan.needsMoreDownloads(SvipeQueuePlan.TARGET_AHEAD - 1));
        assertFalse(SvipeQueuePlan.needsMoreDownloads(SvipeQueuePlan.TARGET_AHEAD));
        assertFalse(SvipeQueuePlan.needsMoreDownloads(SvipeQueuePlan.TARGET_AHEAD + 5));
    }

    @Test
    public void replayAndVideoEndAlwaysCountAsWatched() {
        assertTrue(SvipeQueuePlan.countsAsWatched("REPLAY", 0, 3000));
        assertTrue(SvipeQueuePlan.countsAsWatched("VIDEO_END", 10, 3000));
    }

    @Test
    public void swipeAwayCountsOnlyPastMinDwell() {
        assertFalse(SvipeQueuePlan.countsAsWatched("SWIPE_AWAY", 2999, 3000));
        assertTrue(SvipeQueuePlan.countsAsWatched("SWIPE_AWAY", 3000, 3000));
        assertTrue(SvipeQueuePlan.countsAsWatched("SWIPE_AWAY", 8000, 3000));
    }
}
