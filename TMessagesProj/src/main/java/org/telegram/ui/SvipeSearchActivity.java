package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.ui.Components.SvipeExploreGrid;

/**
 * Svipe "Search" section — the dedicated bottom-tab that replaced the old Contacts tab.
 *
 * It reuses the entire chats search stack (FragmentSearchField + SearchViewPager +
 * DialogsSearchAdapter, plus the per-result navigation and action mode) verbatim by extending
 * {@link DialogsActivity} in a permanent search-only mode: {@link #isSvipeSearchSection()} makes
 * the parent open the full search experience on load and never collapse back to a dialogs list,
 * which also hides the chats chrome (dialogs list, stories, folders, floating button).
 *
 * The Instagram-style Explore grid fills the empty (no-query) state: it loads reel references from
 * GET /v1/discover, renders client-side thumbnails, and on tap opens the reels player seeded at the
 * tapped reel. Once the user types, the grid hides and the 1:1 search results show.
 */
public class SvipeSearchActivity extends DialogsActivity {

    private SvipeExploreGrid exploreGrid;

    public SvipeSearchActivity(Bundle args) {
        super(args);
    }

    @Override
    protected boolean isSvipeSearchSection() {
        return true;
    }

    @Override
    protected View svipeCreateExploreGrid(Context context) {
        exploreGrid = new SvipeExploreGrid(context, currentAccount);
        // Needed by the horizontal cards' ⋮ menu (ItemOptions routes the popup above the bottom tabs
        // via the fragment), plus the share sheet and the report flow.
        exploreGrid.setFragment(this);
        // A film card in a category shelf opens the MovieProfile — the grid raises the tap, the
        // fragment presents, because only a fragment can present another one.
        exploreGrid.setMovieDelegate(movie -> presentFragment(new SvipeMovieActivity(movie)));
        exploreGrid.setOnReelTapListener((items, position) -> {
            final org.telegram.svipe.SvipeDiscover.Item ref =
                    position >= 0 && position < items.size() ? items.get(position) : null;
            // A tapped SEARCH result is a query→click signal — log it before opening the player
            // (browse-grid taps carry no query, so svipeLogVideoResultClick no-ops there).
            if (exploreGrid.svipeIsSearchActive()) {
                svipeLogVideoResultClick(exploreGrid.svipeActiveQuery(), ref);
            }
            // A full-width long-form card opens the YouTube-shaped watch page; a vertical short keeps
            // opening the reels player, seeded so its swipe feed continues from the tapped reel.
            if (ref != null && ref.isLongForm()) {
                presentFragment(new SvipeWatchActivity(ref));
            } else {
                presentFragment(ReelsActivity.ofDiscoverSeed(items, position));
            }
        });
        return exploreGrid;
    }

    @Override
    protected void svipeOnExploreGridVisible() {
        if (exploreGrid != null) {
            exploreGrid.ensureLoaded();
        }
    }

    /**
     * The parent {@link DialogsActivity#canParentTabsSlide} blocks horizontal bottom-tab swipes
     * whenever search mode is showing — and this section is permanently in search mode. While the
     * Explore grid (empty query) is up, re-enable the swipe so the Search tab slides to the
     * neighbouring tabs like every other tab. Once a query is typed, the search results' own
     * category pager keeps owning horizontal swipes.
     */
    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        if (exploreGrid != null && exploreGrid.getVisibility() == View.VISIBLE) {
            return true;
        }
        return super.canParentTabsSlide(ev, forward);
    }

    /**
     * Re-tapping the already-selected Search tab scrolls back to the top. While the Explore grid is
     * up (empty query) scroll the grid; once a query is typed the parent handles its own results.
     */
    @Override
    public void onParentScrollToTop() {
        if (exploreGrid != null && exploreGrid.getVisibility() == View.VISIBLE) {
            exploreGrid.scrollToTop();
            return;
        }
        super.onParentScrollToTop();
    }
}
