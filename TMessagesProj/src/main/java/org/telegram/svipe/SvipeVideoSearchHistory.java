package org.telegram.svipe;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Bounded, most-recent-first ledger of the reels the user tapped from video / discover search —
 * stored as the video REFERENCE itself (channel_id, message_id, username, topic_id), native-Telegram
 * style, rather than the typed query. The recent list therefore renders with the grid's own
 * reference→thumbnail cell and tapping a recent re-opens that reel (ofDiscoverSeed) instead of
 * re-running a search. A search that never yields a tap stores nothing.
 *
 * Deduped by (channel_id, message_id) — the newest tap floats to the top; the oldest evicts once the
 * cap is hit. Same JSON-blob-in-main-settings pattern as {@link SvipeWatchedSet} /
 * {@link SvipeMusicSearchHistory}, serialized + persisted off the UI thread.
 */
public class SvipeVideoSearchHistory {

    private static final int MAX_ITEMS = 20;

    private final int account;
    private final LinkedList<SvipeDiscover.Item> items = new LinkedList<>();

    public SvipeVideoSearchHistory(int account) {
        this.account = account;
        load();
    }

    public synchronized void add(SvipeDiscover.Item ref) {
        if (ref == null || ref.username == null || ref.username.isEmpty()) return;
        for (Iterator<SvipeDiscover.Item> it = items.iterator(); it.hasNext(); ) {
            if (sameKey(it.next(), ref)) {
                it.remove();
                break;
            }
        }
        items.addFirst(ref);
        while (items.size() > MAX_ITEMS) {
            items.removeLast();
        }
        persist();
    }

    /** Most-recent-first snapshot (a copy — safe to iterate without the lock). */
    public synchronized List<SvipeDiscover.Item> getAll() {
        return new ArrayList<>(items);
    }

    public synchronized void remove(SvipeDiscover.Item ref) {
        if (ref == null) return;
        boolean changed = false;
        for (Iterator<SvipeDiscover.Item> it = items.iterator(); it.hasNext(); ) {
            if (sameKey(it.next(), ref)) {
                it.remove();
                changed = true;
                break;
            }
        }
        if (changed) persist();
    }

    public synchronized void clear() {
        if (items.isEmpty()) return;
        items.clear();
        persist();
    }

    public synchronized int size() {
        return items.size();
    }

    private static boolean sameKey(SvipeDiscover.Item a, SvipeDiscover.Item b) {
        return a != null && b != null && a.channelId == b.channelId && a.messageId == b.messageId;
    }

    private SharedPreferences prefs() {
        return MessagesController.getMainSettings(account);
    }

    private void load() {
        items.clear();
        try {
            String blob = prefs().getString(SvipeConfig.PREF_VIDEO_SEARCH_HISTORY, null);
            if (blob == null || blob.isEmpty()) return;
            JSONArray arr = new JSONArray(blob);
            for (int i = 0; i < arr.length(); i++) {
                SvipeDiscover.Item ref = fromJson(arr.optJSONObject(i));
                if (ref != null) items.add(ref);
            }
        } catch (Exception ex) {
            FileLog.e(ex);
        }
    }

    private void persist() {
        final SvipeDiscover.Item[] snapshot = items.toArray(new SvipeDiscover.Item[0]);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (SvipeDiscover.Item ref : snapshot) {
                    JSONObject o = toJson(ref);
                    if (o != null) arr.put(o);
                }
                prefs().edit().putString(SvipeConfig.PREF_VIDEO_SEARCH_HISTORY, arr.toString()).apply();
            } catch (Exception ex) {
                FileLog.e(ex);
            }
        });
    }

    private static JSONObject toJson(SvipeDiscover.Item ref) {
        if (ref == null) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("channel_id", ref.channelId);
            o.put("message_id", ref.messageId);
            o.put("username", ref.username);
            if (ref.topicId != null) o.put("topic_id", ref.topicId);
            // Dimensions must round-trip: a recent entry renders in the same mixed-orientation grid,
            // so dropping them here would demote every remembered horizontal video to a portrait tile.
            if (ref.width > 0) o.put("width", ref.width);
            if (ref.height > 0) o.put("height", ref.height);
            if (ref.durationMs > 0) o.put("duration_ms", ref.durationMs);
            if (ref.shareUrl != null) o.put("share_url", ref.shareUrl);
            return o;
        } catch (Exception ex) {
            FileLog.e(ex);
            return null;
        }
    }

    private static SvipeDiscover.Item fromJson(JSONObject o) {
        if (o == null) return null;
        String username = o.isNull("username") ? null : o.optString("username", null);
        if (username == null || username.isEmpty()) return null;
        SvipeDiscover.Item ref = new SvipeDiscover.Item();
        ref.channelId = o.optLong("channel_id");
        ref.messageId = o.optInt("message_id");
        ref.username = username;
        ref.topicId = o.isNull("topic_id") ? null : o.optInt("topic_id");
        ref.width = o.optInt("width", 0);       // absent in entries stored before the mixed grid
        ref.height = o.optInt("height", 0);
        ref.durationMs = o.optInt("duration_ms", 0);
        ref.shareUrl = o.isNull("share_url") ? null : o.optString("share_url", null);
        return ref;
    }
}
