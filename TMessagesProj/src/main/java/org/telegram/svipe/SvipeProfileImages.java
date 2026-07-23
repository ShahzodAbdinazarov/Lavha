package org.telegram.svipe;

import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data model for the profile "Rasmlar" tab: the union of a user's live current profile photos
 * (photos.getUserPhotos) with the photos we captured before they were deleted
 * ({@link SvipeAvatarStore}). A captured photo no longer in the live set is flagged deleted and
 * rendered from its persisted local copy. Ordered newest-set-first.
 */
public class SvipeProfileImages {

    /** Pure, Android-free reference used for the merge/order (JVM-testable). */
    public static class Ref {
        public final long photoId;
        public final int date;
        public final boolean deleted;

        public Ref(long photoId, int date, boolean deleted) {
            this.photoId = photoId;
            this.date = date;
            this.deleted = deleted;
        }
    }

    /** A renderable item: a live server photo and/or a persisted local copy. */
    public static class Item {
        public final long photoId;
        public final int date;
        public final boolean deleted;
        public final TLRPC.Photo photo; // non-null when the photo is still live on the server
        public final File localFile;    // our persisted copy, if any

        public Item(long photoId, int date, boolean deleted, TLRPC.Photo photo, File localFile) {
            this.photoId = photoId;
            this.date = date;
            this.deleted = deleted;
            this.photo = photo;
            this.localFile = localFile;
        }
    }

    // ---- pure merge/order (JVM-testable) ----

    /**
     * Union current + stored by photoId. Current -&gt; deleted=false (date from the live photo);
     * stored not present in current -&gt; deleted=true (date from the ledger). Newest set first with a
     * stable tie-break so the grid never reshuffles between reads.
     */
    public static List<Ref> mergeRefs(Map<Long, Integer> currentIdToDate, List<SvipeAvatarStore.Photo> stored) {
        ArrayList<Ref> refs = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : currentIdToDate.entrySet()) {
            Integer d = e.getValue();
            refs.add(new Ref(e.getKey(), d == null ? 0 : d, false));
        }
        if (stored != null) {
            for (SvipeAvatarStore.Photo p : stored) {
                if (!currentIdToDate.containsKey(p.photoId)) {
                    refs.add(new Ref(p.photoId, p.date, true));
                }
            }
        }
        Collections.sort(refs, new Comparator<Ref>() {
            @Override
            public int compare(Ref a, Ref b) {
                if (a.date != b.date) return Integer.compare(b.date, a.date);
                return Long.compare(b.photoId, a.photoId);
            }
        });
        return refs;
    }

    // ---- Android glue ----

    /**
     * Build renderable items for a user's profile-images tab. Current photos come from the live
     * per-user DialogPhotos model; captured/deleted photos come from {@link SvipeAvatarStore}. A
     * deleted photo we never persisted is skipped (nothing to show).
     */
    public static List<Item> build(int account, long userId) {
        Map<Long, Integer> currentIdToDate = new HashMap<>();
        Map<Long, TLRPC.Photo> currentById = new HashMap<>();
        MessagesController.DialogPhotos dp = MessagesController.getInstance(account).getDialogPhotos(userId);
        if (dp != null && dp.photos != null) {
            ArrayList<TLRPC.Photo> snapshot = new ArrayList<>(dp.photos);
            for (TLRPC.Photo photo : snapshot) {
                if (photo instanceof TLRPC.TL_photo && photo.id != 0) {
                    currentIdToDate.put(photo.id, photo.date);
                    currentById.put(photo.id, photo);
                }
            }
        }
        List<SvipeAvatarStore.Photo> stored = SvipeAvatarStore.getInstance().getForUser(userId);
        List<Ref> refs = mergeRefs(currentIdToDate, stored);
        ArrayList<Item> items = new ArrayList<>();
        for (Ref r : refs) {
            TLRPC.Photo photo = currentById.get(r.photoId);
            File file = SvipeAvatarStore.getInstance().fileFor(userId, r.photoId);
            if (file == null || !(file.exists() && file.length() > 0)) {
                file = null;
            }
            if (r.deleted && file == null && photo == null) {
                continue; // nothing renderable
            }
            items.add(new Item(r.photoId, r.date, r.deleted, photo, file));
        }
        return items;
    }
}
