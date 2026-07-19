package org.telegram.svipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Pure decisions about the update artifacts {@link SvipeUpdater} leaves on disk.
 *
 * <p>Deliberately free of every Android dependency: the interesting logic here (which downloaded APK is
 * garbage, which one must survive, whether a finished download has been superseded) is exactly the part
 * that must be unit-tested, and this project cannot run Robolectric — deep UI classes drag in native
 * Theme/Utilities initialisation. So the rules live here as static functions over primitives and
 * strings, {@code SvipeUpdater} only supplies the real {@code File} objects and does the I/O.
 *
 * <p>Background: a real device accumulated 29 update APKs totalling ~2.25 GB, going back to versionCode
 * 259, because nothing ever deleted them.
 */
public final class SvipeUpdateFiles {

    /** Downloads are written as {@code svipe-<versionCode>.apk} (see SvipeUpdater.startDownload). */
    public static final String PREFIX = "svipe-";
    public static final String SUFFIX = ".apk";

    private SvipeUpdateFiles() {}

    /** The canonical file name for a given version code. */
    public static String fileName(int versionCode) {
        return PREFIX + versionCode + SUFFIX;
    }

    /**
     * Extract the version code from a {@code svipe-<vc>.apk} file name.
     *
     * <p>Strict and defensive on purpose: the updates directory is shared external storage, and anything
     * we cannot positively identify as one of our own downloads must be left alone rather than deleted.
     *
     * @return the version code, or -1 when the name is not one of ours (wrong prefix/suffix, empty,
     *         non-digit, or too large for an int).
     */
    public static int parseVersionCode(String fileName) {
        if (fileName == null) return -1;
        if (!fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) return -1;
        String digits = fileName.substring(PREFIX.length(), fileName.length() - SUFFIX.length());
        if (digits.isEmpty()) return -1;
        for (int i = 0; i < digits.length(); i++) {
            // Reject '+'/'-'/whitespace that Integer.parseInt would otherwise accept or mis-handle.
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') return -1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException overflow) {
            return -1; // more digits than an int holds -> not something we wrote
        }
    }

    /**
     * Is this file one of our downloads that is safe to delete?
     *
     * <p>Rules, in order:
     * <ol>
     *   <li>Not a {@code svipe-<vc>.apk} name -> never delete (foreign file).</li>
     *   <li>Listed in {@code keepNames} -> never delete. That set is the currently pending/ready APK
     *       and the file an in-flight download is writing into; deleting either would break an install
     *       the user already started (the system installer reads the file through our FileProvider) or
     *       corrupt a running download.</li>
     *   <li>Anything else -> delete. A build we already run ({@code vc <= installedVersionCode}) is
     *       provably useless, and a leftover newer one that is no longer the pending offer is an
     *       orphan from an abandoned or superseded update: the only APK worth keeping is the one the
     *       updater is currently pointing at.</li>
     * </ol>
     */
    public static boolean isDeletable(String fileName, int installedVersionCode, Collection<String> keepNames) {
        int vc = parseVersionCode(fileName);
        if (vc < 0) return false;
        if (keepNames != null && keepNames.contains(fileName)) return false;
        // Both the already-installed-or-older case (vc <= installedVersionCode) and the orphaned-newer
        // case land here; installedVersionCode stays in the signature because it documents the primary
        // rule and lets a caller reason about (or a future policy soften) the two cases separately.
        return true;
    }

    /**
     * Filter a directory listing down to the names that {@link #isDeletable} approves.
     *
     * @param fileNames the raw listing (a null/empty array — e.g. an unreadable or missing dir — yields
     *                  an empty result rather than an error).
     */
    public static List<String> selectDeletable(String[] fileNames, int installedVersionCode, Collection<String> keepNames) {
        if (fileNames == null || fileNames.length == 0) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String name : fileNames) {
            if (isDeletable(name, installedVersionCode, keepNames)) out.add(name);
        }
        return out;
    }

    /** Convenience overload for callers with no file to protect. */
    public static List<String> selectDeletable(String[] fileNames, int installedVersionCode) {
        return selectDeletable(fileNames, installedVersionCode, Collections.<String>emptyList());
    }

    /** The names that must survive a cleanup: the pending/ready APK plus any in-flight download. */
    public static List<String> keepNamesFor(String pendingFileName, int pendingVersionCode) {
        return keepNamesFor(pendingFileName, pendingVersionCode, null);
    }

    /**
     * As {@link #keepNamesFor(String, int)}, plus the file a download is writing into right now.
     *
     * <p>The in-flight name is tracked separately from the pending offer because the two can differ:
     * {@code pending} may already have been retired or replaced while the worker thread is still
     * streaming bytes into its own file, and that file is created early and filled incrementally, so it
     * looks like a plain stale APK to a sweep that only knows about the offer.
     */
    public static List<String> keepNamesFor(String pendingFileName, int pendingVersionCode, String inFlightFileName) {
        List<String> keep = new ArrayList<>(3);
        if (pendingFileName != null && !pendingFileName.isEmpty()) keep.add(pendingFileName);
        if (pendingVersionCode > 0) {
            String byVc = fileName(pendingVersionCode);
            if (!keep.contains(byVc)) keep.add(byVc);
        }
        if (inFlightFileName != null && !inFlightFileName.isEmpty() && !keep.contains(inFlightFileName)) {
            keep.add(inFlightFileName);
        }
        return keep;
    }

    /**
     * The sweep's delete list, with the concurrent-download rule applied.
     *
     * <p>While a download is active the sweep deletes <em>nothing</em>. The keep-set alone is not enough:
     * it is a snapshot, and a download that starts after the snapshot was taken but before the delete
     * loop reaches its file would have its half-written APK unlinked underneath it. The updater pairs
     * this flag with a lock so the flag cannot flip between being read here and the deletes happening —
     * this function only states the rule.
     *
     * @param downloadInFlight whether a download owns the updates directory right now.
     */
    public static List<String> selectDeletableForSweep(String[] fileNames, int installedVersionCode,
                                                       Collection<String> keepNames, boolean downloadInFlight) {
        if (downloadInFlight) return Collections.emptyList();
        return selectDeletable(fileNames, installedVersionCode, keepNames);
    }

    /**
     * Is the finished-but-not-installed download on disk something other than what the current offer
     * describes?
     *
     * <p>The invariant is version <em>identity</em>, not ordering. "Ready" means exactly one thing: the
     * bytes on disk are the build the sheet and the banner are advertising. Any other version code is
     * stale, whether it is older or newer than the offer.
     *
     * <p>Ordering ({@code offered > ready}) was the original rule and it is wrong in the case that
     * matters most. Rolling a bad release back is the standard response to a bad release: 569 ships, gets
     * withdrawn, the server offers 559 again while the user still runs 549. Under the ordering rule the
     * 569 file was kept, {@code pending} became 559, and both the {@code isReady()} install shortcut and
     * the banner tap installed the <em>withdrawn</em> 569 while the UI said 559 — and it could never
     * self-correct, because 569 is >= every later 559 offer.
     *
     * @param readyVersionCode   the version code of the verified APK on disk, or <= 0 when there is none.
     * @param offeredVersionCode the version code the server is currently offering.
     */
    public static boolean readyIsStaleFor(int readyVersionCode, int offeredVersionCode) {
        return readyVersionCode > 0 && offeredVersionCode != readyVersionCode;
    }

    /** Debug helper: the version codes of our own APKs in a listing, ascending. */
    public static List<Integer> ownVersionCodes(String[] fileNames) {
        List<Integer> out = new ArrayList<>();
        if (fileNames == null) return out;
        for (String name : fileNames) {
            int vc = parseVersionCode(name);
            if (vc >= 0) out.add(vc);
        }
        Integer[] arr = out.toArray(new Integer[0]);
        Arrays.sort(arr);
        return Arrays.asList(arr);
    }
}
