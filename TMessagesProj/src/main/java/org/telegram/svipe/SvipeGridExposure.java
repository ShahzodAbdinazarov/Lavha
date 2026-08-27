package org.telegram.svipe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which grid tiles a person actually LOOKED at — the dwell rule behind the explore grid's exposure
 * events, with no Android in it so it can be tested on the JVM.
 *
 * <p><b>Why the grid has to report at all.</b> The server's "don't show me that again" set is fed
 * exclusively by exposure events, and until now the explore grid sent none: the only surface that
 * ever said "this reached a screen" was the reels player. So a clip could be scrolled past in the
 * grid every day for a fortnight and still arrive in the feed as something new — and, worse, the
 * duplicate keys that would have suppressed its re-encoded twins were never written either, because
 * those are written on exposure and nowhere else.
 *
 * <p><b>Why a dwell rule and not "it was laid out".</b> A fling crosses a hundred tiles in a second
 * and the viewer saw none of them. Reporting on layout would burn a hundred references out of the
 * catalog for one flick of a thumb — the opposite failure to the one being fixed, and a harder one
 * to notice. So a tile counts only once it has been substantially on screen, uninterrupted, for
 * {@link #MIN_DWELL_MS}: leaving the viewport resets it, which is what makes a fling free and a
 * pause count. The caller decides "substantially" in pixels; this class owns the clock.
 *
 * <p>Each key is reported at most once per grid instance ({@link #MAX_REMEMBERED} of them, oldest
 * forgotten first) so a viewer scrolling up and down their own screen sends one event, not twenty.
 */
public final class SvipeGridExposure {

    /**
     * How long a tile must hold the viewport before it counts as seen. Long enough that a fling
     * through a screenful reports nothing; short enough that a viewer who stopped to read a row has
     * unquestionably looked at it.
     */
    public static final long MIN_DWELL_MS = 1200L;

    /** How much of a tile must be inside the viewport for its clock to run. */
    public static final float MIN_VISIBLE_FRACTION = 0.6f;

    /** Reported keys held per grid, oldest evicted first. A re-report is harmless, just wasteful. */
    static final int MAX_REMEMBERED = 2048;

    /** Tiles whose clock is running: key -> when it became (and stayed) visible. */
    private final HashMap<String, Long> pending = new HashMap<>();

    /** Keys already reported. LRU so a very long session cannot grow without bound. */
    private final LinkedHashMap<String, Boolean> reported =
            new LinkedHashMap<String, Boolean>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_REMEMBERED;
                }
            };

    /**
     * Take a fresh reading of what is on screen and return what has just earned an exposure event.
     *
     * <p>Call this whenever the picture may have changed (a scroll, a layout) AND once more after
     * {@link #nextRipeInMs} — a tile that ripens while the viewer sits still produces no scroll
     * callback of its own, and waiting for one is how "they stopped to watch it" became the one case
     * that reported nothing.
     *
     * @param visibleKeys the keys currently meeting the visibility bar, in any order
     * @return keys crossing the dwell threshold on THIS call, never repeated afterwards
     */
    public List<String> update(Collection<String> visibleKeys, long nowMs) {
        final List<String> ripe = new ArrayList<>();
        if (visibleKeys == null) {
            pending.clear();
            return ripe;
        }
        // Gone from the viewport -> the clock resets. A tile the viewer scrolled back to starts over.
        Iterator<String> tracked = pending.keySet().iterator();
        while (tracked.hasNext()) {
            if (!visibleKeys.contains(tracked.next())) {
                tracked.remove();
            }
        }
        for (String key : visibleKeys) {
            if (key == null || key.isEmpty() || reported.containsKey(key)) {
                continue;
            }
            final Long since = pending.get(key);
            if (since == null) {
                pending.put(key, nowMs);
                continue;
            }
            // A clock stamped in the future (the device's own clock moved) would never ripen; treat
            // it as a fresh start rather than a tile that can never be reported.
            if (since > nowMs) {
                pending.put(key, nowMs);
                continue;
            }
            if (nowMs - since >= MIN_DWELL_MS) {
                ripe.add(key);
            }
        }
        for (String key : ripe) {
            pending.remove(key);
            reported.put(key, Boolean.TRUE);
        }
        return ripe;
    }

    /**
     * Milliseconds until the earliest tile on the clock ripens, or -1 when nothing is on it.
     *
     * <p>0 means something is ripe right now. The caller uses this to arm exactly one re-check
     * instead of polling.
     */
    public long nextRipeInMs(long nowMs) {
        long soonest = -1;
        for (Long since : pending.values()) {
            final long left = Math.max(0, MIN_DWELL_MS - (nowMs - since));
            if (soonest < 0 || left < soonest) {
                soonest = left;
            }
        }
        return soonest;
    }

    /** True once this key has produced its event. */
    public boolean alreadyReported(String key) {
        return key != null && reported.containsKey(key);
    }

    /** Forget what is on the clock (the content underneath changed), keeping what was reported. */
    public void clearPending() {
        pending.clear();
    }

    /** Reset everything — a different content set is about to occupy the same grid. */
    public void reset() {
        pending.clear();
        reported.clear();
    }

    /** Tiles currently on the clock. Diagnostics and tests only. */
    int pendingCount() {
        return pending.size();
    }

    /** Keys that have produced an event. Diagnostics and tests only. */
    int reportedCount() {
        return reported.size();
    }
}
