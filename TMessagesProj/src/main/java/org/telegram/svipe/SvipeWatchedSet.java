package org.telegram.svipe;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Bounded, insertion-ordered ledger of reels the user has already watched, so they never re-enter
 * the offline ready-queue or get replayed on a cold start. Keyed by channelId:messageId (a repost
 * of the same document on a different message is a legitimately distinct feed item).
 *
 * Backed by a single JSON-array blob in the account's main settings; oldest entries evict first
 * once the cap is hit.
 */
public class SvipeWatchedSet {

    private static final int MAX_WATCHED = 2000;

    private final int account;
    private final LinkedHashSet<String> ids = new LinkedHashSet<>();

    public SvipeWatchedSet(int account) {
        this.account = account;
        load();
    }

    public synchronized boolean isWatched(long channelId, int messageId) {
        return ids.contains(SvipeQueuePlan.compositeKey(channelId, messageId));
    }

    public synchronized void markWatched(long channelId, int messageId) {
        String key = SvipeQueuePlan.compositeKey(channelId, messageId);
        if (ids.contains(key)) return;
        ids.add(key);
        while (ids.size() > MAX_WATCHED) {
            Iterator<String> it = ids.iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove(); // drop the oldest
        }
        persist();
    }

    public synchronized int size() {
        return ids.size();
    }

    private SharedPreferences prefs() {
        return MessagesController.getMainSettings(account);
    }

    private void load() {
        ids.clear();
        try {
            String blob = prefs().getString(SvipeConfig.PREF_REEL_WATCHED, null);
            if (blob == null || blob.isEmpty()) return;
            JSONArray arr = new JSONArray(blob);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (s != null && !s.isEmpty()) ids.add(s);
            }
        } catch (Exception ex) {
            FileLog.e(ex);
        }
    }

    private void persist() {
        // Snapshot under the caller's lock (cheap), then serialize + write off the UI thread:
        // markWatched runs on every swipe, and a ~2000-entry JSON build on the main thread janks paging.
        final String[] snapshot = ids.toArray(new String[0]);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (String s : snapshot) arr.put(s);
                prefs().edit().putString(SvipeConfig.PREF_REEL_WATCHED, arr.toString()).apply();
            } catch (Exception ex) {
                FileLog.e(ex);
            }
        });
    }
}
