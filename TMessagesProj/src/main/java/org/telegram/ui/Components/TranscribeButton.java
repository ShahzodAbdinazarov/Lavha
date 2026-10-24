package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.StateSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

public class TranscribeButton {

    private final static int[] pressedState = new int[]{android.R.attr.state_enabled, android.R.attr.state_pressed};

    private int backgroundColor, color, iconColor, rippleColor;
    private float backgroundBack;
    private Paint backgroundPaint, strokePaint;
    private Path progressClipPath;

    private boolean drawLock;
    private final AnimatedFloat animatedDrawLock;

    private boolean loading;
    private final AnimatedFloat loadingFloat;

    private int inIconDrawableAlpha;
    private RLottieDrawable inIconDrawable;
    private int outIconDrawableAlpha;
    private RLottieDrawable outIconDrawable;

    private Drawable selectorDrawable;
    private ChatMessageCell parent;
    private SeekBarWaveform seekBar;

    private long start;
    private Rect bounds, pressBounds;
    private boolean clickedToOpen = false;

    private boolean premium;
    private boolean isOpen, shouldBeOpen;

    public TranscribeButton(ChatMessageCell parent, SeekBarWaveform seekBar) {
        start = SystemClock.elapsedRealtime();
        this.parent = parent;
        this.seekBar = seekBar;
        this.bounds = new Rect(0, 0, dp(30), dp(30));
        this.pressBounds = new Rect(this.bounds);
        this.pressBounds.inset(dp(8), dp(8));

        createIcons(org.telegram.svipe.SvipeStt.active(currentAccount()));

        this.isOpen = false;
        this.shouldBeOpen = false;
        premium = parent.getMessageObject() != null && UserConfig.getInstance(parent.getMessageObject().currentAccount).isPremium();

        loadingFloat = new AnimatedFloat(parent, 250, CubicBezierInterpolator.EASE_OUT_QUINT);
        animatedDrawLock = new AnimatedFloat(parent, 250, CubicBezierInterpolator.EASE_OUT_QUINT);
    }

    // Svipe: "→S" while our engine answers the button, Telegram's "→A" otherwise (SvipeStt).
    private boolean svipeIcon;

    private int currentAccount() {
        return parent != null && parent.getMessageObject() != null ? parent.getMessageObject().currentAccount : UserConfig.selectedAccount;
    }

    private void createIcons(boolean svipeIcon) {
        this.svipeIcon = svipeIcon;
        outIconDrawable = new RLottieDrawable(svipeIcon ? R.raw.svipe_transcribe_out_s : R.raw.transcribe_out, dp(26), dp(26));
        outIconDrawable.setCurrentFrame(0);
        outIconDrawable.setCallback(parent);
        outIconDrawable.setOnFinishCallback(() -> {
            outIconDrawable.stop();
            inIconDrawable.stop();
            isOpen = shouldBeOpen = true;
            inIconDrawable.setCurrentFrame(0);
        }, 19);
        outIconDrawable.setAllowDecodeSingleFrame(true);

        inIconDrawable = new RLottieDrawable(svipeIcon ? R.raw.svipe_transcribe_in_s : R.raw.transcribe_in, dp(26), dp(26));
        inIconDrawable.setCurrentFrame(0);
        inIconDrawable.setCallback(parent);
        inIconDrawable.setMasterParent(parent);
        inIconDrawable.setOnFinishCallback(() -> {
            inIconDrawable.stop();
            outIconDrawable.stop();
            isOpen = shouldBeOpen = false;
            outIconDrawable.setCurrentFrame(0);
        }, 19);
        inIconDrawable.setAllowDecodeSingleFrame(true);
    }

    private void applyIconColor() {
        inIconDrawable.beginApplyLayerColors();
        inIconDrawable.setLayerColor("Artboard Outlines", this.iconColor);
        inIconDrawable.commitApplyLayerColors();
        inIconDrawable.setAllowDecodeSingleFrame(true);
        inIconDrawable.updateCurrentFrame(0, false);
        inIconDrawable.setAlpha(inIconDrawableAlpha = (int) (Color.alpha(color)));
        outIconDrawable.beginApplyLayerColors();
        outIconDrawable.setLayerColor("Artboard Outlines", this.iconColor);
        outIconDrawable.commitApplyLayerColors();
        outIconDrawable.setAllowDecodeSingleFrame(true);
        outIconDrawable.updateCurrentFrame(0, false);
        outIconDrawable.setAlpha(outIconDrawableAlpha = (int) (Color.alpha(color)));
    }

    /** The engine was switched since these icons were made: remake them in the other letter. */
    private void checkEngineIcon() {
        final boolean want = org.telegram.svipe.SvipeStt.active(currentAccount());
        if (want != svipeIcon) {
            createIcons(want);
            if (color != 0) {
                applyIconColor();
            }
        }
    }

    private boolean premium() {
        return premium || org.telegram.svipe.SvipeStt.active(currentAccount());
    }

    public void setLock(boolean drawLock, boolean animated) {
        if (this.drawLock != drawLock && parent != null) {
            parent.invalidate();
        }
        this.drawLock = drawLock;
        if (!animated) {
            animatedDrawLock.set(drawLock, true);
        }
    }

    public void setLoading(boolean loading, boolean animated) {
        this.loading = loading;
        seekBar.setLoading(loading);
        if (!animated) {
            loadingFloat.set(this.loading, true);
        } else {
            if (loadingFloat.get() <= 0f) {
                start = SystemClock.elapsedRealtime();
            }
        }
        if (parent != null) {
            parent.invalidate();
        }
    }

    protected void onOpen() {}

    public void setOpen(boolean open, boolean animated) {
        if (!shouldBeOpen && open && clickedToOpen) {
            clickedToOpen = false;
            onOpen();
        }
        boolean wasShouldBeOpen = shouldBeOpen;
        shouldBeOpen = open;
        if (animated) {
            if (open && !wasShouldBeOpen) {
                isOpen = false;
                inIconDrawable.setCurrentFrame(0);
                outIconDrawable.setCurrentFrame(0);
                outIconDrawable.start();
            } else if (!open && wasShouldBeOpen) {
                isOpen = true;
                outIconDrawable.setCurrentFrame(0);
                inIconDrawable.setCurrentFrame(0);
                inIconDrawable.start();
            }
        } else {
            isOpen = open;
            inIconDrawable.stop();
            outIconDrawable.stop();
            inIconDrawable.setCurrentFrame(0);
            outIconDrawable.setCurrentFrame(0);
        }
        if (parent != null) {
            parent.invalidate();
        }
    }

    private boolean pressed = false;
    private long pressId = 0;
    // Svipe: a long press swaps the engine (S ⇄ A) for the whole app.
    private boolean longPressed;
    private final Runnable longPress = () -> {
        if (!pressed || parent == null || !org.telegram.svipe.SvipeStt.available(currentAccount())) {
            return;
        }
        longPressed = true;
        pressed = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && selectorDrawable instanceof RippleDrawable) {
            selectorDrawable.setState(StateSet.NOTHING);
        }
        try {
            parent.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Exception ignore) {}
        showEnginePicker();
    };

    /** Svipe: the two engines side by side, the current one checked; the pick holds for the whole app. */
    private void showEnginePicker() {
        final org.telegram.ui.ActionBar.BaseFragment fragment = org.telegram.ui.LaunchActivity.getLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final boolean telegram = org.telegram.svipe.SvipeStt.telegramEngine();
        final ItemOptions options = ItemOptions.makeOptions(fragment, parent)
                .addText(LocaleController.getString(R.string.SvipeSttPickTitle), 13)
                .add(LocaleController.getString(R.string.SvipeSttPickSvipe), LocaleController.getString(R.string.SvipeSttPickSvipeInfo), () -> pickEngine(false));
        if (!telegram) {
            options.putCheck();
        }
        options.add(LocaleController.getString(R.string.SvipeSttPickTelegram), LocaleController.getString(R.string.SvipeSttPickTelegramInfo), () -> pickEngine(true));
        if (telegram) {
            options.putCheck();
        }
        options.setDimAlpha(0).show();
    }

    private void pickEngine(boolean telegram) {
        if (org.telegram.svipe.SvipeStt.telegramEngine() == telegram) {
            return;
        }
        org.telegram.svipe.SvipeStt.setEngine(telegram);
        checkEngineIcon();
        if (parent != null) {
            parent.invalidate();
        }
        BulletinFactory.global().createSimpleBulletin(R.raw.transcribe,
                LocaleController.getString(telegram ? R.string.SvipeSttEngineTelegram : R.string.SvipeSttEngineSvipe)).show();
    }

    public boolean onTouch(int action, float x, float y) {
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            AndroidUtilities.cancelRunOnUIThread(longPress);
            if (longPressed) {
                longPressed = false;
                return true;
            }
            if (pressed && action == MotionEvent.ACTION_UP) {
                onTap();
                return true;
            }
            pressed = false;
            return false;
        }
        if (!pressBounds.contains((int) x, (int) y)) {
            // The finger slid off the button: that is no longer a press of it, long or short.
            AndroidUtilities.cancelRunOnUIThread(longPress);
            return false;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            pressed = true;
            longPressed = false;
            AndroidUtilities.cancelRunOnUIThread(longPress);
            AndroidUtilities.runOnUIThread(longPress, android.view.ViewConfiguration.getLongPressTimeout());
        }
        if (pressed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && selectorDrawable instanceof RippleDrawable) {
            selectorDrawable.setHotspot(x, y);
            selectorDrawable.setState(pressedState);
            parent.invalidate();
        }
        return true;
    }

    public void onTap() {
        if (parent == null) {
            return;
        }
        clickedToOpen = false;
        boolean processClick, toOpen = !shouldBeOpen;
        if (!shouldBeOpen) {
            processClick = !loading;
            if ((premium() || canTranscribeTrial(parent.getMessageObject())) && parent.getMessageObject().isSent()) {
                setLoading(true, true);
            }
        } else {
            processClick = true;
            setOpen(false, true);
            setLoading(false, true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && selectorDrawable instanceof RippleDrawable) {
            selectorDrawable.setState(StateSet.NOTHING);
            parent.invalidate();
        }
        pressed = false;
        if (processClick) {
            if (!premium() && toOpen) {
                if (canTranscribeTrial(parent.getMessageObject()) || parent.getMessageObject() != null && parent.getMessageObject().messageOwner != null && !TextUtils.isEmpty(parent.getMessageObject().messageOwner.voiceTranscription)) {
                    transcribePressed(parent.getMessageObject(), toOpen, parent.getDelegate());
                } else {
                    if (parent.getDelegate() != null) {
                        if (MessagesController.getInstance(parent.currentAccount).transcribeAudioTrialWeeklyNumber > 0) {
                            parent.getDelegate().needShowPremiumBulletin(3);
                        } else {
                            parent.getDelegate().needShowPremiumBulletin(0);
                        }
                    }
                }
            } else {
                if (toOpen) {
                    clickedToOpen = true;
                }
                transcribePressed(parent.getMessageObject(), toOpen, parent.getDelegate());
            }
        }
    }

    public void drawGradientBackground(Canvas canvas, Rect bounds, float alpha) {

    }

    public void setColor(int color, int grayColor, boolean isOut, float bgBack) {
        boolean disabled = !premium();
//        if (disabled) {
//            color = ColorUtils.blendARGB(color, grayColor, isOut ? .6f : .8f);
//            color = ColorUtils.setAlphaComponent(color, (int) (Color.alpha(color) * .6f));
//        }
        boolean newColor = this.color != color;
        this.iconColor = this.color = color;
        this.backgroundColor = ColorUtils.setAlphaComponent(color, (int) (Color.alpha(color) * 0.156f));
        this.backgroundBack = bgBack;
        this.rippleColor = Theme.blendOver(this.backgroundColor, ColorUtils.setAlphaComponent(color, (int) (Color.alpha(color) * (Theme.isCurrentThemeDark() ? .3f : .2f))));
        if (backgroundPaint == null) {
            backgroundPaint = new Paint();
        }
        backgroundPaint.setColor(this.backgroundColor);
        backgroundPaint.setAlpha((int) (backgroundPaint.getAlpha() * (1f - bgBack)));
        if (newColor || selectorDrawable == null) {
            selectorDrawable = Theme.createSimpleSelectorRoundRectDrawable(dp(8), 0, this.rippleColor);
            selectorDrawable.setCallback(parent);
        }
        if (newColor) {
            applyIconColor();
        }
        if (strokePaint == null) {
            strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
        }
        strokePaint.setColor(color);
    }

    private final FastOutSlowInInterpolator interpolator = new FastOutSlowInInterpolator();
    private Path boundsPath;
    private Path loadingPath;
    private int radius, diameter;

    private float a, b;
    public void setBounds(int x, int y, int w, int h, int r) {
        if (w != this.bounds.width() || h != this.bounds.height()) {
            a = (float) (Math.atan((w/2f-r) / (h/2f)) * 180f / Math.PI);
            b = (float) (Math.atan((w/2f) / (h/2f-r)) * 180f / Math.PI);
        }
        this.bounds.set(x, y, x + w, y + h);
        this.radius = Math.min(Math.min(w, h) / 2, r);
        this.diameter = this.radius * 2;
    }
    
    public int width() {
        return this.bounds.width();
    }

    public int height() {
        return this.bounds.height();
    }

    public void draw(Canvas canvas, float alpha) {
        this.pressBounds.set(this.bounds.left - dp(8), this.bounds.top - dp(8), this.bounds.right + dp(8), this.bounds.bottom + dp(8));
        if (boundsPath == null) {
            boundsPath = new Path();
        } else {
            boundsPath.rewind();
        }
        AndroidUtilities.rectTmp.set(this.bounds);
        boundsPath.addRoundRect(AndroidUtilities.rectTmp, this.radius, this.radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(boundsPath);
        if (backgroundBack * alpha > 0) {
            drawGradientBackground(canvas, this.bounds, backgroundBack * alpha);
        }
        if (backgroundPaint != null) {
            int wasAlpha = backgroundPaint.getAlpha();
            backgroundPaint.setAlpha((int) (wasAlpha * alpha));
            canvas.drawRect(this.bounds, backgroundPaint);
            backgroundPaint.setAlpha(wasAlpha);
        }
        if (selectorDrawable != null) {
            selectorDrawable.setBounds(bounds);
            selectorDrawable.draw(canvas);
        }
        canvas.restore();

        float loadingT = loadingFloat.set(loading ? 1f : 0f);
        if (loadingT > 0f) {
            float[] segments = getSegments((long) ((SystemClock.elapsedRealtime() - start) * .75f));

            if (progressClipPath == null) {
                progressClipPath = new Path();
            } else {
                progressClipPath.rewind();
            }
            float segmentLength = Math.max(40 * loadingT, segments[1] - segments[0]);
            float from = segments[0] + segmentLength * (1f - loadingT) * (loading ? 0f : 1f), to = from + segmentLength * loadingT;

            from = from % 360;
            to = to % 360;
            if (from < 0)
                from += 360;
            if (to < 0)
                to += 360;

            addLine(progressClipPath, bounds.centerX(), bounds.top, bounds.right - radius, bounds.top, from, to, 0, a);
            addCorner(progressClipPath, bounds.right, bounds.top, diameter, 1, from, to, a, b);
            addLine(progressClipPath, bounds.right, bounds.top + radius, bounds.right, bounds.bottom - radius, from, to, b, 180 - b);
            addCorner(progressClipPath, bounds.right, bounds.bottom, diameter, 2, from, to, 180 - b, 180 - a);
            addLine(progressClipPath, bounds.right - radius, bounds.bottom, bounds.left + radius, bounds.bottom, from, to, 180 - a, 180 + a);
            addCorner(progressClipPath, bounds.left, bounds.bottom, diameter, 3, from, to, 180 + a, 180 + b);
            addLine(progressClipPath, bounds.left, bounds.bottom - radius, bounds.left, bounds.top + radius, from, to, 180 + b, 360 - b);
            addCorner(progressClipPath, bounds.left, bounds.top, diameter, 4, from, to, 360 - b, 360 - a);
            addLine(progressClipPath, bounds.left + radius, bounds.top,  bounds.centerX(), bounds.top, from, to, 360 - a, 360);

            strokePaint.setStrokeWidth(dp(1.5f));
            int wasAlpha = strokePaint.getAlpha();
            strokePaint.setAlpha((int) (wasAlpha * alpha));
            canvas.drawPath(progressClipPath, strokePaint);
            strokePaint.setAlpha(wasAlpha);

            parent.invalidate();
        }

        checkEngineIcon();
        // Svipe: an icon animation that stopped before its last frame (its finish callback never ran)
        // froze the button halfway between "→A/→S" and "^". Settle on the state it was heading to.
        if (!isOpen && shouldBeOpen && !outIconDrawable.isRunning()) {
            isOpen = true;
            inIconDrawable.setCurrentFrame(0);
        } else if (isOpen && !shouldBeOpen && !inIconDrawable.isRunning()) {
            isOpen = false;
            outIconDrawable.setCurrentFrame(0);
        }
        canvas.save();
        canvas.translate(bounds.centerX() + dp(2 - 15), bounds.centerY() + dp(-1 - 12));
        canvas.saveLayerAlpha(0, 0, dp(26), dp(26), 0xFF, Canvas.ALL_SAVE_FLAG);
        if (isOpen) {
            inIconDrawable.setAlpha((int) (inIconDrawableAlpha * alpha));
            inIconDrawable.draw(canvas);
        } else {
            outIconDrawable.setAlpha((int) (outIconDrawableAlpha * alpha));
            outIconDrawable.draw(canvas);
        }
        drawLock(canvas);
        canvas.restore();
        canvas.restore();
    }

    private Paint clipLockPaint;
    private Paint lockPaint, lockStrokePaint;
    private float lockHandlePathDensity;
    private Path lockHandlePath;

    private void drawLock(Canvas canvas) {
        final float alpha = animatedDrawLock.set(drawLock && !isOpen && !loading);
        if (alpha <= 0) {
            return;
        }

        canvas.save();
        canvas.translate(dp(16 + 2), dp(10 + 2));
        if (clipLockPaint == null) {
            clipLockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            clipLockPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        }
        AndroidUtilities.rectTmp.set(0, -dp(.4f), dp(6.666f), dp(8.333f + .4f));
        canvas.scale(alpha, alpha, AndroidUtilities.rectTmp.centerX(), AndroidUtilities.rectTmp.centerY());
        canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(2), dp(2), clipLockPaint);
        if (lockPaint == null) {
            lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        lockPaint.setColor(iconColor);
        lockPaint.setAlpha((int) (0xFF * alpha));
        AndroidUtilities.rectTmp.set(0, dp(3.33f), dp(6.666f), dp(3.33f + 5));
        canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(1.33f), dp(1.33f), lockPaint);
        if (lockHandlePath == null || Math.abs(lockHandlePathDensity - AndroidUtilities.density) > 0.1f) {
            lockHandlePathDensity = AndroidUtilities.density;
            lockHandlePath = new Path();
            lockHandlePath.moveTo(dp(1.66f), dp(3.33f));
            lockHandlePath.lineTo(dp(1.66f), dp(2));
            AndroidUtilities.rectTmp.set(dp(1.66f), dp(0.33f), dp(1.66f + 3.33f), dp(0.33f + 3.33f));
            lockHandlePath.arcTo(AndroidUtilities.rectTmp, -180, 180, false);
            lockHandlePath.lineTo(dp(5), dp(3.33f));
        }
        if (lockStrokePaint == null) {
            lockStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            lockStrokePaint.setStyle(Paint.Style.STROKE);
        }
        lockStrokePaint.setStrokeWidth(dp(1));
        lockStrokePaint.setColor(iconColor);
        lockStrokePaint.setAlpha((int) (0xFF * alpha));
        canvas.drawPath(lockHandlePath, lockStrokePaint);
        canvas.restore();
    }

    private float[] segments;
    private float[] getSegments(long d) {
        if (segments == null) {
            segments = new float[2];
        }
        final long t = d % 5400L;
        segments[0] = 1520 * t / 5400f - 20;
        segments[1] = 1520 * t / 5400f;
        for (int i = 0; i < 4; ++i) {
            segments[1] += interpolator.getInterpolation((t - i * 1350) / 667f) * 250;
            segments[0] += interpolator.getInterpolation((t - (667 + i * 1350)) / 667f) * 250;
        }
        return segments;
    }

    private void addLine(
        Path path,
        int x1,
        int y1,
        int x2,
        int y2,
        float L, float R,
        float l, float r
    ) {
        if (x1 == x2 && y1 == y2) {
            return;
        }
        if (L > R) {
            addLine(path, x1, y1, x2, y2, (L - l) / (r - l), 1f);
            addLine(path, x1, y1, x2, y2, 0, (R - l) / (r - l));
        } else {
            addLine(path, x1, y1, x2, y2, Math.max(0, L - l) / (r - l), (Math.min(R, r) - l) / (r - l));
        }
    }

    private void addLine(
        Path path,
        int x1,
        int y1,
        int x2,
        int y2,
        float a,
        float b
    ) {
        if (x1 == x2 && y1 == y2) {
            return;
        }
        a = MathUtils.clamp(a, 0, 1);
        b = MathUtils.clamp(b, 0, 1);
        if (b - a <= 0) {
            return;
        }
        path.moveTo(
            AndroidUtilities.lerp(x1, x2, a),
            AndroidUtilities.lerp(y1, y2, a)
        );
        path.lineTo(
            AndroidUtilities.lerp(x1, x2, b),
            AndroidUtilities.lerp(y1, y2, b)
        );
    }

    private void addCorner(
        Path path,
        int x1,
        int y1,
        int d,
        int side,
        float L, float R,
        float l, float r
    ) {
        if (L > R) {
            addCorner(path, x1, y1, d, side, (L - l) / (r - l), 1f);
            addCorner(path, x1, y1, d, side, 0, (R - l) / (r - l));
        } else {
            addCorner(path, x1, y1, d, side, Math.max(0, L - l) / (r - l), (Math.min(R, r) - l) / (r - l));
        }
    }

    private void addCorner(
        Path path,
        int cx, // stands for x of corner, not center
        int cy,
        int d,
        int side,
        float a,
        float b
    ) {
        a = MathUtils.clamp(a, 0, 1);
        b = MathUtils.clamp(b, 0, 1);
        if (b - a <= 0) {
            return;
        }
        if (side == 1) { // top-right
            AndroidUtilities.rectTmp.set(cx-d,cy,cx,cy+d);
        } else if (side == 2) { // bottom-right
            AndroidUtilities.rectTmp.set(cx-d,cy-d,cx,cy);
        } else if (side == 3) { // bottom-left
            AndroidUtilities.rectTmp.set(cx,cy-d,cx+d,cy);
        } else if (side == 4) { // top-left
            AndroidUtilities.rectTmp.set(cx,cy,cx+d,cy+d);
        }
        path.addArc(AndroidUtilities.rectTmp, -180+side*90+(90*a), 90*(b-a));
    }


    public static class LoadingPointsSpan extends ImageSpan {
        private static LoadingPointsDrawable drawable;

        public LoadingPointsSpan() {
            super(drawable == null ? drawable = new LoadingPointsDrawable(Theme.chat_msgTextPaint) : drawable, ImageSpan.ALIGN_BOTTOM);
            float fontSize = Theme.chat_msgTextPaint.getTextSize() * 0.89f;
            int yoff = (int) (fontSize * 0.02f);
            getDrawable().setBounds(0, yoff, (int) fontSize, yoff + (int) (fontSize * 1.25f));
        }

        @Override
        public void updateDrawState(TextPaint textPaint) {
            float fontSize = textPaint.getTextSize() * 0.89f;
            int yoff = (int) (fontSize * 0.02f);
            getDrawable().setBounds(0, yoff, (int) fontSize, yoff + (int) (fontSize * 1.25f));
            super.updateDrawState(textPaint);
        }
    }

    private static class LoadingPointsDrawable extends Drawable {
        private RLottieDrawable lottie;
        private int lastColor;
        private Paint paint;
        private final Drawable.Callback callback = new Callback() {
            @Override
            public void invalidateDrawable(@NonNull Drawable who) {
                invalidateSelf();
            }

            @Override
            public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {

            }

            @Override
            public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {

            }
        };

        public LoadingPointsDrawable(TextPaint textPaint) {
            this.paint = textPaint;
            float fontSize = textPaint.getTextSize() * 0.89f;
            lottie = new RLottieDrawable(R.raw.dots_loading, (int) fontSize, (int) (fontSize * 1.25f));
            lottie.setCallback(callback);
            lottie.setAutoRepeat(1);
            lottie.setCurrentFrame((int) (SystemClock.elapsedRealtime() / 16f % 60f));
            lottie.setAllowDecodeSingleFrame(true);
            lottie.start();
        }

        public void setColor(int color) {
            lottie.beginApplyLayerColors();
            lottie.setLayerColor("Comp 1", color);
            lottie.commitApplyLayerColors();
            lottie.setAllowDecodeSingleFrame(true);
            lottie.updateCurrentFrame(0, false);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            int color = this.paint.getColor();
            if (color != lastColor) {
                setColor(color);
                lastColor = color;
            }
            lottie.draw(canvas);
        }

        @Override
        public void setAlpha(int i) {}
        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {}
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSPARENT;
        }
    }


    // requests logic

    private static int reqInfoHash(MessageObject messageObject) {
        if (messageObject == null) {
            return 0;
        }
        return Objects.hash(messageObject.currentAccount, messageObject.getDialogId(), messageObject.getId());
    }

    private static HashMap<Long, MessageObject> transcribeOperationsById;
    private static HashMap<Integer, MessageObject> transcribeOperationsByDialogPosition;
    private static ArrayList<Integer> videoTranscriptionsOpen;

    public static void openVideoTranscription(MessageObject messageObject) {
        if (messageObject == null || isVideoTranscriptionOpen(messageObject)) {
            return;
        }
        if (videoTranscriptionsOpen == null) {
            videoTranscriptionsOpen = new ArrayList<>(1);
        }
        videoTranscriptionsOpen.add(reqInfoHash(messageObject));
    }

    public static boolean isVideoTranscriptionOpen(MessageObject messageObject) {
        return videoTranscriptionsOpen != null && (!messageObject.isRoundVideo() || videoTranscriptionsOpen.contains(reqInfoHash(messageObject)));
    }

    public static void resetVideoTranscriptionsOpen() {
        if (videoTranscriptionsOpen != null) {
            videoTranscriptionsOpen.clear();
        }
    }

    public static boolean isTranscribing(MessageObject messageObject) {
        return (
            (transcribeOperationsByDialogPosition != null && (transcribeOperationsByDialogPosition.containsValue(messageObject) || transcribeOperationsByDialogPosition.containsKey((Integer) reqInfoHash(messageObject)))) ||
            (transcribeOperationsById != null && messageObject != null && messageObject.messageOwner != null && transcribeOperationsById.containsKey(messageObject.messageOwner.voiceTranscriptionId))
        );
    }

    private static void transcribePressed(MessageObject messageObject, boolean open, ChatMessageCell.ChatMessageCellDelegate delegate) {
        if (messageObject == null || messageObject.messageOwner == null || !messageObject.isSent()) {
            return;
        }
        int account = messageObject.currentAccount;
        final long start = SystemClock.elapsedRealtime(), minDuration = 350;
        TLRPC.InputPeer peer = MessagesController.getInstance(account).getInputPeer(messageObject.messageOwner.peer_id);
        long dialogId = DialogObject.getPeerDialogId(peer);
        int messageId = messageObject.messageOwner.id;
        if (open) {
            if (swapEngineText(messageObject)) {
                return;
            }
            if (org.telegram.svipe.SvipeStt.active(account) && messageObject.messageOwner.voiceTranscription != null
                    && messageObject.messageOwner.voiceTranscriptionId == 0 && org.telegram.svipe.SvipeStt.oursIsStale(messageObject)) {
                // Svipe: our text here was made by an older engine; the server makes it again (once).
                messageObject.messageOwner.voiceTranscription = null;
                messageObject.messageOwner.voiceTranscriptionFinal = false;
            }
            if (messageObject.messageOwner.voiceTranscription != null && messageObject.messageOwner.voiceTranscriptionFinal) {
                TranscribeButton.openVideoTranscription(messageObject);
                messageObject.messageOwner.voiceTranscriptionOpen = true;
                MessagesStorage.getInstance(account).updateMessageVoiceTranscriptionOpen(dialogId, messageId, messageObject.messageOwner);
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, messageObject, null, null, (Boolean) true, (Boolean) true);
                });
            } else {
                if (org.telegram.svipe.SvipeStt.active(account)) {
                    // Svipe: our own speech to text, for everyone. Telegram's request is the fallback,
                    // and only for Premium, which is the only account it would answer for anyway.
                    if (transcribeOperationsByDialogPosition == null) {
                        transcribeOperationsByDialogPosition = new HashMap<>();
                    }
                    transcribeOperationsByDialogPosition.put((Integer) reqInfoHash(messageObject), messageObject);
                    org.telegram.svipe.SvipeStt.transcribe(messageObject, text -> {
                        if (text == null && UserConfig.getInstance(account).isPremium()) {
                            transcribeViaTelegram(messageObject, peer, dialogId, messageId, start, minDuration, delegate);
                            return;
                        }
                        if (text == null) {
                            // Nothing stored, so the next tap tries again instead of showing an empty text.
                            if (transcribeOperationsByDialogPosition != null) {
                                transcribeOperationsByDialogPosition.remove((Integer) reqInfoHash(messageObject));
                            }
                            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, messageObject);
                            BulletinFactory.global().createErrorBulletin(LocaleController.getString(R.string.SvipeSttFailed)).show();
                            return;
                        }
                        final String finalText = text;
                        TranscribeButton.openVideoTranscription(messageObject);
                        messageObject.messageOwner.voiceTranscriptionOpen = true;
                        messageObject.messageOwner.voiceTranscriptionFinal = true;
                        // Id 0 marks the text as ours; Telegram's always carry their transcription id.
                        messageObject.messageOwner.voiceTranscriptionId = 0;
                        org.telegram.svipe.SvipeStt.keep(messageObject, true, 0, text);
                        final long duration = SystemClock.elapsedRealtime() - start;
                        AndroidUtilities.runOnUIThread(() -> finishTranscription(messageObject, 0, finalText), Math.max(0, minDuration - duration));
                    });
                    return;
                }
                transcribeViaTelegram(messageObject, peer, dialogId, messageId, start, minDuration, delegate);
            }
        } else {
            if (otherEngineText(messageObject)) {
                // Svipe: the open text is the other engine's; a tap shows this engine's instead of closing.
                transcribePressed(messageObject, true, delegate);
                return;
            }
            if (transcribeOperationsByDialogPosition != null) {
                transcribeOperationsByDialogPosition.remove((Integer) reqInfoHash(messageObject));
            }
            messageObject.messageOwner.voiceTranscriptionOpen = false;
            MessagesStorage.getInstance(account).updateMessageVoiceTranscriptionOpen(dialogId, messageId, messageObject.messageOwner);
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, messageObject, null, null, (Boolean) false, null);
            });
        }
    }

    /**
     * Svipe: the message keeps one text, but each engine has its own. When the stored text was made
     * by the other engine than the button's, put it aside and show this engine's: from what was kept
     * before, or by asking this engine (returns false, and the caller transcribes as for a new one).
     */
    /** Svipe: the stored text was made by the engine the button is not set to (id 0 is ours). */
    private static boolean otherEngineText(MessageObject messageObject) {
        final TLRPC.Message owner = messageObject.messageOwner;
        final int account = messageObject.currentAccount;
        if (owner == null || owner.voiceTranscription == null || !owner.voiceTranscriptionFinal || !org.telegram.svipe.SvipeStt.available(account)) {
            return false;
        }
        return (owner.voiceTranscriptionId == 0) != org.telegram.svipe.SvipeStt.active(account);
    }

    private static boolean swapEngineText(MessageObject messageObject) {
        if (!otherEngineText(messageObject)) {
            return false;
        }
        final TLRPC.Message owner = messageObject.messageOwner;
        final boolean ours = org.telegram.svipe.SvipeStt.active(messageObject.currentAccount);
        final boolean storedOurs = !ours;
        org.telegram.svipe.SvipeStt.keep(messageObject, storedOurs, owner.voiceTranscriptionId, owner.voiceTranscription);
        final org.telegram.svipe.SvipeStt.Kept kept = ours && org.telegram.svipe.SvipeStt.oursIsStale(messageObject)
                ? null : org.telegram.svipe.SvipeStt.kept(messageObject, ours);
        if (kept == null) {
            owner.voiceTranscription = null;
            owner.voiceTranscriptionFinal = false;
            owner.voiceTranscriptionId = 0;
            return false;
        }
        owner.voiceTranscriptionId = kept.id;
        owner.voiceTranscription = kept.text;
        owner.voiceTranscriptionOpen = true;
        TranscribeButton.openVideoTranscription(messageObject);
        finishTranscription(messageObject, kept.id, kept.text);
        return true;
    }

    private static void transcribeViaTelegram(MessageObject messageObject, TLRPC.InputPeer peer, long dialogId, int messageId, long start, long minDuration, ChatMessageCell.ChatMessageCellDelegate delegate) {
        final int account = messageObject.currentAccount;
        {
            {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("sending Transcription request, msg_id=" + messageId + " dialog_id=" + dialogId);
                }
                TLRPC.TL_messages_transcribeAudio req = new TLRPC.TL_messages_transcribeAudio();
                req.peer = peer;
                req.msg_id = messageId;
                if (transcribeOperationsByDialogPosition == null) {
                    transcribeOperationsByDialogPosition = new HashMap<>();
                }
                transcribeOperationsByDialogPosition.put((Integer) reqInfoHash(messageObject), messageObject);
                int flags = 0;
                if (!UserConfig.getInstance(account).isPremium()) {
                    flags |= ConnectionsManager.RequestFlagDoNotWaitFloodWait;
                }
                ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
                    String text;
                    long id = 0;
                    boolean isFinal = false;
                    if (res instanceof TLRPC.TL_messages_transcribedAudio) {
                        TLRPC.TL_messages_transcribedAudio r = (TLRPC.TL_messages_transcribedAudio) res;
                        text = r.text;
                        id = r.transcription_id;
                        isFinal = !r.pending;
                        if (TextUtils.isEmpty(text)) {
                            text = !isFinal ? null : "";
                        }
                        if ((r.flags & 2) != 0) {
                            MessagesController.getInstance(account).updateTranscribeAudioTrialCurrentNumber(r.trial_remains_num);
                            MessagesController.getInstance(account).updateTranscribeAudioTrialCooldownUntil(r.trial_remains_until_date);
                            AndroidUtilities.runOnUIThread(() -> {
                                if (delegate != null) {
                                    delegate.needShowPremiumBulletin(r.trial_remains_num > 0 ? 1 : 2);
                                }
                            });
                        }
                        if (transcribeOperationsById == null) {
                            transcribeOperationsById = new HashMap<>();
                        }
                        transcribeOperationsById.put(id, messageObject);
                        messageObject.messageOwner.voiceTranscriptionId = id;
                    } else {
                        if (err != null && err.text != null) {
                            if (err.text.startsWith("FLOOD_WAIT_")) {
                                MessagesController.getInstance(account).updateTranscribeAudioTrialCurrentNumber(0);
                                MessagesController.getInstance(account).updateTranscribeAudioTrialCooldownUntil(ConnectionsManager.getInstance(account).getCurrentTime() + Utilities.parseInt(err.text));
                                AndroidUtilities.runOnUIThread(() -> {
                                    if (transcribeOperationsByDialogPosition != null) {
                                        transcribeOperationsByDialogPosition.remove((Integer) reqInfoHash(messageObject));
                                    }
                                    if (delegate != null) {
                                        delegate.needShowPremiumBulletin(3);
                                    }
                                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, messageObject);
                                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateTranscriptionLock);
                                });
                                return;
                            }
                        }

                        text = "";
                        isFinal = true;
                    }
                    final String finalText = text;
                    final long finalId = id;
                    final long duration = SystemClock.elapsedRealtime() - start;
                    TranscribeButton.openVideoTranscription(messageObject);
                    messageObject.messageOwner.voiceTranscriptionOpen = true;
                    messageObject.messageOwner.voiceTranscriptionFinal = isFinal;
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("Transcription request sent, received final=" + isFinal + " id=" + finalId + " text=" + finalText);
                    }

                    MessagesStorage.getInstance(account).updateMessageVoiceTranscription(dialogId, messageId, finalText, messageObject.messageOwner);
                    if (isFinal) {
                        AndroidUtilities.runOnUIThread(() -> finishTranscription(messageObject, finalId, finalText), Math.max(0, minDuration - duration));
                    }
                }, flags);
            }
        }
    }

    public static boolean finishTranscription(MessageObject messageObject, long transcription_id, String text) {
        try {
            MessageObject messageObjectByTranscriptionId = null;
            if (transcribeOperationsById != null && transcribeOperationsById.containsKey(transcription_id)) {
                messageObjectByTranscriptionId = transcribeOperationsById.remove(transcription_id);
            }
            if (messageObject == null) {
                messageObject = messageObjectByTranscriptionId;
            }
            if (messageObject == null || messageObject.messageOwner == null) {
                return false;
            }
            final MessageObject finalMessageObject = messageObject;
            if (transcribeOperationsByDialogPosition != null) {
                transcribeOperationsByDialogPosition.remove((Integer) reqInfoHash(messageObject));
            }
            messageObject.messageOwner.voiceTranscriptionFinal = true;
            if (transcription_id != 0) {
                org.telegram.svipe.SvipeStt.keep(messageObject, false, transcription_id, text);
            }
            MessagesStorage.getInstance(messageObject.currentAccount).updateMessageVoiceTranscription(messageObject.getDialogId(), messageObject.getId(), text, messageObject.messageOwner);
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getInstance(finalMessageObject.currentAccount).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, finalMessageObject, (Long) transcription_id, (String) text, (Boolean) true, (Boolean) true);
            });
            return true;
        } catch (Exception ignore) {}
        return false;
    }

    public static void showOffTranscribe(MessageObject messageObject) {
        showOffTranscribe(messageObject, true);
    }

    public static void showOffTranscribe(MessageObject messageObject, boolean notify) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return;
        }
        final MessageObject finalMessageObject = messageObject;
        messageObject.messageOwner.voiceTranscriptionForce = true;
        MessagesStorage.getInstance(messageObject.currentAccount).updateMessageVoiceTranscriptionOpen(messageObject.getDialogId(), messageObject.getId(), messageObject.messageOwner);
        if (notify) {
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getInstance(finalMessageObject.currentAccount).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, finalMessageObject);
            });
        }
    }

    public static boolean canTranscribeTrial(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        ConnectionsManager cc = ConnectionsManager.getInstance(messageObject.currentAccount);
        MessagesController mc = MessagesController.getInstance(messageObject.currentAccount);
        if (isFreeTranscribeInChat(messageObject)) {
            return true;
        }
        if (mc.transcribeAudioTrialWeeklyNumber <= 0 || messageObject.getDuration() > mc.transcribeAudioTrialDurationMax) {
            return false;
        }
        return mc.transcribeAudioTrialCooldownUntil == 0 || cc.getCurrentTime() > mc.transcribeAudioTrialCooldownUntil || mc.transcribeAudioTrialCurrentNumber > 0;
    }

    public static boolean isFreeTranscribeInChat(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        MessagesController mc = MessagesController.getInstance(messageObject.currentAccount);
        TLRPC.Chat chat = mc.getChat(messageObject.getChatId());
        return ChatObject.isMegagroup(chat) && chat.level >= mc.groupTranscribeLevelMin;
    }

    public static int getTranscribeTrialCount(int currentAccount) {
        ConnectionsManager cc = ConnectionsManager.getInstance(currentAccount);
        MessagesController mc = MessagesController.getInstance(currentAccount);
        if (mc.transcribeAudioTrialWeeklyNumber <= 0) {
            return 0;
        }
        if (mc.transcribeAudioTrialCooldownUntil == 0 || cc.getCurrentTime() > mc.transcribeAudioTrialCooldownUntil)
            return mc.transcribeAudioTrialWeeklyNumber;
        return mc.transcribeAudioTrialCurrentNumber;
    }

    public static boolean showTranscribeLock(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        if (isFreeTranscribeInChat(messageObject)) {
            return false;
        }
        if (!TextUtils.isEmpty(messageObject.messageOwner.voiceTranscription)) {
            return false;
        }
        ConnectionsManager cc = ConnectionsManager.getInstance(messageObject.currentAccount);
        MessagesController mc = MessagesController.getInstance(messageObject.currentAccount);
        if (UserConfig.getInstance(messageObject.currentAccount).isPremium() || org.telegram.svipe.SvipeStt.active(messageObject.currentAccount)) {
            return false;
        }
        return mc.transcribeAudioTrialCooldownUntil != 0 && cc.getCurrentTime() <= mc.transcribeAudioTrialCooldownUntil && mc.transcribeAudioTrialCurrentNumber <= 0;
    }
}
