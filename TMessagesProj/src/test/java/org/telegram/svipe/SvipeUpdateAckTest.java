package org.telegram.svipe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import org.telegram.svipe.SvipeUpdateAck.Ack;

/** What a "check for updates" press says immediately, before the ~40 s worst-case request can answer. */
public class SvipeUpdateAckTest {

    // ---- the bug this class exists for ----

    @Test
    public void theFirstPressOnAnIdleUpdaterIsAcknowledgedImmediately() {
        // Previously nothing was said here at all: the press went straight into a GET that can take ~40 s
        // (15 s connect + 25 s read) to fail, and only a SECOND press produced "Checking for updates…".
        assertEquals(Ack.CHECKING, SvipeUpdateAck.forCheck(true, false, false, false));
    }

    @Test
    public void aSecondPressOntoTheSameCheckDoesNotRepeatItself() {
        assertEquals(Ack.NONE, SvipeUpdateAck.forCheck(true, true, false, true));
    }

    @Test
    public void aFirstPressCoalescingOntoTheAutomaticCheckIsStillAcknowledged() {
        // The cold-start check runs with force=false and announces nothing, so a manual press landing
        // while it is in flight has been told nothing yet — dropping the message here would restore the
        // very silence being fixed.
        assertEquals(Ack.CHECKING, SvipeUpdateAck.forCheck(true, true, false, false));
    }

    // ---- the download case, which the coalesce branch still owns ----

    @Test
    public void aPressDuringADownloadReportsTheDownload() {
        assertEquals(Ack.DOWNLOADING, SvipeUpdateAck.forCheck(true, false, true, false));
    }

    @Test
    public void aRunningDownloadOutranksARunningCheck() {
        assertEquals(Ack.DOWNLOADING, SvipeUpdateAck.forCheck(true, true, true, false));
        assertEquals(Ack.DOWNLOADING, SvipeUpdateAck.forCheck(true, true, true, true));
    }

    // ---- automatic checks are always silent ----

    @Test
    public void noAutomaticCheckEverToastsAnything() {
        for (boolean checking : new boolean[]{false, true}) {
            for (boolean downloading : new boolean[]{false, true}) {
                for (boolean announced : new boolean[]{false, true}) {
                    assertEquals(Ack.NONE, SvipeUpdateAck.forCheck(false, checking, downloading, announced));
                }
            }
        }
    }
}
