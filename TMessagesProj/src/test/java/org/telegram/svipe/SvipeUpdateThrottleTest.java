package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure-JVM coverage for the update-check throttle ({@link SvipeUpdateThrottle}).
 *
 * <p>Motivation: the updater stamped "last check" before validating the response, so a request that
 * never reached the server armed the full 30-minute interval. On the device that prompted this — a
 * router whose DNS forwarder resolves the backend about five times in eight — a launch that lost the
 * coin flip suppressed every retry for half an hour. These tests pin the outcome-dependent interval and
 * the clock-skew behaviour. No Android classes involved (this project cannot run Robolectric).
 */
public class SvipeUpdateThrottleTest {

    private static final long MINUTE = 60L * 1000;

    // ---- intervalFor ----

    @Test
    public void successBuysTheLongIntervalAndFailureOnlyTheBackoff() {
        assertEquals(SvipeUpdateThrottle.SUCCESS_INTERVAL_MS, SvipeUpdateThrottle.intervalFor(true));
        assertEquals(SvipeUpdateThrottle.FAILURE_BACKOFF_MS, SvipeUpdateThrottle.intervalFor(false));
    }

    @Test
    public void theBackoffIsShortEnoughToBeInvisibleAndLongEnoughNotToHammer() {
        // Contract, not a restatement of the constant: a user coming back to the app must be able to
        // retry within a couple of minutes, but two resumes seconds apart must not both fire.
        assertTrue(SvipeUpdateThrottle.FAILURE_BACKOFF_MS <= 2 * MINUTE);
        assertTrue(SvipeUpdateThrottle.FAILURE_BACKOFF_MS >= 30L * 1000);
        assertTrue(SvipeUpdateThrottle.FAILURE_BACKOFF_MS < SvipeUpdateThrottle.SUCCESS_INTERVAL_MS);
    }

    // ---- shouldCheck: never checked ----

    @Test
    public void aFreshInstallAlwaysChecks() {
        assertTrue(SvipeUpdateThrottle.shouldCheck(0, 1_000_000L, true));
        assertTrue(SvipeUpdateThrottle.shouldCheck(0, 1_000_000L, false));
        assertTrue(SvipeUpdateThrottle.shouldCheck(-1, 1_000_000L, true)); // corrupt stamp
    }

    // ---- shouldCheck: after a successful check ----

    @Test
    public void afterSuccessWeStayQuietForTheFullInterval() {
        long last = 10_000_000L;
        assertFalse(SvipeUpdateThrottle.shouldCheck(last, last, true));
        assertFalse(SvipeUpdateThrottle.shouldCheck(last, last + MINUTE, true));
        assertFalse(SvipeUpdateThrottle.shouldCheck(last, last + 29 * MINUTE, true));
        assertFalse(SvipeUpdateThrottle.shouldCheck(last, last + SvipeUpdateThrottle.SUCCESS_INTERVAL_MS - 1, true));
    }

    @Test
    public void afterSuccessWeCheckAgainOnceTheIntervalElapses() {
        long last = 10_000_000L;
        assertTrue(SvipeUpdateThrottle.shouldCheck(last, last + SvipeUpdateThrottle.SUCCESS_INTERVAL_MS, true));
        assertTrue(SvipeUpdateThrottle.shouldCheck(last, last + 31 * MINUTE, true));
    }

    // ---- shouldCheck: after a failed check (the whole point of the fix) ----

    @Test
    public void afterFailureTheNextResumeRetriesWithinAMinute() {
        long last = 10_000_000L;
        // This is the case that used to go silent for 30 minutes.
        assertFalse(SvipeUpdateThrottle.shouldCheck(last, last + 5_000, false));  // seconds later: still throttled
        assertTrue(SvipeUpdateThrottle.shouldCheck(last, last + MINUTE, false));  // a minute later: retry
        assertTrue(SvipeUpdateThrottle.shouldCheck(last, last + 2 * MINUTE, false));
        // ...and at the same instants a *successful* check would still be holding us off.
        assertFalse(SvipeUpdateThrottle.shouldCheck(last, last + 2 * MINUTE, true));
    }

    @Test
    public void rapidAppSwitchingOnADeadNetworkCannotStormTheServer() {
        // Resume every 5s for 10 minutes with every check failing: the backoff must gate all but a
        // handful of them. (The updater relies on exactly this — it deliberately does not release
        // checkedThisProcess on failure, because that flag bypasses the throttle entirely.)
        long last = 0;
        long now = 1_000_000L;
        int fired = 0;
        for (int i = 0; i <= 120; i++) { // t = 0s .. 600s inclusive
            if (SvipeUpdateThrottle.shouldCheck(last, now, false)) {
                fired++;
                last = now;
            }
            now += 5_000;
        }
        assertEquals(11, fired); // 1 initial + one per minute of the 10 minutes
    }

    // ---- shouldCheck: clock skew ----

    @Test
    public void aBackwardsClockDoesNotWedgeTheUpdater() {
        // NTP correction / manual clock change: a stamp in the future must not park us until it passes.
        long last = 10_000_000L;
        assertTrue(SvipeUpdateThrottle.shouldCheck(last, last - 1, true));
        assertTrue(SvipeUpdateThrottle.shouldCheck(last, last - 10L * 365 * 24 * 3600 * 1000, true));
        assertTrue(SvipeUpdateThrottle.shouldCheck(last, last - 1, false));
    }
}
