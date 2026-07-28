package org.telegram.svipe;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * In-memory cache of the channel ids indexed for Svipe music, so ANY channel row — chat list, search
 * result, action bar, profile — can decide whether to draw the ♪ badge synchronously, with no
 * per-channel network call. Backed by SharedPreferences for an instant, correct cold start; refreshed
 * from {@code GET /v1/music/channels/indexed}. The catalog is small (curated), so the whole set fits.
 */
public final class SvipeMusicIndex {

    private SvipeMusicIndex() {}

    private static final String PREFS = "svipe_music_index";
    private static final String KEY_IDS = "indexed_ids";
    private static final long REFRESH_INTERVAL = 6 * 60 * 60 * 1000L; // 6h

    private static volatile Set<Long> ids;      // null until first loaded (disk or network)
    private static volatile boolean loading;
    private static long lastFetch;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True when this channel is indexed for music. Never blocks; false until the set is available. */
    public static boolean isIndexed(long channelId) {
        Set<Long> s = ids;
        if (s == null) {
            s = loadFromDisk();
        }
        return s.contains(channelId);
    }

    private static synchronized Set<Long> loadFromDisk() {
        if (ids != null) {
            return ids;
        }
        Set<Long> s = new HashSet<>();
        try {
            String raw = prefs().getString(KEY_IDS, null);
            if (raw != null) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    s.add(arr.getLong(i));
                }
            }
        } catch (Exception ignore) {
        }
        ids = s;
        return s;
    }

    /** Optimistically mark one channel indexed (request accepted / action bar reported indexed). */
    public static void markIndexed(long channelId) {
        Set<Long> s = loadFromDisk();
        if (s.contains(channelId)) {
            return;
        }
        Set<Long> next = new HashSet<>(s);
        next.add(channelId);
        ids = next;
        persist(next);
        notifyUpdated();
    }

    /** Refresh the set from the backend when stale. Call from the chat list. Cheap; the set is small. */
    public static void ensureLoaded(int account) {
        loadFromDisk();
        long now = System.currentTimeMillis();
        if (loading || (lastFetch != 0 && now - lastFetch < REFRESH_INTERVAL)) {
            return;
        }
        loading = true;
        lastFetch = now;
        SvipeMusic.indexedChannelIds(account, list -> AndroidUtilities.runOnUIThread(() -> {
            loading = false;
            if (list == null) {
                return;
            }
            Set<Long> next = new HashSet<>(list);
            boolean changed = !next.equals(ids);
            ids = next;
            persist(next);
            if (changed) {
                notifyUpdated();
            }
        }));
    }

    private static void persist(Set<Long> s) {
        try {
            JSONArray arr = new JSONArray();
            for (Long id : s) {
                arr.put((long) id);
            }
            prefs().edit().putString(KEY_IDS, arr.toString()).apply();
        } catch (Exception ignore) {
        }
    }

    private static void notifyUpdated() {
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.svipeMusicIndexUpdated));
    }
}
