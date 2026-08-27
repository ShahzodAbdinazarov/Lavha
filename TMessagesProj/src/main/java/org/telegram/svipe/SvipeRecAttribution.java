package org.telegram.svipe;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whether a {@code recommendation_id} may still be attached to an event — the client half of the
 * server's duplicate suppression.
 *
 * <p><b>Why this exists.</b> A recommendation page is not just an id: the server stores a CONTEXT
 * under it, and that context is where the duplicate keys live (``fp`` byte-identical repost, ``cg``
 * near-duplicate group, written at serve time in app/recsys/engine.py). When an exposure event
 * arrives carrying a recommendation id, the ingest path looks that context up and copies those keys
 * into the viewer's dedup set — that is the ONLY moment they are ever written. The context expires
 * (``REC_TTL_SECONDS``), and a lookup that misses returns no keys at all: the event is still
 * recorded, the ``channel:message`` is still marked seen, but the clip's duplicate keys are lost and
 * the same video under a different message id stays servable.
 *
 * <p>So sending a DEAD recommendation id is strictly worse than sending none: it costs the same
 * request, produces no dedup keys, and — because the event looks attributed — hides the failure from
 * every measurement that counts attributed events. The offline reel queue is exactly how that
 * happened: it persists a page's id to disk with the reels it holds, and a cold start days later
 * replayed them against a context that had been gone for hours (measured: half of all pages emitted
 * events past the hour, p95 4.6 days, worst case 167 hours).
 *
 * <p><b>What it does.</b> One process-wide register of when each page was minted, plus the TTL to
 * judge it against. Callers never carry a timestamp: they ask {@link #attributableId(String)} and
 * get back either the id (the context is still alive) or null (send it unattributed). The mint time
 * of the pages the reel queue persists rides along on disk and is replayed into the register at
 * load, so a cold start knows the age of what it restored rather than assuming it is fresh.
 *
 * <p>The TTL is the SERVER's number, not ours: {@link #DEFAULT_TTL_SECONDS} only stands in until a
 * response carries {@code recommendation_ttl_seconds} (see {@link SvipeConfig#applyRecTtl}), after
 * which the learned value is persisted and used. Changing the server constant changes the client
 * with no release.
 *
 * <p>Pure Java (no Android imports) so it can be unit-tested on the JVM, like {@link SvipeQueuePlan}.
 */
public final class SvipeRecAttribution {

    /**
     * Mirrors {@code REC_TTL_SECONDS} in app/recsys/engine.py. A DEFAULT, not the source of truth —
     * the server's own value replaces it as soon as a response states one.
     */
    public static final long DEFAULT_TTL_SECONDS = 3600L;

    /**
     * Give up attribution this long BEFORE the server's own expiry.
     *
     * <p>The client stamps a page when it finishes parsing the response; the server stamped it when
     * it built it, and the two clocks are not the same clock. Without the margin, every page spends
     * its last seconds sending ids that will miss the lookup by the time they land — the exact
     * failure this class exists to stop, in miniature.
     */
    public static final long SAFETY_MARGIN_MS = 60_000L;

    /**
     * How many pages stay in the register. A viewer who pages all evening mints one id per page, and
     * an unbounded map would hold every one of them for the life of the process. The oldest entries
     * are dropped first, and a dropped id simply reads as "not attributable" — the safe answer.
     */
    static final int MAX_TRACKED_PAGES = 256;

    /** recommendation_id -> when this client first saw the page (epoch ms). Access-ordered LRU. */
    private static final LinkedHashMap<String, Long> minted =
            new LinkedHashMap<String, Long>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_TRACKED_PAGES;
                }
            };

    private static long ttlMs = DEFAULT_TTL_SECONDS * 1000L;

    private SvipeRecAttribution() {}

    // ---- TTL (learned from the server, defaulted from the constant above) ----

    public static synchronized long ttlMs() {
        return ttlMs;
    }

    /** Adopt the server's TTL. Non-positive values are ignored — a bad number must not disable dedup. */
    public static synchronized void setTtlSeconds(long seconds) {
        if (seconds > 0) {
            ttlMs = seconds * 1000L;
        }
    }

    // ---- the register ----

    /**
     * Record when a recommendation page was seen. Idempotent, and the EARLIEST stamp wins: the same
     * page id arrives again on every item of it and again from the queue on disk, and taking the
     * newest would keep resetting the clock on a context the server has already dropped.
     */
    public static synchronized void remember(String recId, long mintedAtMs) {
        if (recId == null || recId.isEmpty() || mintedAtMs <= 0) {
            return;
        }
        Long known = minted.get(recId);
        if (known == null || mintedAtMs < known) {
            minted.put(recId, mintedAtMs);
        }
    }

    /** When this client first saw the page, or {@code fallbackMs} if it never did. */
    public static synchronized long mintedAt(String recId, long fallbackMs) {
        if (recId == null || recId.isEmpty()) {
            return fallbackMs;
        }
        Long known = minted.get(recId);
        return known != null ? known : fallbackMs;
    }

    // ---- the question every event asks ----

    /**
     * The id to put on an event, or null to send it unattributed.
     *
     * <p>An id this process never minted is treated as expired. That is deliberate: the register is
     * seeded from every place a page is parsed AND from the persisted queue, so "unknown" means the
     * page predates this process by more than the queue remembered — which is to say, it is old.
     */
    public static String attributableId(String recId) {
        return attributableId(recId, System.currentTimeMillis());
    }

    static synchronized String attributableId(String recId, long nowMs) {
        if (recId == null || recId.isEmpty()) {
            return null;
        }
        Long mintedAtMs = minted.get(recId);
        if (mintedAtMs == null) {
            return null;
        }
        return fresh(mintedAtMs, nowMs, ttlMs) ? recId : null;
    }

    /**
     * Is a page minted at {@code mintedAtMs} still inside its TTL at {@code nowMs}?
     *
     * <p>A stamp in the future is refused as well: a device whose clock jumped forward and back would
     * otherwise treat a genuinely dead page as eternally fresh.
     */
    public static boolean fresh(long mintedAtMs, long nowMs, long ttlMs) {
        if (mintedAtMs <= 0 || mintedAtMs > nowMs) {
            return false;
        }
        final long usableMs = ttlMs - SAFETY_MARGIN_MS;
        if (usableMs <= 0) {
            return false;
        }
        return nowMs - mintedAtMs < usableMs;
    }

    /** How many pages the register holds. Diagnostics and tests only. */
    static synchronized int trackedPages() {
        return minted.size();
    }

    /** Test hook: forget every page and go back to the compiled-in TTL. */
    static synchronized void reset() {
        minted.clear();
        ttlMs = DEFAULT_TTL_SECONDS * 1000L;
    }

    /** Drop pages that can no longer attribute anything. Cheap, and keeps the register honest. */
    static synchronized void prune(long nowMs) {
        Iterator<Map.Entry<String, Long>> it = minted.entrySet().iterator();
        while (it.hasNext()) {
            if (!fresh(it.next().getValue(), nowMs, ttlMs)) {
                it.remove();
            }
        }
    }
}
