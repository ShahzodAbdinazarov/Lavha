package org.telegram.svipe;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Bottom sheet listing the full edit history of one message: every archived pre-edit version
 * (kind = edited-prior, oldest first) followed by the current live version, each rendered with the
 * app's own native ChatMessageCell / ChatActionCell. Opened from the message long-press menu when
 * "Show in chat" is on and the message is edited. See docs/svipe-deleted-edited-messages-plan.md §7.4.
 */
public class SvipeMessageHistorySheet extends BottomSheet {

    private final int account;
    private final long dialogId;
    private final boolean isChatDialog;
    private final MessageObject current;

    private final ArrayList<MessageObject> items = new ArrayList<>();
    private final HistoryAdapter adapter = new HistoryAdapter();

    public SvipeMessageHistorySheet(Context context, int account, long dialogId, MessageObject current) {
        super(context, false);
        this.account = account;
        this.dialogId = dialogId;
        this.isChatDialog = dialogId < 0;
        this.current = current;

        Theme.createChatResources(context, false);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.SvipeMessageHistory));
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setTextSize(18);
        title.setTypeface(AndroidUtilities.bold());
        title.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(16), AndroidUtilities.dp(22), AndroidUtilities.dp(8));
        container.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter);
        int listHeight = (int) (AndroidUtilities.displaySize.y * 0.6f);
        container.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));
        listView.getLayoutParams().height = listHeight;

        setCustomView(container);
        loadHistory();
    }

    private void loadHistory() {
        MessagesStorage.getInstance(account).getSvipeMessageHistory(dialogId, current.getId(), entries -> {
            items.clear();
            for (SvipeMessageArchiveStore.Entry e : entries) {
                if (e == null || e.message == null) {
                    continue;
                }
                MessageObject mo = new MessageObject(account, e.message, true, false);
                mo.svipeArchived = true;
                if (e.mediaPath != null) {
                    mo.mediaExists = true;
                    mo.mediaThumb = ImageLocation.getForPath(e.mediaPath);
                }
                items.add(mo);
            }
            // Current live version last (newest).
            if (current != null && current.messageOwner != null) {
                MessageObject live = new MessageObject(account, current.messageOwner, true, false);
                items.add(live);
            }
            adapter.notifyDataSetChanged();
        });
    }

    private class HistoryAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).contentType;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            if (viewType == 1) {
                ChatActionCell cell = new ChatActionCell(context);
                cell.setDelegate(new ChatActionCell.ChatActionCellDelegate() {
                    @Override
                    public long getDialogId() {
                        return dialogId;
                    }
                });
                view = cell;
            } else {
                ChatMessageCell cell = new ChatMessageCell(context, account);
                cell.isChat = isChatDialog;
                cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                    @Override
                    public boolean canPerformActions() {
                        return false;
                    }
                });
                view = cell;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            MessageObject mo = items.get(position);
            View v = holder.itemView;
            if (v instanceof ChatActionCell) {
                ((ChatActionCell) v).setMessageObject(mo);
            } else if (v instanceof ChatMessageCell) {
                ChatMessageCell cell = (ChatMessageCell) v;
                cell.isChat = isChatDialog;
                cell.setMessageObject(mo, null, false, false, false);
            }
        }
    }
}
