package org.telegram.svipe;

/**
 * Pure decision: may {@link SvipeUpdater} talk to the update endpoint again yet?
 *
 * <p>Android-free on purpose, like {@link SvipeUpdateFiles} — this project cannot run Robolectric, so
 * every rule worth testing has to live as a static function over primitives.
 *
 * <p>Why the outcome matters. The updater used to arm the full success interval from inside the HTTP
 * callback <em>before</em> the response was validated, so a request that never reached the server (a DNS
 * lookup that timed out, an aeroplane-mode launch) silenced the updater for the whole interval. The
 * device that motivated this sits behind a router whose forwarder resolves the backend roughly five
 * times out of eight, so "the request failed" is that device's normal state, not an edge case: losing
 * one coin flip must cost seconds, not half an hour.
 */
public final class SvipeUpdateThrottle {

    /** Gap between successful background re-checks. Short enough that a release reaches users promptly. */
    public static final long SUCCESS_INTERVAL_MS = 30L * 60 * 1000; // 30 min

    /**
     * Gap after a check that never produced a valid response.
     *
     * <p>One minute is deliberately chosen against the two failure modes:
     * <ul>
     *   <li>It bounds the traffic. The retry is driven by foreground resumes, so the worst case is a
     *       user switching back into the app more often than once a minute; that caps the update
     *       endpoint at ~60 requests/hour/device even then, against ~2/hour when things work. On the
     *       broken-DNS device the failing attempts do not reach the server at all — they die in the
     *       resolver — so the server-side cost of the retry is nil in exactly the case that triggers it.</li>
     *   <li>It is short enough to be invisible. The user's next trip back into the app retries instead
     *       of hitting a half-hour wall, which is the whole point of the fix.</li>
     * </ul>
     *
     * <p>Note that {@code checkedThisProcess} in the updater is deliberately <em>not</em> released on
     * failure: that flag bypasses this throttle entirely (it is the "always check once per cold start"
     * rule), so clearing it would turn every resume into an unthrottled request — a genuine storm when
     * the user flips between apps on a dead network. Letting this shorter interval carry the retry keeps
     * exactly one bound on the request rate.
     */
    public static final long FAILURE_BACKOFF_MS = 60L * 1000; // 1 min

    private SvipeUpdateThrottle() {}

    /** The interval that applies after a check with the given outcome. */
    public static long intervalFor(boolean lastCheckSucceeded) {
        return lastCheckSucceeded ? SUCCESS_INTERVAL_MS : FAILURE_BACKOFF_MS;
    }

    /**
     * Is a throttled (automatic) check due?
     *
     * @param lastCheckMs        when the last check finished, 0/negative when we have never checked.
     * @param nowMs              current wall clock.
     * @param lastCheckSucceeded whether that last check produced a valid response.
     */
    public static boolean shouldCheck(long lastCheckMs, long nowMs, boolean lastCheckSucceeded) {
        if (lastCheckMs <= 0) return true; // never checked
        long elapsed = nowMs - lastCheckMs;
        // Wall clock moved backwards (manual clock change, NTP correction). Treat the stamp as garbage
        // and check, rather than wedging the updater until the clock catches up again.
        if (elapsed < 0) return true;
        return elapsed >= intervalFor(lastCheckSucceeded);
    }
}
