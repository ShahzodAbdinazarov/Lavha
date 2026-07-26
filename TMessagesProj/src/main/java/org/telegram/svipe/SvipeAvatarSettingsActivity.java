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
 * Settings for the profile-photo archive: who may see MY archived photos, whether this device
 * contributes to the shared pool, and a way to erase everything the pool holds of me.
 *
 * <p>The visibility choice lives on the SERVER, not in prefs — it has to apply to requests coming
 * from other people's devices, which never see this phone. It can only ever RESTRICT what Telegram
 * already allows: choosing "Everyone" does not open anything up, it simply adds no restriction on top
 * of Telegram's own permission. The two switches below are the opposite — purely local, about what
 * THIS device uploads, and turning them off never touches the local capture that feeds the profile
 * "Profile Images" tab.
 */
public class SvipeAvatarSettingsActivity extends BaseFragment {

    /** Localized name of a visibility value — shared with the Privacy &amp; Security row. */
    public static String visibilityLabel(String visibility) {
        if (SvipeAvatarSync.VISIBILITY_CONTACTS.equals(visibility)) {
            return LocaleController.getString(R.string.SvipeAvatarVisibilityContacts);
        }
        if (SvipeAvatarSync.VISIBILITY_NOBODY.equals(visibility)) {
            return LocaleController.getString(R.string.SvipeAvatarVisibilityNobody);
        }
        if (SvipeAvatarSync.VISIBILITY_OFF.equals(visibility)) {
            return LocaleController.getString(R.string.SvipeAvatarVisibilityOff);
        }
        return LocaleController.getString(R.string.SvipeAvatarVisibilityEveryone);
    }

    private RecyclerListView listView;
    private ListAdapter adapter;

    private String visibility = SvipeAvatarSync.VISIBILITY_EVERYONE;
    private int archivedCount = -1;   // -1 until the server answers

    private int visibilityHeaderRow;
    private int everyoneRow;
    private int contactsRow;
    private int nobodyRow;
    private int offRow;
    private int visibilityInfoRow;
    private int syncHeaderRow;
    private int syncRow;
    private int wifiRow;
    private int syncInfoRow;
    private int deleteRow;
    private int deleteInfoRow;
    private int rowCount;

    private void updateRows() {
        rowCount = 0;
        visibilityHeaderRow = rowCount++;
        everyoneRow = rowCount++;
        contactsRow = rowCount++;
        nobodyRow = rowCount++;
        offRow = rowCount++;
        visibilityInfoRow = rowCount++;
        syncHeaderRow = rowCount++;
        syncRow = rowCount++;
        wifiRow = rowCount++;
        syncInfoRow = rowCount++;
        deleteRow = rowCount++;
        deleteInfoRow = rowCount++;
    }

    @Override
    public boolean onFragmentCreate() {
        updateRows();
        SvipeAvatarSync.loadMySettings(currentAccount, (value, archived, ok) -> {
            if (ok && value != null) {
                visibility = value;
                archivedCount = archived;
                SvipeConfig.setAvatarVisibility(currentAccount, value);
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
        actionBar.setTitle(LocaleController.getString(R.string.SvipeAvatarArchive));
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
        if (position == everyoneRow) {
            applyVisibility(SvipeAvatarSync.VISIBILITY_EVERYONE);
        } else if (position == contactsRow) {
            // Say what it costs before it happens: this is the one choice that sends anything about
            // other people to the server, and there is no way to check "is a contact" without it.
            confirm(R.string.SvipeAvatarContactsConfirm, R.string.SvipeAvatarVisibilityContacts,
                    () -> applyVisibility(SvipeAvatarSync.VISIBILITY_CONTACTS));
        } else if (position == nobodyRow) {
            applyVisibility(SvipeAvatarSync.VISIBILITY_NOBODY);
        } else if (position == offRow) {
            // Opting out is destructive: it also erases whatever the pool already holds of me.
            confirm(R.string.SvipeAvatarOptOutConfirm, R.string.SvipeAvatarOptOut,
                    () -> applyVisibility(SvipeAvatarSync.VISIBILITY_OFF));
        } else if (position == syncRow) {
            boolean on = !SvipeConfig.isAvatarSyncEnabled(currentAccount);
            SvipeConfig.setAvatarSyncEnabled(currentAccount, on);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(on);
            }
            adapter.notifyItemChanged(wifiRow);
        } else if (position == wifiRow) {
            if (!SvipeConfig.isAvatarSyncEnabled(currentAccount)) {
                return;
            }
            boolean on = !SvipeConfig.isAvatarSyncWifiOnly(currentAccount);
            SvipeConfig.setAvatarSyncWifiOnly(currentAccount, on);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(on);
            }
        } else if (position == deleteRow) {
            confirm(R.string.SvipeAvatarDeleteArchiveConfirm, R.string.SvipeAvatarDeleteArchive, () ->
                    SvipeAvatarSync.deleteMyArchive(currentAccount, (value, deleted, ok) -> {
                        if (ok) {
                            archivedCount = 0;
                            adapter.notifyDataSetChanged();
                        }
                        toast(ok ? R.string.SvipeAvatarArchiveDeleted : R.string.SvipeAvatarSettingsFailed);
                    }));
        }
    }

    private void applyVisibility(String value) {
        String previous = visibility;
        visibility = value;                       // optimistic: the radios must not lag the tap
        adapter.notifyDataSetChanged();
        SvipeAvatarSync.setMyVisibility(currentAccount, value, (applied, deleted, ok) -> {
            if (!ok) {
                visibility = previous;
                toast(R.string.SvipeAvatarSettingsFailed);
            } else {
                SvipeConfig.setAvatarVisibility(currentAccount, value);
                if (SvipeAvatarSync.VISIBILITY_OFF.equals(value)) {
                    archivedCount = 0;
                }
                if (SvipeAvatarSync.VISIBILITY_CONTACTS.equals(value)) {
                    // Only after the server accepted the setting — it refuses the list otherwise.
                    SvipeAvatarSync.uploadMyContacts(currentAccount, (v, stored, uploaded) -> {
                        if (!uploaded) {
                            toast(R.string.SvipeAvatarSettingsFailed);
                        }
                    });
                }
            }
            adapter.notifyDataSetChanged();
        });
    }

    private void confirm(int messageRes, int buttonRes, Runnable onConfirm) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.SvipeAvatarArchive));
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
            if (position == wifiRow) {
                return SvipeConfig.isAvatarSyncEnabled(currentAccount);
            }
            return position == everyoneRow || position == contactsRow || position == nobodyRow
                    || position == offRow || position == syncRow || position == deleteRow;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == visibilityHeaderRow || position == syncHeaderRow) {
                return 0;
            }
            if (position == everyoneRow || position == contactsRow || position == nobodyRow
                    || position == offRow) {
                return 1;
            }
            if (position == syncRow || position == wifiRow) {
                return 2;
            }
            if (position == deleteRow) {
                return 3;
            }
            return 4;
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
                    view = new TextCheckCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 3:
                    view = new TextSettingsCell(context);
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
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setText(LocaleController.getString(position == visibilityHeaderRow
                            ? R.string.SvipeAvatarVisibilityHeader : R.string.SvipeAvatarSyncHeader));
                    break;
                }
                case 1: {
                    RadioCell cell = (RadioCell) holder.itemView;
                    if (position == everyoneRow) {
                        cell.setText(LocaleController.getString(R.string.SvipeAvatarVisibilityEveryone),
                                SvipeAvatarSync.VISIBILITY_EVERYONE.equals(visibility), true);
                    } else if (position == contactsRow) {
                        cell.setText(LocaleController.getString(R.string.SvipeAvatarVisibilityContacts),
                                SvipeAvatarSync.VISIBILITY_CONTACTS.equals(visibility), true);
                    } else if (position == nobodyRow) {
                        cell.setText(LocaleController.getString(R.string.SvipeAvatarVisibilityNobody),
                                SvipeAvatarSync.VISIBILITY_NOBODY.equals(visibility), true);
                    } else {
                        cell.setText(LocaleController.getString(R.string.SvipeAvatarVisibilityOff),
                                SvipeAvatarSync.VISIBILITY_OFF.equals(visibility), false);
                    }
                    break;
                }
                case 2: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == syncRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.SvipeAvatarSyncEnabled),
                                SvipeConfig.isAvatarSyncEnabled(currentAccount), true);
                        cell.setEnabled(true, null);
                    } else {
                        boolean master = SvipeConfig.isAvatarSyncEnabled(currentAccount);
                        cell.setTextAndCheck(LocaleController.getString(R.string.SvipeAvatarSyncWifiOnly),
                                SvipeConfig.isAvatarSyncWifiOnly(currentAccount), false);
                        cell.setEnabled(master, null);
                    }
                    break;
                }
                case 3: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    // Show the count once we know it, so "delete" is never a leap in the dark.
                    String value = archivedCount >= 0 ? String.valueOf(archivedCount) : "";
                    cell.setTextAndValue(LocaleController.getString(R.string.SvipeAvatarDeleteArchive),
                            value, false);
                    cell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                    break;
                }
                default: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == visibilityInfoRow) {
                        cell.setText(LocaleController.getString(R.string.SvipeAvatarVisibilityInfo));
                    } else if (position == syncInfoRow) {
                        cell.setText(LocaleController.getString(R.string.SvipeAvatarSyncInfo));
                    } else {
                        cell.setText(LocaleController.getString(R.string.SvipeAvatarDeleteArchiveInfo));
                    }
                    break;
                }
            }
        }
    }
}
