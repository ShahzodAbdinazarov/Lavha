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
 * In-memory cache of the channel ids Svipe has indexed, so ANY channel row — chat list, search
 * result, action bar, profile — can decide whether to draw the badge synchronously, with no
 * per-channel network call. Backed by SharedPreferences for an instant, correct cold start; refreshed
 * from {@code GET /v1/channels/indexed}. Hundreds of channels, so the whole set fits.
 *
 * <p>It used to hold the MUSIC-indexed set and the badge was a ♪. Both widened together: the badge is
 * now the Reels tab's own mark and means "this channel is in Svipe", which is the thing somebody
 * scrolling their chat list actually wants to know. The disk key changed with the meaning, so a
 * device upgrading does not spend six hours showing music channels under a badge that no longer says
 * music.
 */
public final class SvipeChannelIndex {

    private SvipeChannelIndex() {}

    private static final String PREFS = "svipe_indexed_channels";
    private static final String KEY_IDS = "indexed_ids";
    private static final String KEY_NAMES = "indexed_usernames";
    private static final long REFRESH_INTERVAL = 6 * 60 * 60 * 1000L; // 6h

    private static volatile Set<Long> ids;      // null until first loaded (disk or network)
    /** Channels the server saw but never resolved to an id — matched on username instead. */
    private static volatile Set<String> usernames = new HashSet<>();
    private static volatile boolean loading;
    private static long lastFetch;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True when Svipe has already looked at this channel. Never blocks; false until the set loads. */
    public static boolean isIndexed(long channelId) {
        Set<Long> s = ids;
        if (s == null) {
            s = loadFromDisk();
        }
        return s.contains(channelId);
    }

    /**
     * The same question for a channel we only know by name — a submission the server never managed to
     * resolve. Without this those channels look unseen and get sent in again and again.
     */
    public static boolean isIndexed(long channelId, String username) {
        if (isIndexed(channelId)) {
            return true;
        }
        if (username == null || username.isEmpty()) {
            return false;
        }
        loadFromDisk();
        return usernames.contains(username.toLowerCase(java.util.Locale.ROOT));
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
            String rawNames = prefs().getString(KEY_NAMES, null);
            if (rawNames != null) {
                Set<String> n = new HashSet<>();
                JSONArray arr = new JSONArray(rawNames);
                for (int i = 0; i < arr.length(); i++) {
                    n.add(arr.getString(i));
                }
                usernames = n;
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
        persist(next, usernames);
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
        SvipeMusic.indexedChannelIds(account, (list, names) -> AndroidUtilities.runOnUIThread(() -> {
            loading = false;
            if (list == null) {
                return;
            }
            Set<Long> next = new HashSet<>(list);
            Set<String> nextNames = names == null ? new HashSet<>() : new HashSet<>(names);
            boolean changed = !next.equals(ids) || !nextNames.equals(usernames);
            ids = next;
            usernames = nextNames;
            persist(next, nextNames);
            if (changed) {
                notifyUpdated();
            }
        }));
    }

    private static void persist(Set<Long> s, Set<String> names) {
        try {
            JSONArray arr = new JSONArray();
            for (Long id : s) {
                arr.put((long) id);
            }
            JSONArray narr = new JSONArray();
            for (String n : names) {
                narr.put(n);
            }
            prefs().edit()
                    .putString(KEY_IDS, arr.toString())
                    .putString(KEY_NAMES, narr.toString())
                    .apply();
        } catch (Exception ignore) {
        }
    }

    private static void notifyUpdated() {
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.svipeChannelIndexUpdated));
    }
}
