package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
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
        exploreGrid.setOnReelTapListener((items, position) ->
                presentFragment(ReelsActivity.ofDiscoverSeed(items, position)));
        return exploreGrid;
    }

    @Override
    protected void svipeOnExploreGridVisible() {
        if (exploreGrid != null) {
            exploreGrid.ensureLoaded();
        }
    }
}
