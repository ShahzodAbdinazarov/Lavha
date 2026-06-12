package org.telegram.lavha;

/**
 * Classifies the watch time accumulated on a reel (the player loops, so ExoPlayer never reaches
 * STATE_ENDED) into the telemetry event sent when the user leaves it:
 *   watched >= 150% of duration -> REPLAY     (looped at least half again: strong positive)
 *   watched >=  90% of duration -> VIDEO_END  (effectively a completion)
 *   otherwise                   -> SWIPE_AWAY (backend grades it by dwell/completion)
 * Pure Java so it can be unit-tested on the JVM.
 */
public class LavhaWatchEvent {

    public static String classify(long watchedMs, long durationMs) {
        if (durationMs > 0) {
            if (watchedMs >= 1.5 * durationMs) return "REPLAY";
            if (watchedMs >= 0.9 * durationMs) return "VIDEO_END";
        }
        return "SWIPE_AWAY";
    }
}
