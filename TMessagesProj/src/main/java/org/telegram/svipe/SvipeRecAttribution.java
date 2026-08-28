package org.telegram.svipe;

import org.json.JSONObject;

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
     * How many pages stay in the register.
     *
     * <p>Sized against the TTL, not picked: the register only has to outlast the window in which an
     * event about a page can still be attributed. That window is now the SERVER's five days rather
     * than an hour, and a viewer who opens the app a dozen times a day mints a few hundred pages
     * across it — so a bound built for the one-hour window would have started evicting pages that
     * were still perfectly attributable, turning a live id into "unknown" and silently unattributing
     * the event. An evicted id is refused rather than trusted, so the failure was safe and invisible,
     * which is exactly why the bound is written down with its reasoning.
     *
     * <p>Costs roughly 160 KB full, against a queue that budgets 600 MB of video.
     */
    static final int MAX_TRACKED_PAGES = 1024;

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

    /**
     * The wire name of the server's TTL field, and the ONLY name accepted.
     *
     * <p>Named as a constant because this is a contract with another codebase and a near-miss reads
     * as working code: an abbreviated guess simply finds nothing, the default silently stays, and the
     * client quietly keeps refusing pages the server would still have answered for. Server side it is
     * {@code DiscoverResponse.recommendation_ttl_seconds} (app/schemas/discover.py).
     */
    public static final String TTL_FIELD = "recommendation_ttl_seconds";

    /**
     * The TTL a response states, or 0 when it states none.
     *
     * <p>0 is the server's own default for the field and means "nothing to say" (an empty grid serves
     * no recommendation), so it is not an error and must not overwrite a TTL already learned.
     */
    public static long ttlSecondsIn(JSONObject res) {
        if (res == null || res.isNull(TTL_FIELD)) {
            return 0L;
        }
        final long seconds = res.optLong(TTL_FIELD, 0L);
        return seconds > 0 ? seconds : 0L;
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
            // Evict what is already dead before evicting by age: without this the LRU would drop the
            // oldest LIVE page to make room while expired ones sat in the map doing nothing.
            // Judged against THIS page's clock rather than the wall clock: the caller's time is the
            // newest thing the register knows, and the queue replaying old pages at load must not be
            // able to prune live ones just because its own stamps are old.
            if (known == null && minted.size() >= MAX_TRACKED_PAGES) {
                prune(mintedAtMs);
            }
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
