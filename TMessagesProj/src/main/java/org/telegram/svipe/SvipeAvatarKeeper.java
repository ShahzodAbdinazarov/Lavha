package org.telegram.svipe;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches every profile-photo list that flows past the on-screen avatar carousel and persists a
 * permanent copy of each photo we see, so photos the user later deletes can still be shown in the
 * profile "Rasmlar" tab. Purely additive: the only upstream touch is a one-line delegating call in
 * {@link org.telegram.ui.Components.ProfileGalleryView} when it receives {@code dialogPhotosUpdate}.
 *
 * We only remember photos observed while online (the seam is a real profile view) — a photo set and
 * deleted entirely within our offline window is never delivered by Telegram and cannot be captured.
 * Deletion detection is done at display time by the tab, not here.
 *
 * Per-account because FileLoader / access_hash / NotificationCenter are per-account; the metadata
 * ledger ({@link SvipeAvatarStore}) is global (keyed by the global userId).
 */
public class SvipeAvatarKeeper implements NotificationCenter.NotificationCenterDelegate {

    private static final SvipeAvatarKeeper[] instances = new SvipeAvatarKeeper[UserConfig.MAX_ACCOUNT_COUNT];

    private final int account;
    // attachFileName -> {userId, photoId} for downloads in flight, resolved in fileLoaded.
    private final ConcurrentHashMap<String, long[]> pending = new ConcurrentHashMap<>();

    public static SvipeAvatarKeeper getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            return null;
        }
        SvipeAvatarKeeper local = instances[account];
        if (local == null) {
            synchronized (SvipeAvatarKeeper.class) {
                local = instances[account];
                if (local == null) {
                    local = new SvipeAvatarKeeper(account);
                    instances[account] = local;
                }
            }
        }
        return local;
    }

    private SvipeAvatarKeeper(int account) {
        this.account = account;
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded));
    }

    /**
     * Called from ProfileGalleryView with the raw server photo list for a dialog. Records + persists
     * each real user profile photo. No-op for chats (dialogId &lt;= 0) and empty lists.
     */
    public void onDialogPhotos(long dialogId, ArrayList<TLRPC.Photo> photos) {
        if (dialogId <= 0 || photos == null || photos.isEmpty()) {
            return;
        }
        final long userId = dialogId;
        // Same seam feeds the shared archive: report what is live and what has gone (SvipeAvatarSync).
        // Called here on the UI thread because it has to read the UI-owned DialogPhotos model.
        SvipeAvatarSync.onProfileSeen(account, userId);
        final ArrayList<TLRPC.Photo> snapshot = new ArrayList<>(photos);
        Utilities.globalQueue.postRunnable(() -> {
            for (TLRPC.Photo photo : snapshot) {
                if (!(photo instanceof TLRPC.TL_photo) || photo.id == 0 || photo.sizes == null || photo.sizes.isEmpty()) {
                    continue;
                }
                try {
                    SvipeAvatarStore.getInstance().record(userId, photo.id, photo.date);
                    ensureFilePersisted(userId, photo);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        });
    }

    private void ensureFilePersisted(long userId, TLRPC.Photo photo) {
        SvipeAvatarStore store = SvipeAvatarStore.getInstance();
        if (store.hasFile(userId, photo.id)) {
            return;
        }
        TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true));
        if (size == null) {
            return;
        }
        // Fast path: already downloaded (e.g. the carousel just showed it) — copy straight away.
        File cached = FileLoader.getInstance(account).getPathToAttach(size, false);
        if (cached != null && cached.exists() && cached.length() > 0) {
            copyInto(userId, photo.id, cached);
            return;
        }
        // Otherwise pull it into cache and copy when it lands.
        String name = FileLoader.getAttachFileName(size);
        if (name == null || name.isEmpty()) {
            return;
        }
        ImageLocation loc = ImageLocation.getForPhoto(size, photo);
        if (loc == null) {
            return;
        }
        pending.put(name, new long[]{userId, photo.id});
        FileLoader.getInstance(account).loadFile(loc, photo, "jpg", FileLoader.PRIORITY_LOW, 1);
    }

    private void copyInto(long userId, long photoId, File src) {
        try {
            if (src == null || !src.exists() || src.length() == 0) {
                return;
            }
            File dir = SvipeAvatarStore.getInstance().dir();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            File dest = SvipeAvatarStore.getInstance().fileFor(userId, photoId);
            AndroidUtilities.copyFileSafe(src, dest);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.fileLoaded || account != this.account || args == null || args.length == 0) {
            return;
        }
        String fileName = args[0] instanceof String ? (String) args[0] : null;
        if (fileName == null) {
            return;
        }
        long[] meta = pending.remove(fileName);
        if (meta == null) {
            return;
        }
        final long userId = meta[0];
        final long photoId = meta[1];
        final File src = (args.length > 1 && args[1] instanceof File) ? (File) args[1] : null;
        Utilities.globalQueue.postRunnable(() -> copyInto(userId, photoId, src));
    }
}
