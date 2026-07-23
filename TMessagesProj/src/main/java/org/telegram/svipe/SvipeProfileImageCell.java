package org.telegram.svipe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

/**
 * One square tile in the profile "Rasmlar" grid. Renders a live server photo via ImageLocation or a
 * deleted photo from its persisted local copy, and overlays a translated "Deleted" badge on the
 * captured-but-gone ones. Self-contained (BackupImageView + a badge) so it stays Svipe-owned and
 * never touches the upstream SharedPhotoVideoCell2.
 */
public class SvipeProfileImageCell extends FrameLayout {

    private final BackupImageView imageView;
    private final TextView badge;
    private SvipeProfileImages.Item item;

    public SvipeProfileImageCell(Context context) {
        super(context);

        imageView = new BackupImageView(context);
        imageView.setRoundRadius(AndroidUtilities.dp(4));
        addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        badge = new TextView(context);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(11);
        badge.setTypeface(AndroidUtilities.bold());
        badge.setBackgroundColor(0x99000000);
        badge.setPadding(AndroidUtilities.dp(5), AndroidUtilities.dp(2), AndroidUtilities.dp(5), AndroidUtilities.dp(2));
        badge.setVisibility(GONE);
        addView(badge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT, 4, 0, 0, 4));
    }

    public SvipeProfileImages.Item getItem() {
        return item;
    }

    public void bind(int account, SvipeProfileImages.Item item) {
        this.item = item;
        final String filter = "160_160";

        ImageLocation loc = null;
        if (item.photo != null) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(item.photo.sizes, 200);
            if (size != null) {
                loc = ImageLocation.getForPhoto(size, item.photo);
            }
        }
        if (loc != null) {
            imageView.setImage(loc, filter, (Drawable) null, item.photo);
        } else if (item.localFile != null) {
            imageView.setImage(item.localFile.getAbsolutePath(), filter, (Drawable) null);
        } else {
            imageView.setImageDrawable(null);
        }

        if (item.deleted) {
            badge.setText(LocaleController.getString(R.string.SvipePhotoDeleted));
            badge.setVisibility(VISIBLE);
        } else {
            badge.setVisibility(GONE);
        }
    }
}
