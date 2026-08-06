package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.svipe.SvipeMusic;
import org.telegram.ui.ActionBar.Theme;

/**
 * One canonical song as a list row: circular cover (letter tile, or the real Deezer art once the song
 * is enriched), title, artist line, and a right-hand badge — a version count for a song we host, a "+"
 * for one we don't yet.
 *
 * <p>Lives here rather than inside a screen because two screens show the same row and they must not
 * drift apart: music search, and an artist's page, whose tail lists the Deezer tracks by that artist we
 * have not acquired. A row is a row wherever the listener meets it.
 */
public class SvipeSongCell extends FrameLayout {

    /** How the host screen plays a row inline. Null on a screen where rows are never playable. */
    public interface Delegate {
        void onPlayTapped(SvipeMusic.Song song);
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final Delegate delegate;

    private final FrameLayout cover;
    private final TextView letterView;
    private final BackupImageView coverImage;
    private final ImageView playOverlay;
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView badgeView;
    private SvipeMusic.Song song;

    public SvipeSongCell(Context context, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.delegate = delegate;
        setPadding(dp(16), dp(6), dp(12), dp(6));

        cover = new FrameLayout(context);
        // Circular (radius = half the 48dp cover) — deliberately unlike the rounded-SQUARE artist art.
        cover.setBackground(Theme.createRoundRectDrawable(dp(24), color(Theme.key_windowBackgroundGray)));
        addView(cover, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        letterView = new TextView(context);
        letterView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        letterView.setTypeface(AndroidUtilities.bold());
        letterView.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText2));
        letterView.setGravity(Gravity.CENTER);
        cover.addView(letterView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // The real Deezer album cover, drawn over the letter tile once a song is enriched; while it
        // is transparent (unset / loading) the letter shows through, so an unenriched row is unchanged.
        coverImage = new BackupImageView(context);
        coverImage.setRoundRadius(dp(24));
        cover.addView(coverImage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Inline play/pause control over the circular cover; its own click plays the song inline while
        // the rest of the row still opens the song page.
        playOverlay = new ImageView(context);
        playOverlay.setScaleType(ImageView.ScaleType.CENTER);
        playOverlay.setBackground(Theme.createRoundRectDrawable(dp(24), 0x66000000));
        playOverlay.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.MULTIPLY));
        playOverlay.setOnClickListener(v -> {
            if (song != null && delegate != null) {
                delegate.onPlayTapped(song);
            }
        });
        cover.addView(playOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 76, 0, 56, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(titleView);

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText2));
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        badgeView = new TextView(context);
        badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        badgeView.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText2));
        badgeView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        addView(badgeView, LayoutHelper.createFrame(52, LayoutHelper.MATCH_PARENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    public void bind(SvipeMusic.Song s) {
        song = s;
        String title = s.shownTitle() != null && !s.shownTitle().isEmpty()
                ? s.shownTitle() : LocaleController.getString(R.string.AudioUnknownTitle);
        if (s.variantLabel != null && !s.variantLabel.isEmpty()) {
            title = title + " (" + s.variantLabel + ")";
        }
        titleView.setText(title);
        letterView.setText(title.isEmpty() ? "♪" : title.substring(0, 1).toUpperCase());
        String artistLine = s.shownArtist();
        subtitleView.setText(artistLine.isEmpty()
                ? LocaleController.getString(R.string.AudioUnknownArtist) : artistLine);
        // A Deezer placeholder (catalog-missing) shows "+" (addable) instead of a version count.
        badgeView.setText(!s.playable ? "+" : (s.versionCount > 1 ? (s.versionCount + "  ›") : "›"));

        // Playable songs get the inline play button; trackless placeholders are muted (no button).
        boolean canPlay = s.playable && s.defaultTrack != null && delegate != null;
        cover.setAlpha(canPlay ? 1f : 0.45f);
        if (canPlay) {
            playOverlay.setVisibility(VISIBLE);
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            boolean isPlaying = isSamePlayingTrack(playing, s.defaultTrack)
                    && !MediaController.getInstance().isMessagePaused();
            playOverlay.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        } else {
            playOverlay.setVisibility(GONE);
        }

        // Real Deezer cover (small) when enriched; else clear it so the letter tile shows. Clearing is
        // required because cells are recycled — a stale cover must not bleed onto an unenriched song.
        String coverUrl = s.coverSmallUrl != null && !s.coverSmallUrl.isEmpty() ? s.coverSmallUrl
                : (s.coverUrl != null && !s.coverUrl.isEmpty() ? s.coverUrl : null);
        if (coverUrl != null) {
            coverImage.setVisibility(VISIBLE);
            coverImage.setImage(ImageLocation.getForPath(coverUrl), "48_48", (Drawable) null, null);
        } else {
            coverImage.setImageDrawable(null);
            coverImage.setVisibility(GONE);
        }
    }

    /** Same underlying channel post as this catalog track, whichever queue minted the playing copy. */
    public static boolean isSamePlayingTrack(MessageObject playing, SvipeMusic.Track t) {
        if (playing == null || t == null) {
            return false;
        }
        long dialogId = playing.getDialogId();
        return dialogId < 0 && t.channelId != 0 && -dialogId == t.channelId
                && playing.getRealId() == t.messageId;
    }
}
