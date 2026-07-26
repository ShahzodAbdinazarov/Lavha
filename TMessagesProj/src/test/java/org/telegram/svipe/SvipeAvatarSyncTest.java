package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * The decisions the avatar-archive uploader makes before it ever touches the network: when a live
 * photo list may be trusted to prove a deletion, what counts as a change worth reporting, and which
 * of the server's wanted ids we can actually supply.
 */
public class SvipeAvatarSyncTest {

    private static SvipeAvatarStore.Photo captured(long id, int date) {
        return new SvipeAvatarStore.Photo(id, date, 1000L);
    }

    private static HashSet<Long> ids(long... values) {
        HashSet<Long> set = new HashSet<>();
        for (long v : values) set.add(v);
        return set;
    }

    @Test
    public void deletionNeedsAFullyLoadedServerList() {
        // Every page arrived: a captured id missing from this list really is gone.
        assertTrue(SvipeAvatarSync.liveSetComplete(3, 3, true, false));
        // Pages still outstanding (nulls in the model) — absence means "not fetched yet".
        assertFalse(SvipeAvatarSync.liveSetComplete(5, 2, true, false));
        // Cache-only or still loading: never enough to claim a deletion.
        assertFalse(SvipeAvatarSync.liveSetComplete(3, 3, true, true));
        assertFalse(SvipeAvatarSync.liveSetComplete(3, 3, false, false));
        assertFalse(SvipeAvatarSync.liveSetComplete(0, 0, true, false));
    }

    @Test
    public void deletedIdsAreCapturedMinusLive() {
        List<SvipeAvatarStore.Photo> captured = Arrays.asList(
                captured(10L, 300), captured(20L, 200), captured(30L, 100));
        ArrayList<Long> gone = SvipeAvatarSync.deletedIds(captured, ids(10L, 30L));
        assertEquals(1, gone.size());
        assertEquals(20L, (long) gone.get(0));
        // Nothing captured, or everything still live -> nothing to report.
        assertTrue(SvipeAvatarSync.deletedIds(null, ids(1L)).isEmpty());
        assertTrue(SvipeAvatarSync.deletedIds(captured, ids(10L, 20L, 30L)).isEmpty());
    }

    @Test
    public void signatureIgnoresOrderButNotContent() {
        List<Long> gone = Arrays.asList(7L);
        String a = SvipeAvatarSync.signature(ids(1L, 2L, 3L), gone);
        String b = SvipeAvatarSync.signature(ids(3L, 1L, 2L), gone);
        assertEquals("page order must not look like a change", a, b);
        assertFalse(a.equals(SvipeAvatarSync.signature(ids(1L, 2L), gone)));
        assertFalse(a.equals(SvipeAvatarSync.signature(ids(1L, 2L, 3L), Arrays.asList(8L))));
        assertFalse("a photo moving from live to deleted is a change",
                SvipeAvatarSync.signature(ids(1L, 2L), new ArrayList<>())
                        .equals(SvipeAvatarSync.signature(ids(1L), Arrays.asList(2L))));
    }

    @Test
    public void reopeningAnUnchangedProfileIsSilent() {
        long interval = SvipeAvatarSync.MIN_REPORT_INTERVAL_MS;
        String sig = SvipeAvatarSync.signature(ids(1L), new ArrayList<>());
        assertTrue("nothing reported yet", SvipeAvatarSync.shouldReport(null, sig, 0, 5000, interval));
        assertFalse("same profile, reopened right away",
                SvipeAvatarSync.shouldReport(sig, sig, 5000, 6000, interval));
        assertTrue("a real change reports immediately",
                SvipeAvatarSync.shouldReport(sig, "other", 5000, 6000, interval));
        assertTrue("an unchanged profile still refreshes once the window passes",
                SvipeAvatarSync.shouldReport(sig, sig, 5000, 5000 + interval, interval));
    }

    @Test
    public void onlyPhotosWeStillHoldAreUploaded() {
        List<Long> wanted = Arrays.asList(1L, 2L, 3L, 4L, 5L);
        // 2 was captured before its bytes landed; 4 was evicted — both are skipped, not retried forever.
        ArrayList<Long> picked = SvipeAvatarSync.pickUploads(wanted, ids(1L, 3L, 5L), 10);
        assertEquals(Arrays.asList(1L, 3L, 5L), picked);
    }

    @Test
    public void onlyMissingArchivedPhotosAreDownloaded() {
        // The pool offers four; two are already on this device, so only the other two are fetched.
        List<Long> archived = Arrays.asList(1L, 2L, 3L, 4L);
        assertEquals(Arrays.asList(2L, 4L), SvipeAvatarSync.pickDownloads(archived, ids(1L, 3L), 10));
        assertTrue(SvipeAvatarSync.pickDownloads(archived, ids(1L, 2L, 3L, 4L), 10).isEmpty());
        assertEquals(2, SvipeAvatarSync.pickDownloads(archived, ids(), 2).size());
        assertTrue(SvipeAvatarSync.pickDownloads(null, ids(), 5).isEmpty());
    }

    @Test
    public void uploadsAreCappedPerProfileView() {
        List<Long> wanted = Arrays.asList(1L, 2L, 3L, 4L, 5L);
        ArrayList<Long> picked = SvipeAvatarSync.pickUploads(wanted, ids(1L, 2L, 3L, 4L, 5L),
                SvipeAvatarSync.MAX_UPLOADS_PER_VISIT);
        assertEquals(SvipeAvatarSync.MAX_UPLOADS_PER_VISIT, picked.size());
        assertEquals(Arrays.asList(1L, 2L, 3L), picked);
        assertTrue(SvipeAvatarSync.pickUploads(null, ids(1L), 3).isEmpty());
    }
}
