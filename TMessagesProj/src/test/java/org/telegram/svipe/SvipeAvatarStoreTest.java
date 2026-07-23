package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure list-logic of the avatar ledger: dedup on photo_id, "newest set first" ordering, and
 * oldest-captured eviction over the cap. The prefs/file shell is Android-coupled and not unit-tested.
 */
public class SvipeAvatarStoreTest {

    @Test
    public void upsertAddsOnceAndReportsNovelty() {
        List<SvipeAvatarStore.Photo> list = new ArrayList<>();
        assertTrue("first sighting is new", SvipeAvatarStore.upsert(list, 100L, 10, 1000L));
        assertFalse("same photo_id is not new", SvipeAvatarStore.upsert(list, 100L, 10, 2000L));
        assertTrue("different photo_id is new", SvipeAvatarStore.upsert(list, 200L, 20, 3000L));
        assertEquals(2, list.size());
    }

    @Test
    public void sortIsNewestSetFirst() {
        List<SvipeAvatarStore.Photo> list = new ArrayList<>();
        SvipeAvatarStore.upsert(list, 1L, 100, 5000L); // oldest set
        SvipeAvatarStore.upsert(list, 2L, 300, 5000L); // newest set
        SvipeAvatarStore.upsert(list, 3L, 200, 5000L); // middle
        SvipeAvatarStore.sortBySetOrder(list);
        assertEquals(2L, list.get(0).photoId); // date 300
        assertEquals(3L, list.get(1).photoId); // date 200
        assertEquals(1L, list.get(2).photoId); // date 100
    }

    @Test
    public void sortTieBreaksDeterministicallyOnEqualDate() {
        List<SvipeAvatarStore.Photo> list = new ArrayList<>();
        SvipeAvatarStore.upsert(list, 1L, 100, 1000L);
        SvipeAvatarStore.upsert(list, 2L, 100, 3000L); // captured later -> first
        SvipeAvatarStore.upsert(list, 3L, 100, 2000L);
        SvipeAvatarStore.sortBySetOrder(list);
        assertEquals(2L, list.get(0).photoId);
        assertEquals(3L, list.get(1).photoId);
        assertEquals(1L, list.get(2).photoId);
    }

    @Test
    public void trimDropsOldestCapturedOverCap() {
        List<SvipeAvatarStore.Photo> list = new ArrayList<>();
        SvipeAvatarStore.upsert(list, 1L, 10, 1000L); // oldest captured -> evicted
        SvipeAvatarStore.upsert(list, 2L, 20, 2000L);
        SvipeAvatarStore.upsert(list, 3L, 30, 3000L);
        SvipeAvatarStore.trim(list, 2);
        assertEquals(2, list.size());
        for (SvipeAvatarStore.Photo p : list) {
            assertFalse("oldest-captured entry evicted", p.photoId == 1L);
        }
    }

    @Test
    public void trimIsNoOpUnderCap() {
        List<SvipeAvatarStore.Photo> list = new ArrayList<>();
        SvipeAvatarStore.upsert(list, 1L, 10, 1000L);
        SvipeAvatarStore.upsert(list, 2L, 20, 2000L);
        SvipeAvatarStore.trim(list, 5);
        assertEquals(2, list.size());
    }
}
