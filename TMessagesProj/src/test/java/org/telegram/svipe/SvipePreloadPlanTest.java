package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SvipePreloadPlanTest {

    private static final int AHEAD = 5;

    @Test
    public void aheadWindowCoversNextNOnly() {
        assertTrue(SvipePreloadPlan.inAheadWindow(11, 10, AHEAD));
        assertTrue(SvipePreloadPlan.inAheadWindow(15, 10, AHEAD));
        assertFalse(SvipePreloadPlan.inAheadWindow(10, 10, AHEAD)); // current itself
        assertFalse(SvipePreloadPlan.inAheadWindow(16, 10, AHEAD)); // beyond window
        assertFalse(SvipePreloadPlan.inAheadWindow(9, 10, AHEAD));  // behind
    }

    @Test
    public void nextInLineGetsNormalPriorityAndBypassesGate() {
        assertEquals(SvipePreloadPlan.NORMAL, SvipePreloadPlan.priorityFor(11, 10));
        assertTrue(SvipePreloadPlan.bypassesGate(11, 10));
    }

    @Test
    public void restOfWindowGetsLowPriorityBehindGate() {
        for (int i = 12; i <= 15; i++) {
            assertEquals(SvipePreloadPlan.LOW, SvipePreloadPlan.priorityFor(i, 10));
            assertFalse(SvipePreloadPlan.bypassesGate(i, 10));
        }
    }

    @Test
    public void cancelsStartedPreloadsOutsideWindow() {
        // Swiped forward fast: an old window item now behind gets cancelled...
        assertTrue(SvipePreloadPlan.shouldCancelPreload(8, 10, AHEAD, true));
        // ...but never the current reel, never unstarted items, never the live window.
        assertFalse(SvipePreloadPlan.shouldCancelPreload(10, 10, AHEAD, true));
        assertFalse(SvipePreloadPlan.shouldCancelPreload(8, 10, AHEAD, false));
        assertFalse(SvipePreloadPlan.shouldCancelPreload(13, 10, AHEAD, true));
    }

    @Test
    public void preparesNextPlayerOnlyOnHighEndDevicesWithinBounds() {
        assertTrue(SvipePreloadPlan.shouldPrepareNextPlayer(true, 5, 20));
        assertFalse(SvipePreloadPlan.shouldPrepareNextPlayer(false, 5, 20)); // low-end device
        assertFalse(SvipePreloadPlan.shouldPrepareNextPlayer(true, 20, 20)); // past the end
        assertFalse(SvipePreloadPlan.shouldPrepareNextPlayer(true, -1, 20)); // no next
    }

    @Test
    public void backSwipeRebuildsWindowForward() {
        // After swiping back from 10 to 9, item 10's preload (now "ahead") must survive.
        assertTrue(SvipePreloadPlan.inAheadWindow(10, 9, AHEAD));
        assertFalse(SvipePreloadPlan.shouldCancelPreload(10, 9, AHEAD, true));
    }
}
