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
        // This user does use the Video tab: from the next launch its first screen is prepared at
        // app start (see SvipeVideoWarmer — a tab you never visit should cost you nothing).
        org.telegram.svipe.SvipeVideoWarmer.markUsed(currentAccount);
        exploreGrid = new SvipeExploreGrid(context, currentAccount);
        // Needed by the horizontal cards' ⋮ menu (ItemOptions routes the popup above the bottom tabs
        // via the fragment), plus the share sheet and the report flow.
        exploreGrid.setFragment(this);
        // A show card opens its playlist channel — the grid raises the tap, the fragment presents,
        // because only a fragment can present another one. A FILM card is not here: it plays, on the
        // ordinary tap path, like every other card in the tab.
        exploreGrid.setMovieDelegate(new org.telegram.ui.Components.SvipeExploreGrid.MovieDelegate() {
            @Override
            public void onSeriesTapped(org.telegram.svipe.SvipeMovies.Series series) {
                // A show opens IN THE APP, on the watch page, with its episodes as a playlist panel.
                // It used to open t.me/<generated channel> — a design that needed a public channel
                // built per show before a card did anything, and that sent the tap out of Svipe the
                // moment it worked. The playlist is a list of references now, so there is nothing to
                // build and nothing to leave for.
                openSeries(series);
            }
        });
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
                final SvipeWatchActivity page = new SvipeWatchActivity(ref);
                // Open OUT OF the tapped card: the player grows from the picture the user pressed
                // rather than appearing from nowhere at the top of a new screen.
                final android.graphics.Rect from = new android.graphics.Rect();
                if (exploreGrid.getLastTapRect(from)) {
                    page.setOpenFromRect(from);
                }
                presentFragment(page);
            } else {
                presentFragment(ReelsActivity.ofDiscoverSeed(items, position));
            }
        });
        return exploreGrid;
    }

    /**
     * Fetch a show's episode list, then open it where the viewer left off.
     *
     * <p>The list is fetched on the tap rather than carried on the card: a card is one of thirty on
     * screen and the episodes are only wanted for the one that is pressed.
     */
    private void openSeries(org.telegram.svipe.SvipeMovies.Series series) {
        if (series == null || seriesLoading) {
            return;
        }
        seriesLoading = true;
        org.telegram.svipe.SvipeMovies.seriesDetail(currentAccount, series.id, (page, error) -> {
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                seriesLoading = false;
                if (page == null || page.isEmpty() || getParentActivity() == null) {
                    // Nothing to play: a show whose episodes all failed their reference check. Saying
                    // so beats a screen that opens onto a black player.
                    if (getParentActivity() != null) {
                        org.telegram.ui.Components.BulletinFactory.of(SvipeSearchActivity.this)
                                .createErrorBulletin(org.telegram.messenger.LocaleController.getString(
                                        org.telegram.messenger.R.string.SvipeSeriesEmpty))
                                .show();
                    }
                    return;
                }
                // A "continue watching" card names BOTH the episode and the second to open it at —
                // that is the whole point of it, and it beats the local mark, which knows only this
                // device. An ordinary shelf card falls back to what this phone remembers.
                final int at = series.resumeIndex >= 0
                        ? Math.min(series.resumeIndex, page.episodes.size() - 1)
                        : Math.min(org.telegram.svipe.SvipeSeriesProgress.lastEpisode(page.series.id),
                                   page.episodes.size() - 1);
                presentFragment(SvipeWatchActivity.ofSeries(page, at, series.resumeMs));
            });
        });
    }

    private boolean seriesLoading;

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
