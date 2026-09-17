package org.telegram.svipe;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
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

    private int typesRow;
    private int muteRow;
    private int muteInfoRow;
    private int exceptionsShadowRow;
    private int addExceptionRow;
    private int botsStartRow;
    private int botsEndRow;
    private int deleteShadowRow;
    private int deleteAllRow;
    private int exceptionsInfoRow;
    private int rowCount;

    @Override
    public boolean onFragmentCreate() {
        buildRows();
        return super.onFragmentCreate();
    }

    private void buildRows() {
        // Only the bots that ARE exceptions are listed, the way Telegram lists exceptions: the rest
        // live behind Add Exception. Listing every bot with a switch was our own invention and made
        // a settings screen out of what is a short list of names.
        bots.clear();
        for (TLRPC.User bot : SvipeBotMute.botDialogs(currentAccount)) {
            if (SvipeBotMute.isException(currentAccount, bot.id)) {
                bots.add(bot);
            }
        }
        rowCount = 0;
        muteRow = rowCount++;
        muteInfoRow = rowCount++;
        typesRow = rowCount++;
        exceptionsShadowRow = rowCount++;
        addExceptionRow = rowCount++;
        botsStartRow = rowCount;
        rowCount += bots.size();
        botsEndRow = rowCount;
        if (bots.isEmpty()) {
            deleteShadowRow = -1;
            deleteAllRow = -1;
        } else {
            deleteShadowRow = rowCount++;
            deleteAllRow = rowCount++;
        }
        exceptionsInfoRow = rowCount++;
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
            } else if (position == typesRow) {
                presentFragment(new SvipeMessageTypesActivity(NotificationsController.SCOPE_BOTS, R.string.SvipeMessageTypes));
            } else if (position == addExceptionRow) {
                showBotPicker();
            } else if (position == deleteAllRow) {
                for (TLRPC.User bot : new ArrayList<>(bots)) {
                    SvipeBotMute.setException(currentAccount, bot.id, false);
                }
                refresh();
            } else if (position >= botsStartRow && position < botsEndRow) {
                TLRPC.User bot = bots.get(position - botsStartRow);
                SvipeBotMute.setException(currentAccount, bot.id, false);
                refresh();
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
            return position == muteRow || position == typesRow
                    || position == addExceptionRow || position == deleteAllRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == muteRow) return 0;
            if (position == muteInfoRow || position == exceptionsInfoRow) return 1;
            if (position == exceptionsShadowRow || position == deleteShadowRow) return 2;
            if (position == typesRow || position == addExceptionRow || position == deleteAllRow) return 4;
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
                    view = new ShadowSectionCell(context);
                    break;
                case 4:
                    view = new TextCell(context);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                default:
                    // No checkbox: an exception list shows who is on it, the way Telegram's does.
                    view = new UserCell(context, 6, 0, false);
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
                case 4: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (position == typesRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextAndIcon(LocaleController.getString(R.string.SvipeMessageTypes), R.drawable.msg_msgbubble3, false);
                    } else if (position == addExceptionRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
                        cell.setTextAndIcon(LocaleController.getString(R.string.NotificationsAddAnException),
                                R.drawable.msg_contact_add, !bots.isEmpty());
                    } else {
                        cell.setColors(-1, Theme.key_text_RedRegular);
                        cell.setText(LocaleController.getString(R.string.NotificationsDeleteAllException), false);
                    }
                    break;
                }
                case 2: {
                    break;   // a shadow has nothing to bind
                }
                default: {
                    UserCell cell = (UserCell) holder.itemView;
                    TLRPC.User bot = bots.get(position - botsStartRow);
                    cell.setData(bot, null, LocaleController.getString(R.string.SvipeNotificationsBotOn), 0,
                            position != botsEndRow - 1);
                    cell.setAlpha(SvipeBotMute.isEnabled(currentAccount) ? 1f : 0.5f);
                    break;
                }
            }
        }
    }

    private void refresh() {
        buildRows();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * Telegram's Add Exception opens a list of people to choose from; ours opens the bots the user
     * actually has a chat with. Picking one makes it an exception and closes the sheet, the way
     * choosing a contact there does.
     */
    private void showBotPicker() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        ArrayList<TLRPC.User> candidates = new ArrayList<>();
        for (TLRPC.User bot : SvipeBotMute.botDialogs(currentAccount)) {
            if (!SvipeBotMute.isException(currentAccount, bot.id)) {
                candidates.add(bot);
            }
        }
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        HeaderCell header = new HeaderCell(context);
        header.setText(LocaleController.getString(R.string.NotificationsAddAnException));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final BottomSheet[] sheet = new BottomSheet[1];
        for (int i = 0; i < candidates.size(); i++) {
            final TLRPC.User bot = candidates.get(i);
            UserCell cell = new UserCell(context, 6, 0, false);
            cell.setData(bot, null, null, 0, i < candidates.size() - 1);
            cell.setOnClickListener(v -> {
                SvipeBotMute.setException(currentAccount, bot.id, true);
                refresh();
                if (sheet[0] != null) {
                    sheet[0].dismiss();
                }
            });
            layout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58));
        }
        sheet[0] = new BottomSheet.Builder(context, false).setCustomView(layout).create();
        showDialog(sheet[0]);
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
