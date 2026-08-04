package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SvipeColdStartTest {

    @Test
    public void backoffWidensThenCaps() {
        assertEquals(3_000L, SvipeColdStart.retryDelayMs(0));
        assertEquals(6_000L, SvipeColdStart.retryDelayMs(1));
        assertEquals(12_000L, SvipeColdStart.retryDelayMs(2));
        assertEquals(24_000L, SvipeColdStart.retryDelayMs(3));
        assertEquals(SvipeColdStart.MAX_RETRY_MS, SvipeColdStart.retryDelayMs(4));
        assertEquals(SvipeColdStart.MAX_RETRY_MS, SvipeColdStart.retryDelayMs(50));
    }

    @Test
    public void negativeAttemptIsTreatedAsFirst() {
        assertEquals(3_000L, SvipeColdStart.retryDelayMs(-1));
    }

    @Test
    public void freshRequestIsNotStale() {
        long now = 1_000_000L;
        assertFalse(SvipeColdStart.isRequestStale(now - 1_000L, now));
        assertFalse(SvipeColdStart.isRequestStale(now - (SvipeColdStart.REQUEST_TIMEOUT_MS - 1), now));
    }

    @Test
    public void requestPastItsBudgetIsStale() {
        long now = 1_000_000L;
        assertTrue(SvipeColdStart.isRequestStale(now - SvipeColdStart.REQUEST_TIMEOUT_MS, now));
        assertTrue(SvipeColdStart.isRequestStale(now - 10 * SvipeColdStart.REQUEST_TIMEOUT_MS, now));
    }

    /** A latch left set with no start time recorded must never block loading forever. */
    @Test
    public void unstampedRequestIsStale() {
        assertTrue(SvipeColdStart.isRequestStale(0, 1_000_000L));
    }

    @Test
    public void startsWhenIdleOrWhenInFlightRequestIsPresumedDead() {
        long now = 1_000_000L;
        assertTrue(SvipeColdStart.canStartRequest(false, 0, now));
        assertFalse(SvipeColdStart.canStartRequest(true, now - 1_000L, now));
        assertTrue(SvipeColdStart.canStartRequest(true, now - SvipeColdStart.REQUEST_TIMEOUT_MS, now));
    }
}
