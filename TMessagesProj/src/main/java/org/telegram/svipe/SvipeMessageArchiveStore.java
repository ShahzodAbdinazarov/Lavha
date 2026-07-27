package org.telegram.svipe;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Filesystem + policy layer for the deleted/edited message archive. The archive metadata and the
 * serialized {@link org.telegram.tgnet.TLRPC.Message} blobs live in a dedicated SQLite table
 * ({@code svipe_deleted_messages}) owned by {@code MessagesStorage} (which holds the db handle); this
 * class owns only the pinned-media files on disk and the pure, JVM-testable capacity policy.
 *
 * <p>Pinned media goes under {@link ApplicationLoader#getFilesDirFixed(String)} so it survives every
 * in-app "Clear Cache". Capture is always-on (see docs/svipe-deleted-edited-messages-plan.md); the
 * per-dialog caps here bound growth by both entry count and on-disk bytes, evicting oldest-captured
 * first and deleting the evicted blob's media file alongside its row.
 */
public class SvipeMessageArchiveStore {

    private static final String DIR = "svipe_msg_archive";

    /** kind column values. */
    public static final int KIND_DELETED = 0;       // a message that was deleted
    public static final int KIND_EDITED_PRIOR = 1;  // a pre-edit version of a still-live message
    public static final int KIND_LIVE = 2;          // not stored: the current live message, attached on read so the log can show the newest edit

    /** Per-dialog caps (see plan §5, owner-approved). */
    public static final int MAX_PER_DIALOG = 2000;
    public static final long MAX_BYTES_PER_DIALOG = 32L * 1024 * 1024; // 32 MB

    /** Media-pin outcomes (see {@link #planMediaPin}). */
    public static final int PIN_NONE = 0;   // pin nothing — the inline blurred thumb still rides in the blob
    public static final int PIN_FULL = 1;   // copy the whole cached media file
    public static final int PIN_COVER = 2;  // store only a sharp still cover, never the multi-MB payload

    /** Above this, an image is treated as an oversized "sent as file" payload and pinned as a cover, not in full. */
    public static final long MAX_PHOTO_BYTES = 2L * 1024 * 1024; // 2 MB

    // ---- singleton ----

    private static volatile SvipeMessageArchiveStore instance;

    public static SvipeMessageArchiveStore getInstance() {
        SvipeMessageArchiveStore local = instance;
        if (local == null) {
            synchronized (SvipeMessageArchiveStore.class) {
                local = instance;
                if (local == null) {
                    local = new SvipeMessageArchiveStore();
                    instance = local;
                }
            }
        }
        return local;
    }

    // ---- paths ----

    /** Root archive directory (survives every in-app cache clear). */
    public File dir() {
        return ApplicationLoader.getFilesDirFixed(DIR);
    }

    /** Per (account, dialog) directory holding this dialog's pinned media files. */
    public File dialogDir(int account, long dialogId) {
        return new File(new File(dir(), "a" + account), "d" + dialogId);
    }

    /** Pinned media file for one archived message version, or {@code null} if paths can't be built. */
    public File mediaFileFor(int account, long dialogId, int mid, int version) {
        File d = dialogDir(account, dialogId);
        if (d != null && !d.exists()) {
            d.mkdirs();
        }
        return new File(d, mid + "_" + version + ".media");
    }

    /** One archived version rebuilt for display (deserialized message + metadata). */
    public static class Entry {
        public TLRPC.Message message;
        public int kind;
        public int version;
        public long capturedAt;  // when we archived it (ms) — used as the log event date
        public String mediaPath; // pinned local media copy, or null
    }

    // ---- pure policy (JVM-testable, no Android) ----

    /** One archived version's identity + size, used for capacity planning. */
    public static class ArchiveRow {
        public final int mid;
        public final int version;
        public final long capturedAt;
        public final long bytes;        // serialized blob length + pinned media length
        public final String mediaPath;  // absolute path to the pinned media file, or null

        public ArchiveRow(int mid, int version, long capturedAt, long bytes, String mediaPath) {
            this.mid = mid;
            this.version = version;
            this.capturedAt = capturedAt;
            this.bytes = bytes;
            this.mediaPath = mediaPath;
        }
    }

    /**
     * Media-pin policy (owner-approved, plan §7). Keep photos and short audio/round messages in full,
     * but for videos and files store only a sharp cover — never the multi-MB payload. Stickers and GIFs
     * are public and re-fetchable by document id, so pin nothing. This bounds the on-disk archive: one
     * 20 MB video used to eat two-thirds of a dialog's 32 MB budget and evict everything else; a cover
     * costs tens of KB. Pure: takes classification booleans, returns a {@code PIN_*} code.
     *
     * @param hasMedia        the message carries non-empty media
     * @param isPhoto         media is a photo (TL_messageMediaPhoto)
     * @param isVoiceOrRound  media is a voice message or a round video note (small, kept in full)
     * @param isStickerOrGif  media is a sticker, animated sticker, or GIF (pin nothing)
     * @param canProduceCover a sharp cover is obtainable locally (a cached thumb, or a cached video to frame)
     * @param photoBytes      size of the cached full media file in bytes (0 if not cached)
     * @param maxPhotoBytes   {@link #MAX_PHOTO_BYTES}
     */
    public static int planMediaPin(boolean hasMedia,
                                   boolean isPhoto,
                                   boolean isVoiceOrRound,
                                   boolean isStickerOrGif,
                                   boolean canProduceCover,
                                   long photoBytes,
                                   long maxPhotoBytes) {
        if (!hasMedia) return PIN_NONE;
        if (isStickerOrGif) return PIN_NONE;                 // public, re-fetchable by document id
        if (isVoiceOrRound) return PIN_FULL;                 // small and precious
        if (isPhoto) {
            if (photoBytes > 0 && photoBytes <= maxPhotoBytes) return PIN_FULL;
            return canProduceCover ? PIN_COVER : PIN_NONE;   // oversized image (sent as file) -> cover
        }
        return canProduceCover ? PIN_COVER : PIN_NONE;       // video or any other document (file)
    }

    /**
     * A synthetic, always-negative row id for a message pulled from the server (identified by its
     * content-addressed merge_key, not a local mid). Negative so it can never collide with a real
     * positive mid — which keeps synced peer messages out of the in-chat inline merge (that scans real
     * mid ranges) while still appearing in the Recent Actions log. Deterministic from the key, so
     * re-fetching the same item overwrites its row instead of duplicating it.
     */
    public static int syntheticMid(String mergeKey) {
        if (mergeKey == null || mergeKey.length() < 8) {
            return -1;
        }
        try {
            int v = (int) (Long.parseLong(mergeKey.substring(0, 8), 16) & 0x7fffffffL);
            return v == 0 ? -1 : -v;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Next version index for a message id given the versions already stored for it. */
    public static int nextVersion(List<Integer> existingVersions) {
        int max = 0;
        if (existingVersions != null) {
            for (Integer v : existingVersions) {
                if (v != null && v > max) max = v;
            }
        }
        return max + 1;
    }

    /**
     * Decide which rows to evict so that the surviving set is within both {@code maxCount} and
     * {@code maxBytes}. Evicts oldest-captured first (stable tie-break by mid/version). Pure: returns
     * the rows to remove, mutates nothing.
     */
    public static List<ArchiveRow> planTrim(List<ArchiveRow> rows, int maxCount, long maxBytes) {
        ArrayList<ArchiveRow> evict = new ArrayList<>();
        if (rows == null || rows.isEmpty()) return evict;

        ArrayList<ArchiveRow> sorted = new ArrayList<>(rows);
        Collections.sort(sorted, new Comparator<ArchiveRow>() {
            @Override
            public int compare(ArchiveRow a, ArchiveRow b) {
                if (a.capturedAt != b.capturedAt) return Long.compare(a.capturedAt, b.capturedAt); // oldest first
                if (a.mid != b.mid) return Integer.compare(a.mid, b.mid);
                return Integer.compare(a.version, b.version);
            }
        });

        int count = sorted.size();
        long totalBytes = 0;
        for (ArchiveRow r : sorted) totalBytes += r.bytes;

        int i = 0;
        while ((count > maxCount || totalBytes > maxBytes) && i < sorted.size()) {
            ArchiveRow r = sorted.get(i++);
            evict.add(r);
            count--;
            totalBytes -= r.bytes;
        }
        return evict;
    }
}
