package org.telegram.svipe;

import android.content.SharedPreferences;
import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The user's favourite songs — a per-account, device-local store that the backend mirrors for catalog
 * songs only.
 *
 * <p>Local is the source of truth: tapping the heart mutates the map and persists immediately, so the
 * UI never waits on the network and works offline. The backend copy exists so favourites survive a
 * reinstall and reach a second device. Anything that is not a catalog song ({@link SvipeFavourite#isSyncable()})
 * is never uploaded — that is the "not in a public channel -> only I can see it" rule, enforced here
 * rather than trusted to the server.
 *
 * <p>Merge semantics (see {@link #merge}): entries added since the last successful sync are pushed up;
 * older entries the server no longer has were un-favourited on another device and are dropped locally.
 * Without that split, an un-favourite on device A would be resurrected by device B re-uploading its
 * stale copy, and the entry could never die.
 */
public class SvipeFavouritesSet {

    private static final int MAX_FAVOURITES = 1000;
    private static final int SYNC_PAGE = 100;
    private static final int SYNC_MAX_PAGES = 10;   // 1000 = MAX_FAVOURITES

    private static final SparseArray<SvipeFavouritesSet> INSTANCES = new SparseArray<>();

    private final int account;
    // key -> entry, newest LAST (LinkedHashMap insertion order); list() reverses for display.
    private final LinkedHashMap<String, SvipeFavourite> items = new LinkedHashMap<>();
    // Song ids removed locally whose DELETE the server has not acknowledged yet. Survives a restart so
    // an un-favourite made offline is not undone by the next sync.
    private final LinkedHashSet<Long> pendingRemovals = new LinkedHashSet<>();
    private boolean syncStarted;

    public static synchronized SvipeFavouritesSet getInstance(int account) {
        SvipeFavouritesSet set = INSTANCES.get(account);
        if (set == null) {
            set = new SvipeFavouritesSet(account);
            INSTANCES.put(account, set);
        }
        return set;
    }

    private SvipeFavouritesSet(int account) {
        this.account = account;
        load();
    }

    /**
     * Forget a catalog song's favourite LOCALLY, without telling the server.
     *
     * <p>For one caller only: a dislike landed, and the server dropped the favourite as part of that
     * same write ({@link SvipeMusicDislikes}). Pushing a second removal from here would be a request
     * for work already done, and — worse — one that could race the dislike itself.
     */
    public void removeSong(long songId) {
        if (songId <= 0) {
            return;
        }
        final boolean removed;
        synchronized (this) {
            removed = items.remove("song:" + songId) != null;
            if (removed) {
                persistLocked();
            }
        }
        if (removed) {
            notifyChanged();
        }
    }

    public synchronized boolean isFavourite(String key) {
        return key != null && items.containsKey(key);
    }

    /** Newest first — the order the Music page renders. */
    public synchronized List<SvipeFavourite> list() {
        ArrayList<SvipeFavourite> out = new ArrayList<>(items.values());
        Collections.reverse(out);
        return out;
    }

    public synchronized int size() {
        return items.size();
    }

    /**
     * Flip the favourite state for this entry. Returns the state AFTER the toggle (true = favourited).
     * Local mutation + notification happen synchronously; the backend call is fire-and-forget.
     */
    public boolean toggle(SvipeFavourite fav) {
        if (fav == null || fav.key == null) {
            return false;
        }
        final boolean nowFavourite;
        synchronized (this) {
            if (items.remove(fav.key) != null) {
                nowFavourite = false;
            } else {
                if (fav.addedAt == 0) {
                    fav.addedAt = System.currentTimeMillis();
                }
                items.put(fav.key, fav);
                evictOverflow();
                nowFavourite = true;
            }
            persistLocked();
        }
        notifyChanged();
        if (fav.isSyncable()) {
            pushOne(fav.songId, nowFavourite);
        }
        return nowFavourite;
    }

    /** Adopt a catalog song id discovered after the fact, re-keying the entry from "msg:" to "song:". */
    synchronized SvipeFavourite get(String key) {
        return key != null ? items.get(key) : null;
    }

    /**
     * A favourite first stored as {@link SvipeFavKey#KIND_MSG} turned out to be a catalog song. Re-key it
     * so it dedupes against the catalog and starts syncing. No-op when the entry is gone (the user
     * un-favourited while the lookup was in flight) or the song key already exists.
     */
    public void upgradeToSong(String msgKey, long songId) {
        if (msgKey == null || songId <= 0) {
            return;
        }
        final SvipeFavourite upgraded;
        synchronized (this) {
            SvipeFavourite old = items.get(msgKey);
            if (old == null) {
                return;                     // un-favourited meanwhile — do not resurrect it
            }
            String songKey = SvipeFavKey.song(songId).key;
            items.remove(msgKey);
            if (items.containsKey(songKey)) {
                persistLocked();
                upgraded = null;            // already favourited under its catalog identity
            } else {
                old.key = songKey;
                old.kind = SvipeFavKey.KIND_SONG;
                old.songId = songId;
                items.put(songKey, old);
                persistLocked();
                upgraded = old;
            }
        }
        notifyChanged();
        if (upgraded != null) {
            pushOne(songId, true);
        }
    }

    /** One-shot per process: pull the server copy and reconcile. Safe to call from any screen. */
    public void syncFromServer() {
        synchronized (this) {
            if (syncStarted) {
                return;
            }
            syncStarted = true;
        }
        fetchPage(new ArrayList<>(), 0);
    }

    private void fetchPage(ArrayList<SvipeMusic.Song> acc, int offset) {
        SvipeMusic.favourites(account, offset, SYNC_PAGE, (items, nextOffset, error) -> {
            if (items == null) {
                return;                                     // offline / auth — keep local as-is
            }
            acc.addAll(items);
            int page = offset / SYNC_PAGE + 1;
            if (nextOffset != null && !nextOffset.isEmpty() && page < SYNC_MAX_PAGES) {
                try {
                    fetchPage(acc, Integer.parseInt(nextOffset));
                    return;
                } catch (NumberFormatException ignore) {
                }
            }
            // complete == we saw the whole server list, so an absent entry really was removed there.
            boolean complete = nextOffset == null || nextOffset.isEmpty();
            applyServerList(acc, complete);
        });
    }

    private void applyServerList(List<SvipeMusic.Song> remote, boolean complete) {
        final ArrayList<Long> toRepush = new ArrayList<>();
        final ArrayList<Long> toReremove;
        synchronized (this) {
            long lastSync = prefs().getLong(SvipeConfig.PREF_MUSIC_FAV_SYNCED_AT, 0);
            // Anything the server still lists but we removed locally has to be re-deleted, and must not
            // be adopted back in the meantime.
            toReremove = new ArrayList<>(pendingRemovals);
            merge(items, remote, lastSync, complete, toRepush, pendingRemovals);
            evictOverflow();
            persistLocked();
            prefs().edit().putLong(SvipeConfig.PREF_MUSIC_FAV_SYNCED_AT, System.currentTimeMillis()).apply();
        }
        notifyChanged();
        for (Long songId : toRepush) {
            pushOne(songId, true);
        }
        for (Long songId : toReremove) {
            pushOne(songId, false);
        }
    }

    /**
     * Reconcile the local map with the server's list. Pure logic, package-visible for unit tests.
     *
     * <ul>
     *   <li>server entry we don't have -> add it;</li>
     *   <li>local syncable entry added AFTER the last sync -> the server hasn't seen it, push it up;</li>
     *   <li>local syncable entry added BEFORE the last sync and absent from a COMPLETE server list ->
     *       un-favourited on another device, drop it;</li>
     *   <li>non-syncable entries (private/unknown audio) are never touched.</li>
     * </ul>
     *
     * @param complete false when the server list was truncated — deletions are then skipped, because an
     *                 unseen page is indistinguishable from a removal.
     * @param pendingRemovals song ids the user removed locally that the server has not acknowledged yet;
     *                 never re-adopted, or an offline un-favourite would come straight back.
     */
    static void merge(LinkedHashMap<String, SvipeFavourite> local, List<SvipeMusic.Song> remote,
                      long lastSyncAt, boolean complete, Collection<Long> outRepush,
                      Set<Long> pendingRemovals) {
        HashSet<Long> remoteIds = new HashSet<>();
        for (SvipeMusic.Song s : remote) {
            if (s != null && s.id > 0) {
                remoteIds.add(s.id);
            }
        }

        // Drop local catalog favourites the server no longer has (removed on another device).
        if (complete) {
            Iterator<SvipeFavourite> it = local.values().iterator();
            while (it.hasNext()) {
                SvipeFavourite f = it.next();
                if (f.isSyncable() && f.addedAt <= lastSyncAt && !remoteIds.contains(f.songId)) {
                    it.remove();
                }
            }
        }

        // Push up anything favourited locally since the last sync that the server is missing.
        for (SvipeFavourite f : local.values()) {
            if (f.isSyncable() && f.addedAt > lastSyncAt && !remoteIds.contains(f.songId)) {
                outRepush.add(f.songId);
            }
        }

        // Adopt server entries we don't have. Oldest first so insertion order stays newest-last; the
        // server returns newest first.
        for (int i = remote.size() - 1; i >= 0; i--) {
            SvipeMusic.Song s = remote.get(i);
            if (s == null || s.id <= 0) {
                continue;
            }
            if (pendingRemovals != null && pendingRemovals.contains(s.id)) {
                continue;   // removed here, server just hasn't caught up
            }
            String key = SvipeFavKey.song(s.id).key;
            SvipeFavourite existing = local.get(key);
            if (existing != null) {
                existing.title = s.shownTitle();
                existing.artist = s.shownArtist();
                continue;
            }
            SvipeFavourite f = SvipeFavourite.of(SvipeFavKey.song(s.id));
            f.title = s.shownTitle();
            f.artist = s.shownArtist();
            f.isPublic = true;
            f.addedAt = System.currentTimeMillis();
            if (s.defaultTrack != null) {
                f.channelId = s.defaultTrack.channelId;
                f.messageId = s.defaultTrack.messageId;
                f.username = s.defaultTrack.username;
                f.durationS = s.defaultTrack.durationS;
                // Where to open it if it ever fails to resolve — without this an adopted favourite
                // would have no dialog to fall back to and its row would be a dead tap.
                f.dialogId = -s.defaultTrack.channelId;
            }
            local.put(key, f);
        }
    }

    /**
     * Mirror one change to the backend.
     *
     * <p>Removals are tracked until the server confirms them. A failed ADD self-heals — the next
     * {@link #merge} sees a local entry the server lacks and pushes it again — but a failed REMOVE would
     * be actively UNDONE by that same merge re-adopting the row the server still has. So an unconfirmed
     * removal is remembered and both replayed and honoured on the next sync.
     */
    private void pushOne(long songId, boolean favourite) {
        if (songId <= 0) {
            return;
        }
        if (favourite) {
            markRemovalSettled(songId);     // re-favouriting cancels any pending removal
            SvipeMusic.favourite(account, songId, null);
        } else {
            markRemovalPending(songId);
            SvipeMusic.unfavourite(account, songId, (id, isFavourite, error) -> {
                if (error == null) {
                    markRemovalSettled(id);
                }
            });
        }
    }

    private synchronized void markRemovalPending(long songId) {
        pendingRemovals.add(songId);
        persistPendingLocked();
    }

    private synchronized void markRemovalSettled(long songId) {
        if (pendingRemovals.remove(songId)) {
            persistPendingLocked();
        }
    }

    private void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.svipeFavouritesChanged));
    }

    private void evictOverflow() {
        while (items.size() > MAX_FAVOURITES) {
            Iterator<String> it = items.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();    // oldest first
        }
    }

    private SharedPreferences prefs() {
        return MessagesController.getMainSettings(account);
    }

    private void load() {
        items.clear();
        pendingRemovals.clear();
        try {
            List<SvipeFavourite> stored = SvipeFavourite.deserialize(
                    prefs().getString(SvipeConfig.PREF_MUSIC_FAVOURITES, null));
            // Stored oldest-first, matching the map's insertion order.
            for (SvipeFavourite f : stored) {
                items.put(f.key, f);
            }
            pendingRemovals.addAll(parseIds(prefs().getString(SvipeConfig.PREF_MUSIC_FAV_PENDING_REMOVALS, null)));
        } catch (Exception ex) {
            FileLog.e(ex);
        }
    }

    /** Comma-separated song ids; anything unparseable is dropped rather than failing the whole load. */
    static List<Long> parseIds(String blob) {
        ArrayList<Long> out = new ArrayList<>();
        if (blob == null || blob.isEmpty()) {
            return out;
        }
        for (String part : blob.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0) {
                    out.add(id);
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return out;
    }

    static String joinIds(Collection<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /** Caller holds the lock. Tiny (a handful of ids), so it writes inline rather than off-thread. */
    private void persistPendingLocked() {
        try {
            prefs().edit()
                    .putString(SvipeConfig.PREF_MUSIC_FAV_PENDING_REMOVALS, joinIds(pendingRemovals))
                    .apply();
        } catch (Exception ex) {
            FileLog.e(ex);
        }
    }

    /** Caller holds the lock: snapshot cheaply here, serialize + write off the UI thread. */
    private void persistLocked() {
        final ArrayList<SvipeFavourite> snapshot = new ArrayList<>(items.values());
        Utilities.globalQueue.postRunnable(() -> {
            try {
                prefs().edit()
                        .putString(SvipeConfig.PREF_MUSIC_FAVOURITES, SvipeFavourite.serialize(snapshot))
                        .apply();
            } catch (Exception ex) {
                FileLog.e(ex);
            }
        });
    }
}
