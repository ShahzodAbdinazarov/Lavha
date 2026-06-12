package org.telegram.lavha;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LavhaPreloadPlanTest {

    private static final int AHEAD = 5;

    @Test
    public void aheadWindowCoversNextNOnly() {
        assertTrue(LavhaPreloadPlan.inAheadWindow(11, 10, AHEAD));
        assertTrue(LavhaPreloadPlan.inAheadWindow(15, 10, AHEAD));
        assertFalse(LavhaPreloadPlan.inAheadWindow(10, 10, AHEAD)); // current itself
        assertFalse(LavhaPreloadPlan.inAheadWindow(16, 10, AHEAD)); // beyond window
        assertFalse(LavhaPreloadPlan.inAheadWindow(9, 10, AHEAD));  // behind
    }

    @Test
    public void nextInLineGetsNormalPriorityAndBypassesGate() {
        assertEquals(LavhaPreloadPlan.NORMAL, LavhaPreloadPlan.priorityFor(11, 10));
        assertTrue(LavhaPreloadPlan.bypassesGate(11, 10));
    }

    @Test
    public void restOfWindowGetsLowPriorityBehindGate() {
        for (int i = 12; i <= 15; i++) {
            assertEquals(LavhaPreloadPlan.LOW, LavhaPreloadPlan.priorityFor(i, 10));
            assertFalse(LavhaPreloadPlan.bypassesGate(i, 10));
        }
    }

    @Test
    public void cancelsStartedPreloadsOutsideWindow() {
        // Swiped forward fast: an old window item now behind gets cancelled...
        assertTrue(LavhaPreloadPlan.shouldCancelPreload(8, 10, AHEAD, true));
        // ...but never the current reel, never unstarted items, never the live window.
        assertFalse(LavhaPreloadPlan.shouldCancelPreload(10, 10, AHEAD, true));
        assertFalse(LavhaPreloadPlan.shouldCancelPreload(8, 10, AHEAD, false));
        assertFalse(LavhaPreloadPlan.shouldCancelPreload(13, 10, AHEAD, true));
    }

    @Test
    public void preparesNextPlayerOnlyOnHighEndDevicesWithinBounds() {
        assertTrue(LavhaPreloadPlan.shouldPrepareNextPlayer(true, 5, 20));
        assertFalse(LavhaPreloadPlan.shouldPrepareNextPlayer(false, 5, 20)); // low-end device
        assertFalse(LavhaPreloadPlan.shouldPrepareNextPlayer(true, 20, 20)); // past the end
        assertFalse(LavhaPreloadPlan.shouldPrepareNextPlayer(true, -1, 20)); // no next
    }

    @Test
    public void backSwipeRebuildsWindowForward() {
        // After swiping back from 10 to 9, item 10's preload (now "ahead") must survive.
        assertTrue(LavhaPreloadPlan.inAheadWindow(10, 9, AHEAD));
        assertFalse(LavhaPreloadPlan.shouldCancelPreload(10, 9, AHEAD, true));
    }
}
