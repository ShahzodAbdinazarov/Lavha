package org.telegram.svipe.video;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.SvipeWideVideoCell;
import org.telegram.ui.ChooseQualityLayout;
import org.telegram.ui.ChooseSpeedLayout;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.PopupSwipeBackLayout;
import org.telegram.ui.Components.SpeedIconDrawable;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.Components.VideoPlayerSeekBar;
import org.telegram.ui.Stories.DarkThemeResourceProvider;

import java.io.File;

/**
 * The player chrome: back / ⋮ on top, a centre play affordance, and a bottom bar with the seek bar,
 * the elapsed-and-total time and the fullscreen toggle — plus the mini player's own row (title,
 * play/pause, ✕), because the mini bar is the same chrome laid out beside the video instead of over it.
 *
 * Assembled from the PhotoViewer-FREE sources: {@link VideoPlayerSeekBar} driven by a headless host
 * view and {@link PlayPauseDrawable}, wired the way SecretMediaViewer wires them. PhotoViewer's own
 * control container drags in its editor, web-player and timeline state and is not worth extracting.
 *
 * HARD RULE (this is the owner's rotation bug): which chrome is shown, and whether the fullscreen
 * button reads "enter" or "exit", is decided ONLY by {@link SvipeVideoPlayerController#getMode()}.
 * Nothing here may compare width to height or read the configuration — PhotoViewer re-derives
 * "am I fullscreen" from {@code parentWidth > parentHeight} on every measure pass, which is why
 * turning the phone back to portrait silently drops it out of fullscreen.
 */
public class SvipeVideoControls extends FrameLayout {

    /** How long the chrome stays up after the last interaction, as in PhotoViewer. */
    private static final long AUTO_HIDE_MS = 3000;
    /** The brightness / volume read-out is a transient confirmation, not chrome. */
    private static final long LEVEL_HIDE_MS = 900;

    public static final int LEVEL_BRIGHTNESS = 0;
    public static final int LEVEL_VOLUME = 1;

    private final FrameLayout mainChrome;   // INLINE + FULLSCREEN
    private final LinearLayout miniChrome;  // MINI

    private final ImageView playButton;
    private final PlayPauseDrawable playDrawable = new PlayPauseDrawable(28);
    private final ImageView fullscreenButton;
    private final ImageView menuButton;
    private final AutoplaySwitch autoplaySwitch;

    private final SimpleTextView timeText;
    private final SeekBarHost seekHost;
    private final VideoPlayerSeekBar seekBar;

    private final TextView miniTitle;
    private final TextView miniSubtitle;
    private final ImageView miniPlayButton;
    private final PlayPauseDrawable miniPlayDrawable = new PlayPauseDrawable(20);

    private final LevelIndicator levelIndicator;
    private final UpNextView upNextView;

    private boolean shown = true;
    private boolean playing;
    private int mode = SvipeVideoPlayerController.MODE_INLINE;

    private final Runnable hideRunnable = () -> hide(true);
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            AndroidUtilities.runOnUIThread(this, 200);
        }
    };

    public SvipeVideoControls(Context context) {
        super(context);

        mainChrome = new FrameLayout(context);
        // updateViewVisibilityAnimated keeps its shown/hidden state in the view's TAG, so the tag has
        // to agree with the initial visibility or the very first hide is silently dropped.
        mainChrome.setTag(1);
        addView(mainChrome, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Scrims, not a full dim: white icons have to stay readable over a bright frame without
        // darkening the middle of the picture.
        final View topScrim = new View(context);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x00000000}));
        mainChrome.addView(topScrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 76, Gravity.TOP));
        final View bottomScrim = new View(context);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x8C000000, 0x00000000}));
        mainChrome.addView(bottomScrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 84, Gravity.BOTTOM));

        final ImageView backButton = iconButton(context, R.drawable.ic_ab_back, R.string.AccDescrGoBack,
                v -> SvipeVideoPlayerController.getInstance().onBackAffordance());
        mainChrome.addView(backButton, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.LEFT, 6, 6, 0, 0));

        menuButton = iconButton(context, R.drawable.msg_actions, R.string.AccDescrMoreOptions,
                this::showSettingsMenu);
        mainChrome.addView(menuButton, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.RIGHT, 0, 6, 6, 0));

        // The owner asked for a real switch, not a menu row: autoplay is the one player setting that
        // changes what happens next, so YouTube puts it in the chrome and so do we.
        autoplaySwitch = new AutoplaySwitch(context);
        autoplaySwitch.setContentDescription(LocaleController.getString(R.string.SvipeVideoAutoplay));
        autoplaySwitch.setOnClickListener(v -> {
            final SvipeVideoPlayerController c = SvipeVideoPlayerController.getInstance();
            c.setAutoplayEnabled(!c.isAutoplayEnabled());
            autoplaySwitch.setChecked(c.isAutoplayEnabled(), true);
            scheduleHide();
        });
        mainChrome.addView(autoplaySwitch, LayoutHelper.createFrame(52, 40, Gravity.TOP | Gravity.RIGHT, 0, 6, 46, 0));

        playButton = new ImageView(context);
        playButton.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(60), 0x66000000));
        playDrawable.setCallback(playButton);
        playDrawable.setParent(playButton);
        playButton.setImageDrawable(playDrawable);
        playButton.setScaleType(ImageView.ScaleType.CENTER);
        playButton.setContentDescription(LocaleController.getString(R.string.AccActionPlay));
        playButton.setOnClickListener(v -> togglePlayPause());
        mainChrome.addView(playButton, LayoutHelper.createFrame(60, 60, Gravity.CENTER));

        timeText = new SimpleTextView(context);
        timeText.setTextColor(Color.WHITE);
        timeText.setTextSize(13);
        timeText.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        timeText.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        mainChrome.addView(timeText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 22,
                Gravity.BOTTOM | Gravity.LEFT, 14, 0, 0, 5));

        fullscreenButton = iconButton(context, R.drawable.msg_maxvideo, R.string.AccSwitchToFullscreen, v -> {
            final SvipeVideoPlayerController c = SvipeVideoPlayerController.getInstance();
            if (c.getMode() == SvipeVideoPlayerController.MODE_FULLSCREEN) {
                c.exitFullscreen();
            } else {
                c.enterFullscreen();
            }
            scheduleHide();
        });
        mainChrome.addView(fullscreenButton, LayoutHelper.createFrame(34, 34,
                Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 6, 0));

        seekHost = new SeekBarHost(context);
        seekBar = new VideoPlayerSeekBar(seekHost);
        seekBar.setHorizontalPadding(AndroidUtilities.dp(2));
        seekBar.setColors(0x33ffffff, 0x59ffffff, Color.WHITE, Color.WHITE, Color.WHITE, 0x59ffffff);
        seekBar.setDelegate(new VideoPlayerSeekBar.SeekBarDelegate() {
            @Override
            public void onSeekBarDrag(float progress) {
                // Release: an EXACT seek, so the frame the user picked is the frame they get.
                seekToFraction(progress, false);
                scheduleHide();
            }

            @Override
            public void onSeekBarContinuousDrag(float progress) {
                // While dragging: CLOSEST_SYNC, which is the only way scrubbing a long video keeps up.
                seekToFraction(progress, true);
                cancelHide();
            }
        });
        mainChrome.addView(seekHost, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 32,
                Gravity.BOTTOM | Gravity.LEFT, 10, 0, 10, 30));

        miniChrome = new LinearLayout(context);
        miniChrome.setOrientation(LinearLayout.HORIZONTAL);
        miniChrome.setGravity(Gravity.CENTER_VERTICAL);
        miniChrome.setBackgroundColor(0xFF141414);
        miniChrome.setVisibility(GONE);
        addView(miniChrome, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final LinearLayout miniTexts = new LinearLayout(context);
        miniTexts.setOrientation(LinearLayout.VERTICAL);
        miniTitle = new TextView(context);
        miniTitle.setTextColor(0xFFFFFFFF);
        miniTitle.setTextSize(13);
        miniTitle.setMaxLines(2);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniTexts.addView(miniTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        miniSubtitle = new TextView(context);
        miniSubtitle.setTextColor(0x99FFFFFF);
        miniSubtitle.setTextSize(11);
        miniSubtitle.setMaxLines(1);
        miniSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        miniTexts.addView(miniSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        miniChrome.addView(miniTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 4, 0));

        miniPlayButton = new ImageView(context);
        miniPlayDrawable.setCallback(miniPlayButton);
        miniPlayDrawable.setParent(miniPlayButton);
        miniPlayButton.setImageDrawable(miniPlayDrawable);
        miniPlayButton.setScaleType(ImageView.ScaleType.CENTER);
        miniPlayButton.setBackground(Theme.createSelectorDrawable(0x30ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP));
        miniPlayButton.setContentDescription(LocaleController.getString(R.string.AccActionPlay));
        miniPlayButton.setOnClickListener(v -> togglePlayPause());
        miniChrome.addView(miniPlayButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        final ImageView miniCloseButton = iconButton(context, R.drawable.msg_close, R.string.Close,
                v -> SvipeVideoPlayerController.getInstance().close());
        miniChrome.addView(miniCloseButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        levelIndicator = new LevelIndicator(context);
        levelIndicator.setVisibility(GONE);
        addView(levelIndicator, LayoutHelper.createFrame(160, 40, Gravity.CENTER));

        // Added last so it covers the chrome: while it is up, the finished video's own controls are not
        // what the user is deciding between.
        upNextView = new UpNextView(context);
        upNextView.setVisibility(GONE);
        addView(upNextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        setMode(mode);
    }

    private ImageView iconButton(Context context, int icon, int contentDescription, OnClickListener onClick) {
        final ImageView v = new ImageView(context);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setImageResource(icon);
        v.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        v.setBackground(Theme.createSelectorDrawable(0x30ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP));
        v.setContentDescription(LocaleController.getString(contentDescription));
        v.setOnClickListener(onClick);
        return v;
    }

    private void togglePlayPause() {
        SvipeVideoPlayerController.getInstance().togglePlayPause();
        scheduleHide();
    }

    private void seekToFraction(float fraction, boolean fast) {
        final SvipeVideoPlayerController c = SvipeVideoPlayerController.getInstance();
        final long duration = c.getDurationMs();
        if (duration > 0) {
            c.seekTo((long) (fraction * duration), fast);
        }
    }

    // ---------------- state ----------------

    /**
     * The ⋮ settings menu: playback speed, quality (only on a multi-rendition post), loop, and "Save to
     * gallery" once the file is on disk.
     *
     * <p>The speed and quality pickers are the app's own — {@link ChooseSpeedLayout} (the five speed rows
     * plus the fine slider) and {@link ChooseQualityLayout} (whose {@code update} returns false when
     * there is only one rendition, which is exactly the rule for hiding the row). Both want a
     * {@link PopupSwipeBackLayout} to slide in over the main list, so the popup is built by
     * {@link ItemOptions} — which owns the positioning, the dim and the dismissal — and its swipeback is
     * borrowed for the two submenus. The container is LaunchActivity's root frame because this view has
     * no fragment: the player lives above the whole fragment stack.
     */
    private void showSettingsMenu(View anchor) {
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity == null || activity.getMainContainerFrameLayout() == null) {
            return;
        }
        final SvipeVideoPlayerController controller = SvipeVideoPlayerController.getInstance();
        final MessageObject mo = controller.getCurrent() != null ? controller.getCurrent().mo : null;
        // The menu is always over video, so it is always dark — never the app's own light palette.
        final Theme.ResourcesProvider dark = new DarkThemeResourceProvider();
        final ItemOptions options = ItemOptions.makeOptions(
                activity.getMainContainerFrameLayout(), dark, anchor, true);
        // ItemOptions builds exactly one popup layout when no space gap was added, and that is the one
        // carrying the swipeback the submenus slide in from.
        final PopupSwipeBackLayout swipeBack = options.getLayout() instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout
                ? ((ActionBarPopupWindow.ActionBarPopupWindowLayout) options.getLayout()).getSwipeBack()
                : null;
        if (swipeBack == null) {
            return;
        }
        final ActionBarPopupWindow.ActionBarPopupWindowLayout popup =
                (ActionBarPopupWindow.ActionBarPopupWindowLayout) options.getLayout();
        popup.swipeBackGravityRight = true;
        popup.setSwipeBackForegroundColor(0xff222222);
        options.setGravity(Gravity.RIGHT);

        final ChooseSpeedLayout[] speedLayout = new ChooseSpeedLayout[1];
        speedLayout[0] = new ChooseSpeedLayout(getContext(), swipeBack, (speed, isFinal, closeMenu) -> {
            // The slider reports continuously: preview live, persist only once the finger settles, or a
            // single drag writes the preference thirty times.
            if (isFinal) {
                controller.chooseSpeed(speed);
            } else {
                controller.setPlaybackSpeed(speed);
            }
            speedLayout[0].update(speed, isFinal);
            if (closeMenu) {
                options.dismiss();
            }
        });
        speedLayout[0].update(controller.getPlaybackSpeed(), true);
        final int speedIndex = popup.addViewToSwipeBack(speedLayout[0].speedSwipeBackLayout);
        options.add(submenuRow(dark, R.drawable.msg_speed, LocaleController.getString(R.string.Speed),
                speedLabel(controller.getPlaybackSpeed()), () -> swipeBack.openForeground(speedIndex)));

        final VideoPlayer player = controller.getPlayer();
        final ChooseQualityLayout quality = new ChooseQualityLayout(getContext(), swipeBack,
                (index, isFinal, closeMenu) -> {
                    controller.selectQuality(index);
                    if (closeMenu) {
                        options.dismiss();
                    }
                });
        // update() is also the "does this post even have a ladder" test — false means one rendition,
        // and then the whole row must not appear.
        if (quality.update(player)) {
            final int qualityIndex = popup.addViewToSwipeBack(quality.layout);
            options.add(submenuRow(dark, R.drawable.video_settings, LocaleController.getString(R.string.Quality),
                    null, () -> swipeBack.openForeground(qualityIndex)));
        }

        options.addGap();
        options.addChecked(controller.isLooping(), R.drawable.menu_video_loop,
                LocaleController.getString(R.string.SvipeVideoLoop),
                () -> controller.setLooping(!controller.isLooping()));

        // Secondary, and only once the bytes are already here: the primary offline action is the watch
        // page's Download pill, which puts the file in Telegram's own Downloads list.
        final File downloaded = mo != null
                ? SvipeDownloadButton.downloadedFile(controller.getAccount(), mo) : null;
        if (downloaded != null) {
            options.add(R.drawable.msg_gallery, LocaleController.getString(R.string.SaveToGallery),
                    () -> MediaController.saveFile(downloaded.getAbsolutePath(), getContext(), 1, null, null));
        }

        cancelHide();   // the popup outlives the tap, and chrome vanishing under it looks broken
        options.setOnDismiss(this::scheduleHide);
        options.show();
    }

    /** A row that opens a swipeback submenu: label, optional current value, and the right arrow. */
    private ActionBarMenuSubItem submenuRow(Theme.ResourcesProvider provider, int icon, CharSequence text,
                                            CharSequence value, Runnable onClick) {
        final ActionBarMenuSubItem item = new ActionBarMenuSubItem(getContext(), false, false, provider);
        item.setTextAndIcon(text, icon);
        if (value != null) {
            item.setSubtext(value);
        }
        item.setRightIcon(R.drawable.msg_arrowright);
        item.setMinimumWidth(AndroidUtilities.dp(196));
        item.openSwipeBackLayout = onClick;
        item.setOnClickListener(v -> item.openSwipeBack());
        return item;
    }

    /** "1.5x" for the speed row's value line; absent at 1x, where there is nothing to report. */
    private static CharSequence speedLabel(float speed) {
        if (Math.abs(speed - 1f) < 0.001f) {
            return null;
        }
        return SpeedIconDrawable.formatNumber(speed) + "x";
    }

    /**
     * Which chrome to wear. The mini row is laid out BESIDE the video (the stage gives this view a rect
     * to the right of the player in mini), so it has its own opaque background while the main chrome
     * sits over the picture and is transparent.
     */
    public void setMode(int mode) {
        this.mode = mode;
        final boolean mini = mode == SvipeVideoPlayerController.MODE_MINI;
        miniChrome.setVisibility(mini ? VISIBLE : GONE);
        // The fullscreen button reads its state from the MODE and nothing else — never from the window
        // being wider than it is tall, which is the predicate that loses PhotoViewer its fullscreen.
        final boolean fullscreen = mode == SvipeVideoPlayerController.MODE_FULLSCREEN;
        fullscreenButton.setImageResource(fullscreen ? R.drawable.msg_minvideo : R.drawable.msg_maxvideo);
        fullscreenButton.setContentDescription(LocaleController.getString(
                fullscreen ? R.string.AccExitFullscreen : R.string.AccSwitchToFullscreen));
        autoplaySwitch.setChecked(SvipeVideoPlayerController.getInstance().isAutoplayEnabled(), false);
        if (mini) {
            // The mini bar's chrome sits BESIDE the picture, so a preview panel there would land next to
            // the video rather than over it. The countdown keeps running and simply advances unseen.
            AndroidUtilities.updateViewVisibilityAnimated(upNextView, false, .96f, false);
        }
        // The main chrome is set here outright rather than through the animated helper: the mini bar has
        // its own row, and the helper keeps its shown/hidden state in the view's tag, which has to be
        // left consistent for the next auto-hide.
        mainChrome.animate().setListener(null).cancel();
        mainChrome.setVisibility(mini ? GONE : VISIBLE);
        mainChrome.setAlpha(1f);
        mainChrome.setScaleX(1f);
        mainChrome.setScaleY(1f);
        mainChrome.setTag(mini ? null : 1);
        shown = !mini;
        if (mini) {
            // The mini row is the only handle on a video playing over another screen — it never hides.
            cancelHide();
            startTicking();
        } else {
            startTicking();
            scheduleHide();
        }
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        playDrawable.setPause(playing, true);
        miniPlayDrawable.setPause(playing, true);
        final String label = LocaleController.getString(playing ? R.string.AccActionPause : R.string.AccActionPlay);
        playButton.setContentDescription(label);
        miniPlayButton.setContentDescription(label);
        if (mode == SvipeVideoPlayerController.MODE_MINI) {
            return;   // the mini row shows play/pause permanently; there is no chrome to reveal
        }
        // A paused video keeps its chrome up: the play affordance is the only way back.
        if (!playing) {
            cancelHide();
            setChromeShown(true, true);
        } else {
            scheduleHide();
        }
    }

    /** The video changed (open, or an in-place swap to a related one) — refresh the mini row's labels. */
    public void setVideo(MessageObject mo, TLRPC.Chat chat) {
        final CharSequence caption = SvipeWideVideoCell.captionOf(mo);
        miniTitle.setText(caption != null ? caption : "");
        miniSubtitle.setText(chat != null && chat.title != null ? chat.title : "");
        seekBar.setProgress(0f);
        seekBar.setBufferedProgress(0f);
        updateProgress();
        if (mode != SvipeVideoPlayerController.MODE_MINI) {
            // A related tap or an autoplay advance swaps the video without changing the mode, so nothing
            // else re-arms the chrome: show it briefly for the new video (and restart the progress tick
            // it drives), the way starting any video does.
            show(true);
        }
    }

    // ---------------- show / hide ----------------

    public boolean isChromeShown() {
        return shown;
    }

    public void toggleChrome() {
        if (shown) {
            hide(true);
        } else {
            show(true);
        }
    }

    public void show(boolean animated) {
        setChromeShown(true, animated);
        scheduleHide();
    }

    public void hide(boolean animated) {
        cancelHide();
        setChromeShown(false, animated);
    }

    private void setChromeShown(boolean show, boolean animated) {
        if (shown == show) {
            return;
        }
        shown = show;
        AndroidUtilities.updateViewVisibilityAnimated(mainChrome, show, 1f, animated);
        if (show) {
            startTicking();
        } else {
            stopTicking();
        }
    }

    /** Restart the 3 s auto-hide. Paused, scrubbing and mini playback all suppress it. */
    public void scheduleHide() {
        cancelHide();
        if (mode == SvipeVideoPlayerController.MODE_MINI || !playing || seekBar.isDragging()) {
            return;
        }
        AndroidUtilities.runOnUIThread(hideRunnable, AUTO_HIDE_MS);
    }

    public void cancelHide() {
        AndroidUtilities.cancelRunOnUIThread(hideRunnable);
    }

    // ---------------- progress ----------------

    private void startTicking() {
        AndroidUtilities.cancelRunOnUIThread(progressTick);
        if (isAttachedToWindow()) {
            updateProgress();   // no 200 ms of stale seek bar when the chrome comes back
            AndroidUtilities.runOnUIThread(progressTick, 200);
        }
    }

    private void stopTicking() {
        AndroidUtilities.cancelRunOnUIThread(progressTick);
    }

    private void updateProgress() {
        final SvipeVideoPlayerController c = SvipeVideoPlayerController.getInstance();
        final long duration = c.getDurationMs();
        final long position = c.getPositionMs();
        if (duration > 0 && !seekBar.isDragging()) {
            seekBar.setProgress(position / (float) duration, true);
            seekBar.setBufferedProgress(Math.min(1f, c.getBufferedMs() / (float) duration));
        }
        timeText.setText(formatTime(position) + " / " + formatTime(duration));
        seekHost.invalidate();
    }

    /** Allocation-light mm:ss / hh:mm:ss, the same shape PhotoViewer's control bar uses. */
    private static String formatTime(long ms) {
        final long total = Math.max(0, ms) / 1000;
        final int h = (int) (total / 3600);
        final int m = (int) ((total / 60) % 60);
        final int s = (int) (total % 60);
        final char[] str = h > 0 ? new char[8] : new char[5];
        int i = 0;
        if (h > 0) {
            str[i++] = (char) ('0' + (h >= 100 ? 99 : h) / 10);
            str[i++] = (char) ('0' + (h >= 100 ? 99 : h) % 10);
            str[i++] = ':';
        }
        str[i++] = (char) ('0' + m / 10);
        str[i++] = (char) ('0' + m % 10);
        str[i++] = ':';
        str[i++] = (char) ('0' + s / 10);
        str[i] = (char) ('0' + s % 10);
        return new String(str);
    }

    // ---------------- brightness / volume read-out (fullscreen drags) ----------------

    /** Show the transient meter for a fullscreen vertical drag. {@code value} is 0..1. */
    public void showLevel(int kind, float value) {
        levelIndicator.set(kind, value);
        AndroidUtilities.cancelRunOnUIThread(levelIndicator.hide);
        AndroidUtilities.runOnUIThread(levelIndicator.hide, LEVEL_HIDE_MS);
    }

    // ---------------- "Up next" (autoplay) ----------------

    /**
     * Show or refresh the autoplay preview. Called once per countdown second by the controller, which
     * owns the clock — this view only paints it.
     *
     * @param nextMo  the next video's resolved message for its title and thumbnail, or null when the
     *                related row it came from has not resolved yet
     * @param total   the countdown's starting value, so the ring knows what fraction is left
     */
    public void showUpNext(MessageObject nextMo, int secondsLeft, int total) {
        cancelHide();
        upNextView.set(nextMo, secondsLeft, total);
        AndroidUtilities.updateViewVisibilityAnimated(upNextView, true, .96f, true);
    }

    public void hideUpNext() {
        if (upNextView.getTag() != null) {
            AndroidUtilities.updateViewVisibilityAnimated(upNextView, false, .96f, true);
            scheduleHide();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (shown) {
            startTicking();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopTicking();
        cancelHide();
        AndroidUtilities.cancelRunOnUIThread(levelIndicator.hide);
    }

    /**
     * Headless {@link VideoPlayerSeekBar} host: the seek bar draws itself into this view and takes its
     * touches through {@code onTouch}. Returning false when the seek bar does not claim the DOWN is
     * deliberate — the touch then falls through to the stage's gesture handler instead of being eaten.
     */
    private class SeekBarHost extends View {
        SeekBarHost(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (seekBar != null) {
                seekBar.setSize(getMeasuredWidth() - AndroidUtilities.dp(4), getMeasuredHeight());
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (seekBar != null) {
                seekBar.draw(canvas, this);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (seekBar != null
                    && seekBar.onTouch(event.getAction(), event.getX() - AndroidUtilities.dp(2), event.getY())) {
                invalidate();
                return true;
            }
            return false;
        }
    }

    /** The brightness / volume plate: an icon and a bar, centred over the video, auto-hiding. */
    private class LevelIndicator extends View {
        final Runnable hide = () -> AndroidUtilities.updateViewVisibilityAnimated(this, false, 1f, true);

        private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private Drawable icon;
        private float value;

        LevelIndicator(Context context) {
            super(context);
            plate.setColor(0xB3000000);
            track.setColor(0x4DFFFFFF);
            fill.setColor(0xFFFFFFFF);
        }

        void set(int kind, float value) {
            this.value = Math.max(0f, Math.min(1f, value));
            final int res = kind == LEVEL_VOLUME
                    ? (this.value <= 0f ? R.drawable.media_mute : R.drawable.media_unmute)
                    : (this.value < .5f ? R.drawable.msg_brightness_low : R.drawable.msg_brightness_high);
            icon = androidx.core.content.ContextCompat.getDrawable(getContext(), res).mutate();
            icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            AndroidUtilities.updateViewVisibilityAnimated(this, true, 1f, true);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getMeasuredWidth();
            final int h = getMeasuredHeight();
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, plate);
            final int iconSize = AndroidUtilities.dp(22);
            final int iconLeft = AndroidUtilities.dp(12);
            if (icon != null) {
                icon.setBounds(iconLeft, (h - iconSize) / 2, iconLeft + iconSize, (h + iconSize) / 2);
                icon.draw(canvas);
            }
            final float barLeft = iconLeft + iconSize + AndroidUtilities.dp(10);
            final float barRight = w - AndroidUtilities.dp(14);
            final float barHeight = AndroidUtilities.dp(4);
            rect.set(barLeft, (h - barHeight) / 2f, barRight, (h + barHeight) / 2f);
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, track);
            rect.right = barLeft + (barRight - barLeft) * value;
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, fill);
        }
    }

    /**
     * The autoplay toggle: a real switch, drawn rather than reusing {@code Components.Switch} because
     * that one takes THEME COLOUR KEYS, and over video the answer is always white-on-translucent
     * whatever the app's theme happens to be.
     */
    private static class AutoplaySwitch extends View {

        private static final int TRACK_W_DP = 30;
        private static final int TRACK_H_DP = 14;

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private boolean checked;
        private float progress;   // 0 = off, 1 = on
        private ValueAnimator animator;

        AutoplaySwitch(Context context) {
            super(context);
            setBackground(Theme.createSelectorDrawable(0x30ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP));
            thumb.setColor(Color.WHITE);
        }

        void setChecked(boolean checked, boolean animated) {
            if (this.checked == checked && animator == null) {
                return;
            }
            this.checked = checked;
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (!animated) {
                progress = checked ? 1f : 0f;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(progress, checked ? 1f : 0f);
            animator.setDuration(160);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = AndroidUtilities.dp(TRACK_W_DP);
            final float h = AndroidUtilities.dp(TRACK_H_DP);
            final float left = (getWidth() - w) / 2f;
            final float top = (getHeight() - h) / 2f;
            // OFF is a translucent white track, ON the accent blue — a white-on-white track would make
            // the white thumb disappear at exactly the state that matters.
            track.setColor(ColorUtils.blendARGB(0x59FFFFFF, 0xFF1A9CFF, progress));
            rect.set(left, top, left + w, top + h);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, track);
            final float radius = h / 2f - AndroidUtilities.dp(1.5f);
            final float cx = left + h / 2f + (w - h) * progress;
            canvas.drawCircle(cx, top + h / 2f, radius, thumb);
        }
    }

    /**
     * The "Up next" preview: what autoplay is about to play, and how long there is to stop it. Tapping
     * the card (or the ring) plays it now; Cancel leaves the player on the last frame.
     *
     * <p>The clock lives in the controller — this view is told the number to paint, so a rotation or a
     * mode change cannot desynchronise it from the advance itself.
     */
    private class UpNextView extends FrameLayout {

        private final BackupImageView thumb;
        private final TextView title;
        private final CountdownRing ring;

        UpNextView(Context context) {
            super(context);
            setBackgroundColor(0xB3000000);
            // Swallow taps that miss the buttons: the video underneath has ended, and toggling its
            // chrome from here would fight the decision the user is being asked to make.
            setOnClickListener(v -> {});

            final LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            addView(column, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER));

            final TextView label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            label.setTextColor(0xB3FFFFFF);
            label.setText(LocaleController.getString(R.string.SvipeVideoUpNext));
            column.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL));

            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOnClickListener(v -> SvipeVideoPlayerController.getInstance().playUpNextNow());
            column.addView(row, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

            thumb = new BackupImageView(context);
            thumb.setRoundRadius(AndroidUtilities.dp(4));
            row.addView(thumb, LayoutHelper.createLinear(76, 43, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            title.setTextColor(Color.WHITE);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setMaxWidth(AndroidUtilities.dp(180));
            row.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL));

            final LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.CENTER_VERTICAL);
            column.addView(buttons, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));

            ring = new CountdownRing(context);
            ring.setOnClickListener(v -> SvipeVideoPlayerController.getInstance().playUpNextNow());
            buttons.addView(ring, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

            final TextView cancel = new TextView(context);
            cancel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            cancel.setTypeface(AndroidUtilities.bold());
            cancel.setTextColor(Color.WHITE);
            cancel.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(7), AndroidUtilities.dp(14), AndroidUtilities.dp(7));
            cancel.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16),
                    0x33FFFFFF, 0x55FFFFFF));
            cancel.setText(LocaleController.getString(R.string.Cancel));
            cancel.setOnClickListener(v -> SvipeVideoPlayerController.getInstance().cancelUpNext());
            buttons.addView(cancel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL));
        }

        void set(MessageObject nextMo, int secondsLeft, int total) {
            ring.set(secondsLeft, total);
            final CharSequence caption = SvipeWideVideoCell.captionOf(nextMo);
            title.setText(caption != null ? caption : "");
            title.setVisibility(caption == null || caption.length() == 0 ? GONE : VISIBLE);
            if (nextMo != null) {
                SvipeWideVideoCell.bindThumb(thumb, nextMo, true);
                thumb.setVisibility(VISIBLE);
            } else {
                // Its related row has not resolved yet: the countdown still has to be honest about
                // what it is doing, so the panel shows without a picture rather than not at all.
                thumb.setVisibility(GONE);
            }
        }
    }

    /** The seconds-remaining ring: a white arc draining clockwise around the number. */
    private static class CountdownRing extends View {

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private String number = "";
        private float fraction = 1f;

        CountdownRing(Context context) {
            super(context);
            setBackground(Theme.createSelectorDrawable(0x30ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP));
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(AndroidUtilities.dp(2));
            track.setColor(0x40FFFFFF);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(AndroidUtilities.dp(2));
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(Color.WHITE);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(AndroidUtilities.bold());
            text.setTextSize(AndroidUtilities.dp(15));
        }

        void set(int secondsLeft, int total) {
            number = String.valueOf(Math.max(0, secondsLeft));
            fraction = total > 0 ? Math.max(0f, Math.min(1f, secondsLeft / (float) total)) : 0f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float inset = AndroidUtilities.dp(4);
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawArc(rect, 0, 360, false, track);
            canvas.drawArc(rect, -90, 360 * fraction, false, arc);
            final Paint.FontMetrics fm = text.getFontMetrics();
            canvas.drawText(number, getWidth() / 2f,
                    getHeight() / 2f - (fm.ascent + fm.descent) / 2f, text);
        }
    }
}
