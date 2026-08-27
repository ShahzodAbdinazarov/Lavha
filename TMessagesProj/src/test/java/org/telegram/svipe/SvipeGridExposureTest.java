package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The explore grid's dwell rule. Two failures are being guarded against at once: reporting nothing
 * (which is how a clip scrolled past every day still arrived in the feed as new) and reporting
 * everything (which would retire a hundred references for one flick of a thumb).
 */
public class SvipeGridExposureTest {

    private static final long T0 = 1_700_000_000_000L;

    private static List<String> tiles(String... keys) {
        return Arrays.asList(keys);
    }

    @Test
    public void aTileMustDwellBeforeItCounts() {
        SvipeGridExposure ex = new SvipeGridExposure();
        assertTrue(ex.update(tiles("1:1"), T0).isEmpty());
        assertTrue(ex.update(tiles("1:1"), T0 + SvipeGridExposure.MIN_DWELL_MS - 1).isEmpty());
        assertEquals(tiles("1:1"), ex.update(tiles("1:1"), T0 + SvipeGridExposure.MIN_DWELL_MS));
    }

    @Test
    public void aFlingReportsNothing() {
        SvipeGridExposure ex = new SvipeGridExposure();
        // Six screenfuls in 600ms — nobody looked at any of them.
        for (int frame = 0; frame < 6; frame++) {
            assertTrue(ex.update(tiles("1:" + frame, "1:" + (frame + 100)), T0 + frame * 100L).isEmpty());
        }
        assertEquals(0, ex.reportedCount());
    }

    @Test
    public void leavingTheViewportResetsTheClock() {
        SvipeGridExposure ex = new SvipeGridExposure();
        ex.update(tiles("1:1"), T0);
        // Scrolled away just before it ripened...
        ex.update(tiles("1:2"), T0 + SvipeGridExposure.MIN_DWELL_MS - 1);
        // ...and back. The dwell starts over rather than resuming.
        assertTrue(ex.update(tiles("1:1"), T0 + SvipeGridExposure.MIN_DWELL_MS).isEmpty());
        assertEquals(tiles("1:1"), ex.update(tiles("1:1"), T0 + 2 * SvipeGridExposure.MIN_DWELL_MS));
    }

    @Test
    public void aTileReportsOnlyOnce() {
        SvipeGridExposure ex = new SvipeGridExposure();
        ex.update(tiles("1:1"), T0);
        assertEquals(tiles("1:1"), ex.update(tiles("1:1"), T0 + SvipeGridExposure.MIN_DWELL_MS));
        // Scrolling up and down over one's own screen must not resend it.
        assertTrue(ex.update(tiles("1:1"), T0 + 10 * SvipeGridExposure.MIN_DWELL_MS).isEmpty());
        ex.update(tiles("2:2"), T0 + 11 * SvipeGridExposure.MIN_DWELL_MS);
        assertTrue(ex.update(tiles("1:1"), T0 + 20 * SvipeGridExposure.MIN_DWELL_MS).isEmpty());
        assertTrue(ex.alreadyReported("1:1"));
        assertEquals(1, ex.reportedCount());
    }

    @Test
    public void awholeRowRipensTogether() {
        SvipeGridExposure ex = new SvipeGridExposure();
        ex.update(tiles("1:1", "1:2", "1:3"), T0);
        List<String> ripe = ex.update(tiles("1:1", "1:2", "1:3"), T0 + SvipeGridExposure.MIN_DWELL_MS);
        assertEquals(3, ripe.size());
        assertTrue(ripe.containsAll(tiles("1:1", "1:2", "1:3")));
        assertEquals(0, ex.pendingCount());
    }

    @Test
    public void nextRipeInMsArmsExactlyOneRecheck() {
        SvipeGridExposure ex = new SvipeGridExposure();
        // Nothing on the clock -> nothing to wait for.
        assertEquals(-1, ex.nextRipeInMs(T0));
        ex.update(tiles("1:1"), T0);
        assertEquals(SvipeGridExposure.MIN_DWELL_MS, ex.nextRipeInMs(T0));
        assertEquals(SvipeGridExposure.MIN_DWELL_MS / 2, ex.nextRipeInMs(T0 + SvipeGridExposure.MIN_DWELL_MS / 2));
        // Already due: 0, not a negative delay.
        assertEquals(0, ex.nextRipeInMs(T0 + SvipeGridExposure.MIN_DWELL_MS * 3));
    }

    @Test
    public void nextRipeInMsTakesTheSoonestTile() {
        SvipeGridExposure ex = new SvipeGridExposure();
        ex.update(tiles("1:1"), T0);
        ex.update(tiles("1:1", "1:2"), T0 + 400);
        // "1:1" started 400ms earlier, so it is the one that decides the wait.
        assertEquals(SvipeGridExposure.MIN_DWELL_MS - 400, ex.nextRipeInMs(T0 + 400));
    }

    @Test
    public void aClockStampedInTheFutureRestartsRatherThanSticking() {
        SvipeGridExposure ex = new SvipeGridExposure();
        ex.update(tiles("1:1"), T0 + 10_000);   // device clock moved forward
        ex.update(tiles("1:1"), T0);            // ...and back
        assertTrue(ex.update(tiles("1:1"), T0 + SvipeGridExposure.MIN_DWELL_MS - 1).isEmpty());
        assertEquals(tiles("1:1"), ex.update(tiles("1:1"), T0 + SvipeGridExposure.MIN_DWELL_MS));
    }

    @Test
    public void anEmptyOrNullReadingClearsTheClock() {
        SvipeGridExposure ex = new SvipeGridExposure();
        ex.update(tiles("1:1"), T0);
        assertEquals(1, ex.pendingCount());
        assertTrue(ex.update(Collections.emptyList(), T0 + 10).isEmpty());
        assertEquals(0, ex.pendingCount());

        ex.update(tiles("1:1"), T0 + 20);
        assertTrue(ex.update(null, T0 + 30).isEmpty());
        assertEquals(0, ex.pendingCount());
    }

    @Test
    public void clearPendingKeepsWhatWasAlreadyReported() {
        SvipeGridExposure ex = new SvipeGridExposure();
        ex.update(tiles("1:1"), T0);
        ex.update(tiles("1:1"), T0 + SvipeGridExposure.MIN_DWELL_MS);
        ex.update(tiles("2:2"), T0 + SvipeGridExposure.MIN_DWELL_MS);
        ex.clearPending();
        assertEquals(0, ex.pendingCount());
        assertTrue(ex.alreadyReported("1:1"));
        // ...and reset forgets everything, for a genuinely different content set.
        ex.reset();
        assertFalse(ex.alreadyReported("1:1"));
    }

    @Test
    public void theReportedSetIsBounded() {
        SvipeGridExposure ex = new SvipeGridExposure();
        for (int i = 0; i < SvipeGridExposure.MAX_REMEMBERED + 100; i++) {
            final String key = "1:" + i;
            ex.update(tiles(key), T0 + i * 10_000L);
            ex.update(tiles(key), T0 + i * 10_000L + SvipeGridExposure.MIN_DWELL_MS);
        }
        assertEquals(SvipeGridExposure.MAX_REMEMBERED, ex.reportedCount());
    }

    @Test
    public void theVisibilityBarIsAMajorityOfTheTile() {
        // The grid measures pixels against this; a tile peeking in at the edge is not "seen".
        assertTrue(SvipeGridExposure.MIN_VISIBLE_FRACTION > 0.5f);
        assertTrue(SvipeGridExposure.MIN_VISIBLE_FRACTION <= 1.0f);
    }
}
