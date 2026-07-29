package org.telegram.svipe;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Bounded, most-recent-first ledger of the user's music search queries — the "recent searches" row
 * under the search box. Deduped case-insensitively (the newest spelling floats to the top); the
 * oldest evicts once the cap is hit.
 *
 * Backed by a single JSON-array blob in the account's main settings, serialized + persisted off the
 * UI thread — same shape as {@link SvipeWatchedSet}. Subclassed by {@link SvipeVideoSearchHistory},
 * which only swaps the storage key.
 */
public class SvipeMusicSearchHistory {

    private static final int MAX_QUERIES = 20;

    private final int account;
    private final LinkedList<String> queries = new LinkedList<>();

    public SvipeMusicSearchHistory(int account) {
        this.account = account;
        load();
    }

    /** The SvipeConfig key this store persists under; overridden by the video-search subclass. */
    protected String prefKey() {
        return SvipeConfig.PREF_MUSIC_SEARCH_HISTORY;
    }

    public synchronized void add(String query) {
        if (query == null) return;
        String q = query.trim();
        if (q.isEmpty()) return;
        for (Iterator<String> it = queries.iterator(); it.hasNext(); ) {
            if (it.next().equalsIgnoreCase(q)) {
                it.remove();
                break;
            }
        }
        queries.addFirst(q);
        while (queries.size() > MAX_QUERIES) {
            queries.removeLast();
        }
        persist();
    }

    /** Most-recent-first snapshot (a copy — safe to iterate without the lock). */
    public synchronized List<String> getAll() {
        return new ArrayList<>(queries);
    }

    public synchronized void remove(String query) {
        if (query == null) return;
        boolean changed = false;
        for (Iterator<String> it = queries.iterator(); it.hasNext(); ) {
            if (it.next().equalsIgnoreCase(query)) {
                it.remove();
                changed = true;
                break;
            }
        }
        if (changed) persist();
    }

    public synchronized void clear() {
        if (queries.isEmpty()) return;
        queries.clear();
        persist();
    }

    public synchronized int size() {
        return queries.size();
    }

    private SharedPreferences prefs() {
        return MessagesController.getMainSettings(account);
    }

    private void load() {
        queries.clear();
        try {
            String blob = prefs().getString(prefKey(), null);
            if (blob == null || blob.isEmpty()) return;
            JSONArray arr = new JSONArray(blob);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (s != null && !s.isEmpty()) queries.add(s);
            }
        } catch (Exception ex) {
            FileLog.e(ex);
        }
    }

    private void persist() {
        // Snapshot under the caller's lock (cheap), then serialize + write off the UI thread.
        final String[] snapshot = queries.toArray(new String[0]);
        final String key = prefKey();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (String s : snapshot) arr.put(s);
                prefs().edit().putString(key, arr.toString()).apply();
            } catch (Exception ex) {
                FileLog.e(ex);
            }
        });
    }
}
