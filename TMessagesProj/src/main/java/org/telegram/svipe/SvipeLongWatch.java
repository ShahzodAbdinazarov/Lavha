package org.telegram.svipe;

/**
 * Classifies the watch time accumulated on a LONG-FORM video into the telemetry event sent when the
 * user leaves it — the long-form sibling of {@link SvipeWatchEvent}, and deliberately NOT the same
 * rules.
 *
 * <p><b>Why reels' classifier must not be reused here.</b> {@link SvipeWatchEvent} emits VIDEO_END at
 * 90% and REPLAY at 150% of the duration. Nobody crosses 90% of a 40-minute upload, so every
 * long-form watch would classify as SWIPE_AWAY, and the backend grades SWIPE_AWAY by
 * {@code watched_ms / video_duration_ms} — a genuinely excellent 8-minute view of a 40-minute
 * documentary would score ~0.2 and feed that near-negative into the bandit and session vector that
 * reels shares. At this duration, completion measures the video's LENGTH, not its quality (the
 * server's own long-form ranker carries no completion term for exactly this reason), so watch TIME is
 * the signal and HEARTBEAT is the neutral terminal event.
 *
 * <p>HEARTBEAT is load-bearing: the backend has no reward branch for it, so it falls through to
 * NEUTRAL (zero bandit impact) while the raw payload — watched_ms, dwell, buffering — is persisted
 * forever and stays re-derivable once a long-form value model exists. The trade-off is explicit: a
 * good-but-partial long watch earns no positive reward today. That costs nothing (the long-form
 * ranker consumes no client events yet) and it avoids the real damage, which is a false negative.
 *
 * <p>{@code event_type} is a strict server-side enum, so the client cannot invent a "WATCH_END" —
 * every string returned here is a member of it.
 *
 * <p>Pure Java so it can be unit-tested on the JVM.
 */
public class SvipeLongWatch {

    /** Below this, leaving reads as a genuine rejection rather than a partial watch. */
    public static final long MIN_MEANINGFUL_WATCH_MS = 10_000;

    /** Close enough to the end to count as finished; the last frames are often credits. */
    private static final double COMPLETE_FRACTION = 0.98;

    public static final String VIDEO_END = "VIDEO_END";
    public static final String SWIPE_AWAY = "SWIPE_AWAY";
    public static final String HEARTBEAT = "HEARTBEAT";

    /**
     * @param watchedMs      time the video was actually PLAYING (paused time excluded)
     * @param durationMs     the video's length, 0 when unknown
     * @param positionMs     where playback stood when the user left
     * @param endedNaturally the player reached STATE_ENDED
     * @param bufferingMs    time spent stalled mid-playback
     * @param ttffMs         time from the play intent to the first rendered frame, 0 if never rendered
     */
    public static String classify(long watchedMs, long durationMs, long positionMs,
                                  boolean endedNaturally, long bufferingMs, long ttffMs) {
        if (endedNaturally) {
            return VIDEO_END;
        }
        if (durationMs > 0 && positionMs >= COMPLETE_FRACTION * durationMs) {
            return VIDEO_END;
        }
        if (watchedMs >= MIN_MEANINGFUL_WATCH_MS) {
            return HEARTBEAT;
        }
        // A short watch the player spent stalling is a NETWORK bail, not a rejection of the video.
        // The backend can also excuse it from the payload, but only the client knows the difference
        // between "watched 4 seconds and left" and "waited 9 seconds for a frame and gave up", and
        // this can only ever move a verdict from negative to neutral.
        final long stalled = Math.max(0, bufferingMs) + Math.max(0, ttffMs);
        if (stalled > 0 && stalled >= Math.max(0, watchedMs)) {
            return HEARTBEAT;
        }
        return SWIPE_AWAY;
    }
}
