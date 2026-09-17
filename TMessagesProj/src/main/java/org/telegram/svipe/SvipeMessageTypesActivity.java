package org.telegram.svipe;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * The same message-type exceptions a single chat can carry, written for a whole class of chats at
 * once — every chat, or only groups, channels, bots or private chats.
 *
 * A chat's own rule is read first and this is what it falls back to, so a person you excepted by
 * hand keeps that exception while the rest of their kind follows the broad rule. Enforcement lives
 * in {@link NotificationsController#isMutedMessageType}; this screen only writes the prefs.
 */
public class SvipeMessageTypesActivity extends BaseFragment {

    private final String scope;
    private final int titleRes;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final ArrayList<String> mutedKinds = new ArrayList<>();
    private final ArrayList<String> notifiedKinds = new ArrayList<>();

    private int mutedHeaderRow;
    private int mutedAddRow;
    private int mutedStart;
    private int mutedEnd;
    private int mutedDeleteShadowRow;
    private int mutedDeleteRow;
    private int mutedInfoRow;
    private int notifiedHeaderRow;
    private int notifiedAddRow;
    private int notifiedStart;
    private int notifiedEnd;
    private int notifiedDeleteShadowRow;
    private int notifiedDeleteRow;
    private int notifiedInfoRow;
    private int rowCount;

    public SvipeMessageTypesActivity(String scope, int titleRes) {
        this.scope = scope;
        this.titleRes = titleRes;
    }

    @Override
    public boolean onFragmentCreate() {
        buildRows();
        return super.onFragmentCreate();
    }

    private void buildRows() {
        mutedKinds.clear();
        notifiedKinds.clear();
        for (String kind : NotificationsController.MESSAGE_KINDS) {
            if (SvipeMessageTypeMute.isMutedScope(currentAccount, NotificationsController.muteKindKey(kind), scope)) {
                mutedKinds.add(kind);
            }
            if (SvipeMessageTypeMute.isMutedScope(currentAccount, NotificationsController.notifyKindKey(kind), scope)) {
                notifiedKinds.add(kind);
            }
        }
        rowCount = 0;
        mutedHeaderRow = rowCount++;
        mutedAddRow = rowCount++;
        mutedStart = rowCount;
        rowCount += mutedKinds.size();
        mutedEnd = rowCount;
        if (mutedKinds.isEmpty()) {
            mutedDeleteShadowRow = -1;
            mutedDeleteRow = -1;
        } else {
            mutedDeleteShadowRow = rowCount++;
            mutedDeleteRow = rowCount++;
        }
        mutedInfoRow = rowCount++;
        notifiedHeaderRow = rowCount++;
        notifiedAddRow = rowCount++;
        notifiedStart = rowCount;
        rowCount += notifiedKinds.size();
        notifiedEnd = rowCount;
        if (notifiedKinds.isEmpty()) {
            notifiedDeleteShadowRow = -1;
            notifiedDeleteRow = -1;
        } else {
            notifiedDeleteShadowRow = rowCount++;
            notifiedDeleteRow = rowCount++;
        }
        notifiedInfoRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(titleRes));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listView = new RecyclerListView(context);
        // The rounded cards the rest of the settings screens draw; cells stay transparent so the
        // section background is what shows through.
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position == mutedAddRow || isMutedRow(position)) {
                showTypesSheet(true);
            } else if (position == notifiedAddRow || isNotifiedRow(position)) {
                showTypesSheet(false);
            } else if (position == mutedDeleteRow || position == notifiedDeleteRow) {
                clearTypes(position == mutedDeleteRow);
            }
        });

        fragmentView = listView;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        return fragmentView;
    }

    private boolean isMutedRow(int position) {
        return position >= mutedStart && position < mutedEnd;
    }

    private boolean isNotifiedRow(int position) {
        return position >= notifiedStart && position < notifiedEnd;
    }

    private void refresh() {
        buildRows();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void clearTypes(boolean muted) {
        for (String kind : NotificationsController.MESSAGE_KINDS) {
            String prefix = muted
                    ? NotificationsController.muteKindKey(kind)
                    : NotificationsController.notifyKindKey(kind);
            if (SvipeMessageTypeMute.isMutedScope(currentAccount, prefix, scope)) {
                SvipeMessageTypeMute.setMutedScope(currentAccount, prefix, scope, false);
            }
        }
        refresh();
    }

    private void showTypesSheet(final boolean muted) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        HeaderCell header = new HeaderCell(context);
        header.setText(LocaleController.getString(muted ? R.string.SvipeMutedTypesHeader : R.string.SvipeUnmutedTypesHeader));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final String[] kinds = NotificationsController.MESSAGE_KINDS;
        for (int i = 0; i < kinds.length; i++) {
            final String kind = kinds[i];
            final String prefix = muted
                    ? NotificationsController.muteKindKey(kind)
                    : NotificationsController.notifyKindKey(kind);
            TextCell cell = new TextCell(context, 23, false, true, null);
            cell.setTextAndCheckAndIcon(LocaleController.getString(SvipeMessageTypeMute.labelOf(kind)),
                    SvipeMessageTypeMute.isMutedScope(currentAccount, prefix, scope),
                    SvipeMessageTypeMute.iconOf(kind), i < kinds.length - 1);
            cell.setOnClickListener(v -> {
                boolean value = !SvipeMessageTypeMute.isMutedScope(currentAccount, prefix, scope);
                SvipeMessageTypeMute.setMutedScope(currentAccount, prefix, scope, value);
                ((TextCell) v).getCheckBox().setChecked(value, true);
                refresh();
            });
            layout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        }

        BottomSheet sheet = new BottomSheet.Builder(context, false)
                .setCustomView(layout)
                .create();
        sheet.setOnDismissListener(dialog -> refresh());
        showDialog(sheet);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final static int VIEW_TYPE_HEADER = 0, VIEW_TYPE_CELL = 1, VIEW_TYPE_INFO = 2, VIEW_TYPE_SHADOW = 3;

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_CELL;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == mutedHeaderRow || position == notifiedHeaderRow) {
                return VIEW_TYPE_HEADER;
            }
            if (position == mutedInfoRow || position == notifiedInfoRow) {
                return VIEW_TYPE_INFO;
            }
            if (position == mutedDeleteShadowRow || position == notifiedDeleteShadowRow) {
                return VIEW_TYPE_SHADOW;
            }
            return VIEW_TYPE_CELL;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(0);
                    break;
                case VIEW_TYPE_INFO:
                    view = new TextInfoPrivacyCell(context);
                    break;
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(context);
                    break;
                default:
                    view = new TextCell(context);
                    view.setBackgroundColor(0);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setText(LocaleController.getString(position == mutedHeaderRow
                            ? R.string.SvipeMutedTypesHeader : R.string.SvipeUnmutedTypesHeader));
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(position == mutedInfoRow
                            ? R.string.SvipeMutedTypesInfo : R.string.SvipeUnmutedTypesInfo));
                    break;
                }
                case VIEW_TYPE_CELL: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (position == mutedAddRow || position == notifiedAddRow) {
                        boolean divider = position == mutedAddRow ? !mutedKinds.isEmpty() : !notifiedKinds.isEmpty();
                        cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
                        cell.setTextAndIcon(LocaleController.getString(R.string.SvipeAddMessageType), R.drawable.msg_add, divider);
                    } else if (position == mutedDeleteRow || position == notifiedDeleteRow) {
                        cell.setColors(-1, Theme.key_text_RedRegular);
                        cell.setText(LocaleController.getString(R.string.SvipeDeleteMessageTypes), false);
                    } else {
                        boolean muted = isMutedRow(position);
                        String kind = muted ? mutedKinds.get(position - mutedStart) : notifiedKinds.get(position - notifiedStart);
                        int last = (muted ? mutedEnd : notifiedEnd) - 1;
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextAndIcon(LocaleController.getString(SvipeMessageTypeMute.labelOf(kind)),
                                SvipeMessageTypeMute.iconOf(kind), position != last);
                    }
                    break;
                }
            }
        }
    }
}
