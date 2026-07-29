package org.telegram.svipe;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Management screen for the reels channels the user has blocked. The list is the SERVER truth (GET
 * /v1/reels/blocked, with hydrated title/username) unioned with the persistent local
 * {@link SvipeBlockedChannels} set, so a block made offline (event still pending) or on another
 * device both appear. Each row offers an Unblock action that posts an UNBLOCK_CHANNEL event
 * (the twin of ReelsActivity's BLOCK_CHANNEL) and drops the channel from the local set so its reels
 * can surface again immediately.
 */
public class SvipeBlockedChannelsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView emptyView;

    private SvipeBlockedChannels blockedStore;
    private final ArrayList<SvipeDiscover.BlockedChannel> channels = new ArrayList<>();
    private boolean loaded;

    @Override
    public boolean onFragmentCreate() {
        blockedStore = new SvipeBlockedChannels(currentAccount);
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.SvipeBlockedChannels));
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
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        emptyView = new TextView(context);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        emptyView.setText(LocaleController.getString(R.string.SvipeNoBlockedChannels));
        emptyView.setVisibility(View.GONE);
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        adapter = new ListAdapter();
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < channels.size()) {
                confirmUnblock(channels.get(position));
            }
        });

        load();
        return fragmentView;
    }

    // ---- data ----

    private void load() {
        final List<Long> localIds = blockedStore.getAll();
        SvipeDiscover.reelsBlocked(currentAccount, (serverItems, error) -> {
            LinkedHashMap<Long, SvipeDiscover.BlockedChannel> merged = new LinkedHashMap<>();
            if (serverItems != null) {
                for (SvipeDiscover.BlockedChannel bc : serverItems) {
                    if (bc != null && bc.channelId != 0) {
                        merged.put(bc.channelId, bc);
                    }
                }
            }
            // Union in anything the local set knows that the server didn't return (pending event /
            // blocked offline). Hydrate title/username from a cached chat when we have one.
            for (Long id : localIds) {
                if (id == null || id == 0 || merged.containsKey(id)) {
                    continue;
                }
                SvipeDiscover.BlockedChannel bc = new SvipeDiscover.BlockedChannel();
                bc.channelId = id;
                TLRPC.Chat chat = getMessagesController().getChat(id);
                if (chat != null) {
                    bc.title = chat.title;
                    bc.username = ChatObject.getPublicUsername(chat);
                }
                merged.put(id, bc);
            }
            channels.clear();
            channels.addAll(merged.values());
            loaded = true;
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            updateEmpty();
        });
    }

    private void updateEmpty() {
        if (emptyView != null) {
            emptyView.setVisibility(loaded && channels.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void confirmUnblock(SvipeDiscover.BlockedChannel bc) {
        if (bc == null || getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.SvipeUnblockChannel));
        builder.setMessage(LocaleController.getString(R.string.SvipeUnblockChannelConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.SvipeUnblock), (dialog, which) -> unblock(bc));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void unblock(SvipeDiscover.BlockedChannel bc) {
        // Optimistic: drop it locally right away so the feed can surface it again, then tell the
        // backend (fire-and-forget with its own 401 retry) to stop excluding it server-side.
        blockedStore.remove(bc.channelId);
        SvipeDiscover.unblockChannel(currentAccount, bc.channelId, null);
        int idx = channels.indexOf(bc);
        if (idx >= 0) {
            channels.remove(idx);
            if (adapter != null) {
                adapter.notifyItemRemoved(idx);
            }
        }
        updateEmpty();
    }

    // ---- list ----

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return channels.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            BlockedChannelCell cell = new BlockedChannelCell(parent.getContext());
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((BlockedChannelCell) holder.itemView).bind(channels.get(position));
        }
    }

    /** Avatar + channel title + @username on the left, an Unblock button on the right. */
    private class BlockedChannelCell extends FrameLayout {

        private final BackupImageView avatar;
        private final TextView titleView;
        private final TextView subtitleView;
        private final TextView unblockButton;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private SvipeDiscover.BlockedChannel channel;

        BlockedChannelCell(Context context) {
            super(context);
            setBackground(Theme.getSelectorDrawable(false));

            avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(23));
            addView(avatar, LayoutHelper.createFrame(46, 46, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 70, 0, 96, 0));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(titleView);

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            unblockButton = new TextView(context);
            unblockButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            unblockButton.setTypeface(AndroidUtilities.bold());
            unblockButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            unblockButton.setText(LocaleController.getString(R.string.SvipeUnblock));
            unblockButton.setGravity(Gravity.CENTER);
            unblockButton.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(6));
            unblockButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            unblockButton.setOnClickListener(v -> {
                if (channel != null) {
                    confirmUnblock(channel);
                }
            });
            addView(unblockButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(60), MeasureSpec.EXACTLY));
        }

        void bind(SvipeDiscover.BlockedChannel bc) {
            channel = bc;
            String title = bc.title != null && !bc.title.isEmpty()
                    ? bc.title
                    : (bc.username != null && !bc.username.isEmpty() ? "@" + bc.username : "Channel " + bc.channelId);
            titleView.setText(title);
            subtitleView.setText(bc.username != null && !bc.username.isEmpty() ? "@" + bc.username : "");
            subtitleView.setVisibility(bc.username != null && !bc.username.isEmpty() ? VISIBLE : GONE);

            TLRPC.Chat chat = getMessagesController().getChat(bc.channelId);
            if (chat != null) {
                avatarDrawable.setInfo(chat);
                avatar.setForUserOrChat(chat, avatarDrawable);
            } else {
                avatarDrawable.setInfo(bc.channelId, title, null);
                avatar.setImageDrawable(avatarDrawable);
            }
        }
    }
}
