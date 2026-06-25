package org.telegram.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Svipe "Reels" Instagram-style comment panel. A dark slide-up bottom sheet over the reels player
 * that shows a channel post's discussion-group comments (Telegram channel post comments) and an
 * input to add a comment. If comments are disabled for the post, it shows a "comments off" state
 * with no input bar.
 *
 * Data flow mirrors ChatActivity.openDiscussionMessageChat / processLoadedDiscussionMessage:
 *  1. TL_messages_getDiscussionMessage(channelPeer, postMessageId) -> thread root (last message),
 *     whose dialogId is the linked discussion megagroup.
 *  2. TL_messages_getReplies(rootPeer, rootMsgId) -> the comments page (newest first, reversed here
 *     for an Instagram chronological look).
 *  3. SendMessagesHelper.sendMessage(...) with peer = root.getDialogId() (the discussion group).
 *
 * The reel keeps playing behind the sheet — nothing here pauses the player.
 */
public class SvipeReelsCommentsSheet extends BottomSheet {

    public interface Listener {
        void onCommentSent(int newCount);
    }

    // Dark palette (the reels UI is black) — kept local so the sheet does not depend on the app theme.
    private static final int COLOR_BG = 0xFF1C1C1E;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUBTEXT = 0xFF9E9E9E;
    private static final int COLOR_DIVIDER = 0x14FFFFFF;
    private static final int COLOR_INPUT_BG = 0xFF2C2C2E;
    private static final int COLOR_HANDLE = 0x40FFFFFF;
    private static final int COLOR_SEND = 0xFF1A9CFF;

    private final int currentAccount;
    private final TLRPC.Chat channelChat;
    private final int postMessageId;
    private final MessageObject postMessage;
    private final boolean enabled;

    private Listener listener;

    private FrameLayout content;
    private TextView headerCount;
    private RecyclerListView listView;
    private CommentsAdapter adapter;
    private RadialProgressView progressView;
    private LinearLayout stateView;
    private ImageView stateIcon;
    private TextView stateTitle;
    private TextView stateSub;
    private LinearLayout inputBar;
    private EditTextBoldCursor editText;
    private ImageView sendButton;
    private FrameLayout disabledBar; // covers the input panel with blur + "comments off" text

    private final ArrayList<MessageObject> comments = new ArrayList<>();
    private MessageObject threadRoot; // the discussion-group thread root used for sending
    private int rootMessageId;
    private int commentCount;
    private boolean loading;
    private boolean disabledState; // resolved disabled (either by config or by load failure)

    public SvipeReelsCommentsSheet(Context context, int currentAccount, TLRPC.Chat channelChat, int postMessageId, MessageObject postMessage) {
        super(context, true);
        this.currentAccount = currentAccount;
        this.channelChat = channelChat;
        this.postMessageId = postMessageId;
        this.postMessage = postMessage;
        // isComments() can be false in feed data even when the post has comments; trust a reply
        // count too. The actual load (getDiscussionMessage) is the final arbiter of disabled.
        this.enabled = postMessage != null && (postMessage.isComments() || postMessage.getRepliesCount() > 0);
        this.disabledState = !enabled;
        this.commentCount = postMessage != null ? postMessage.getRepliesCount() : 0;

        setApplyBottomPadding(false);
        setApplyTopPadding(false);
        setCanDismissWithSwipe(true);
        smoothKeyboardAnimationEnabled = true;

        buildContent(context);

        if (enabled) {
            loadThreadAndComments();
        } else {
            updateState();
        }
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    private void buildContent(Context context) {
        final int sheetHeight = (int) (AndroidUtilities.displaySize.y * 0.75f);

        content = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int h = Math.min(sheetHeight, MeasureSpec.getSize(heightMeasureSpec));
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
        };
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_BG);
        float r = AndroidUtilities.dp(14);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        content.setBackground(bg);

        // drag handle
        View handle = new View(context);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(COLOR_HANDLE);
        handleBg.setCornerRadius(AndroidUtilities.dp(2));
        handle.setBackground(handleBg);
        content.addView(handle, LayoutHelper.createFrame(36, 4, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        // header
        TextView headerTitle = new TextView(context);
        headerTitle.setTextColor(COLOR_TEXT);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        headerTitle.setTypeface(AndroidUtilities.bold());
        headerTitle.setText(LocaleController.getString(R.string.SvipeComments));
        headerTitle.setGravity(Gravity.CENTER);

        headerCount = new TextView(context);
        headerCount.setTextColor(COLOR_SUBTEXT);
        headerCount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        headerCount.setGravity(Gravity.CENTER);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        header.addView(headerTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        header.addView(headerCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 1, 0, 0));
        content.addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP, 0, 14, 0, 0));

        View headerDivider = new View(context);
        headerDivider.setBackgroundColor(COLOR_DIVIDER);
        content.addView(headerDivider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.TOP, 0, 62, 0, 0));

        final int topPad = AndroidUtilities.dp(62);
        final int inputBarHeight = AndroidUtilities.dp(56);

        // list
        listView = new RecyclerListView(context);
        LinearLayoutManager lm = new LinearLayoutManager(context);
        lm.setOrientation(LinearLayoutManager.VERTICAL);
        listView.setLayoutManager(lm);
        adapter = new CommentsAdapter();
        listView.setAdapter(adapter);
        listView.setClipToPadding(false);
        listView.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        content.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 63, 0, 56));

        // loading
        progressView = new RadialProgressView(context);
        progressView.setSize(AndroidUtilities.dp(34));
        progressView.setProgressColor(COLOR_TEXT);
        progressView.setVisibility(View.GONE);
        content.addView(progressView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        // empty / disabled state — Instagram-style: icon + bold title + subtitle, centered.
        stateView = new LinearLayout(context);
        stateView.setOrientation(LinearLayout.VERTICAL);
        stateView.setGravity(Gravity.CENTER);
        stateView.setVisibility(View.GONE);

        stateIcon = new ImageView(context);
        stateIcon.setImageResource(R.drawable.menu_comments);
        stateIcon.setColorFilter(new PorterDuffColorFilter(COLOR_SUBTEXT, PorterDuff.Mode.SRC_IN));
        stateView.addView(stateIcon, LayoutHelper.createLinear(56, 56, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        stateTitle = new TextView(context);
        stateTitle.setTextColor(COLOR_TEXT);
        stateTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        stateTitle.setTypeface(AndroidUtilities.bold());
        stateTitle.setGravity(Gravity.CENTER);
        stateView.addView(stateTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 6));

        stateSub = new TextView(context);
        stateSub.setTextColor(COLOR_SUBTEXT);
        stateSub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        stateSub.setGravity(Gravity.CENTER);
        stateView.addView(stateSub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        content.addView(stateView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 36, 63, 36, 56));

        // input bar
        inputBar = new LinearLayout(context);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setBackgroundColor(COLOR_BG);
        inputBar.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));

        View inputDivider = new View(context);
        inputDivider.setBackgroundColor(COLOR_DIVIDER);
        content.addView(inputDivider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.BOTTOM, 0, 0, 0, 56));

        editText = new EditTextBoldCursor(context);
        editText.setTextColor(COLOR_TEXT);
        editText.setHintColor(COLOR_SUBTEXT);
        editText.setHintText(LocaleController.getString(R.string.SvipeCommentHint));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        editText.setBackground(null);
        editText.setCursorColor(COLOR_TEXT);
        editText.setCursorWidth(1.5f);
        editText.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        editText.setMaxLines(4);
        editText.setSingleLine(false);
        editText.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        GradientDrawable editBg = new GradientDrawable();
        editBg.setColor(COLOR_INPUT_BG);
        editBg.setCornerRadius(AndroidUtilities.dp(20));
        editText.setBackground(editBg);
        editText.setMinimumHeight(AndroidUtilities.dp(40));

        sendButton = new ImageView(context);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setImageResource(R.drawable.attach_send);
        sendButton.setColorFilter(new PorterDuffColorFilter(COLOR_SEND, PorterDuff.Mode.SRC_IN));
        sendButton.setAlpha(0.4f);
        sendButton.setEnabled(false);
        sendButton.setOnClickListener(v -> trySend());

        inputBar.addView(editText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        inputBar.addView(sendButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        content.addView(inputBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.BOTTOM));

        // Disabled bar: when comments are off, the input panel stays in place but is covered with a
        // frosted/blurred layer (the input itself is blurred on API 31+) and a "comments off" label
        // sits on top — Instagram-style. Sits exactly over the input bar; eats touches.
        disabledBar = new FrameLayout(context);
        disabledBar.setVisibility(View.GONE);
        disabledBar.setClickable(true);
        disabledBar.setBackgroundColor(0x66000000); // scrim over the blurred input for legibility
        LinearLayout disRow = new LinearLayout(context);
        disRow.setOrientation(LinearLayout.HORIZONTAL);
        disRow.setGravity(Gravity.CENTER);
        ImageView disLock = new ImageView(context);
        disLock.setImageResource(R.drawable.msg_block);
        disLock.setColorFilter(new PorterDuffColorFilter(COLOR_SUBTEXT, PorterDuff.Mode.SRC_IN));
        disRow.addView(disLock, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        TextView disText = new TextView(context);
        disText.setTextColor(COLOR_TEXT);
        disText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        disText.setText(LocaleController.getString(R.string.SvipeCommentsDisabled));
        disRow.addView(disText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        disabledBar.addView(disRow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        content.addView(disabledBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.BOTTOM));

        editText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                boolean has = s != null && s.toString().trim().length() > 0;
                sendButton.setEnabled(has);
                sendButton.setAlpha(has ? 1f : 0.4f);
            }
        });

        containerView = content;
    }

    private void updateState() {
        if (disabledState) {
            progressView.setVisibility(View.GONE);
            listView.setVisibility(View.GONE);
            stateView.setVisibility(View.GONE);
            // The comment-writing panel stays in place, but is blurred and covered by the
            // "comments off" bar — like Instagram when comments are turned off.
            inputBar.setVisibility(View.VISIBLE);
            setInputEnabled(false);
            applyInputBlur(true);
            disabledBar.setVisibility(View.VISIBLE);
            headerCount.setVisibility(View.GONE);
            return;
        }
        disabledBar.setVisibility(View.GONE);
        applyInputBlur(false);
        setInputEnabled(true);
        inputBar.setVisibility(View.VISIBLE);
        headerCount.setVisibility(View.VISIBLE);
        headerCount.setText(commentCount > 0 ? String.valueOf(commentCount) : "");
        if (loading) {
            progressView.setVisibility(View.VISIBLE);
            listView.setVisibility(View.GONE);
            stateView.setVisibility(View.GONE);
        } else if (comments.isEmpty()) {
            progressView.setVisibility(View.GONE);
            listView.setVisibility(View.GONE);
            stateIcon.setImageResource(R.drawable.menu_comments);
            stateTitle.setText(LocaleController.getString(R.string.SvipeNoComments));
            stateSub.setText(LocaleController.getString(R.string.SvipeNoCommentsSub));
            stateView.setVisibility(View.VISIBLE);
        } else {
            progressView.setVisibility(View.GONE);
            stateView.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            adapter.notifyDataSetChanged();
        }
    }

    private void setInputEnabled(boolean en) {
        editText.setEnabled(en);
        editText.setFocusable(en);
        editText.setFocusableInTouchMode(en);
        if (!en) {
            sendButton.setEnabled(false);
            sendButton.setAlpha(0.4f);
        }
    }

    /** Blur the comment-writing panel itself (API 31+) so the disabled bar reads as frosted glass. */
    private void applyInputBlur(boolean blur) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            inputBar.setRenderEffect(blur
                ? RenderEffect.createBlurEffect(AndroidUtilities.dp(6), AndroidUtilities.dp(6), Shader.TileMode.CLAMP)
                : null);
        }
    }

    // ---------------- data ----------------

    private void loadThreadAndComments() {
        if (channelChat == null) {
            disabledState = true;
            updateState();
            return;
        }
        loading = true;
        updateState();

        // Read the post's comment thread DIRECTLY off the channel post. getDiscussionMessage often
        // returns an empty `messages` (no thread root) for these channels even though the thread
        // exists (unread_count>0), which made the sheet wrongly show "disabled". getReplies on the
        // channel post returns the comments without needing the (missing) discussion root.
        final TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
        req.peer = MessagesController.getInputPeer(channelChat);
        req.msg_id = postMessageId;
        req.offset_id = 0;
        req.offset_date = 0;
        req.add_offset = 0;
        req.limit = 40;
        req.max_id = 0;
        req.min_id = 0;
        req.hash = 0;

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            loading = false;
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                disabledState = true; // CHANNEL_PRIVATE / no discussion
                updateState();
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            MessagesController.getInstance(currentAccount).putUsers(res.users, false);
            MessagesController.getInstance(currentAccount).putChats(res.chats, false);

            comments.clear();
            // getReplies returns newest first; reverse for a chronological Instagram look (oldest on top).
            for (int a = res.messages.size() - 1; a >= 0; a--) {
                TLRPC.Message m = res.messages.get(a);
                if (m == null || m instanceof TLRPC.TL_messageEmpty) continue;
                if (m.id == postMessageId) continue;
                MessageObject mo = new MessageObject(currentAccount, m, true, true);
                comments.add(mo);
                // Capture the discussion-thread root for sending: comments carry reply_to_top_id and
                // live in the discussion group.
                if (threadRoot == null && m.reply_to != null) {
                    rootMessageId = m.reply_to.reply_to_top_id != 0 ? m.reply_to.reply_to_top_id : m.reply_to.reply_to_msg_id;
                    threadRoot = mo;
                }
            }
            if (commentCount < comments.size()) {
                commentCount = comments.size();
            }
            updateState();
            if (!comments.isEmpty()) {
                listView.scrollToPosition(comments.size() - 1);
            }
        }));
    }

    private void trySend() {
        if (threadRoot == null || editText == null) return;
        final String text = editText.getText() == null ? "" : editText.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;

        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                text, threadRoot.getDialogId(), threadRoot, threadRoot, null, false, null, null, null, true, 0, 0, null, false);
        try {
            SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }

        // optimistic: clear input, append a local row, bump count, scroll to it
        editText.setText("");
        TLRPC.TL_message local = new TLRPC.TL_message();
        local.id = 0;
        local.message = text;
        local.date = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        local.out = true;
        long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        local.from_id = new TLRPC.TL_peerUser();
        local.from_id.user_id = selfId;
        local.peer_id = MessagesController.getInstance(currentAccount).getPeer(threadRoot.getDialogId());
        MessageObject localMo = new MessageObject(currentAccount, local, true, true);
        comments.add(localMo);
        commentCount++;
        disabledState = false;
        updateState();
        if (listView.getVisibility() == View.VISIBLE) {
            listView.scrollToPosition(comments.size() - 1);
        }
        if (listener != null) {
            listener.onCommentSent(commentCount);
        }
    }

    // ---------------- adapter / rows ----------------

    private class CommentsAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(new CommentRow(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((CommentRow) holder.itemView).bind(comments.get(position));
        }

        @Override
        public int getItemCount() {
            return comments.size();
        }
    }

    private class CommentRow extends FrameLayout {
        private final BackupImageView avatar;
        private final TextView name;
        private final TextView text;
        private final TextView time;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();

        CommentRow(Context context) {
            super(context);
            setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8), AndroidUtilities.dp(14), AndroidUtilities.dp(8));

            avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(17));
            addView(avatar, LayoutHelper.createFrame(34, 34, Gravity.TOP | Gravity.LEFT));

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);

            LinearLayout nameRow = new LinearLayout(context);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);

            name = new TextView(context);
            name.setTextColor(COLOR_TEXT);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            name.setTypeface(AndroidUtilities.bold());
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);

            time = new TextView(context);
            time.setTextColor(COLOR_SUBTEXT);
            time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            time.setSingleLine(true);

            nameRow.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL));
            nameRow.addView(time, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

            text = new TextView(context);
            text.setTextColor(COLOR_TEXT);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);

            col.addView(nameRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            col.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

            addView(col, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 44, 0, 0, 0));
        }

        void bind(MessageObject mo) {
            TLObject author = mo.getFromPeerObject();
            String authorName = "";
            if (author instanceof TLRPC.User) {
                TLRPC.User u = (TLRPC.User) author;
                authorName = org.telegram.messenger.UserObject.getUserName(u);
                avatarDrawable.setInfo(u);
                avatar.setForUserOrChat(u, avatarDrawable);
            } else if (author instanceof TLRPC.Chat) {
                TLRPC.Chat c = (TLRPC.Chat) author;
                authorName = c.title != null ? c.title : "";
                avatarDrawable.setInfo(c);
                avatar.setForUserOrChat(c, avatarDrawable);
            } else {
                avatarDrawable.setInfo(0, authorName, null);
                avatar.setImageDrawable(avatarDrawable);
            }
            name.setText(authorName);

            CharSequence body = mo.caption != null ? mo.caption : mo.messageText;
            text.setText(body != null ? body : "");
            text.setVisibility(body != null && body.length() > 0 ? View.VISIBLE : View.GONE);

            int date = mo.messageOwner != null ? mo.messageOwner.date : 0;
            time.setText(date > 0 ? LocaleController.formatDateAudio(date, false) : "");
        }
    }
}
