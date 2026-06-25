package org.telegram.ui.Components;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.svipe.SvipeDiscover;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Instagram-style Explore grid for the Search section's empty (no-query) state. Loads reel
 * references from GET /v1/discover, resolves each reference to a Telegram message (batched per
 * channel) to render its video thumbnail, and pages on scroll. Tapping a cell hands the full list +
 * tapped position back to the host, which opens the reels player seeded at that reel.
 */
public class SvipeExploreGrid extends RecyclerListView {

    public interface OnReelTapListener {
        void onReelTap(ArrayList<SvipeDiscover.Item> items, int position);
    }

    private static final int SPAN_COUNT = 3;
    private static final int PAGE_SIZE = 60;

    private final int account;
    private final GridLayoutManager layoutManager;
    private final GridAdapter adapter;
    private final ArrayList<GridItem> items = new ArrayList<>();
    // username (lowercase) -> already resolved chat, so a channel is resolved once across pages.
    private final HashMap<String, TLRPC.Chat> resolvedChats = new HashMap<>();

    private boolean loading;
    private boolean startedFirstLoad;
    private Integer nextOffset = 0;
    private OnReelTapListener tapListener;

    private static class GridItem {
        final SvipeDiscover.Item ref;
        MessageObject mo;
        boolean resolved;
        boolean resolving;

        GridItem(SvipeDiscover.Item ref) {
            this.ref = ref;
        }
    }

    public SvipeExploreGrid(Context context, int account) {
        super(context);
        this.account = account;
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        layoutManager = new GridLayoutManager(context, SPAN_COUNT);
        setLayoutManager(layoutManager);
        adapter = new GridAdapter();
        setAdapter(adapter);

        final int top = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(58);
        final int bottom = AndroidUtilities.dp(96) + AndroidUtilities.navigationBarHeight;
        setPadding(AndroidUtilities.dp(1), top, AndroidUtilities.dp(1), bottom);
        setClipToPadding(false);

        setOnItemClickListener((view, position) -> {
            if (tapListener == null || position < 0 || position >= items.size()) {
                return;
            }
            final ArrayList<SvipeDiscover.Item> refs = new ArrayList<>(items.size());
            for (GridItem gi : items) {
                refs.add(gi.ref);
            }
            tapListener.onReelTap(refs, position);
        });

        addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0) {
                    return;
                }
                if (!loading && nextOffset != null
                        && layoutManager.findLastVisibleItemPosition() >= items.size() - SPAN_COUNT * 2) {
                    loadPage();
                }
            }
        });
    }

    public void setOnReelTapListener(OnReelTapListener listener) {
        this.tapListener = listener;
    }

    /** Trigger the first page load once (called by the host when the grid first becomes visible). */
    public void ensureLoaded() {
        if (startedFirstLoad) {
            return;
        }
        startedFirstLoad = true;
        loadPage();
    }

    private void loadPage() {
        if (loading || nextOffset == null) {
            return;
        }
        loading = true;
        final int offset = nextOffset;
        SvipeDiscover.load(account, null, offset, PAGE_SIZE, (result, next, error) -> {
            loading = false;
            if (result == null) {
                return;
            }
            nextOffset = next;
            final int before = items.size();
            final ArrayList<GridItem> fresh = new ArrayList<>(result.size());
            for (SvipeDiscover.Item ref : result) {
                GridItem gi = new GridItem(ref);
                items.add(gi);
                fresh.add(gi);
            }
            if (!fresh.isEmpty()) {
                adapter.notifyItemRangeInserted(before, fresh.size());
                resolveThumbnails(fresh);
            }
        });
    }

    // ---- thumbnail resolution (resolveUsername -> getMessages, batched per channel) ----

    private void resolveThumbnails(List<GridItem> batch) {
        final HashMap<String, ArrayList<GridItem>> byUser = new HashMap<>();
        for (GridItem gi : batch) {
            if (gi.resolved || gi.resolving || gi.ref.username == null || gi.ref.username.isEmpty()) {
                continue;
            }
            final String u = gi.ref.username.toLowerCase();
            ArrayList<GridItem> group = byUser.get(u);
            if (group == null) {
                group = new ArrayList<>();
                byUser.put(u, group);
            }
            group.add(gi);
        }
        for (Map.Entry<String, ArrayList<GridItem>> e : byUser.entrySet()) {
            resolveChannelGroup(e.getKey(), e.getValue());
        }
    }

    private void resolveChannelGroup(String username, ArrayList<GridItem> group) {
        for (GridItem gi : group) {
            gi.resolving = true;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        final ConnectionsManager cm = ConnectionsManager.getInstance(account);
        final long channelId = group.get(0).ref.channelId;

        final TLRPC.Chat cachedChat = resolvedChats.get(username);
        if (cachedChat != null) {
            fetchMessagesForGroup(cachedChat, group);
            return;
        }

        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        cm.sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                for (GridItem gi : group) {
                    gi.resolving = false;
                }
                return;
            }
            TLRPC.TL_contacts_resolvedPeer rp = (TLRPC.TL_contacts_resolvedPeer) response;
            mc.putUsers(rp.users, false);
            mc.putChats(rp.chats, false);
            TLRPC.Chat chat = null;
            if (rp.chats != null && !rp.chats.isEmpty()) {
                for (int i = 0; i < rp.chats.size(); i++) {
                    if (rp.chats.get(i).id == channelId) {
                        chat = rp.chats.get(i);
                        break;
                    }
                }
                if (chat == null) {
                    chat = rp.chats.get(0);
                }
            }
            if (chat == null) {
                for (GridItem gi : group) {
                    gi.resolving = false;
                }
                return;
            }
            resolvedChats.put(username, chat);
            fetchMessagesForGroup(chat, group);
        }));
    }

    private void fetchMessagesForGroup(TLRPC.Chat chat, ArrayList<GridItem> group) {
        final MessagesController mc = MessagesController.getInstance(account);
        final ConnectionsManager cm = ConnectionsManager.getInstance(account);

        TLRPC.TL_inputChannel inputChannel = new TLRPC.TL_inputChannel();
        inputChannel.channel_id = chat.id;
        inputChannel.access_hash = chat.access_hash;
        TLRPC.TL_channels_getMessages gm = new TLRPC.TL_channels_getMessages();
        gm.channel = inputChannel;
        for (GridItem gi : group) {
            gm.id.add(gi.ref.messageId);
        }
        cm.sendRequest(gm, (resp2, err2) -> AndroidUtilities.runOnUIThread(() -> {
            for (GridItem gi : group) {
                gi.resolving = false;
            }
            if (err2 != null || !(resp2 instanceof TLRPC.messages_Messages)) {
                return;
            }
            TLRPC.messages_Messages mm = (TLRPC.messages_Messages) resp2;
            mc.putUsers(mm.users, false);
            mc.putChats(mm.chats, false);
            if (mm.messages == null) {
                return;
            }
            final HashMap<Integer, MessageObject> byId = new HashMap<>();
            for (int i = 0; i < mm.messages.size(); i++) {
                TLRPC.Message m = mm.messages.get(i);
                if (m == null) {
                    continue;
                }
                byId.put(m.id, new MessageObject(account, m, false, true));
            }
            for (GridItem gi : group) {
                MessageObject mo = byId.get(gi.ref.messageId);
                if (mo != null && mo.getDocument() != null) {
                    gi.mo = mo;
                    gi.resolved = true;
                    final int idx = items.indexOf(gi);
                    if (idx >= 0) {
                        adapter.notifyItemChanged(idx);
                    }
                }
            }
        }));
    }

    // ---- adapter / cell ----

    private class GridAdapter extends SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            SquareImageView iv = new SquareImageView(parent.getContext());
            iv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(iv);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            SquareImageView iv = (SquareImageView) holder.itemView;
            iv.setBackgroundColor(0xFF111111);
            GridItem gi = items.get(position);
            if (gi.mo != null && gi.mo.getDocument() != null) {
                TLRPC.Document doc = gi.mo.getDocument();
                TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 320);
                TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 50);
                iv.setImage(
                        ImageLocation.getForDocument(big, doc), "240_240",
                        ImageLocation.getForDocument(small, doc), "240_240_b",
                        0, gi.mo);
            } else {
                iv.getImageReceiver().clearImage();
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    /** Square cell: height tracks the grid-computed width. */
    private static class SquareImageView extends BackupImageView {
        SquareImageView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int size = MeasureSpec.getSize(widthMeasureSpec);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
        }
    }
}
