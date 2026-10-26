package org.telegram.svipe;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Filesystem + policy layer for the deleted/edited message archive. The archive metadata and the
 * serialized {@link org.telegram.tgnet.TLRPC.Message} blobs live in a dedicated SQLite table
 * ({@code svipe_deleted_messages}) owned by {@code MessagesStorage} (which holds the db handle); this
 * class owns only the pinned-media files on disk and the pure, JVM-testable capacity policy.
 *
 * <p>Pinned media goes under {@link ApplicationLoader#getFilesDirFixed(String)} so it survives every
 * in-app "Clear Cache". Capture is always-on (see docs/svipe-deleted-edited-messages-plan.md). Nothing
 * is evicted automatically: an archived chat stays until the user removes it from Settings → Recent
 * actions.
 */
public class SvipeMessageArchiveStore {

    private static final String DIR = "svipe_msg_archive";

    /** kind column values. */
    public static final int KIND_DELETED = 0;       // a message that was deleted
    public static final int KIND_EDITED_PRIOR = 1;  // a pre-edit version of a still-live message
    public static final int KIND_LIVE = 2;          // not stored: the current live message, attached on read so the log can show the newest edit
    public static final int KIND_REMOVED_BY_ME = 3; // I deleted it only on my side (delete-for-me, delete chat, clear history) — the partner still has it
    public static final int KIND_EVENT = 4;         // a chat event with no message body (a secret chat was started)
    public static final int KIND_REMOVED_BY_PEER = 5; // the partner deleted it only on their side (learned from their synced archive)

    /** {@link #KIND_EVENT} subtypes, kept in the stored message's {@code message} field. */
    public static final String EVENT_SECRET_CHAT = "secret_chat";

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

    /**
     * Whether deleting a whole dialog myself keeps its messages. Clearing history keeps them in every
     * chat; deleting the chat keeps them only in a private chat, because on a group or channel "delete"
     * means leaving it. Secret chats are never kept, and neither are the housekeeping modes (2 = drop a
     * channel's local cache, 3 = drop an empty dialog). {@code onlyHistory} is MessagesController's.
     */
    public static boolean keepsDialogDelete(long dialogId, int onlyHistory, boolean encrypted) {
        if (encrypted || dialogId == 0) return false;
        if (onlyHistory == 1) return true;
        return onlyHistory == 0 && dialogId > 0;
    }

    /**
     * Kinds this device uploads to my server archive. Not the live version (never stored), and not a
     * partner's own removal: that already lives in their archive, and "removed by peer" is relative.
     */
    public static boolean isUploaded(int kind) {
        return kind == KIND_DELETED || kind == KIND_EDITED_PRIOR || kind == KIND_REMOVED_BY_ME || kind == KIND_EVENT;
    }

    /**
     * How an item pulled from the server lands locally. {@code owner} is whose archive it came from
     * (0 = unknown, older server). My own removals and events come back as they are; the partner's
     * removal becomes {@link #KIND_REMOVED_BY_PEER}; the partner's copy of an event is dropped (my
     * device logged its own). Returns -1 to skip.
     */
    public static int kindFromServer(int kind, long owner, long self) {
        if (owner == 0 || owner == self) return kind;
        if (kind == KIND_REMOVED_BY_ME) return KIND_REMOVED_BY_PEER;
        if (kind == KIND_EVENT) return -1;
        return kind;
    }

    // ---- automated deletes that must leave no trace ----

    private static final Set<Long> skipNextDelete = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** The app itself is about to delete this dialog (not the user) — don't keep it. */
    public static void skipNextDialogDelete(long dialogId) {
        skipNextDelete.add(dialogId);
    }

    /** Consumes a {@link #skipNextDialogDelete} mark. */
    public static boolean consumeSkip(long dialogId) {
        return skipNextDelete.remove(dialogId);
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
}
