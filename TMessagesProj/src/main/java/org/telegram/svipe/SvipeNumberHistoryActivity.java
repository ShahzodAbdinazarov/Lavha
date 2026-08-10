package org.telegram.svipe;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads out {@link SvipeNumberHistory}: the numbers that have carried more than one account, and
 * the accounts that have moved between numbers.
 *
 * Both halves answer the same practical question from opposite ends — "is the person behind this
 * number still the person I think it is?" — so both are on one screen, recycled numbers first,
 * because that is the direction in which a number quietly stops meaning what it meant.
 */
public class SvipeNumberHistoryActivity extends BaseFragment {

    private static final int ROW_SECTION = 0;   // section header
    private static final int ROW_NUMBER = 1;    // a phone number heading its accounts
    private static final int ROW_ACCOUNT = 2;   // one account, with the window we saw it in
    private static final int ROW_INFO = 3;      // the footer explaining what this can and cannot know
    private static final int ROW_SHARE = 4;     // the sharing switch, off by default

    private static class Row {
        final int type;
        final String text;
        final String detail;
        final long userId;

        Row(int type, String text, String detail, long userId) {
            this.type = type;
            this.text = text;
            this.detail = detail;
            this.userId = userId;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;
    private TextView emptyView;

    @Override
    public boolean onFragmentCreate() {
        buildRows();
        return super.onFragmentCreate();
    }

    private void buildRows() {
        rows.clear();

        // The switch comes first because it is a decision about other people, not a preference about
        // this screen: with it off, everything below is only what this phone saw for itself.
        rows.add(new Row(ROW_SHARE, LocaleController.getString(R.string.SvipeNumberSyncShare), null, 0));
        rows.add(new Row(ROW_INFO, LocaleController.getString(R.string.SvipeNumberSyncShareInfo), null, 0));

        List<String> recycled = SvipeNumberHistory.recycledNumbers();
        if (!recycled.isEmpty()) {
            rows.add(new Row(ROW_SECTION, LocaleController.getString(R.string.SvipeNumberHistoryReused), null, 0));
            for (String phone : recycled) {
                rows.add(new Row(ROW_NUMBER, "+" + phone, null, 0));
                for (SvipeNumberHistory.Account account : SvipeNumberHistory.accountsOnNumber(phone)) {
                    rows.add(new Row(ROW_ACCOUNT, account.name, window(account.firstSeen, account.lastSeen), account.userId));
                }
            }
        }

        List<Long> moved = SvipeNumberHistory.movedAccounts();
        if (!moved.isEmpty()) {
            rows.add(new Row(ROW_SECTION, LocaleController.getString(R.string.SvipeNumberHistoryMoved), null, 0));
            for (Long userId : moved) {
                TLRPC.User user = getMessagesController().getUser(userId);
                String name = user != null ? UserObject.getUserName(user) : String.valueOf(userId);
                StringBuilder chain = new StringBuilder();
                for (SvipeNumberHistory.Number number : SvipeNumberHistory.numbersOfAccount(userId)) {
                    if (chain.length() > 0) {
                        chain.append("  →  ");
                    }
                    chain.append("+").append(number.phone);
                }
                rows.add(new Row(ROW_ACCOUNT, name, chain.toString(), userId));
            }
        }

        rows.add(new Row(ROW_INFO, LocaleController.getString(R.string.SvipeNumberHistoryInfo), null, 0));
    }

    /** "seen from X to Y" — a pairing is a window, not an instant. */
    private String window(long first, long last) {
        String from = LocaleController.getInstance().getFormatterYear().format(first);
        String to = LocaleController.getInstance().getFormatterYear().format(last);
        return from.equals(to) ? from : from + " — " + to;
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
            if (row.type == ROW_SHARE) {
                boolean on = !SvipeConfig.isNumberSyncEnabled(currentAccount);
                SvipeConfig.setNumberSyncEnabled(currentAccount, on);
                if (view instanceof org.telegram.ui.Cells.TextCheckCell) {
                    ((org.telegram.ui.Cells.TextCheckCell) view).setChecked(on);
                }
                return;
            }
            if (row.type == ROW_ACCOUNT && row.userId != 0 && getMessagesController().getUser(row.userId) != null) {
                android.os.Bundle args = new android.os.Bundle();
                args.putLong("user_id", row.userId);
                presentFragment(new org.telegram.ui.ProfileActivity(args));
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setGravity(android.view.Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        emptyView.setText(LocaleController.getString(R.string.SvipeNumberHistoryEmpty));
        emptyView.setVisibility(View.GONE);   // the switch and its footer are always present
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

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
            int type = holder.getItemViewType();
            return type == ROW_ACCOUNT || type == ROW_SHARE;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case ROW_SECTION:
                case ROW_NUMBER: {
                    view = new HeaderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                }
                case ROW_INFO: {
                    view = new TextInfoPrivacyCell(context);
                    break;
                }
                case ROW_SHARE: {
                    view = new org.telegram.ui.Cells.TextCheckCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                }
                default: {
                    view = new UserCell(context, 6, 0, false);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                }
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            switch (row.type) {
                case ROW_SECTION:
                case ROW_NUMBER: {
                    ((HeaderCell) holder.itemView).setText(row.text);
                    break;
                }
                case ROW_INFO: {
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.text);
                    break;
                }
                case ROW_SHARE: {
                    org.telegram.ui.Cells.TextCheckCell cell = (org.telegram.ui.Cells.TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(row.text, SvipeConfig.isNumberSyncEnabled(currentAccount), false);
                    break;
                }
                default: {
                    UserCell cell = (UserCell) holder.itemView;
                    TLRPC.User user = row.userId != 0 ? getMessagesController().getUser(row.userId) : null;
                    // A user we no longer have is exactly the interesting case — the account that
                    // left this number. Fall back to the name we wrote down when we saw it.
                    cell.setData(user, user != null ? UserObject.getUserName(user) : row.text, row.detail, 0,
                            position < rows.size() - 1 && rows.get(position + 1).type == ROW_ACCOUNT);
                    break;
                }
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
