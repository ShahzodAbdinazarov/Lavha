package org.telegram.svipe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Which strings get their brand rewritten, and — more importantly — which ones must not.
 *
 * <p>The dangerous direction here is the false positive: rewriting a string that legitimately says
 * "Telegram" makes the app assert something untrue, in every language at once, and nothing else in
 * the build would catch it.
 */
public class SvipeBrandTest {

    // ---- the leak this class exists for ----

    @Test
    public void theBackgroundLocationDisclosureNamesThisApp() {
        // Google Play reviews this exact dialog in the background-location demo video. Naming a
        // different app there reads as an inaccurate declaration.
        String pack = "Telegram needs access to your location all the time, including while the app is in the background.";
        assertEquals(
                "Svipe needs access to your location all the time, including while the app is in the background.",
                SvipeBrand.apply("PermissionNoLocation", pack));
    }

    @Test
    public void permissionRationalesAreRebrandedInEveryLanguageThePackReturns() {
        // The point of rebranding the resolved value instead of the local resource: the Russian text
        // still arrives translated from the cloud pack, and only the Latin-script brand changes.
        assertEquals(
                "Разрешите Svipe отправлять вам уведомления.",
                SvipeBrand.apply("NotificationsPermissionAlertSubtitle", "Разрешите Telegram отправлять вам уведомления."));
    }

    // ---- what must survive untouched ----

    @Test
    public void anUnlistedKeyIsNeverRewritten() {
        // "Telegram Premium" is a real product of a real service the user is genuinely on. Renaming it
        // would be a lie, so nothing outside the allow-list may be touched.
        String premium = "Subscribe to Telegram Premium to unlock this.";
        assertEquals(premium, SvipeBrand.apply("PremiumMore", premium));

        String tos = "By signing up, you agree to the Telegram Terms of Service.";
        assertEquals(tos, SvipeBrand.apply("TermsOfServiceLogin", tos));
    }

    @Test
    public void theWordBoundaryKeepsPluralsAndCompoundsIntact() {
        // "Telegrams" is a different word; only the bare brand is a self-reference.
        assertEquals("Svipe and Telegrams", SvipeBrand.apply("PermissionNoLocation", "Telegram and Telegrams"));
    }

    @Test
    public void possessivesReadCorrectlyAfterTheSwap() {
        assertEquals("Svipe's camera", SvipeBrand.apply("PermissionNoCameraWithHint", "Telegram's camera"));
    }

    // ---- callers pass whatever LocaleController resolved, including nothing ----

    @Test
    public void nullsAndMissesArePassedStraightThrough() {
        assertEquals(null, SvipeBrand.apply("PermissionNoLocation", null));
        assertEquals("x", SvipeBrand.apply(null, "x"));
    }

    @Test
    public void everyPermissionDialogTheReviewerWillSeeIsCovered() {
        // These are the dialogs that appear in the demo videos the sensitive-permission declarations
        // require. If one is missing from the allow-list, the video shows the wrong app name.
        for (String key : new String[]{
                "PermissionNoCameraWithHint",
                "PermissionNoAudioWithHint",
                "PermissionNoCameraMicVideo",
                "VoipNeedMicPermissionWithHint",
                "VoipNeedCameraPermission",
                "PermissionDrawAboveOtherApps",
                "PermissionNoLocation",
        }) {
            assertEquals(key + " must be rebranded",
                    "Svipe needs access", SvipeBrand.apply(key, "Telegram needs access"));
        }
    }
}
