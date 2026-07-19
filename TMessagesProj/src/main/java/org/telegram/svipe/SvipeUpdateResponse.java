package org.telegram.svipe;

/**
 * Pure decision: did the update endpoint actually <em>answer the question</em>?
 *
 * <p>Android-free like {@link SvipeUpdateFiles} / {@link SvipeUpdateThrottle}; the updater passes in
 * primitives read off the JSON body and does the I/O.
 *
 * <p>Why this must be positive evidence rather than a default. {@code SvipeApi} turns any body starting
 * with '{' into a JSONObject, so a deploy-window {@code {}} or an error envelope like
 * {@code {"detail":"..."}} arrives as a perfectly "valid" 2xx JSON response. Read with
 * {@code optBoolean("available", false)} that silently means "no update", which used to retire the
 * standing offer and delete a downloaded, SHA-256-verified ~60 MB APK while telling the user "Svipe is
 * up to date". The same hole existed for {@code available=true} with no {@code version_code}:
 * {@code optInt} defaults to 0, and 0 <= installed also read as "up to date".
 *
 * <p>So: the payload must carry {@code available}, and when that is true it must carry a usable
 * {@code version_code} (> 0). Anything else is not an answer — the caller treats it exactly like a
 * transport failure and never touches a downloaded APK. Re-downloading 60 MB is the most expensive thing
 * this feature can do to a user on a bad network; destroying one on an ambiguous response is not
 * acceptable.
 */
public final class SvipeUpdateResponse {

    public enum Outcome {
        /** A newer build is genuinely offered. */
        UPDATE,
        /** The server positively answered "nothing newer" — authoritative, a standing offer may be retired. */
        NO_UPDATE,
        /** The body did not answer the question. Treat as a failed check; never retire anything. */
        NOT_AN_ANSWER
    }

    private SvipeUpdateResponse() {}

    /**
     * @param hasAvailable          the body carries a non-null {@code available} field.
     * @param available             its value (meaningless unless {@code hasAvailable}).
     * @param versionCodeOrZero     {@code version_code}, or 0 when absent/unparseable.
     * @param installedVersionCode  the version code we are running.
     */
    public static Outcome classify(boolean hasAvailable, boolean available,
                                   int versionCodeOrZero, int installedVersionCode) {
        if (!hasAvailable) return Outcome.NOT_AN_ANSWER;
        if (!available) return Outcome.NO_UPDATE;
        // available=true is only an answer when it says WHICH build. A missing/zero/negative version_code
        // is a malformed payload, not "you are up to date".
        if (versionCodeOrZero <= 0) return Outcome.NOT_AN_ANSWER;
        // A real version code we already run (or beat) is a genuine, fully-specified no-update answer.
        return versionCodeOrZero > installedVersionCode ? Outcome.UPDATE : Outcome.NO_UPDATE;
    }
}
