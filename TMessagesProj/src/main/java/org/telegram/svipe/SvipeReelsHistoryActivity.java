package org.telegram.svipe;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SvipeExploreGrid;
import org.telegram.ui.ReelsActivity;

/**
 * Settings "Reels watch history": the videos this user recently watched, newest first, as a
 * thumbnail grid. Reuses the exact Instagram-style {@link SvipeExploreGrid} the Search section uses
 * (reference -> Telegram-message -> video-thumbnail resolution, batched per channel, paged on
 * scroll), but pointed at GET /v1/reels/history via a page-loader instead of /v1/discover. Tapping a
 * cell opens the reels player seeded at that reel — identical to the Explore grid's behavior.
 */
public class SvipeReelsHistoryActivity extends BaseFragment {

    private SvipeExploreGrid grid;
    private TextView emptyView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.SvipeReelsWatchHistory));
        if (AndroidUtilities.isTablet()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = root;

        grid = new SvipeExploreGrid(context, currentAccount, true);
        grid.setFragment(this);   // the horizontal cards' ⋮ menu / share / report need a host fragment
        grid.setPageLoader((offset, limit, refresh, cb) ->
                SvipeDiscover.reelsHistory(currentAccount, offset, limit, (items, next, error) -> {
                    if (offset == 0 && error == null && emptyView != null) {
                        emptyView.setVisibility(items == null || items.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                    cb.onResult(items, next, error);
                }));
        grid.setOnReelTapListener((items, position) ->
                presentFragment(ReelsActivity.ofDiscoverSeed(items, position)));
        root.addView(grid, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        emptyView.setText(LocaleController.getString(R.string.SvipeNoWatchHistory));
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        grid.ensureLoaded();
        return fragmentView;
    }
}
