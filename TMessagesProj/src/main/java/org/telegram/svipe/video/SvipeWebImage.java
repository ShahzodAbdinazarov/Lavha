package org.telegram.svipe.video;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Bytes of a picture scraped off {@code t.me}, kept on disk under a name that never expires.
 *
 * <h3>The problem this class exists for</h3>
 *
 * <p>A {@code cdn*.telesco.pe} URL carries a short-lived token. That is the single fact that made the
 * backend copy every poster into R2 (see {@code app/content/posters.py}): a URL you store is a dead
 * link within hours, so the server stored the bytes and served a stable address of its own.
 *
 * <p>Moving the work to the device does not make the token live longer — it makes it <b>irrelevant</b>,
 * and this class is where that happens. The tokenised URL is never persisted anywhere and is never
 * handed to a view. It is used once, immediately, to pull the bytes down into a file whose name is a
 * pure function of the thing the picture is OF:
 *
 * <pre>
 *   poster  ->  p_&lt;channelId&gt;_&lt;messageId&gt;.jpg
 *   avatar  ->  c_&lt;lowercased username&gt;.jpg
 * </pre>
 *
 * <p>That name is what the {@code ImageReceiver} is given ({@code ImageLocation.getForPath(file)}),
 * so Telegram's own image cache keys on a local path that is stable forever, instead of on an MD5 of
 * a URL that changes every time it is minted. An expired token can therefore only ever be observed
 * at one moment — inside {@link SvipeTme#download}, on a file we do not have yet — and the answer to
 * it is "read the page again for a fresh URL", once. It can never be observed as a broken image,
 * because a broken download leaves no file and the caller simply has not got the picture yet.
 *
 * <h3>Eviction</h3>
 *
 * <p>This is cache in the strict sense — every byte in it can be fetched again from a public page —
 * so it is aged and cleared like cache, three ways:
 *
 * <ul>
 *   <li><b>Budget.</b> {@link #BUDGET_BYTES} (32 MB) and {@link #MAX_AGE_MS} (7 days). A poster
 *       measures 5-16 KB and an avatar 20-60 KB, so 32 MB is some 2,000 pictures — far more than any
 *       session browses, and a hard stop long before this is worth noticing on a phone. The sweep is
 *       strict LRU on last-modified, and it runs at most once every {@link #SWEEP_INTERVAL_MS}
 *       after a write, never on the UI thread.</li>
 *   <li><b>The user's keep-media setting</b>, via {@link org.telegram.svipe.SvipeStorage}, which this
 *       directory is registered with. That is also what puts it on the Storage Usage screen: a
 *       directory this fork opens that Telegram's own scan cannot see is exactly how 2.25 GB of
 *       abandoned APKs once went unnoticed.</li>
 *   <li><b>Clear All</b>, through the same registration.</li>
 * </ul>
 *
 * <p>Losing a file is never an error. The next bind re-scrapes the page it came from.
 */
public final class SvipeWebImage {

    private SvipeWebImage() {}

    /** Registered with {@code SvipeStorage} so the size shown, the clear button and the daily age
     *  sweep can never disagree about what this directory is. */
    public static final String DIR_NAME = "svipe_webimg";

    /** ~2,000 pictures at the sizes Telegram actually serves. See the class docs. */
    static final long BUDGET_BYTES = 32L * 1024 * 1024;
    /** A poster of a week-old post is still the right frame; this is a floor on staleness, not a
     *  correctness bound. Avatars change more often, and re-reading one costs a single page. */
    static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    /** Walking the directory is cheap but not free, and a scroll writes a file every few hundred ms. */
    private static final long SWEEP_INTERVAL_MS = 5 * 60 * 1000L;

    private static volatile long lastSweepMs;

    // ---- pure name derivation (JVM-testable, no Android) ----

    /**
     * The on-disk name of one post's poster frame.
     *
     * <p>Keyed on (channel, message) and nothing else, so it is the SAME name whichever page the
     * URL was scraped from and whatever token that URL carried. This is the whole stability
     * argument; do not put anything URL-derived in here.
     */
    public static String posterName(long channelId, int messageId) {
        return "p_" + channelId + "_" + messageId + ".jpg";
    }

    /**
     * The on-disk name of one channel's avatar.
     *
     * <p>Keyed on the handle rather than the numeric id, because the handle is what the app has
     * without a resolve — having the id would mean having done the very MTProto call this whole path
     * exists to avoid. Lower-cased because t.me treats handles case-insensitively and two cases of
     * one channel must not become two files.
     */
    public static String avatarName(String username) {
        final String handle = normaliseHandle(username);
        return handle == null ? null : "c_" + handle + ".jpg";
    }

    /**
     * A public handle reduced to its canonical form, or null when it is not one.
     *
     * <p>Rejects anything outside {@code [A-Za-z0-9_]} rather than escaping it: a handle is the last
     * path segment of a URL we are about to build AND part of a filename we are about to open, and
     * both of those go wrong in interesting ways for input we did not check. Telegram's own rules are
     * stricter than this, so nothing legitimate is refused.
     */
    public static String normaliseHandle(String username) {
        if (username == null) return null;
        String s = username.trim();
        if (s.startsWith("@")) s = s.substring(1);
        if (s.isEmpty() || s.length() > 64) return null;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return null;
        }
        return s.toLowerCase(Locale.US);
    }

    /** One file as the eviction pass sees it. Plain data so the policy can be tested without a disk. */
    public static final class Entry {
        public final String name;
        public final long bytes;
        public final long modifiedMs;

        public Entry(String name, long bytes, long modifiedMs) {
            this.name = name;
            this.bytes = bytes;
            this.modifiedMs = modifiedMs;
        }
    }

    /**
     * Which files must go, given everything on disk. Pure, so the policy is provable without a disk.
     *
     * <p>Age first, then strict LRU down to the budget. Age first is deliberate: a file older than
     * {@link #MAX_AGE_MS} goes even when there is room, because keeping it does not save a fetch —
     * whatever it was a picture of will very likely be re-scraped anyway — and it would otherwise sit
     * at the bottom of the LRU forever, holding budget that a live scroll wants.
     */
    public static List<String> evictionPlan(List<Entry> entries, long budgetBytes,
                                            long maxAgeMs, long nowMs) {
        final List<String> doomed = new ArrayList<>();
        final List<Entry> keep = new ArrayList<>();
        long total = 0;
        for (Entry e : entries) {
            if (e == null) continue;
            if (nowMs - e.modifiedMs > maxAgeMs) {
                doomed.add(e.name);
            } else {
                keep.add(e);
                total += e.bytes;
            }
        }
        if (total <= budgetBytes) {
            return doomed;
        }
        // Oldest touched first — a file read during this scroll was re-stamped and survives.
        Collections.sort(keep, (a, b) -> Long.compare(a.modifiedMs, b.modifiedMs));
        for (Entry e : keep) {
            if (total <= budgetBytes) break;
            doomed.add(e.name);
            total -= e.bytes;
        }
        return doomed;
    }

    // ---- the disk (Android) ----

    /** The directory, or null when the app has no files dir yet (very early start-up). */
    public static File dir() {
        try {
            return ApplicationLoader.getFilesDirFixed(DIR_NAME);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private static File fileNamed(String name) {
        if (name == null) return null;
        final File dir = dir();
        return dir == null ? null : new File(dir, name);
    }

    /** Where a poster lives, whether or not it is there yet. */
    public static File posterFile(long channelId, int messageId) {
        return fileNamed(posterName(channelId, messageId));
    }

    /** Where an avatar lives, whether or not it is there yet. */
    public static File avatarFile(String username) {
        return fileNamed(avatarName(username));
    }

    /**
     * The file if we already hold it, else null. Never blocks and never touches the network.
     *
     * <p>Touches {@code lastModified} on a hit so the LRU reflects what is actually being looked at
     * rather than what was downloaded longest ago.
     */
    public static File hit(File f) {
        if (f == null || !f.exists() || f.length() <= 0) return null;
        try {
            f.setLastModified(System.currentTimeMillis());
        } catch (Throwable ignore) {
            // A filesystem that refuses the stamp costs us LRU accuracy, not correctness.
        }
        return f;
    }

    /**
     * Pull {@code url} into {@code dest} and return it, or null. Blocking — callers are on the pool.
     */
    public static File store(String url, File dest) {
        if (dest == null) return null;
        if (!SvipeTme.download(url, dest)) return null;
        maybeSweep();
        return dest;
    }

    /** Run the eviction pass if it is due. Cheap to call after every write. */
    static void maybeSweep() {
        final long now = System.currentTimeMillis();
        if (now - lastSweepMs < SWEEP_INTERVAL_MS) return;
        lastSweepMs = now;
        SvipeTme.submit(() -> sweep(now));
    }

    /** Apply {@link #evictionPlan} to the directory. Safe to call from anywhere off the UI thread. */
    public static void sweep(long nowMs) {
        final File dir = dir();
        final File[] files = dir == null ? null : dir.listFiles();
        if (files == null || files.length == 0) return;
        final List<Entry> entries = new ArrayList<>(files.length);
        for (File f : files) {
            if (f == null || f.isDirectory()) continue;
            entries.add(new Entry(f.getName(), f.length(), f.lastModified()));
        }
        for (String name : evictionPlan(entries, BUDGET_BYTES, MAX_AGE_MS, nowMs)) {
            try {
                new File(dir, name).delete();
            } catch (Throwable ignore) {}
        }
    }
}
