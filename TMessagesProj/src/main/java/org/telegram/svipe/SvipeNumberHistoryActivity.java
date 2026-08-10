package org.telegram.svipe;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * The settings for number history: whether this device contributes, and who may read mine.
 *
 * The history ITSELF is not here and never was meant to be. It belongs where the question gets
 * asked — on a person's profile, as the "Old profiles" and "Old numbers" tabs — not on a list of
 * strangers in a settings menu. What is left here is the pair of choices that are settings by
 * nature, the same two the profile photo archive keeps in the same place.
 */
public class SvipeNumberHistoryActivity extends BaseFragment {

    private static final int ROW_SHARE = 0;       // contribute what this device saw
    private static final int ROW_VISIBILITY = 1;  // who may read MY history
    private static final int ROW_INFO = 2;

    private static class Row {
        final int type;
        final String text;
        final String detail;

        Row(int type, String text, String detail) {
            this.type = type;
            this.text = text;
            this.detail = detail;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;

    @Override
    public boolean onFragmentCreate() {
        buildRows();
        // The server owns the visibility; the cached value only keeps the row from blanking.
        SvipeNumberSync.loadMySettings(currentAccount, (visibility, count, ok) -> {
            if (ok) {
                refresh();
            }
        });
        return super.onFragmentCreate();
    }

    private void buildRows() {
        rows.clear();
        rows.add(new Row(ROW_SHARE, LocaleController.getString(R.string.SvipeNumberSyncShare), null));
        rows.add(new Row(ROW_VISIBILITY, LocaleController.getString(R.string.SvipeNumberVisibility),
                visibilityLabel(SvipeConfig.getNumberVisibility(currentAccount))));
        rows.add(new Row(ROW_INFO, LocaleController.getString(R.string.SvipeNumberSyncShareInfo), null));
    }

    private void refresh() {
        buildRows();
        if (listView != null && listView.getAdapter() != null) {
            listView.getAdapter().notifyDataSetChanged();
        }
    }

    /** The options, in Telegram's own words — this is Telegram's own kind of choice. */
    public static String visibilityLabel(String value) {
        if ("contacts".equals(value)) {
            return LocaleController.getString(R.string.LastSeenContacts);
        }
        if ("nobody".equals(value)) {
            return LocaleController.getString(R.string.LastSeenNobody);
        }
        if ("off".equals(value)) {
            return LocaleController.getString(R.string.SvipeNumberVisibilityOff);
        }
        return LocaleController.getString(R.string.LastSeenEverybody);
    }

    private void pickVisibility() {
        final String[] values = {"everyone", "contacts", "nobody", "off"};
        final String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = visibilityLabel(values[i]);
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.SvipeNumberVisibility))
                .setItems(labels, (dialog, which) -> {
                    // Choosing "My Contacts" is what makes the contact list needed, so picking it
                    // uploads it and picking anything else makes the server drop it. Both happen
                    // inside setMyVisibility so the two can never drift apart.
                    SvipeConfig.setNumberVisibility(currentAccount, values[which]);
                    refresh();
                    SvipeNumberSync.setMyVisibility(currentAccount, values[which], (visibility, count, ok) -> {
                        if (!ok) {
                            // The server is the source of truth; re-read rather than keep a guess.
                            SvipeNumberSync.loadMySettings(currentAccount, (v, c, o) -> refresh());
                        }
                    });
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.SvipeNumberHistory));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(new Adapter(context));
        listView.setOnItemClickListener((view, position) -> {
            Row row = rows.get(position);
            if (row.type == ROW_VISIBILITY) {
                pickVisibility();
            } else if (row.type == ROW_SHARE) {
                boolean on = !SvipeConfig.isNumberSyncEnabled(currentAccount);
                SvipeConfig.setNumberSyncEnabled(currentAccount, on);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(on);
                }
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        return root;
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != ROW_INFO;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view;
            if (viewType == ROW_INFO) {
                view = new TextInfoPrivacyCell(context);
            } else if (viewType == ROW_VISIBILITY) {
                view = new TextSettingsCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextCheckCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (row.type == ROW_INFO) {
                ((TextInfoPrivacyCell) holder.itemView).setText(row.text);
            } else if (row.type == ROW_VISIBILITY) {
                ((TextSettingsCell) holder.itemView).setTextAndValue(row.text, row.detail, false);
            } else {
                ((TextCheckCell) holder.itemView)
                        .setTextAndCheck(row.text, SvipeConfig.isNumberSyncEnabled(currentAccount), true);
            }
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
