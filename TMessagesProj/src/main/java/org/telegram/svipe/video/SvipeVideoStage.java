package org.telegram.svipe.video;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;

/**
 * The one and only long-form player surface: an app-level overlay added ONCE to
 * {@code LaunchActivity.frameLayout}, as a sibling of {@code bottomSheetTabsOverlay}. It is never
 * re-parented, so inline -> fullscreen -> mini are pure geometry on a single {@link TextureView}:
 * no surface handover, no black flash, no re-prepare, no seek, and the mini player surviving
 * fragment navigation is free (the fragment dies, this view does not).
 *
 * Two children carry everything: {@link #content} (the picture) and {@link #controls} (the chrome).
 * In inline and fullscreen the chrome lies OVER the picture; in mini it sits BESIDE it, which is what
 * turns the same two views into YouTube's mini bar without a third layout.
 *
 * The whole view is MATCH_PARENT, so it must consume only the touches that land on the player itself
 * ({@link #onTouchEvent}) — otherwise it would swallow the bottom tab bar and every screen underneath.
 *
 * HARD RULE: this view never reads the configuration (or compares width to height) to decide WHAT
 * state the player is in — only how big it is. The state lives in
 * {@link SvipeVideoPlayerController#getMode()}, which is the single source of truth. Deriving
 * "am I fullscreen" from the window aspect on every measure pass is precisely the bug that drops
 * Telegram's own PhotoViewer out of fullscreen when the device is turned back to portrait.
 */
public class SvipeVideoStage extends FrameLayout {

    private static final long TRANSITION_MS = 220;
    /** Not a mode: {@link #dragTarget}'s "no finger-driven transition in progress". */
    private static final int NO_DRAG = -1;

    /** Mini bar height; the picture inside it is this tall and 16:9 wide. */
    /** The floating mini card: 16:9, capped at half the screen so it never dominates the page. */
    private static final int MINI_WIDTH_DP = 190;
    private static final int MINI_MARGIN_DP = 12;
    private static final int MINI_RADIUS_DP = 12;

    private final FrameLayout content;
    private final AspectRatioFrameLayout aspect;
    private final TextureView textureView;
    private final BackupImageView cover; // video thumbnail shown until the first frame renders
    /** Failure + retry over the picture. Without it a playback failure is an indefinite black frame. */
    private final LinearLayout errorView;
    private Runnable retryAction;
    private final SvipeVideoControls controls;
    private final SvipeVideoGestures gestures;

    /** Where the watch page's 16:9 placeholder sits, in WINDOW coordinates (see {@link #setInlineRect}). */
    private final Rect inlineWindowRect = new Rect();
    private final Rect fromRect = new Rect();
    private final Rect fromChromeRect = new Rect();
    private final Rect toRect = new Rect();
    private final Rect toChromeRect = new Rect();
    private final Rect dragRect = new Rect();
    private final Rect drawRect = new Rect();
    // onInterceptTouchEvent bookkeeping: whether this gesture started on the player at all, and where.
    private boolean interceptCandidate;
    private float interceptDownX, interceptDownY;
    private final int touchSlop;
    private final Rect chromeRect = new Rect();
    private final Rect hitRect = new Rect();
    private final int[] location = new int[2];

    private float transition = 1f; // 0 = fromRect, 1 = toRect
    private ValueAnimator transitionAnimator;

    private int dragTarget = NO_DRAG;
    private float dragProgress;
    private ValueAnimator dismissAnimator;

    public SvipeVideoStage(Context context) {
        super(context);
        setVisibility(GONE);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        content = new FrameLayout(context);
        content.setBackgroundColor(0xFF000000); // letterbox bars, and the frame before the first one

        cover = new BackupImageView(context);
        cover.getImageReceiver().setAspectFit(true);
        content.addView(cover, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        aspect = new AspectRatioFrameLayout(context);
        aspect.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        textureView = new TextureView(context);
        aspect.addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        content.addView(aspect, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        errorView = new LinearLayout(context);
        errorView.setOrientation(LinearLayout.VERTICAL);
        errorView.setGravity(Gravity.CENTER);
        errorView.setBackgroundColor(0x99000000);
        errorView.setVisibility(GONE);
        final TextView errorText = new TextView(context);
        errorText.setText(LocaleController.getString(R.string.SvipeVideoPlaybackFailed));
        errorText.setTextColor(0xFFFFFFFF);
        errorText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        errorText.setGravity(Gravity.CENTER);
        errorView.addView(errorText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 0, 16, 10));
        final TextView retryButton = new TextView(context);
        retryButton.setText(LocaleController.getString(R.string.Retry));
        retryButton.setTextColor(0xFFFFFFFF);
        retryButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        retryButton.setGravity(Gravity.CENTER);
        retryButton.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(8), AndroidUtilities.dp(18), AndroidUtilities.dp(8));
        retryButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(16), 0x33FFFFFF, 0x55FFFFFF));
        retryButton.setOnClickListener(v -> {
            final Runnable r = retryAction;
            if (r != null) r.run();
        });
        errorView.addView(retryButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        content.addView(errorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        controls = new SvipeVideoControls(context);
        addView(controls, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Neither child is clickable, so a touch that misses a button or the seek bar falls through to
        // this view's onTouchEvent and becomes a gesture.
        gestures = new SvipeVideoGestures(this, controls);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        SvipeVideoPlayerController.getInstance().attachStage(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelTransition();
        if (dismissAnimator != null) {
            dismissAnimator.cancel();
            dismissAnimator = null;
        }
        gestures.onDetached(this);
        SvipeVideoPlayerController.getInstance().detachStage(this);
    }

    // ---------------- playback surface (the controller is the only caller) ----------------

    public TextureView getTextureView() {
        return textureView;
    }

    public SvipeVideoControls getControls() {
        return controls;
    }

    public SvipeVideoGestures getGestures() {
        return gestures;
    }

    /** Aspect from the document before the first frame, then corrected from the decoder. */
    public void setAspectRatio(float ratio, int rotation) {
        aspect.setAspectRatio(ratio, rotation);
    }

    /**
     * Show the poster for a video about to play. The TextureView is transparent until the decoder
     * pushes a frame, so the cover is what the user actually sees while the stream starts.
     */
    /**
     * Show ({@code retry != null}) or hide the failure affordance. The alternative — what this player
     * did before — is a black rectangle with no message, no spinner and no way to try again, which is
     * indistinguishable from "the video does not play at all".
     */
    public void showError(Runnable retry) {
        retryAction = retry;
        errorView.setVisibility(retry != null ? VISIBLE : GONE);
    }

    public void showCover(MessageObject mo) {
        showError(null);
        textureView.setAlpha(0f);
        TLRPC.Document doc = mo != null ? mo.getDocument() : null;
        TLRPC.PhotoSize thumb = doc != null ? FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 320) : null;
        if (thumb != null) {
            cover.setImage(ImageLocation.getForDocument(thumb, doc), "360_640", null, null, mo);
            cover.setVisibility(VISIBLE);
        } else {
            cover.setImageDrawable(null);
            cover.setVisibility(GONE);
        }
    }

    /** Idempotent — both onRenderedFirstFrame overloads land here through the controller. */
    public void onFirstFrame() {
        textureView.setAlpha(1f);
        cover.setVisibility(GONE);
    }

    // ---------------- geometry ----------------

    /**
     * The watch page reports its pinned 16:9 placeholder in window coordinates (the fragment owns no
     * player views at all). Window coordinates rather than the fragment's own — this overlay lives in
     * a different view tree.
     */
    public void setInlineRect(Rect windowRect) {
        if (windowRect == null || windowRect.equals(inlineWindowRect)) return;
        inlineWindowRect.set(windowRect);
        requestLayout();
    }

    /**
     * The mode changed. Nothing here decides the mode — it only re-measures for the new one, and
     * animates the rect when the change is a user-visible transition. The rect it animates FROM is
     * wherever the player currently is, including mid-drag, so letting go of a drag continues it.
     */
    public void onModeChanged(int mode, boolean animated) {
        if (mode == SvipeVideoPlayerController.MODE_CLOSED) {
            cancelTransition();
            resetDragVisuals();
            controls.hide(false);
            transition = 1f;
            setVisibility(GONE);
            return;
        }
        controls.setMode(mode);
        applyMiniCardLook(mode == SvipeVideoPlayerController.MODE_MINI);
        boolean wasVisible = getVisibility() == VISIBLE;
        setVisibility(VISIBLE);
        cancelTransition();
        if (animated && wasVisible && !drawRect.isEmpty()) {
            fromRect.set(drawRect);
            fromChromeRect.set(chromeRect);
            transition = 0f;
            transitionAnimator = ValueAnimator.ofFloat(0f, 1f);
            transitionAnimator.setDuration(TRANSITION_MS);
            transitionAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
            transitionAnimator.addUpdateListener(a -> {
                transition = (float) a.getAnimatedValue();
                requestLayout();
            });
            transitionAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    transition = 1f;
                    requestLayout();
                }
            });
            transitionAnimator.start();
        } else {
            transition = 1f;
        }
        requestLayout();
    }

    /**
     * The floating card is rounded and casts a shadow; every other mode is a plain rectangle. Applied
     * on the mode change rather than per-frame so a drag does not round and un-round the picture
     * while the finger is moving.
     */
    private void applyMiniCardLook(boolean mini) {
        if (mini) {
            final float radius = AndroidUtilities.dp(MINI_RADIUS_DP);
            content.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            content.setClipToOutline(true);
            content.setElevation(AndroidUtilities.dp(8));
            // Elevation reorders drawing, not just shadows: raising the picture alone put it OVER the
            // chrome and the pause/✕ vanished. The chrome rides one step higher so it stays on the
            // card it belongs to.
            controls.setElevation(AndroidUtilities.dp(9));
        } else {
            content.setOutlineProvider(null);
            content.setClipToOutline(false);
            content.setElevation(0);
            controls.setElevation(0);
        }
    }

    /**
     * Forwarded from {@code LaunchActivity.onConfigurationChanged} AFTER checkDisplaySize/checkLayout.
     * Recomputing the rects for the new window size is ALL this does — it must never touch the mode.
     * A rotation therefore moves the player and nothing else: playback, position and mode are
     * untouched, which is exactly what PhotoViewer gets wrong.
     */
    public void onConfigurationChanged() {
        cancelTransition();
        // A drag in flight was measured against the old window; abandon it rather than commit it
        // against rects that no longer exist.
        gestures.cancelTransientState();
        resetDragVisuals();
        transition = 1f;
        requestLayout();
    }

    private void cancelTransition() {
        if (transitionAnimator != null) {
            transitionAnimator.cancel();
            transitionAnimator = null;
        }
    }

    /** The player rect for a mode, in this view's own coordinates. */
    private void rectFor(int mode, Rect out) {
        final int w = getMeasuredWidth();
        final int h = getMeasuredHeight();
        if (mode == SvipeVideoPlayerController.MODE_FULLSCREEN) {
            // The whole window, black, letterboxed by the AspectRatioFrameLayout. It must look right
            // in portrait too: at targetSdk 36 the orientation request is only a hint (Android ignores
            // it on large displays), so fullscreen can never depend on the window being landscape.
            out.set(0, 0, w, h);
            return;
        }
        if (mode == SvipeVideoPlayerController.MODE_MINI) {
            // A floating 16:9 card in the bottom-right corner, not a bar across the screen. The bar
            // spent its width on a title nobody reads and left the video a postage stamp; this is
            // the shape YouTube uses now, and it is also the honest one — the thing being minimised
            // is a video, so what stays on screen should be the video.
            final int cardWidth = Math.min(AndroidUtilities.dp(MINI_WIDTH_DP), Math.round(w * .5f));
            final int cardHeight = Math.round(cardWidth * 9f / 16f);
            final int margin = AndroidUtilities.dp(MINI_MARGIN_DP);
            final int bottom = h - miniBottomClearance() - margin;
            out.set(w - margin - cardWidth, bottom - cardHeight, w - margin, bottom);
            return;
        }
        // Inline: exactly the watch page's placeholder. Until it reports one, a 16:9 strip under the
        // status bar so a stray open() is still visible rather than a zero-sized surface.
        if (inlineWindowRect.isEmpty()) {
            final int top = AndroidUtilities.statusBarHeight;
            out.set(0, top, w, top + (int) (w * 9f / 16f));
            return;
        }
        getLocationInWindow(location);
        out.set(inlineWindowRect);
        out.offset(-location[0], -location[1]);
    }

    /** The chrome rect: over the picture, except in mini where it is the rest of the bar. */
    private void chromeRectFor(int mode, Rect playerRect, Rect out) {
        // The mini chrome lies OVER the card now (pause and ✕ on the picture), so every mode uses
        // the player's own rect. It used to be the strip beside a thumbnail — the old bar layout.
        out.set(playerRect);
    }

    /**
     * How far above the bottom edge the mini bar has to sit. Read live rather than baked in: the
     * floating main tab bar exists only while the root screen is on top, and a docked web-app tab
     * strip has its own animated height.
     */
    private int miniBottomClearance() {
        int clearance = AndroidUtilities.navigationBarHeight;
        final LaunchActivity activity = LaunchActivity.instance;
        final INavigationLayout layout = activity != null ? activity.getActionBarLayout() : null;
        if (layout != null) {
            clearance += layout.getBottomTabsHeight(true);
            if (layout.getLastFragment() instanceof MainTabsActivity) {
                clearance += AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN);
            }
        }
        return clearance;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final int mode = SvipeVideoPlayerController.getInstance().getMode();
        rectFor(mode, toRect);
        chromeRectFor(mode, toRect, toChromeRect);
        if (dragTarget != NO_DRAG && dragProgress > 0) {
            rectFor(dragTarget, dragRect);
            AndroidUtilities.lerp(toRect, dragRect, dragProgress, drawRect);
            // The chrome keeps the CURRENT mode's relationship to the picture for the whole drag:
            // swapping which layout it wears halfway through a gesture reads as a glitch, and the mode
            // has not actually changed yet.
            chromeRectFor(mode, drawRect, chromeRect);
        } else if (transition >= 1f) {
            drawRect.set(toRect);
            chromeRect.set(toChromeRect);
        } else {
            AndroidUtilities.lerp(fromRect, toRect, transition, drawRect);
            AndroidUtilities.lerp(fromChromeRect, toChromeRect, transition, chromeRect);
        }
        content.measure(
                MeasureSpec.makeMeasureSpec(Math.max(0, drawRect.width()), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, drawRect.height()), MeasureSpec.EXACTLY));
        controls.measure(
                MeasureSpec.makeMeasureSpec(Math.max(0, chromeRect.width()), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, chromeRect.height()), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        content.layout(drawRect.left, drawRect.top, drawRect.right, drawRect.bottom);
        controls.layout(chromeRect.left, chromeRect.top, chromeRect.right, chromeRect.bottom);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        // The ∓10 s ripple and the 2x badge are drawables positioned on the picture, not views — drawn
        // last so they read over both the video and the chrome.
        gestures.draw(canvas, drawRect);
    }

    /**
     * Hold the display awake while the video plays. On the VIEW rather than the window: a view's
     * keep-screen-on is dropped the moment it detaches, so no exit path can leave a phone burning
     * its battery on a black screen.
     */
    public void setKeepScreenOn(boolean on) {
        super.setKeepScreenOn(on);
        content.setKeepScreenOn(on);
    }

    /** The player rect currently on screen, in this view's coordinates. Read-only — never mutate it. */
    public Rect getPlayerRect() {
        return drawRect;
    }

    /** The video surface container — the anchor the chrome and gesture drawables are measured against. */
    public View getContentView() {
        return content;
    }

    // ---------------- finger-driven transitions ----------------

    /** Finger travel for a full inline<->fullscreen / inline<->mini transition. */
    public float dragTravel() {
        return AndroidUtilities.dp(180);
    }

    /** Travel past which letting go commits the transition instead of springing back. */
    public float dragCommit() {
        return AndroidUtilities.dp(64);
    }

    /**
     * Claim the gesture from whatever hosts this overlay. Today that is LaunchActivity's plain root
     * frame, which intercepts nothing — this is the fork's own idiom (SvipeExploreGrid does the same
     * against the tab pager) so the player keeps its drags if it is ever re-hosted.
     */
    public void disallowParentIntercept(boolean disallow) {
        final android.view.ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    /**
     * Drive a transition from the finger. {@code progress} lerps the geometry towards
     * {@code targetMode} without touching the mode itself — the controller stays the only writer, so a
     * drag that is abandoned leaves no state behind. {@code offsetX/offsetY} are the mini bar's
     * swipe-to-dismiss displacement.
     */
    public void setDrag(int targetMode, float progress, float offsetX, float offsetY) {
        dragTarget = targetMode;
        dragProgress = Math.max(0f, Math.min(1f, progress));
        content.setTranslationX(offsetX);
        content.setTranslationY(offsetY);
        controls.setTranslationX(offsetX);
        controls.setTranslationY(offsetY);
        final int width = Math.max(1, drawRect.width());
        final float dismissed = Math.min(1f, Math.abs(offsetX) / width);
        content.setAlpha(1f - .8f * dismissed);
        // The chrome fades out of the way of a transition drag; the picture never does.
        controls.setAlpha((1f - .85f * dragProgress) * (1f - .8f * dismissed));
        requestLayout();
    }

    /**
     * A fullscreen transition drag, moving with the finger.
     *
     * The geometric lerp the mini transition uses is wrong here: the inline picture sits in a hole at
     * the top of the page, so growing it towards fullscreen moves it DOWNWARD while the finger goes
     * up — the gesture and the picture disagree, and it reads as the animation running backwards.
     * Following the finger (damped, and bounded so the picture never leaves) says "yes, this drag is
     * doing something" and lets the mode change itself play out on release.
     */
    public void setFollowDrag(float dy) {
        final float damped = dy * FOLLOW_DAMPING;
        final float max = FOLLOW_MAX_PX();
        followDrag = Math.max(-max, Math.min(max, damped));
        content.setTranslationY(followDrag);
        controls.setTranslationY(followDrag);
        content.setTranslationX(0);
        controls.setTranslationX(0);
    }

    /** A drag should hint at the transition, not perform it — the finger outruns the picture. */
    private static final float FOLLOW_DAMPING = 0.35f;

    private static float FOLLOW_MAX_PX() {
        return AndroidUtilities.dp(56);
    }

    private float followDrag;

    /**
     * The finger let go. On a commit the caller immediately asks the controller for the new mode, whose
     * animation starts from the rect the drag left behind; on a cancel the player springs back.
     */
    public void endDrag(boolean commit) {
        if (followDrag != 0) {
            // Spring the hint back either way: on a commit the mode change animates over it, on a
            // cancel this IS the way back.
            content.animate().translationY(0).setDuration(180).start();
            controls.animate().translationY(0).setDuration(180).start();
            followDrag = 0;
        }
        resetDragVisuals();
        if (!commit) {
            onModeChanged(SvipeVideoPlayerController.getInstance().getMode(), true);
        }
    }

    /** Fling the mini bar off the edge it was pushed towards, then close the player. */
    public void animateDismiss(int directionSign) {
        if (dismissAnimator != null) {
            dismissAnimator.cancel();
        }
        final float from = content.getTranslationX();
        final float to = directionSign * (float) getMeasuredWidth();
        dismissAnimator = ValueAnimator.ofFloat(from, to);
        dismissAnimator.setDuration(180);
        dismissAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        dismissAnimator.addUpdateListener(a -> setDrag(SvipeVideoPlayerController.MODE_MINI, 0,
                (float) a.getAnimatedValue(), 0));
        dismissAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                dismissAnimator = null;
                resetDragVisuals();
                SvipeVideoPlayerController.getInstance().close();
            }
        });
        dismissAnimator.start();
    }

    private void resetDragVisuals() {
        followDrag = 0f;
        dragTarget = NO_DRAG;
        dragProgress = 0f;
        content.setTranslationX(0);
        content.setTranslationY(0);
        content.setAlpha(1f);
        controls.setTranslationX(0);
        controls.setTranslationY(0);
        controls.setAlpha(1f);
        // Committing a drag normally continues into onModeChanged, which reads drawRect synchronously
        // before this lands; requesting it here is the backstop for a transition the controller
        // refuses, so the player cannot be left parked mid-drag.
        requestLayout();
    }

    // ---------------- touch ----------------

    /**
     * Claim a VERTICAL drag before the children see it.
     *
     * Without this the swipe gestures die the moment the chrome is up: {@link SvipeVideoControls} is a
     * child laid out over the whole player, so once it is visible IT takes the DOWN and this view's
     * {@link #onTouchEvent} is never called — measured on device, the tap logged a DOWN and the
     * following swipe logged nothing at all. Intercepting is the standard Android answer, and it must
     * be a DRAG (past slop, vertical-dominant) rather than the DOWN itself, or the controls' own
     * buttons — play/pause, fullscreen, the seek bar — would stop being tappable.
     *
     * The gesture pipeline still gets the DOWN, so it has its anchor point when the drag is handed
     * over: dispatch delivers ACTION_DOWN to onInterceptTouchEvent before any child, and we forward it
     * without claiming it.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (SvipeVideoPlayerController.getInstance().getMode() == SvipeVideoPlayerController.MODE_CLOSED) {
            return false;
        }
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            hitRect.set(drawRect);
            hitRect.union(chromeRect);
            interceptCandidate = hitRect.contains((int) event.getX(), (int) event.getY());
            interceptDownX = event.getX();
            interceptDownY = event.getY();
            if (interceptCandidate) {
                gestures.onTouch(event);   // give the pipeline its anchor; do not claim the stream yet
            }
            return false;
        }
        if (!interceptCandidate) {
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            final float dx = Math.abs(event.getX() - interceptDownX);
            final float dy = Math.abs(event.getY() - interceptDownY);
            if (dy > touchSlop && dy > dx) {
                return true;   // a vertical drag is ours: fullscreen / mini live here
            }
        }
        return false;
    }

    /**
     * TRAP: this overlay is MATCH_PARENT over every screen in the app. It may only consume a touch
     * that actually lands on the player, or the floating bottom tab bar — and every fragment
     * underneath — silently stops responding. Same discipline as FloatingDebugView's host.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (SvipeVideoPlayerController.getInstance().getMode() == SvipeVideoPlayerController.MODE_CLOSED) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            hitRect.set(drawRect);
            hitRect.union(chromeRect);
            if (!hitRect.contains((int) event.getX(), (int) event.getY())) {
                return false;
            }
        }
        return gestures.onTouch(event);
    }
}
