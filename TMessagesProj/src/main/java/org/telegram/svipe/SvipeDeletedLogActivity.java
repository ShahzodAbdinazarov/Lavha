package org.telegram.svipe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Per-chat "Recent Actions" log of deleted + edited messages captured into
 * {@code svipe_deleted_messages}. Rather than approximating Telegram's admin log, this builds
 * synthetic {@link TLRPC.TL_channelAdminLogEvent}s from the archive and feeds them through the app's
 * OWN admin-log {@link MessageObject} constructor, so the result is pixel-identical to the real
 * Recent Actions screen: "X deleted this message:" / "X edited this message:" service headers, the
 * embedded "Original message" block under an edited message, date pills, avatars and native cells.
 *
 * <p>Capture is always on, so this screen is always available (see
 * docs/svipe-deleted-edited-messages-plan.md §6); the per-chat "Show in chat" switch on top only
 * controls whether deleted messages additionally stay inline in the chat itself.
 */
public class SvipeDeletedLogActivity extends BaseFragment {

    private final long dialogId;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView emptyView;

    /** Built exactly like ChannelAdminLogActivity: content + service rows + date rows, newest first. */
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private final HashMap<String, ArrayList<MessageObject>> messagesByDays = new HashMap<>();
    private final int[] mid = new int[]{2};

    private TLRPC.Chat logChat;

    public SvipeDeletedLogActivity(long dialogId) {
        this.dialogId = dialogId;
    }

    @Override
    public View createView(Context context) {
        Theme.createChatResources(context, false); // REQUIRED before building any Chat*Cell
        hasOwnBackground = true;

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.EventLog));
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

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        Drawable wallpaper = Theme.getCachedWallpaper();
        if (wallpaper != null) {
            column.setBackground(wallpaper);
        } else {
            column.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        }
        fragmentView = column;

        // Real switch for the per-chat "Show in chat" setting.
        TextCheckCell showInChatCell = new TextCheckCell(context);
        showInChatCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        showInChatCell.setTextAndCheck(LocaleController.getString(R.string.SvipeShowInChat), SvipeConfig.isShowInChat(currentAccount, dialogId), false);
        showInChatCell.setOnClickListener(v -> {
            boolean now = !SvipeConfig.isShowInChat(currentAccount, dialogId);
            SvipeConfig.setShowInChat(currentAccount, dialogId, now);
            showInChatCell.setChecked(now);
        });
        column.addView(showInChatCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout listContainer = new FrameLayout(context);
        column.addView(listContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        emptyView = new TextView(context);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        emptyView.setText(LocaleController.getString(R.string.SvipeArchiveEmpty));
        emptyView.setVisibility(View.GONE);
        listContainer.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        adapter = new ListAdapter();
        // Sender avatars are drawn by the LIST, not by the cell — same as ChannelAdminLogActivity's
        // chatListView.drawChild. Without this the cell reserves the avatar slot but nothing appears.
        listView = new RecyclerListView(context) {
            @Override
            public boolean drawChild(Canvas canvas, View child, long drawingTime) {
                boolean result = super.drawChild(canvas, child, drawingTime);
                if (child instanceof ChatMessageCell) {
                    ChatMessageCell cell = (ChatMessageCell) child;
                    ImageReceiver imageReceiver = cell.getAvatarImage();
                    if (imageReceiver != null) {
                        MessageObject mo = cell.getMessageObject();
                        if (mo != null && mo.deleted) {
                            imageReceiver.setVisible(false, false);
                            return result;
                        }
                        int top = (int) child.getY();
                        int y = (int) child.getY() + cell.getLayoutHeight();
                        int maxY = getMeasuredHeight() - getPaddingBottom();
                        if (y > maxY) {
                            y = maxY;
                        }
                        if (y - AndroidUtilities.dp(48) < top) {
                            y = top + AndroidUtilities.dp(48);
                        }
                        int cellBottom = (int) (cell.getY() + cell.getMeasuredHeight());
                        if (y > cellBottom) {
                            y = cellBottom;
                        }
                        canvas.save();
                        imageReceiver.setImageY(y - AndroidUtilities.dp(44));
                        imageReceiver.setAlpha(1f);
                        imageReceiver.setVisible(true, false);
                        imageReceiver.draw(canvas);
                        canvas.restore();
                    }
                }
                return result;
            }
        };
        LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        layoutManager.setStackFromEnd(true); // same anchoring as the real admin log
        listView.setLayoutManager(layoutManager);
        listView.setAdapter(adapter);
        listContainer.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));

        loadArchive();
        return fragmentView;
    }

    // ---- data ----

    private void loadArchive() {
        getMessagesStorage().getSvipeArchivedMessages(dialogId, entries -> {
            build(entries);
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            if (emptyView != null) {
                emptyView.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
            }
            if (listView != null && !messages.isEmpty()) {
                listView.scrollToPosition(messages.size() - 1);
            }
        });
    }

    /** One synthetic admin-log event ready to be fed through the real MessageObject constructor. */
    private static class Event {
        TLRPC.TL_channelAdminLogEvent event;
        TLRPC.Message deleted; // non-null for delete events, so we can build the header row
        boolean wasOut;        // captured before the admin-log constructor clears `out`: our own message
    }

    private void build(ArrayList<SvipeMessageArchiveStore.Entry> entries) {
        messages.clear();
        messagesByDays.clear();
        mid[0] = 2;

        logChat = resolveChat();
        if (logChat == null || entries == null) {
            return;
        }

        // Group the archive by message id so edited versions form a chain.
        HashMap<Integer, ArrayList<SvipeMessageArchiveStore.Entry>> byMid = new HashMap<>();
        ArrayList<SvipeMessageArchiveStore.Entry> deleted = new ArrayList<>();
        for (SvipeMessageArchiveStore.Entry e : entries) {
            if (e == null || e.message == null) {
                continue;
            }
            if (e.kind == SvipeMessageArchiveStore.KIND_DELETED) {
                deleted.add(e);
            } else {
                ArrayList<SvipeMessageArchiveStore.Entry> list = byMid.get(e.message.id);
                if (list == null) {
                    byMid.put(e.message.id, list = new ArrayList<>());
                }
                list.add(e);
            }
        }

        ArrayList<Event> events = new ArrayList<>();
        long eventId = 1;

        for (SvipeMessageArchiveStore.Entry e : deleted) {
            TLRPC.TL_channelAdminLogEventActionDeleteMessage action = new TLRPC.TL_channelAdminLogEventActionDeleteMessage();
            action.message = e.message;
            Event ev = new Event();
            ev.event = newEvent(eventId++, e.capturedAt > 0 ? (int) (e.capturedAt / 1000L) : e.message.date, peerIdOf(e.message), action);
            ev.deleted = e.message;
            ev.wasOut = e.message.out;
            events.add(ev);
        }

        // Every consecutive pair of versions is one "edited this message" event; the last pair ends at
        // the live message, so the newest edit is shown too.
        for (ArrayList<SvipeMessageArchiveStore.Entry> chain : byMid.values()) {
            Collections.sort(chain, (a, b) -> {
                if (a.kind != b.kind && (a.kind == SvipeMessageArchiveStore.KIND_LIVE || b.kind == SvipeMessageArchiveStore.KIND_LIVE)) {
                    return a.kind == SvipeMessageArchiveStore.KIND_LIVE ? 1 : -1; // live is always newest
                }
                return Integer.compare(a.version, b.version);
            });
            for (int i = 0; i + 1 < chain.size(); i++) {
                TLRPC.Message prev = chain.get(i).message;
                TLRPC.Message next = chain.get(i + 1).message;
                if (isSameContent(prev, next)) {
                    continue; // nothing actually changed — don't show an empty "edited" event
                }
                TLRPC.TL_channelAdminLogEventActionEditMessage action = new TLRPC.TL_channelAdminLogEventActionEditMessage();
                action.prev_message = prev;
                action.new_message = next;
                int date = next.edit_date > 0 ? next.edit_date : (chain.get(i + 1).capturedAt > 0 ? (int) (chain.get(i + 1).capturedAt / 1000L) : next.date);
                Event ev = new Event();
                ev.event = newEvent(eventId++, date, peerIdOf(next), action);
                ev.wasOut = next.out;
                events.add(ev);
            }
        }

        // Newest first — the admin log builds its list in that order and the adapter reverses it.
        Collections.sort(events, (a, b) -> Integer.compare(b.event.date, a.event.date));

        for (Event ev : events) {
            try {
                MessageObject header = new MessageObject(currentAccount, ev.event, messages, messagesByDays, logChat, mid, false);
                if (ev.deleted != null) {
                    // The upstream constructor returns early for delete events (the real admin log
                    // builds a grouped header itself), so add our own header row the same way it does.
                    MessageObject row = createDeletedHeader(ev.deleted, ev.event.date, ev.wasOut);
                    if (row != null && !messages.isEmpty()) {
                        messages.add(messages.size() - 1, row);
                    }
                } else if (header != null) {
                    if (ev.wasOut) {
                        // We made the edit ourselves — say so, instead of naming ourselves in third person.
                        header.messageText = LocaleController.getString(R.string.SvipeYouEditedMessage);
                    } else if (ev.event.user_id < 0) {
                        // Channel/group post: the upstream constructor only resolves user actors, so the
                        // name would be blank. Link the chat instead.
                        TLRPC.Chat from = getMessagesController().getChat(-ev.event.user_id);
                        if (from != null) {
                            header.messageText = MessageObject.replaceWithLink(LocaleController.getString(R.string.EventLogEditedMessages), "un1", from);
                        }
                    }
                }
            } catch (Exception e) {
                org.telegram.messenger.FileLog.e(e);
            }
        }

        // In a 1:1 dialog restore the natural sides: our messages on the right, theirs on the left.
        // (The admin-log constructor forces every message to the "incoming" side, which is right for a
        // group/channel log but wrong for a private chat.)
        if (dialogId > 0) {
            long selfId = getUserConfig().getClientUserId();
            for (MessageObject mo : messages) {
                if (mo != null && mo.contentType == 0 && mo.messageOwner != null && peerIdOf(mo.messageOwner) == selfId) {
                    mo.messageOwner.out = true;
                    mo.isOutOwnerCached = null;
                }
            }
        }
    }

    private TLRPC.TL_channelAdminLogEvent newEvent(long id, int date, long userId, TLRPC.ChannelAdminLogEventAction action) {
        TLRPC.TL_channelAdminLogEvent event = new TLRPC.TL_channelAdminLogEvent();
        event.id = id;
        event.date = date;
        event.user_id = userId;
        event.action = action;
        return event;
    }

    /** Two versions that render identically (same text, same media kind) are not a real edit. */
    private static boolean isSameContent(TLRPC.Message a, TLRPC.Message b) {
        if (a == null || b == null) {
            return false;
        }
        String ta = a.message == null ? "" : a.message;
        String tb = b.message == null ? "" : b.message;
        if (!ta.equals(tb)) {
            return false;
        }
        String ma = a.media == null ? "" : a.media.getClass().getName();
        String mb = b.media == null ? "" : b.media.getClass().getName();
        return ma.equals(mb);
    }

    private static long peerIdOf(TLRPC.Message m) {
        return m != null && m.from_id != null ? MessageObject.getPeerId(m.from_id) : 0;
    }

    /**
     * The chat the log is rendered against. Groups/channels use the real one; a 1:1 dialog has none,
     * so a lightweight stand-in carries the id the admin-log constructor needs (peer + dialog id).
     */
    private TLRPC.Chat resolveChat() {
        if (dialogId < 0) {
            TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            if (chat != null) {
                return chat;
            }
        }
        TLRPC.TL_chat stub = new TLRPC.TL_chat();
        stub.id = Math.abs(dialogId);
        TLRPC.User user = dialogId > 0 ? getMessagesController().getUser(dialogId) : null;
        stub.title = user != null ? UserObject.getUserName(user) : "";
        return stub;
    }

    /** Mirrors ChannelAdminLogActivity#actionMessagesDeletedBy for the single-message case. */
    private MessageObject createDeletedHeader(TLRPC.Message deletedMsg, int date, boolean wasOut) {
        try {
            TLRPC.TL_message msg = new TLRPC.TL_message();
            msg.dialog_id = -logChat.id;
            msg.id = -1;
            msg.date = date;
            MessageObject messageObject = new MessageObject(currentAccount, msg, false, false);
            messageObject.contentType = 1;
            if (wasOut) {
                // Our own message — we deleted it, so don't name ourselves in third person.
                messageObject.messageText = LocaleController.getString(R.string.SvipeYouDeletedMessage);
                return messageObject;
            }
            long fromId = peerIdOf(deletedMsg);
            Object from = fromId > 0 ? getMessagesController().getUser(fromId) : (fromId < 0 ? getMessagesController().getChat(-fromId) : null);
            CharSequence text = LocaleController.getString(R.string.EventLogDeletedMessages);
            if (from instanceof TLRPC.User) {
                text = MessageObject.replaceWithLink(text, "un1", (TLRPC.User) from);
            } else if (from instanceof TLRPC.Chat) {
                text = MessageObject.replaceWithLink(text, "un1", (TLRPC.Chat) from);
            } else {
                text = text.toString().replace("un1", "");
            }
            messageObject.messageText = text;
            return messageObject;
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
            return null;
        }
    }

    // ---- list ----

    private MessageObject itemAt(int position) {
        // Same reversal the admin-log adapter applies: list is newest-first, display is oldest-first.
        return messages.get(messages.size() - 1 - position);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false; // read-only viewer
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        @Override
        public int getItemViewType(int position) {
            return itemAt(position).contentType; // 0 = ChatMessageCell, 1 = ChatActionCell
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
                ChatMessageCell cell = new ChatMessageCell(context, currentAccount);
                cell.isChat = dialogId < 0; // group/channel: sender avatar + name, like the real log
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
            MessageObject mo = itemAt(position);
            View v = holder.itemView;
            if (v instanceof ChatActionCell) {
                ((ChatActionCell) v).setMessageObject(mo);
                ((ChatActionCell) v).setAlpha(1.0f);
            } else if (v instanceof ChatMessageCell) {
                ChatMessageCell cell = (ChatMessageCell) v;
                cell.isChat = dialogId < 0;
                if (mo.mediaThumb == null && mo.messageOwner != null && mo.messageOwner.attachPath != null && mo.messageOwner.attachPath.contains("svipe_msg_archive")) {
                    mo.mediaExists = true;
                    mo.mediaThumb = ImageLocation.getForPath(mo.messageOwner.attachPath);
                }
                cell.setMessageObject(mo, null, false, false, false);
            }
        }
    }
}
