package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Reconciling the local favourites store with the server copy.
 *
 * <p>The interesting case is the one a naive union merge gets wrong: un-favouriting on device A must
 * not be undone by device B re-uploading its stale copy. The split on {@code addedAt} vs the last sync
 * time is what prevents that, so most of these tests pin exactly that boundary.
 */
public class SvipeFavouritesMergeTest {

    private static final long LAST_SYNC = 1_000L;
    private static final Set<Long> NONE = Collections.emptySet();

    private static SvipeMusic.Song song(long id, String title) {
        SvipeMusic.Song s = new SvipeMusic.Song();
        s.id = id;
        s.title = title;
        return s;
    }

    private static SvipeFavourite local(SvipeFavKey key, long addedAt) {
        SvipeFavourite f = SvipeFavourite.of(key);
        f.addedAt = addedAt;
        return f;
    }

    private static LinkedHashMap<String, SvipeFavourite> mapOf(SvipeFavourite... entries) {
        LinkedHashMap<String, SvipeFavourite> m = new LinkedHashMap<>();
        for (SvipeFavourite f : entries) {
            m.put(f.key, f);
        }
        return m;
    }

    @Test
    public void serverEntriesWeDoNotHaveAreAdopted() {
        LinkedHashMap<String, SvipeFavourite> localMap = mapOf();
        ArrayList<Long> repush = new ArrayList<>();
        SvipeFavouritesSet.merge(localMap, Arrays.asList(song(1, "A"), song(2, "B")), LAST_SYNC, true, repush, NONE);

        assertEquals(2, localMap.size());
        assertTrue(localMap.containsKey("song:1"));
        assertEquals("A", localMap.get("song:1").title);
        assertTrue(repush.isEmpty());
    }

    @Test
    public void serverIsReturnedNewestFirstSoAdoptionInsertsOldestFirst() {
        // The map is ordered oldest-last-inserted -> newest; list() reverses it for display. Adopting in
        // reverse keeps the server's newest-first order intact once reversed.
        LinkedHashMap<String, SvipeFavourite> localMap = mapOf();
        SvipeFavouritesSet.merge(localMap, Arrays.asList(song(3, "newest"), song(2, "mid"), song(1, "oldest")),
                LAST_SYNC, true, new ArrayList<>(), NONE);
        assertEquals(Arrays.asList("song:1", "song:2", "song:3"), new ArrayList<>(localMap.keySet()));
    }

    @Test
    public void locallyNewFavouriteIsPushedUpNotDeleted() {
        // Favourited offline AFTER the last sync: the server has never heard of it.
        LinkedHashMap<String, SvipeFavourite> localMap = mapOf(local(SvipeFavKey.song(9), LAST_SYNC + 1));
        ArrayList<Long> repush = new ArrayList<>();
        SvipeFavouritesSet.merge(localMap, Collections.emptyList(), LAST_SYNC, true, repush, NONE);

        assertTrue(localMap.containsKey("song:9"));
        assertEquals(Collections.singletonList(9L), repush);
    }

    @Test
    public void staleLocalFavouriteRemovedElsewhereIsDropped() {
        // Favourited BEFORE the last sync and absent from a complete server list => un-favourited on
        // another device. Re-uploading it here is what would resurrect it forever.
        LinkedHashMap<String, SvipeFavourite> localMap = mapOf(local(SvipeFavKey.song(9), LAST_SYNC - 1));
        ArrayList<Long> repush = new ArrayList<>();
        SvipeFavouritesSet.merge(localMap, Collections.emptyList(), LAST_SYNC, true, repush, NONE);

        assertFalse(localMap.containsKey("song:9"));
        assertTrue(repush.isEmpty());
    }

    @Test
    public void truncatedServerListNeverDeletes() {
        // An unseen page is indistinguishable from a removal, so deletions must be skipped entirely.
        LinkedHashMap<String, SvipeFavourite> localMap = mapOf(local(SvipeFavKey.song(9), LAST_SYNC - 1));
        SvipeFavouritesSet.merge(localMap, Collections.emptyList(), LAST_SYNC, false, new ArrayList<>(), NONE);
        assertTrue(localMap.containsKey("song:9"));
    }

    @Test
    public void privateFavouritesAreNeverDeletedNorPushed() {
        // The privacy invariant: msg:/doc: entries live on the device only and the server's view of the
        // world must not be able to touch them.
        LinkedHashMap<String, SvipeFavourite> localMap = mapOf(
                local(SvipeFavKey.document(7), LAST_SYNC - 1),
                local(SvipeFavKey.message(1001234L, 42), LAST_SYNC - 1));
        ArrayList<Long> repush = new ArrayList<>();
        SvipeFavouritesSet.merge(localMap, Collections.emptyList(), LAST_SYNC, true, repush, NONE);

        assertEquals(2, localMap.size());
        assertTrue(repush.isEmpty());
    }

    @Test
    public void entryPresentOnBothSidesIsKeptAndItsMetadataRefreshed() {
        SvipeFavourite existing = local(SvipeFavKey.song(1), LAST_SYNC - 1);
        existing.title = "stale tag";
        LinkedHashMap<String, SvipeFavourite> localMap = mapOf(existing);
        ArrayList<Long> repush = new ArrayList<>();

        SvipeMusic.Song enriched = song(1, "raw");
        enriched.displayTitle = "Real Title";       // Deezer enrichment landed since we stored it
        SvipeFavouritesSet.merge(localMap, Collections.singletonList(enriched), LAST_SYNC, true, repush, NONE);

        assertEquals(1, localMap.size());
        assertEquals("Real Title", localMap.get("song:1").title);
        assertTrue(repush.isEmpty());
    }

    @Test
    public void firstEverSyncPushesEverythingLocal() {
        // lastSyncAt == 0: every local entry counts as "new", nothing is treated as removed.
        LinkedHashMap<String, SvipeFavourite> localMap = mapOf(
                local(SvipeFavKey.song(1), 5), local(SvipeFavKey.song(2), 6));
        ArrayList<Long> repush = new ArrayList<>();
        SvipeFavouritesSet.merge(localMap, Collections.emptyList(), 0, true, repush, NONE);

        assertEquals(2, localMap.size());
        assertEquals(Arrays.asList(1L, 2L), repush);
    }

    @Test
    public void junkServerRowsAreIgnored() {
        LinkedHashMap<String, SvipeFavourite> localMap = mapOf();
        List<SvipeMusic.Song> remote = Arrays.asList(null, song(0, "no id"), song(-5, "deezer placeholder"));
        SvipeFavouritesSet.merge(localMap, remote, LAST_SYNC, true, new ArrayList<>(), NONE);
        assertTrue(localMap.isEmpty());
    }

    @Test
    public void unconfirmedRemovalIsNotResurrected() {
        // Un-favourited offline: the DELETE never landed, so the server still lists it. Adopting it back
        // would silently undo what the user did — and keep undoing it on every sync.
        LinkedHashMap<String, SvipeFavourite> localMap = mapOf();
        Set<Long> pending = Collections.singleton(9L);
        SvipeFavouritesSet.merge(localMap, Collections.singletonList(song(9, "removed")),
                LAST_SYNC, true, new ArrayList<>(), pending);
        assertTrue(localMap.isEmpty());
    }

    @Test
    public void pendingRemovalDoesNotBlockOtherSongs() {
        LinkedHashMap<String, SvipeFavourite> localMap = mapOf();
        SvipeFavouritesSet.merge(localMap, Arrays.asList(song(9, "removed"), song(10, "kept")),
                LAST_SYNC, true, new ArrayList<>(), Collections.singleton(9L));
        assertEquals(Collections.singletonList("song:10"), new ArrayList<>(localMap.keySet()));
    }

    @Test
    public void pendingRemovalIdsRoundTripThroughPrefs() {
        assertEquals("1,2,3", SvipeFavouritesSet.joinIds(Arrays.asList(1L, 2L, 3L)));
        assertEquals(Arrays.asList(1L, 2L, 3L), SvipeFavouritesSet.parseIds("1,2,3"));
        assertEquals("", SvipeFavouritesSet.joinIds(Collections.emptyList()));
        // A corrupt or partial value must not take the whole load down.
        assertTrue(SvipeFavouritesSet.parseIds(null).isEmpty());
        assertTrue(SvipeFavouritesSet.parseIds("").isEmpty());
        assertEquals(Collections.singletonList(4L), SvipeFavouritesSet.parseIds("x,,-1,0,4"));
    }
}
