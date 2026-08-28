package org.telegram.svipe;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AutoDeleteMediaTask;
import org.telegram.messenger.CacheByChatsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.util.ArrayList;

/**
 * Every directory Svipe keeps bytes in that Telegram's own storage scan cannot see.
 *
 * <p>Telegram measures, ages out and clears exactly the directories it created itself
 * ({@code ImageLoader.createMediaPaths()} — image, video, document, audio, cache…). Everything Svipe
 * downloads through {@code FileLoader} lands in those and is therefore already counted, aged and
 * cleared with the rest. What is NOT is the storage this fork opened on its own, which until now was
 * invisible on the Storage Usage screen, survived Clear All, and was never touched by the daily
 * keep-media sweep. A real device carried 29 abandoned update APKs — ~2.25 GB — before anyone noticed.
 *
 * <p>This class is the single list of those directories, so the screen that SHOWS the size, the
 * button that CLEARS it and the daily job that AGES it can never disagree about what "Svipe's data"
 * means.
 *
 * <p><b>What is deliberately not here: {@code svipe_msg_archive}.</b> That is the captured media of
 * deleted and edited messages, and it is the only copy in existence — the message it came from is
 * gone. Cache is what can be fetched again; that is not cache, and putting it behind a keep-media age
 * limit would quietly destroy the feature that keeps it. It has its own bounded budget instead
 * (SvipeMessageArchiveStore: 2000 rows / 32 MB per dialog).
 */
public final class SvipeStorage {

    private SvipeStorage() {
    }

    /** Archived profile photos (SvipeAvatarStore). Re-fetchable: the server keeps the archive too. */
    private static final String AVATARS_DIR = "svipe_avatars";

    /**
     * Channel avatars and video posters scraped from public t.me pages
     * ({@link org.telegram.svipe.video.SvipeWebImage}). Pure cache — every byte can be fetched again
     * from a public page — so it belongs here rather than beside {@code svipe_msg_archive}, and it
     * keeps its own 32 MB / 7-day budget on top of whatever the keep-media setting says.
     */
    private static final String WEB_IMAGES_DIR =
            org.telegram.svipe.video.SvipeWebImage.DIR_NAME;

    /** The directories this class owns. Missing ones are included — they simply measure zero. */
    public static File[] dirs() {
        final ArrayList<File> out = new ArrayList<>(3);
        for (String name : new String[]{AVATARS_DIR, WEB_IMAGES_DIR}) {
            try {
                final File dir = ApplicationLoader.getFilesDirFixed(name);
                if (dir != null) {
                    out.add(dir);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        final File updates = SvipeUpdater.updatesDir();
        if (updates != null) {
            out.add(updates);
        }
        return out.toArray(new File[0]);
    }

    /**
     * Bytes on disk, measured the same way Telegram measures its own categories — the native
     * directory walk, so a Svipe row is comparable with the rows above it rather than an estimate.
     */
    public static long size() {
        long total = 0;
        for (File dir : dirs()) {
            try {
                if (dir.exists()) {
                    total += Utilities.getDirSize(dir.getAbsolutePath(), 0, true);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        return total;
    }

    /**
     * Clear All, for our half of the disk. Called from CacheControlActivity's own clear pass, on its
     * background queue, so it must block rather than schedule.
     */
    public static void clear() {
        for (String name : new String[]{AVATARS_DIR, WEB_IMAGES_DIR}) {
            try {
                final File dir = ApplicationLoader.getFilesDirFixed(name);
                if (dir != null) {
                    deleteContents(dir);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        // The updates directory has a live writer, so it is emptied by its owner rather than from
        // here: an in-flight download's file is written incrementally and unlinking it mid-write
        // breaks the update (SvipeUpdater's SWEEP_LOCK exists for exactly that race).
        SvipeUpdater.clearDownloadedApks();
    }

    /**
     * Apply the user's "keep media" age limit to our directories, so the setting means the same
     * thing everywhere in the app.
     *
     * <p>Our files belong to no dialog, so there is no per-chat exception to look up and no dialog
     * type to read: they are all channel content, and the CHANNEL limit is what they get. That is
     * also the strictest of the defaults (one week), which is the right way round for a cache.
     *
     * @param nowSeconds unix seconds, passed in so one sweep uses one clock
     */
    public static void enforceKeepMedia(int nowSeconds) {
        // Read straight off the shared preference, the way AutoDeleteMediaTask does: the setting is
        // global (SharedConfig), our files belong to no account, and going through a
        // CacheByChatsController would mean picking one arbitrarily.
        final int type = CacheByChatsController.KEEP_MEDIA_TYPE_CHANNEL;
        final int keep = SharedConfig.getPreferences().getInt(
                "keep_media_type_" + type, CacheByChatsController.getDefault(type));
        final long seconds = CacheByChatsController.getDaysInSeconds(keep);
        if (seconds == Long.MAX_VALUE) {
            return;                       // "forever" — the user asked us to keep it
        }
        final long cutoff = nowSeconds - seconds;
        for (File dir : dirs()) {
            deleteOlderThan(dir, cutoff);
        }
    }

    private static void deleteOlderThan(File dir, long cutoff) {
        final File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            try {
                if (f.isDirectory()) {
                    deleteOlderThan(f, cutoff);
                    continue;
                }
                if (AutoDeleteMediaTask.usingFilePaths.contains(f.getAbsolutePath())) {
                    continue;             // something is reading or writing it right now
                }
                final long lastUsage = Utilities.getLastUsageFileTime(f.getAbsolutePath());
                // The same guard the media sweep uses: a filesystem that reports no usable time at
                // all reads as 0, and deleting on that would wipe the directory on the first run.
                if (lastUsage > 316000000 && lastUsage < cutoff) {
                    f.delete();
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    private static void deleteContents(File dir) {
        final File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            try {
                if (f.isDirectory()) {
                    deleteContents(f);
                    f.delete();
                } else if (!AutoDeleteMediaTask.usingFilePaths.contains(f.getAbsolutePath())) {
                    f.delete();
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }
}
