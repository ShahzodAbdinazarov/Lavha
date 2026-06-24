package org.telegram.svipe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The in-app update prompt for the non-Play (.web/.beta) builds. This is a faithful re-creation of
 * Telegram's native {@code UpdateAppAlertDialog} bottom sheet — same layout, styling, scroll/shadow
 * behaviour and two-button footer ("Download Now" / "Remind me later") — but it is driven by Svipe's
 * own HTTP update data and triggers an HTTP download (see {@link SvipeUpdater}) instead of Telegram's
 * MTProto {@code FileLoader}. Text is Svipe-branded Uzbek (the native string is "Update Telegram").
 */
public class SvipeUpdateSheet extends BottomSheet {

    private final FrameLayout container;
    private final NestedScrollView scrollView;
    private final LinearLayout linearLayout;
    private final Drawable shadowDrawable;
    private final View shadow;

    private int scrollOffsetY;
    private final int[] location = new int[2];

    public SvipeUpdateSheet(Context context, String version, long sizeBytes, String changelog,
                            boolean canNotSkip, Runnable onDownload) {
        super(context, false);
        setCanceledOnTouchOutside(!canNotSkip);
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
                    if (!canNotSkip) dismiss();
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
                if (padding < 0) padding = 0;
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
                if (ignoreLayout) return;
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

        TextView titleView = new TextView(context);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText("Svipe yangilanishi");
        linearLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 23, 16, 23, 0));

        TextView versionView = new TextView(context);
        versionView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        versionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        String verLine = "Versiya " + version;
        if (sizeBytes > 0) verLine += " • " + AndroidUtilities.formatFileSize(sizeBytes);
        versionView.setText(verLine);
        versionView.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        linearLayout.addView(versionView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 23, 0, 23, 5));

        TextView changelogView = new TextView(context);
        changelogView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        changelogView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        changelogView.setText(TextUtils.isEmpty(changelog)
                ? "Bu versiyada yaxshilanishlar va xatolik tuzatishlari."
                : changelog);
        changelogView.setGravity(Gravity.LEFT | Gravity.TOP);
        linearLayout.addView(changelogView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 23, 15, 23, 0));

        FrameLayout.LayoutParams shadowParams = new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, AndroidUtilities.getShadowHeight(), Gravity.BOTTOM | Gravity.LEFT);
        shadowParams.bottomMargin = AndroidUtilities.dp(canNotSkip ? 80 : 130);
        shadow = new View(context);
        shadow.setBackgroundColor(Theme.getColor(Theme.key_dialogShadowLine));
        shadow.setAlpha(0.0f);
        container.addView(shadow, shadowParams);

        BottomSheetCell doneButton = new BottomSheetCell(context, false);
        doneButton.setText("Hozir yuklab olish");
        doneButton.background.setOnClickListener(v -> {
            if (onDownload != null) onDownload.run();
            dismiss();
        });
        container.addView(doneButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.LEFT | Gravity.BOTTOM, 0, 0, 0, canNotSkip ? 0 : 50));

        if (!canNotSkip) {
            BottomSheetCell laterButton = new BottomSheetCell(context, true);
            laterButton.setText("Keyinroq eslatish");
            laterButton.background.setOnClickListener(v -> dismiss());
            container.addView(laterButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.LEFT | Gravity.BOTTOM, 0, 0, 0, 0));
        }
    }

    private void updateLayout() {
        if (linearLayout.getChildCount() == 0) return;
        View child = linearLayout.getChildAt(0);
        child.getLocationInWindow(location);
        int top = location[1] - AndroidUtilities.dp(24);
        int newOffset = Math.max(top, 0);
        shadow.setAlpha(location[1] + linearLayout.getMeasuredHeight() <= container.getMeasuredHeight() - AndroidUtilities.dp(113) + containerView.getTranslationY() ? 0f : 1f);
        if (scrollOffsetY != newOffset) {
            scrollOffsetY = newOffset;
            scrollView.invalidate();
        }
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return false;
    }

    /** Telegram-style footer button (filled primary / flat secondary), matching UpdateAppAlertDialog. */
    private static class BottomSheetCell extends FrameLayout {
        final View background;
        private final TextView textView;

        BottomSheetCell(Context context, boolean flat) {
            super(context);
            background = new View(context);
            if (!flat) {
                background.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 4));
            }
            addView(background, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0, 16, flat ? 0 : 16, 16, 16));

            textView = new TextView(context);
            textView.setLines(1);
            textView.setSingleLine(true);
            textView.setGravity(Gravity.CENTER);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            if (flat) {
                textView.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            } else {
                textView.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
                textView.setTypeface(AndroidUtilities.bold());
            }
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setPadding(0, 0, 0, flat ? AndroidUtilities.dp(13) : 0);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(50), MeasureSpec.EXACTLY));
        }

        void setText(CharSequence text) {
            textView.setText(text);
        }
    }
}
