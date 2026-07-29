package org.telegram.svipe;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * The dedicated Svipe settings hub — its own entry at the top of the main Settings page, holding
 * every Svipe-specific setting in one place (previously scattered inside Privacy &amp; Security).
 *
 * <p>Each row just opens the same self-contained screen it always did; this fragment only groups
 * them. The two "privacy-ish" rows (profile-photo archive visibility, deleted-message sync) show
 * their current value in the row, exactly as the Privacy page used to; the media/history rows are
 * plain and open on tap.
 */
public class SvipeSettingsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;

    // Group A: the archive / sync settings that carry a live value label.
    private int avatarRow;
    private int msgSyncRow;
    private int groupShadowRow;
    // Group B: reels/music media & history.
    private int musicHistoryRow;
    private int reelsHistoryRow;
    private int blockedChannelsRow;
    private int bottomShadowRow;
    private int rowCount;

    private void updateRows() {
        rowCount = 0;
        avatarRow = rowCount++;
        msgSyncRow = rowCount++;
        groupShadowRow = rowCount++;
        musicHistoryRow = rowCount++;
        reelsHistoryRow = rowCount++;
        blockedChannelsRow = rowCount++;
        bottomShadowRow = rowCount++;
    }

    @Override
    public boolean onFragmentCreate() {
        updateRows();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.SvipeSettingsTitle));
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

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> onRowClick(position));
        return fragmentView;
    }

    private void onRowClick(int position) {
        if (position == avatarRow) {
            presentFragment(new SvipeAvatarSettingsActivity());
        } else if (position == msgSyncRow) {
            presentFragment(new SvipeMessageSyncSettingsActivity());
        } else if (position == musicHistoryRow) {
            presentFragment(new SvipeMusicHistoryActivity());
        } else if (position == reelsHistoryRow) {
            presentFragment(new SvipeReelsHistoryActivity());
        } else if (position == blockedChannelsRow) {
            presentFragment(new SvipeBlockedChannelsActivity());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // The value rows mirror server-cached state that a child screen may have just changed.
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == avatarRow || position == msgSyncRow || position == musicHistoryRow
                    || position == reelsHistoryRow || position == blockedChannelsRow;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == groupShadowRow || position == bottomShadowRow) {
                return 1;
            }
            return 0;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 1) {
                view = new ShadowSectionCell(context);
            } else {
                view = new TextSettingsCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() != 0) {
                return;
            }
            TextSettingsCell cell = (TextSettingsCell) holder.itemView;
            if (position == avatarRow) {
                cell.setTextAndValue(LocaleController.getString(R.string.SvipeAvatarArchive),
                        SvipeAvatarSettingsActivity.visibilityLabel(SvipeConfig.getAvatarVisibility(currentAccount)), true);
            } else if (position == msgSyncRow) {
                cell.setTextAndValue(LocaleController.getString(R.string.SvipeMsgSyncTitle),
                        SvipeMessageSyncSettingsActivity.modeLabel(SvipeConfig.getMsgSyncMode(currentAccount)), false);
            } else if (position == musicHistoryRow) {
                cell.setText(LocaleController.getString(R.string.SvipeMusicListeningHistory), true);
            } else if (position == reelsHistoryRow) {
                cell.setText(LocaleController.getString(R.string.SvipeReelsWatchHistory), true);
            } else if (position == blockedChannelsRow) {
                cell.setText(LocaleController.getString(R.string.SvipeBlockedChannels), false);
            }
        }
    }
}
