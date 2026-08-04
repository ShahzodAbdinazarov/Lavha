package org.telegram.svipe;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * "Bots" — the notification category Telegram does not have.
 *
 * Deliberately built out of the app's own settings cells ({@link TextCheckCell} for the switch,
 * {@link UserCell} for each bot) rather than anything of our own design: this screen has to feel
 * like the Notifications screen it is opened from, and the surest way to get that is to use the same
 * pieces.
 *
 * The list is every bot the user actually has a chat with, each with its own switch — on means that
 * bot still notifies while the rule silences the rest. See {@link SvipeBotMute} for what the rule
 * does and why it is enforced with Telegram's own mute.
 */
public class SvipeBotNotificationsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;
    private final ArrayList<TLRPC.User> bots = new ArrayList<>();

    private int muteRow;
    private int muteInfoRow;
    private int exceptionsHeaderRow;
    private int botsStartRow;
    private int botsEndRow;
    private int exceptionsInfoRow;
    private int rowCount;

    @Override
    public boolean onFragmentCreate() {
        buildRows();
        return super.onFragmentCreate();
    }

    private void buildRows() {
        bots.clear();
        bots.addAll(SvipeBotMute.botDialogs(currentAccount));
        rowCount = 0;
        muteRow = rowCount++;
        muteInfoRow = rowCount++;
        if (!bots.isEmpty()) {
            exceptionsHeaderRow = rowCount++;
            botsStartRow = rowCount;
            rowCount += bots.size();
            botsEndRow = rowCount;
            exceptionsInfoRow = rowCount++;
        } else {
            exceptionsHeaderRow = -1;
            botsStartRow = -1;
            botsEndRow = -1;
            exceptionsInfoRow = -1;
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.SvipeNotificationsBots));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position == muteRow) {
                boolean muted = !SvipeBotMute.isEnabled(currentAccount);
                SvipeBotMute.setEnabled(currentAccount, muted);
                ((TextCheckCell) view).setChecked(muted);
                if (adapter != null) adapter.notifyDataSetChanged();
            } else if (position >= botsStartRow && position < botsEndRow) {
                TLRPC.User bot = bots.get(position - botsStartRow);
                boolean nowExcepted = !SvipeBotMute.isException(currentAccount, bot.id);
                SvipeBotMute.setException(currentAccount, bot.id, nowExcepted);
                if (adapter != null) adapter.notifyItemChanged(position);
            }
        });
        return fragmentView;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

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
            int position = holder.getAdapterPosition();
            // A bot row only means something while the rule is on — otherwise nothing is muted for
            // it to be an exception to, and a tappable switch would promise something untrue.
            if (position >= botsStartRow && position < botsEndRow) {
                return SvipeBotMute.isEnabled(currentAccount);
            }
            return position == muteRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == muteRow) return 0;
            if (position == muteInfoRow || position == exceptionsInfoRow) return 1;
            if (position == exceptionsHeaderRow) return 2;
            return 3;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new TextCheckCell(context);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case 1:
                    view = new TextInfoPrivacyCell(context);
                    break;
                case 2:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                default:
                    view = new UserCell(context, 6, 2, false);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(LocaleController.getString(R.string.SvipeNotificationsBotsMute),
                            SvipeBotMute.isEnabled(currentAccount), !bots.isEmpty());
                    break;
                }
                case 1: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == muteInfoRow) {
                        cell.setText(LocaleController.getString(R.string.SvipeNotificationsBotsInfo));
                    } else {
                        cell.setText(LocaleController.getString(R.string.SvipeNotificationsBotsExceptionsInfo));
                    }
                    cell.setBackground(Theme.getThemedDrawableByKey(context,
                            R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
                case 2: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.SvipeNotificationsBotsExceptions));
                    break;
                }
                default: {
                    UserCell cell = (UserCell) holder.itemView;
                    TLRPC.User bot = bots.get(position - botsStartRow);
                    boolean notifies = SvipeBotMute.isException(currentAccount, bot.id)
                            || !SvipeBotMute.isEnabled(currentAccount);
                    cell.setData(bot, null, LocaleController.getString(notifies
                            ? R.string.SvipeNotificationsBotOn : R.string.SvipeNotificationsBotOff), 0, true);
                    cell.setChecked(SvipeBotMute.isException(currentAccount, bot.id), false);
                    cell.setAlpha(SvipeBotMute.isEnabled(currentAccount) ? 1f : 0.5f);
                    break;
                }
            }
        }
    }

    /** Used by the Notifications screen's row to describe the rule without opening it. */
    public static String rowValue(int account) {
        if (!SvipeBotMute.isEnabled(account)) {
            return LocaleController.getString(R.string.SvipeNotificationsBotsOn);
        }
        int n = SvipeBotMute.exceptions(account).size();
        if (n == 0) {
            return LocaleController.getString(R.string.SvipeNotificationsBotsOff);
        }
        return LocaleController.formatPluralString("SvipeNotificationsBotsExceptionsCount", n);
    }
}
