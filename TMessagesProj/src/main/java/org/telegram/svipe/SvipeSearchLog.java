package org.telegram.svipe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Per-visit search-history logger. One instance == one visit to a search page: it mints a session id,
 * keeps the (collapsed) list of query variants the user typed during the visit, and reports them plus
 * the result they tap to the backend (POST /v1/search/session), which aggregates by session id so the
 * whole visit lands as one record. Fire-and-forget — failures are swallowed, telemetry must never
 * affect the UX. Mirrors SvipeMusic's auth + 401-retry idiom.
 */
public class SvipeSearchLog {

    private final int account;
    private final String source;                 // "music" | "explore"
    private final String sessionId;
    private final ArrayList<String> queries = new ArrayList<>();
    private int lastResultCount = -1;

    public SvipeSearchLog(int account, String source) {
        this.account = account;
        this.source = source;
        this.sessionId = UUID.randomUUID().toString();
    }

    /**
     * A settled query (call after your debounce, not per keystroke). Progressive typing — a query that
     * is a prefix-extension of the previous one ("lo" -> "love") — collapses into a single variant, so
     * the visit records "the few distinct things they tried", not every intermediate string. The whole
     * accumulated list is re-sent each time, so the server always holds the full aggregate.
     */
    public void query(String q, int resultCount) {
        if (q == null || q.trim().isEmpty()) return;
        collapse(queries, q.trim());
        lastResultCount = resultCount;
        post(buildBody(null), false);
    }

    /**
     * Fold a settled query into the visit's list. Progressive typing — a query that is a prefix of the
     * previous or vice versa ("lo" -> "love", or a backspace) — replaces the last entry so the visit
     * keeps "the few distinct things they tried"; a genuinely different query appends; an exact repeat
     * is a no-op. Pure + package-visible so it is unit-tested without the network side of {@link #query}.
     */
    static void collapse(ArrayList<String> queries, String q) {
        if (q == null) return;
        q = q.trim();
        if (q.isEmpty()) return;
        String last = queries.isEmpty() ? null : queries.get(queries.size() - 1);
        if (q.equals(last)) {
            return;
        }
        if (last != null && (q.startsWith(last) || last.startsWith(q))) {
            queries.set(queries.size() - 1, q);
        } else {
            queries.add(q);
        }
    }

    /** The result the user tapped — signals they found what they were after. Any arg may be null. */
    public void click(String query, String kind, String ref, String label) {
        JSONObject clicked = new JSONObject();
        try {
            if (query != null && !query.trim().isEmpty()) clicked.put("query", query.trim());
            if (kind != null) clicked.put("kind", kind);
            if (ref != null) clicked.put("ref", ref);
            if (label != null && !label.trim().isEmpty()) clicked.put("label", label.trim());
        } catch (Exception e) {
            FileLog.e(e);
        }
        post(buildBody(clicked), false);
    }

    /** True once the visit actually has something worth reporting (so callers can skip empty clicks). */
    public boolean hasQueries() {
        return !queries.isEmpty();
    }

    private JSONObject buildBody(JSONObject clicked) {
        JSONObject body = new JSONObject();
        try {
            body.put("session_id", sessionId);
            body.put("source", source);
            if (!queries.isEmpty()) body.put("queries", new JSONArray(queries));
            if (lastResultCount >= 0) body.put("result_count", lastResultCount);
            if (clicked != null) body.put("clicked", clicked);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return body;
    }

    private void post(JSONObject body, boolean retried) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) return;
            SvipeApi.post("/v1/search/session", body, token, (res, code, err) -> {
                if (code == 401 && !retried) {
                    SvipeAuth.invalidateAccessToken(account);
                    post(body, true);
                }
            });
        });
    }
}
