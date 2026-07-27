package org.telegram.svipe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The pure consent-prompt decision: big dialog once, then a daily snackbar, a monthly re-ask, and a
 *  "don't ask for a month" mute. */
public class SvipeMsgSyncPromptTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 1_000_000L * DAY;   // some day boundary
    private static final long NO_MUTE = 0L;

    @Test
    public void grantedModesNeverPrompt() {
        assertEquals(SvipeMsgSyncPrompt.NONE,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_WITH_PARTNER, true, 0, NO_MUTE, NOW, false));
        assertEquals(SvipeMsgSyncPrompt.NONE,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_SELF_ONLY, false, 0, NO_MUTE, NOW, false));
    }

    @Test
    public void firstAskIsTheBigDialog() {
        assertEquals(SvipeMsgSyncPrompt.BIG_DIALOG,
                SvipeMsgSyncPrompt.decide("", false, 0, NO_MUTE, NOW, false));   // undecided, never shown
    }

    @Test
    public void afterRejectionItIsASnackbarOncePerDay() {
        assertEquals(SvipeMsgSyncPrompt.SNACKBAR,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_OFF, true, 0, NO_MUTE, NOW, false));
        assertEquals(SvipeMsgSyncPrompt.NONE,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_OFF, true, 0, NO_MUTE, NOW, true));
    }

    @Test
    public void monthlyReAskIsTheBigDialogWhenDue() {
        assertEquals(SvipeMsgSyncPrompt.BIG_DIALOG,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_OFF, true, NOW - 1, NO_MUTE, NOW, true));
        assertEquals(SvipeMsgSyncPrompt.SNACKBAR,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_OFF, true, NOW + DAY, NO_MUTE, NOW, false));
    }

    @Test
    public void mutedSuppressesEveryPrompt() {
        // "Don't ask for a month" wins over both the snackbar and a due monthly re-ask.
        assertEquals(SvipeMsgSyncPrompt.NONE,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_OFF, true, 0, NOW + DAY, NOW, false));
        assertEquals(SvipeMsgSyncPrompt.NONE,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_OFF, true, NOW - 1, NOW + DAY, NOW, false));
        // once the mute has passed, prompts resume
        assertEquals(SvipeMsgSyncPrompt.SNACKBAR,
                SvipeMsgSyncPrompt.decide(SvipeMessageSync.MODE_OFF, true, 0, NOW - 1, NOW, false));
    }

    @Test
    public void epochDayGroupsByCalendarDay() {
        assertEquals(SvipeMsgSyncPrompt.epochDay(NOW), SvipeMsgSyncPrompt.epochDay(NOW + DAY - 1));
        assertEquals(SvipeMsgSyncPrompt.epochDay(NOW) + 1, SvipeMsgSyncPrompt.epochDay(NOW + DAY));
    }
}
