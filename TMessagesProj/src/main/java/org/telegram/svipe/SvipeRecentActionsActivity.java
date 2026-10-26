package org.telegram.svipe;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Settings → Recent actions: every chat that has anything kept in the deleted/edited archive,
 * including chats that were deleted outright. Tap opens the chat's log; long-press (or the trash in
 * the log) removes what was kept, for good. Until then nothing is evicted.
 */
public class SvipeRecentActionsActivity extends BaseFragment
        implements NotificationCenter.NotificationCenterDelegate {

    private final ArrayList<MessagesStorage.SvipeArchivedDialog> dialogs = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter adapter;
    private boolean loaded;

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.svipeMessageArchiveUpdated);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.svipeMessageArchiveUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.svipeMessageArchiveUpdated) {
            load();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.SvipeRecentActions));
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

        adapter = new ListAdapter();
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position < dialogs.size()) {
                presentFragment(new SvipeDeletedLogActivity(dialogs.get(position).dialogId));
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < dialogs.size()) {
                confirmDeleteForever(dialogs.get(position));
                return true;
            }
            return false;
        });
        return fragmentView;
    }

    private void load() {
        getMessagesStorage().getSvipeArchivedDialogs(result -> {
            dialogs.clear();
            dialogs.addAll(result);
            loaded = true;
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void confirmDeleteForever(MessagesStorage.SvipeArchivedDialog d) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.SvipeDeleteForever));
        builder.setMessage(LocaleController.getString(R.string.SvipeDeleteForeverInfo));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) ->
                getMessagesStorage().svipePurgeArchive(d.dialogId, null));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private static final int TYPE_CHAT = 0;
        private static final int TYPE_INFO = 1;

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_CHAT;
        }

        @Override
        public int getItemCount() {
            return loaded ? dialogs.size() + 1 : 0;
        }

        @Override
        public int getItemViewType(int position) {
            return position < dialogs.size() ? TYPE_CHAT : TYPE_INFO;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_CHAT) {
                view = new UserCell(parent.getContext(), 6, 0, false);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextInfoPrivacyCell(parent.getContext());
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == TYPE_INFO) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(LocaleController.getString(dialogs.isEmpty() ? R.string.SvipeArchiveEmpty : R.string.SvipeRecentActionsInfo));
                cell.setBackground(Theme.getThemedDrawableByKey(cell.getContext(), R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                return;
            }
            MessagesStorage.SvipeArchivedDialog d = dialogs.get(position);
            Object peer;
            CharSequence name;
            if (d.dialogId > 0) {
                TLRPC.User user = getMessagesController().getUser(d.dialogId);
                peer = user;
                name = user == null ? null : UserObject.isUserSelf(user)
                        ? LocaleController.getString(R.string.SavedMessages) : UserObject.getUserName(user);
            } else {
                TLRPC.Chat chat = getMessagesController().getChat(-d.dialogId);
                peer = chat;
                name = chat == null ? null : chat.title;
            }
            if (name == null) {
                name = LocaleController.getString(R.string.HiddenName);
            }
            String status = LocaleController.formatString(R.string.SvipeArchivedCount, d.count, AndroidUtilities.formatFileSize(d.bytes));
            ((UserCell) holder.itemView).setData(peer, name, status, 0, position < dialogs.size() - 1);
        }
    }
}
