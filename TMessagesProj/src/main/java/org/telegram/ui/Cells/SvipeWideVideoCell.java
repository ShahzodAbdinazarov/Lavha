package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.svipe.SvipeBlockedChannels;
import org.telegram.svipe.SvipeDiscover;
import org.telegram.svipe.SvipeSavedChannels;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.ReportBottomSheet;

import java.util.ArrayList;

/**
 * The full-width HORIZONTAL / long-form video card — the YouTube-shaped one: a 16:9-ish thumbnail with
 * a duration badge, then a metadata row of channel avatar + 2-line title + "channel · views · age" +
 * a ⋮ overflow menu.
 *
 * <p>ONE renderer for every surface that lists long-form videos: the Search section's Explore grid
 * (SvipeExploreGrid) and the watch page's related list (SvipeWatchActivity). It lives here rather than
 * inside the grid because a second copy of a card whose ⋮ menu posts recsys events is exactly how two
 * menus end up calling the same action by two different names.
 *
 * <p><b>Height discipline (load-bearing).</b> The card's aspect comes off the /v1/videos REFERENCE and
 * never off the resolved Telegram document, and the two title lines are fixed with {@code setLines},
 * not {@code setMaxLines}. So the cell measures to its final height on the first layout pass and does
 * not reflow when MTProto answers — the same rule the grid's span lookup follows.
 */
public class SvipeWideVideoCell extends LinearLayout {

    /**
     * Widest card we will draw. Beyond this a "horizontal" video is a letterboxed banner, so clamp and
     * let the thumbnail crop instead of handing the row to a 30dp-tall sliver.
     */
    public static final float MAX_CARD_ASPECT = 2.4f;

    /**
     * What the ⋮ menu needs from whoever is showing the card. The menu itself — its actions, wording,
     * icons, bulletins and the events it posts — lives in this cell so the grid and the watch page
     * cannot drift apart; only the list surgery is delegated, because only the host knows its list.
     */
    public interface Delegate {
        /**
         * Host fragment. ItemOptions.downFragment special-cases a DialogsActivity that owns the main
         * tabs and redirects the popup to the MainTabsActivity layer — that redirect is what makes the
         * menu draw above the floating bottom tab bar, so the fragment form of makeOptions is required
         * here, not the ViewGroup one. ShareAlert and ReportBottomSheet need it too.
         */
        BaseFragment fragment();

        /** Drop this one reference from the host's list — "not interested" must make it disappear. */
        void onRefRemoved(SvipeDiscover.Item ref);

        /** Drop every reference from a channel the user just blocked. */
        void onChannelBlocked(long channelId);
    }

    private final int account;
    private final WideThumbView thumb;
    private final BackupImageView avatar;
    private final TextView title;
    private final TextView meta;

    private SvipeDiscover.Item ref;
    private MessageObject mo;
    private Delegate delegate;

    public SvipeWideVideoCell(Context context, int account) {
        super(context);
        this.account = account;
        setOrientation(VERTICAL);

        thumb = new WideThumbView(context);
        addView(thumb, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(14));

        avatar = new BackupImageView(context);
        avatar.setRoundRadius(AndroidUtilities.dp(18));
        row.addView(avatar, LayoutHelper.createLinear(36, 36, Gravity.TOP, 0, 0, 12, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(VERTICAL);

        title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        // setLines, NOT setMaxLines: the row must occupy its full height even before the caption has
        // resolved, or every card would grow by two lines mid-scroll.
        title.setLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(AndroidUtilities.dp(1), 1f);
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        meta = new TextView(context);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        meta.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        meta.setLines(1);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.TOP));

        ImageView more = new ImageView(context);
        more.setImageResource(R.drawable.msg_actions);
        more.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN);
        more.setScaleType(ImageView.ScaleType.CENTER);
        more.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        more.setOnClickListener(this::showMore);
        row.addView(more, LayoutHelper.createLinear(36, 36, Gravity.TOP));

        addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    /**
     * @param ref      the feed reference — the ONLY source of the card's shape (see the height rule).
     * @param mo       the resolved Telegram message, or null while MTProto is still answering.
     * @param chatHint the channel if the host already resolved it; MessagesController is tried first.
     */
    public void bind(SvipeDiscover.Item ref, MessageObject mo, TLRPC.Chat chatHint) {
        bind(ref, mo, chatHint, null, null);
    }

    /**
     * The same card with the two text lines supplied by the host — what a FILM row needs: its title is
     * the film's, not the post's caption, and its second line is "year · ★ rating", not
     * "channel · views · age".
     *
     * <p>That is the whole difference between a film shelf and the unfiltered feed. It used to be a
     * separate cell class, and the shelves consequently read as a different screen from the tab they
     * were opened from — no avatar, no ⋮, different type sizes and paddings. Two renderers for one
     * row is also how the ⋮ menu ends up existing on one surface and not the other.
     *
     * @param titleOverride first line, or null to use the post's caption.
     * @param metaOverride  second line, or null to use "channel · views · age".
     */
    public void bind(SvipeDiscover.Item ref, MessageObject mo, TLRPC.Chat chatHint,
                     CharSequence titleOverride, CharSequence metaOverride) {
        this.ref = ref;
        this.mo = mo;
        thumb.bindRef(ref);
        bindThumb(thumb, mo, true);

        final TLRPC.Chat chat = chatFor(account, ref, chatHint);
        if (chat != null) {
            avatar.setForUserOrChat(chat, new AvatarDrawable(chat));
        } else {
            // Not resolved yet: a letter avatar off the @username, so the row never shows a hole.
            AvatarDrawable ad = new AvatarDrawable();
            ad.setInfo(0, ref != null ? ref.username : null, null);
            avatar.setImageDrawable(ad);
        }
        title.setText(titleOverride != null ? titleOverride : captionOf(mo));
        meta.setText(metaOverride != null ? metaOverride : metaLine(account, ref, mo, chat));
    }

    public SvipeDiscover.Item getRef() {
        return ref;
    }

    /** Loads a resolved message's Telegram video thumbnail into a cell, or clears it if unresolved. */
    public static void bindThumb(BackupImageView iv, MessageObject mo, boolean wide) {
        if (mo == null || mo.getDocument() == null) {
            iv.getImageReceiver().clearImage();
            return;
        }
        TLRPC.Document doc = mo.getDocument();
        // A full-width card is ~3x the pixel width of a 3-up tile, so it needs the larger thumb and a
        // matching filter or it renders visibly soft.
        TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, wide ? 1000 : 320);
        TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 50);
        final String filter = wide ? "720_720" : "240_240";
        iv.setImage(
                ImageLocation.getForDocument(big, doc), filter,
                ImageLocation.getForDocument(small, doc), filter + "_b",
                0, mo);
    }

    /** Video caption, mirroring ReelsActivity's own caption fallback (caption, then message text). */
    public static CharSequence captionOf(MessageObject mo) {
        if (mo == null) return null;
        if (mo.caption != null && mo.caption.length() > 0) {
            return AndroidUtilities.replaceNewLines(mo.caption);
        }
        if (mo.messageOwner != null && mo.messageOwner.message != null && mo.messageOwner.message.length() > 0) {
            return AndroidUtilities.replaceNewLines(mo.messageOwner.message);
        }
        return null;
    }

    /** The channel behind a reference once it has resolved, else the host's hint, else null. */
    public static TLRPC.Chat chatFor(int account, SvipeDiscover.Item ref, TLRPC.Chat hint) {
        if (ref == null) return hint;
        // The username resolve already put the chats into MessagesController, and channelId IS chat.id
        // here — not the negated dialogId.
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(ref.channelId);
        return chat != null ? chat : hint;
    }

    /** "Channel · 2.7K views · 2 days ago" — each part dropped when unknown, so it never reads oddly. */
    public static CharSequence metaLine(int account, SvipeDiscover.Item ref, MessageObject mo, TLRPC.Chat chat) {
        final ArrayList<String> parts = new ArrayList<>(3);
        if (chat != null && chat.title != null) {
            parts.add(chat.title);
        } else if (ref != null && ref.username != null) {
            parts.add("@" + ref.username);
        }
        if (mo != null && mo.messageOwner != null) {
            final int views = mo.messageOwner.views;
            if (views > 0) {
                parts.add(String.format(
                        LocaleController.getPluralString("Views", views),
                        AndroidUtilities.formatWholeNumber(views, 0)));
            }
            final int date = mo.messageOwner.date;
            if (date > 0) {
                final int now = ConnectionsManager.getInstance(account).getCurrentTime();
                if (now > date) {
                    parts.add(LocaleController.formatRelativeDate(now - date));
                }
            }
        }
        return TextUtils.join("  ·  ", parts);
    }

    /**
     * The card's ⋮ menu. Deliberately the same actions, wording and icons as the reels player's own
     * overflow (ReelsActivity.showMore) — one action must not be called two different things in two
     * places. Report is red, being the only irreversible/escalating one.
     */
    private void showMore(View anchor) {
        final BaseFragment fragment = delegate != null ? delegate.fragment() : null;
        if (ref == null || fragment == null) {
            return;
        }
        final SvipeDiscover.Item item = ref;
        final MessageObject message = mo;
        ItemOptions.makeOptions(fragment, anchor)
                .setGravity(Gravity.RIGHT)
                // "Keyinroq ko'rish" — the YouTube action, implemented as a forward into the user's own
                // private archived channel (SvipeSavedChannels). Only offered once the message has
                // resolved, because the list stores a real copy, not a reference.
                // The two YouTube "before you play it" saves, in YouTube's own order. They are
                // different intents and therefore different lists: Watch Later is a queue you mean to
                // empty, Saved is a library you mean to keep — see SvipeSavedChannels.
                .addIf(message != null, R.drawable.msg_recent,
                        LocaleController.getString(R.string.SvipeSaveWatchLater), () ->
                        saveTo(fragment, account, SvipeSavedChannels.Kind.WATCH_LATER, message))
                .addIf(message != null, R.drawable.msg_saved,
                        LocaleController.getString(R.string.SvipeSaveToList), () ->
                        saveTo(fragment, account, SvipeSavedChannels.Kind.SAVED_VIDEOS, message))
                .add(R.drawable.msg_share, LocaleController.getString(R.string.SvipeReelsShare), () -> share(fragment, item, message))
                .add(R.drawable.msg2_block2, LocaleController.getString(R.string.SvipeReelsNotInterested), () -> {
                    SvipeDiscover.sendEvent(account, item.channelId, item.messageId, "NOT_INTERESTED", null);
                    delegate.onRefRemoved(item);
                    BulletinFactory.of(fragment).createSimpleBulletin(
                            R.raw.chats_infotip,
                            LocaleController.getString(R.string.SvipeReelsLessLikeThis)).show();
                })
                .add(R.drawable.msg_disable, LocaleController.getString(R.string.SvipeReelsBlockChannel), () -> {
                    new SvipeBlockedChannels(account).add(item.channelId);
                    SvipeDiscover.sendEvent(account, item.channelId, 0, "BLOCK_CHANNEL", null);
                    delegate.onChannelBlocked(item.channelId);
                    BulletinFactory.of(fragment).createSimpleBulletin(
                            R.raw.chats_infotip,
                            LocaleController.getString(R.string.SvipeReelsChannelBlocked)).show();
                })
                .add(R.drawable.msg_report, LocaleController.getString(R.string.ReportChat), true, () -> {
                    if (message != null) {
                        ReportBottomSheet.openMessage(fragment, message);
                    }
                })
                .show();
    }

    /** Forward a post into one of the user's saved-list channels, then confirm it landed. */
    private static void saveTo(BaseFragment fragment, int account, SvipeSavedChannels.Kind kind,
                               MessageObject message) {
        SvipeSavedChannels.save(account, kind, message, fragment,
                chatId -> AndroidUtilities.runOnUIThread(() -> {
                    if (chatId != 0) {
                        BulletinFactory.of(fragment).createSimpleBulletin(
                                R.raw.saved_messages,
                                LocaleController.getString(R.string.SvipeSavedToList)).show();
                    }
                }));
    }

    /** Share a reference: the owned svipe.uz link when the feed carried one, else the t.me post. */
    private static void share(BaseFragment fragment, SvipeDiscover.Item ref, MessageObject mo) {
        if (fragment.getParentActivity() == null || ref == null) {
            return;
        }
        String link = ref.shareUrl;
        if ((link == null || link.isEmpty()) && ref.username != null) {
            link = "https://t.me/" + ref.username + "/" + ref.messageId;
        }
        if (link == null || link.isEmpty()) {
            return;
        }
        fragment.showDialog(new ShareAlert(fragment.getParentActivity(), null, link, false, link, false));
    }

    /**
     * The card image itself: a full-width thumbnail whose height follows the video's own aspect (so it
     * is shown uncropped, unlike the portrait tiles which centre-crop), with a duration badge in the
     * bottom-right corner.
     */
    public static class WideThumbView extends BackupImageView {
        private final Shimmer shimmer = new Shimmer();
        private final RectF rect = new RectF();
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint badgeText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private float aspect = 16f / 9f;
        private String duration;

        public WideThumbView(Context context) {
            super(context);
            badgePaint.setColor(0x99000000);
            badgeText.setColor(Color.WHITE);
            badgeText.setTextSize(AndroidUtilities.dp(11));
            badgeText.setTypeface(AndroidUtilities.bold());
        }

        public void bindRef(SvipeDiscover.Item ref) {
            final float a = ref == null
                    ? 16f / 9f
                    : Math.min(MAX_CARD_ASPECT, Math.max(SvipeDiscover.LANDSCAPE_MIN_ASPECT, ref.aspect()));
            final int seconds = ref == null ? 0 : ref.durationMs / 1000;
            final String d = seconds > 0 ? AndroidUtilities.formatShortDuration(seconds) : null;
            if (a != aspect) {
                aspect = a;
                requestLayout();
            }
            if (!TextUtils.equals(d, duration)) {
                duration = d;
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int height = Math.max(1, Math.round(width / aspect));
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!getImageReceiver().hasBitmapImage()) {
                final float inset = AndroidUtilities.dp(1);
                rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
                shimmer.draw(canvas, rect, AndroidUtilities.dp(3), this);
            }
            super.onDraw(canvas);
            if (duration != null) {
                final float pad = AndroidUtilities.dp(4);
                final float margin = AndroidUtilities.dp(6);
                final float tw = badgeText.measureText(duration);
                final float th = badgeText.getTextSize();
                final float right = getWidth() - margin;
                final float bottom = getHeight() - margin;
                rect.set(right - tw - pad * 2, bottom - th - pad * 1.6f, right, bottom);
                canvas.drawRoundRect(rect, AndroidUtilities.dp(3), AndroidUtilities.dp(3), badgePaint);
                canvas.drawText(duration, rect.left + pad, rect.bottom - pad * 0.8f, badgeText);
            }
        }
    }

    /**
     * Theme-aware placeholder shimmer: an opaque gray block with a soft highlight band sweeping across.
     * The highlight is derived from the theme (only ~9% lighter in dark mode, so it isn't garish; a
     * stronger lift in light mode where contrast is naturally lower). Self-animates via invalidate().
     *
     * <p>It lives with this cell because the cell's own thumbnail needs it, and every other surface
     * that draws a video placeholder (the grid's portrait tiles and skeletons, the watch page's related
     * skeletons) then shimmers identically instead of each rolling its own.
     */
    public static class Shimmer {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private LinearGradient gradient;
        private int gradientWidth, base, highlight;
        private float progress;
        private long lastUpdate;

        public void draw(Canvas canvas, RectF rect, float rad, View view) {
            final int b = Theme.getColor(Theme.key_windowBackgroundGray);
            final boolean dark = (Color.red(b) * 0.299f + Color.green(b) * 0.587f + Color.blue(b) * 0.114f) < 128f;
            final int h = ColorUtils.blendARGB(b, Color.WHITE, dark ? 0.09f : 0.45f);
            final int w = AndroidUtilities.dp(200);
            if (gradient == null || base != b || highlight != h || gradientWidth != w) {
                base = b;
                highlight = h;
                gradientWidth = w;
                gradient = new LinearGradient(0, 0, w, 0,
                        new int[]{b, h, b}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
                paint.setShader(gradient);
            }
            final long now = System.currentTimeMillis();
            if (lastUpdate != 0) {
                progress += (now - lastUpdate) / 1100f;
                while (progress > 1f) {
                    progress -= 1f;
                }
            }
            lastUpdate = now;
            final float x = (rect.width() + gradientWidth * 2f) * progress - gradientWidth;
            matrix.reset();
            matrix.setTranslate(rect.left + x, 0);
            gradient.setLocalMatrix(matrix);
            canvas.drawRoundRect(rect, rad, rad, paint);
            if (view != null) {
                view.invalidate();
            }
        }
    }
}
