package org.telegram.svipe;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
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
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.ProfileNotificationsActivity;

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

    private int notifyHeaderRow;
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
        // Laid out exactly like Private Chats, Groups and Channels next door: the "notify me about"
        // switch in its own card, the message-type rules in theirs, then the exception list.
        rowCount = 0;
        notifyHeaderRow = rowCount++;
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
        exceptionsInfoRow = -1;
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
        // The rounded cards the rest of the settings screens draw; cells stay transparent so the
        // section background is what shows through.
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position == muteRow) {
                boolean notify = SvipeBotMute.isEnabled(currentAccount);
                SvipeBotMute.setEnabled(currentAccount, !notify);
                ((TextCheckCell) view).setChecked(notify);
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
                final TLRPC.User bot = bots.get(position - botsStartRow);
                ItemOptions.makeOptions(this, view)
                    .setGravity(Gravity.LEFT)
                    .add(R.drawable.msg_customize, LocaleController.getString(R.string.NotificationsCustomize), () -> {
                        Bundle args = new Bundle();
                        args.putLong("dialog_id", bot.id);
                        presentFragment(new ProfileNotificationsActivity(args));
                    })
                    .add(R.drawable.msg_mute, LocaleController.getString(R.string.NotificationsStoryMute), true, () -> {
                        SvipeBotMute.setException(currentAccount, bot.id, false);
                        refresh();
                    })
                    .setScrimViewBackground(listView.getClipBackground(view))
                    .show();
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
            if (position == muteInfoRow) return 1;
            if (position == notifyHeaderRow) return 5;
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
                    view.setBackgroundColor(0);
                    break;
                case 1:
                    view = new TextInfoPrivacyCell(context);
                    break;
                case 2:
                    view = new ShadowSectionCell(context);
                    break;
                case 5:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(0);
                    break;
                case 4:
                    view = new TextCell(context);
                    view.setBackgroundColor(0);
                    break;
                default:
                    // No checkbox: an exception list shows who is on it, the way Telegram's does.
                    view = new UserCell(context, 6, 0, false);
                    view.setBackgroundColor(0);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 5: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.NotifyMeAbout));
                    break;
                }
                case 0: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(LocaleController.getString(R.string.SvipeNotificationsBotsMessages),
                            !SvipeBotMute.isEnabled(currentAccount), false);
                    break;
                }
                case 1: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.SvipeNotificationsBotsInfo));
                    cell.setBackground(Theme.getThemedDrawableByKey(context,
                            R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
                case 4: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (position == typesRow) {
                        // No icon: the same row on Private Chats, Groups and Channels carries none,
                        // and this screen sits beside them.
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setText(LocaleController.getString(R.string.SvipeMessageTypes), false);
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
    /**
     * The same Select Chat screen Telegram opens from Add Exception, narrowed to users and then to
     * bots — the picker has no bots-only mode, so a person picked here is turned down rather than
     * quietly added to a list about bots.
     */
    private void showBotPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", false);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_USERS_ONLY);
        DialogsActivity activity = new DialogsActivity(args);
        activity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids.isEmpty()) {
                return true;
            }
            long did = dids.get(0).dialogId;
            TLRPC.User user = getMessagesController().getUser(did);
            if (user == null || !user.bot) {
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.SvipeNotificationsBotsOnlyBots)).show();
                return false;
            }
            SvipeBotMute.setException(currentAccount, did, true);
            fragment.finishFragment();
            refresh();
            return true;
        });
        presentFragment(activity);
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
