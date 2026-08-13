package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
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
import org.telegram.ui.Cells.UserCell;
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
    /**
     * The section tabs — Related | Actors | Variants — and their rows. Only films have the last two,
     * because only a film is a WORK with a cast and many copies; an ordinary video keeps the plain
     * "Related videos" header it always had.
     *
     * <p>This is where the film page went. A film used to open a separate profile screen that did not
     * play anything, so the shelves felt like a different app from the tab they were opened from:
     * tapping a card in All played, tapping one under Comedy did not. The watch page IS the film page
     * now — it plays first and answers "who is in it" and "which copy" in tabs underneath.
     */
    private static final int TYPE_TABS = 8;
    private static final int TYPE_ACTOR = 9;
    private static final int TYPE_VERSION = 10;
    /** A selected tab with nothing in it — a film whose cast never matched, most often. */
    private static final int TYPE_TAB_EMPTY = 11;
    /**
     * The playlist panel: a header that folds, then ONE row holding a bounded, self-scrolling list of
     * episodes.
     *
     * <p>The episodes used to be rows of this page — ninety of them for a long show — so the episode
     * playing sat wherever it happened to sit and finding it meant scrolling the whole page. A bounded
     * panel is what YouTube shows and what the owner asked for: the episode playing is always under
     * the thumb, with the one before it above and as many as fit below.
     */
    private static final int TYPE_PLAYLIST_BAR = 12;
    private static final int TYPE_PLAYLIST_PANEL = 13;

    private static final int TAB_RELATED = 0;
    private static final int TAB_ACTORS = 1;
    private static final int TAB_VERSIONS = 2;

    private static final int RELATED_PAGE_SIZE = 20;

    /** Episodes resolved ahead of the one on screen — one screenful, so scrolling never shows blanks. */
    private static final int PLAYLIST_RESOLVE_AHEAD = 8;
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
    /** Which of the three sections is showing. Always Related on open — that is the page's own job. */
    private int currentTab = TAB_RELATED;
    /** First row index of the tab's own list, so a tap maps back to an actor/version/related item. */
    private int firstTabRow = -1;
    private boolean pinInFlight;
    private final ArrayList<Row> related = new ArrayList<>();

    /**
     * The show this page is playing an episode OF, when it was opened from one.
     *
     * <p>A playlist here is a list of references, not a channel: the episodes stay in whichever
     * channels published them and this page holds the running order. That is why opening episode 12
     * costs nothing to prepare — there is nothing to build, only a list to index into.
     */
    private SvipeMovies.SeriesPage playlist;
    private final ArrayList<Row> playlistRows = new ArrayList<>();
    private int playlistIndex = -1;
    /** Open on arrival, the way YouTube shows the playlist you came in through. */
    private boolean playlistExpanded = true;
    /** Which episode the panel is currently parked on, so a rebind cannot yank a browsing user back. */
    private int playlistParkedFor = Integer.MIN_VALUE;
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
        this(ref, null, null);
    }

    /**
     * A video the user ran into somewhere else in the app, opened here with the Telegram message
     * ALREADY in hand — see {@code SvipeVideoOpen}, which is the only caller.
     *
     * <p>A seeded page never resolves, and that is not an optimisation. For a {@code local}
     * reference there is no public handle to resolve, so the resolve would fail and leave a black
     * player behind a retry button that can never work; for a public one it would spend a
     * resolveUsername + getMessages on a message we are holding. The player does not need the resolve
     * callback either: it reads {@link #getWatchMessage()} in {@code onWatchPageOpened}, which runs at
     * the end of {@link #createView}, and starts playback straight from there.
     *
     * @param local true when the source is NOT a public channel post (a private chat, a group, a
     *              private channel, saved messages). Then nothing identifying this video may reach our
     *              server — see {@link #isLocal()} for everything that is switched off.
     */
    public static SvipeWatchActivity seeded(SvipeDiscover.Item ref, MessageObject mo, TLRPC.Chat chat,
                                            boolean local) {
        if (ref != null) {
            ref.local = local;   // on the REFERENCE, so a mini-bar restore cannot lose it
        }
        return new SvipeWatchActivity(ref, mo, chat);
    }

    /**
     * The same page, rebuilt after the mini bar was dragged back up.
     *
     * <p>Everything the mini bar remembers has to come back with it, and the playlist is part of that:
     * a restore that dropped it left the user inside a show with no show around them — the panel gone,
     * autoplay off the running order, and the next episode replaced by whatever the related pipe
     * happened to like. {@code index} may be -1: the user tapped a related video while inside a show,
     * and the panel is still theirs to go back to even though nothing in it is playing.
     */
    public static SvipeWatchActivity restored(SvipeDiscover.Item ref, MessageObject mo, TLRPC.Chat chat,
                                              boolean local, SvipeMovies.SeriesPage page, int index) {
        SvipeWatchActivity fragment = local || mo != null
                ? seeded(ref, mo, chat, local)
                : new SvipeWatchActivity(ref);
        fragment.attachPlaylist(page, index);
        return fragment;
    }

    /**
     * Attach a show to a page that is ALREADY on screen, and move it to the right episode.
     *
     * <p>Opening a show used to fetch its episode list first and present the page only when the list
     * arrived — so on a slow connection tapping a show did nothing at all, for seconds, which reads
     * as a frozen phone rather than as loading. The page now opens on the show's own poster post
     * (a real video from the same show) and this fills the rest in when it lands.
     */
    public void attachSeries(SvipeMovies.SeriesPage page, int index) {
        if (page == null || page.isEmpty()) {
            return;
        }
        attachPlaylist(page, index);
        final int at = playlistIndex >= 0 ? playlistIndex : 0;
        final Row target = playlistRows.get(at);
        playlistIndex = at;
        playlistParkedFor = Integer.MIN_VALUE;
        if (watched.ref != null && target.ref != null
                && watched.ref.channelId == target.ref.channelId
                && watched.ref.messageId == target.ref.messageId) {
            rebuildRows();   // already on the right episode: only the panel is new
            return;
        }
        openRow(target);
    }

    /** Hang a run order on this page without touching what it is playing. */
    private void attachPlaylist(SvipeMovies.SeriesPage page, int index) {
        if (page == null || page.isEmpty()) {
            return;
        }
        playlist = page;
        playlistIndex = index >= 0 && index < page.episodes.size() ? index : -1;
        playlistRows.clear();
        for (SvipeMovies.Episode e : page.episodes) {
            playlistRows.add(new Row(e.asItem()));
        }
    }

    /** The show this page is inside, or null. Read by the player so a restore can put it back. */
    public SvipeMovies.SeriesPage getPlaylist() {
        return playlist;
    }

    /** Where in the run order the page is, or -1 when it has wandered off it. */
    public int getPlaylistIndex() {
        return playlistIndex;
    }

    /**
     * Open a show at one episode, with the whole run order attached.
     *
     * <p>The page is otherwise the ordinary watch page — same player, same related list underneath —
     * because an episode IS a long-form video. What the playlist adds is a panel and an order to
     * advance along, and both are additions to this screen rather than a screen of their own.
     */
    public static SvipeWatchActivity ofSeries(SvipeMovies.SeriesPage page, int index) {
        return ofSeries(page, index, 0);
    }

    /**
     * @param startMs where to open the episode, when the caller knows better than this device does —
     *                the server's "continue watching" answer, which followed the account here from
     *                wherever it was last watched. 0 keeps the local mark.
     */
    public static SvipeWatchActivity ofSeries(SvipeMovies.SeriesPage page, int index, long startMs) {
        final int at = index >= 0 && index < page.episodes.size() ? index : 0;
        if (startMs > 0) {
            final SvipeMovies.Episode e = page.episodes.get(at);
            org.telegram.svipe.video.SvipeVideoPlayerController.requestStartAt(
                    e.channelId, e.messageId, startMs);
        }
        SvipeWatchActivity fragment = new SvipeWatchActivity(page.episodes.get(at).asItem());
        fragment.playlist = page;
        fragment.playlistIndex = at;
        for (SvipeMovies.Episode e : page.episodes) {
            fragment.playlistRows.add(new Row(e.asItem()));
        }
        org.telegram.svipe.SvipeSeriesProgress.setLastEpisode(page.series.id, at);
        return fragment;
    }

    private SvipeWatchActivity(SvipeDiscover.Item ref, MessageObject mo, TLRPC.Chat chat) {
        this.watched = new Row(ref);
        this.watched.mo = mo;
        this.watched.chat = chat;
        this.liked = isLiked(mo);
        this.likeCount = totalReactions(mo);
        if (ref != null) {
            shownKeys.add(keyOf(ref));
        }
    }

    /**
     * True when the video on screen is not a public channel post. Then this page is a PLAYER and
     * nothing else: no watch events, no related seed, no film lookup, no follow — see the suppressed
     * calls one by one below. It mirrors the intake contract on the backend side, which knows about
     * public handles only and stores no submitter identity.
     */
    private boolean isLocal() {
        return watched != null && watched.ref != null && watched.ref.local;
    }

    /**
     * True when the message on screen IS the post the reference names. It is not, for a forward
     * opened from a chat: the reference is resolved to the ORIGINAL channel post (the copy the server
     * can index and serve), while playback and the reaction bar work on the forwarded copy the user
     * actually tapped.
     *
     * <p>That split is right for the video and wrong for the actions. A ❤️ goes to the message on
     * screen — Telegram's own semantics, and the only message this page holds — so reporting it to the
     * server as a like on the ORIGIN post would credit a post nobody touched, and the count beside it
     * is the copy's anyway. Watch telemetry is deliberately NOT gated on this: the video being watched
     * is the same video either way, and the canonical post is exactly where that signal belongs.
     */
    private boolean displayingReferencedPost() {
        final MessageObject mo = watched == null ? null : watched.mo;
        if (mo == null || watched.ref == null) {
            return false;
        }
        return mo.getId() == watched.ref.messageId
                && MessageObject.getChatId(mo.messageOwner) == watched.ref.channelId;
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
    /** Where this page was opened from, so the player can grow out of it. Set before presenting. */
    public void setOpenFromRect(Rect windowRect) {
        openFromRect = windowRect == null ? null : new Rect(windowRect);
    }

    public Rect getOpenFromRect() {
        return openFromRect;
    }

    private Rect openFromRect;

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
        // Autoplay and the panel's "now playing" mark both read the index, and a swap that came from
        // the related list (or from autoplay walking off the end of the show) has moved off the run.
        syncPlaylistIndex(row.ref);
        // A different video is a different film (or none): its cast, its copies and the open tab all
        // belong to the post we just left.
        movieDetail = null;
        currentTab = TAB_RELATED;
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
        if (playerHole != null) {
            // The new video may not have the shape of the old one, and the hole is measured from the
            // reference (see holeHeight). The list's spacer re-measures with the adapter; this does not.
            playerHole.requestLayout();
        }
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
        } else {
            // Already playable — it came from the link path, which carries no channel. Fill the
            // header behind the video rather than in front of it.
            enrichWatched();
        }
        loadRelated();
        loadMovie();
    }

    // ---------------- fragment ----------------

    /**
     * The page being dragged away under the player, on the way to the mini card.
     *
     * The list holds everything below the picture — title, actions, related — and the player itself
     * is an overlay above it, so moving the list IS moving "the rest of the page". Without this the
     * video slid to the corner while the page it belonged to stayed put, which read as two unrelated
     * things happening at once instead of one screen being put away.
     */
    public void setDragAway(float translationY, float alpha) {
        if (listView == null) return;
        listView.setTranslationY(translationY);
        listView.setAlpha(alpha);
    }

    /** Put the page back after a drag that did not commit. */
    public void resetDragAway() {
        if (listView == null) return;
        listView.animate().cancel();
        listView.animate().translationY(0).alpha(1f).setDuration(180).start();
    }

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
            final int type = position >= 0 && position < rows.size() ? rows.get(position) : -1;
            if (type == TYPE_ACTOR) {
                final int idx = position - firstTabRow;
                if (movieDetail != null && idx >= 0 && idx < movieDetail.actors.size()) {
                    final SvipeMovies.Actor a = movieDetail.actors.get(idx);
                    presentFragment(new SvipeActorActivity(a.id, a.name));
                }
                return;
            }
            if (type == TYPE_VERSION) {
                final int idx = position - firstTabRow;
                if (movieDetail != null && idx >= 0 && idx < movieDetail.versions.size()) {
                    final SvipeMovies.Version v = movieDetail.versions.get(idx);
                    // Another copy of the SAME film is not another video: swap the page onto it
                    // instead of stacking a second watch page for the film you are already on.
                    if (!isPlaying(v)) {
                        openItem(v.toItem());
                    }
                }
                return;
            }
            final Row row = relatedRowAt(position);
            if (row != null) {
                // A related video opens ON TOP of this one. Stacking rather than swapping is what
                // makes back mean "the video I was watching": this page stays alive underneath with
                // its scroll and its playback position, and the player hands itself over.
                org.telegram.svipe.video.SvipeVideoPlayerController.getInstance().expectHandover();
                presentFragment(new SvipeWatchActivity(row.ref));
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            final int type = position >= 0 && position < rows.size() ? rows.get(position) : -1;
            return type == TYPE_VERSION && pinVersion(position - firstTabRow);
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
        if (watched.mo == null) {
            resolveWatched();   // a seeded page is already holding the message — see #seeded
        }
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
     * The reserved hole, black, pinned under the status bar.
     *
     * <p>Sized from dimensions that are known BEFORE the first frame — the reference's, or the seeded
     * document's — never from the decoder: the overlay letterboxes the video inside whatever rect it
     * is given, so a hole that resized when the real dimensions arrived would shove the whole page
     * down mid-read. In landscape a full-width hole would eat the screen, so it is capped and the
     * overlay letterboxes into the wider rect instead.
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

    /**
     * 16:9 of the width, capped so an inline player cannot swallow a landscape window — and TALLER
     * than that, up to the same cap, for a video whose own aspect is taller.
     *
     * <p>The one-way clamp is the point. Every reference from the Video tab is horizontal, so it is
     * bounded by 16:9 and this is byte-identical to the fixed hole it replaces (an unknown aspect is
     * 16:9 too). Since any video in the app can now be opened here, a 9:16 phone clip also arrives —
     * and in a 16:9 hole it would be a stamp between two fat pillarbox bars.
     */
    private int holeHeight(int width) {
        final int cap = Math.round(Math.max(AndroidUtilities.displaySize.y, dp(320)) * 0.6f);
        final float aspect = watched != null && watched.ref != null ? watched.ref.aspect() : 16f / 9f;
        final int height = aspect > 0 && aspect < 16f / 9f
                ? Math.round(width / aspect)
                : Math.round(width * 9f / 16f);
        return Math.max(dp(1), Math.min(height, cap));
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
        if (hasChannelRow()) {
            rows.add(TYPE_CHANNEL);
        }
        rows.add(TYPE_ACTIONS);
        if (playlist != null && !playlist.isEmpty()) {
            rows.add(TYPE_PLAYLIST_BAR);
            if (playlistExpanded) {
                rows.add(TYPE_PLAYLIST_PANEL);
            }
        }
        if (hasCaptionBody()) {
            rows.add(TYPE_CAPTION);
        }
        firstRelatedRow = -1;
        firstTabRow = -1;
        if (hasFilmTabs()) {
            // A film: the three sections share one strip, and Related is the one that is open.
            rows.add(TYPE_TABS);
            firstTabRow = rows.size();
            if (currentTab == TAB_ACTORS) {
                for (int i = 0; i < movieDetail.actors.size(); i++) {
                    rows.add(TYPE_ACTOR);
                }
                if (movieDetail.actors.isEmpty()) {
                    rows.add(TYPE_TAB_EMPTY);
                }
            } else if (currentTab == TAB_VERSIONS) {
                for (int i = 0; i < movieDetail.versions.size(); i++) {
                    rows.add(TYPE_VERSION);
                }
                if (movieDetail.versions.isEmpty()) {
                    rows.add(TYPE_TAB_EMPTY);
                }
            } else {
                firstRelatedRow = rows.size();
                addRelatedRows();
            }
        } else if (!related.isEmpty() || loadingRelated) {
            rows.add(TYPE_RELATED_HEADER);
            firstRelatedRow = rows.size();
            addRelatedRows();
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    // ---------------- the playlist ----------------

    /**
     * Swap the page onto another episode of the same show.
     *
     * <p>In place, like a related tap: the overlay player keeps its surface and back still means "the
     * screen I came from" rather than the eighty-ninth watch page on a stack.
     */
    private void playEpisode(int index) {
        if (playlist == null || index < 0 || index >= playlistRows.size() || index == playlistIndex) {
            return;
        }
        playlistIndex = index;
        // openRow, not openItem: the playlist row may already hold its resolved message, and reusing
        // it paints the new episode's title and actions immediately instead of blanking for a round-trip.
        openRow(playlistRows.get(index));
    }

    /** Whether the run order has an episode {@code delta} steps from the one playing. */
    public boolean canPlayPlaylistStep(int delta) {
        final int to = playlistIndex + delta;
        return playlist != null && playlistIndex >= 0 && to >= 0 && to < playlistRows.size();
    }

    /**
     * Step along the run order — the player's ⏮ / ⏭ buttons. Returns false when there is nothing
     * that way, which is how the player knows to fall through to the related pipe instead.
     */
    public boolean playPlaylistStep(int delta) {
        if (!canPlayPlaylistStep(delta)) {
            return false;
        }
        playEpisode(playlistIndex + delta);
        return true;
    }

    /**
     * The episode after the one playing, for the player's autoplay step — or null at the end of the
     * show, which is where autoplay should stop rather than wander off into related videos.
     *
     * <p>Deliberately NOT filtered against what autoplay has already played: a playlist's order is
     * explicit and finite, so "next" is always index + 1 and the run ends by running out.
     */
    public SvipeDiscover.Item getPlaylistNext() {
        if (playlist == null || playlistIndex < 0) {
            return null;
        }
        final int next = playlistIndex + 1;
        return next < playlistRows.size() ? playlistRows.get(next).ref : null;
    }

    /** Keep {@link #playlistIndex} honest when the page is swapped by anything but a playlist tap. */
    private void syncPlaylistIndex(SvipeDiscover.Item ref) {
        if (playlist == null || ref == null) {
            return;
        }
        for (int i = 0; i < playlistRows.size(); i++) {
            final SvipeDiscover.Item candidate = playlistRows.get(i).ref;
            if (candidate.channelId == ref.channelId && candidate.messageId == ref.messageId) {
                playlistIndex = i;
                if (playlist.series != null) {
                    org.telegram.svipe.SvipeSeriesProgress.setLastEpisode(playlist.series.id, i);
                }
                return;
            }
        }
        playlistIndex = -1;   // the user tapped out of the show, into a related video
    }

    /**
     * Resolve the episodes around the one being drawn.
     *
     * <p>A show is a long list and every row wants a thumbnail, but a thumbnail costs a getMessages.
     * Resolving a window around what is on screen keeps the list filled while scrolling without
     * spending ninety requests on a user who watches one episode. The batcher already coalesces per
     * channel, so a window usually costs ONE request.
     */
    private void resolveAround(int index) {
        final int from = Math.max(0, index - 2);
        final int to = Math.min(playlistRows.size(), index + PLAYLIST_RESOLVE_AHEAD);
        final ArrayList<Row> want = new ArrayList<>();
        for (int i = from; i < to; i++) {
            final Row row = playlistRows.get(i);
            if (row.mo == null && !row.resolving) {
                want.add(row);
            }
        }
        if (!want.isEmpty()) {
            resolveBatch(want);
        }
    }

    /** The related cards, or their shimmer stand-ins while the first page is still in flight. */
    private void addRelatedRows() {
        if (!related.isEmpty()) {
            for (int i = 0; i < related.size(); i++) {
                rows.add(TYPE_RELATED);
            }
            return;
        }
        firstRelatedRow = -1;
        for (int i = 0; i < RELATED_SKELETONS; i++) {
            rows.add(TYPE_RELATED_SKELETON);
        }
    }

    /**
     * True when this post is a copy of a film we know — the only case with anything to put in a
     * second and third tab. A concert, a serial episode or an ordinary long video has no film row
     * behind it and keeps the plain related header.
     */
    private boolean hasFilmTabs() {
        return movieDetail != null
                && (!movieDetail.actors.isEmpty() || !movieDetail.versions.isEmpty());
    }

    private void selectTab(int tab) {
        if (currentTab == tab) {
            return;
        }
        currentTab = tab;
        rebuildRows();
        if (tab == TAB_RELATED && related.isEmpty() && !loadingRelated) {
            loadRelated();
        }
    }

    /** The film copy playing right now, matched on the ids the page was opened with. */
    private boolean isPlaying(SvipeMovies.Version v) {
        return watched != null && watched.ref != null && v != null
                && watched.ref.channelId == v.channelId && watched.ref.messageId == v.messageId;
    }

    /**
     * The channel row needs something to name: the resolved chat, or the handle the reference came
     * with. A video opened from a private chat has neither, and the row would render "@null" under a
     * blank avatar.
     */
    private boolean hasChannelRow() {
        return watched.chat != null
                || watched.ref != null && watched.ref.username != null && !watched.ref.username.isEmpty();
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
        // Once we know this post is a copy of a film, the page is that FILM's page: it is named after
        // the film, not after whatever the uploading channel wrote above it ("PREMYERA🔥"). The caption
        // itself is not lost — it is the description row further down.
        if (movieDetail != null && movieDetail.movie != null
                && movieDetail.movie.title != null && !movieDetail.movie.title.isEmpty()) {
            return movieDetail.movie.title;
        }
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

    /** Re-run the MTProto resolve after it failed. The player's retry button is the only caller. */
    public void retryResolve() {
        if (watched.mo != null) {
            return;   // seeded: there is nothing to resolve, and a local reference has no handle anyway
        }
        resolveWatched();
    }

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
            // A message that arrived through the link path has no chat with it: fill the header
            // behind the video (SvipeWatchActivity#enrichWatched).
            enrichWatched();
            // Unconditional on purpose. The controller opened this reference with resolveHere=false
            // (SvipeVideoPlayerController.onWatchPageOpened) and is waiting on exactly this callback;
            // firing it only on success left a failed resolve as a permanently black player with no
            // error and no retry. onWatchItemResolved is a no-op when the message is still null, and
            // the controller surfaces the failure from there.
            if (holeListener != null) {
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
        // A related list is a dozen DIFFERENT channels, so this page is the app's biggest single
        // source of contacts.resolveUsername — measured at 20 inside the same 100 ms. Anything the
        // device already knows must come from memory or the local database instead; only a genuinely
        // new channel is worth an RPC. See SvipeChannelResolve.
        org.telegram.svipe.SvipeChannelResolve.lookup(currentAccount, head.ref.channelId, local -> {
            if (local != null) {
                resolvedChats.put(username, local);
                fetchMessages(local, group, done);
                return;
            }
            webPageGroup(username, head.ref.channelId, group, done);
        });
    }

    /**
     * Episode thumbnails and related cards through the posts' own links — no channel bought.
     *
     * <p>This page holds the app's widest spread of channels: a related list is a dozen different
     * ones and a show's playlist can be ninety posts. It used to be the biggest single source of
     * contacts.resolveUsername for exactly that reason. Rows whose link has no preview at all are
     * collected and cost ONE resolve between them, instead of a column of blanks.
     */
    private void webPageGroup(final String username, final long channelId,
                              final ArrayList<Row> group, final Runnable done) {
        final int[] pending = {group.size()};
        final ArrayList<Row> missed = new ArrayList<>();
        for (Row row : group) {
            final Row r = row;
            org.telegram.svipe.video.SvipeWebRef.fetch(currentAccount, username, r.ref.messageId,
                    channelId, (mo, page) -> {
                if (mo != null) {
                    r.mo = mo;
                    r.resolving = false;
                    org.telegram.svipe.SvipeObserved.note(currentAccount, r.ref, mo);
                } else {
                    missed.add(r);
                }
                if (--pending[0] == 0) {
                    if (!missed.isEmpty()) {
                        sendResolveGroup(username, missed, done);
                    } else if (done != null) {
                        done.run();
                    }
                    if (adapter != null) adapter.notifyDataSetChanged();
                }
            });
        }
    }

    /**
     * Fill the header for a video that opened through its link: the channel (avatar, name,
     * subscribe) and the post's own view count, which a preview does not carry.
     *
     * <p>Runs AFTER the video is playing and through the paced lane, so a flood window costs a plain
     * header rather than the video. The real message is not swapped in — its counters are copied onto
     * the one on screen, because the document, and so the file being played, is the same either way.
     */
    private void enrichWatched() {
        if (watched == null || watched.chat != null || watched.ref == null
                || watched.ref.username == null || watched.ref.username.isEmpty()) {
            return;
        }
        final Row row = watched;
        final String username = row.ref.username.toLowerCase();
        final TLRPC.Chat cached = resolvedChats.get(username);
        if (cached != null) {
            row.chat = cached;
            refreshHeader(row);
            return;
        }
        org.telegram.svipe.SvipeChannelResolve.lookup(currentAccount, row.ref.channelId, local -> {
            if (local != null) {
                resolvedChats.put(username, local);
                row.chat = local;
                refreshHeader(row);
                return;
            }
            final ArrayList<Row> single = new ArrayList<>();
            single.add(row);
            // Counters only: the message this page is playing must not be replaced under the player.
            enrichRow(username, single);
        });
    }

    private void enrichRow(final String username, final ArrayList<Row> single) {
        final Row row = single.get(0);
        final MessageObject playing = row.mo;
        sendResolveGroup(username, single, () -> {
            if (row.mo != null && row.mo != playing && playing != null
                    && row.mo.messageOwner != null && playing.messageOwner != null) {
                playing.messageOwner.views = row.mo.messageOwner.views;
                playing.messageOwner.forwards = row.mo.messageOwner.forwards;
                playing.messageOwner.reactions = row.mo.messageOwner.reactions;
                if (row.mo.messageOwner.date != 0) playing.messageOwner.date = row.mo.messageOwner.date;
                row.mo = playing;   // keep the object the player was handed
            }
            refreshHeader(row);
        });
    }

    private void refreshHeader(Row row) {
        AndroidUtilities.runOnUIThread(() -> {
            if (adapter != null) adapter.notifyDataSetChanged();
        });
    }

    private void sendResolveGroup(final String username, final ArrayList<Row> group, final Runnable done) {
        final Row head = group.get(0);
        if (org.telegram.svipe.SvipeChannelResolve.blocked(currentAccount)) {
            // Inside an open flood window: asking again is what makes Telegram extend it.
            for (Row r : group) {
                r.resolving = false;
            }
            if (done != null) done.run();
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                        org.telegram.svipe.SvipeChannelResolve.noteError(currentAccount, error);
                        for (Row r : group) {
                            r.resolving = false;
                        }
                        if (done != null) done.run();
                        return;
                    }
                    TLRPC.TL_contacts_resolvedPeer rp = (TLRPC.TL_contacts_resolvedPeer) response;
                    // Persisted as well as cached: this page's resolves are the ones that add up.
                    org.telegram.svipe.SvipeChannelResolve.remember(currentAccount, rp);
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
        if (watched == null || watched.ref == null || isLocal()) {
            return;   // asking "is this post a film" would put a private post's ids on the wire
        }
        SvipeMovies.movieByPost(currentAccount, watched.ref.channelId, watched.ref.messageId,
                (detail, error) -> AndroidUtilities.runOnUIThread(() -> {
                    // Kept even with an empty cast: the copies alone are worth a Variants tab, and a
                    // film whose cast never matched Wikidata still has them.
                    if (detail == null) {
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
        final SvipeDiscover.Callback callback = (items, next, error) -> {
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
        };
        if (isLocal()) {
            // A local video cannot seed the related list — the seed IS its ids, on the wire. The plain
            // long-form pipe answers "what should this user watch" instead of "what goes with THIS",
            // which is a worse related list and the only honest one available here. Identical response
            // shape, so the cursor, the dedupe and the failure cap above are untouched.
            SvipeDiscover.videos(currentAccount, null, offset, RELATED_PAGE_SIZE, false, callback);
            return;
        }
        // Retrieved from the video on screen (see SvipeDiscover#relatedVideos): the next episode of
        // its show, then the nearest videos by caption embedding, then more from its channel.
        SvipeDiscover.relatedVideos(currentAccount, seed.ref.channelId, seed.ref.messageId,
                offset, RELATED_PAGE_SIZE, callback);
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
        // The reaction itself is Telegram's and always goes through; only OUR event is suppressed for
        // a video that is nobody's business but the user's.
        if (!isLocal() && displayingReferencedPost()) {
            SvipeDiscover.sendEvent(currentAccount, watched.ref.channelId, watched.ref.messageId,
                    newLiked ? "LIKE" : "UNLIKE", null);
        }
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
        if (getParentActivity() == null || ref == null || sharePending) {
            return;
        }
        // A reference can reach this button without a share url — an episode served by an older
        // backend, a film version, a video restored from a cold queue — and the fallback below is a
        // t.me link, the one share that ends somewhere other than Svipe. Mint the owned link first.
        if ((ref.shareUrl == null || ref.shareUrl.isEmpty()) && !isLocal()
                && ref.username != null && !ref.username.isEmpty()) {
            sharePending = true;
            org.telegram.svipe.SvipeShareLink.mint(currentAccount, ref.channelId, ref.messageId, url -> {
                sharePending = false;
                if (url != null) {
                    ref.shareUrl = url;
                }
                if (getParentActivity() != null) {
                    shareNow();
                }
            });
            return;
        }
        shareNow();
    }

    /** True while a share is waiting on a minted link, so a second tap cannot start a second one. */
    private boolean sharePending;

    /**
     * The link this video is shared BY.
     *
     * <p>Inside a show it carries the show's code as {@code ?p=<code>}: the recipient then opens the
     * episode WITH the playlist around it — panel, position, running order — instead of a loose video
     * that happens to be episode seven of something. The context is a query parameter and not a code
     * of its own on purpose: a video is one video with one stable link, and the same episode shared
     * from three lists must not mint three pages.
     *
     * <p>A show with no share url of its own is one the server does not publish. Its episodes are then
     * shared alone, which is what "unless the playlist is private" means in practice.
     */
    private String shareLink() {
        final SvipeDiscover.Item ref = watched.ref;
        if (ref == null) {
            return null;
        }
        if (ref.shareUrl == null || ref.shareUrl.isEmpty()) {
            return ref.username != null && !ref.username.isEmpty()
                    ? "https://t.me/" + ref.username + "/" + ref.messageId : null;
        }
        final String list = playlistIndex >= 0 && playlist != null
                ? org.telegram.svipe.SvipeShareLink.codeOf(playlist.shareUrl) : null;
        if (list == null) {
            return ref.shareUrl;
        }
        return ref.shareUrl + (ref.shareUrl.indexOf('?') >= 0 ? "&" : "?") + "p=" + list;
    }

    private void shareNow() {
        final SvipeDiscover.Item ref = watched.ref;
        if (getParentActivity() == null || ref == null) {
            return;
        }
        final String link = shareLink();
        // A video opened out of the user's own chats has no public link, and the promo caption would
        // advertise a page nobody else can open. Forward the document by itself instead — refusing to
        // share was this chip silently doing nothing, which reads as a broken button. Except when the
        // source protects its content: Telegram takes forwarding away there, and this page must not
        // be the one surface that hands it back.
        final MessageObject mo = watched.mo;
        if (link == null && (mo == null || forwardsRestricted(mo))) {
            return;
        }
        final String caption = link == null ? null
                : getString(R.string.SvipeSharePromo) + "\n\n" + link.replaceFirst("^https?://", "");
        try {
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
            } else if (link != null) {
                // Not resolved yet — share the promo text + link alone rather than nothing.
                showDialog(new ShareAlert(getParentActivity(), null, caption, false, link, false));
            } else {
                return;   // no document to forward and no link to send in its place
            }
            if (!isLocal() && displayingReferencedPost()) {
                SvipeDiscover.sendEvent(currentAccount, ref.channelId, ref.messageId, "SHARE", null);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** The message's own restriction plus the peer's, which is how the rest of the app reads it. */
    private boolean forwardsRestricted(MessageObject mo) {
        if (mo.messageOwner == null || mo.messageOwner.noforwards) {
            return true;
        }
        return MessagesController.getInstance(currentAccount).isPeerNoForwards(mo.getDialogId());
    }

    /** Subscribe / unsubscribe, exactly as the reels rail does it. */
    private void toggleFollow() {
        final TLRPC.Chat chat = watched.chat;
        // isLocal() as well as the null check: a private channel or a group seed has a perfectly real
        // Chat, and subscribing to it is neither something this page should offer nor something whose
        // id may be posted. The button is hidden there too (ChannelView#bind).
        if (chat == null || isLocal()) {
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
            final int type = holder.getItemViewType();
            return type == TYPE_RELATED || type == TYPE_ACTOR || type == TYPE_VERSION;
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
                case TYPE_TABS:
                    view = new SectionTabsView(ctx);
                    break;
                case TYPE_ACTOR:
                case TYPE_VERSION:
                    view = new UserCell(ctx, 6, 0, false, getResourceProvider());
                    break;
                case TYPE_RELATED_HEADER:
                    view = sectionHeader(ctx);
                    break;
                case TYPE_TAB_EMPTY: {
                    TextView empty = new TextView(ctx);
                    empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(dp(16), dp(28), dp(16), dp(28));
                    empty.setText(getString(R.string.NoResult));
                    view = empty;
                    break;
                }
                case TYPE_RELATED_SKELETON:
                    view = new RelatedSkeletonView(ctx);
                    break;
                case TYPE_PLAYLIST_BAR:
                    view = new PlaylistBarView(ctx);
                    break;
                case TYPE_PLAYLIST_PANEL:
                    view = new PlaylistPanelView(ctx);
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
                case TYPE_TABS:
                    ((SectionTabsView) holder.itemView).bind();
                    break;
                case TYPE_ACTOR:
                    bindActor((UserCell) holder.itemView, position - firstTabRow);
                    break;
                case TYPE_VERSION:
                    bindVersion((UserCell) holder.itemView, position - firstTabRow);
                    break;
                case TYPE_RELATED: {
                    final Row row = related.get(position - firstRelatedRow);
                    ((SvipeWideVideoCell) holder.itemView).bind(row.ref, row.mo, row.chat);
                    break;
                }
                case TYPE_PLAYLIST_BAR:
                    ((PlaylistBarView) holder.itemView).bind();
                    break;
                case TYPE_PLAYLIST_PANEL:
                    ((PlaylistPanelView) holder.itemView).bind();
                    break;
                default:
                    break;   // spacer / header / skeleton render themselves
            }
        }
    }


    /**
     * The playlist panel's header — show title, position, and the fold.
     *
     * <p>Shaped like YouTube's: a tinted block directly under the action row that says WHICH list you
     * are inside and where in it you are, and folds away when you would rather see the page. The
     * share icon shares the SHOW (svipe.uz/<code>), not the episode: what is worth passing on here is
     * "this show is in Svipe", and the link opens a page that offers the app.
     */
    private class PlaylistBarView extends FrameLayout {
        private final TextView title;
        private final TextView position;
        private final ImageView chevron;
        private final ImageView shareIcon;
        private final LinearLayout block;

        PlaylistBarView(Context context) {
            super(context);
            setPadding(dp(12), dp(6), dp(12), dp(6));

            block = new LinearLayout(context);
            block.setOrientation(LinearLayout.HORIZONTAL);
            block.setGravity(Gravity.CENTER_VERTICAL);
            block.setPadding(dp(14), dp(10), dp(6), dp(10));
            block.setBackground(panelBackground(true, false));
            addView(block, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);

            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.bold());
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            position = new TextView(context);
            position.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            position.setMaxLines(1);
            position.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            texts.addView(position, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            block.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            shareIcon = new ImageView(context);
            shareIcon.setScaleType(ImageView.ScaleType.CENTER);
            shareIcon.setImageResource(R.drawable.msg_share);
            shareIcon.setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN));
            shareIcon.setOnClickListener(v -> shareSeries());
            block.addView(shareIcon, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL));

            chevron = new ImageView(context);
            chevron.setScaleType(ImageView.ScaleType.CENTER);
            chevron.setImageResource(R.drawable.arrow_more);
            chevron.setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN));
            block.addView(chevron, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL));

            block.setOnClickListener(v -> togglePlaylist());
        }

        void bind() {
            if (playlist == null || playlist.series == null) {
                return;
            }
            title.setText(playlist.series.title);
            position.setText(LocaleController.formatString(R.string.SvipePlaylistPosition,
                    Math.max(playlistIndex, 0) + 1, playlistRows.size()));
            // Open, the header is the TOP of one block that the episodes sit inside: square bottom
            // corners, no gap underneath. Closed, it is a card on its own again. The episodes used to
            // float on the page background below a rounded header, which read as two unrelated things.
            block.setBackground(panelBackground(true, !playlistExpanded));
            setPadding(dp(12), dp(6), dp(12), playlistExpanded ? 0 : dp(6));
            chevron.setRotation(playlistExpanded ? 180f : 0f);
            shareIcon.setVisibility(
                    playlist.shareUrl != null && !playlist.shareUrl.isEmpty() ? VISIBLE : GONE);
        }
    }

    /**
     * The playlist block's background: the bar and the episode list are ONE tinted card, so the
     * rounding lives on the outside of the pair — top corners on the header, bottom corners on the
     * list, and nothing in the seam between them.
     */
    private android.graphics.drawable.Drawable panelBackground(boolean top, boolean bottom) {
        final float r = dp(12);
        final float t = top ? r : 0f;
        final float b = bottom ? r : 0f;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
        bg.setCornerRadii(new float[]{t, t, t, t, b, b, b, b});
        return bg;
    }

    private void togglePlaylist() {
        playlistExpanded = !playlistExpanded;
        rebuildRows();
    }

    /**
     * Share the SHOW as a svipe.uz link.
     *
     * <p>The whole reason the playlist is not a Telegram channel: a channel link ends in Telegram and
     * teaches nobody that Svipe exists, while this link opens a page that shows the show and offers
     * the app. Falls back to nothing rather than to a t.me link — sharing the episode instead of the
     * show is what the episode's own share chip is for.
     */
    private void shareSeries() {
        if (playlist == null || getParentActivity() == null) {
            return;
        }
        final String link = playlist.shareUrl;
        if (link == null || link.isEmpty()) {
            return;
        }
        final String caption = getString(R.string.SvipeSharePromo) + "\n\n"
                + link.replaceFirst("^https?://", "");
        showDialog(new ShareAlert(getParentActivity(), null, caption, false, link, false));
    }

    /**
     * The bounded episode list — the panel itself.
     *
     * <p>Four and a half rows tall, scrolling INSIDE the page rather than as part of it, and parked so
     * the episode playing sits second from the top: the previous one above it, the next ones below.
     * That is the whole fix for "I have to scroll to find where I am" — the answer is always in the
     * same place, one thumb-width under the player, however long the show is.
     *
     * <p>The half row is deliberate. A panel cut off mid-row says it scrolls; a panel that ends on a
     * clean edge reads as the complete list, and the user never drags it.
     */
    private class PlaylistPanelView extends FrameLayout {
        /** EpisodeCell: 54dp of thumbnail plus its 4dp padding, top and bottom. */
        private static final int ROW_HEIGHT_DP = 62;
        private static final float VISIBLE_ROWS = 4.5f;

        private final RecyclerListView list;
        private final LinearLayoutManager manager;
        private final EpisodeAdapter episodeAdapter;
        private float downY;

        PlaylistPanelView(Context context) {
            super(context);
            // The same 12dp inset the bar uses, so the two are one card and not two.
            setPadding(dp(12), 0, dp(12), dp(6));
            list = new RecyclerListView(context);
            manager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
            list.setLayoutManager(manager);
            episodeAdapter = new EpisodeAdapter();
            list.setAdapter(episodeAdapter);
            list.setOnItemClickListener((view, position) -> playEpisode(position));
            list.setBackground(panelBackground(false, true));
            // Rounded corners are only rounded if what is inside them is clipped to the shape.
            list.setClipToOutline(true);
            list.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, -dp(12), view.getWidth(), view.getHeight(), dp(12));
                }
            });
            addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        /**
         * Keep the drag: a vertical gesture inside the panel belongs to the panel while it still has
         * somewhere to go, and to the page the moment it does not. Without this the outer list steals
         * every drag at the touch slop and the panel is a picture of a list.
         */
        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            final ViewGroup parent = (ViewGroup) getParent();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = ev.getY();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    final float dy = ev.getY() - downY;
                    final boolean mine = dy < 0 ? list.canScrollVertically(1) : list.canScrollVertically(-1);
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(mine);
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                    break;
                default:
                    break;
            }
            return super.dispatchTouchEvent(ev);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int rows = Math.max(1, playlistRows.size());
            final float shown = Math.min(rows, VISIBLE_ROWS);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                    Math.round(dp(ROW_HEIGHT_DP) * shown) + getPaddingBottom(), MeasureSpec.EXACTLY));
        }

        void bind() {
            episodeAdapter.notifyDataSetChanged();
            if (playlistParkedFor == playlistIndex) {
                // Already parked for this episode. Re-parking on every rebind would yank the panel
                // back under the user's thumb every time a thumbnail resolved, which is the opposite
                // of a list you can browse.
                return;
            }
            playlistParkedFor = playlistIndex;
            // One above the one playing: the previous episode is the second thing a viewer reaches for
            // (the first is the next one, which is already below the fold of the thumb).
            manager.scrollToPositionWithOffset(Math.max(0, playlistIndex - 1), 0);
        }
    }

    private class EpisodeAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return playlistRows.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view = new EpisodeCell(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((EpisodeCell) holder.itemView).bind(position);
            // Resolve what is on screen and nothing else: a show can be ninety episodes, and resolving
            // all of them up front is ninety getMessages nobody asked for.
            resolveAround(position);
        }
    }

    /**
     * One episode in the panel.
     *
     * <p>Compact on purpose — a show is dozens of rows and the full-width card the related list uses
     * would make the panel a second page. The row that is playing is marked rather than merely
     * highlighted: colour alone disappears on a bright thumbnail.
     */
    private class EpisodeCell extends FrameLayout {
        private final BackupImageView thumb;
        private final TextView index;
        private final TextView label;
        private final TextView duration;
        private final ImageView playing;

        EpisodeCell(Context context) {
            super(context);
            setPadding(dp(8), dp(4), dp(8), dp(4));

            index = new TextView(context);
            index.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            index.setGravity(Gravity.CENTER);
            index.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            addView(index, LayoutHelper.createFrame(24, 54, Gravity.LEFT | Gravity.CENTER_VERTICAL, 0, 0, 0, 0));

            playing = new ImageView(context);
            playing.setScaleType(ImageView.ScaleType.CENTER);
            playing.setImageResource(R.drawable.msg_played);
            playing.setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_chats_actionBackground), PorterDuff.Mode.SRC_IN));
            addView(playing, LayoutHelper.createFrame(24, 54, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            thumb = new BackupImageView(context);
            thumb.setRoundRadius(dp(6));
            addView(thumb, LayoutHelper.createFrame(96, 54, Gravity.LEFT | Gravity.CENTER_VERTICAL, 30, 0, 0, 0));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setGravity(Gravity.CENTER_VERTICAL);

            label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            texts.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            duration = new TextView(context);
            duration.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            duration.setMaxLines(1);
            duration.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            texts.addView(duration, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.CENTER_VERTICAL, 134, 0, 8, 0));
        }

        void bind(int position) {
            if (playlist == null || position < 0 || position >= playlist.episodes.size()) {
                return;
            }
            final SvipeMovies.Episode episode = playlist.episodes.get(position);
            final boolean isNow = position == playlistIndex;
            index.setVisibility(isNow ? GONE : VISIBLE);
            playing.setVisibility(isNow ? VISIBLE : GONE);
            index.setText(String.valueOf(position + 1));
            label.setText(episode.label(position));
            label.setTypeface(isNow ? AndroidUtilities.bold() : android.graphics.Typeface.DEFAULT);
            label.setTextColor(Theme.getColor(isNow
                    ? Theme.key_chats_actionBackground : Theme.key_windowBackgroundWhiteBlackText));
            duration.setText(episode.durationMs > 0
                    ? AndroidUtilities.formatShortDuration(episode.durationMs / 1000) : "");

            // The thumbnail is whatever the resolve has produced so far: a placeholder colour until
            // MTProto answers, never a blank the row later grows into (the size is fixed above).
            final MessageObject mo = playlistRows.get(position).mo;
            final TLRPC.PhotoSize size = mo == null ? null
                    : org.telegram.messenger.FileLoader.getClosestPhotoSizeWithSize(
                            mo.photoThumbs, AndroidUtilities.dp(96), true);
            if (size != null) {
                thumb.setImage(org.telegram.messenger.ImageLocation.getForObject(size, mo.photoThumbsObject),
                        "96_54", null, null, mo);
            } else {
                thumb.setImageDrawable(new android.graphics.drawable.ColorDrawable(
                        Theme.getColor(Theme.key_windowBackgroundGray)));
            }
        }
    }

    /**
     * The cast strip: horizontally scrolling round avatars under the player, each opening that
     * performer's ActorProfile. Names are the uploading channel's own Uzbek spelling — see
     * {@link SvipeActorActivity} for why that is the right label rather than a romanisation.
     */
    /**
     * The section strip — Related | Actors | Variants — in the app's own pinned-tab shape (the same
     * one the profile screens use), so a film's page reads as one screen and not as a video page with
     * a film page bolted under it. Related is selected on open: whatever else a film is, the page is
     * still a player.
     */
    private class SectionTabsView extends FrameLayout {
        private final LinearLayout row;

        SectionTabsView(Context context) {
            super(context);
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.TOP, 8, 4, 8, 4));
        }

        void bind() {
            row.removeAllViews();
            addTab(TAB_RELATED, getString(R.string.SvipeRelatedVideos));
            addTab(TAB_ACTORS, getString(R.string.SvipeMovieActors));
            addTab(TAB_VERSIONS, getString(R.string.SvipeMovieVersions));
        }

        private void addTab(int tab, CharSequence label) {
            final boolean selected = currentTab == tab;
            TextView tv = new TextView(getContext());
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            tv.setTypeface(AndroidUtilities.bold());
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setText(label);
            tv.setTextColor(Theme.getColor(selected
                    ? Theme.key_windowBackgroundWhiteBlackText
                    : Theme.key_windowBackgroundWhiteGrayText));
            final int bg = Theme.getColor(selected
                    ? Theme.key_listSelector : Theme.key_windowBackgroundGray);
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setColor(bg);
            shape.setCornerRadius(dp(16));
            tv.setBackground(shape);
            tv.setOnClickListener(v -> selectTab(tab));
            LinearLayout.LayoutParams lp = LayoutHelper.createLinear(0, 36, 1f, Gravity.CENTER_VERTICAL);
            lp.leftMargin = dp(4);
            lp.rightMargin = dp(4);
            row.addView(tv, lp);
        }
    }

    /** One cast member: the same row the film page used, so the move cost the list nothing. */
    private void bindActor(UserCell cell, int index) {
        if (movieDetail == null || index < 0 || index >= movieDetail.actors.size()) {
            return;
        }
        final SvipeMovies.Actor a = movieDetail.actors.get(index);
        AvatarDrawable avatar = new AvatarDrawable();
        avatar.setInfo(a.id, a.name, null);
        cell.setData(null, a.name,
                a.movieCount > 0
                        ? a.movieCount + " " + getString(R.string.SvipeActorFilmography)
                        : getString(R.string.SvipeMovieCast),
                0, index != movieDetail.actors.size() - 1);
        cell.avatarImageView.setImageDrawable(avatar);
    }

    /** One copy of the film: which channel posted it, at what quality, in what language. */
    private void bindVersion(UserCell cell, int index) {
        if (movieDetail == null || index < 0 || index >= movieDetail.versions.size()) {
            return;
        }
        final SvipeMovies.Version v = movieDetail.versions.get(index);
        final String name = v.channelTitle != null && !v.channelTitle.isEmpty()
                ? v.channelTitle : ("@" + (v.username == null ? "" : v.username));
        AvatarDrawable avatar = new AvatarDrawable();
        avatar.setInfo(v.channelId, name, null);
        cell.setData(null, name, versionStatus(v), 0, index != movieDetail.versions.size() - 1);
        cell.avatarImageView.setImageDrawable(avatar);
    }

    /** "1080p • uz • 👤 12 • ✓", each part dropped when unknown; ▶ marks the copy on screen. */
    private String versionStatus(SvipeMovies.Version v) {
        StringBuilder sb = new StringBuilder();
        if (isPlaying(v)) {
            sb.append("▶ ");
        }
        if (v.quality != null && !v.quality.isEmpty()) {
            sb.append(v.quality);
        } else if (v.height > 0) {
            sb.append(v.height).append("p");
        }
        if (v.language != null && !v.language.isEmpty()) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(v.language);
        }
        if (v.votes > 0) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append("👤 ").append(v.votes);
        }
        if (v.isDefault) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append("✓");
        }
        return sb.toString();
    }

    /**
     * Long-press a copy to pin it: "this dub, this encode is the one I want". It is also the crowd
     * vote that elects everyone else's default, which is why it is a deliberate gesture and not a side
     * effect of watching one.
     */
    private boolean pinVersion(int index) {
        if (movieDetail == null || pinInFlight || index < 0 || index >= movieDetail.versions.size()) {
            return false;
        }
        final SvipeMovies.Version v = movieDetail.versions.get(index);
        pinInFlight = true;
        SvipeMovies.setDefault(currentAccount, movieDetail.movie.id, v.channelId, v.messageId,
                (ok, error) -> AndroidUtilities.runOnUIThread(() -> {
                    pinInFlight = false;
                    if (!ok || movieDetail == null) {
                        return;
                    }
                    for (SvipeMovies.Version other : movieDetail.versions) {
                        other.isDefault = other == v;
                    }
                    movieDetail.myDefault = v;
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    BulletinFactory.of(SvipeWatchActivity.this)
                            .createSimpleBulletin(R.raw.chats_infotip,
                                    getString(R.string.SvipeMovieDefaultSet)).show();
                }));
        return true;
    }

    /** Row 0: the hole the pinned player occupies. Same height, computed the same way. */
    private class HoleSpacerView extends View {
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
            final CharSequence videoMeta =
                    SvipeWideVideoCell.metaLine(currentAccount, watched.ref, watched.mo, watched.chat);
            if (movieDetail != null && movieDetail.movie != null) {
                // "2016 · ★ 7.7 · Komediya" first, then the copy's own line — the film is the subject
                // here, and which channel posted this copy is what the channel row underneath says.
                final String film = SvipeMovies.cardMeta(movieDetail.movie);
                meta.setText(film.isEmpty() ? videoMeta : film);
            } else {
                meta.setText(videoMeta);
            }
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
                // No chat yet — this post opened through its public link (SvipeWebRef). The link
                // itself carries the channel's NAME, so show that rather than the raw handle while
                // the background enrichment fetches the real thing.
                final String fromPage =
                        org.telegram.svipe.video.SvipeWebRef.channelTitle(watched.ref.channelId);
                final String shown = fromPage != null ? fromPage : ("@" + watched.ref.username);
                AvatarDrawable ad = new AvatarDrawable();
                ad.setInfo(watched.ref.channelId, shown, null);
                avatar.setImageDrawable(ad);
                name.setText(shown);
            }
            // Opportunistic: shown only when the full chat is already cached — a watch page must not
            // spend a round-trip on a subscriber count.
            TLRPC.ChatFull full = chat == null ? null : MessagesController.getInstance(currentAccount).getChatFull(chat.id);
            if (full != null && full.participants_count > 0) {
                subtitle.setVisibility(VISIBLE);
                // A local source can be a group as well as a channel, and a group has members, not
                // subscribers — the label follows the chat, the way the rest of the app writes it.
                final boolean broadcast = ChatObject.isChannel(chat) && !chat.megagroup;
                subtitle.setText(LocaleController.formatPluralString(
                        broadcast ? "Subscribers" : "Members", full.participants_count));
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
            // Hidden for a local source: subscribing is a public-channel action, and on a private
            // channel the user is already in, this button would offer to LEAVE it from a video page.
            follow.setVisibility(chat == null || isLocal() ? GONE : VISIBLE);
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
            // Labelled like every other chip: an icon-only pill in a row of labelled ones reads as a
            // decoration and gets missed entirely.
            saveChip.setLabel(getString(R.string.SvipeReelsSave));
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
