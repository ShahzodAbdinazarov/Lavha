package org.telegram.svipe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import org.telegram.svipe.SvipeUpdateResponse.Outcome;

/**
 * The rule that decides whether a 2xx body actually answered "is there an update?".
 *
 * <p>Everything here is a shape that really can come back from the backend: SvipeApi parses any body
 * starting with '{' into a JSONObject, so an empty object, an error envelope or a half-filled payload all
 * arrive looking valid. Reading them with optBoolean/optInt defaults used to mean "up to date", which
 * retired the offer and deleted a downloaded, SHA-256-verified ~60 MB APK.
 */
public class SvipeUpdateResponseTest {

    private static final int INSTALLED = 549;

    /** Convenience mirroring how the updater reads the JSON: {} -> no key, no version code. */
    private static Outcome classify(boolean hasAvailable, boolean available, int versionCode) {
        return SvipeUpdateResponse.classify(hasAvailable, available, versionCode, INSTALLED);
    }

    // ---- ambiguous shapes: NOT an answer, must never retire anything ----

    @Test
    public void emptyObjectIsNotAnAnswer() {
        // '{}' — a deploy window, a proxy stub, a stripped body.
        assertEquals(Outcome.NOT_AN_ANSWER, classify(false, false, 0));
    }

    @Test
    public void errorEnvelopeIsNotAnAnswer() {
        // '{"detail":"Not Found"}' — 2xx from a misrouted gateway, or a framework error body.
        assertEquals(Outcome.NOT_AN_ANSWER, classify(false, false, 0));
    }

    @Test
    public void availableTrueWithoutAVersionCodeIsNotAnAnswer() {
        // '{"available":true}' — optInt defaults to 0, which used to read as 0 <= installed = "up to date".
        assertEquals(Outcome.NOT_AN_ANSWER, classify(true, true, 0));
    }

    @Test
    public void availableTrueWithAnUnusableVersionCodeIsNotAnAnswer() {
        assertEquals(Outcome.NOT_AN_ANSWER, classify(true, true, 0));
        assertEquals(Outcome.NOT_AN_ANSWER, classify(true, true, -1));
    }

    @Test
    public void aVersionCodeAloneIsNotAnAnswerWithoutTheAvailableKey() {
        // '{"version_code":559}' with no "available": the server never said whether it is offering it.
        assertEquals(Outcome.NOT_AN_ANSWER, classify(false, false, 559));
        assertEquals(Outcome.NOT_AN_ANSWER, classify(false, true, 559));
    }

    // ---- genuine answers ----

    @Test
    public void availableFalseIsAGenuineNoUpdate() {
        // '{"available":false}' — the server positively answered; a standing offer may be retired.
        assertEquals(Outcome.NO_UPDATE, classify(true, false, 0));
        assertEquals(Outcome.NO_UPDATE, classify(true, false, 559));
    }

    @Test
    public void availableTrueForTheInstalledOrAnOlderBuildIsAGenuineNoUpdate() {
        assertEquals(Outcome.NO_UPDATE, classify(true, true, INSTALLED));
        assertEquals(Outcome.NO_UPDATE, classify(true, true, 539));
    }

    @Test
    public void availableTrueWithANewerVersionCodeIsAnUpdate() {
        assertEquals(Outcome.UPDATE, classify(true, true, 559));
    }

    @Test
    public void theSmallestUsableVersionCodeStillCounts() {
        // Guards the > 0 boundary from drifting into >= 0 or > 1.
        assertEquals(Outcome.UPDATE, SvipeUpdateResponse.classify(true, true, 1, 0));
        assertEquals(Outcome.NOT_AN_ANSWER, SvipeUpdateResponse.classify(true, true, 0, 0));
    }
}
