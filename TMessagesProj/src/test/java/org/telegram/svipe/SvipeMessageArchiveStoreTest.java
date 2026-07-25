package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure capacity policy of the deleted/edited message archive: version numbering and oldest-captured
 * eviction under both a count cap and a byte cap. The SQLite + filesystem shell is Android-coupled
 * and not unit-tested here.
 */
public class SvipeMessageArchiveStoreTest {

    @Test
    public void nextVersionStartsAtOne() {
        assertEquals(1, SvipeMessageArchiveStore.nextVersion(new ArrayList<>()));
        assertEquals(1, SvipeMessageArchiveStore.nextVersion(null));
    }

    @Test
    public void nextVersionIsMaxPlusOne() {
        assertEquals(4, SvipeMessageArchiveStore.nextVersion(Arrays.asList(1, 3, 2)));
        assertEquals(6, SvipeMessageArchiveStore.nextVersion(Arrays.asList(5)));
    }

    private static SvipeMessageArchiveStore.ArchiveRow row(int mid, int version, long capturedAt, long bytes) {
        return new SvipeMessageArchiveStore.ArchiveRow(mid, version, capturedAt, bytes, null);
    }

    private static Set<String> keys(List<SvipeMessageArchiveStore.ArchiveRow> rows) {
        Set<String> s = new HashSet<>();
        for (SvipeMessageArchiveStore.ArchiveRow r : rows) s.add(r.mid + ":" + r.version);
        return s;
    }

    @Test
    public void trimIsNoOpUnderBothCaps() {
        List<SvipeMessageArchiveStore.ArchiveRow> rows = Arrays.asList(
                row(1, 1, 1000L, 10),
                row(2, 1, 2000L, 10));
        assertTrue(SvipeMessageArchiveStore.planTrim(rows, 5, 1000L).isEmpty());
    }

    @Test
    public void trimEvictsOldestCapturedOverCountCap() {
        List<SvipeMessageArchiveStore.ArchiveRow> rows = Arrays.asList(
                row(1, 1, 1000L, 10), // oldest -> evicted
                row(2, 1, 2000L, 10),
                row(3, 1, 3000L, 10));
        List<SvipeMessageArchiveStore.ArchiveRow> evict = SvipeMessageArchiveStore.planTrim(rows, 2, Long.MAX_VALUE);
        assertEquals(1, evict.size());
        assertTrue(keys(evict).contains("1:1"));
    }

    @Test
    public void trimEvictsUntilUnderByteCap() {
        List<SvipeMessageArchiveStore.ArchiveRow> rows = Arrays.asList(
                row(1, 1, 1000L, 40), // oldest
                row(2, 1, 2000L, 40),
                row(3, 1, 3000L, 40)); // total 120
        // byte cap 50 -> must evict the two oldest (leaving 40 <= 50)
        List<SvipeMessageArchiveStore.ArchiveRow> evict = SvipeMessageArchiveStore.planTrim(rows, Integer.MAX_VALUE, 50L);
        assertEquals(2, evict.size());
        assertEquals(keys(Arrays.asList(row(1, 1, 1000L, 40), row(2, 1, 2000L, 40))), keys(evict));
    }

    @Test
    public void trimHonorsWhicheverCapBitesHarder() {
        List<SvipeMessageArchiveStore.ArchiveRow> rows = Arrays.asList(
                row(1, 1, 1000L, 100),
                row(2, 1, 2000L, 1),
                row(3, 1, 3000L, 1),
                row(4, 1, 4000L, 1));
        // count cap 3 would evict 1; byte cap 5 also needs the big oldest gone -> evict just mid 1 suffices
        List<SvipeMessageArchiveStore.ArchiveRow> evict = SvipeMessageArchiveStore.planTrim(rows, 3, 5L);
        assertTrue(keys(evict).contains("1:1"));
        long remaining = 0;
        Set<String> ev = keys(evict);
        for (SvipeMessageArchiveStore.ArchiveRow r : rows) {
            if (!ev.contains(r.mid + ":" + r.version)) remaining += r.bytes;
        }
        assertTrue("under byte cap after eviction", remaining <= 5L);
        assertTrue("under count cap after eviction", rows.size() - evict.size() <= 3);
    }
}
