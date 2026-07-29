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
        exploreGrid.setOnReelTapListener((items, position) -> {
            // A tapped SEARCH result is a query→click signal — log it before opening the reels player
            // (browse-grid taps carry no query, so svipeLogVideoResultClick no-ops there).
            if (exploreGrid.svipeIsSearchActive()) {
                final org.telegram.svipe.SvipeDiscover.Item ref =
                        position >= 0 && position < items.size() ? items.get(position) : null;
                svipeLogVideoResultClick(exploreGrid.svipeActiveQuery(), ref);
            }
            presentFragment(ReelsActivity.ofDiscoverSeed(items, position));
        });
        // Tapping a recent search re-runs it by writing the query back into the search field.
        exploreGrid.setOnRecentTapListener(this::svipeSetSearchText);
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
