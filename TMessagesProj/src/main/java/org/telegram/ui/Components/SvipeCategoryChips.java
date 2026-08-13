package org.telegram.ui.Components;

import android.content.Context;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.svipe.SvipeMovies;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * The YouTube-style category chip strip above the Video tab.
 *
 * <p>The order is decided by the CALLER and this view renders it as given — see
 * {@code SvipeExploreGrid.rebuildChipOrder}, which puts the shelves this user actually opens at the
 * front. (It used to be the server's order, untouched, with "Kino" deliberately last; the owner
 * asked for most-opened-first instead, and a strip whose second chip is the one you always tap beats
 * a taxonomy nobody scrolls.) The order is recomputed only when the tab is entered, never while the
 * strip is on screen — chips that move under a finger are worse than chips in the wrong order.
 *
 * <p>The leading "All" chip is synthetic — it is the absence of a filter, so the server never sends
 * it.
 *
 * <p>Every other chip is labelled from {@link SvipeMovies.Category#label()}, NOT from the server's
 * {@code title}: the payload carries one language (Uzbek), so the stable slug is resolved to a
 * string resource and the label follows the user's Telegram language.
 */
public class SvipeCategoryChips extends HorizontalScrollView {

    public interface Delegate {
        /** @param category null for "All" (no filter). */
        void onCategorySelected(SvipeMovies.Category category);
    }

    private static final int H_PADDING = 12;
    private static final int CHIP_H = 32;
    /** How far a finger may travel and still have meant a tap. A scroll does not stop at 16dp. */
    private static final int TAP_SLOP_DP = 16;

    private final LinearLayout row;
    private final List<SvipeMovies.Category> categories = new ArrayList<>();
    private Delegate delegate;
    private String selectedSlug;   // null = "Hammasi"
    /** The slug list this strip was last built from, so a bind can tell "same order" from "new order". */
    private String signature = "";
    private View pressed;
    private float downX;
    private float downY;
    private boolean travelled;

    public SvipeCategoryChips(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        setClipToPadding(false);
        setPadding(AndroidUtilities.dp(H_PADDING), AndroidUtilities.dp(6),
                AndroidUtilities.dp(H_PADDING), AndroidUtilities.dp(6));
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public String getSelectedSlug() {
        return selectedSlug;
    }

    /** Rebuild the strip. Safe to call repeatedly — the selection is preserved by slug, not by index. */
    public void setCategories(List<SvipeMovies.Category> list, String selected) {
        categories.clear();
        if (list != null) {
            categories.addAll(list);
        }
        selectedSlug = selected;
        signature = signatureOf(categories);
        row.removeAllViews();
        row.addView(chip(null, LocaleController.getString(R.string.SvipeVideoCategoryAll)));
        for (SvipeMovies.Category c : categories) {
            row.addView(chip(c, c.label()));
        }
    }

    /**
     * True when the strip already shows exactly this list, in this order.
     *
     * <p>The point is what it prevents: rebuilding the row on every adapter bind would throw away the
     * strip's horizontal scroll position several times a second, so a user who scrolled to "Kino"
     * would be yanked back to "All" the moment a thumbnail resolved.
     */
    public boolean shows(List<SvipeMovies.Category> list) {
        return signature.equals(signatureOf(list));
    }

    /** Move the highlight without rebuilding — the cheap half of a bind. */
    public void setSelectedSlug(String slug) {
        if (slug == null ? selectedSlug == null : slug.equals(selectedSlug)) {
            return;
        }
        selectedSlug = slug;
        for (int i = 0; i < row.getChildCount(); i++) {
            final View child = row.getChildAt(i);
            if (!(child instanceof ChipView)) {
                continue;
            }
            final String childSlug = i == 0 ? null : categories.get(i - 1).slug;
            ((ChipView) child).setSelectedChip(
                    childSlug == null ? slug == null : childSlug.equals(slug));
        }
    }

    /** Take the tap. Idempotent: re-tapping the active chip is a no-op, not a reload. */
    private void select(View view, SvipeMovies.Category category) {
        final String slug = category == null ? null : category.slug;
        if ((slug == null && selectedSlug == null)
                || (slug != null && slug.equals(selectedSlug))) {
            return;
        }
        selectedSlug = slug;
        for (int i = 0; i < row.getChildCount(); i++) {
            final View child = row.getChildAt(i);
            if (child instanceof ChipView) {
                ((ChipView) child).setSelectedChip(child == view);
            }
        }
        if (delegate != null) {
            delegate.onCategorySelected(category);
        }
    }

    /**
     * Take the tap OURSELVES, because a real finger never taps a chip cleanly.
     *
     * <p>The strip is a horizontal scroller inside a vertical list, and both ancestors claim a gesture
     * the moment it drifts past their touch slop — which every human tap does, by a few pixels. The
     * chip then gets ACTION_CANCEL and its click listener never runs: the owner tapped "All" and
     * nothing happened, over and over, while a synthetic zero-drift tap worked perfectly and hid the
     * bug. So the DOWN blocks the ancestors from stealing, and an UP that never travelled more than
     * {@link #TAP_SLOP_DP} is treated as what it plainly was — a tap on that chip.
     *
     * <p>A gesture that DOES travel is handed straight back: past the slop, a mostly-vertical drag
     * belongs to the list underneath (it is scrolling the page, not choosing a shelf) and a horizontal
     * one stays here and scrolls the strip.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final ViewGroup parent = getParent() instanceof ViewGroup ? (ViewGroup) getParent() : null;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                travelled = false;
                pressed = chipAt(ev.getX());
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                final float dx = Math.abs(ev.getX() - downX);
                final float dy = Math.abs(ev.getY() - downY);
                if (!travelled && Math.max(dx, dy) > AndroidUtilities.dp(TAP_SLOP_DP)) {
                    travelled = true;
                    if (dy > dx && parent != null) {
                        parent.requestDisallowInterceptTouchEvent(false);   // it is the page scrolling
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
                if (!travelled && pressed != null) {
                    select(pressed, categoryOf(pressed));
                }
                pressed = null;
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                pressed = null;
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** The chip under a touch x (view coordinates), or null between/outside them. */
    private View chipAt(float x) {
        final float contentX = x + getScrollX() - row.getLeft();
        for (int i = 0; i < row.getChildCount(); i++) {
            final View child = row.getChildAt(i);
            if (contentX >= child.getLeft() && contentX <= child.getRight()) {
                return child;
            }
        }
        return null;
    }

    /** The category a chip stands for — the first one is the synthetic "All", which has none. */
    private SvipeMovies.Category categoryOf(View chip) {
        final int index = row.indexOfChild(chip);
        return index <= 0 || index - 1 >= categories.size() ? null : categories.get(index - 1);
    }

    private static String signatureOf(List<SvipeMovies.Category> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(list.size() * 8);
        for (SvipeMovies.Category c : list) {
            sb.append(c.slug).append(',');
        }
        return sb.toString();
    }

    private View chip(SvipeMovies.Category category, String label) {
        final ChipView view = new ChipView(getContext());
        view.setText(label);
        view.setSelectedChip(category == null ? selectedSlug == null
                : category.slug.equals(selectedSlug));
        view.setOnClickListener(v -> select(view, category));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(CHIP_H));
        lp.rightMargin = AndroidUtilities.dp(8);
        view.setLayoutParams(lp);
        return view;
    }

    /**
     * One pill. The background is a real drawable rather than something painted in {@code onDraw} —
     * a TextView subclass that paints its own background is easy to get subtly wrong (the first
     * attempt drew nothing, leaving dark-on-dark text), and {@link Theme#createSimpleSelectorRoundRectDrawable}
     * is what the rest of the app already uses for pill buttons, ripple included.
     *
     * <p>The selected pill inverts: fill = the theme's text colour, label = the theme's background
     * colour. That is the YouTube treatment and it works in both light and dark themes without a
     * second colour set.
     */
    private static class ChipView extends TextView {
        private boolean chipSelected;

        ChipView(Context context) {
            super(context);
            setGravity(Gravity.CENTER);
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
            setTypeface(AndroidUtilities.bold());
            setSingleLine(true);
            setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
            applyStyle();
        }

        void setSelectedChip(boolean selected) {
            chipSelected = selected;
            applyStyle();
        }

        private void applyStyle() {
            final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            final int page = Theme.getColor(Theme.key_windowBackgroundWhite);
            setTextColor(chipSelected ? page : text);
            setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                    AndroidUtilities.dp(CHIP_H / 2f),
                    chipSelected ? text : ColorUtils.setAlphaComponent(text, 0x1A),
                    ColorUtils.setAlphaComponent(text, 0x33)));
            invalidate();
        }
    }
}
