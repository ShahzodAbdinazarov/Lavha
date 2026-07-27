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

    // ---- media-pin policy (plan §7) ----

    private static final long CAP = SvipeMessageArchiveStore.MAX_PHOTO_BYTES;

    private static int pin(boolean hasMedia, boolean isPhoto, boolean isVoiceOrRound,
                           boolean isStickerOrGif, boolean canCover, long photoBytes) {
        return SvipeMessageArchiveStore.planMediaPin(hasMedia, isPhoto, isVoiceOrRound, isStickerOrGif, canCover, photoBytes, CAP);
    }

    @Test
    public void pinNothingWhenNoMedia() {
        assertEquals(SvipeMessageArchiveStore.PIN_NONE, pin(false, false, false, false, true, 0));
    }

    @Test
    public void pinNothingForStickersAndGifs() {
        // even when a cover could be produced, stickers/GIFs are public and re-fetchable by document id
        assertEquals(SvipeMessageArchiveStore.PIN_NONE, pin(true, false, false, true, true, 0));
    }

    @Test
    public void pinFullForVoiceOrRound() {
        assertEquals(SvipeMessageArchiveStore.PIN_FULL, pin(true, false, true, false, false, 0));
    }

    @Test
    public void pinFullForPhotoUnderCap() {
        assertEquals(SvipeMessageArchiveStore.PIN_FULL, pin(true, true, false, false, false, CAP - 1));
        assertEquals(SvipeMessageArchiveStore.PIN_FULL, pin(true, true, false, false, false, CAP)); // boundary inclusive
    }

    @Test
    public void pinCoverForOversizedPhotoWhenCoverAvailable() {
        assertEquals(SvipeMessageArchiveStore.PIN_COVER, pin(true, true, false, false, true, CAP + 1));
    }

    @Test
    public void pinNothingForOversizedPhotoWithoutCover() {
        assertEquals(SvipeMessageArchiveStore.PIN_NONE, pin(true, true, false, false, false, CAP + 1));
    }

    @Test
    public void pinCoverForUncachedPhotoThatStillHasACover() {
        // full file not on disk (0 bytes) but a smaller cached size can serve as the cover
        assertEquals(SvipeMessageArchiveStore.PIN_COVER, pin(true, true, false, false, true, 0));
    }

    @Test
    public void pinCoverForVideoWithCover() {
        assertEquals(SvipeMessageArchiveStore.PIN_COVER, pin(true, false, false, false, true, 50L * 1024 * 1024));
    }

    @Test
    public void pinNothingForVideoWithoutCover() {
        assertEquals(SvipeMessageArchiveStore.PIN_NONE, pin(true, false, false, false, false, 50L * 1024 * 1024));
    }

    @Test
    public void pinCoverForGenericFileWithCover() {
        // a non-photo, non-video document (pdf/apk/music) is a "file" -> cover only
        assertEquals(SvipeMessageArchiveStore.PIN_COVER, pin(true, false, false, false, true, 8L * 1024 * 1024));
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
