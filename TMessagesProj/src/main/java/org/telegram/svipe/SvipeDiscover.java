package org.telegram.svipe;

import org.telegram.messenger.AndroidUtilities;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the Explore grid's reference endpoints. References only — each item is a Telegram post
 * the app resolves and renders a thumbnail for, then plays via the reels player. Mirrors
 * ReelsActivity.requestFeed's auth + 401-retry idiom.
 *
 * The browse grid is fed by TWO fully independent pipes, each with its own server-side algorithm and
 * its own paging cursor, and neither mixes orientations:
 * <ul>
 *   <li>{@link #load} → GET /v1/discover — SHORTS: vertical, short videos (the diversity grid);</li>
 *   <li>{@link #videos} → GET /v1/videos — LONG-FORM: horizontal, minutes-long videos.</li>
 * </ul>
 * Interleaving them is the CLIENT's job (see SvipeExploreGrid), which is what makes the grid's row
 * alignment structural instead of something the server has to get right.
 */
public class SvipeDiscover {

    /**
     * A video counts as HORIZONTAL once its width exceeds its height by this factor. Mirrors the
     * backend's {@code discover_landscape_min_aspect} — 1.2 rather than a bare w>h so a near-square
     * upload (which crops fine into a portrait tile) stays on the vertical side. Keep the two in sync:
     * the server mixes the feed by this rule, the client lays it out by this rule.
     */
    public static final float LANDSCAPE_MIN_ASPECT = 1.2f;

    /**
     * Where the long-form watch page takes over from the reels player. Mirrors the backend's
     * {@code longform_min_duration_ms}, so a video the server considers long-form is one the client
     * opens in the watch page. ReelsActivity carries the same threshold as a playback guard (no
     * looping, no implicit full-file pull, never persisted to the offline reels queue).
     */
    public static final long LONG_FORM_MIN_DURATION_MS = 180_000L;

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
        // Owned svipe.uz/<code> link the server attaches to every reference (attach_share_urls). The
        // share sheet prefers it over the raw t.me link because it carries the install loop.
        public String shareUrl;
        /**
         * The id of the recommendation PAGE this reference arrived with, so an event about it can be
         * attributed back to the ranking that served it. The reels feed carries one; the discover /
         * long-form responses do not yet, so this stays null and their events land unattributed —
         * read from the response the moment the server starts sending it, which is why nothing here
         * has to change then.
         */
        public String recId;
        /**
         * CLIENT-ONLY, never parsed from a response: this reference was built from a message the user
         * ran into somewhere in the app (SvipeVideoOpen), not served by us, and it is NOT a public
         * channel post — so its ids may belong to a private chat and nothing carrying them may reach
         * our server. The flag rides on the reference rather than on the watch page because the mini
         * bar's restore and the buried-page restore both re-open from the Item alone, with no page
         * involved; a page-level flag would be lost exactly there.
         */
        public boolean local;

        /** True for a horizontal/long-form entry. Unknown dimensions fall back to the vertical tile. */
        public boolean isLandscape() {
            return width > 0 && height > 0 && width >= height * LANDSCAPE_MIN_ASPECT;
        }

        /** Video aspect (w/h), or 16:9 when the server sent no dimensions. */
        public float aspect() {
            return width > 0 && height > 0 ? (float) width / height : 16f / 9f;
        }

        /**
         * True for a video that belongs in the long-form watch page rather than the vertical reels
         * player: the full-width cards of the /v1/videos pipe, plus anything long enough that reels'
         * swipe-up-to-skip model stops making sense.
         *
         * <p>Decided off the server-sent fields the reference already carries, so routing a tap needs
         * no MTProto round-trip — the tap has to open something immediately.
         */
        public boolean isLongForm() {
            return isLandscape() || durationMs >= LONG_FORM_MIN_DURATION_MS;
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

    /**
     * SHORTS pipe (GET /v1/discover): vertical, short videos only — the original diversity grid.
     * refresh=true rotates the server grid to a fresh window (pull-to-refresh); page-0 only.
     */
    public static void load(int account, String category, int offset, int limit, boolean refresh, Callback cb) {
        load(account, category, null, offset, limit, refresh, cb);
    }

    /**
     * @param cat a long-video CATEGORY slug ("komediya", "sport"...) — the SAME chip
     *            {@link #videos} takes, so one tap filters both halves of the browse grid. A
     *            different axis from {@code category} above, which is a reels interest cluster.
     *            Null = the unfiltered grid, byte-identical to what this call produced before.
     */
    public static void load(int account, String category, String cat, int offset, int limit,
                            boolean refresh, Callback cb) {
        SvipeAuth.ensureTokenRetrying(account, token -> {
            if (token == null) {
                cb.onResult(null, null, "auth");
                return;
            }
            request(account, category, cat, offset, limit, refresh, token, false, cb);
        });
    }

    private static void request(int account, String category, String cat, int offset, int limit,
                                boolean refresh, String token, boolean retried, Callback cb) {
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
        if (cat != null && !cat.isEmpty()) {
            try {
                path.append("&cat=").append(URLEncoder.encode(cat, "UTF-8"));
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
                    request(account, category, cat, offset, limit, refresh, t2, true, cb);
                });
                return;
            }
            if (res == null || !res.has("items")) {
                cb.onResult(null, null, err != null ? err : ("http " + code));
                return;
            }
            ArrayList<Item> out = new ArrayList<>();
            parseItems(res.optJSONArray("items"), recIdOf(res), out);
            Integer next = res.isNull("next_offset") ? null : Integer.valueOf(res.optInt("next_offset"));
            cb.onResult(out, next, null);
        });
    }

    /**
     * LONG-FORM pipe (GET /v1/videos): horizontal, minutes-long videos, ranked by their own server-side
     * algorithm. Same query shape, response shape and {@link Item} model as {@link #load} — the two are
     * separate only in WHAT they return, so the grid can page them independently and still render both
     * with one cell renderer.
     *
     * No client-level gate is needed: a build that predates this endpoint simply never calls it, and
     * /v1/discover stays shorts-only, so old builds keep their exact original feed by construction.
     *
     * {@code refresh=true} asks the server for a fresh window (pull-to-refresh); page-0 only.
     */
    public static void videos(int account, String category, int offset, int limit, boolean refresh, Callback cb) {
        videos(account, category, null, offset, limit, refresh, cb);
    }

    /**
     * @param cat a long-video CATEGORY slug from {@code GET /v1/videos/categories}
     *            (see {@link SvipeMovies}) — "komediya", "serial", "konsert"… A different axis from
     *            {@code category} above: that one is a reels interest cluster matched on
     *            {@code topic_id}, which ~80% of the horizontal corpus does not carry, while every
     *            long video carries its categories. Null = the unfiltered feed, byte-identical to
     *            what this call produced before categories existed.
     */
    public static void videos(int account, String category, String cat, int offset, int limit,
                              boolean refresh, Callback cb) {
        StringBuilder path = new StringBuilder("/v1/videos?limit=").append(limit).append("&offset=").append(offset);
        if (refresh) {
            path.append("&refresh=1");
        }
        if (category != null && !category.isEmpty()) {
            try {
                path.append("&category=").append(URLEncoder.encode(category, "UTF-8"));
            } catch (Exception ignore) {
            }
        }
        if (cat != null && !cat.isEmpty()) {
            try {
                path.append("&cat=").append(URLEncoder.encode(cat, "UTF-8"));
            } catch (Exception ignore) {
            }
        }
        feedGet(account, path.toString(), cb);
    }

    /**
     * The watch page's RELATED list: what belongs beside the video being watched.
     *
     * <p>Asks {@code GET /v1/videos/related} with the seed on the wire, so the list is retrieved FROM
     * the video on screen — the next episode of its show first, then the nearest videos by caption
     * embedding, then more from its channel. Before this it was {@code GET /v1/videos} minus the seed:
     * a perfectly good feed and a poor related list, because it answered "what should this user watch"
     * rather than "what goes with THIS".
     *
     * <p>The seed is still dropped here as well as server-side — one line of belt and braces, so a
     * ranking change that ever lets the seed back in cannot show a video as related to itself.
     *
     * <p>A server without the route answers 404, which {@link #feedGet} surfaces as a failed page; the
     * caller retries as an ordinary paging failure. {@code refresh} is deliberately absent: opening a
     * watch page must never reshuffle the Video tab the user came from.
     */
    public static void relatedVideos(int account, long seedChannelId, int seedMessageId,
                                     int offset, int limit, Callback cb) {
        final String path = "/v1/videos/related?seed_channel_id=" + seedChannelId
                + "&seed_message_id=" + seedMessageId
                + "&limit=" + limit + "&offset=" + offset;
        feedGet(account, path, (items, next, error) -> {
            if (items == null) {
                cb.onResult(null, next, error);
                return;
            }
            final ArrayList<Item> out = new ArrayList<>(items.size());
            for (Item it : items) {
                if (it.channelId == seedChannelId && it.messageId == seedMessageId) {
                    continue;
                }
                out.add(it);
            }
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

    /**
     * LONG-FORM search (GET /v1/videos/search) — the horizontal twin of {@link #search}, exactly as
     * {@link #videos} is the twin of {@link #load}. Same reference shape, its own cursor.
     *
     * <p>A separate endpoint rather than a {@code q=} on /v1/videos, and that is deliberate: /v1/videos
     * is an epoch-rotated, cached recommendation window, so a query must not enter its cache key nor
     * touch its rotation counter. Searching therefore never disturbs the Video tab the user came from.
     *
     * <p>An older server without this route answers 404, which {@link #feedGet} surfaces as a failed
     * page — the grid retires the long pipe after its failure cap and search degrades to the shorts-only
     * list it produces today, never to an error screen.
     */
    public static void videosSearch(int account, String query, int offset, int limit, Callback cb) {
        String q;
        try {
            q = URLEncoder.encode(query == null ? "" : query, "UTF-8");
        } catch (Exception e) {
            q = "";
        }
        feedGet(account, "/v1/videos/search?q=" + q + "&limit=" + limit + "&offset=" + offset, cb);
    }

    /** Reels the user recently watched, newest first (the "watching history"). Same reference shape as the grid. */
    public static void reelsHistory(int account, int offset, int limit, Callback cb) {
        feedGet(account, "/v1/reels/history?limit=" + limit + "&offset=" + offset, cb);
    }

    /** Shared GET for the reference-list endpoints returning {items:[FeedItem], next_offset}. */
    private static void feedGet(int account, String path, Callback cb) {

        SvipeAuth.ensureTokenRetrying(account, token -> {
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
            parseItems(res.optJSONArray("items"), recIdOf(res), out);
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
        sendEvent(account, channelId, 0, "UNBLOCK_CHANNEL", cb);
    }

    /**
     * Post ONE recsys event for a reference the user acted on outside the reels player — the explore
     * grid's ⋮ menu (NOT_INTERESTED, BLOCK_CHANNEL) and channel unblocking.
     *
     * ReelsActivity has its own sendEvent/postEvents pair, but those are private and hang off the
     * fragment's cached token, so they cannot serve the grid. Same wire contract as there:
     * POST /v1/events with {"events":[{channel_id, message_id, event_type}]}, one silent re-auth
     * retry on 401. {@code messageId} is 0 for channel-level actions, which carry no single post.
     *
     * These carry no payload and no {@code recommendation_id}: they are bare actions, and the grid's
     * references don't come with a recommendation id anyway ({@link Item#recId}). See the overload
     * below for the measurement events the long-form player sends.
     */
    public static void sendEvent(int account, long channelId, int messageId, String eventType, EventCallback cb) {
        sendEvent(account, channelId, messageId, eventType, null, null, cb);
    }

    /**
     * The same event carrying a MEASUREMENT payload and the recommendation it came from — what the
     * long-form watch player needs and the payload-less form above cannot express (watch time,
     * buffering, time-to-first-frame). ReelsActivity has an equivalent private pair hanging off the
     * fragment's cached token; this is the one every surface outside that fragment posts through, so
     * there is exactly one auth + 401-retry idiom to get right.
     *
     * @param payload measurement fields, stored verbatim server-side; null for a bare action event
     * @param recId   the recommendation page the reference arrived with, or null (unattributed)
     */
    public static void sendEvent(int account, long channelId, int messageId, String eventType,
                                 JSONObject payload, String recId, EventCallback cb) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                if (cb != null) cb.onDone(false);
                return;
            }
            eventRequest(account, channelId, messageId, eventType, payload, recId, token, false, cb);
        });
    }

    private static void eventRequest(int account, long channelId, int messageId, String eventType,
                                     JSONObject payload, String recId,
                                     String token, boolean retried, EventCallback cb) {
        JSONObject batch = new JSONObject();
        try {
            JSONObject ev = new JSONObject();
            ev.put("channel_id", channelId);
            ev.put("message_id", messageId);
            ev.put("event_type", eventType);
            if (recId != null) ev.put("recommendation_id", recId);
            if (payload != null) ev.put("payload", payload);
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
                    eventRequest(account, channelId, messageId, eventType, payload, recId, t2, true, cb);
                });
                return;
            }
            if (cb != null) cb.onDone(code >= 200 && code < 300);
        });
    }

    /**
     * The recommendation id of a whole response page, or null. The reels feed sends one; the discover
     * and long-form responses do not (yet) — reading it here rather than assuming means the client
     * starts attributing their events the day the server adds the field.
     */
    private static String recIdOf(JSONObject res) {
        return res == null || res.isNull("recommendation_id") ? null : res.optString("recommendation_id", null);
    }

    /** Parse a {items:[FeedItem]} array into Items; rows without a username are skipped (can't resolve). */
    private static void parseItems(JSONArray arr, String recId, List<Item> out) {
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
            it.shareUrl = o.isNull("share_url") ? null : o.optString("share_url", null);
            it.recId = recId;
            out.add(it);
        }
    }
}
