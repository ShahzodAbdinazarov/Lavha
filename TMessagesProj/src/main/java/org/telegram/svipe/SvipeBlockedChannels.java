package org.telegram.svipe;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Persistent set of reels channel ids the user has blocked, so the explore grid / feed can hide a
 * blocked channel instantly (before the server round-trip) and a "blocked channels" screen can list
 * them offline. Insertion-ordered (newest last); the oldest evicts once the cap is hit.
 *
 * Backed by a single JSON-array blob in the account's main settings, persisted off the UI thread —
 * same LinkedHashSet shape as {@link SvipeWatchedSet}.
 */
public class SvipeBlockedChannels {

    private static final int MAX_BLOCKED = 1000;

    private final int account;
    private final LinkedHashSet<Long> ids = new LinkedHashSet<>();

    public SvipeBlockedChannels(int account) {
        this.account = account;
        load();
    }

    public synchronized boolean contains(long channelId) {
        return ids.contains(channelId);
    }

    public synchronized void add(long channelId) {
        if (ids.contains(channelId)) return;
        ids.add(channelId);
        while (ids.size() > MAX_BLOCKED) {
            Iterator<Long> it = ids.iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove(); // drop the oldest
        }
        persist();
    }

    public synchronized void remove(long channelId) {
        if (ids.remove(channelId)) persist();
    }

    /** Snapshot of the blocked ids (a copy — safe to iterate without the lock). */
    public synchronized List<Long> getAll() {
        return new ArrayList<>(ids);
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
            String blob = prefs().getString(SvipeConfig.PREF_REEL_BLOCKED_CHANNELS, null);
            if (blob == null || blob.isEmpty()) return;
            JSONArray arr = new JSONArray(blob);
            for (int i = 0; i < arr.length(); i++) {
                long id = arr.optLong(i, 0);
                if (id != 0) ids.add(id);
            }
        } catch (Exception ex) {
            FileLog.e(ex);
        }
    }

    private void persist() {
        // Snapshot under the caller's lock (cheap), then serialize + write off the UI thread.
        final Long[] snapshot = ids.toArray(new Long[0]);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (Long id : snapshot) arr.put((long) id);
                prefs().edit().putString(SvipeConfig.PREF_REEL_BLOCKED_CHANNELS, arr.toString()).apply();
            } catch (Exception ex) {
                FileLog.e(ex);
            }
        });
    }
}
