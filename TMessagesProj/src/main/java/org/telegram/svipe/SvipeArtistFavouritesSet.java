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
 * The user's favourite singers — a per-account, device-local store the backend mirrors.
 *
 * <p>A deliberately SIMPLER sibling of {@link SvipeFavouritesSet} rather than a generified version of
 * it. Songs arrive in three identity shapes (catalog song / public post / private document), which is
 * where that class's KIND_MSG/KIND_DOC and {@code upgradeToSong} machinery comes from; an artist only
 * ever exists as a catalog row, so the artist id is the whole identity and every entry is syncable.
 * Folding the two together would push all that song-only complexity into a store that has no use for
 * it, so they stay separate.
 *
 * <p>Local is the source of truth: tapping the heart mutates the map and persists immediately, so the
 * UI never waits on the network and works offline. The reconciliation rules in {@link #merge} are the
 * same ones the songs use, and for the same reason — without the {@code addedAt} vs last-sync split, an
 * un-favourite on device A would be resurrected forever by device B re-uploading its stale copy.
 */
public class SvipeArtistFavouritesSet {

    private static final int MAX_FAVOURITES = 1000;
    private static final int SYNC_PAGE = 100;
    private static final int SYNC_MAX_PAGES = 10;   // 1000 = MAX_FAVOURITES

    private static final SparseArray<SvipeArtistFavouritesSet> INSTANCES = new SparseArray<>();

    private final int account;
    // artistId -> entry, newest LAST (LinkedHashMap insertion order); list() reverses for display.
    private final LinkedHashMap<Long, SvipeArtistFavourite> items = new LinkedHashMap<>();
    // Artist ids removed locally whose DELETE the server has not acknowledged yet. Survives a restart so
    // an un-favourite made offline is not undone by the next sync.
    private final LinkedHashSet<Long> pendingRemovals = new LinkedHashSet<>();
    private boolean syncStarted;

    public static synchronized SvipeArtistFavouritesSet getInstance(int account) {
        SvipeArtistFavouritesSet set = INSTANCES.get(account);
        if (set == null) {
            set = new SvipeArtistFavouritesSet(account);
            INSTANCES.put(account, set);
        }
        return set;
    }

    private SvipeArtistFavouritesSet(int account) {
        this.account = account;
        load();
    }

    /**
     * Forget a singer's follow LOCALLY, without telling the server — the twin of
     * {@link SvipeFavouritesSet#removeSong}. The server un-follows as part of the dislike write, so
     * a push from here would repeat work and could race it.
     */
    public void removeArtist(long artistId) {
        if (artistId <= 0) {
            return;
        }
        final boolean removed;
        synchronized (this) {
            removed = items.remove(artistId) != null;
            if (removed) {
                persistLocked();
            }
        }
        if (removed) {
            notifyChanged();
        }
    }

    public synchronized boolean isFavourite(long artistId) {
        return artistId > 0 && items.containsKey(artistId);
    }

    /** Newest first — the order the favourite-singers page renders. */
    public synchronized List<SvipeArtistFavourite> list() {
        ArrayList<SvipeArtistFavourite> out = new ArrayList<>(items.values());
        Collections.reverse(out);
        return out;
    }

    public synchronized int size() {
        return items.size();
    }

    /**
     * Flip the favourite state for this artist. Returns the state AFTER the toggle (true = favourited).
     * Local mutation + notification happen synchronously; the backend call is fire-and-forget.
     */
    public boolean toggle(SvipeArtistFavourite fav) {
        if (fav == null || fav.artistId <= 0) {
            return false;
        }
        final boolean nowFavourite;
        synchronized (this) {
            if (items.remove(fav.artistId) != null) {
                nowFavourite = false;
            } else {
                if (fav.addedAt == 0) {
                    fav.addedAt = System.currentTimeMillis();
                }
                items.put(fav.artistId, fav);
                evictOverflow();
                nowFavourite = true;
            }
            persistLocked();
        }
        notifyChanged();
        pushOne(fav.artistId, nowFavourite);
        return nowFavourite;
    }

    synchronized SvipeArtistFavourite get(long artistId) {
        return items.get(artistId);
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

    private void fetchPage(ArrayList<SvipeMusic.Artist> acc, int offset) {
        SvipeMusic.artistFavourites(account, offset, SYNC_PAGE, (items, nextOffset, error) -> {
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

    private void applyServerList(List<SvipeMusic.Artist> remote, boolean complete) {
        final ArrayList<Long> toRepush = new ArrayList<>();
        final ArrayList<Long> toReremove;
        synchronized (this) {
            long lastSync = prefs().getLong(SvipeConfig.PREF_MUSIC_ARTIST_FAV_SYNCED_AT, 0);
            // Anything the server still lists but we removed locally has to be re-deleted, and must not
            // be adopted back in the meantime.
            toReremove = new ArrayList<>(pendingRemovals);
            merge(items, remote, lastSync, complete, toRepush, pendingRemovals);
            evictOverflow();
            persistLocked();
            prefs().edit().putLong(SvipeConfig.PREF_MUSIC_ARTIST_FAV_SYNCED_AT, System.currentTimeMillis()).apply();
        }
        notifyChanged();
        for (Long artistId : toRepush) {
            pushOne(artistId, true);
        }
        for (Long artistId : toReremove) {
            pushOne(artistId, false);
        }
    }

    /**
     * Reconcile the local map with the server's list. Pure logic, package-visible for unit tests.
     *
     * <ul>
     *   <li>server entry we don't have -> add it;</li>
     *   <li>local entry added AFTER the last sync -> the server hasn't seen it, push it up;</li>
     *   <li>local entry added BEFORE the last sync and absent from a COMPLETE server list ->
     *       un-favourited on another device, drop it.</li>
     * </ul>
     *
     * @param complete false when the server list was truncated — deletions are then skipped, because an
     *                 unseen page is indistinguishable from a removal.
     * @param pendingRemovals artist ids the user removed locally that the server has not acknowledged
     *                 yet; never re-adopted, or an offline un-favourite would come straight back.
     */
    static void merge(LinkedHashMap<Long, SvipeArtistFavourite> local, List<SvipeMusic.Artist> remote,
                      long lastSyncAt, boolean complete, Collection<Long> outRepush,
                      Set<Long> pendingRemovals) {
        HashSet<Long> remoteIds = new HashSet<>();
        for (SvipeMusic.Artist a : remote) {
            if (a != null && a.id > 0) {
                remoteIds.add(a.id);
            }
        }

        // Drop local favourites the server no longer has (removed on another device).
        if (complete) {
            Iterator<SvipeArtistFavourite> it = local.values().iterator();
            while (it.hasNext()) {
                SvipeArtistFavourite f = it.next();
                if (f.addedAt <= lastSyncAt && !remoteIds.contains(f.artistId)) {
                    it.remove();
                }
            }
        }

        // Push up anything favourited locally since the last sync that the server is missing.
        for (SvipeArtistFavourite f : local.values()) {
            if (f.addedAt > lastSyncAt && !remoteIds.contains(f.artistId)) {
                outRepush.add(f.artistId);
            }
        }

        // Adopt server entries we don't have. Oldest first so insertion order stays newest-last; the
        // server returns newest first.
        for (int i = remote.size() - 1; i >= 0; i--) {
            SvipeMusic.Artist a = remote.get(i);
            if (a == null || a.id <= 0) {
                continue;
            }
            if (pendingRemovals != null && pendingRemovals.contains(a.id)) {
                continue;   // removed here, server just hasn't caught up
            }
            SvipeArtistFavourite existing = local.get(a.id);
            if (existing != null) {
                // Refresh the cached display fields: enrichment can land after we stored the entry.
                existing.name = a.name;
                existing.displayName = a.displayName;
                existing.photoUrl = a.photoUrl;
                existing.songCount = a.songCount;
                existing.artChannelId = a.artChannelId;
                existing.artMessageId = a.artMessageId;
                continue;
            }
            SvipeArtistFavourite f = SvipeArtistFavourite.of(a);
            f.addedAt = System.currentTimeMillis();
            local.put(a.id, f);
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
    private void pushOne(long artistId, boolean favourite) {
        if (artistId <= 0) {
            return;
        }
        if (favourite) {
            markRemovalSettled(artistId);   // re-favouriting cancels any pending removal
            SvipeMusic.favouriteArtist(account, artistId, null);
        } else {
            markRemovalPending(artistId);
            SvipeMusic.unfavouriteArtist(account, artistId, (id, isFavourite, error) -> {
                if (error == null) {
                    markRemovalSettled(id);
                }
            });
        }
    }

    private synchronized void markRemovalPending(long artistId) {
        pendingRemovals.add(artistId);
        persistPendingLocked();
    }

    private synchronized void markRemovalSettled(long artistId) {
        if (pendingRemovals.remove(artistId)) {
            persistPendingLocked();
        }
    }

    private void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.svipeArtistFavouritesChanged));
    }

    private void evictOverflow() {
        while (items.size() > MAX_FAVOURITES) {
            Iterator<Long> it = items.keySet().iterator();
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
            List<SvipeArtistFavourite> stored = SvipeArtistFavourite.deserialize(
                    prefs().getString(SvipeConfig.PREF_MUSIC_ARTIST_FAVOURITES, null));
            // Stored oldest-first, matching the map's insertion order.
            for (SvipeArtistFavourite f : stored) {
                items.put(f.artistId, f);
            }
            pendingRemovals.addAll(SvipeFavouritesSet.parseIds(
                    prefs().getString(SvipeConfig.PREF_MUSIC_ARTIST_FAV_PENDING_REMOVALS, null)));
        } catch (Exception ex) {
            FileLog.e(ex);
        }
    }

    /** Caller holds the lock. Tiny (a handful of ids), so it writes inline rather than off-thread. */
    private void persistPendingLocked() {
        try {
            prefs().edit()
                    .putString(SvipeConfig.PREF_MUSIC_ARTIST_FAV_PENDING_REMOVALS,
                            SvipeFavouritesSet.joinIds(pendingRemovals))
                    .apply();
        } catch (Exception ex) {
            FileLog.e(ex);
        }
    }

    /** Caller holds the lock: snapshot cheaply here, serialize + write off the UI thread. */
    private void persistLocked() {
        final ArrayList<SvipeArtistFavourite> snapshot = new ArrayList<>(items.values());
        Utilities.globalQueue.postRunnable(() -> {
            try {
                prefs().edit()
                        .putString(SvipeConfig.PREF_MUSIC_ARTIST_FAVOURITES,
                                SvipeArtistFavourite.serialize(snapshot))
                        .apply();
            } catch (Exception ex) {
                FileLog.e(ex);
            }
        });
    }
}
