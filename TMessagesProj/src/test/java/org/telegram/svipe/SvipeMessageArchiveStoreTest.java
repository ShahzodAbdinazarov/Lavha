package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Pure policy of the deleted/edited message archive: version numbering, which self-initiated deletes
 * are kept, which kinds are shared, and media pinning. The SQLite + filesystem shell is
 * Android-coupled and not unit-tested here.
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

    // ---- what a self-initiated delete keeps ----

    @Test
    public void clearingHistoryIsKeptForEveryChatKind() {
        assertTrue(SvipeMessageArchiveStore.keepsDialogDelete(42L, 1, false));
        assertTrue(SvipeMessageArchiveStore.keepsDialogDelete(-42L, 1, false));
    }

    @Test
    public void deletingAPrivateChatIsKeptButLeavingAGroupIsNot() {
        assertTrue(SvipeMessageArchiveStore.keepsDialogDelete(42L, 0, false));
        assertFalse("delete on a group/channel = leaving it", SvipeMessageArchiveStore.keepsDialogDelete(-42L, 0, false));
    }

    @Test
    public void secretChatsAndHousekeepingDeletesAreNeverKept() {
        assertFalse(SvipeMessageArchiveStore.keepsDialogDelete(42L, 1, true));
        assertFalse(SvipeMessageArchiveStore.keepsDialogDelete(42L, 0, true));
        assertFalse("2 = channel cache clear", SvipeMessageArchiveStore.keepsDialogDelete(-42L, 2, false));
        assertFalse("3 = drop an empty dialog", SvipeMessageArchiveStore.keepsDialogDelete(42L, 3, false));
    }

    @Test
    public void onlyRealDeletionsAreShared() {
        assertTrue(SvipeMessageArchiveStore.isShared(SvipeMessageArchiveStore.KIND_DELETED));
        assertTrue(SvipeMessageArchiveStore.isShared(SvipeMessageArchiveStore.KIND_EDITED_PRIOR));
        assertFalse("removed only on my side — the partner still has it", SvipeMessageArchiveStore.isShared(SvipeMessageArchiveStore.KIND_REMOVED_BY_ME));
        assertFalse(SvipeMessageArchiveStore.isShared(SvipeMessageArchiveStore.KIND_EVENT));
        assertFalse(SvipeMessageArchiveStore.isShared(SvipeMessageArchiveStore.KIND_LIVE));
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

    // ---- synthetic mid for synced (server-pulled) items ----

    @Test
    public void syntheticMidIsAlwaysNegativeAndDeterministic() {
        String key = "0123456789abcdef" + "0".repeat(48);
        int a = SvipeMessageArchiveStore.syntheticMid(key);
        assertTrue("negative so it never collides with a real positive mid", a < 0);
        assertEquals("deterministic, so re-fetch overwrites not duplicates", a,
                SvipeMessageArchiveStore.syntheticMid(key));
    }

    @Test
    public void syntheticMidDiffersByKeyAndHandlesGarbage() {
        int a = SvipeMessageArchiveStore.syntheticMid("aaaaaaaa" + "0".repeat(56));
        int b = SvipeMessageArchiveStore.syntheticMid("bbbbbbbb" + "0".repeat(56));
        assertTrue(a != b);
        assertEquals(-1, SvipeMessageArchiveStore.syntheticMid(null));
        assertEquals(-1, SvipeMessageArchiveStore.syntheticMid("short"));
        // The all-zero prefix would map to 0; it must be forced to a non-zero negative id.
        assertTrue(SvipeMessageArchiveStore.syntheticMid("0".repeat(64)) < 0);
    }
}
