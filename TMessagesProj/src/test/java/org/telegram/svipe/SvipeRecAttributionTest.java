package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * The rule that decides whether an event may still carry its {@code recommendation_id}.
 *
 * <p>Getting this wrong is silent in both directions — too strict and attribution is lost, too loose
 * and events go out pointing at a context the server has already dropped — so every boundary is
 * pinned here rather than left to the one caller that happened to be checked by hand.
 */
public class SvipeRecAttributionTest {

    private static final long HOUR_MS = 3_600_000L;

    @Before
    public void setUp() {
        SvipeRecAttribution.reset();
    }

    @Test
    public void defaultTtlMirrorsTheServerConstant() {
        // app/recsys/engine.py: REC_TTL_SECONDS = 3600
        assertEquals(3600L, SvipeRecAttribution.DEFAULT_TTL_SECONDS);
        assertEquals(HOUR_MS, SvipeRecAttribution.ttlMs());
    }

    @Test
    public void freshWhileInsideTheTtlMinusTheMargin() {
        final long now = 10_000_000L;
        assertTrue(SvipeRecAttribution.fresh(now, now, HOUR_MS));
        assertTrue(SvipeRecAttribution.fresh(now - 1000, now, HOUR_MS));
        // The last usable instant is TTL minus the safety margin.
        assertTrue(SvipeRecAttribution.fresh(now - (HOUR_MS - SvipeRecAttribution.SAFETY_MARGIN_MS) + 1,
                now, HOUR_MS));
        assertFalse(SvipeRecAttribution.fresh(now - (HOUR_MS - SvipeRecAttribution.SAFETY_MARGIN_MS),
                now, HOUR_MS));
        assertFalse(SvipeRecAttribution.fresh(now - HOUR_MS, now, HOUR_MS));
    }

    @Test
    public void theMeasuredColdStartAgesAreAllRefused() {
        final long now = 1_000_000_000L;
        // The measurement behind this fix: half of all pages emitted events past the hour, p95 at
        // 4.6 days, worst case 167 hours. None of those may be attributed.
        assertFalse(SvipeRecAttribution.fresh(now - HOUR_MS - 1, now, HOUR_MS));
        assertFalse(SvipeRecAttribution.fresh(now - (long) (4.6 * 24 * HOUR_MS), now, HOUR_MS));
        assertFalse(SvipeRecAttribution.fresh(now - 167 * HOUR_MS, now, HOUR_MS));
    }

    @Test
    public void aStampInTheFutureIsNeverFresh() {
        final long now = 10_000_000L;
        // A clock that jumped forward and back would otherwise make a dead page eternally usable.
        assertFalse(SvipeRecAttribution.fresh(now + 1, now, HOUR_MS));
        assertFalse(SvipeRecAttribution.fresh(0, now, HOUR_MS));
        assertFalse(SvipeRecAttribution.fresh(-5, now, HOUR_MS));
    }

    @Test
    public void aTtlSmallerThanTheMarginAttributesNothing() {
        // Rather than wrapping into a negative window and attributing everything.
        assertFalse(SvipeRecAttribution.fresh(1000, 1000, SvipeRecAttribution.SAFETY_MARGIN_MS));
        assertFalse(SvipeRecAttribution.fresh(1000, 1000, 1000L));
    }

    @Test
    public void anUnknownPageIsNotAttributable() {
        // Never minted here -> the process cannot know its age -> send it unattributed.
        assertNull(SvipeRecAttribution.attributableId("rec-never-seen", 1_000L));
        assertNull(SvipeRecAttribution.attributableId(null, 1_000L));
        assertNull(SvipeRecAttribution.attributableId("", 1_000L));
    }

    @Test
    public void aRememberedPageIsAttributableUntilItExpires() {
        final long minted = 5_000_000L;
        SvipeRecAttribution.remember("rec-1", minted);
        assertEquals("rec-1", SvipeRecAttribution.attributableId("rec-1", minted + 60_000L));
        assertNull(SvipeRecAttribution.attributableId("rec-1", minted + HOUR_MS));
    }

    @Test
    public void theEarliestStampWins() {
        // The same page id arrives once per item and again from the queue on disk. Taking the newest
        // would keep resetting the clock on a context the server has already dropped.
        SvipeRecAttribution.remember("rec-1", 5_000_000L);
        SvipeRecAttribution.remember("rec-1", 5_000_000L + HOUR_MS);
        assertEquals(5_000_000L, SvipeRecAttribution.mintedAt("rec-1", -1));
        assertNull(SvipeRecAttribution.attributableId("rec-1", 5_000_000L + HOUR_MS));
    }

    @Test
    public void zeroAndNegativeStampsAreNotRemembered() {
        // A legacy queue entry written before the age was persisted reads as 0 — unknown, not fresh.
        SvipeRecAttribution.remember("rec-legacy", 0L);
        SvipeRecAttribution.remember("rec-legacy-2", -1L);
        assertEquals(-7L, SvipeRecAttribution.mintedAt("rec-legacy", -7L));
        assertNull(SvipeRecAttribution.attributableId("rec-legacy", 1_000L));
        assertNull(SvipeRecAttribution.attributableId("rec-legacy-2", 1_000L));
    }

    @Test
    public void mintedAtFallsBackWhenThePageIsUnknown() {
        assertEquals(42L, SvipeRecAttribution.mintedAt("nope", 42L));
        assertEquals(42L, SvipeRecAttribution.mintedAt(null, 42L));
    }

    @Test
    public void theServerTtlIsAdoptedAndBadValuesAreIgnored() {
        SvipeRecAttribution.setTtlSeconds(7200);
        assertEquals(2 * HOUR_MS, SvipeRecAttribution.ttlMs());
        final long minted = 1_000_000L;
        SvipeRecAttribution.remember("rec-1", minted);
        // Still attributable at 90 minutes now that the server said two hours.
        assertEquals("rec-1", SvipeRecAttribution.attributableId("rec-1", minted + 90 * 60_000L));

        SvipeRecAttribution.setTtlSeconds(0);
        SvipeRecAttribution.setTtlSeconds(-1);
        assertEquals(2 * HOUR_MS, SvipeRecAttribution.ttlMs());
    }

    @Test
    public void theRegisterIsBounded() {
        final int minted = SvipeRecAttribution.MAX_TRACKED_PAGES + 50;
        for (int i = 0; i < minted; i++) {
            SvipeRecAttribution.remember("rec-" + i, 1_000_000L + i);
        }
        final long now = 1_000_000L + minted;   // after the last stamp, well inside the TTL
        assertEquals(SvipeRecAttribution.MAX_TRACKED_PAGES, SvipeRecAttribution.trackedPages());
        // The newest survive; an evicted one simply reads as unknown, which is the safe answer.
        assertEquals("rec-" + (minted - 1), SvipeRecAttribution.attributableId("rec-" + (minted - 1), now));
        assertNull(SvipeRecAttribution.attributableId("rec-0", now));
    }

    @Test
    public void pruneDropsOnlyWhatCanNoLongerAttribute() {
        SvipeRecAttribution.remember("old", 1_000_000L);
        SvipeRecAttribution.remember("new", 1_000_000L + HOUR_MS);
        SvipeRecAttribution.prune(1_000_000L + HOUR_MS);
        assertEquals(1, SvipeRecAttribution.trackedPages());
        assertEquals("new", SvipeRecAttribution.attributableId("new", 1_000_000L + HOUR_MS));
    }

    @Test
    public void queueEntriesAreStampedWithThePageAgeNotTheQueueingTime() {
        // A reel queued at the END of a long page must not read as freshly recommended.
        final long pageArrived = 2_000_000L;
        final long queuedAt = pageArrived + 20 * 60_000L;
        SvipeRecAttribution.remember("rec-page", pageArrived);

        SvipeReelQueue.Entry e = new SvipeReelQueue.Entry();
        e.recId = "rec-page";
        SvipeReelQueue.stampPageAge(e, queuedAt);
        assertEquals(pageArrived, e.recAtMs);

        // Stamping is idempotent: a re-enqueue must not refresh an age that is already known.
        SvipeReelQueue.stampPageAge(e, queuedAt + HOUR_MS);
        assertEquals(pageArrived, e.recAtMs);
    }

    @Test
    public void anEntryWithNoPageFallsBackToTheQueueingTime() {
        SvipeReelQueue.Entry e = new SvipeReelQueue.Entry();
        SvipeReelQueue.stampPageAge(e, 1_234L);
        assertEquals(1_234L, e.recAtMs);
        // ...and with no page id there is nothing to attribute anyway.
        assertNull(SvipeRecAttribution.attributableId(e.recId, 1_234L));
    }
}
