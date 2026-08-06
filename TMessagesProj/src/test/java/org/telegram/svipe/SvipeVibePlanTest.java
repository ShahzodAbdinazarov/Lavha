package org.telegram.svipe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM cover for the two decisions a seeded vibe makes. */
public class SvipeVibePlanTest {

    /* ---------------- carriesProgress ---------------- */

    @Test
    public void carriesProgressWhenQueueOpensOnTheSeed() {
        assertTrue(SvipeVibePlan.carriesProgress(true, true, "-100:55", "-100:55"));
    }

    @Test
    public void refusesWhenTheSeedWasNotPrepended() {
        // The seed just finished playing; replaying it from where it ended would be a stutter.
        assertFalse(SvipeVibePlan.carriesProgress(false, true, "-100:55", "-100:55"));
    }

    @Test
    public void refusesWhenThereIsNoPositionToInherit() {
        assertFalse(SvipeVibePlan.carriesProgress(true, false, "-100:55", "-100:55"));
    }

    @Test
    public void refusesWhenResolutionDroppedTheSeed() {
        // The prepended seed failed to resolve, so the queue opens on the vibe's own first track.
        // Carrying a position onto it would start a different song halfway through.
        assertFalse(SvipeVibePlan.carriesProgress(true, true, "-100:77", "-100:55"));
    }

    @Test
    public void refusesWhenTheFirstEntryHasNoCatalogIdentity() {
        assertFalse(SvipeVibePlan.carriesProgress(true, true, null, "-100:55"));
    }

    @Test
    public void refusesWhenTheSeedHasNoKey() {
        assertFalse(SvipeVibePlan.carriesProgress(true, true, "-100:55", null));
    }

    @Test
    public void twoNullKeysAreNotAMatch() {
        // Both unknown is not the same as both equal — nothing has been shown to line up here.
        assertFalse(SvipeVibePlan.carriesProgress(true, true, null, null));
    }

    @Test
    public void keysAreComparedByValueNotIdentity() {
        assertTrue(SvipeVibePlan.carriesProgress(true, true, new String("-100:55"), "-100:55"));
    }

    @Test
    public void differentChannelSameMessageIsNotAMatch() {
        assertFalse(SvipeVibePlan.carriesProgress(true, true, "-101:55", "-100:55"));
    }

    /* ---------------- handsOffToVibe ---------------- */

    @Test
    public void finiteQueueHandsOffSoTheMusicKeepsGoing() {
        assertTrue(SvipeVibePlan.handsOffToVibe(true, false, true));
    }

    @Test
    public void exhaustedQueueDoesNotHandOff() {
        // The backend said there is no more — asking it again changes nothing.
        assertFalse(SvipeVibePlan.handsOffToVibe(true, true, true));
    }

    @Test
    public void selfPagingQueueThatFailedToPageStillHandsOff() {
        // A page load lost to a flaky connection is not the end of the recommendations. Before repeat
        // stopped being forced on, this case wrapped; it must not become a silent stop.
        assertFalse(SvipeVibePlan.handsOffToVibe(true, true, true));
        assertTrue(SvipeVibePlan.handsOffToVibe(true, false, true));
    }

    @Test
    public void nonSvipeQueueDoesNotHandOff() {
        // A dialog's own music must keep stopping at the end, as it always has.
        assertFalse(SvipeVibePlan.handsOffToVibe(false, false, true));
    }

    @Test
    public void withoutASeedTrackThereIsNothingToBuildAVibeFrom() {
        assertFalse(SvipeVibePlan.handsOffToVibe(true, false, false));
    }

    @Test
    public void noQueueAndNoTrackDoesNotHandOff() {
        assertFalse(SvipeVibePlan.handsOffToVibe(false, false, false));
    }

    /* ---------------- shouldFetchMore ---------------- */

    @Test
    public void vibeQueuePagesAheadWithFourLeft() {
        // 10 entries, playing #5 -> four still to come.
        assertTrue(SvipeVibePlan.shouldFetchMore(10, 5, true, false, false));
        assertFalse(SvipeVibePlan.shouldFetchMore(10, 4, true, false, false));
    }

    @Test
    public void finiteListPagesAheadOnTheSameDistance() {
        // The bug: a favourites list used to wait for its last track to END before asking for more,
        // so the music stopped for as long as the request took. It now fetches where a vibe does.
        // selfPaging=false and endReached=true is exactly how a finite queue is constructed.
        assertTrue(SvipeVibePlan.shouldFetchMore(10, 5, false, true, false));
    }

    @Test
    public void aFinishedVibeQueueStopsAsking() {
        // Once the backend says there is nothing more, asking again puts the same question to the
        // same empty answer.
        assertFalse(SvipeVibePlan.shouldFetchMore(10, 9, true, true, false));
    }

    @Test
    public void neverTwoRequestsAtOnce() {
        assertFalse(SvipeVibePlan.shouldFetchMore(10, 9, true, false, true));
        assertFalse(SvipeVibePlan.shouldFetchMore(10, 9, false, true, true));
    }

    @Test
    public void ignoresATrackThatIsNotInThisQueue() {
        // indexOf miss: playback moved to another queue, or the entry was dropped.
        assertFalse(SvipeVibePlan.shouldFetchMore(10, -1, true, false, false));
    }

    @Test
    public void aShortListFetchesImmediately() {
        // Three favourites: the listener is already inside the prefetch window on track one, so the
        // continuation is on its way before the first song ends.
        assertTrue(SvipeVibePlan.shouldFetchMore(3, 0, false, true, false));
    }
}
