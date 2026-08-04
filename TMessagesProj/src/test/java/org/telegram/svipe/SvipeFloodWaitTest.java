package org.telegram.svipe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SvipeFloodWaitTest {

    @Test
    public void readsAPlainFloodWait() {
        assertEquals(4473, SvipeFloodWait.secondsIn("FLOOD_WAIT_4473"));
        assertEquals(1, SvipeFloodWait.secondsIn("FLOOD_WAIT_1"));
    }

    @Test
    public void readsPremiumAndSlowmodeWaits() {
        assertEquals(60, SvipeFloodWait.secondsIn("FLOOD_PREMIUM_WAIT_60"));
        assertEquals(30, SvipeFloodWait.secondsIn("SLOWMODE_WAIT_30"));
    }

    @Test
    public void ignoresEverythingElse() {
        assertEquals(0, SvipeFloodWait.secondsIn(null));
        assertEquals(0, SvipeFloodWait.secondsIn(""));
        assertEquals(0, SvipeFloodWait.secondsIn("USERNAME_NOT_OCCUPIED"));
        assertEquals(0, SvipeFloodWait.secondsIn("FLOOD_WAIT_")); // no number to act on
    }

    /** The premium form contains the plain form as a substring — it must not win the match. */
    @Test
    public void premiumFormIsNotMisreadAsPlain() {
        assertEquals(120, SvipeFloodWait.secondsIn("FLOOD_PREMIUM_WAIT_120"));
    }
}
