package org.telegram.svipe;

/**
 * The in-memory unread counters in MessagesStorage drift: incoming forum-topic
 * messages increment the main counter, but several read paths (dialog-level
 * reads of forums, reads synced from other devices) never decrement it, so the
 * tab badge can get stuck until something happens to trigger a full recount.
 * The fork's tab badge heals itself by requesting a full recount on resume;
 * this throttle keeps those requests from hammering the storage queue.
 */
public class SvipeUnreadRecountThrottle {

    private final long intervalMs;
    private boolean ranOnce;
    private long lastRunMs;

    public SvipeUnreadRecountThrottle(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /**
     * @param nowMs monotonic time, e.g. SystemClock.elapsedRealtime()
     * @return true if the caller should run the recount now; the throttle
     * records the run. The first call always returns true.
     */
    public boolean shouldRun(long nowMs) {
        if (ranOnce && nowMs - lastRunMs < intervalMs) {
            return false;
        }
        ranOnce = true;
        lastRunMs = nowMs;
        return true;
    }
}
