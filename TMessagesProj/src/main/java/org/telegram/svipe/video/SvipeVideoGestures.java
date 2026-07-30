package org.telegram.svipe.video;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.GestureDetectorFixDoubleTap;
import org.telegram.ui.Components.SeekSpeedDrawable;
import org.telegram.ui.Components.VideoForwardDrawable;

/**
 * Every touch gesture on the player surface, in one place, driven by the stage's own touch stream
 * (the overlay is a sibling of the whole fragment stack, so nothing above it competes for the drag —
 * there is no parent pager or list to yield to; the only disambiguation needed is between our own
 * gestures).
 *
 * <ul>
 *   <li>single tap → toggle the chrome (mini: restore the watch page)</li>
 *   <li>double tap on the outer thirds → ∓10 s with the ripple on that side; the MIDDLE THIRD does
 *       nothing, because that is where the vertical exit drag lives</li>
 *   <li>long press → 2× while held, 1× on release</li>
 *   <li>inline: drag up → fullscreen, drag down → mini</li>
 *   <li>fullscreen: left third = brightness, right third = volume, middle third drag down → inline</li>
 *   <li>mini: drag up → restore, drag sideways → dismiss</li>
 * </ul>
 *
 * Vertical drags are finger-driven geometry on the one overlay view ({@link SvipeVideoStage#setDrag}),
 * so nothing is re-parented, re-prepared or re-sought at any point in a transition.
 */
public class SvipeVideoGestures {

    private static final long DOUBLE_TAP_SEEK_MS = 10_000;
    /** PhotoViewer's rule: below this a ∓10 s jump is more disruptive than useful. */
    private static final long MIN_SEEKABLE_MS = 15_000;
    /** A backwards jump that would land this far before zero is a mis-tap, not a seek. */
    private static final long BACK_SEEK_TOLERANCE_MS = 9_000;
    private static final float BOOST_SPEED = 2f;
    /** Long-press hold before 2× engages; short enough to feel instant, long enough not to fire on taps. */
    private static final long LONG_PRESS_MS = 250;

    private static final int DRAG_NONE = 0;
    /** A mode transition the finger is dragging through: the stage lerps between the two rects. */
    private static final int DRAG_RECT = 1;
    private static final int DRAG_BRIGHTNESS = 2;
    private static final int DRAG_VOLUME = 3;
    /** Side-swipe on the mini bar. */
    private static final int DRAG_DISMISS = 4;

    private final SvipeVideoStage stage;
    private final SvipeVideoControls controls;
    private final GestureDetectorFixDoubleTap detector;
    private final VideoForwardDrawable forwardDrawable;
    private final SeekSpeedDrawable speedDrawable;
    private final int touchSlop;

    private VelocityTracker velocityTracker;
    private float downX, downY;
    /** The live delta of the gesture in progress; the release rules read it instead of re-deriving it. */
    private float moveDx, moveDy;
    private int dragKind = DRAG_NONE;
    private int dragTargetMode;
    private boolean dragUp;
    private float dragStartValue;
    private boolean doubleTapArmed;
    private boolean suppressDrag;
    private boolean speedBoosted;
    private float speedBeforeBoost = 1f;

    public SvipeVideoGestures(SvipeVideoStage stage, SvipeVideoControls controls) {
        this.stage = stage;
        this.controls = controls;
        this.touchSlop = ViewConfiguration.get(stage.getContext()).getScaledTouchSlop();

        forwardDrawable = new VideoForwardDrawable(false);
        forwardDrawable.setDelegate(new VideoForwardDrawable.VideoForwardDrawableDelegate() {
            @Override
            public void onAnimationEnd() {}

            @Override
            public void invalidate() {
                stage.invalidate();
            }
        });
        // isPiP=true only to suppress the "slide to change speed" hint: that hint belongs to
        // PhotoViewer's rewinder, and we deliberately do not implement drag-to-change-speed.
        speedDrawable = new SeekSpeedDrawable(stage::invalidate, true, false);
        speedDrawable.setSpeed(BOOST_SPEED, false);

        detector = new GestureDetectorFixDoubleTap(stage.getContext(), new GestureDetectorFixDoubleTap.OnGestureListener() {
            @Override
            public boolean hasDoubleTap(MotionEvent e) {
                // Arms the detector's double-tap path. When it is off, a single tap is reported
                // immediately instead of waiting out the double-tap window.
                doubleTapArmed = canDoubleTapSeek(e);
                return doubleTapArmed;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (!doubleTapArmed) {
                    onTap();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (doubleTapArmed) {
                    onTap();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                suppressDrag = true;   // the second tap's moves must not start a transition drag
                return seekByDoubleTap(e);
            }

            @Override
            public void onLongPress(MotionEvent e) {
                startSpeedBoost();
            }
        });
        detector.setLongpressDuration(LONG_PRESS_MS);
    }

    /** Drawn by the stage over the player rect: the ∓10 s ripple and the 2× badge. */
    public void draw(android.graphics.Canvas canvas, android.graphics.Rect playerRect) {
        if (forwardDrawable.isAnimating()) {
            forwardDrawable.setBounds(playerRect);
            forwardDrawable.draw(canvas);
        }
        if (speedDrawable.isShown()) {
            speedDrawable.setBounds(playerRect);
            speedDrawable.draw(canvas);
        }
    }

    // ---------------- touch ----------------

    public boolean onTouch(MotionEvent ev) {
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
            downY = ev.getY();
            moveDx = moveDy = 0;
            dragKind = DRAG_NONE;
            suppressDrag = false;
            if (velocityTracker != null) {
                velocityTracker.recycle();
            }
            velocityTracker = VelocityTracker.obtain();
        }
        if (velocityTracker != null) {
            velocityTracker.addMovement(ev);
        }
        detector.onTouchEvent(ev);
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                onMove(ev);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                onRelease(action == MotionEvent.ACTION_UP);
                break;
        }
        return true;
    }

    private void onMove(MotionEvent ev) {
        final float dx = moveDx = ev.getX() - downX;
        final float dy = moveDy = ev.getY() - downY;
        if (dragKind == DRAG_NONE) {
            if (suppressDrag || speedBoosted) {
                return;
            }
            if (Math.max(Math.abs(dx), Math.abs(dy)) < touchSlop) {
                return;
            }
            beginDrag(Math.abs(dy) > Math.abs(dx), dy < 0);
            if (dragKind == DRAG_NONE) {
                suppressDrag = true;   // this gesture is not ours; do not re-evaluate it on every move
                return;
            }
        }
        switch (dragKind) {
            case DRAG_RECT: {
                // Travel is measured along the initiating direction only, so dragging back past the
                // start collapses the transition instead of re-opening it the other way.
                final float travelled = Math.max(0, dragUp ? -dy : dy);
                stage.setDrag(dragTargetMode, Math.min(1f, travelled / stage.dragTravel()), 0, 0);
                break;
            }
            case DRAG_BRIGHTNESS: {
                final float value = clamp01(dragStartValue - dy / adjustTravel());
                applyBrightness(stage.getContext(), value);
                controls.showLevel(SvipeVideoControls.LEVEL_BRIGHTNESS, value);
                break;
            }
            case DRAG_VOLUME: {
                final float value = clamp01(dragStartValue - dy / adjustTravel());
                controls.showLevel(SvipeVideoControls.LEVEL_VOLUME, applyVolume(stage.getContext(), value));
                break;
            }
            case DRAG_DISMISS:
                stage.setDrag(SvipeVideoPlayerController.MODE_MINI, 0, dx, 0);
                break;
        }
    }

    private void beginDrag(boolean vertical, boolean up) {
        dragUp = up;
        final SvipeVideoPlayerController controller = SvipeVideoPlayerController.getInstance();
        switch (controller.getMode()) {
            case SvipeVideoPlayerController.MODE_INLINE:
                if (!vertical) {
                    return;
                }
                dragKind = DRAG_RECT;
                dragTargetMode = up ? SvipeVideoPlayerController.MODE_FULLSCREEN
                        : SvipeVideoPlayerController.MODE_MINI;
                break;
            case SvipeVideoPlayerController.MODE_FULLSCREEN:
                if (!vertical) {
                    return;
                }
                final int third = thirdOf(downX);
                if (third < 0) {
                    dragKind = DRAG_BRIGHTNESS;
                    dragStartValue = currentBrightness(stage.getContext());
                } else if (third > 0) {
                    dragKind = DRAG_VOLUME;
                    dragStartValue = currentVolume(stage.getContext());
                } else if (!up) {
                    dragKind = DRAG_RECT;
                    dragTargetMode = SvipeVideoPlayerController.MODE_INLINE;
                }
                break;
            case SvipeVideoPlayerController.MODE_MINI:
                if (vertical) {
                    if (up) {
                        dragKind = DRAG_RECT;
                        dragTargetMode = SvipeVideoPlayerController.MODE_INLINE;
                    }
                } else {
                    dragKind = DRAG_DISMISS;
                }
                break;
        }
        if (dragKind != DRAG_NONE) {
            controls.cancelHide();
            stage.disallowParentIntercept(true);
        }
    }

    private void onRelease(boolean up) {
        float velocityX = 0, velocityY = 0;
        if (velocityTracker != null) {
            try {
                velocityTracker.computeCurrentVelocity(1000);
                velocityX = velocityTracker.getXVelocity();
                velocityY = velocityTracker.getYVelocity();
            } catch (Exception ignore) {
            }
            velocityTracker.recycle();
            velocityTracker = null;
        }
        if (speedBoosted) {
            stopSpeedBoost();
        }
        final int kind = dragKind;
        dragKind = DRAG_NONE;
        if (kind != DRAG_NONE) {
            stage.disallowParentIntercept(false);
        }
        switch (kind) {
            case DRAG_RECT: {
                final float travelled = Math.max(0, dragUp ? -moveDy : moveDy);
                final float directional = dragUp ? -velocityY : velocityY;
                final boolean commit = up && (travelled >= stage.dragCommit()
                        || directional >= AndroidUtilities.dp(700));
                stage.endDrag(commit);
                if (commit) {
                    commitTransition(dragTargetMode);
                }
                break;
            }
            case DRAG_DISMISS: {
                final boolean commit = up && (Math.abs(moveDx) >= stage.getPlayerRect().width() * .5f
                        || Math.abs(velocityX) >= AndroidUtilities.dp(700));
                if (commit) {
                    stage.animateDismiss(moveDx + velocityX * .1f >= 0 ? 1 : -1);
                } else {
                    stage.endDrag(false);
                }
                break;
            }
            default:
                controls.scheduleHide();
                break;
        }
    }

    private void commitTransition(int target) {
        final SvipeVideoPlayerController controller = SvipeVideoPlayerController.getInstance();
        switch (target) {
            case SvipeVideoPlayerController.MODE_FULLSCREEN:
                controller.enterFullscreen();
                break;
            case SvipeVideoPlayerController.MODE_MINI:
                controller.minimise();
                break;
            case SvipeVideoPlayerController.MODE_INLINE:
                if (controller.getMode() == SvipeVideoPlayerController.MODE_FULLSCREEN) {
                    controller.exitFullscreen();
                } else {
                    controller.restoreFromMini();
                }
                break;
        }
    }

    private void onTap() {
        final SvipeVideoPlayerController controller = SvipeVideoPlayerController.getInstance();
        if (controller.getMode() == SvipeVideoPlayerController.MODE_MINI) {
            controller.restoreFromMini();
            return;
        }
        controls.toggleChrome();
    }

    // ---------------- double tap ∓10 s ----------------

    /** -1 left third, 0 middle, +1 right third, in the player rect's own width. */
    private int thirdOf(float stageX) {
        final android.graphics.Rect rect = stage.getPlayerRect();
        final int width = rect.width();
        if (width <= 0) {
            return 0;
        }
        final float x = stageX - rect.left;
        if (x < width / 3f) {
            return -1;
        }
        return x >= width * 2f / 3f ? 1 : 0;
    }

    private boolean canDoubleTapSeek(MotionEvent e) {
        final SvipeVideoPlayerController controller = SvipeVideoPlayerController.getInstance();
        if (controller.getMode() == SvipeVideoPlayerController.MODE_MINI || controller.getPlayer() == null) {
            return false;
        }
        final int third = thirdOf(e.getX());
        if (third == 0) {
            return false;   // the middle third is the vertical drag's, and never seeks
        }
        final long total = controller.getDurationMs();
        final long position = controller.getPositionMs();
        return total > MIN_SEEKABLE_MS && (third < 0 || total - position > DOUBLE_TAP_SEEK_MS);
    }

    private boolean seekByDoubleTap(MotionEvent e) {
        if (!canDoubleTapSeek(e)) {
            return false;
        }
        final SvipeVideoPlayerController controller = SvipeVideoPlayerController.getInstance();
        final int third = thirdOf(e.getX());
        final long total = controller.getDurationMs();
        long target = controller.getPositionMs() + (third > 0 ? DOUBLE_TAP_SEEK_MS : -DOUBLE_TAP_SEEK_MS);
        if (target > total) {
            target = total;
        } else if (target < 0) {
            if (target < -BACK_SEEK_TOLERANCE_MS) {
                return false;   // already at the very start: a jump back is a mis-tap
            }
            target = 0;
        }
        forwardDrawable.setOneShootAnimation(true);
        forwardDrawable.setLeftSide(third < 0);
        forwardDrawable.addTime(DOUBLE_TAP_SEEK_MS);
        controller.seekTo(target, false);
        stage.invalidate();
        return true;
    }

    // ---------------- long-press 2x ----------------

    private void startSpeedBoost() {
        final SvipeVideoPlayerController controller = SvipeVideoPlayerController.getInstance();
        if (speedBoosted || controller.getPlayer() == null || !controller.isPlaying()
                || controller.getMode() == SvipeVideoPlayerController.MODE_MINI) {
            return;
        }
        speedBoosted = true;
        speedBeforeBoost = controller.getPlaybackSpeed();
        controller.setPlaybackSpeed(BOOST_SPEED);
        speedDrawable.setSpeed(BOOST_SPEED, false);
        speedDrawable.setShown(true, true);
    }

    private void stopSpeedBoost() {
        speedBoosted = false;
        // Back to whatever the user had chosen, not a hardcoded 1× — the speed menu is a later step
        // but this must not silently undo it.
        SvipeVideoPlayerController.getInstance().setPlaybackSpeed(speedBeforeBoost);
        speedDrawable.setShown(false, true);
    }

    /** Called on every exit path: a boost must never survive the gesture that started it. */
    public void cancelTransientState() {
        if (speedBoosted) {
            stopSpeedBoost();
        }
        dragKind = DRAG_NONE;
    }

    // ---------------- brightness / volume ----------------

    /** How far the finger travels for a full 0→1 sweep. */
    private float adjustTravel() {
        return Math.max(AndroidUtilities.dp(120), stage.getPlayerRect().height() * .7f);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static Activity activityOf(Context context) {
        Activity activity = AndroidUtilities.findActivity(context);
        if (activity == null) {
            activity = org.telegram.ui.LaunchActivity.instance;
        }
        return activity != null && !activity.isFinishing() ? activity : null;
    }

    /**
     * Screen brightness is a WINDOW attribute, so there is exactly one of it and it MUST be handed
     * back — see {@link #releaseBrightness}. Same mechanism the story recorder's flash uses.
     *
     * Writing it is a WindowManager round-trip, so a value the screen cannot tell apart is dropped
     * rather than pushed on every touch move.
     */
    private static void applyBrightness(Context context, float value) {
        final Activity activity = activityOf(context);
        if (activity == null) {
            return;
        }
        try {
            final Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            final WindowManager.LayoutParams params = window.getAttributes();
            if (Math.abs(params.screenBrightness - value) < 1f / 255f) {
                return;
            }
            params.screenBrightness = value;
            window.setAttributes(params);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Give the screen brightness back to the system. Called from every path that leaves fullscreen —
     * including backgrounding — because an override left in place dims (or blazes) the whole app.
     */
    public static void releaseBrightness(Context context) {
        applyBrightness(context, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
    }

    private static float currentBrightness(Context context) {
        final Activity activity = activityOf(context);
        if (activity != null && activity.getWindow() != null) {
            final float override = activity.getWindow().getAttributes().screenBrightness;
            if (override >= 0) {
                return override;
            }
        }
        // No override yet: start the drag from where the system actually is, so the first pixel of
        // movement does not jump the screen.
        try {
            return Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Exception ignore) {
            return .5f;
        }
    }

    private static float currentVolume(Context context) {
        try {
            final AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            final int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            return max <= 0 ? 0 : am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
        } catch (Exception ignore) {
            return 0;
        }
    }

    /** Returns the level actually applied — the stream is quantised, so the meter must follow it. */
    private static float applyVolume(Context context, float value) {
        try {
            final AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            final int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) {
                return 0;
            }
            final int target = Math.max(0, Math.min(max, Math.round(value * max)));
            // The stream is quantised, so most touch moves land on the step it is already at.
            if (target != am.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            }
            return target / (float) max;
        } catch (Exception e) {
            FileLog.e(e);
            return value;
        }
    }

    /** The stage forwards its own detach so no boost or override outlives the surface. */
    public void onDetached(View host) {
        cancelTransientState();
        releaseBrightness(host.getContext());
    }
}
