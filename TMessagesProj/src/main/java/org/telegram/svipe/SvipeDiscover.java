package org.telegram.svipe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the Instagram-style Explore grid endpoint (GET /v1/discover). References only — each
 * item is a Telegram post the app resolves and renders a thumbnail for, then plays via the reels
 * player. Mirrors ReelsActivity.requestFeed's auth + 401-retry idiom.
 */
public class SvipeDiscover {

    /**
     * A video counts as HORIZONTAL once its width exceeds its height by this factor. Mirrors the
     * backend's {@code discover_landscape_min_aspect} — 1.2 rather than a bare w>h so a near-square
     * upload (which crops fine into a portrait tile) stays on the vertical side. Keep the two in sync:
     * the server mixes the feed by this rule, the client lays it out by this rule.
     */
    public static final float LANDSCAPE_MIN_ASPECT = 1.2f;

    public static class Item {
        public long channelId;
        public int messageId;
        public String username;
        public Integer topicId;
        // Pixel size + length as the server knows them, so a cell can be measured BEFORE the Telegram
        // message is resolved over MTProto (the grid is mixed-orientation: full-width 16:9 cards for
        // horizontal long-form, 3-up portrait tiles for vertical reels). 0 = the server didn't say.
        public int width;
        public int height;
        public int durationMs;

        /** True for a horizontal/long-form entry. Unknown dimensions fall back to the vertical tile. */
        public boolean isLandscape() {
            return width > 0 && height > 0 && width >= height * LANDSCAPE_MIN_ASPECT;
        }

        /** Video aspect (w/h), or 16:9 when the server sent no dimensions. */
        public float aspect() {
            return width > 0 && height > 0 ? (float) width / height : 16f / 9f;
        }
    }

    public interface Callback {
        /** items==null on failure. nextOffset==null when there are no more pages. */
        void onResult(List<Item> items, Integer nextOffset, String error);
    }

    /** A reels channel the user has blocked (the read behind a block-management screen). */
    public static class BlockedChannel {
        public long channelId;
        public String title;
        public String username;
    }

    public interface BlockedCallback {
        /** items==null on failure. */
        void onResult(List<BlockedChannel> items, String error);
    }

    public static void load(int account, String category, int offset, int limit, Callback cb) {
        load(account, category, offset, limit, false, cb);
    }

    /** refresh=true rotates the server grid to a fresh window (pull-to-refresh); page-0 only. */
    public static void load(int account, String category, int offset, int limit, boolean refresh, Callback cb) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                cb.onResult(null, null, "auth");
                return;
            }
            request(account, category, offset, limit, refresh, token, false, cb);
        });
    }

    private static void request(int account, String category, int offset, int limit, boolean refresh,
                                String token, boolean retried, Callback cb) {
        StringBuilder path = new StringBuilder("/v1/discover?limit=").append(limit).append("&offset=").append(offset);
        if (refresh) {
            path.append("&refresh=1");
        }
        if (category != null && !category.isEmpty()) {
            try {
                path.append("&category=").append(URLEncoder.encode(category, "UTF-8"));
            } catch (Exception ignore) {
            }
        }
        SvipeApi.get(path.toString(), token, (res, code, err) -> {
            if (code == 401 && !retried) {
                // Access token died: silent re-auth, one retry — same pattern as ReelsActivity.
                SvipeAuth.invalidateAccessToken(account);
                SvipeAuth.ensureToken(account, t2 -> {
                    if (t2 == null) {
                        cb.onResult(null, null, "auth");
                        return;
                    }
                    request(account, category, offset, limit, refresh, t2, true, cb);
                });
                return;
            }
            if (res == null || !res.has("items")) {
                cb.onResult(null, null, err != null ? err : ("http " + code));
                return;
            }
            ArrayList<Item> out = new ArrayList<>();
            parseItems(res.optJSONArray("items"), out);
            Integer next = res.isNull("next_offset") ? null : Integer.valueOf(res.optInt("next_offset"));
            cb.onResult(out, next, null);
        });
    }

    /**
     * Server-side reels/video search — the text twin of the grid. Same reference shape (FeedItem) as
     * {@link #load}, so callers reuse the explore-grid renderer.
     */
    public static void search(int account, String query, int offset, int limit, Callback cb) {
        String q;
        try {
            q = URLEncoder.encode(query == null ? "" : query, "UTF-8");
        } catch (Exception e) {
            q = "";
        }
        feedGet(account, "/v1/discover/search?q=" + q + "&limit=" + limit + "&offset=" + offset, cb);
    }

    /** Reels the user recently watched, newest first (the "watching history"). Same reference shape as the grid. */
    public static void reelsHistory(int account, int offset, int limit, Callback cb) {
        feedGet(account, "/v1/reels/history?limit=" + limit + "&offset=" + offset, cb);
    }

    /** Shared GET for the reference-list endpoints returning {items:[FeedItem], next_offset}. */
    private static void feedGet(int account, String path, Callback cb) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                cb.onResult(null, null, "auth");
                return;
            }
            feedRequest(account, path, token, false, cb);
        });
    }

    private static void feedRequest(int account, String path, String token, boolean retried, Callback cb) {
        SvipeApi.get(path, token, (res, code, err) -> {
            if (code == 401 && !retried) {
                SvipeAuth.invalidateAccessToken(account);
                SvipeAuth.ensureToken(account, t2 -> {
                    if (t2 == null) {
                        cb.onResult(null, null, "auth");
                        return;
                    }
                    feedRequest(account, path, t2, true, cb);
                });
                return;
            }
            if (res == null || !res.has("items")) {
                cb.onResult(null, null, err != null ? err : ("http " + code));
                return;
            }
            ArrayList<Item> out = new ArrayList<>();
            parseItems(res.optJSONArray("items"), out);
            Integer next = res.isNull("next_offset") ? null : Integer.valueOf(res.optInt("next_offset"));
            cb.onResult(out, next, null);
        });
    }

    /** The reels channels this user has blocked, newest first (channel_id + title + username). */
    public static void reelsBlocked(int account, BlockedCallback cb) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                cb.onResult(null, "auth");
                return;
            }
            blockedRequest(account, token, false, cb);
        });
    }

    private static void blockedRequest(int account, String token, boolean retried, BlockedCallback cb) {
        SvipeApi.get("/v1/reels/blocked", token, (res, code, err) -> {
            if (code == 401 && !retried) {
                SvipeAuth.invalidateAccessToken(account);
                SvipeAuth.ensureToken(account, t2 -> {
                    if (t2 == null) {
                        cb.onResult(null, "auth");
                        return;
                    }
                    blockedRequest(account, t2, true, cb);
                });
                return;
            }
            if (res == null || !res.has("items")) {
                cb.onResult(null, err != null ? err : ("http " + code));
                return;
            }
            ArrayList<BlockedChannel> out = new ArrayList<>();
            JSONArray arr = res.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    BlockedChannel bc = new BlockedChannel();
                    bc.channelId = o.optLong("channel_id");
                    bc.title = o.isNull("title") ? null : o.optString("title", null);
                    bc.username = o.isNull("username") ? null : o.optString("username", null);
                    out.add(bc);
                }
            }
            cb.onResult(out, null);
        });
    }

    /** ok==false on failure (network/auth/http). */
    public interface EventCallback {
        void onDone(boolean ok);
    }

    /**
     * Unblock a reels channel: the twin of the BLOCK_CHANNEL event ReelsActivity posts on block. Sent
     * to POST /v1/events exactly like ReelsActivity.sendEvent/postEvents, with event_type
     * "UNBLOCK_CHANNEL" (message_id 0 — this is a channel-level action, not tied to one reel). One
     * silent re-auth retry on 401, same as every other write here.
     */
    public static void unblockChannel(int account, long channelId, EventCallback cb) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                if (cb != null) cb.onDone(false);
                return;
            }
            unblockRequest(account, channelId, token, false, cb);
        });
    }

    private static void unblockRequest(int account, long channelId, String token, boolean retried, EventCallback cb) {
        JSONObject batch = new JSONObject();
        try {
            JSONObject ev = new JSONObject();
            ev.put("channel_id", channelId);
            ev.put("message_id", 0);
            ev.put("event_type", "UNBLOCK_CHANNEL");
            JSONArray events = new JSONArray();
            events.put(ev);
            batch.put("events", events);
        } catch (Exception e) {
            if (cb != null) cb.onDone(false);
            return;
        }
        SvipeApi.post("/v1/events", batch, token, (res, code, err) -> {
            if (code == 401 && !retried) {
                SvipeAuth.invalidateAccessToken(account);
                SvipeAuth.ensureToken(account, t2 -> {
                    if (t2 == null) {
                        if (cb != null) cb.onDone(false);
                        return;
                    }
                    unblockRequest(account, channelId, t2, true, cb);
                });
                return;
            }
            if (cb != null) cb.onDone(code >= 200 && code < 300);
        });
    }

    /** Parse a {items:[FeedItem]} array into Items; rows without a username are skipped (can't resolve). */
    private static void parseItems(JSONArray arr, List<Item> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String username = o.isNull("username") ? null : o.optString("username", null);
            if (username == null || username.isEmpty()) continue;
            Item it = new Item();
            it.channelId = o.optLong("channel_id");
            it.messageId = o.optInt("message_id");
            it.username = username;
            it.topicId = o.isNull("topic_id") ? null : o.optInt("topic_id");
            // Absent on an older server build -> 0 -> the item renders as a vertical tile, exactly the
            // pre-mixed-grid behaviour.
            it.width = o.optInt("width", 0);
            it.height = o.optInt("height", 0);
            it.durationMs = o.optInt("duration_ms", 0);
            out.add(it);
        }
    }
}
