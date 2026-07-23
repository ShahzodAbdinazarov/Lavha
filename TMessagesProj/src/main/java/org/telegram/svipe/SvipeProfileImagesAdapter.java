package org.telegram.svipe;

import android.content.Context;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.SharedPhotoVideoCell2;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Grid adapter for the profile "Profile Images" tab. Renders through the app's own
 * {@link SharedPhotoVideoCell2} — the exact same cell the Media tab uses — so spacing, corners,
 * ripple and image scaling are identical. Each item is wrapped in a lightweight MessageObject:
 * a live photo renders from the server like Media; a deleted photo renders from our persisted local
 * copy via {@code mediaThumb} and gets a "Deleted" badge.
 */
public class SvipeProfileImagesAdapter extends RecyclerListView.SelectionAdapter {

    private final Context context;
    private final int account;
    private final int columnsCount;
    private SharedPhotoVideoCell2.SharedResources sharedResources;

    private final ArrayList<SvipeProfileImages.Item> items = new ArrayList<>();
    private final ArrayList<MessageObject> messages = new ArrayList<>();

    public SvipeProfileImagesAdapter(Context context, int account, int columnsCount) {
        this.context = context;
        this.account = account;
        this.columnsCount = Math.max(1, columnsCount);
    }

    public void setItems(List<SvipeProfileImages.Item> newItems) {
        items.clear();
        messages.clear();
        if (newItems != null) {
            int id = 1;
            for (SvipeProfileImages.Item item : newItems) {
                items.add(item);
                messages.add(buildMessage(item, id++));
            }
        }
    }

    public SvipeProfileImages.Item getItem(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    /** The wrapped MessageObjects, in grid order — handed to PhotoViewer for the full-screen viewer. */
    public ArrayList<MessageObject> getMessages() {
        return messages;
    }

    private MessageObject buildMessage(SvipeProfileImages.Item item, int id) {
        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.id = id;
        msg.date = item.date;
        // A dummy peer keeps the MessageObject constructor off any null-peer paths.
        msg.peer_id = new TLRPC.TL_peerUser();
        msg.peer_id.user_id = 1;
        msg.from_id = new TLRPC.TL_peerUser();
        msg.from_id.user_id = 1;
        msg.dialog_id = 1;

        TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
        media.flags |= 1;
        media.photo = item.photo != null ? item.photo : dummyPhoto(item);
        msg.media = media;
        msg.flags |= 512; // has media
        // Deleted photo: no live server object — point PhotoViewer + its progress check at the local copy
        // we saved (server no longer serves it), so it loads instead of spinning forever.
        if (item.photo == null && item.localFile != null) {
            msg.attachPath = item.localFile.getAbsolutePath();
        }

        MessageObject mo = new MessageObject(account, msg, false, false);
        mo.mediaExists = true;
        if (item.photo == null && item.localFile != null) {
            mo.mediaThumb = ImageLocation.getForPath(item.localFile.getAbsolutePath());
        }
        return mo;
    }

    /** A minimal photo so MessageObject.photoThumbs is non-empty; the actual pixels come from mediaThumb. */
    private static TLRPC.Photo dummyPhoto(SvipeProfileImages.Item item) {
        TLRPC.TL_photo photo = new TLRPC.TL_photo();
        photo.id = item.photoId;
        photo.date = item.date;
        TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
        size.type = "x";
        size.w = 200;
        size.h = 200;
        size.size = 1;
        size.location = new TLRPC.TL_fileLocationToBeDeprecated();
        photo.sizes.add(size);
        return photo;
    }

    @Override
    public boolean isEnabled(RecyclerView.ViewHolder holder) {
        return true;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (sharedResources == null) {
            sharedResources = new SharedPhotoVideoCell2.SharedResources(context, null);
        }
        SharedPhotoVideoCell2 cell = new SharedPhotoVideoCell2(context, sharedResources, account);
        cell.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        return new RecyclerListView.Holder(cell);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        SharedPhotoVideoCell2 cell = (SharedPhotoVideoCell2) holder.itemView;
        cell.setMessageObject(messages.get(position), columnsCount);
        cell.setSvipeDeleted(items.get(position).deleted);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
