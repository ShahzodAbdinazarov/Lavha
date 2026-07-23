package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merge/order of the profile-images tab: live current photos + captured photos, with the captured
 * ones that are no longer live flagged deleted, deduped by photo_id, newest set first.
 */
public class SvipeProfileImagesTest {

    private static SvipeAvatarStore.Photo stored(long id, int date, long capturedAt) {
        return new SvipeAvatarStore.Photo(id, date, capturedAt);
    }

    @Test
    public void mergesDedupsFlagsAndOrders() {
        Map<Long, Integer> current = new HashMap<>();
        current.put(10L, 300); // live, newest set
        current.put(20L, 100); // live, oldest set

        List<SvipeAvatarStore.Photo> storedList = new ArrayList<>();
        storedList.add(stored(20L, 100, 1000L)); // also live -> keep as live, no duplicate
        storedList.add(stored(30L, 200, 2000L)); // gone from live -> deleted

        List<SvipeProfileImages.Ref> refs = SvipeProfileImages.mergeRefs(current, storedList);

        assertEquals(3, refs.size());
        // newest set first: 10 (300), 30 (200), 20 (100)
        assertEquals(10L, refs.get(0).photoId);
        assertFalse(refs.get(0).deleted);
        assertEquals(30L, refs.get(1).photoId);
        assertTrue("captured photo absent from live set is deleted", refs.get(1).deleted);
        assertEquals(20L, refs.get(2).photoId);
        assertFalse("captured photo still live is not deleted", refs.get(2).deleted);
    }

    @Test
    public void allCurrentNoneDeleted() {
        Map<Long, Integer> current = new HashMap<>();
        current.put(1L, 10);
        current.put(2L, 20);
        List<SvipeProfileImages.Ref> refs = SvipeProfileImages.mergeRefs(current, new ArrayList<>());
        assertEquals(2, refs.size());
        for (SvipeProfileImages.Ref r : refs) {
            assertFalse(r.deleted);
        }
    }

    @Test
    public void allStoredGoneAllDeleted() {
        Map<Long, Integer> current = new HashMap<>();
        List<SvipeAvatarStore.Photo> storedList = new ArrayList<>();
        storedList.add(stored(5L, 50, 100L));
        storedList.add(stored(6L, 60, 200L));
        List<SvipeProfileImages.Ref> refs = SvipeProfileImages.mergeRefs(current, storedList);
        assertEquals(2, refs.size());
        for (SvipeProfileImages.Ref r : refs) {
            assertTrue(r.deleted);
        }
        assertEquals(6L, refs.get(0).photoId); // date 60 first
    }
}
