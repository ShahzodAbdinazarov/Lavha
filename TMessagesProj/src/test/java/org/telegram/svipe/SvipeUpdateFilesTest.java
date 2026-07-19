package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure-JVM coverage for the update-artifact rules ({@link SvipeUpdateFiles}).
 *
 * <p>Motivation: a real device accumulated 29 update APKs / ~2.25 GB because nothing ever deleted them.
 * These tests pin the two decisions that make the cleanup safe — which names we recognise as our own,
 * and which of those may be removed — plus the "downloaded build has been superseded" rule that keeps
 * the banner honest. No Android classes are involved (this project cannot run Robolectric).
 */
public class SvipeUpdateFilesTest {

    private static List<String> keep(String... names) {
        return Arrays.asList(names);
    }

    // ---- parseVersionCode ----

    @Test
    public void parsesOurOwnNames() {
        assertEquals(549, SvipeUpdateFiles.parseVersionCode("svipe-549.apk"));
        assertEquals(259, SvipeUpdateFiles.parseVersionCode("svipe-259.apk"));
        assertEquals(0, SvipeUpdateFiles.parseVersionCode("svipe-0.apk"));
    }

    @Test
    public void rejectsMalformedNames() {
        // Anything we cannot positively identify must be left alone rather than deleted.
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode(null));
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode(""));
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode("svipe-.apk"));
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode("svipe-abc.apk"));
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode("svipe-12a.apk"));
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode("svipe-549.apk.part"));
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode("svipe549.apk"));
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode("telegram-549.apk"));
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode("549.apk"));
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode("svipe-549.APK")); // case-sensitive on purpose
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode("important-user-file.zip"));
    }

    @Test
    public void rejectsSignedAndOversizedNumbers() {
        // Integer.parseInt would happily accept "+549"/"-549"; the digit scan must not.
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode("svipe-+549.apk"));
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode("svipe--549.apk"));
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode("svipe- 549.apk"));
        assertEquals(-1, SvipeUpdateFiles.parseVersionCode("svipe-99999999999999.apk")); // overflows int
    }

    @Test
    public void fileNameRoundTrips() {
        assertEquals("svipe-549.apk", SvipeUpdateFiles.fileName(549));
        assertEquals(549, SvipeUpdateFiles.parseVersionCode(SvipeUpdateFiles.fileName(549)));
    }

    // ---- isDeletable ----

    @Test
    public void olderThanInstalledIsDeletable() {
        assertTrue(SvipeUpdateFiles.isDeletable("svipe-259.apk", 549, Collections.<String>emptyList()));
        assertTrue(SvipeUpdateFiles.isDeletable("svipe-539.apk", 549, Collections.<String>emptyList()));
    }

    @Test
    public void theInstalledVersionItselfIsDeletable() {
        // Equal version code: we are already running that build, the APK is provably useless.
        assertTrue(SvipeUpdateFiles.isDeletable("svipe-549.apk", 549, Collections.<String>emptyList()));
    }

    @Test
    public void newerOrphanIsDeletable() {
        // Newer than installed but not the pending offer -> abandoned/superseded download.
        assertTrue(SvipeUpdateFiles.isDeletable("svipe-559.apk", 549, Collections.<String>emptyList()));
    }

    @Test
    public void pendingFileIsNeverDeleted() {
        List<String> keep = keep("svipe-559.apk");
        assertFalse(SvipeUpdateFiles.isDeletable("svipe-559.apk", 549, keep));
        // ...but its neighbours still are.
        assertTrue(SvipeUpdateFiles.isDeletable("svipe-549.apk", 549, keep));
        assertTrue(SvipeUpdateFiles.isDeletable("svipe-569.apk", 549, keep));
    }

    @Test
    public void pendingFileIsKeptEvenWhenOlderThanInstalled() {
        // Defensive: the keep-list wins over every staleness rule, because deleting a file the system
        // installer is currently reading through our FileProvider breaks a running install.
        assertFalse(SvipeUpdateFiles.isDeletable("svipe-259.apk", 549, keep("svipe-259.apk")));
    }

    @Test
    public void foreignFilesAreNeverDeletable() {
        assertFalse(SvipeUpdateFiles.isDeletable("notes.txt", 549, Collections.<String>emptyList()));
        assertFalse(SvipeUpdateFiles.isDeletable("svipe-beta.apk", 549, Collections.<String>emptyList()));
        assertFalse(SvipeUpdateFiles.isDeletable(null, 549, Collections.<String>emptyList()));
        assertFalse(SvipeUpdateFiles.isDeletable("svipe-549.apk.tmp", 549, Collections.<String>emptyList()));
    }

    @Test
    public void nullKeepListIsTolerated() {
        assertTrue(SvipeUpdateFiles.isDeletable("svipe-259.apk", 549, null));
        assertFalse(SvipeUpdateFiles.isDeletable("readme.md", 549, null));
    }

    // ---- selectDeletable ----

    @Test
    public void emptyDirectoryYieldsNothing() {
        assertTrue(SvipeUpdateFiles.selectDeletable(new String[0], 549).isEmpty());
        assertTrue(SvipeUpdateFiles.selectDeletable(null, 549).isEmpty()); // unreadable dir -> list() == null
    }

    @Test
    public void sweepsTheRealWorldBacklogButSparesThePendingApk() {
        // Shape of the device that prompted this fix: a long tail of old builds, one live offer, and a
        // foreign file that must survive.
        String[] listing = {
                "svipe-259.apk", "svipe-269.apk", "svipe-529.apk", "svipe-539.apk",
                "svipe-549.apk",           // == installed
                "svipe-559.apk",           // the pending download
                "svipe-partial.apk",       // malformed -> untouched
                "somebody-elses.apk",      // foreign -> untouched
        };
        List<String> doomed = SvipeUpdateFiles.selectDeletable(listing, 549, keep("svipe-559.apk"));

        assertEquals(5, doomed.size());
        assertTrue(doomed.containsAll(Arrays.asList(
                "svipe-259.apk", "svipe-269.apk", "svipe-529.apk", "svipe-539.apk", "svipe-549.apk")));
        assertFalse(doomed.contains("svipe-559.apk"));
        assertFalse(doomed.contains("svipe-partial.apk"));
        assertFalse(doomed.contains("somebody-elses.apk"));
    }

    @Test
    public void withNoPendingEverythingOfOursGoes() {
        String[] listing = {"svipe-259.apk", "svipe-549.apk", "svipe-559.apk", "keep-me.bin"};
        List<String> doomed = SvipeUpdateFiles.selectDeletable(listing, 549);
        assertEquals(3, doomed.size());
        assertFalse(doomed.contains("keep-me.bin"));
    }

    // ---- keepNamesFor ----

    @Test
    public void keepNamesCoverBothThePathAndTheVersionCode() {
        // Mid-download the file is not persisted yet, so the version code is the only handle we have.
        List<String> keep = SvipeUpdateFiles.keepNamesFor(null, 559);
        assertEquals(Collections.singletonList("svipe-559.apk"), keep);

        keep = SvipeUpdateFiles.keepNamesFor("svipe-559.apk", 559);
        assertEquals(1, keep.size()); // same file, not duplicated

        keep = SvipeUpdateFiles.keepNamesFor("svipe-559.apk", 569);
        assertEquals(2, keep.size()); // persisted 559 + in-flight 569 both protected
        assertTrue(keep.contains("svipe-559.apk"));
        assertTrue(keep.contains("svipe-569.apk"));
    }

    @Test
    public void keepNamesEmptyWhenNothingIsPending() {
        assertTrue(SvipeUpdateFiles.keepNamesFor(null, 0).isEmpty());
        assertTrue(SvipeUpdateFiles.keepNamesFor("", 0).isEmpty());
    }

    // ---- keepNamesFor: the in-flight download ----

    @Test
    public void theInFlightDownloadIsKeptEvenWithNoOfferAtAll() {
        // The offer can be retired (a "no update" response) while the worker thread is still streaming
        // bytes into its own file; the file must still survive.
        List<String> keep = SvipeUpdateFiles.keepNamesFor(null, 0, "svipe-569.apk");
        assertEquals(Collections.singletonList("svipe-569.apk"), keep);
        assertFalse(SvipeUpdateFiles.isDeletable("svipe-569.apk", 549, keep));
    }

    @Test
    public void theInFlightDownloadIsNotDuplicatedWhenItIsAlsoThePendingOffer() {
        List<String> keep = SvipeUpdateFiles.keepNamesFor("svipe-559.apk", 559, "svipe-559.apk");
        assertEquals(1, keep.size());
    }

    @Test
    public void persistedReadyAndInFlightAreAllProtectedAtOnce() {
        // Installed 549, 559 downloaded and persisted, 569 being fetched right now: three live files.
        List<String> keep = SvipeUpdateFiles.keepNamesFor("svipe-559.apk", 559, "svipe-569.apk");
        assertEquals(2, keep.size());
        assertTrue(keep.contains("svipe-559.apk"));
        assertTrue(keep.contains("svipe-569.apk"));

        String[] listing = {"svipe-259.apk", "svipe-549.apk", "svipe-559.apk", "svipe-569.apk"};
        List<String> doomed = SvipeUpdateFiles.selectDeletable(listing, 549, keep);
        assertEquals(Arrays.asList("svipe-259.apk", "svipe-549.apk"), doomed);
    }

    @Test
    public void blankInFlightNameIsIgnored() {
        assertTrue(SvipeUpdateFiles.keepNamesFor(null, 0, null).isEmpty());
        assertTrue(SvipeUpdateFiles.keepNamesFor(null, 0, "").isEmpty());
    }

    @Test
    public void threeArgKeepNamesMatchesTheTwoArgOverloadWhenNothingIsInFlight() {
        assertEquals(SvipeUpdateFiles.keepNamesFor("svipe-559.apk", 569),
                SvipeUpdateFiles.keepNamesFor("svipe-559.apk", 569, null));
    }

    // ---- selectDeletableForSweep: never delete while a download runs ----

    @Test
    public void aRunningDownloadSuspendsTheSweepEntirely() {
        // The keep-set is a snapshot; a download that starts after it was taken would not be in it. The
        // rule is therefore all-or-nothing rather than "delete everything the snapshot allows".
        String[] listing = {"svipe-259.apk", "svipe-269.apk", "svipe-549.apk"};
        assertTrue(SvipeUpdateFiles
                .selectDeletableForSweep(listing, 549, Collections.<String>emptyList(), true)
                .isEmpty());
    }

    @Test
    public void withNoDownloadTheSweepBehavesExactlyAsBefore() {
        String[] listing = {"svipe-259.apk", "svipe-559.apk", "notes.txt"};
        List<String> keep = keep("svipe-559.apk");
        assertEquals(SvipeUpdateFiles.selectDeletable(listing, 549, keep),
                SvipeUpdateFiles.selectDeletableForSweep(listing, 549, keep, false));
        assertEquals(Collections.singletonList("svipe-259.apk"),
                SvipeUpdateFiles.selectDeletableForSweep(listing, 549, keep, false));
    }

    @Test
    public void theBacklogIsStillDrainedOnTheNextSweepAfterTheDownloadEnds() {
        // Suspending is safe precisely because it is not permanent: clearPending() and the next launch
        // both re-run the sweep.
        String[] listing = {"svipe-259.apk", "svipe-269.apk", "svipe-549.apk", "svipe-569.apk"};
        assertTrue(SvipeUpdateFiles.selectDeletableForSweep(listing, 549, keep("svipe-569.apk"), true).isEmpty());
        assertEquals(Arrays.asList("svipe-259.apk", "svipe-269.apk", "svipe-549.apk"),
                SvipeUpdateFiles.selectDeletableForSweep(listing, 549, keep("svipe-569.apk"), false));
    }

    // ---- readyIsStaleFor (identity, not ordering) ----

    @Test
    public void readyDownloadIsStaleForANewerOffer() {
        // installed 549, downloaded 559, server now offers 569 -> the 559 file must stop being "ready".
        assertTrue(SvipeUpdateFiles.readyIsStaleFor(559, 569));
    }

    @Test
    public void readyDownloadIsStaleForAnOlderOfferAfterARollback() {
        // Replaces the former readyDownloadSurvivesSameOrOlderOffer, which asserted
        // assertFalse(readySupersededBy(559, 549)) and thereby locked in the bug: 569 ships, is withdrawn,
        // the server offers 559 again while the user still runs 549. Under the old ordering rule the
        // withdrawn 569 file stayed "ready" and got installed while the UI advertised 559 — permanently,
        // since 569 outranks every later 559 offer. Only equality may keep the file.
        assertTrue(SvipeUpdateFiles.readyIsStaleFor(569, 559));
        assertTrue(SvipeUpdateFiles.readyIsStaleFor(559, 549));
    }

    @Test
    public void readyDownloadSurvivesOnlyTheOfferThatDescribesIt() {
        assertFalse(SvipeUpdateFiles.readyIsStaleFor(559, 559));
        assertFalse(SvipeUpdateFiles.readyIsStaleFor(1, 1));
    }

    @Test
    public void nothingReadyIsNeverStale() {
        assertFalse(SvipeUpdateFiles.readyIsStaleFor(0, 569));
        assertFalse(SvipeUpdateFiles.readyIsStaleFor(-1, 569));
        assertFalse(SvipeUpdateFiles.readyIsStaleFor(0, 0));
    }

    @Test
    public void aReadyFileWithNoOfferAtAllIsStale() {
        // offered 0 = there is no offer describing this file, so it is not installable state.
        assertTrue(SvipeUpdateFiles.readyIsStaleFor(559, 0));
    }

    // ---- ownVersionCodes ----

    @Test
    public void ownVersionCodesIgnoresForeignNamesAndSorts() {
        List<Integer> vcs = SvipeUpdateFiles.ownVersionCodes(
                new String[]{"svipe-559.apk", "readme.txt", "svipe-259.apk", "svipe-x.apk"});
        assertEquals(Arrays.asList(259, 559), vcs);
        assertTrue(SvipeUpdateFiles.ownVersionCodes(null).isEmpty());
    }
}
