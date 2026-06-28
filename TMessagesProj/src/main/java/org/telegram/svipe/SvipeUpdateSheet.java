package org.telegram.svipe;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The in-app update prompt for the non-Play (.web/.beta) builds.
 *
 * <p>This is Telegram's native {@code UpdateAppAlertDialog} bottom sheet, ported verbatim into the Svipe
 * package: identical layout, the {@code BottomSheetCell} footer (with the animated text-swap), the
 * scroll/shadow behaviour and the native {@code R.string.AppUpdate*} strings. Only the data SOURCE is
 * different — it is driven by Svipe's HTTP update metadata ({@link SvipeUpdater}) and the "Download Now"
 * button triggers an HTTP download instead of Telegram's MTProto {@code FileLoader.loadFile}. No sticker
 * is shown (the backend ships none), exactly as native does when {@code appUpdate.sticker == null}.
 *
 * <p>{@code canNotSkip} is accepted for call-site compatibility; like native's {@code UpdateAppAlertDialog}
 * this optional sheet always shows both buttons (forced updates would use {@code BlockingUpdateView}).
 */
public class SvipeUpdateSheet extends BottomSheet {

    private Drawable shadowDrawable;
    private NestedScrollView scrollView;
    private AnimatorSet shadowAnimation;
    private View shadow;
    private FrameLayout container;
    private LinearLayout linearLayout;

    private int scrollOffsetY;
    private int[] location = new int[2];
    private boolean animationInProgress;

    public class BottomSheetCell extends FrameLayout {

        private View background;
        private TextView[] textView = new TextView[2];
        private boolean hasBackground;

        public BottomSheetCell(Context context, boolean withoutBackground) {
            super(context);

            hasBackground = !withoutBackground;
            setBackground(null);

            background = new View(context);
            if (hasBackground) {
                background.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 4));
            }
            addView(background, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0, 16, withoutBackground ? 0 : 16, 16, 16));

            for (int a = 0; a < 2; a++) {
                textView[a] = new TextView(context);
                textView[a].setLines(1);
                textView[a].setSingleLine(true);
                textView[a].setGravity(Gravity.CENTER_HORIZONTAL);
                textView[a].setEllipsize(TextUtils.TruncateAt.END);
                textView[a].setGravity(Gravity.CENTER);
                if (hasBackground) {
                    textView[a].setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
                    textView[a].setTypeface(AndroidUtilities.bold());
                } else {
                    textView[a].setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                }
                textView[a].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                textView[a].setPadding(0, 0, 0, hasBackground ? 0 : AndroidUtilities.dp(13));
                addView(textView[a], LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
                if (a == 1) {
                    textView[a].setAlpha(0.0f);
                }
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(hasBackground ? 80 : 50), MeasureSpec.EXACTLY));
        }

        public void setText(CharSequence text, boolean animated) {
            if (!animated) {
                textView[0].setText(text);
            } else {
                textView[1].setText(text);
                animationInProgress = true;
                AnimatorSet animatorSet = new AnimatorSet();
                animatorSet.setDuration(180);
                animatorSet.setInterpolator(CubicBezierInterpolator.EASE_OUT);
                animatorSet.playTogether(
                        ObjectAnimator.ofFloat(textView[0], View.ALPHA, 1.0f, 0.0f),
                        ObjectAnimator.ofFloat(textView[0], View.TRANSLATION_Y, 0, -AndroidUtilities.dp(10)),
                        ObjectAnimator.ofFloat(textView[1], View.ALPHA, 0.0f, 1.0f),
                        ObjectAnimator.ofFloat(textView[1], View.TRANSLATION_Y, AndroidUtilities.dp(10), 0)
                );
                animatorSet.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        animationInProgress = false;
                        TextView temp = textView[0];
                        textView[0] = textView[1];
                        textView[1] = temp;
                    }
                });
                animatorSet.start();
            }
        }
    }

    public SvipeUpdateSheet(Context context, String version, long sizeBytes, String changelog,
                            boolean canNotSkip, Runnable onDownload) {
        super(context, false);
        setCanceledOnTouchOutside(false);

        setApplyTopPadding(false);
        setApplyBottomPadding(false);

        shadowDrawable = context.getResources().getDrawable(R.drawable.sheet_shadow_round).mutate();
        shadowDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogBackground), PorterDuff.Mode.MULTIPLY));

        container = new FrameLayout(context) {
            @Override
            public void setTranslationY(float translationY) {
                super.setTranslationY(translationY);
                updateLayout();
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_DOWN && scrollOffsetY != 0 && ev.getY() < scrollOffsetY) {
                    dismiss();
                    return true;
                }
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                return !isDismissed() && super.onTouchEvent(e);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                int top = (int) (scrollOffsetY - backgroundPaddingTop - getTranslationY());
                shadowDrawable.setBounds(0, top, getMeasuredWidth(), getMeasuredHeight());
                shadowDrawable.draw(canvas);
            }
        };
        container.setWillNotDraw(false);
        containerView = container;

        scrollView = new NestedScrollView(context) {

            private boolean ignoreLayout;

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int height = MeasureSpec.getSize(heightMeasureSpec);
                measureChildWithMargins(linearLayout, widthMeasureSpec, 0, heightMeasureSpec, 0);
                int contentHeight = linearLayout.getMeasuredHeight();
                int padding = (height / 5 * 2);
                int visiblePart = height - padding;
                if (contentHeight - visiblePart < AndroidUtilities.dp(90) || contentHeight < height / 2 + AndroidUtilities.dp(90)) {
                    padding = height - contentHeight;
                }
                if (padding < 0) {
                    padding = 0;
                }
                if (getPaddingTop() != padding) {
                    ignoreLayout = true;
                    setPadding(0, padding, 0, 0);
                    ignoreLayout = false;
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                updateLayout();
            }

            @Override
            public void requestLayout() {
                if (ignoreLayout) {
                    return;
                }
                super.requestLayout();
            }

            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                updateLayout();
            }
        };
        scrollView.setFillViewport(true);
        scrollView.setWillNotDraw(false);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        container.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 0, 0, 0, 130));

        linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(linearLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));

        // Svipe brand logo at the top, where Telegram's native update sticker goes (the backend ships no
        // sticker, so we hardcode the Svipe brand mark instead).
        ImageView logoView = new ImageView(context);
        logoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logoView.setImageResource(R.drawable.svipe_intro_logo);
        linearLayout.addView(logoView, LayoutHelper.createLinear(160, 160, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 17, 8, 17, 0));
        // Exactly the Svipe intro animation (IntroActivity.playSvipeLogoIntro): swipe-up + scale-in with an
        // overshoot, then play the logo's own animated-vector ("assemble") animation.
        logoView.post(() -> {
            logoView.setTranslationY(AndroidUtilities.dp(90));
            logoView.setScaleX(0.85f);
            logoView.setScaleY(0.85f);
            logoView.animate().translationY(0).scaleX(1f).scaleY(1f)
                    .setDuration(560).setInterpolator(new OvershootInterpolator(1.1f)).start();
            Drawable d = logoView.getDrawable();
            if (d instanceof Animatable) {
                ((Animatable) d).stop();
                ((Animatable) d).start();
            }
        });

        TextView textView = new TextView(context);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        textView.setSingleLine(true);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setText(LocaleController.getString(R.string.SvipeUpdateTitle));
        linearLayout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 23, 16, 23, 0));

        TextView messageTextView = new TextView(getContext());
        messageTextView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        messageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        messageTextView.setText(LocaleController.formatString(R.string.AppUpdateVersionAndSize, version, AndroidUtilities.formatFileSize(sizeBytes)));
        messageTextView.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        linearLayout.addView(messageTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 23, 0, 23, 5));

        TextView changelogTextView = new TextView(getContext());
        changelogTextView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        changelogTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        changelogTextView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        changelogTextView.setLinkTextColor(Theme.getColor(Theme.key_dialogTextLink));
        if (TextUtils.isEmpty(changelog)) {
            changelogTextView.setText(AndroidUtilities.replaceTags(LocaleController.getString(R.string.AppUpdateChangelogEmpty)));
        } else {
            changelogTextView.setText(changelog);
        }
        changelogTextView.setGravity(Gravity.LEFT | Gravity.TOP);
        linearLayout.addView(changelogTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 23, 15, 23, 0));

        FrameLayout.LayoutParams frameLayoutParams = new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, AndroidUtilities.getShadowHeight(), Gravity.BOTTOM | Gravity.LEFT);
        frameLayoutParams.bottomMargin = AndroidUtilities.dp(130);
        shadow = new View(context);
        shadow.setBackgroundColor(Theme.getColor(Theme.key_dialogShadowLine));
        shadow.setAlpha(0.0f);
        shadow.setTag(1);
        container.addView(shadow, frameLayoutParams);

        BottomSheetCell doneButton = new BottomSheetCell(context, false);
        doneButton.setText(LocaleController.getString(R.string.AppUpdateDownloadNow), false);
        doneButton.background.setOnClickListener(v -> {
            if (onDownload != null) {
                onDownload.run();
            }
            dismiss();
        });
        container.addView(doneButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.LEFT | Gravity.BOTTOM, 0, 0, 0, 50));

        BottomSheetCell scheduleButton = new BottomSheetCell(context, true);
        scheduleButton.setText(LocaleController.getString(R.string.AppUpdateRemindMeLater), false);
        scheduleButton.background.setOnClickListener(v -> dismiss());
        container.addView(scheduleButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.LEFT | Gravity.BOTTOM, 0, 0, 0, 0));
    }

    private void runShadowAnimation(final int num, final boolean show) {
        if (show && shadow.getTag() != null || !show && shadow.getTag() == null) {
            shadow.setTag(show ? null : 1);
            if (show) {
                shadow.setVisibility(View.VISIBLE);
            }
            if (shadowAnimation != null) {
                shadowAnimation.cancel();
            }
            shadowAnimation = new AnimatorSet();
            shadowAnimation.playTogether(ObjectAnimator.ofFloat(shadow, View.ALPHA, show ? 1.0f : 0.0f));
            shadowAnimation.setDuration(150);
            shadowAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (shadowAnimation != null && shadowAnimation.equals(animation)) {
                        if (!show) {
                            shadow.setVisibility(View.INVISIBLE);
                        }
                        shadowAnimation = null;
                    }
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    if (shadowAnimation != null && shadowAnimation.equals(animation)) {
                        shadowAnimation = null;
                    }
                }
            });
            shadowAnimation.start();
        }
    }

    private void updateLayout() {
        View child = linearLayout.getChildAt(0);
        child.getLocationInWindow(location);
        int top = location[1] - AndroidUtilities.dp(24);
        int newOffset = Math.max(top, 0);
        if (location[1] + linearLayout.getMeasuredHeight() <= container.getMeasuredHeight() - AndroidUtilities.dp(113) + containerView.getTranslationY()) {
            runShadowAnimation(0, false);
        } else {
            runShadowAnimation(0, true);
        }
        if (scrollOffsetY != newOffset) {
            scrollOffsetY = newOffset;
            scrollView.invalidate();
        }
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return false;
    }
}
