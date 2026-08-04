package org.telegram.svipe;

/**
 * Timing policy for the reels COLD start — the one path a user with nothing on disk depends on
 * (auth -> /v1/feed -> resolve -> play). Every step there is a network step, so the only honest
 * design is "assume any of them can never answer".
 *
 * Two rules, both pure so they can be unit-tested on the JVM:
 *   1. an in-flight feed request is only allowed to block a new one for as long as it could
 *      plausibly still be alive ({@link #isRequestStale}); past that it is treated as dead, because
 *      a latch that is never cleared silently disables every retry path there is;
 *   2. while the pager is still empty, keep re-kicking the load on a widening backoff
 *      ({@link #retryDelayMs}) instead of waiting for a connection-state change that may never come
 *      (the app was online the whole time — it was the auth chain that stalled).
 *
 * See {@link SvipeFeedRetry} for the complementary "we are back online" trigger.
 */
public class SvipeColdStart {

    /** Beyond this an unanswered feed request is presumed dead. Comfortably past SvipeApi's own
     *  15s connect + 25s read budget, so a slow-but-alive request is never cut off early. */
    public static final long REQUEST_TIMEOUT_MS = 45_000;

    /** Backoff for the empty-pager watchdog: 3s, 6s, 12s, 24s, then every 30s, forever. Forever is
     *  deliberate — the alternative is a user staring at "Loading" until they restart the app. */
    public static final long FIRST_RETRY_MS = 3_000;
    public static final long MAX_RETRY_MS = 30_000;

    public static long retryDelayMs(int attempt) {
        if (attempt < 0) attempt = 0;
        long delay = FIRST_RETRY_MS;
        for (int i = 0; i < attempt && delay < MAX_RETRY_MS; i++) {
            delay *= 2;
        }
        return Math.min(delay, MAX_RETRY_MS);
    }

    /** Whether a request started at {@code startedMs} has outlived its budget (0 = never started). */
    public static boolean isRequestStale(long startedMs, long nowMs) {
        return startedMs <= 0 || nowMs - startedMs >= REQUEST_TIMEOUT_MS;
    }

    /** A new load may start when nothing is in flight, or what is in flight is presumed dead. */
    public static boolean canStartRequest(boolean loading, long startedMs, long nowMs) {
        return !loading || isRequestStale(startedMs, nowMs);
    }
}
