package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.svipe.SvipeDiscover;
import org.telegram.svipe.SvipeMovies;
import org.telegram.svipe.SvipeSavedChannels;
import org.telegram.svipe.video.SvipeDownloadButton;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.SvipeWideVideoCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The long-form WATCH PAGE — the YouTube-shaped screen behind the Video tab's full-width cards:
 * a pinned 16:9 player at the top, then title / channel / actions / caption and a related-videos list.
 *
 * <p><b>This fragment owns no player.</b> It reserves a black 16:9 hole at the top and publishes that
 * hole's window rect ({@link PlayerHoleListener}); the playback surface is an app-level overlay that
 * lives in LaunchActivity's root frame and is never re-parented, so inline → fullscreen → mini player
 * are pure geometry on one view and none of them can be broken by this fragment coming or going.
 *
 * <p><b>Why one flat list and not the profile screen.</b> ProfileStyleActivity's nested-scroll header
 * machinery exists to collapse an avatar-shaped header, and it owns the pull-down gesture the mini
 * player needs. Here the player is PINNED, exactly as YouTube pins it: the hole never moves while the
 * page scrolls, the rows simply slide up behind it. That removes the scroll-sync problem entirely, so
 * everything below the hole is one {@link RecyclerListView} with no nested scrolling anywhere.
 *
 * <p>Tapping a related row swaps this page to that video IN PLACE ({@link #openItem}) instead of
 * stacking another fragment: the overlay player keeps its surface, and a long browsing session cannot
 * build a twenty-deep back stack of watch pages.
 */
public class SvipeWatchActivity extends BaseFragment {

    /**
     * How the app-level player overlay tracks a watch page. Registered once per process by the overlay
     * (it is a singleton); this fragment deliberately does not know the overlay's type, so the page and
     * the player can be built, changed and reviewed independently.
     */
    public interface PlayerHoleListener {
        /** The page is showing a (new) reference — prepare/swap the player. May fire more than once. */
        void onWatchPageOpened(SvipeWatchActivity page);

        /** The reference resolved to a playable Telegram message: {@link #getWatchMessage()} is set. */
        void onWatchItemResolved(SvipeWatchActivity page);

        /** The reserved hole moved or resized (layout, insets, rotation, transition end). */
        void onPlayerHoleChanged(SvipeWatchActivity page, Rect windowRect);

        /**
         * Another fragment finished presenting over this page. The player overlay draws above the whole
         * fragment stack, so an inline player would otherwise hang over a screen it has nothing to do
         * with — the overlay uses this to go mini.
         */
        void onWatchPageHidden(SvipeWatchActivity page);

        /** This page is on top again; the hole it reported is valid once more. */
        void onWatchPageVisible(SvipeWatchActivity page);

        /** The page left the stack. Whether that means mini or close is the overlay's decision. */
        void onWatchPageClosed(SvipeWatchActivity page);
    }

    private static PlayerHoleListener holeListener;

    /** Wired once by the player overlay. Static because the overlay outlives every watch page. */
    public static void setPlayerHoleListener(PlayerHoleListener listener) {
        holeListener = listener;
    }

    private static final int TYPE_PLAYER_HOLE = 0;
    private static final int TYPE_TITLE = 1;
    private static final int TYPE_CHANNEL = 2;
    private static final int TYPE_ACTIONS = 3;
    private static final int TYPE_CAPTION = 4;
    private static final int TYPE_RELATED_HEADER = 5;
    private static final int TYPE_RELATED = 6;
    private static final int TYPE_RELATED_SKELETON = 7;
    /** Cast strip under the player — only present when this post resolved to a film. */
    private static final int TYPE_ACTORS = 8;

    private static final int RELATED_PAGE_SIZE = 20;
    /** Placeholder rows shown while the first related page loads — about one screenful. */
    private static final int RELATED_SKELETONS = 4;
    /**
     * Consecutive related-page failures before the page stops chasing the endpoint by itself. Without
     * a cap an outage would spin requests as fast as they can fail; the related list is the one part of
     * this screen that is allowed to stay empty.
     */
    private static final int MAX_RELATED_FAILURES = 2;
    /** The heart reels reacts with. Same emoji, so a like from either surface is the same reaction. */
    private static final String LIKE_EMOJI = "❤";
    /** Caption lines shown before "Show more" — YouTube's collapsed description, roughly. */
    private static final int CAPTION_COLLAPSED_LINES = 4;
    /** Title lines before it ellipsizes; the caption row below carries the rest. */
    private static final int TITLE_MAX_LINES = 3;
    /**
     * A first line this long cannot fit {@link #TITLE_MAX_LINES}, so the caption row has to be shown
     * even though it repeats the title — otherwise a single-paragraph caption would be unreadable past
     * the clip. An approximation on purpose: measuring the real ellipsis needs a laid-out TextView, and
     * the row set must be decided before the list is bound.
     */
    private static final int TITLE_CLIP_CHARS = 120;

    /** A feed reference plus whatever MTProto has told us about it so far. */
    private static class Row {
        final SvipeDiscover.Item ref;
        MessageObject mo;
        TLRPC.Chat chat;
        boolean resolving;

        Row(SvipeDiscover.Item ref) {
            this.ref = ref;
        }
    }

    private Row watched;
    /**
     * The film this post is a copy of, or null — most long videos (concerts, serial episodes,
     * lectures) are not films, and then the page renders exactly as it did before the movie layer.
     */
    private SvipeMovies.MovieDetail movieDetail;
    private final ArrayList<Row> related = new ArrayList<>();
    /** channelId:messageId of everything already on this page, so a related page cannot repeat it. */
    private final HashSet<String> shownKeys = new HashSet<>();
    // username (lowercase) -> resolved chat, so a channel is resolved once across related pages.
    private final HashMap<String, TLRPC.Chat> resolvedChats = new HashMap<>();

    private Integer relatedOffset = 0;   // null once the pipe is exhausted or retired
    private boolean loadingRelated;
    private int relatedFailures;

    /** Like state is tracked locally once known — see {@link #toggleLike} for why, it is not cosmetic. */
    private boolean liked;
    private int likeCount;
    private boolean captionExpanded;

    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private ListAdapter adapter;
    private PlayerHoleView playerHole;
    /** View type per adapter position; rebuilt whenever the page's content changes. */
    private final ArrayList<Integer> rows = new ArrayList<>();
    private int firstRelatedRow = -1;
    private final Rect reportedHole = new Rect();
    private final Rect holeProbe = new Rect();   // reused: notifyHoleChanged runs inside a layout pass

    public SvipeWatchActivity(SvipeDiscover.Item ref) {
        this.watched = new Row(ref);
        if (ref != null) {
            shownKeys.add(keyOf(ref));
        }
    }

    private static String keyOf(SvipeDiscover.Item ref) {
        return ref.channelId + ":" + ref.messageId;
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    // ---------------- the player hole (this fragment's whole contribution to playback) ----------------

    /** The reference being watched. Never null. */
    public SvipeDiscover.Item getWatchItem() {
        return watched.ref;
    }

    /** The resolved Telegram message, or null while MTProto is still answering. */
    public MessageObject getWatchMessage() {
        return watched.mo;
    }

    /** The channel that posted it, or null until resolved. */
    public TLRPC.Chat getWatchChat() {
        return watched.chat;
    }

    /** The related references currently listed, in display order — the source for "up next". */
    public List<SvipeDiscover.Item> getRelatedItems() {
        final ArrayList<SvipeDiscover.Item> out = new ArrayList<>(related.size());
        for (Row r : related) {
            out.add(r.ref);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The resolved message of a related reference, or null while MTProto is still answering for it —
     * what the autoplay "Up next" preview needs for a title and a thumbnail without paying for a
     * second resolve of a row this page has already resolved.
     */
    public MessageObject relatedMessage(SvipeDiscover.Item ref) {
        if (ref == null) {
            return null;
        }
        for (Row r : related) {
            if (r.ref == ref) {
                return r.mo;
            }
        }
        return null;
    }

    /**
     * The reserved hole in WINDOW coordinates, or false when the page is not laid out yet.
     *
     * <p>Deliberately pure geometry (this view's width + the status-bar inset) rather than
     * {@code getLocationInWindow}: that would follow the present/dismiss slide animation and hand the
     * overlay a rect one screen-width off for the whole transition. It assumes the page fills the
     * window, which a presented fragment does on a phone — this screen is not laid out for the tablet
     * two-column stack.
     */
    public boolean getPlayerHoleRect(Rect out) {
        if (playerHole == null || playerHole.getWidth() <= 0) {
            return false;
        }
        final int top = AndroidUtilities.statusBarHeight;
        out.set(0, top, playerHole.getWidth(), top + playerHole.playerHeight());
        return true;
    }

    private void notifyHoleChanged() {
        if (holeListener == null || !getPlayerHoleRect(holeProbe) || holeProbe.equals(reportedHole)) {
            return;
        }
        reportedHole.set(holeProbe);
        holeListener.onPlayerHoleChanged(this, new Rect(reportedHole));
    }

    /**
     * Swap this page to another video without leaving it — a related tap, and later the autoplay
     * advance. The overlay keeps its surface; only the page's content is rebuilt.
     */
    public void openItem(SvipeDiscover.Item ref) {
        if (ref == null) {
            return;
        }
        openRow(new Row(ref));
    }

    /**
     * The same swap, reusing a row the related list has ALREADY resolved — so tapping a related card
     * paints its title, channel and actions immediately instead of blanking them for a round-trip.
     */
    private void openRow(Row row) {
        if (row.ref == null || watched.ref != null && keyOf(row.ref).equals(keyOf(watched.ref))) {
            return;
        }
        watched = row;
        related.clear();
        shownKeys.clear();
        shownKeys.add(keyOf(row.ref));
        // resolvedChats is a username cache, not page state: keeping it warm across a swap saves the
        // resolveUsername round-trip for the channel we almost certainly just resolved.
        relatedOffset = 0;
        loadingRelated = false;
        relatedFailures = 0;
        liked = isLiked(row.mo);
        likeCount = totalReactions(row.mo);
        captionExpanded = false;
        rebuildRows();
        if (listView != null) {
            listView.scrollToPosition(0);
        }
        if (holeListener != null) {
            holeListener.onWatchPageOpened(this);
            if (row.mo != null) {
                holeListener.onWatchItemResolved(this);
            }
        }
        if (row.mo == null) {
            resolveWatched();
        }
        loadRelated();
    }

    // ---------------- fragment ----------------

    @Override
    public View createView(Context context) {
        // No action bar: the top of this screen belongs to the player, whose own chrome carries back.
        actionBar.setAddToContainer(false);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = root;

        listView = new RecyclerListView(context);
        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        listView.setLayoutManager(layoutManager);
        adapter = new ListAdapter();
        listView.setAdapter(adapter);
        // Presented fragments cover MainTabsActivity's floating tab bar, so only the nav bar to clear.
        listView.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + dp(12));
        listView.setClipToPadding(false);
        listView.setOnItemClickListener((view, position) -> {
            final Row row = relatedRowAt(position);
            if (row != null) {
                openRow(row);
            }
        });
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy > 0 && layoutManager.findLastVisibleItemPosition() >= rows.size() - 3) {
                    loadRelated();
                }
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // The hole is PINNED over the list (never a scrolling row, hence never re-parented or moved):
        // row 0 is a spacer of exactly this height, so the content starts below it and then slides up
        // behind it, which is what YouTube's watch page does.
        playerHole = new PlayerHoleView(context);
        root.addView(playerHole, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        rebuildRows();
        resolveWatched();
        loadRelated();
        loadMovie();
        if (holeListener != null) {
            holeListener.onWatchPageOpened(this);
        }
        return fragmentView;
    }

    @Override
    public boolean isLightStatusBar() {
        return false;   // the strip behind the status bar is the player's black letterbox
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && !backward) {
            // Backstop for an overlay that registered its listener after this page's first layout —
            // notifyHoleChanged is a no-op when the rect it would send has already been sent.
            notifyHoleChanged();
        }
    }

    @Override
    public void onBecomeFullyHidden() {
        super.onBecomeFullyHidden();
        if (holeListener != null) {
            holeListener.onWatchPageHidden(this);
        }
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        if (holeListener != null) {
            holeListener.onWatchPageVisible(this);
        }
        notifyHoleChanged();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (holeListener != null) {
            holeListener.onWatchPageClosed(this);
        }
    }

    /**
     * The reserved 16:9 hole, black, pinned under the status bar.
     *
     * <p>Fixed at 16:9 rather than the video's own aspect on purpose: the overlay letterboxes the video
     * inside whatever rect it is given, so a hole that resized when the real dimensions arrived would
     * shove the whole page down mid-read. In landscape a full-width 16:9 hole would eat the screen, so
     * it is capped and the overlay letterboxes into the wider rect instead.
     */
    private class PlayerHoleView extends View {
        PlayerHoleView(Context context) {
            super(context);
            setBackgroundColor(0xFF000000);
        }

        int playerHeight() {
            return Math.max(0, getMeasuredHeight() - AndroidUtilities.statusBarHeight);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                    AndroidUtilities.statusBarHeight + holeHeight(MeasureSpec.getSize(widthMeasureSpec)));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            notifyHoleChanged();
        }
    }

    /** 16:9 of the width, capped so an inline player cannot swallow a landscape window. */
    private static int holeHeight(int width) {
        final int cap = Math.round(Math.max(AndroidUtilities.displaySize.y, dp(320)) * 0.6f);
        return Math.max(dp(1), Math.min(Math.round(width * 9f / 16f), cap));
    }

    // ---------------- rows ----------------

    /**
     * Rebuild the view-type-per-position list. Everything above the related header exists always (the
     * metadata rows render empty and fill in as MTProto answers, so the page does not jump); the
     * caption row and the related section appear only when they have something to say.
     */
    private void rebuildRows() {
        rows.clear();
        rows.add(TYPE_PLAYER_HOLE);
        rows.add(TYPE_TITLE);
        rows.add(TYPE_CHANNEL);
        rows.add(TYPE_ACTIONS);
        if (movieDetail != null && !movieDetail.actors.isEmpty()) {
            rows.add(TYPE_ACTORS);
        }
        if (hasCaptionBody()) {
            rows.add(TYPE_CAPTION);
        }
        firstRelatedRow = -1;
        if (!related.isEmpty()) {
            rows.add(TYPE_RELATED_HEADER);
            firstRelatedRow = rows.size();
            for (int i = 0; i < related.size(); i++) {
                rows.add(TYPE_RELATED);
            }
        } else if (loadingRelated) {
            rows.add(TYPE_RELATED_HEADER);
            for (int i = 0; i < RELATED_SKELETONS; i++) {
                rows.add(TYPE_RELATED_SKELETON);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private Row relatedRowAt(int position) {
        if (firstRelatedRow < 0 || position < firstRelatedRow) {
            return null;
        }
        final int idx = position - firstRelatedRow;
        return idx < related.size() ? related.get(idx) : null;
    }

    /** The video's own title line: the caption's first line, which is how these posts are written. */
    private CharSequence titleText() {
        final CharSequence caption = fullCaption();
        if (caption == null) {
            return null;
        }
        final int nl = TextUtils.indexOf(caption, '\n');
        return nl > 0 ? caption.subSequence(0, nl) : caption;
    }

    private CharSequence fullCaption() {
        final MessageObject mo = watched.mo;
        if (mo == null) {
            return null;
        }
        if (mo.caption != null && mo.caption.length() > 0) {
            return mo.caption;
        }
        if (mo.messageOwner != null && mo.messageOwner.message != null && mo.messageOwner.message.length() > 0) {
            return mo.messageOwner.message;
        }
        return null;
    }

    /** True when the caption says more than the title row can show — else the row is a pure duplicate. */
    private boolean hasCaptionBody() {
        final CharSequence caption = fullCaption();
        final CharSequence title = titleText();
        if (caption == null || title == null) {
            return false;
        }
        return caption.length() > title.length() || title.length() > TITLE_CLIP_CHARS;
    }

    // ---------------- reference resolution (username -> messages, batched per channel) ----------------
    //
    // A local copy of the grid's resolver rather than a shared one: the extraction of ReelsActivity's
    // resolver into svipe/video/SvipeRefResolver is its own step (it carries the in-flight-callback
    // queue that keeps a reel from spinning forever), and this page must not wait on it.

    private void resolveWatched() {
        final Row row = watched;
        resolve(row, () -> {
            if (row != watched) {
                return;   // the page swapped to another video while this resolve was in flight
            }
            if (row.mo != null) {
                liked = isLiked(row.mo);
                likeCount = totalReactions(row.mo);
            }
            rebuildRows();
            if (holeListener != null && row.mo != null) {
                holeListener.onWatchItemResolved(this);
            }
        });
    }

    /** Resolve one row (channel + message) and run {@code done} on the UI thread either way. */
    private void resolve(final Row row, final Runnable done) {
        final ArrayList<Row> single = new ArrayList<>(1);
        single.add(row);
        resolveGroup(single, done);
    }

    /** Batch the rows of one channel: one username resolve, then one getMessages for all of them. */
    private void resolveBatch(List<Row> batch) {
        final HashMap<String, ArrayList<Row>> byUser = new HashMap<>();
        for (Row r : batch) {
            if (r.mo != null || r.resolving || r.ref == null || r.ref.username == null || r.ref.username.isEmpty()) {
                continue;
            }
            final String u = r.ref.username.toLowerCase();
            ArrayList<Row> group = byUser.get(u);
            if (group == null) {
                group = new ArrayList<>();
                byUser.put(u, group);
            }
            group.add(r);
        }
        for (Map.Entry<String, ArrayList<Row>> e : byUser.entrySet()) {
            resolveGroup(e.getValue(), null);
        }
    }

    private void resolveGroup(final ArrayList<Row> group, final Runnable done) {
        final Row head = group.get(0);
        if (head.ref == null || head.ref.username == null || head.ref.username.isEmpty()) {
            if (done != null) done.run();
            return;
        }
        for (Row r : group) {
            r.resolving = true;
        }
        final String username = head.ref.username.toLowerCase();
        final TLRPC.Chat cached = resolvedChats.get(username);
        if (cached != null) {
            fetchMessages(cached, group, done);
            return;
        }
        final MessagesController mc = MessagesController.getInstance(currentAccount);
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                        for (Row r : group) {
                            r.resolving = false;
                        }
                        if (done != null) done.run();
                        return;
                    }
                    TLRPC.TL_contacts_resolvedPeer rp = (TLRPC.TL_contacts_resolvedPeer) response;
                    mc.putUsers(rp.users, false);
                    mc.putChats(rp.chats, false);
                    TLRPC.Chat chat = null;
                    if (rp.chats != null && !rp.chats.isEmpty()) {
                        for (int i = 0; i < rp.chats.size(); i++) {
                            if (rp.chats.get(i).id == head.ref.channelId) {
                                chat = rp.chats.get(i);
                                break;
                            }
                        }
                        if (chat == null) {
                            chat = rp.chats.get(0);
                        }
                    }
                    if (chat == null) {
                        for (Row r : group) {
                            r.resolving = false;
                        }
                        if (done != null) done.run();
                        return;
                    }
                    resolvedChats.put(username, chat);
                    fetchMessages(chat, group, done);
                }));
    }

    private void fetchMessages(final TLRPC.Chat chat, final ArrayList<Row> group, final Runnable done) {
        for (Row r : group) {
            r.chat = chat;
        }
        final MessagesController mc = MessagesController.getInstance(currentAccount);
        TLRPC.TL_inputChannel inputChannel = new TLRPC.TL_inputChannel();
        inputChannel.channel_id = chat.id;
        inputChannel.access_hash = chat.access_hash;
        TLRPC.TL_channels_getMessages gm = new TLRPC.TL_channels_getMessages();
        gm.channel = inputChannel;
        for (Row r : group) {
            gm.id.add(r.ref.messageId);
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(gm, (resp, err) ->
                AndroidUtilities.runOnUIThread(() -> {
                    for (Row r : group) {
                        r.resolving = false;
                    }
                    if (err == null && resp instanceof TLRPC.messages_Messages) {
                        TLRPC.messages_Messages mm = (TLRPC.messages_Messages) resp;
                        mc.putUsers(mm.users, false);
                        mc.putChats(mm.chats, false);
                        if (mm.messages != null) {
                            final HashMap<Integer, MessageObject> byId = new HashMap<>();
                            for (int i = 0; i < mm.messages.size(); i++) {
                                TLRPC.Message m = mm.messages.get(i);
                                if (m != null) {
                                    byId.put(m.id, new MessageObject(currentAccount, m, false, true));
                                }
                            }
                            for (Row r : group) {
                                MessageObject mo = byId.get(r.ref.messageId);
                                if (mo != null && mo.getDocument() != null) {
                                    r.mo = mo;
                                    notifyRowChanged(r);
                                }
                            }
                        }
                    }
                    if (done != null) done.run();
                }));
    }

    /** Repaint just this row once its thumbnail/metadata arrived (no-op for the watched item). */
    private void notifyRowChanged(Row row) {
        if (adapter == null || firstRelatedRow < 0) {
            return;
        }
        final int idx = related.indexOf(row);
        if (idx >= 0) {
            adapter.notifyItemChanged(firstRelatedRow + idx);
        }
    }

    // ---------------- related list ----------------

    /**
     * Page the related list. Phase A source (see {@link SvipeDiscover#relatedVideos}): the same
     * long-form pipe the Video tab uses, minus the video being watched and anything already listed.
     */
    /**
     * Save the watched post into the user's "Saved Videos" list. Requires the message to be resolved
     * — the list stores a real forwarded copy, not a reference, which is what makes it survive the
     * source channel deleting the post.
     */
    private void saveToList() {
        if (watched == null || watched.mo == null) {
            return;
        }
        SvipeSavedChannels.save(currentAccount, SvipeSavedChannels.Kind.SAVED_VIDEOS, watched.mo, this,
                chatId -> AndroidUtilities.runOnUIThread(() -> {
                    if (chatId != 0) {
                        BulletinFactory.of(this)
                                .createSimpleBulletin(R.raw.saved_messages,
                                        getString(R.string.SvipeSavedToList))
                                .show();
                    }
                }));
    }

    /**
     * Ask whether the watched post is a film; if it is, its cast becomes a strip under the player.
     * Fire-and-forget: a 404 is the common answer and leaves the page exactly as it was.
     */
    private void loadMovie() {
        if (watched == null || watched.ref == null) {
            return;
        }
        SvipeMovies.movieByPost(currentAccount, watched.ref.channelId, watched.ref.messageId,
                (detail, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (detail == null || detail.actors.isEmpty()) {
                        return;
                    }
                    movieDetail = detail;
                    rebuildRows();
                }));
    }

    private void loadRelated() {
        if (loadingRelated || relatedOffset == null || watched.ref == null) {
            return;
        }
        loadingRelated = true;
        final boolean first = related.isEmpty();
        if (first) {
            rebuildRows();   // reveal the shimmer rows under the header
        }
        final Row seed = watched;
        final int offset = relatedOffset;
        SvipeDiscover.relatedVideos(currentAccount, seed.ref.channelId, seed.ref.messageId,
                offset, RELATED_PAGE_SIZE, (items, next, error) -> {
                    loadingRelated = false;
                    if (seed != watched) {
                        return;   // the page swapped videos: this page of related belongs to nobody
                    }
                    if (items == null) {
                        if (++relatedFailures >= MAX_RELATED_FAILURES) {
                            relatedOffset = null;   // stop chasing an endpoint that keeps failing
                        }
                        rebuildRows();
                        return;
                    }
                    relatedFailures = 0;
                    // An empty page means there is nothing left to list, whatever next_offset claims —
                    // trusting the cursor there is what turns a spent pipe into an endless request loop.
                    relatedOffset = items.isEmpty() ? null : next;
                    final ArrayList<Row> fresh = new ArrayList<>();
                    for (SvipeDiscover.Item it : items) {
                        if (!shownKeys.add(keyOf(it))) {
                            continue;   // already listed (or the seed itself)
                        }
                        final Row row = new Row(it);
                        related.add(row);
                        fresh.add(row);
                    }
                    rebuildRows();
                    resolveBatch(fresh);
                    // A page that landed entirely deduped leaves nothing new to scroll, and then the
                    // scroll listener can never ask again — so keep going while the pipe has more.
                    if (fresh.isEmpty() && relatedOffset != null) {
                        loadRelated();
                    }
                });
    }

    /** Drop one related reference (the ⋮ "not interested" action). */
    private void removeRelated(SvipeDiscover.Item ref) {
        for (int i = related.size() - 1; i >= 0; i--) {
            if (related.get(i).ref == ref) {
                related.remove(i);
            }
        }
        rebuildRows();
    }

    /** Drop every related reference from a channel the user just blocked. */
    private void removeRelatedChannel(long channelId) {
        for (int i = related.size() - 1; i >= 0; i--) {
            if (related.get(i).ref.channelId == channelId) {
                related.remove(i);
            }
        }
        rebuildRows();
    }

    private final SvipeWideVideoCell.Delegate cellDelegate = new SvipeWideVideoCell.Delegate() {
        @Override
        public BaseFragment fragment() {
            return SvipeWatchActivity.this;
        }

        @Override
        public void onRefRemoved(SvipeDiscover.Item ref) {
            removeRelated(ref);
        }

        @Override
        public void onChannelBlocked(long channelId) {
            removeRelatedChannel(channelId);
        }
    };

    // ---------------- actions (the reels player's own, on a light surface) ----------------

    private static int totalReactions(MessageObject mo) {
        int total = 0;
        if (mo != null && mo.messageOwner != null && mo.messageOwner.reactions != null
                && mo.messageOwner.reactions.results != null) {
            for (int i = 0; i < mo.messageOwner.reactions.results.size(); i++) {
                total += mo.messageOwner.reactions.results.get(i).count;
            }
        }
        return total;
    }

    private static boolean isLiked(MessageObject mo) {
        if (mo == null) {
            return false;
        }
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> chosen = mo.getChoosenReactions();
        for (int i = 0; i < chosen.size(); i++) {
            if (LIKE_EMOJI.equals(chosen.get(i).emojicon)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Like / unlike, driven by the locally tracked {@link #liked} flag rather than by re-reading the
     * message's reaction list — reels learned the hard way that reading it back breaks UNLIKING, because
     * emoji variation selectors make the equality check miss.
     */
    private void toggleLike() {
        final MessageObject mo = watched.mo;
        if (mo == null) {
            return;
        }
        final boolean newLiked = !liked;
        ReactionsLayoutInBubble.VisibleReaction heart =
                ReactionsLayoutInBubble.VisibleReaction.fromEmojicon(LIKE_EMOJI);
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> visible = new ArrayList<>();
        if (newLiked) {
            visible.add(heart);
        }
        SendMessagesHelper.getInstance(currentAccount)
                .sendReaction(mo, visible, newLiked ? heart : null, false, true, this, null);
        liked = newLiked;
        likeCount = Math.max(0, likeCount + (newLiked ? 1 : -1));
        SvipeDiscover.sendEvent(currentAccount, watched.ref.channelId, watched.ref.messageId,
                newLiked ? "LIKE" : "UNLIKE", null);
        rebuildRows();
    }

    /** Instagram-style comment panel — the reels sheet as-is; only open when there is a thread. */
    private void openComments() {
        final MessageObject mo = watched.mo;
        if (mo == null || getContext() == null || mo.getRepliesCount() <= 0) {
            return;
        }
        try {
            SvipeReelsCommentsSheet sheet = new SvipeReelsCommentsSheet(
                    getContext(), currentAccount, watched.chat, watched.ref.messageId, mo);
            sheet.setListener(newCount -> {
                if (mo.messageOwner != null && mo.messageOwner.replies != null) {
                    mo.messageOwner.replies.replies = newCount;
                }
                rebuildRows();
            });
            sheet.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Share the VIDEO, not a link — the twin of ReelsActivity.share: the owned svipe.uz/&lt;code&gt;
     * install link rides under the actual document as a promo caption, so the recipient watches it in
     * Telegram and lands on our install page. Keep the two in step if either changes.
     */
    private void share() {
        final SvipeDiscover.Item ref = watched.ref;
        if (getParentActivity() == null || ref == null) {
            return;
        }
        String link = (ref.shareUrl != null && !ref.shareUrl.isEmpty())
                ? ref.shareUrl
                : (ref.username != null && !ref.username.isEmpty()
                        ? "https://t.me/" + ref.username + "/" + ref.messageId : null);
        if (link == null) {
            return;
        }
        final String caption = getString(R.string.SvipeSharePromo)
                + "\n\n" + link.replaceFirst("^https?://", "");
        try {
            final MessageObject mo = watched.mo;
            TLRPC.Document d = mo != null ? mo.getDocument() : null;
            if (d instanceof TLRPC.TL_document) {
                final TLRPC.TL_document document = (TLRPC.TL_document) d;
                ArrayList<MessageObject> messages = new ArrayList<>();
                messages.add(mo);
                ShareAlert alert = new ShareAlert(getParentActivity(), messages, null, false, null, false) {
                    @Override
                    protected void sendInternal(boolean withSound) {
                        for (int a = 0; a < selectedDialogs.size(); a++) {
                            long key = selectedDialogs.keyAt(a);
                            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                                    document, null, null, key, null, null, caption, null, null, null,
                                    withSound, 0, 0, 0, mo, null, false);
                            SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
                        }
                        dismiss();
                    }
                };
                showDialog(alert);
            } else {
                // Not resolved yet — share the promo text + link alone rather than nothing.
                showDialog(new ShareAlert(getParentActivity(), null, caption, false, link, false));
            }
            SvipeDiscover.sendEvent(currentAccount, ref.channelId, ref.messageId, "SHARE", null);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Subscribe / unsubscribe, exactly as the reels rail does it. */
    private void toggleFollow() {
        final TLRPC.Chat chat = watched.chat;
        if (chat == null) {
            return;
        }
        TLRPC.User self = MessagesController.getInstance(currentAccount)
                .getUser(UserConfig.getInstance(currentAccount).getClientUserId());
        if (ChatObject.isInChat(chat)) {
            MessagesController.getInstance(currentAccount).deleteParticipantFromChat(chat.id, self);
            chat.left = true;
            SvipeDiscover.sendEvent(currentAccount, watched.ref.channelId, watched.ref.messageId, "UNFOLLOW", null);
        } else {
            MessagesController.getInstance(currentAccount).addUserToChat(chat.id, self, 0, null, this, null);
            chat.left = false;
            SvipeDiscover.sendEvent(currentAccount, watched.ref.channelId, watched.ref.messageId, "FOLLOW", null);
        }
        rebuildRows();
    }

    /** Open the channel at this post — the channel row's own tap, like YouTube's channel link. */
    private void openChannel() {
        // Only once the chat is resolved: ChatActivity opens a chat_id MessagesController knows about.
        if (watched.chat == null || watched.ref == null || watched.ref.channelId == 0) {
            return;
        }
        Bundle args = new Bundle();
        args.putLong("chat_id", watched.ref.channelId);
        args.putInt("message_id", watched.ref.messageId);
        try {
            presentFragment(new ChatActivity(args));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---------------- adapter ----------------

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_RELATED;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position);
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final Context ctx = parent.getContext();
            final View view;
            switch (viewType) {
                case TYPE_PLAYER_HOLE:
                    view = new HoleSpacerView(ctx);
                    break;
                case TYPE_TITLE:
                    view = new TitleView(ctx);
                    break;
                case TYPE_CHANNEL:
                    view = new ChannelView(ctx);
                    break;
                case TYPE_ACTIONS:
                    view = new ActionsView(ctx);
                    break;
                case TYPE_CAPTION:
                    view = new CaptionView(ctx);
                    break;
                case TYPE_ACTORS:
                    view = new ActorsRowView(ctx);
                    break;
                case TYPE_RELATED_HEADER:
                    view = sectionHeader(ctx);
                    break;
                case TYPE_RELATED_SKELETON:
                    view = new RelatedSkeletonView(ctx);
                    break;
                default:
                    SvipeWideVideoCell cell = new SvipeWideVideoCell(ctx, currentAccount);
                    cell.setDelegate(cellDelegate);
                    view = cell;
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_TITLE:
                    ((TitleView) holder.itemView).bind();
                    break;
                case TYPE_CHANNEL:
                    ((ChannelView) holder.itemView).bind();
                    break;
                case TYPE_ACTIONS:
                    ((ActionsView) holder.itemView).bind();
                    break;
                case TYPE_CAPTION:
                    ((CaptionView) holder.itemView).bind();
                    break;
                case TYPE_ACTORS:
                    ((ActorsRowView) holder.itemView).bind();
                    break;
                case TYPE_RELATED: {
                    final Row row = related.get(position - firstRelatedRow);
                    ((SvipeWideVideoCell) holder.itemView).bind(row.ref, row.mo, row.chat);
                    break;
                }
                default:
                    break;   // spacer / header / skeleton render themselves
            }
        }
    }

    /**
     * The cast strip: horizontally scrolling round avatars under the player, each opening that
     * performer's ActorProfile. Names are the uploading channel's own Uzbek spelling — see
     * {@link SvipeActorActivity} for why that is the right label rather than a romanisation.
     */
    private class ActorsRowView extends FrameLayout {
        private final LinearLayout row;

        ActorsRowView(Context context) {
            super(context);
            HorizontalScrollView scroll = new HorizontalScrollView(context);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            scroll.setPadding(dp(12), 0, dp(12), 0);
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            scroll.addView(row, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 104, Gravity.TOP, 0, 4, 0, 8));
        }

        void bind() {
            row.removeAllViews();
            if (movieDetail == null) {
                return;
            }
            for (SvipeMovies.Actor a : movieDetail.actors) {
                row.addView(actorChip(getContext(), a));
            }
        }

        private View actorChip(Context context, SvipeMovies.Actor a) {
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);

            BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(28));
            AvatarDrawable drawable = new AvatarDrawable();
            drawable.setInfo(a.id, a.name, null);
            avatar.setImageDrawable(drawable);
            column.addView(avatar, LayoutHelper.createLinear(56, 56));

            TextView name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setText(a.name);
            LinearLayout.LayoutParams nlp = LayoutHelper.createLinear(72, LayoutHelper.WRAP_CONTENT);
            nlp.topMargin = dp(4);
            column.addView(name, nlp);

            column.setOnClickListener(v -> presentFragment(new SvipeActorActivity(a.id, a.name)));
            LinearLayout.LayoutParams lp = LayoutHelper.createLinear(72, LayoutHelper.WRAP_CONTENT);
            lp.rightMargin = dp(4);
            column.setLayoutParams(lp);
            return column;
        }
    }

    /** Row 0: the hole the pinned player occupies. Same height, computed the same way. */
    private static class HoleSpacerView extends View {
        HoleSpacerView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(width, AndroidUtilities.statusBarHeight + holeHeight(width));
        }
    }

    /** Title (the caption's first line, up to three of them) plus "channel · views · age". */
    private class TitleView extends LinearLayout {
        private final TextView title;
        private final TextView meta;

        TitleView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(14), dp(16), dp(10));

            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setLineSpacing(dp(2), 1f);
            title.setMaxLines(TITLE_MAX_LINES);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            meta = new TextView(context);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            meta.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        }

        void bind() {
            final CharSequence t = titleText();
            title.setText(t);
            title.setVisibility(t == null || t.length() == 0 ? GONE : VISIBLE);
            meta.setText(SvipeWideVideoCell.metaLine(currentAccount, watched.ref, watched.mo, watched.chat));
        }
    }

    /** Channel avatar + name (+ subscriber count when already cached) + Subscribe. */
    private class ChannelView extends LinearLayout {
        private final BackupImageView avatar;
        private final TextView name;
        private final TextView subtitle;
        private final TextView follow;

        ChannelView(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(6), dp(16), dp(10));
            setBackground(Theme.getSelectorDrawable(false));
            setOnClickListener(v -> openChannel());

            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(20));
            addView(avatar, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);

            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setTypeface(AndroidUtilities.bold());
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

            follow = new TextView(context);
            follow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            follow.setTypeface(AndroidUtilities.bold());
            follow.setSingleLine(true);
            follow.setPadding(dp(16), dp(7), dp(16), dp(7));
            follow.setOnClickListener(v -> toggleFollow());
            addView(follow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        }

        void bind() {
            final TLRPC.Chat chat = watched.chat;
            if (chat != null) {
                avatar.setForUserOrChat(chat, new AvatarDrawable(chat));
                name.setText(chat.title != null ? chat.title : ("@" + watched.ref.username));
            } else {
                AvatarDrawable ad = new AvatarDrawable();
                ad.setInfo(0, watched.ref.username, null);
                avatar.setImageDrawable(ad);
                name.setText("@" + watched.ref.username);
            }
            // Opportunistic: shown only when the full chat is already cached — a watch page must not
            // spend a round-trip on a subscriber count.
            TLRPC.ChatFull full = chat == null ? null : MessagesController.getInstance(currentAccount).getChatFull(chat.id);
            if (full != null && full.participants_count > 0) {
                subtitle.setVisibility(VISIBLE);
                subtitle.setText(LocaleController.formatPluralString("Subscribers", full.participants_count));
            } else {
                subtitle.setVisibility(GONE);
            }

            final boolean following = chat != null && ChatObject.isInChat(chat);
            // Unlike the reels rail, a subscribed channel keeps its button and reads "Subscribed": on a
            // watch page a vanishing button just looks like the tap failed.
            follow.setText(getString(following ? R.string.SvipeReelsSubscribed : R.string.SvipeReelsSubscribe));
            follow.setTextColor(following
                    ? Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)
                    : Theme.getColor(Theme.key_featuredStickers_buttonText));
            follow.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18),
                    following ? Theme.getColor(Theme.key_listSelector) : Theme.getColor(Theme.key_featuredStickers_addButton),
                    Theme.getColor(Theme.key_listSelector)));
            follow.setVisibility(chat == null ? GONE : VISIBLE);
        }
    }

    /**
     * Like / comment / share / download chips — the reels player's actions on a light surface, plus the
     * one YouTube action reels has no equivalent of. The row scrolls horizontally so a narrow screen
     * never has to drop a chip.
     */
    private class ActionsView extends HorizontalScrollView {
        private final ActionPill like;
        private final ActionPill comment;
        private final ActionPill shareChip;
        private final ActionPill saveChip;
        private final SvipeDownloadButton download;

        ActionsView(Context context) {
            super(context);
            setHorizontalScrollBarEnabled(false);
            setClipToPadding(false);

            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(12), 0, dp(12), dp(10));
            addView(row, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            like = new ActionPill(context, R.drawable.media_like);
            like.setOnClickListener(v -> toggleLike());
            row.addView(like, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, Gravity.CENTER_VERTICAL, 4, 0, 4, 0));

            comment = new ActionPill(context, R.drawable.menu_comments);
            comment.setOnClickListener(v -> openComments());
            row.addView(comment, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, Gravity.CENTER_VERTICAL, 4, 0, 4, 0));

            shareChip = new ActionPill(context, R.drawable.media_share);
            shareChip.setOnClickListener(v -> share());
            row.addView(shareChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, Gravity.CENTER_VERTICAL, 4, 0, 4, 0));

            // "Saqlash" — forwards this post into the user's private, archived "Saved Videos" channel.
            // The list lives in the user's own Telegram account, not on our servers; see
            // SvipeSavedChannels for why that is the right home for it.
            saveChip = new ActionPill(context, R.drawable.msg_saved);
            saveChip.setOnClickListener(v -> saveToList());
            row.addView(saveChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, Gravity.CENTER_VERTICAL, 4, 0, 4, 0));

            download = new SvipeDownloadButton(context, currentAccount);
            row.addView(download, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, Gravity.CENTER_VERTICAL, 4, 0, 4, 0));
        }

        void bind() {
            final MessageObject mo = watched.mo;
            like.setLabel(likeCount > 0 ? String.valueOf(likeCount) : getString(R.string.SvipeReelsLike));
            like.setActive(liked);
            final int replies = mo != null ? mo.getRepliesCount() : 0;
            comment.setLabel(replies > 0 ? String.valueOf(replies) : getString(R.string.SvipeReelsComment));
            comment.setDimmed(replies <= 0);
            shareChip.setLabel(getString(R.string.SvipeReelsShare));
            download.bind(mo);
        }
    }

    /** One rounded icon+label chip. */
    private static class ActionPill extends LinearLayout {
        private final ImageView icon;
        private final TextView label;

        ActionPill(Context context, int iconRes) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(12), 0, dp(14), 0);
            setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18),
                    Theme.getColor(Theme.key_windowBackgroundGray), Theme.getColor(Theme.key_listSelector)));

            icon = new ImageView(context);
            icon.setImageResource(iconRes);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            addView(icon, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

            label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            label.setSingleLine(true);
            addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
            setActive(false);
        }

        void setLabel(CharSequence text) {
            label.setText(text);
        }

        /** Liked: the reels heart red, so the same state reads the same in both players. */
        void setActive(boolean active) {
            final int color = active ? 0xFFFF2E38 : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            label.setTextColor(color);
        }

        /** Nothing to open (no comment thread) — visibly inert, as the reels rail shows it. */
        void setDimmed(boolean dimmed) {
            setAlpha(dimmed ? 0.4f : 1f);
        }
    }

    /** The full caption, collapsed to a few lines with a Show more / Show less toggle. */
    private class CaptionView extends LinearLayout {
        private final TextView text;
        private final TextView toggle;

        CaptionView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(4), dp(16), dp(14));

            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            text.setLineSpacing(dp(2), 1f);
            addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            toggle = new TextView(context);
            toggle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            toggle.setTypeface(AndroidUtilities.bold());
            toggle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            addView(toggle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

            setOnClickListener(v -> {
                captionExpanded = !captionExpanded;
                bind();
            });
        }

        void bind() {
            text.setText(fullCaption());
            if (captionExpanded) {
                text.setMaxLines(Integer.MAX_VALUE);
                text.setEllipsize(null);
            } else {
                text.setMaxLines(CAPTION_COLLAPSED_LINES);
                text.setEllipsize(TextUtils.TruncateAt.END);
            }
            toggle.setText(getString(captionExpanded ? R.string.ShowLess : R.string.ShowMore));
        }
    }

    /** "Related videos" section header. */
    private View sectionHeader(Context context) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        tv.setPadding(dp(16), dp(14), dp(16), dp(8));
        tv.setText(getString(R.string.SvipeRelatedVideos));
        return tv;
    }

    /** Shimmer stand-in for a related card, shaped like one so the list does not reflow when it lands. */
    private static class RelatedSkeletonView extends View {
        private final SvipeWideVideoCell.Shimmer shimmer = new SvipeWideVideoCell.Shimmer();
        private final RectF rect = new RectF();

        RelatedSkeletonView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            // 16:9 thumbnail + the metadata row's fixed height, matching SvipeWideVideoCell.
            setMeasuredDimension(width, Math.round(width * 9f / 16f) + dp(84));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float inset = dp(1);
            rect.set(inset, inset, getWidth() - inset, Math.round(getWidth() * 9f / 16f) - inset);
            shimmer.draw(canvas, rect, dp(3), this);
            rect.set(dp(12), rect.bottom + dp(12), getWidth() - dp(60), rect.bottom + dp(28));
            shimmer.draw(canvas, rect, dp(3), null);
        }
    }
}
