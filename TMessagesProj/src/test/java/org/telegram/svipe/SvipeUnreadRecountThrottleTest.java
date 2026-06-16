package org.telegram.svipe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SvipeUnreadRecountThrottleTest {

    @Test
    public void firstCallAlwaysRuns() {
        SvipeUnreadRecountThrottle throttle = new SvipeUnreadRecountThrottle(30_000);
        assertTrue(throttle.shouldRun(0));
    }

    @Test
    public void firstCallRunsEvenWithLargeNegativeClock() {
        // elapsedRealtime is small right after boot; make sure no sentinel
        // value blocks the very first recount.
        SvipeUnreadRecountThrottle throttle = new SvipeUnreadRecountThrottle(30_000);
        assertTrue(throttle.shouldRun(Long.MIN_VALUE + 1));
    }

    @Test
    public void blocksWithinInterval() {
        SvipeUnreadRecountThrottle throttle = new SvipeUnreadRecountThrottle(30_000);
        assertTrue(throttle.shouldRun(1_000));
        assertFalse(throttle.shouldRun(1_001));
        assertFalse(throttle.shouldRun(30_999));
    }

    @Test
    public void runsAgainAfterInterval() {
        SvipeUnreadRecountThrottle throttle = new SvipeUnreadRecountThrottle(30_000);
        assertTrue(throttle.shouldRun(1_000));
        assertTrue(throttle.shouldRun(31_000));
    }

    @Test
    public void blockedCallDoesNotResetWindow() {
        SvipeUnreadRecountThrottle throttle = new SvipeUnreadRecountThrottle(30_000);
        assertTrue(throttle.shouldRun(0));
        assertFalse(throttle.shouldRun(29_999));
        // The window is measured from the last RUN, not the last attempt.
        assertTrue(throttle.shouldRun(30_000));
    }
}
