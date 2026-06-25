package org.telegram.ui;

import android.os.Bundle;

/**
 * Svipe "Search" section — the dedicated bottom-tab that replaced the old Contacts tab.
 *
 * It reuses the entire chats search stack (FragmentSearchField + SearchViewPager +
 * DialogsSearchAdapter, plus the per-result navigation and action mode) verbatim by extending
 * {@link DialogsActivity} in a permanent search-only mode: {@link #isSvipeSearchSection()} makes
 * the parent open the full search experience on load and never collapse back to a dialogs list,
 * which also hides the chats chrome (dialogs list, stories, folders, floating button).
 *
 * The Instagram-style Explore/Discover grid for the empty (no-query) state is layered on top in a
 * later phase ({@code /v1/discover} + client-side reel thumbnails); for now the empty state shows
 * the standard recent-search experience.
 */
public class SvipeSearchActivity extends DialogsActivity {

    public SvipeSearchActivity(Bundle args) {
        super(args);
    }

    @Override
    protected boolean isSvipeSearchSection() {
        return true;
    }
}
