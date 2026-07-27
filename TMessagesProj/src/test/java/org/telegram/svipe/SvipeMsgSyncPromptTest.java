package org.telegram.svipe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The pure consent-prompt decision: big dialog once, then a daily snackbar, then a monthly re-ask. */
public class SvipeMsgSyncPromptTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 1_000_000L * DAY;   // some day boundary

    @Test
    public void grantedModesNeverPrompt() {
        assertEquals(SvipeMsgSyncPrompt.NONE,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_WITH_PARTNER, true, 0, NOW, false));
        assertEquals(SvipeMsgSyncPrompt.NONE,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_SELF_ONLY, false, 0, NOW, false));
    }

    @Test
    public void firstAskIsTheBigDialog() {
        assertEquals(SvipeMsgSyncPrompt.BIG_DIALOG,
                SvipeMsgSyncPrompt.decide("", false, 0, NOW, false));   // undecided, never shown
    }

    @Test
    public void afterRejectionItIsASnackbarOncePerDay() {
        // rejected (off), big already shown, no re-ask scheduled, snackbar not shown today -> snackbar
        assertEquals(SvipeMsgSyncPrompt.SNACKBAR,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_OFF, true, 0, NOW, false));
        // already shown today -> nothing
        assertEquals(SvipeMsgSyncPrompt.NONE,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_OFF, true, 0, NOW, true));
    }

    @Test
    public void monthlyReAskIsTheBigDialogWhenDue() {
        assertEquals(SvipeMsgSyncPrompt.BIG_DIALOG,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_OFF, true, NOW - 1, NOW, true));   // due
        assertEquals(SvipeMsgSyncPrompt.SNACKBAR,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_OFF, true, NOW + DAY, NOW, false)); // not yet
    }

    @Test
    public void epochDayGroupsByCalendarDay() {
        assertEquals(SvipeMsgSyncPrompt.epochDay(NOW), SvipeMsgSyncPrompt.epochDay(NOW + DAY - 1));
        assertEquals(SvipeMsgSyncPrompt.epochDay(NOW) + 1, SvipeMsgSyncPrompt.epochDay(NOW + DAY));
    }
}
