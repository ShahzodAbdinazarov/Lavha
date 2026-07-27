package org.telegram.svipe;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * Settings for the P2P deleted/edited message archive: my global sync mode, and a way to erase
 * everything the server holds that I contributed.
 *
 * <p>The mode lives on the SERVER, not in prefs — it governs uploads from this device but also what
 * the OTHER participant may receive, so both sides read it. It is one of three:
 * <em>with the other person</em> (reciprocal — you each see the other's deletions), <em>only my own
 * devices</em> (only YOUR messages are backed up, nothing of theirs), or <em>don't sync</em>. Turning
 * off is reciprocal, like Telegram's "last seen": stop sharing and you stop receiving too.
 */
public class SvipeMessageSyncSettingsActivity extends BaseFragment {

    /** Localized name of a mode value — shared with the Privacy &amp; Security row and the in-chat banner. */
    public static String modeLabel(String mode) {
        if (SvipeMessageSync.MODE_WITH_PARTNER.equals(mode)) {
            return LocaleController.getString(R.string.SvipeMsgSyncWithPartner);
        }
        if (SvipeMessageSync.MODE_SELF_ONLY.equals(mode)) {
            return LocaleController.getString(R.string.SvipeMsgSyncSelfOnly);
        }
        // Blank (never decided) reads the same as off in the row value.
        return LocaleController.getString(R.string.SvipeMsgSyncOff);
    }

    private RecyclerListView listView;
    private ListAdapter adapter;

    private String mode = SvipeMessageSync.MODE_OFF;

    private int modeHeaderRow;
    private int withPartnerRow;
    private int selfOnlyRow;
    private int offRow;
    private int modeInfoRow;
    private int remindRow;       // only when mode == off
    private int remindInfoRow;   // only when mode == off
    private int deleteRow;
    private int deleteInfoRow;
    private int rowCount;

    private void updateRows() {
        rowCount = 0;
        modeHeaderRow = rowCount++;
        withPartnerRow = rowCount++;
        selfOnlyRow = rowCount++;
        offRow = rowCount++;
        modeInfoRow = rowCount++;
        remindRow = -1;
        remindInfoRow = -1;
        if (SvipeMessageSync.MODE_OFF.equals(mode)) {   // "remind me in a month" only makes sense after opting out
            remindRow = rowCount++;
            remindInfoRow = rowCount++;
        }
        deleteRow = rowCount++;
        deleteInfoRow = rowCount++;
    }

    /** The monthly re-ask is armed when a future re-ask time is scheduled. */
    private boolean isRemindArmed() {
        return SvipeConfig.getMsgSyncNextBigAt(currentAccount) > System.currentTimeMillis();
    }

    @Override
    public boolean onFragmentCreate() {
        updateRows();
        String cached = SvipeConfig.getMsgSyncMode(currentAccount);
        mode = cached == null || cached.isEmpty() ? SvipeMessageSync.MODE_OFF : cached;
        SvipeMessageSync.loadMyMode(currentAccount, (value, deleted, ok) -> {
            if (ok && value != null) {
                mode = value.isEmpty() ? SvipeMessageSync.MODE_OFF : value;
                SvipeConfig.setMsgSyncMode(currentAccount, value);
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.SvipeMsgSyncTitle));
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
        listView.setOnItemClickListener((view, position) -> onRowClick(position, view));
        return fragmentView;
    }

    private void onRowClick(int position, View view) {
        if (position == withPartnerRow) {
            applyMode(SvipeMessageSync.MODE_WITH_PARTNER);
        } else if (position == selfOnlyRow) {
            applyMode(SvipeMessageSync.MODE_SELF_ONLY);
        } else if (position == offRow) {
            if (SvipeMessageSync.MODE_OFF.equals(mode)) {
                return;
            }
            // Reciprocity warning (the "last seen" pattern): turning off also stops you receiving.
            confirm(R.string.SvipeMsgSyncOffConfirm, R.string.SvipeMsgSyncOff,
                    () -> applyMode(SvipeMessageSync.MODE_OFF));
        } else if (position == remindRow) {
            // One-shot "remind me in a month": arm schedules the big dialog for +1 month; disarm clears it.
            boolean arm = !isRemindArmed();
            SvipeConfig.setMsgSyncNextBigAt(currentAccount,
                    arm ? System.currentTimeMillis() + SvipeMsgSyncPrompt.ONE_MONTH_MS : 0L);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(arm);
            }
        } else if (position == deleteRow) {
            confirm(R.string.SvipeMsgSyncDeleteConfirm, R.string.SvipeMsgSyncDelete, () ->
                    SvipeMessageSync.deleteMyArchive(currentAccount, (value, deleted, ok) -> {
                        if (ok) {
                            mode = SvipeMessageSync.MODE_OFF;
                            SvipeConfig.setMsgSyncMode(currentAccount, SvipeMessageSync.MODE_OFF);
                            updateRows();
                            adapter.notifyDataSetChanged();
                        }
                        toast(ok ? R.string.SvipeMsgSyncArchiveDeleted : R.string.SvipeMsgSyncFailed);
                    }));
        }
    }

    private void applyMode(String value) {
        String previous = mode;
        mode = value;                             // optimistic: the radios must not lag the tap
        SvipeConfig.setMsgSyncMode(currentAccount, value);
        updateRows();                             // show/hide the "remind me" row for off
        adapter.notifyDataSetChanged();
        SvipeMessageSync.setMyMode(currentAccount, value, (applied, deleted, ok) -> {
            if (!ok) {
                mode = previous;
                SvipeConfig.setMsgSyncMode(currentAccount, previous);
                updateRows();
                toast(R.string.SvipeMsgSyncFailed);
            }
            adapter.notifyDataSetChanged();
        });
    }

    private void confirm(int messageRes, int buttonRes, Runnable onConfirm) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.SvipeMsgSyncTitle));
        builder.setMessage(LocaleController.getString(messageRes));
        builder.setPositiveButton(LocaleController.getString(buttonRes), (dialog, which) -> onConfirm.run());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void toast(int res) {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(res), Toast.LENGTH_SHORT).show();
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
            return position == withPartnerRow || position == selfOnlyRow || position == offRow
                    || position == remindRow || position == deleteRow;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == modeHeaderRow) {
                return 0;
            }
            if (position == withPartnerRow || position == selfOnlyRow || position == offRow) {
                return 1;
            }
            if (position == deleteRow) {
                return 2;
            }
            if (position == remindRow) {
                return 4;
            }
            return 3;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 1:
                    view = new RadioCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 2:
                    view = new TextSettingsCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 4:
                    view = new TextCheckCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                default:
                    view = new TextInfoPrivacyCell(context);
                    view.setBackground(Theme.getThemedDrawable(context, R.drawable.greydivider,
                            Theme.key_windowBackgroundGrayShadow));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0: {
                    ((HeaderCell) holder.itemView).setText(
                            LocaleController.getString(R.string.SvipeMsgSyncModeHeader));
                    break;
                }
                case 1: {
                    RadioCell cell = (RadioCell) holder.itemView;
                    if (position == withPartnerRow) {
                        cell.setText(LocaleController.getString(R.string.SvipeMsgSyncWithPartner),
                                SvipeMessageSync.MODE_WITH_PARTNER.equals(mode), true);
                    } else if (position == selfOnlyRow) {
                        cell.setText(LocaleController.getString(R.string.SvipeMsgSyncSelfOnly),
                                SvipeMessageSync.MODE_SELF_ONLY.equals(mode), true);
                    } else {
                        cell.setText(LocaleController.getString(R.string.SvipeMsgSyncOff),
                                SvipeMessageSync.MODE_OFF.equals(mode), false);
                    }
                    break;
                }
                case 2: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.SvipeMsgSyncDelete), false);
                    cell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                    break;
                }
                case 4: {
                    ((TextCheckCell) holder.itemView).setTextAndCheck(
                            LocaleController.getString(R.string.SvipeMsgSyncRemind), isRemindArmed(), true);
                    break;
                }
                default: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    int res = R.string.SvipeMsgSyncDeleteInfo;
                    if (position == modeInfoRow) {
                        res = R.string.SvipeMsgSyncModeInfo;
                    } else if (position == remindInfoRow) {
                        res = R.string.SvipeMsgSyncRemindInfo;
                    }
                    cell.setText(LocaleController.getString(res));
                    break;
                }
            }
        }
    }
}
