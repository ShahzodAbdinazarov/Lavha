package org.telegram.svipe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the music catalog endpoints (GET /v1/music/*). Same reference-only contract as the
 * reels feed: every track is a Telegram post reference (channel_id, message_id, username) plus
 * metadata (title/performer/duration) so lists render without resolving each row; the audio bytes
 * flow through Telegram's own FileLoader once the client resolves the message. Mirrors
 * SvipeDiscover's auth + 401-retry idiom.
 */
public class SvipeMusic {

    public static class Track {
        public long channelId;
        public int messageId;
        public String username;
        public String title;
        public String performer;
        public int durationS;
        public long size;
        public boolean hasThumb;

        public String key() {
            return channelId + ":" + messageId;
        }
    }

    public static class Section {
        public String key;
        public String title;
        public final ArrayList<Track> tracks = new ArrayList<>();
    }

    public interface HomeCallback {
        /** sections==null on failure. */
        void onResult(List<Section> sections, String error);
    }

    public interface TracksCallback {
        /** items==null on failure. nextCursor==null when there are no more pages. */
        void onResult(List<Track> items, String recommendationId, String nextCursor, String error);
    }

    public static void home(int account, HomeCallback cb) {
        withToken(account, () -> cb.onResult(null, "auth"),
            token -> homeRequest(account, token, false, cb));
    }

    private static void homeRequest(int account, String token, boolean retried, HomeCallback cb) {
        SvipeApi.get("/v1/music/home", token, (res, code, err) -> {
            if (code == 401 && !retried) {
                reauth(account, () -> cb.onResult(null, "auth"),
                    t2 -> homeRequest(account, t2, true, cb));
                return;
            }
            if (res == null || !res.has("sections")) {
                cb.onResult(null, err != null ? err : ("http " + code));
                return;
            }
            ArrayList<Section> out = new ArrayList<>();
            JSONArray arr = res.optJSONArray("sections");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Section s = new Section();
                    s.key = o.optString("key");
                    s.title = o.optString("title");
                    parseTracks(o.optJSONArray("items"), s.tracks);
                    if (!s.tracks.isEmpty()) {
                        out.add(s);
                    }
                }
            }
            cb.onResult(out, null);
        });
    }

    public static void vibe(int account, String cursor, Long seedChannelId, Integer seedMessageId, TracksCallback cb) {
        StringBuilder path = new StringBuilder("/v1/music/vibe");
        char sep = '?';
        if (cursor != null && !cursor.isEmpty()) {
            path.append(sep).append("cursor=").append(urlEncode(cursor));
            sep = '&';
        }
        if (seedChannelId != null && seedMessageId != null) {
            path.append(sep).append("seed_channel_id=").append(seedChannelId)
                .append("&seed_message_id=").append(seedMessageId);
        }
        tracksGet(account, path.toString(), cb);
    }

    public static void search(int account, String query, int offset, int limit, TracksCallback cb) {
        tracksGet(account, "/v1/music/search?q=" + urlEncode(query) + "&limit=" + limit + "&offset=" + offset, cb);
    }

    public static void liked(int account, int offset, int limit, TracksCallback cb) {
        tracksGet(account, "/v1/music/liked?limit=" + limit + "&offset=" + offset, cb);
    }

    /** Shared GET for all endpoints returning {items, recommendation_id?, next_cursor?/next_offset?}. */
    private static void tracksGet(int account, String path, TracksCallback cb) {
        withToken(account, () -> cb.onResult(null, null, null, "auth"),
            token -> tracksRequest(account, path, token, false, cb));
    }

    private static void tracksRequest(int account, String path, String token, boolean retried, TracksCallback cb) {
        SvipeApi.get(path, token, (res, code, err) -> {
            if (code == 401 && !retried) {
                reauth(account, () -> cb.onResult(null, null, null, "auth"),
                    t2 -> tracksRequest(account, path, t2, true, cb));
                return;
            }
            if (res == null || !res.has("items")) {
                cb.onResult(null, null, null, err != null ? err : ("http " + code));
                return;
            }
            ArrayList<Track> out = new ArrayList<>();
            parseTracks(res.optJSONArray("items"), out);
            String recId = res.isNull("recommendation_id") ? null : res.optString("recommendation_id", null);
            String next;
            if (!res.isNull("next_cursor")) {
                next = res.optString("next_cursor", null);
            } else if (!res.isNull("next_offset")) {
                next = String.valueOf(res.optInt("next_offset"));
            } else {
                next = null;
            }
            cb.onResult(out, recId, next, null);
        });
    }

    /** Fire-and-forget telemetry. payload may be null. */
    public static void sendEvent(int account, Track track, String eventType, JSONObject payload) {
        if (track == null || eventType == null) {
            return;
        }
        try {
            JSONObject ev = new JSONObject();
            ev.put("channel_id", track.channelId);
            ev.put("message_id", track.messageId);
            ev.put("event_type", eventType);
            ev.put("client_ts", System.currentTimeMillis() / 1000.0);
            if (payload != null) {
                ev.put("payload", payload);
            }
            JSONObject batch = new JSONObject();
            JSONArray events = new JSONArray();
            events.put(ev);
            batch.put("events", events);
            postEvents(account, batch, false);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void postEvents(int account, JSONObject batch, boolean retried) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                return;
            }
            SvipeApi.post("/v1/music/events", batch, token, (res, code, err) -> {
                if (code == 401 && !retried) {
                    SvipeAuth.invalidateAccessToken(account);
                    postEvents(account, batch, true);
                }
            });
        });
    }

    /* internals */

    private interface TokenAction {
        void run(String token);
    }

    private static void withToken(int account, Runnable onAuthFail, TokenAction action) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                onAuthFail.run();
                return;
            }
            action.run(token);
        });
    }

    // Access token died: silent re-auth, one retry — same pattern as SvipeDiscover.
    private static void reauth(int account, Runnable onAuthFail, TokenAction retry) {
        SvipeAuth.invalidateAccessToken(account);
        SvipeAuth.ensureToken(account, t2 -> {
            if (t2 == null) {
                onAuthFail.run();
                return;
            }
            retry.run(t2);
        });
    }

    private static void parseTracks(JSONArray arr, List<Track> out) {
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String username = o.isNull("username") ? null : o.optString("username", null);
            if (username == null || username.isEmpty()) continue;
            Track t = new Track();
            t.channelId = o.optLong("channel_id");
            t.messageId = o.optInt("message_id");
            t.username = username;
            t.title = o.isNull("title") ? null : o.optString("title", null);
            t.performer = o.isNull("performer") ? null : o.optString("performer", null);
            t.durationS = o.optInt("duration_s");
            t.size = o.optLong("size");
            t.hasThumb = o.optBoolean("has_thumb", false);
            out.add(t);
        }
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
