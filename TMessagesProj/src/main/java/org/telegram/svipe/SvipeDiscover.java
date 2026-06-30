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

    public static class Item {
        public long channelId;
        public int messageId;
        public String username;
        public Integer topicId;
    }

    public interface Callback {
        /** items==null on failure. nextOffset==null when there are no more pages. */
        void onResult(List<Item> items, Integer nextOffset, String error);
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
            JSONArray arr = res.optJSONArray("items");
            if (arr != null) {
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
                    out.add(it);
                }
            }
            Integer next = res.isNull("next_offset") ? null : Integer.valueOf(res.optInt("next_offset"));
            cb.onResult(out, next, null);
        });
    }
}
