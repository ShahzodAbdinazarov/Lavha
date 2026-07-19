package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Reconciling the local favourite-singers store with the server copy.
 *
 * <p>Same reconciliation contract as {@link SvipeFavouritesMergeTest}, minus the syncable/private split
 * that only songs have. The case a naive union merge gets wrong is still the interesting one:
 * un-favouriting on device A must not be undone by device B re-uploading its stale copy, which is what
 * the {@code addedAt} vs last-sync boundary prevents.
 */
public class SvipeArtistFavouritesMergeTest {

    private static final long LAST_SYNC = 1_000L;
    private static final Set<Long> NONE = Collections.emptySet();

    private static SvipeMusic.Artist remoteArtist(long id, String name) {
        SvipeMusic.Artist a = new SvipeMusic.Artist();
        a.id = id;
        a.name = name;
        return a;
    }

    private static SvipeArtistFavourite local(long id, long addedAt) {
        SvipeArtistFavourite f = SvipeArtistFavourite.of(remoteArtist(id, "local-" + id));
        f.addedAt = addedAt;
        return f;
    }

    private static LinkedHashMap<Long, SvipeArtistFavourite> mapOf(SvipeArtistFavourite... entries) {
        LinkedHashMap<Long, SvipeArtistFavourite> m = new LinkedHashMap<>();
        for (SvipeArtistFavourite f : entries) {
            m.put(f.artistId, f);
        }
        return m;
    }

    @Test
    public void serverEntriesWeDoNotHaveAreAdopted() {
        LinkedHashMap<Long, SvipeArtistFavourite> localMap = mapOf();
        ArrayList<Long> repush = new ArrayList<>();
        SvipeArtistFavouritesSet.merge(localMap, Arrays.asList(remoteArtist(1, "A"), remoteArtist(2, "B")),
                LAST_SYNC, true, repush, NONE);

        assertEquals(2, localMap.size());
        assertTrue(localMap.containsKey(1L));
        assertEquals("A", localMap.get(1L).name);
        assertTrue(repush.isEmpty());
    }

    @Test
    public void serverIsReturnedNewestFirstSoAdoptionInsertsOldestFirst() {
        // The map is ordered oldest-first -> newest-last; list() reverses it for display. Adopting in
        // reverse keeps the server's newest-first order intact once reversed.
        LinkedHashMap<Long, SvipeArtistFavourite> localMap = mapOf();
        SvipeArtistFavouritesSet.merge(localMap,
                Arrays.asList(remoteArtist(3, "newest"), remoteArtist(2, "mid"), remoteArtist(1, "oldest")),
                LAST_SYNC, true, new ArrayList<>(), NONE);
        assertEquals(Arrays.asList(1L, 2L, 3L), new ArrayList<>(localMap.keySet()));
    }

    @Test
    public void adoptedEntriesLandAfterExistingLocalOnesSoTheStayNewest() {
        // An existing local entry must remain OLDER than anything adopted in this pass, or the display
        // order would silently reshuffle on every sync.
        LinkedHashMap<Long, SvipeArtistFavourite> localMap = mapOf(local(5, LAST_SYNC - 1));
        SvipeArtistFavouritesSet.merge(localMap, Arrays.asList(remoteArtist(5, "kept"), remoteArtist(6, "new")),
                LAST_SYNC, true, new ArrayList<>(), NONE);
        assertEquals(Arrays.asList(5L, 6L), new ArrayList<>(localMap.keySet()));
    }

    @Test
    public void locallyNewFavouriteIsPushedUpNotDeleted() {
        // Favourited offline AFTER the last sync: the server has never heard of it.
        LinkedHashMap<Long, SvipeArtistFavourite> localMap = mapOf(local(9, LAST_SYNC + 1));
        ArrayList<Long> repush = new ArrayList<>();
        SvipeArtistFavouritesSet.merge(localMap, Collections.emptyList(), LAST_SYNC, true, repush, NONE);

        assertTrue(localMap.containsKey(9L));
        assertEquals(Collections.singletonList(9L), repush);
    }

    @Test
    public void staleLocalFavouriteRemovedElsewhereIsDropped() {
        // Favourited BEFORE the last sync and absent from a complete server list => un-favourited on
        // another device. Re-uploading it here is what would resurrect it forever.
        LinkedHashMap<Long, SvipeArtistFavourite> localMap = mapOf(local(9, LAST_SYNC - 1));
        ArrayList<Long> repush = new ArrayList<>();
        SvipeArtistFavouritesSet.merge(localMap, Collections.emptyList(), LAST_SYNC, true, repush, NONE);

        assertFalse(localMap.containsKey(9L));
        assertTrue(repush.isEmpty());
    }

    @Test
    public void truncatedServerListNeverDeletes() {
        // An unseen page is indistinguishable from a removal, so deletions must be skipped entirely.
        LinkedHashMap<Long, SvipeArtistFavourite> localMap = mapOf(local(9, LAST_SYNC - 1));
        SvipeArtistFavouritesSet.merge(localMap, Collections.emptyList(), LAST_SYNC, false,
                new ArrayList<>(), NONE);
        assertTrue(localMap.containsKey(9L));
    }

    @Test
    public void entryPresentOnBothSidesIsKeptAndItsMetadataRefreshed() {
        SvipeArtistFavourite existing = local(1, LAST_SYNC - 1);
        existing.name = "stale tag";
        LinkedHashMap<Long, SvipeArtistFavourite> localMap = mapOf(existing);
        ArrayList<Long> repush = new ArrayList<>();

        SvipeMusic.Artist enriched = remoteArtist(1, "raw");
        enriched.displayName = "Real Name";     // Deezer enrichment landed since we stored it
        enriched.photoUrl = "https://e-cdn.example/1.jpg";
        enriched.songCount = 12;
        SvipeArtistFavouritesSet.merge(localMap, Collections.singletonList(enriched),
                LAST_SYNC, true, repush, NONE);

        assertEquals(1, localMap.size());
        assertEquals("Real Name", localMap.get(1L).shownName());
        assertEquals("https://e-cdn.example/1.jpg", localMap.get(1L).photoUrl);
        assertEquals(12, localMap.get(1L).songCount);
        assertTrue(repush.isEmpty());
    }

    @Test
    public void refreshKeepsTheOriginalAddedAt() {
        // addedAt drives both the display order and the push/drop split, so a refresh must not bump it.
        SvipeArtistFavourite existing = local(1, LAST_SYNC - 500);
        LinkedHashMap<Long, SvipeArtistFavourite> localMap = mapOf(existing);
        SvipeArtistFavouritesSet.merge(localMap, Collections.singletonList(remoteArtist(1, "same")),
                LAST_SYNC, true, new ArrayList<>(), NONE);
        assertEquals(LAST_SYNC - 500, localMap.get(1L).addedAt);
    }

    @Test
    public void firstEverSyncPushesEverythingLocal() {
        // lastSyncAt == 0: every local entry counts as "new", nothing is treated as removed.
        LinkedHashMap<Long, SvipeArtistFavourite> localMap = mapOf(local(1, 5), local(2, 6));
        ArrayList<Long> repush = new ArrayList<>();
        SvipeArtistFavouritesSet.merge(localMap, Collections.emptyList(), 0, true, repush, NONE);

        assertEquals(2, localMap.size());
        assertEquals(Arrays.asList(1L, 2L), repush);
    }

    @Test
    public void junkServerRowsAreIgnored() {
        LinkedHashMap<Long, SvipeArtistFavourite> localMap = mapOf();
        List<SvipeMusic.Artist> remote = Arrays.asList(null, remoteArtist(0, "no id"), remoteArtist(-5, "bad"));
        SvipeArtistFavouritesSet.merge(localMap, remote, LAST_SYNC, true, new ArrayList<>(), NONE);
        assertTrue(localMap.isEmpty());
    }

    @Test
    public void unconfirmedRemovalIsNotResurrected() {
        // Un-favourited offline: the DELETE never landed, so the server still lists it. Adopting it back
        // would silently undo what the user did — and keep undoing it on every sync.
        LinkedHashMap<Long, SvipeArtistFavourite> localMap = mapOf();
        Set<Long> pending = Collections.singleton(9L);
        SvipeArtistFavouritesSet.merge(localMap, Collections.singletonList(remoteArtist(9, "removed")),
                LAST_SYNC, true, new ArrayList<>(), pending);
        assertTrue(localMap.isEmpty());
        assertNull(localMap.get(9L));
    }

    @Test
    public void pendingRemovalDoesNotBlockOtherArtists() {
        LinkedHashMap<Long, SvipeArtistFavourite> localMap = mapOf();
        SvipeArtistFavouritesSet.merge(localMap,
                Arrays.asList(remoteArtist(9, "removed"), remoteArtist(10, "kept")),
                LAST_SYNC, true, new ArrayList<>(), Collections.singleton(9L));
        assertEquals(Collections.singletonList(10L), new ArrayList<>(localMap.keySet()));
    }

    @Test
    public void aNullPendingRemovalSetIsTolerated() {
        // applyServerList always passes a set, but merge is package-visible and must not NPE on null.
        LinkedHashMap<Long, SvipeArtistFavourite> localMap = mapOf();
        SvipeArtistFavouritesSet.merge(localMap, Collections.singletonList(remoteArtist(1, "A")),
                LAST_SYNC, true, new ArrayList<>(), null);
        assertEquals(1, localMap.size());
    }
}
