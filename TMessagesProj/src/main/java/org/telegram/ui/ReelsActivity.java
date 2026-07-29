package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.util.Base64;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.svipe.SvipeApi;
import org.telegram.svipe.SvipeAuth;
import org.telegram.svipe.SvipeBlockedChannels;
import org.telegram.svipe.SvipeFeedRetry;
import org.telegram.svipe.SvipePreloadPlan;
import org.telegram.svipe.SvipeQueuePlan;
import org.telegram.svipe.SvipeReelQueue;
import org.telegram.svipe.SvipeWatchedSet;
import org.telegram.svipe.SvipeWatchEvent;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.StatsController;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileStreamLoadOperation;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Svipe "Reels" — vertical short-video feed over the Svipe backend with a native TikTok-style
 * action rail (like = total reactions, comments, share, more) and a channel bar (avatar + follow).
 * Everything reuses Telegram's own components (VideoPlayer, ShareAlert, reactions, ReportBottomSheet).
 */
public class ReelsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private final int account = UserConfig.selectedAccount;
    private static final String LIKE_EMOJI = "❤";
    private static final int PREFETCH_AHEAD = 5; // resolve + warm bytes for the next N reels
    private static final int LOAD_MORE_AHEAD = 4; // ask for the next page this close to the end
    private static final int MAX_EMPTY_APPEND_PAGES = 25; // safety cap: chain through this many all-watched pages before giving up
    // Stuck-reel watchdog thresholds. A cached/fast reel fires its first frame in <100ms (well inside
    // the grace), so only a genuinely parked stream (zero buffered-position growth) or a reel that
    // is long overdue for its first frame ever trips this.
    private static final long STUCK_FIRST_FRAME_GRACE_MS = 2000; // wait this long before the first tick
    private static final long STUCK_TICK_MS = 1200;              // sample interval
    private static final int STUCK_TICKS = 2;                    // consecutive no-progress ticks => stuck
    private static final int MAX_STUCK_RECOVERIES = 2;           // quick attempts; past this, cooldown-paced retries
    // 5s beats the user's own patience (field reports: manual skip-and-back at ~3-5s). A recovery
    // is cheap — completed chunks survive the cancel — so firing on an honestly-slow reel only
    // costs the in-flight chunk + a 250ms rebuild, while firing late means the user rescues
    // manually and concludes the app can't heal itself.
    private static final long STUCK_HARD_DEADLINE_MS = 5000;     // no first frame this long after (re)arm => stuck even if bytes trickle
    private static final long STUCK_RETRY_COOLDOWN_MS = 6000;    // pace of retries past the quick cap — the watchdog never gives up
    private static final long STUCK_REBUILD_DELAY_MS = 250;      // let cancels drain on the loader thread before rebuilding
    private static final long PLAYBACK_START_DEADLINE_MS = 4000; // no player at all this long after a page change => re-kick
    private static final int MAX_RESOLVE_RETRIES = 3;            // transient resolve failures: bounded retry
    private static final long RESOLVE_RETRY_DELAY_MS = 1500;

    private SizeNotifierFrameLayout root;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private ReelsAdapter adapter;
    private TextView statusView;

    // ---- Search-seeded reels: persistent native discussion-comment input at the bottom ----
    // Only present when opened from the Search Explore grid (ofDiscoverSeed -> seed_channel),
    // where the floating Telegram bottom tab bar is absent. Posts comments into the current
    // reel's channel post discussion thread (the same input as the linked discussion chat).
    private boolean discoverSeed;
    private ChatActivityEnterView reelEnterView;      // the real Telegram input bar
    private FrameLayout reelDisabledBar;              // shown when the current post has comments off
    // Resolved discussion thread root for the CURRENT reel; rebuilt every time the pager moves.
    private MessageObject commentThreadRoot;
    private long commentThreadForChannelId;          // which channel/message commentThreadRoot belongs to
    private int commentThreadForMessageId;
    private boolean commentThreadResolving;
    private CharSequence pendingCommentToSend;        // a send issued before the thread resolved
    private static final int COMMENT_BAR_HEIGHT_DP = 56;

    private final ArrayList<FeedItem> items = new ArrayList<>();
    private String recommendationId;
    private String token;
    private boolean loadingFeed;
    private boolean feedLoadFailed; // auto-retried via didUpdateConnectionState when network returns
    private boolean feedExhausted;  // the backend ran out of pages (null cursor); reset on a fresh load
    private int emptyAppendStreak;  // consecutive append pages that added 0 new items — bounds cursor-chaining

    // Watch clock for the CURRENT reel: dwell since shown, play time accumulated across
    // pause/resume. Flushed into REPLAY/VIDEO_END/SWIPE_AWAY when the user leaves the reel.
    private long itemShownMs;
    private long watchStartMs;
    private long watchedAccumMs;

    private VideoPlayer currentPlayer;
    private int currentPosition = -1;
    private boolean userPaused;
    private int bottomInset;

    // Video progress / scrub bar (overlay on root, reflects currentPlayer; see SeekBarView).
    private SeekBarView seekBar;
    // Clips shorter than this get no scrub bar — there's nothing meaningful to drag through.
    private static final long MIN_SEEKBAR_DURATION_MS = 15_000;
    private Handler positionUpdateHandler;
    private Runnable updateProgressRunnable;

    // ---- Two-finger pinch-to-zoom on the playing video (Telegram-player style) ----
    // The reels player drives a VideoPlayer onto a TextureView (NOT MediaController), so Telegram's
    // PinchToZoomHelper video path doesn't apply. Instead we scale+pan the live video view (aspect +
    // cover) in place and fade EVERY overlay (rail/caption/gradient/scrub bar) to zero while pinching,
    // so nothing covers the video — then spring it all back over 220ms when the fingers lift.
    private boolean pinchClaimed;     // a 2-finger gesture is owned by the zoom (until the last finger lifts)
    private boolean pinchActive;      // currently transforming (two fingers down)
    private ReelsHolder pinchHolder;  // the reel being zoomed
    private float pinchStartDistance;
    private float pinchCenterX, pinchCenterY;
    private float pinchScale = 1f;
    private float pinchTransX, pinchTransY;
    private ValueAnimator pinchFinishAnimator;
    private static final float PINCH_MAX_SCALE = 3f;
    // Host controller — set only when shown as the MainTabsActivity "Reels" tab. Lets the pinch hide the
    // floating bottom tab bar so the zoomed video rises above it too (null in the search-seeded player).
    private MainTabsActivityController mainTabsController;

    // ---- One-finger long-press "peek": hide ALL chrome (rail/caption/scrub/tab bar/pause icon) so the
    // bare video shows; restore on release. Playback is untouched — playing stays playing, paused stays
    // paused, and the pause icon is hidden while peeking. ----
    private boolean peeking;
    private ReelsHolder peekHolder;
    private ValueAnimator peekAnimator;

    // Channels the user blocked this session — filtered from the feed immediately for instant feedback;
    // the BLOCK_CHANNEL event makes it durable + cross-device (the backend then excludes them server-side).
    // Thread-safe: written on the UI thread (block/undo), read on background feed-load threads.
    private final java.util.Set<Long> blockedChannels = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Persistent twin of blockedChannels: survives app restarts and backs the "Blocked channels"
    // management screen. The feed filters consult BOTH so a block made in a previous session still
    // hides the channel on a cold start (blockedChannels alone is session-only).
    private SvipeBlockedChannels svipeBlockedChannels;

    // Float bulletins (undo / "less of this" / copy-link) above the floating native bottom tab bar —
    // the exact anchor MainTabsActivity uses for its theme-change & account hints. Without a registered
    // delegate, Bulletin falls back to getBottomInset() and the snackbar hides under the tab bar.
    // In the search-seeded player there is no tab bar (the comment input bar sits there), so clear that.
    private final Bulletin.Delegate bulletinDelegate = new Bulletin.Delegate() {
        @Override
        public int getBottomOffset(int tag) {
            return discoverSeed
                    ? bottomInset
                    : AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN);
        }
    };

    // Stories trick: the next reel's player is created in advance and buffers PAUSED at LOW
    // priority; the swipe just attaches it to the texture — no setup, no buffer ramp-up.
    private VideoPlayer nextPlayer;
    private int nextPlayerPos = -1;
    // --- stuck-reel watchdog (auto skip-and-back): a prepared player whose stream stalled in the
    // FileLoader priority queue shows the spinner forever; promotion only re-maps priority and never
    // wakes the parked read(). This watchdog rebuilds the reel fresh (like a manual revisit) when a
    // reel stays in BUFFERING with zero buffered-position growth past a grace window.
    private boolean currentReelFirstFrame; // set once real frames render; watchdog then goes inert
    private int stuckRecoveryAttempts;     // quick attempts used; reset on page change AND on first frame
    private long lastBufferedPos;          // last sampled buffered position, to detect no-progress
    private int noProgressTicks;           // consecutive ticks with no buffered-position growth
    private Runnable stuckWatchdogRunnable;
    private long watchdogArmedMs;          // when the watchdog was (re)armed — drives the hard first-frame deadline
    private long lastStuckRecoveryMs;      // paces cooldown retries past the quick cap
    private boolean pendingPrefetchRearm;  // recovery cleared the ahead-window ops; re-arm them on first frame
    private Runnable playbackStartChecker; // backstop for reels stuck BEFORE a player exists (resolve/startPlayback failed)
    private long playRequestMs; // for the "svipe: first frame" timing log
    // Manual-rescue detector (diagnostics only): remembers the reel the user swiped AWAY from while
    // it was still spinning. If they come back and it renders quickly, the manual skip-and-back
    // rescued a reel the watchdog should have healed — reported via sendDiag so real-device failures
    // of the auto-recovery are visible in the prod events table instead of anecdotal.
    private long rescueChannelId;
    private int rescueMessageId;
    private long rescueLeftAtMs;
    private int rescueLeftAttempts;
    private long rescueLeftSpinnerMs;
    private long pendingSeekToMs; // mid-play recovery: resume the rebuilt player here, not at 0

    // Persistent offline ready-queue: play instantly from disk on open, refill in the background.
    private SvipeReelQueue reelQueue;
    private SvipeWatchedSet watchedSet;
    private boolean coldStartDone; // the instant (queue) path has run; feed loads now MERGE, not clear
    // Seed-conditioned continuation (opened from a discover tap): the tapped video conditions the
    // feed. feedCursor carries the page index + seed across loadMore calls (set from next_cursor).
    private boolean seeded;
    private long seedChannel;
    private int seedMessage;
    private Integer seedTopic;
    private String feedCursor;
    private final HashMap<String, FeedItem> fileNameToItem = new HashMap<>(); // full-download completion lookup
    private final HashSet<Long> fullDownloadStarted = new HashSet<>();        // docIds with a cacheType-0 load in flight

    private static class FeedItem {
        long channelId;
        int messageId;
        String username;
        String shareUrl;     // owned svipe.uz/<code> preview link, supplied by the backend with the feed
        Integer topicId;
        String recId;         // recommendation_id of the page this item arrived with
        MessageObject mo;     // filled after MTProto resolution
        TLRPC.Chat chat;
        boolean liked;        // local like state (authoritative for the UI)
        int likeCount;        // total reactions count, kept in sync locally
        boolean resolving;    // an MTProto resolve is in flight (prevents duplicate prefetch)
        final java.util.ArrayList<Runnable> resolveCallbacks = new java.util.ArrayList<>(); // waiters for an in-flight resolve — never dropped
        int resolveAttempts;  // bounded retry counter for transient resolve failures
        boolean preloadStarted;                          // a head-preload was requested
        int preloadPriority = FileLoader.PRIORITY_LOW;   // set by prefetchAround before resolve
        boolean preloadBypassGate;                       // next-in-line skips the data-saving gate
        boolean fromQueue;                               // restored from the persisted offline queue
        boolean fullDownloadStarted;                     // a full (cacheType 0) download was requested
        long downloadDocId;                              // the rendition doc a full download targets (0 = none); the observers key off this
        // Real comment availability, resolved via getDiscussionMessage (the message's isComments()
        // flag can be stale — true but with no actual discussion -> MSG_ID_INVALID). null=unknown.
        Boolean commentsAvailable;
    }

    public ReelsActivity(Bundle args) {
        super(args);
    }

    /**
     * Build a reels player opened at the tapped discover/explore reel. The tapped video plays first
     * (instant), and the swipe feed then CONTINUES seed-conditioned on it — "similar to the tapped
     * video" blended with the user's personalized feed (see backend build_seeded_feed). Used by the
     * Search section's Explore grid.
     */
    public static ReelsActivity ofDiscoverSeed(java.util.List<org.telegram.svipe.SvipeDiscover.Item> all, int start) {
        Bundle args = new Bundle();
        final int n = all == null ? 0 : all.size();
        if (n > 0) {
            if (start < 0 || start >= n) start = 0;
            org.telegram.svipe.SvipeDiscover.Item it = all.get(start);
            args.putLong("seed_channel", it.channelId);
            args.putInt("seed_message", it.messageId);
            args.putString("seed_username", it.username);
            args.putInt("seed_topic", it.topicId != null ? it.topicId : -1);
        }
        return new ReelsActivity(args);
    }

    /** Cold-start for the discover seed: play the tapped video, then continue with a seed-conditioned feed. */
    private boolean playSeedIfPresent() {
        Bundle args = getArguments();
        if (args == null) return false;
        long ch = args.getLong("seed_channel", 0);
        int msg = args.getInt("seed_message", 0);
        String user = args.getString("seed_username");
        if (ch == 0 || msg == 0 || user == null || user.isEmpty()) return false;
        int topic = args.getInt("seed_topic", -1);

        FeedItem it = new FeedItem();
        it.channelId = ch;
        it.messageId = msg;
        it.username = user;
        it.topicId = topic >= 0 ? topic : null;
        items.add(it);

        // Continuation feed is conditioned on this tapped video; loadMore sends it to /v1/feed.
        seeded = true;
        seedChannel = ch;
        seedMessage = msg;
        seedTopic = topic >= 0 ? topic : null;

        // The tapped reel is the pager head; the seeded feed MERGES (never clears) below it.
        coldStartDone = true;
        setStatus(null);
        if (adapter != null) adapter.notifyDataSetChanged();
        currentPosition = -1;
        AndroidUtilities.runOnUIThread(this::checkCurrentPage, 0);
        kickBackgroundFeed();
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        // Search-seeded player (opened from the Explore grid via ofDiscoverSeed): no floating tab bar,
        // so we host a persistent native discussion-comment input at the bottom instead.
        discoverSeed = getArguments() != null && getArguments().getLong("seed_channel", 0) != 0;

        // Keep all overlay UI above the floating native bottom tab bar (height 72dp + nav bar).
        // In the search-seeded player there is no tab bar; the input bar lives there instead, so the
        // reel overlays only need to clear the input bar height + nav bar.
        if (discoverSeed) {
            bottomInset = AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(COMMENT_BAR_HEIGHT_DP + 4);
        } else {
            bottomInset = AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(88);
        }

        // SizeNotifierFrameLayout (drop-in for FrameLayout) so it can host ChatActivityEnterView and
        // report keyboard height to it. occupyStatusBar=false keeps the existing full-screen layout.
        root = new SizeNotifierFrameLayout(context);
        root.setOccupyStatusBar(false);
        root.setBackgroundColor(0xFF000000);

        listView = new RecyclerListView(context);
        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        listView.setLayoutManager(layoutManager);
        adapter = new ReelsAdapter(context);
        listView.setAdapter(adapter);
        new PagerSnapHelper().attachToRecyclerView(listView);

        // RecyclerListView's internal item-touch listener probes children via onTouchEvent() (not
        // dispatchTouchEvent), so a child's setOnTouchListener never fires. Own the tap gestures at
        // the list level instead and NEVER consume, so vertical paging + rail buttons keep working.
        final GestureDetector tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return false; }
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                // Fire control actions immediately (rail buttons / follow / channel / caption). The
                // child views carry no click listener; this list-level path drives them reliably so a
                // RecyclerView scroll-claim can't swallow the tap.
                return dispatchControlTap(e);
            }
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // Only the bare video toggles play/pause. The detector observes EVERY tap (it is fed in
                // onInterceptTouchEvent), so taps meant for the action rail or the caption/channel box
                // must be excluded — otherwise like/share/comment/caption taps also pause the video.
                if (!tapOnControls(e)) togglePlayPause();
                return false;
            }
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                int pos = currentPosition;
                if (pos < 0 || pos >= items.size()) pos = layoutManager.findFirstVisibleItemPosition();
                if (pos < 0 || pos >= items.size()) return false;
                FeedItem it = items.get(pos);
                ReelsHolder h = holderAt(pos);
                if (it != null && it.mo != null && h != null) {
                    setLike(it, h, true, true);
                    showHeartBurst((FrameLayout) h.itemView, e.getX(), e.getY());
                }
                return false;
            }
            @Override
            public void onLongPress(MotionEvent e) {
                // Press-and-hold to peek the bare video: hide everything over it. No play/pause change.
                if (pinchClaimed) return; // a two-finger gesture owns this
                ReelsHolder h = holderAt(currentPosition);
                if (h != null) startPeek(h);
            }
        });
        listView.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                boolean wasClaimed = pinchClaimed;
                if (handlePinch(e)) {
                    if (!wasClaimed) {
                        // A pinch just took over — cancel the pending tap so the lift can't pause/like.
                        MotionEvent cancel = MotionEvent.obtain(e);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        tapDetector.onTouchEvent(cancel);
                        cancel.recycle();
                    }
                    return true; // two-finger zoom owns the gesture — stop vertical paging
                }
                tapDetector.onTouchEvent(e); // may fire onLongPress -> startPeek
                if (peeking) {
                    endPeekOnUp(e);
                    return true; // hold the gesture while peeking — no paging
                }
                return false; // observe only — never intercept paging
            }
            @Override
            public void onTouchEvent(RecyclerView rv, MotionEvent e) {
                handlePinch(e);
                if (peeking) endPeekOnUp(e);
            }
            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallow) {}
        });

        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    checkCurrentPage();
                    // Settled on the snapped reel: the scrub bar belongs to it again at its anchor.
                    if (seekBar != null) {
                        seekBar.setTranslationY(0);
                    }
                }
            }

            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                // Make the scrub bar swipe away together with its own video: follow the current
                // (outgoing) reel page as it scrolls off, instead of hanging fixed over the next reel.
                // Purely a translation, so scrubbing/touch dispatch is untouched once settled.
                ReelsHolder h = holderAt(currentPosition);
                if (seekBar != null && h != null) {
                    seekBar.setTranslationY(h.itemView.getTop());
                }
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Draggable video progress bar — a thin always-on line above the caption + tab bar; it sits on
        // root (not inside the RecyclerListView) so it gets normal touch dispatch for scrubbing.
        seekBar = new SeekBarView(context);
        FrameLayout.LayoutParams seekLp = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 20, Gravity.BOTTOM | Gravity.LEFT);
        // Sit just above the tab bar with a small gap (dropped lower than the old caption-hugging spot
        // so the bar reads as its own row, but not so low it crowds the tab bar).
        seekLp.bottomMargin = bottomInset - AndroidUtilities.dp(2);
        root.addView(seekBar, seekLp);

        statusView = new TextView(context);
        statusView.setTextColor(0xFFFFFFFF);
        statusView.setTextSize(15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setText(getString(R.string.Loading));
        root.addView(statusView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        fragmentView = root;
        reelQueue = new SvipeReelQueue(account);
        watchedSet = new SvipeWatchedSet(account);
        svipeBlockedChannels = new SvipeBlockedChannels(account);
        if (!playSeedIfPresent()) {
            restoreQueueThenPlay();
        }
        // When opened from the Search Explore grid (seeded), this is a presented fragment — show a
        // back button at the top-left to return to Search (the reels tab itself has none), and host
        // the persistent native discussion-comment input at the bottom.
        if (discoverSeed) {
            ImageView backButton = new ImageView(context);
            BackDrawable backDrawable = new BackDrawable(false);
            backDrawable.setColor(0xFFFFFFFF);
            backButton.setImageDrawable(backDrawable);
            backButton.setScaleType(ImageView.ScaleType.CENTER);
            backButton.setOnClickListener(v -> finishFragment());
            FrameLayout.LayoutParams backLp = LayoutHelper.createFrame(48, 48, Gravity.TOP | Gravity.LEFT, 6, 0, 0, 0);
            backLp.topMargin = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(6);
            root.addView(backButton, backLp);

            createReelEnterView(context);
        }
        return fragmentView;
    }

    /**
     * Build the persistent native message-input bar shown at the bottom of the search-seeded player.
     * Modeled on PeerStoriesView.createEnterView(): a {@link ChatActivityEnterView} with a null
     * ChatActivity fragment and the activity root (a SizeNotifierFrameLayout) as its parent. Sends are
     * routed by us into the current reel's channel-post discussion thread (see sendCommentToThread).
     */
    private void createReelEnterView(Context context) {
        final Activity activity = AndroidUtilities.findActivity(context);
        if (activity == null) return;
        final Theme.ResourcesProvider rp = getResourceProvider();

        // Disabled bar (comments off): a non-interactive rounded "pill" with a block icon + label,
        // styled to match Telegram's channel bottom button / "Join request sent" bar — 44dp tall,
        // 22dp corner radius, 7dp side insets, translucent dark. Sits in the same slot as the enter
        // view; only one of the two is visible at a time.
        reelDisabledBar = new FrameLayout(context);
        GradientDrawable reelDisabledBg = new GradientDrawable();
        reelDisabledBg.setShape(GradientDrawable.RECTANGLE);
        reelDisabledBg.setCornerRadius(AndroidUtilities.dp(22));
        reelDisabledBg.setColor(0xCC1C1C1E);
        reelDisabledBar.setBackground(reelDisabledBg);
        reelDisabledBar.setClickable(true);
        reelDisabledBar.setVisibility(View.GONE);
        LinearLayout disRow = new LinearLayout(context);
        disRow.setOrientation(LinearLayout.HORIZONTAL);
        disRow.setGravity(Gravity.CENTER);
        ImageView disIcon = new ImageView(context);
        disIcon.setImageResource(R.drawable.msg_block);
        disIcon.setColorFilter(new PorterDuffColorFilter(0xFF9E9E9E, PorterDuff.Mode.SRC_IN));
        disRow.addView(disIcon, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        TextView disText = new TextView(context);
        disText.setTextColor(0xFFFFFFFF);
        disText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        disText.setText(LocaleController.getString(R.string.SvipeCommentsDisabled));
        disRow.addView(disText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        reelDisabledBar.addView(disRow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        FrameLayout.LayoutParams disLp = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.BOTTOM, 7, 0, 7, 0);
        disLp.bottomMargin = AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(6);
        root.addView(reelDisabledBar, disLp);

        // The real Telegram input. null ChatActivity fragment, root as the SizeNotifierFrameLayout
        // parent. We do the actual send ourselves (into the discussion thread) by overriding
        // sendMessage(); the native processSendingText path (which would send to dialog_id=0) is never
        // reached.
        reelEnterView = new ChatActivityEnterView(activity, root, null, true, rp) {
            @Override
            public boolean sendMessage() {
                CharSequence text = getFieldText();
                if (text == null || text.toString().trim().length() == 0) {
                    openKeyboard();
                    return false;
                }
                sendCommentToThread(text.toString().trim());
                setFieldText("");
                return true;
            }
        };
        reelEnterView.setDelegate(new ChatActivityEnterView.ChatActivityEnterViewDelegate() {
            @Override
            public void onMessageSend(CharSequence message, boolean notify, int scheduleDate, int scheduleRepeatPeriod, long payStars) {
                // The send itself is handled in the overridden sendMessage() above; nothing to do here.
            }

            @Override
            public void needSendTyping() {}

            @Override
            public void onTextChanged(CharSequence text, boolean bigChange, boolean fromDraft) {}

            @Override
            public void onTextSelectionChanged(int start, int end) {}

            @Override
            public void onTextSpansChanged(CharSequence text) {}

            @Override
            public void onAttachButtonHidden() {}

            @Override
            public void onAttachButtonShow() {}

            @Override
            public void onWindowSizeChanged(int size) {}

            @Override
            public void onStickersTab(boolean opened) {}

            @Override
            public void onMessageEditEnd(boolean loading) {}

            @Override
            public void didPressAttachButton() {}

            @Override
            public void needStartRecordVideo(int state, boolean notify, int scheduleDate, int scheduleRepeatPeriod, int ttl, long effectId, long stars) {}

            @Override
            public void toggleVideoRecordingPause() {}

            @Override
            public boolean isVideoRecordingPaused() {
                return false;
            }

            @Override
            public void needChangeVideoPreviewState(int state, float seekProgress) {}

            @Override
            public void onSwitchRecordMode(boolean video) {}

            @Override
            public void onPreAudioVideoRecord() {}

            @Override
            public void needStartRecordAudio(int state) {}

            @Override
            public void needShowMediaBanHint() {}

            @Override
            public void onStickersExpandedChange() {}

            @Override
            public void onUpdateSlowModeButton(View button, boolean show, CharSequence time) {}

            @Override
            public void onSendLongClick() {}

            @Override
            public void onAudioVideoInterfaceUpdated() {}

            @Override
            public int getContentViewHeight() {
                return root.getHeight();
            }
        });
        reelEnterView.setAllowStickersAndGifs(true, true, true);
        reelEnterView.updateColors();
        reelEnterView.recordingGuid = classGuid;
        // Match Telegram's rounded floating composer (ChatInputViewsContainer's input bubble): the enter
        // view paints its panel background flat edge-to-edge, so clip it to a 22dp round rect and inset it
        // 7dp on the sides — same radius/insets as the chat input island and the disabled bar above.
        reelEnterView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(22));
            }
        });
        reelEnterView.setClipToOutline(true);
        FrameLayout.LayoutParams enterLp = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 7, 0, 7, 0);
        enterLp.bottomMargin = AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(6);
        root.addView(reelEnterView, enterLp);
        reelEnterView.onResume();
    }

    /**
     * Resolve the CURRENT reel's channel-post discussion thread (TL_messages_getDiscussionMessage)
     * and cache its root MessageObject for sending. Called when the input is created and whenever the
     * pager moves to a new reel. Also (re)applies the enabled/disabled state of the bottom bar.
     */
    private void refreshCommentTargetForCurrent() {
        if (!discoverSeed || reelEnterView == null) return;
        FeedItem item = (currentPosition >= 0 && currentPosition < items.size()) ? items.get(currentPosition) : null;
        // Gate on the reply COUNT — the same number shown on the rail and the only reliable signal
        // here (the isComments flag is often missing in feed data, and the getDiscussionMessage
        // resolve is async/flaky). A reel with comments => show the native input; "Izoh" (0) => the
        // disabled bar. The thread is still resolved below so a typed comment can be posted.
        boolean enabled = item != null && item.mo != null && item.mo.getRepliesCount() > 0;
        if (reelDisabledBar != null) {
            reelDisabledBar.setVisibility(enabled ? View.GONE : View.VISIBLE);
        }
        reelEnterView.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) {
            // closing the keyboard avoids a dangling IME over a hidden input when paging onto a
            // comments-off reel mid-typing.
            try { reelEnterView.closeKeyboard(); } catch (Exception ignore) {}
            commentThreadRoot = null;
            pendingCommentToSend = null;
            return;
        }
        // Same post already resolved? keep it. Otherwise drop the old root and resolve afresh.
        if (commentThreadRoot != null
                && commentThreadForChannelId == item.channelId
                && commentThreadForMessageId == item.messageId) {
            return;
        }
        commentThreadRoot = null;
        commentThreadForChannelId = item.channelId;
        commentThreadForMessageId = item.messageId;
        resolveCommentThread(item, null);
    }

    /**
     * TL_messages_getDiscussionMessage(channelPeer, postMessageId) -> the discussion-group thread root
     * (last message). On success caches it in commentThreadRoot; if a send is pending, sends it.
     */
    private void resolveCommentThread(final FeedItem item, final Runnable onResolved) {
        if (item == null || item.chat == null) return;
        if (commentThreadResolving) return;
        commentThreadResolving = true;
        final long channelId = item.channelId;
        final int messageId = item.messageId;
        final TLRPC.TL_messages_getDiscussionMessage req = new TLRPC.TL_messages_getDiscussionMessage();
        // Build the channel peer straight from the chat object (it carries access_hash). Using
        // getInputPeer(-chat.id) relies on the controller cache and yields a wrong inputPeerChat
        // (-> MSG_ID_INVALID) when the channel isn't cached. Mirrors ChatActivity.openDiscussionMessageChat.
        req.peer = MessagesController.getInputPeer(item.chat);
        req.msg_id = messageId;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            commentThreadResolving = false;
            // The pager may have moved on while resolving — discard this result and resolve the new
            // current target instead (its earlier resolveCommentThread call was dropped because one
            // was already in flight).
            if (channelId != commentThreadForChannelId || messageId != commentThreadForMessageId) {
                refreshCommentTargetForCurrent();
                return;
            }
            if (error != null || !(response instanceof TLRPC.TL_messages_discussionMessage)) {
                // No real discussion (e.g. MSG_ID_INVALID) -> comments effectively off for this post.
                pendingCommentToSend = null;
                if (item != null) item.commentsAvailable = false;
                refreshCommentTargetForCurrent();
                return;
            }
            TLRPC.TL_messages_discussionMessage res = (TLRPC.TL_messages_discussionMessage) response;
            MessagesController.getInstance(account).putUsers(res.users, false);
            MessagesController.getInstance(account).putChats(res.chats, false);
            MessageObject root = null;
            for (int a = res.messages.size() - 1; a >= 0; a--) {
                TLRPC.Message m = res.messages.get(a);
                if (m instanceof TLRPC.TL_messageEmpty) continue;
                m.isThreadMessage = true;
                root = new MessageObject(account, m, true, true);
                break;
            }
            if (root == null) {
                pendingCommentToSend = null;
                if (item != null) item.commentsAvailable = false;
                refreshCommentTargetForCurrent();
                return;
            }
            commentThreadRoot = root;
            if (item != null) item.commentsAvailable = true;
            // The bottom may currently show the disabled bar (e.g. it was applied before mo/count
            // enriched, or during an optimistic pass) — now that the thread is confirmed, re-apply
            // so the native input is shown.
            refreshCommentTargetForCurrent();
            if (onResolved != null) onResolved.run();
            if (pendingCommentToSend != null) {
                CharSequence pend = pendingCommentToSend;
                pendingCommentToSend = null;
                sendCommentToThread(pend.toString());
            }
        }));
    }

    /**
     * Post a comment into the current reel's discussion thread. If the thread root isn't resolved yet,
     * stash the text and kick the resolve; it sends as soon as the root arrives.
     */
    private void sendCommentToThread(String text) {
        if (text == null || text.trim().length() == 0) return;
        if (commentThreadRoot == null) {
            pendingCommentToSend = text;
            FeedItem item = (currentPosition >= 0 && currentPosition < items.size()) ? items.get(currentPosition) : null;
            if (item != null) resolveCommentThread(item, null);
            return;
        }
        final MessageObject root = commentThreadRoot;
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                text, root.getDialogId(), root, root, null, false, null, null, null, true, 0, 0, null, false);
        try {
            SendMessagesHelper.getInstance(account).sendMessage(params);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Instant cold-start: rebuild playable reels from the persisted queue (no auth, no /v1/feed, no
     * MTProto) and start playing position 0 immediately. Deserialization + file-existence checks run
     * off the UI thread; the adapter is touched only on the UI thread. The fresh feed is then loaded
     * in the BACKGROUND and merged below the instant queue.
     */
    private void restoreQueueThenPlay() {
        restoreQueueThenPlay(0);
    }

    private static final int RESTORE_MAX_ATTEMPTS = 4; // retry the early-cold-start FileLoader race

    private void restoreQueueThenPlay(int attempt) {
        // Idempotent: if a prior attempt (or the other createView pass) already populated the pager,
        // don't touch it again.
        if (coldStartDone && !items.isEmpty()) return;
        Utilities.globalQueue.postRunnable(() -> {
            final ArrayList<FeedItem> rebuilt = new ArrayList<>();
            int total = 0, skipWatched = 0, skipDeser = 0, skipNoFile = 0, downloadedUnwatched = 0;
            for (SvipeReelQueue.Entry e : reelQueue.list()) {
                total++;
                if (watchedSet.isWatched(e.channelId, e.messageId) || blockedChannels.contains(e.channelId) || (svipeBlockedChannels != null && svipeBlockedChannels.contains(e.channelId))) { skipWatched++; continue; } // never re-show watched/blocked (persistent blocks too)
                if (e.downloaded) downloadedUnwatched++;
                MessageObject mo = deserializeMessage(e.messageB64);
                if (mo == null || mo.getDocument() == null) { skipDeser++; continue; }
                // validate-on-load: drop entries whose cached file was evicted (keepMedia auto-delete).
                // Laddered entries store ONE rendition — count the item present when ANY rendition
                // (or the original, for legacy entries) is fully on disk; playback pins exactly that
                // cached file, so a "present" item is one the player can really play offline.
                boolean present;
                ArrayList<VideoPlayer.Quality> qs = qualitiesFor(mo);
                if (qs != null) {
                    present = cachedQualityOf(qs) != null;
                } else {
                    File f = FileLoader.getInstance(account).getPathToAttach(mo.getDocument(), null, false, false);
                    present = f != null && f.exists();
                }
                if (!present) { skipNoFile++; continue; }
                FeedItem it = new FeedItem();
                it.channelId = e.channelId;
                it.messageId = e.messageId;
                it.username = e.username;
                it.shareUrl = e.shareUrl;
                it.topicId = e.topicId;
                it.recId = e.recId;
                it.mo = mo;
                it.fromQueue = true;
                it.liked = isLiked(mo);
                it.likeCount = totalReactions(mo);
                rebuilt.add(it);
            }
            final int fTotal = total, fSkipWatched = skipWatched, fSkipDeser = skipDeser, fSkipNoFile = skipNoFile;
            final int fDownloadedUnwatched = downloadedUnwatched;
            AndroidUtilities.runOnUIThread(() -> {
                if (coldStartDone && !items.isEmpty()) return; // another pass already won
                FileLog.d("svipe: cold start attempt=" + attempt + " queue total=" + fTotal + " restored=" + rebuilt.size()
                        + " skip(watched=" + fSkipWatched + ",deser=" + fSkipDeser + ",noFile=" + fSkipNoFile + ")");
                if (!rebuilt.isEmpty()) {
                    coldStartDone = true;
                    items.clear();
                    items.addAll(rebuilt);
                    setStatus(null);
                    adapter.notifyDataSetChanged();
                    currentPosition = -1;
                    AndroidUtilities.runOnUIThread(this::checkCurrentPage, 0); // plays pos 0, no network gate
                    kickBackgroundFeed(); // refresh/extend the feed in the background
                } else if (fDownloadedUnwatched > 0 && attempt + 1 < RESTORE_MAX_ATTEMPTS) {
                    // We HAVE downloaded reels but their files weren't resolvable yet (FileLoader not
                    // warmed this early in process start). Retry shortly before falling back online.
                    AndroidUtilities.runOnUIThread(() -> restoreQueueThenPlay(attempt + 1), 200);
                } else {
                    coldStartDone = true;
                    setStatus(getString(R.string.Loading)); // genuinely empty queue -> online fallback shows the spinner
                    kickBackgroundFeed();
                }
            });
        });
    }

    /** Background feed load that MERGES into the playing queue instead of replacing it. */
    private void kickBackgroundFeed() {
        feedExhausted = false;
        requestFeed(false, false);
    }

    private MessageObject deserializeMessage(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
            SerializedData data = new SerializedData(bytes);
            int constructor = data.readInt32(false);
            TLRPC.Message m = TLRPC.Message.TLdeserialize(data, constructor, false);
            if (m == null) return null;
            return new MessageObject(account, m, false, false);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private String serializeMessage(TLRPC.Message m) {
        try {
            SerializedData data = new SerializedData();
            m.serializeToStream(data);
            return Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private void setStatus(String text) {
        if (statusView == null) return;
        if (text == null) {
            statusView.setVisibility(View.GONE);
        } else {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(text);
        }
    }

    private void loadFeed() {
        feedExhausted = false;
        emptyAppendStreak = 0;
        requestFeed(false, false);
    }

    /** Endless feed: append the next personalized page (the backend excludes what we've seen). */
    private void loadMore() {
        if (feedExhausted) return;
        requestFeed(true, false);
    }

    private void requestFeed(boolean append, boolean retried) {
        if (loadingFeed) return;
        loadingFeed = true;
        feedLoadFailed = false;
        // While the instant queue is already playing, a fresh load merges silently below it: no
        // status spinner, no clear, no player restart. The status path is only for the cold,
        // empty-queue online fallback.
        final boolean playing = coldStartDone && !items.isEmpty();
        if (!append && !playing) setStatus(getString(R.string.Connecting));
        SvipeAuth.ensureToken(account, t -> {
            if (t == null) {
                loadingFeed = false;
                feedLoadFailed = true;
                if (!append && !playing) setStatus(getString(R.string.SvipeReelsConnectFailed));
                return;
            }
            token = t;
            if (!append && !playing) setStatus(getString(R.string.SvipeReelsLoadingFeed));
            // First request carries the seed (discover tap); afterwards the cursor carries page+seed.
            String path = "/v1/feed";
            try {
                if (feedCursor != null) {
                    path += "?cursor=" + java.net.URLEncoder.encode(feedCursor, "UTF-8");
                } else if (seeded) {
                    path += "?seed_channel_id=" + seedChannel + "&seed_message_id=" + seedMessage;
                    if (seedTopic != null) path += "&seed_topic_id=" + seedTopic;
                }
            } catch (java.io.UnsupportedEncodingException ignore) {
                path = "/v1/feed";
            }
            SvipeApi.get(path, token, (res, code, err) -> {
                loadingFeed = false;
                if (code == 401 && !retried) {
                    // Access token died mid-session: silent re-auth, one retry.
                    SvipeAuth.invalidateAccessToken(account);
                    requestFeed(append, true);
                    return;
                }
                if (res == null || !res.has("items")) {
                    feedLoadFailed = true;
                    if (!append && !playing) {
                        setStatus(code == 0
                                ? getString(R.string.SvipeReelsNoInternet)
                                : LocaleController.formatString(R.string.SvipeReelsLoadFailed, String.valueOf(code)));
                    }
                    return;
                }
                String recId = res.isNull("recommendation_id") ? null : res.optString("recommendation_id", null);
                recommendationId = recId;
                // Advance pagination: the cursor carries the next page index (+ seed). Null => no more.
                feedCursor = res.isNull("next_cursor") ? null : res.optString("next_cursor", null);
                // additive = append page OR a background merge into the already-playing queue.
                // Re-evaluated HERE (not at request start): a cold-start restore may have populated
                // items after this request began, so a fresh feed must merge, never clear them.
                final boolean additive = append || (coldStartDone && !items.isEmpty());
                if (!additive) {
                    items.clear();
                }
                int before = items.size();
                int added = 0;
                JSONArray arr = res.optJSONArray("items");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        String username = o.isNull("username") ? null : o.optString("username", null);
                        if (username == null || username.isEmpty()) continue;
                        long channelId = o.optLong("channel_id");
                        int messageId = o.optInt("message_id");
                        // Never re-add what's already in the pager or already watched.
                        if (additive && containsItem(channelId, messageId)) continue;
                        if ((watchedSet != null && watchedSet.isWatched(channelId, messageId)) || blockedChannels.contains(channelId) || (svipeBlockedChannels != null && svipeBlockedChannels.contains(channelId))) continue;
                        FeedItem it = new FeedItem();
                        it.channelId = channelId;
                        it.messageId = messageId;
                        it.username = username;
                        it.shareUrl = o.isNull("share_url") ? null : o.optString("share_url", null);
                        it.topicId = o.isNull("topic_id") ? null : o.optInt("topic_id");
                        it.recId = recId;
                        items.add(it);
                        added++;
                    }
                }
                if (additive) {
                    if (added > 0) {
                        emptyAppendStreak = 0;
                        adapter.notifyItemRangeInserted(before, added);
                        // A merge can newly satisfy the download-ahead target — top it up.
                        if (playing && currentPosition >= 0) ensureFullDownloadsAhead(currentPosition);
                    }
                    // The feed is only truly exhausted when the backend stops giving a cursor. A page
                    // that added 0 NEW items (all already watched/deduped) is NOT the end: early pages
                    // are sparse for a heavily-watched account while later pages are full, so chain
                    // straight on to the next page instead of dead-ending — bounded by a safety cap so
                    // a misbehaving backend can't loop forever. A null cursor still stops us (that also
                    // covers seeded mode, where the cursor — not the seed — drives continuation).
                    if (feedCursor == null) {
                        feedExhausted = true;
                    } else if (added == 0) {
                        if (++emptyAppendStreak >= MAX_EMPTY_APPEND_PAGES) {
                            feedExhausted = true;
                        } else {
                            loadMore();
                        }
                    }
                } else {
                    setStatus(items.isEmpty() ? getString(R.string.SvipeReelsEmpty) : null);
                    adapter.notifyDataSetChanged();
                    AndroidUtilities.runOnUIThread(this::checkCurrentPage, 200);
                }
            });
        });
    }

    private boolean containsItem(long channelId, int messageId) {
        for (int i = 0; i < items.size(); i++) {
            FeedItem it = items.get(i);
            if (it.channelId == channelId && it.messageId == messageId) return true;
        }
        return false;
    }

    private void checkCurrentPage() {
        if (items.isEmpty()) return;
        int pos = layoutManager.findFirstCompletelyVisibleItemPosition();
        if (pos == RecyclerView.NO_POSITION) {
            pos = layoutManager.findFirstVisibleItemPosition();
        }
        if (pos == RecyclerView.NO_POSITION || pos == currentPosition) return;
        int prevPos = currentPosition;
        flushWatchEvent(prevPos);
        FeedItem prev = prevPos >= 0 && prevPos < items.size() ? items.get(prevPos) : null;
        // Leaving a reel that never rendered after a real wait = the user giving up on a spinner.
        // Remember it: if they swipe back and it renders instantly, that's a manual rescue the
        // watchdog failed to deliver — the strongest field signal we can collect (see sendDiag).
        // The 1.5s floor skips ordinary fast scrolling past reels that never got a chance.
        if (prev != null && !currentReelFirstFrame && playRequestMs > 0
                && System.currentTimeMillis() - playRequestMs >= 1500) {
            rescueChannelId = prev.channelId;
            rescueMessageId = prev.messageId;
            rescueLeftAtMs = System.currentTimeMillis();
            rescueLeftAttempts = stuckRecoveryAttempts;
            rescueLeftSpinnerMs = System.currentTimeMillis() - playRequestMs;
        }
        currentPosition = pos;
        userPaused = false;
        // Kill the previous reel's download BEFORE starting the new one: its stream operation
        // would otherwise keep pulling the whole file at stream priority. Cached bytes survive,
        // and if it's now in the ahead window, prefetchAround re-arms a cheap head-preload.
        releaseCurrentPlayer();
        if (prev != null) {
            prev.preloadStarted = false;
            stopLoadingFor(prev);
        }
        playPosition(pos);
        // Search-seeded player: point the persistent input at the new reel's discussion thread and
        // apply its comments-enabled/disabled state. (Re-applied when the chat resolves, see updateActions.)
        refreshCommentTargetForCurrent();
        if (pos >= items.size() - LOAD_MORE_AHEAD) {
            loadMore();
        }
    }

    /**
     * On leaving a reel, turn its accumulated watch clock into the one telemetry event the
     * recommender learns the most from. Must run BEFORE the player is released (duration source).
     */
    private void flushWatchEvent(int pos) {
        if (pos < 0 || pos >= items.size() || itemShownMs == 0) return;
        FeedItem item = items.get(pos);
        long now = System.currentTimeMillis();
        long watched = watchedAccumMs + (watchStartMs > 0 ? now - watchStartMs : 0);
        long dwell = now - itemShownMs;
        itemShownMs = 0;
        watchStartMs = 0;
        watchedAccumMs = 0;
        if (item.mo == null) return; // never resolved -> never actually shown
        long durationMs = (long) (item.mo.getDuration() * 1000);
        String classification = SvipeWatchEvent.classify(watched, durationMs);
        try {
            JSONObject payload = new JSONObject();
            payload.put("watched_ms", watched);
            if (durationMs > 0) payload.put("video_duration_ms", durationMs);
            payload.put("dwell_ms", dwell);
            payload.put("feed_position", pos);
            sendEvent(classification, item, payload);
        } catch (Exception ignore) {}
        // Once watched, the reel must never re-enter the offline queue or replay on a cold start.
        if (watchedSet != null && SvipeQueuePlan.countsAsWatched(classification, watched, SvipeQueuePlan.MIN_WATCHED_MS)) {
            watchedSet.markWatched(item.channelId, item.messageId);
            if (reelQueue != null && reelQueue.removeByMessageId(item.channelId, item.messageId) != null) {
                reelQueue.persist();
            }
        }
    }

    private void playPosition(int pos) {
        releaseCurrentPlayer();
        if (pos < 0 || pos >= items.size()) return;
        playRequestMs = System.currentTimeMillis();
        // Fresh reel: clear stall-watchdog state and drop any watchdog still armed for the previous reel.
        currentReelFirstFrame = false;
        stuckRecoveryAttempts = 0;
        lastBufferedPos = -1;
        noProgressTicks = 0;
        lastStuckRecoveryMs = 0;
        pendingPrefetchRearm = false;
        pendingSeekToMs = 0; // a stale mid-play resume must never seek a NEW reel
        cancelStuckWatchdog();
        schedulePlaybackStartChecker(pos);
        if (nextPlayer != null && nextPlayerPos != pos) {
            releaseNextPlayer(); // prepared for a different reel (e.g. back swipe) — drop it
        }
        final FeedItem item = items.get(pos);
        ReelsHolder holder = holderAt(pos);
        if (holder != null) {
            holder.showLoading(true);
            holder.setPaused(false);
            holder.setCover(item.mo); // thumbnail instantly, video replaces it on first frame
        }
        itemShownMs = System.currentTimeMillis();
        watchStartMs = 0;
        watchedAccumMs = 0;
        try {
            JSONObject impression = new JSONObject();
            impression.put("feed_position", pos);
            sendEvent("IMPRESSION", item, impression);
        } catch (Exception ignore) {}
        if (item.mo != null) {
            // Queue-restored items have mo but no chat — play offline NOW, fill chat for the rail later.
            startPlayback(item.mo, item.mo.getDocument(), pos, item);
            updateActions(pos);
            if (item.chat == null) {
                final int fpos = pos;
                resolveItem(item, () -> updateActions(fpos)); // background enrich for the action rail
            }
        } else {
            final int fpos = pos;
            resolveItem(item, () -> {
                updateActions(fpos);
                if (currentPosition == fpos && item.mo != null && currentPlayer == null) {
                    startPlayback(item.mo, item.mo.getDocument(), fpos, item);
                }
            });
        }
        prefetchAround(pos);
        ensureFullDownloadsAhead(pos);
    }

    private ReelsHolder holderAt(int pos) {
        RecyclerView.ViewHolder vh = listView.findViewHolderForAdapterPosition(pos);
        return vh instanceof ReelsHolder ? (ReelsHolder) vh : null;
    }

    /**
     * Resolve a feed item's channel + message over MTProto (2 round-trips) and cache the
     * MessageObject on the FeedItem. Reused for both the visible page (then play) and read-ahead
     * prefetch (no play). Idempotent: skips if already resolved or a resolve is in flight.
     */
    private void resolveItem(final FeedItem item, final Runnable onResolved) {
        if (item.mo != null && item.chat != null) { if (onResolved != null) onResolved.run(); return; }
        // Queue-restored item: we already hold a playable MessageObject, only the chat is missing
        // (needed for the action rail). Fill it with one resolveUsername round-trip — skip getMessages.
        if (item.mo != null && item.chat == null) { resolveChatOnly(item, onResolved); return; }
        // Full resolve (no mo yet). Queue the caller's callback so an already-in-flight resolve (e.g.
        // one started by prefetch/read-ahead) still notifies THIS caller when it completes — otherwise
        // playPosition's start-playback intent is silently dropped and the reel spins forever until a
        // manual skip-and-back.
        if (onResolved != null) item.resolveCallbacks.add(onResolved);
        if (item.resolving) return;
        item.resolving = true;
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = item.username.toLowerCase();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                onResolveFail(item, true); // transient network failure — retry
                return;
            }
            TLRPC.TL_contacts_resolvedPeer rp = (TLRPC.TL_contacts_resolvedPeer) response;
            MessagesController.getInstance(account).putUsers(rp.users, false);
            MessagesController.getInstance(account).putChats(rp.chats, false);
            TLRPC.Chat chat = null;
            if (rp.chats != null) {
                for (int i = 0; i < rp.chats.size(); i++) {
                    if (rp.chats.get(i).id == item.channelId) { chat = rp.chats.get(i); break; }
                }
                if (chat == null && !rp.chats.isEmpty()) chat = rp.chats.get(0);
            }
            if (chat == null) {
                onResolveFail(item, false); // channel not found — give up
                return;
            }
            final TLRPC.Chat fchat = chat;

            TLRPC.TL_inputChannel inputChannel = new TLRPC.TL_inputChannel();
            inputChannel.channel_id = chat.id;
            inputChannel.access_hash = chat.access_hash;
            TLRPC.TL_channels_getMessages gm = new TLRPC.TL_channels_getMessages();
            gm.channel = inputChannel;
            gm.id.add(item.messageId);
            ConnectionsManager.getInstance(account).sendRequest(gm, (resp2, err2) -> {
                if (err2 != null || !(resp2 instanceof TLRPC.messages_Messages)) {
                    onResolveFail(item, true); // transient network failure — retry
                    return;
                }
                TLRPC.messages_Messages mm = (TLRPC.messages_Messages) resp2;
                MessagesController.getInstance(account).putUsers(mm.users, false);
                MessagesController.getInstance(account).putChats(mm.chats, false);
                if (mm.messages == null || mm.messages.isEmpty()) {
                    onResolveFail(item, false); // message gone — give up
                    return;
                }
                final MessageObject mo = new MessageObject(account, mm.messages.get(0), false, true);
                TLRPC.Document doc = mo.getDocument();
                if (doc == null || !MessageObject.isVideoDocument(doc)) {
                    onResolveFail(item, false); // not a playable video — give up
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    item.resolving = false;
                    item.resolveAttempts = 0;
                    item.mo = mo;
                    item.chat = fchat;
                    item.liked = isLiked(mo);
                    item.likeCount = totalReactions(mo);
                    preloadMedia(item);
                    drainResolveCallbacks(item); // wakes playPosition's queued start-playback intent
                });
            });
        });
    }

    /** Fill only the missing {@code chat} on a queue-restored item (one resolveUsername round-trip). */
    private void resolveChatOnly(final FeedItem item, final Runnable onResolved) {
        if (item.resolving) return;
        item.resolving = true;
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = item.username.toLowerCase();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            AndroidUtilities.runOnUIThread(() -> {
                item.resolving = false;
                if (error == null && response instanceof TLRPC.TL_contacts_resolvedPeer) {
                    TLRPC.TL_contacts_resolvedPeer rp = (TLRPC.TL_contacts_resolvedPeer) response;
                    MessagesController.getInstance(account).putUsers(rp.users, false);
                    MessagesController.getInstance(account).putChats(rp.chats, false);
                    if (rp.chats != null) {
                        for (int i = 0; i < rp.chats.size(); i++) {
                            if (rp.chats.get(i).id == item.channelId) { item.chat = rp.chats.get(i); break; }
                        }
                        if (item.chat == null && !rp.chats.isEmpty()) item.chat = rp.chats.get(0);
                    }
                }
                if (onResolved != null) onResolved.run();
            });
        });
    }

    /** Run and clear every queued waiter for this item's resolve (safe on success or on give-up). */
    private void drainResolveCallbacks(FeedItem item) {
        if (item.resolveCallbacks.isEmpty()) return;
        java.util.ArrayList<Runnable> cbs = new java.util.ArrayList<>(item.resolveCallbacks);
        item.resolveCallbacks.clear();
        for (int i = 0; i < cbs.size(); i++) {
            try { cbs.get(i).run(); } catch (Exception e) { FileLog.e(e); }
        }
    }

    /**
     * A resolve attempt failed. A transient (network) failure for a reel someone is waiting on gets a
     * bounded, delayed retry, so a blip does not leave a permanent black frame (the manual skip-and-back
     * users rely on). Data failures (not a video, message gone) give up and drain waiters — they no-op
     * since mo stays null. Runs its own hop to the UI thread (callers are on a connection thread).
     */
    private void onResolveFail(final FeedItem item, final boolean retryable) {
        AndroidUtilities.runOnUIThread(() -> {
            item.resolving = false;
            hideLoadingFor(item);
            int idx = items.indexOf(item);
            boolean awaited = idx == currentPosition || !item.resolveCallbacks.isEmpty();
            if (retryable && awaited && item.resolveAttempts < MAX_RESOLVE_RETRIES) {
                item.resolveAttempts++;
                final int attempt = item.resolveAttempts;
                AndroidUtilities.runOnUIThread(() -> {
                    if (item.mo == null && (items.indexOf(item) == currentPosition || !item.resolveCallbacks.isEmpty())) {
                        FileLog.d("svipe: retrying resolve attempt=" + attempt);
                        resolveItem(item, null); // waiters already queued; success drains them
                    }
                }, RESOLVE_RETRY_DELAY_MS);
            } else {
                drainResolveCallbacks(item); // give up cleanly so no queued play intent leaks
            }
        });
    }

    /** Resolve (if needed) and start the reel at {@code pos} — recovers a reel stuck before playback. */
    private void resolveAndPlay(final int pos) {
        if (pos < 0 || pos >= items.size()) return;
        final FeedItem item = items.get(pos);
        ReelsHolder holder = holderAt(pos);
        if (holder != null) holder.showLoading(true);
        resolveItem(item, () -> {
            updateActions(pos);
            if (currentPosition == pos && item.mo != null && currentPlayer == null) {
                startPlayback(item.mo, item.mo.getDocument(), pos, item);
            }
        });
    }

    /**
     * Read-ahead per {@link SvipePreloadPlan}: resolve + head-preload the ahead window (the rest at
     * LOW), and cancel any started preload that fell out of the window. The immediate next reel
     * (pos+1) is skipped here — {@link #ensureFullDownloadsAhead(int)} owns it with a FULL download.
     * Behind reels are never touched — their bytes stay in cache.
     */
    private void prefetchAround(int pos) {
        for (int i = pos + 1; i <= pos + PREFETCH_AHEAD && i < items.size(); i++) {
            // The next reel (pos+1) is normally owned by ensureFullDownloadsAhead's FULL download,
            // so we skip its head-preload here — but only when full downloads are allowed (Wi-Fi).
            // On cellular there is no full download, so let pos+1 get its cheap ~2MB head-preload.
            if (i == pos + 1 && fullDownloadsAllowed()) continue;
            FeedItem it = items.get(i);
            it.preloadPriority = SvipePreloadPlan.priorityFor(i, pos) == SvipePreloadPlan.NORMAL
                    ? FileLoader.PRIORITY_NORMAL : FileLoader.PRIORITY_LOW;
            it.preloadBypassGate = SvipePreloadPlan.bypassesGate(i, pos);
            if (it.mo == null) {
                resolveItem(it, null); // resolveItem head-preloads itself on completion
            } else {
                preloadMedia(it);
            }
        }
        for (int i = 0; i < items.size(); i++) {
            FeedItem it = items.get(i);
            if (SvipePreloadPlan.shouldCancelPreload(i, pos, PREFETCH_AHEAD, it.preloadStarted)) {
                it.preloadStarted = false;
                stopLoadingFor(it);
            }
        }
    }

    /** Is this item playable entirely from disk (ANY rendition or the original fully present)? */
    private boolean fileFullyPresent(FeedItem it) {
        if (it == null || it.mo == null) return false;
        ArrayList<VideoPlayer.Quality> qualities = qualitiesFor(it.mo);
        if (qualities != null) return cachedQualityOf(qualities) != null;
        TLRPC.Document doc = it.mo.getDocument();
        if (doc == null) return false;
        File f = FileLoader.getInstance(account).getPathToAttach(doc, null, false, false);
        return f != null && f.exists();
    }

    /** Count fully-downloaded, unwatched reels currently ahead of {@code pos} in the pager. */
    private int countDownloadedUnwatchedAhead(int pos) {
        int n = 0;
        for (int i = pos + 1; i < items.size(); i++) {
            FeedItem it = items.get(i);
            if (it.mo == null) continue;
            if (watchedSet != null && watchedSet.isWatched(it.channelId, it.messageId)) continue;
            if (fileFullyPresent(it)) n++;
        }
        return n;
    }

    /**
     * Keep at least {@link SvipeQueuePlan#TARGET_AHEAD} fully-downloaded, unwatched reels ready ahead,
     * bounded by the count cap and disk budget. Starts FULL downloads (cacheType 0) on the nearest
     * not-yet-present items and persists each into the offline queue so it survives an app restart.
     */
    /**
     * Whether speculative FULL (cacheType 0) downloads may run. Restricted to unmetered Wi-Fi/
     * Ethernet ({@link ApplicationLoader#getAutodownloadNetworkType()} treats metered Wi-Fi as
     * mobile): on cellular/metered/roaming the ~2MB head-preload + prepared-next-player stream
     * already give an instant first frame, so we never burn mobile data pre-fetching whole files.
     */
    private boolean fullDownloadsAllowed() {
        return ApplicationLoader.getAutodownloadNetworkType() == StatsController.TYPE_WIFI;
    }

    private void ensureFullDownloadsAhead(int pos) {
        if (reelQueue == null) return;
        // Wi-Fi-only: the offline cold-start cushion is a nicety, not worth cellular data. pos+1's
        // instant start is covered by prefetchAround's head-preload (gated on the same check there)
        // and prepareNextPlayer's stream, so nothing regresses on mobile.
        if (!fullDownloadsAllowed()) return;
        int have = countDownloadedUnwatchedAhead(pos);
        for (int i = pos + 1; i < items.size() && SvipeQueuePlan.needsMoreDownloads(have); i++) {
            FeedItem it = items.get(i);
            if (watchedSet != null && watchedSet.isWatched(it.channelId, it.messageId)) continue;
            if (it.mo == null) {
                resolveItem(it, () -> ensureFullDownloadsAhead(currentPosition)); // retry once resolved
                continue;
            }
            // The queue stores ONE file per reel: the target rendition when a ladder exists (~720p,
            // half the bytes of a 1080p source), the original document otherwise.
            VideoPlayer.VideoUri target = targetRendition(qualitiesFor(it.mo));
            TLRPC.Document doc = target != null ? target.document : it.mo.getDocument();
            if (doc == null) continue;
            if (fileFullyPresent(it)) {
                enqueueResolved(it, true); // already on disk — make sure it's persisted
                have++;
                continue;
            }
            // Respect the disk budget; stop starting new downloads once we'd blow past it. An item
            // already persisted in the queue (e.g. its download was cancelled by a stall recovery
            // and is being retried) contributes 0 NEW bytes/entries — don't double-count it, or a
            // near-full budget would permanently block its own retry.
            boolean queued = reelQueue.contains(it.channelId, it.messageId);
            long addBytes = queued ? 0 : doc.size;
            if (!SvipeQueuePlan.withinByteBudget(reelQueue.totalBytes(), addBytes, SvipeQueuePlan.MAX_QUEUE_BYTES)
                    || (!queued && reelQueue.size() >= SvipeQueuePlan.MAX_ENTRIES)) {
                break;
            }
            if (!fullDownloadStarted.contains(doc.id)) {
                fullDownloadStarted.add(doc.id);
                it.fullDownloadStarted = true;
                it.downloadDocId = doc.id; // the observers clean up by THIS id, not mo.getDocument()
                fileNameToItem.put(FileLoader.getAttachFileName(doc), it);
                enqueueResolved(it, false); // persist now (downloaded=false) so an in-flight load survives backgrounding
                try {
                    // FULL download (cacheType 0), bypassing the data-saving gate per product choice.
                    FileLoader.getInstance(account).loadFile(doc, it.mo, FileLoader.PRIORITY_LOW, 0);
                } catch (Exception e) { FileLog.e(e); }
            }
        }
    }

    /** Migrate an in-memory resolved item into the persisted offline queue (no extra MTProto). */
    private void enqueueResolved(FeedItem it, boolean downloaded) {
        if (reelQueue == null || it == null || it.mo == null || it.mo.messageOwner == null) return;
        if (watchedSet != null && watchedSet.isWatched(it.channelId, it.messageId)) return;
        if (reelQueue.contains(it.channelId, it.messageId)) {
            if (downloaded) {
                reelQueue.markDownloaded(it.channelId, it.messageId);
                reelQueue.persist();
            }
            return;
        }
        String b64 = serializeMessage(it.mo.messageOwner);
        if (b64 == null) return;
        SvipeReelQueue.Entry e = new SvipeReelQueue.Entry();
        e.channelId = it.channelId;
        e.messageId = it.messageId;
        e.username = it.username;
        e.shareUrl = it.shareUrl;
        e.topicId = it.topicId;
        e.recId = it.recId;
        // Persist the file the queue actually stores — the target rendition when a ladder exists.
        // sizeBytes drives the disk budget, so it must match the bytes really downloaded.
        VideoPlayer.VideoUri target = targetRendition(qualitiesFor(it.mo));
        TLRPC.Document doc = target != null ? target.document : it.mo.getDocument();
        e.documentId = doc != null ? doc.id : 0;
        e.sizeBytes = doc != null ? doc.size : 0;
        e.downloaded = downloaded;
        e.messageB64 = b64;
        reelQueue.enqueue(e);
        reelQueue.persist();
    }

    /**
     * Head-preload (Telegram's cacheType 10 = setIsPreloadVideoOperation): downloads only ~2MB +
     * the moov atom — enough for an instant first frame. When the player later streams the same
     * file, FileLoader converts the operation and reuses the preloaded ranges. Crucially this no
     * longer pulls FULL videos, so the current reel keeps the bandwidth.
     */
    private void preloadMedia(FeedItem item) {
        if (item == null || item.mo == null || item.preloadStarted) return;
        try {
            if (!item.preloadBypassGate && !DownloadController.getInstance(account).canPreloadStories()) return;
            ArrayList<VideoPlayer.Quality> qualities = qualitiesFor(item.mo);
            if (qualities != null) {
                // Laddered reel: warm the HLS manifests (a few hundred bytes each — the player
                // fetches the selected rung's playlist synchronously at prepare, so having them on
                // disk makes the adaptive start instant even on cellular) and head-preload the
                // target rendition — the same file the Wi-Fi queue completes later, so every
                // preloaded byte is reused rather than thrown away.
                item.preloadStarted = true;
                for (VideoPlayer.Quality q : qualities) {
                    for (VideoPlayer.VideoUri u : q.uris) {
                        if (u.manifestDocument != null && !u.isManifestCached()) {
                            FileLoader.getInstance(account).loadFile(u.manifestDocument, item.mo, FileLoader.PRIORITY_NORMAL, 0);
                        }
                    }
                }
                VideoPlayer.VideoUri target = targetRendition(qualities);
                if (target != null && target.document != null && !target.isCached()) {
                    FileLoader.getInstance(account).loadFile(target.document, item.mo, item.preloadPriority, 10);
                }
                return;
            }
            TLRPC.Document doc = item.mo.getDocument();
            if (doc == null) return;
            item.preloadStarted = true;
            FileLoader.getInstance(account).loadFile(doc, item.mo, item.preloadPriority, 10);
        } catch (Exception e) { FileLog.e(e); }
    }

    /**
     * Stop downloading an item's video without deleting what's cached. Used for the reel just
     * swiped away (its stream operation would otherwise keep pulling the WHOLE file at stream
     * priority, starving the new current reel) and for preloads that left the window.
     */
    private void stopLoadingFor(FeedItem item) {
        if (item == null || item.mo == null) return;
        try {
            // A repost can share the same file(s) as the current reel — never cancel those.
            FeedItem cur = currentPosition >= 0 && currentPosition < items.size() ? items.get(currentPosition) : null;
            if (cur == item) return; // never cancel the current reel from here
            java.util.HashSet<Long> protect = new java.util.HashSet<>();
            if (cur != null && cur.mo != null) {
                TLRPC.Document cd = cur.mo.getDocument();
                if (cd != null) protect.add(cd.id);
                for (TLRPC.Document d : ladderDocsWithManifests(cur.mo)) protect.add(d.id);
            }
            // Under HLS any rung — or its manifest — may hold the in-flight op; cancel them all.
            ArrayList<TLRPC.Document> docs = ladderDocsWithManifests(item.mo);
            if (docs.isEmpty()) {
                TLRPC.Document doc = item.mo.getDocument();
                if (doc != null) docs.add(doc);
            }
            for (TLRPC.Document d : docs) {
                if (!protect.contains(d.id)) {
                    FileLoader.getInstance(account).cancelLoadFile(d);
                }
            }
        } catch (Exception e) { FileLog.e(e); }
    }

    /**
     * Cancel a reel's in-flight STREAM ops — every rung and its manifest, since under HLS/ABR the
     * live op is on whichever rung the selector chose (and the dwell escalation fully downloads
     * that same rung), not on mo.getDocument(). An in-flight OFFLINE-queue download (tracked by
     * {@link FeedItem#downloadDocId}) is left running so it finishes and persists. Used on the way
     * out (onFragmentDestroy) where those HIGH-priority pulls are pure waste once the screen is gone.
     */
    private void cancelReelStreams(FeedItem item) {
        if (item == null || item.mo == null) return;
        try {
            ArrayList<TLRPC.Document> docs = ladderDocsWithManifests(item.mo);
            if (docs.isEmpty()) {
                TLRPC.Document d = item.mo.getDocument();
                if (d != null) docs.add(d);
            }
            for (TLRPC.Document d : docs) {
                if (item.downloadDocId != 0 && d.id == item.downloadDocId) continue; // keep the offline download
                FileLoader.getInstance(account).cancelLoadFile(d);
            }
        } catch (Exception e) { FileLog.e(e); }
    }

    private void hideLoadingFor(FeedItem item) {
        int idx = items.indexOf(item);
        if (idx < 0) return;
        ReelsHolder h = holderAt(idx);
        if (h != null) h.showLoading(false);
    }

    // ---------------- Telegram ABR ladder (multi-quality) helpers ----------------
    // Popular channels' videos arrive with server-made renditions (480/720/1080) + per-rendition
    // HLS manifests in media.alt_documents. Playing through VideoPlayer's qualities path gives
    // Instagram-style adaptive streaming: ExoPlayer picks a rung the CURRENT bandwidth can sustain
    // and switches mid-play, so a slowing connection degrades quality instead of buffering.

    /**
     * The quality ladder for a reel, or null when the message carries none (small channels) —
     * callers then use the legacy single-document path. Recomputed per call: VideoUri cached-flags
     * are resolved at build time, so a fresh call sees files downloaded since the last one.
     *
     * reference=0: inspection only (doc ids, sizes, cached-flags for priorities / cancels / budget /
     * presence). A real MTProto file reference is minted ONLY on the playback path
     * ({@link #playbackQualitiesFor}), because {@link FileLoader#getFileReference} inserts into a
     * process-lifetime map that is never pruned — calling it in the per-swipe inspection loops would
     * leak one MessageObject-retaining entry per call.
     */
    private ArrayList<VideoPlayer.Quality> qualitiesFor(MessageObject mo) {
        return buildQualities(mo, 0);
    }

    /** Ladder built with a live file reference embedded in each stream URI — for preparePlayer only. */
    private ArrayList<VideoPlayer.Quality> playbackQualitiesFor(MessageObject mo) {
        return buildQualities(mo, FileLoader.getInstance(account).getFileReference(mo));
    }

    private ArrayList<VideoPlayer.Quality> buildQualities(MessageObject mo, int reference) {
        if (mo == null || mo.messageOwner == null) return null;
        TLRPC.MessageMedia media = mo.messageOwner.media;
        if (!(media instanceof TLRPC.TL_messageMediaDocument) || media.alt_documents.isEmpty()) return null;
        try {
            // useFileDatabaseQueue=false — same flag the legacy VideoUri.of path uses here, so
            // "cached" means exactly "the player can open it from disk right now".
            ArrayList<VideoPlayer.Quality> q = VideoPlayer.getQualities(
                    account, media.document, media.alt_documents, reference, false, false);
            return q == null || q.isEmpty() ? null : q;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Every doc a reel's playback touches — video rungs AND their HLS manifests — for cancels. */
    private ArrayList<TLRPC.Document> ladderDocsWithManifests(MessageObject mo) {
        ArrayList<VideoPlayer.Quality> qs = qualitiesFor(mo);
        ArrayList<TLRPC.Document> docs = ladderVideoDocs(qs);
        if (qs != null) {
            for (VideoPlayer.Quality q : qs) {
                for (VideoPlayer.VideoUri u : q.uris) {
                    if (u.manifestDocument != null) docs.add(u.manifestDocument);
                }
            }
        }
        return docs;
    }

    /** Every playable rendition document of the ladder — the unit for priorities and cancels. */
    private static ArrayList<TLRPC.Document> ladderVideoDocs(ArrayList<VideoPlayer.Quality> qualities) {
        ArrayList<TLRPC.Document> docs = new ArrayList<>();
        if (qualities == null) return docs;
        for (VideoPlayer.Quality q : qualities) {
            for (VideoPlayer.VideoUri u : q.uris) {
                if (u.document != null) docs.add(u.document);
            }
        }
        return docs;
    }

    /**
     * The ONE file a reel is stored as (offline queue, Wi-Fi top-ups, dwell escalation): a rendition
     * already on disk if any (never download a second copy of the same reel), else the highest rung
     * at or below 720p — sharp on a phone at roughly half the bytes of a 1080p source — preferring
     * the smaller file inside a rung (the more efficient codec), else the smallest rung available.
     */
    private static VideoPlayer.VideoUri targetRendition(ArrayList<VideoPlayer.Quality> qualities) {
        if (qualities == null) return null;
        VideoPlayer.VideoUri best = null, smallest = null;
        for (VideoPlayer.Quality q : qualities) {
            for (VideoPlayer.VideoUri u : q.uris) {
                if (u.document == null) continue;
                if (u.isCached()) return u;
                if (smallest == null || u.size < smallest.size) smallest = u;
                int p = Math.min(u.width, u.height);
                if (p <= 720 + 55) { // the same rung tolerance Quality.p() uses
                    int bp = best == null ? 0 : Math.min(best.width, best.height);
                    if (best == null || bp < p || (bp == p && u.size < best.size)) {
                        best = u;
                    }
                }
            }
        }
        return best != null ? best : smallest;
    }

    /** The Quality wrapping a fully-cached rendition (pin it -> plays from disk, works offline), or null for AUTO. */
    private static VideoPlayer.Quality cachedQualityOf(ArrayList<VideoPlayer.Quality> qualities) {
        if (qualities == null) return null;
        for (VideoPlayer.Quality q : qualities) {
            for (VideoPlayer.VideoUri u : q.uris) {
                if (u.isCached()) return q;
            }
        }
        return null;
    }

    /** Width/height are known from the document long before the first frame — no layout jump. */
    private static float videoAspect(TLRPC.Document doc) {
        if (doc == null) return 0f;
        for (int i = 0; i < doc.attributes.size(); i++) {
            TLRPC.DocumentAttribute a = doc.attributes.get(i);
            if (a instanceof TLRPC.TL_documentAttributeVideo && a.h > 0) {
                return (float) a.w / a.h;
            }
        }
        return 0f;
    }

    private void startPlayback(MessageObject mo, TLRPC.Document doc, int pos, FeedItem item) {
        // Async callers (resolve callbacks, the start checker, deferred recovery) can land after the
        // fragment paused or died — never start audio/video in the background or on a dead fragment.
        // Nothing is lost: onResume re-arms the start checker, which re-kicks within one tick.
        if (isPaused || isFinished) return;
        ReelsHolder holder = holderAt(pos);
        if (holder == null) return;
        try {
            float ar = videoAspect(doc);
            if (ar > 0) holder.aspect.setAspectRatio(ar, 0);

            final boolean prepared = nextPlayer != null && nextPlayerPos == pos;
            VideoPlayer player;
            if (prepared) {
                player = nextPlayer;
                nextPlayer = null;
                nextPlayerPos = -1;
            } else {
                player = new VideoPlayer(true, false); // Svipe: pauseOther=true -> starting a reel pauses music, and music pauses the reel
                player.setIsReels();
                player.setLooping(true);
            }
            // This reel owns the bandwidth now: stream reads at HIGH so playback, loops and seeks
            // stay smooth. Under HLS every rung the selector may pick must be HIGH — the stream
            // priority map is per-document. Don't force the whole file up front — a quick glance
            // shouldn't cost the full download. Escalate to a full (cacheType 0) pull only once the
            // user has dwelled past MIN_WATCHED_MS and is still on this reel, so engaged reels keep
            // looping/seeking seamlessly while a skipped reel costs only the streamed prefix.
            // Real reference: this ladder feeds preparePlayer, whose stream URIs must carry a live
            // file reference (one getFileReference per played reel — legacy paid the same).
            final ArrayList<VideoPlayer.Quality> qualities = playbackQualitiesFor(mo);
            if (qualities != null) {
                for (TLRPC.Document d : ladderVideoDocs(qualities)) {
                    FileStreamLoadOperation.setPriorityForDocument(d, FileLoader.PRIORITY_HIGH);
                }
            } else {
                FileStreamLoadOperation.setPriorityForDocument(doc, FileLoader.PRIORITY_HIGH);
            }
            final VideoPlayer boundPlayer = player;
            AndroidUtilities.runOnUIThread(() -> {
                if (currentPosition == pos && currentPlayer == boundPlayer) {
                    try {
                        // Complete the file that is ACTUALLY streaming (same doc => FileLoader merges
                        // it into the live stream op — zero extra bandwidth) so loops replay from
                        // disk; fall back to the queue's target rendition, then the top document.
                        TLRPC.Document full = null;
                        if (qualities != null) {
                            full = boundPlayer.getCurrentDocument();
                            if (full == null) {
                                VideoPlayer.VideoUri target = targetRendition(qualities);
                                if (target != null) full = target.document;
                            }
                        }
                        if (full == null) full = doc;
                        FileLoader.getInstance(account).loadFile(full, mo, FileLoader.PRIORITY_HIGH, 0);
                    } catch (Exception e) { FileLog.e(e); }
                }
            }, SvipeQueuePlan.MIN_WATCHED_MS);
            player.setTextureView(holder.textureView);
            player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                // No STATE_ENDED handling: the player loops, so completion is derived from the
                // watch clock in flushWatchEvent() when the user leaves the reel.
                @Override
                public void onStateChanged(boolean playWhenReady, int playbackState) {
                    if (playbackState == ExoPlayer.STATE_READY) {
                        holder.showLoading(false);
                        handleFirstFrame(); // belt & braces: READY means frames are ready to render
                    } else if (playbackState == ExoPlayer.STATE_BUFFERING) {
                        holder.showLoading(true);
                    }
                }
                @Override
                public void onError(VideoPlayer p, Exception e) { FileLog.e(e); holder.showLoading(false); }
                @Override
                public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                    if (unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270) { int t = width; width = height; height = t; }
                    float ratio = height == 0 ? 1f : (width * pixelWidthHeightRatio) / height;
                    holder.aspect.setAspectRatio(ratio, unappliedRotationDegrees);
                }
                private boolean firstFrameSeen;

                // The live callback is the AnalyticsListener overload (with EventTime) — the
                // no-arg one exists in the delegate interface but VideoPlayer never routes the
                // player's real event to it. Handle both, once.
                @Override
                public void onRenderedFirstFrame() { handleFirstFrame(); }

                @Override
                public void onRenderedFirstFrame(com.google.android.exoplayer2.analytics.AnalyticsListener.EventTime eventTime) { handleFirstFrame(); }

                private void handleFirstFrame() {
                    if (firstFrameSeen) return;
                    firstFrameSeen = true;
                    AndroidUtilities.runOnUIThread(() -> {
                        holder.showLoading(false);
                        holder.textureView.setAlpha(1f);
                        holder.hideCover();
                        // The reel is genuinely rendering — disarm the stall watchdog. Guard so a late
                        // first frame from a PREVIOUS reel can't cancel the NEW reel's watchdog.
                        if (currentPosition == pos && currentPlayer == boundPlayer) {
                            long sinceRequest = System.currentTimeMillis() - playRequestMs;
                            if (rescueChannelId == item.channelId && rescueMessageId == item.messageId
                                    && System.currentTimeMillis() - rescueLeftAtMs <= 30000) {
                                // The user left this reel spinning, came back, and it now renders:
                                // a manual skip-and-back rescue. Report what the auto-recovery had
                                // (not) managed before they gave up — the exact evidence needed to
                                // debug the stuck-reel bug on real devices.
                                try {
                                    JSONObject p = new JSONObject();
                                    p.put("spinner_ms_before_leave", rescueLeftSpinnerMs);
                                    p.put("attempts_before_leave", rescueLeftAttempts);
                                    p.put("ms_away", playRequestMs - rescueLeftAtMs);
                                    p.put("ms_to_frame_after_return", sinceRequest);
                                    sendDiag(item, "manual_rescue", p);
                                } catch (Exception ignore) {}
                                rescueChannelId = 0;
                                rescueMessageId = 0;
                            }
                            currentReelFirstFrame = true;
                            // stuckRecoveryAttempts is NOT reset here: a recovery rebuild resumed
                            // mid-reel renders its first frame from disk bytes and can starve again
                            // within a second — resetting on the frame alone would grant every churn
                            // cycle a fresh quick budget and defeat the exponential cooldown. The
                            // watchdog's healthy PLAY tick resets it (and reports 'recovered') once
                            // the reel demonstrably plays without buffering.
                            // Watchdog stays armed: it flips into its PLAY phase to catch reels
                            // that starve MID-playback (spinner with no pre-frame watchdog).
                            if (pendingPrefetchRearm) {
                                // Recovery cleared the ahead-window downloads to free the pipe for this
                                // reel; now that it renders, re-arm the window (same calls playPosition makes).
                                pendingPrefetchRearm = false;
                                prefetchAround(pos);
                                ensureFullDownloadsAhead(pos);
                            }
                        }
                        FileLog.d("svipe: first frame pos=" + pos + " in " + (System.currentTimeMillis() - playRequestMs) + "ms prepared=" + prepared);
                        // The screen is busy with a playing video — perfect moment to warm the next one.
                        if (currentPosition == pos && nextPlayerPos != pos + 1) {
                            prepareNextPlayer(pos + 1);
                        }
                    });
                }
                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {}
                @Override
                public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) { return false; }
            });
            if (!prepared) {
                // preparePlayer MUST come after setDelegate: VideoPlayer reports the first state
                // change during prepare and NPEs on a null delegate.
                if (qualities != null) {
                    // Adaptive path: a fully-cached rendition is pinned (plays from disk, works
                    // offline); otherwise AUTO — ExoPlayer starts on a rung the bandwidth estimate
                    // sustains and adapts mid-play instead of buffering.
                    VideoPlayer.Quality cached = cachedQualityOf(qualities);
                    FileLog.d("svipe: play pos=" + pos + " hls rungs=" + qualities.size()
                            + " source=" + (cached != null ? "LOCAL-cache" : "network-auto")
                            + " fromQueue=" + item.fromQueue);
                    player.preparePlayer(qualities, cached);
                } else {
                    int reference = FileLoader.getInstance(account).getFileReference(mo);
                    VideoPlayer.VideoUri vu = VideoPlayer.VideoUri.of(account, doc, null, reference, false);
                    FileLog.d("svipe: play pos=" + pos + " source=" + (vu.isCached() ? "LOCAL-cache" : "network")
                            + " fromQueue=" + item.fromQueue);
                    player.preparePlayer(vu.uri, "other");
                }
                if (pendingSeekToMs > 0) {
                    // Mid-play recovery rebuild: pick up where the starved player left off.
                    try { player.seekTo(pendingSeekToMs); } catch (Exception ignore) {}
                    pendingSeekToMs = 0;
                }
            }
            player.setPlayWhenReady(true);
            player.play();
            currentPlayer = player;
            scheduleStuckWatchdog(pos, boundPlayer);
            watchStartMs = System.currentTimeMillis();
        } catch (Exception e) {
            FileLog.e(e);
            holder.showLoading(false);
        }
    }

    private void cancelStuckWatchdog() {
        if (positionUpdateHandler != null && stuckWatchdogRunnable != null) {
            positionUpdateHandler.removeCallbacks(stuckWatchdogRunnable);
        }
    }

    private static boolean safeIsBuffering(VideoPlayer p) {
        try { return p.isBuffering(); } catch (Exception ignore) { return false; }
    }

    /**
     * Watchdog for the "reel stuck on an infinite spinner, skip-and-back fixes it" bug: a player
     * whose stream stalled (parked read in the FileLoader priority queue) is never woken by
     * promotion, which only re-maps priority. Covers the reel's WHOLE visible life in two phases:
     *  - PRE (no first frame yet): PARKED = zero buffered-position growth across consecutive
     *    ticks (the wedged-stream case); OVERDUE = bytes may still trickle, but no first frame
     *    long past the point ExoPlayer needs (100ms of buffer).
     *  - PLAY (rendering started): a reel that starves MID-playback (buffered prefix exhausted,
     *    stream wedged) shows the same infinite spinner with no watchdog to save it — reproduced
     *    live on the emulator: network blip mid-reel wedges the op, and it never resumes even
     *    after connectivity returns. Same two signatures, measured from when buffering began;
     *    recovery additionally resumes from the playback position instead of restarting at 0.
     * Recovery rebuilds the reel fresh, exactly like a manual revisit. The user can repeat the
     * manual skip-and-back forever, so the watchdog never gives up either: quick attempts first,
     * then cooldown-paced retries for as long as the reel stays current and stuck.
     */
    private void scheduleStuckWatchdog(final int pos, final VideoPlayer boundPlayer) {
        if (positionUpdateHandler == null) {
            positionUpdateHandler = new Handler(Looper.getMainLooper());
        }
        if (stuckWatchdogRunnable != null) {
            positionUpdateHandler.removeCallbacks(stuckWatchdogRunnable);
        }
        lastBufferedPos = -1;
        noProgressTicks = 0;
        watchdogArmedMs = System.currentTimeMillis();
        stuckWatchdogRunnable = new Runnable() {
            private long bufferingSinceMs; // PLAY phase: when the current mid-play stall began (0 = playing fine)

            @Override
            public void run() {
                // Bail only if the page changed or the player was swapped — rendering reels stay
                // watched (PLAY phase) because mid-playback starvation wedges just like startup.
                if (currentPosition != pos || currentPlayer != boundPlayer) {
                    return;
                }
                if (userPaused) {
                    // The user deliberately paused a still-buffering reel — never force-restart it.
                    // Refresh the deadlines so nothing fires the instant they unpause.
                    watchdogArmedMs = System.currentTimeMillis();
                    bufferingSinceMs = 0;
                    positionUpdateHandler.postDelayed(this, STUCK_TICK_MS);
                    return;
                }
                long buffered = 0;
                try { buffered = boundPlayer.getBufferedPosition(); } catch (Exception ignore) {}
                if (currentReelFirstFrame) {
                    long duration = 0;
                    try { duration = boundPlayer.getDuration(); } catch (Exception ignore) {}
                    boolean fullyBuffered = duration > 0 && buffered >= duration - 500;
                    if (!safeIsBuffering(boundPlayer) || fullyBuffered) {
                        // PLAY phase, playing healthily (or everything is already local — a stream
                        // rebuild can't help a decoder hiccup): reset stall clock and counters.
                        bufferingSinceMs = 0;
                        lastBufferedPos = -1;
                        noProgressTicks = 0;
                        if (stuckRecoveryAttempts > 0) {
                            // Demonstrably playing again after >=1 recovery — THIS is the real
                            // "recovered" moment (a rebuilt frame alone can starve again at once).
                            // Reset grants future stalls a fresh quick budget.
                            int attempts = stuckRecoveryAttempts;
                            stuckRecoveryAttempts = 0;
                            if (pos < items.size()) {
                                try {
                                    JSONObject p = new JSONObject();
                                    p.put("attempts", attempts);
                                    p.put("spinner_ms", System.currentTimeMillis() - playRequestMs);
                                    sendDiag(items.get(pos), "recovered", p);
                                } catch (Exception ignore) {}
                            }
                        }
                        positionUpdateHandler.postDelayed(this, STUCK_TICK_MS);
                        return;
                    }
                }
                if (buffered > lastBufferedPos && buffered > 0) {
                    noProgressTicks = 0;   // bytes are arriving — treat as slow, not parked
                } else {
                    noProgressTicks++;     // a parked stream delivers exactly zero
                }
                if (buffered > lastBufferedPos) lastBufferedPos = buffered;
                boolean parked;
                boolean overdue;
                if (!currentReelFirstFrame) {
                    parked = noProgressTicks >= STUCK_TICKS
                            && (buffered == 0 || safeIsBuffering(boundPlayer));
                    overdue = System.currentTimeMillis() - watchdogArmedMs >= STUCK_HARD_DEADLINE_MS;
                } else {
                    // PLAY phase, buffering: clock the stall from when buffering started, not from
                    // arm time — a reel can play healthily for a minute before starving. PARKED
                    // (zero growth of THIS reel's buffered position) is the precise wedge signature
                    // — a dead request window delivers exactly nothing. An honest slow rebuffer
                    // keeps trickling, so give it 3x the pre-frame deadline before forcing a
                    // rebuild; cancelling a working op only re-buys the same wait.
                    if (bufferingSinceMs == 0) bufferingSinceMs = System.currentTimeMillis();
                    long stalled = System.currentTimeMillis() - bufferingSinceMs;
                    parked = noProgressTicks >= STUCK_TICKS;
                    overdue = stalled >= STUCK_HARD_DEADLINE_MS * 3;
                }
                if (parked || overdue) {
                    // Quick attempts fire immediately; past the cap keep retrying on an EXPONENTIAL
                    // cooldown (6s -> 12s -> 24s -> 48s cap). The growing window matters on very slow
                    // links: MTProto streams land in atomic 128KB chunks, so retries must leave enough
                    // room for a whole chunk or the loop would starve itself (see recoverStuckReel —
                    // retries also stop cancelling the underlying op for the same reason).
                    long cooldown = STUCK_RETRY_COOLDOWN_MS
                            << Math.min(3, Math.max(0, stuckRecoveryAttempts - MAX_STUCK_RECOVERIES));
                    if (stuckRecoveryAttempts < MAX_STUCK_RECOVERIES
                            || System.currentTimeMillis() - lastStuckRecoveryMs >= cooldown) {
                        // re-arms the watchdog via its startPlayback
                        recoverStuckReel(pos, (currentReelFirstFrame ? "midplay_" : "")
                                + (parked ? "parked" : "overdue"));
                        return;
                    }
                    // inside the cooldown — keep watching rather than giving up
                }
                positionUpdateHandler.postDelayed(this, STUCK_TICK_MS);
            }
        };
        positionUpdateHandler.postDelayed(stuckWatchdogRunnable, STUCK_FIRST_FRAME_GRACE_MS);
    }

    /** Rebuild a stuck reel from scratch — automates the manual skip-and-back that users rely on. */
    private void recoverStuckReel(final int pos, final String cause) {
        if (pos != currentPosition) return;
        if (pos < 0 || pos >= items.size()) return;
        long now = System.currentTimeMillis();
        if (stuckRecoveryAttempts > 0 && now - lastStuckRecoveryMs < 1000) return; // debounce double-fires
        final FeedItem item = items.get(pos);
        if (item == null || item.mo == null) return;
        final TLRPC.Document doc = item.mo.getDocument();
        if (doc == null) return;
        stuckRecoveryAttempts++;
        lastStuckRecoveryMs = now;
        // Mid-play recovery: resume where playback starved instead of restarting at 0, and drop
        // back to pre-frame state so the rebuild guards, checker and PRE watchdog phase apply to
        // the rebuilding reel exactly as they do to a fresh page.
        long resumeMs = 0;
        if (currentReelFirstFrame && currentPlayer != null) {
            try { resumeMs = Math.max(0, currentPlayer.getCurrentPosition()); } catch (Exception ignore) {}
        }
        pendingSeekToMs = resumeMs > 500 ? resumeMs : 0;
        currentReelFirstFrame = false;
        FileLog.d("svipe: recovering stuck reel pos=" + pos + " attempt=" + stuckRecoveryAttempts
                + " cause=" + cause + " resumeMs=" + resumeMs);
        // First attempts only: cooldown-paced repeats add no information (the final count rides in
        // the 'recovered' diag) and each diag is a blocking POST on the shared globalQueue — worst
        // exactly when the network is bad, which is when long recovery loops happen.
        if (stuckRecoveryAttempts <= 3) {
            try {
                JSONObject p = new JSONObject();
                p.put("attempt", stuckRecoveryAttempts);
                p.put("cause", cause);
                p.put("spinner_ms", now - playRequestMs);
                p.put("buffered_ms", lastBufferedPos);
                if (resumeMs > 0) p.put("resume_ms", resumeMs);
                sendDiag(item, "auto_recovery", p);
            } catch (Exception ignore) {}
        }
        releaseCurrentPlayer();
        releaseNextPlayer();
        // Free the download pipe the way a manual skip-and-back does: the large-file queue has only
        // 2 active slots, and the stall usually means this reel's op is parked BEHIND the ahead
        // window's head-preloads / Wi-Fi full downloads. Cancel the AHEAD in-flight ops only (bytes
        // on disk survive; behind reels' ops were already stopped on page change, and cancelling
        // them here would orphan their offline-queue downloads — first-frame re-arm only covers
        // ahead). The window is re-armed on this reel's first frame via pendingPrefetchRearm.
        for (int i = pos + 1; i < items.size(); i++) {
            FeedItem other = items.get(i);
            if (other == null || (!other.preloadStarted && !other.fullDownloadStarted)) continue;
            other.preloadStarted = false;
            other.fullDownloadStarted = false;
            // Free the in-flight-download guard by the id the download was REGISTERED under — the
            // target rendition (downloadDocId), not mo.getDocument() (the top rung). Using the wrong
            // id leaves a stale entry that blocks this reel from ever being re-queued this session.
            long otherId = other.downloadDocId != 0 ? other.downloadDocId
                    : (other.mo != null && other.mo.getDocument() != null ? other.mo.getDocument().id : 0);
            if (otherId != 0) fullDownloadStarted.remove(otherId); // let the Wi-Fi top-up retry later
            other.downloadDocId = 0;
            stopLoadingFor(other); // its guard keeps a repost sharing THIS reel's file untouched
        }
        // Destroy the wedged operation on EVERY attempt — the field-proven manual fix. Releasing
        // the player only detaches the stream listener: the operation stays in stateDownloading
        // with its request window full of dead in-flight requests, so any start()/changePriority
        // kick is silently swallowed by startDownloadRequest's full-window guard — no priority
        // shuffle can ever revive it (v2 tried exactly that on retries; provably a no-op).
        // cancelLoadFile() is the only reset that empties the window: completed chunks stay on
        // disk (.temp/.pt survive a cancel — only the in-flight chunk's progress is lost), and
        // the fresh operation the rebuild creates below passes the guard and issues brand-new
        // requests. The exponential cooldown in the watchdog paces these cancels, so even a very
        // slow link keeps banking at least a chunk per attempt. Under HLS any rung — or a rung's
        // tiny MANIFEST fetch — may own the wedged op; a surviving wedged manifest op would be
        // re-attached by the rebuild's playlist fetch and park it again. Reset all of them.
        try {
            ArrayList<TLRPC.Document> ownDocs = ladderDocsWithManifests(item.mo);
            if (ownDocs.isEmpty()) ownDocs.add(doc);
            for (TLRPC.Document d : ownDocs) {
                FileLoader.getInstance(account).cancelLoadFile(d);
            }
        } catch (Exception e) { FileLog.e(e); }
        pendingPrefetchRearm = true;
        ReelsHolder holder = holderAt(pos);
        if (holder != null) holder.showLoading(true);
        // Give the cancels one beat to drain on the loader thread before rebuilding — the manual
        // round-trip has a whole swipe between cancel and rebuild, and a same-tick rebuild can race
        // the cancel inside FileLoader and re-park the fresh stream.
        AndroidUtilities.runOnUIThread(() -> {
            // items.get(pos) == item guards against the list compacting under us (block-channel):
            // never start playback for an item that no longer occupies this slot.
            if (currentPosition == pos && currentPlayer == null && !currentReelFirstFrame
                    && !isPaused && pos < items.size() && items.get(pos) == item && item.mo != null) {
                // Fresh, non-prepared branch: new VideoPlayer + preparePlayer with a re-fetched file
                // reference and a brand-new FileStreamLoadOperation. startPlayback re-arms the watchdog.
                startPlayback(item.mo, doc, pos, item);
            }
        }, STUCK_REBUILD_DELAY_MS);
    }

    /**
     * Backstop for reels stuck BEFORE a player exists (resolve hung or failed, startPlayback threw):
     * the buffered-position watchdog can't arm without a player, so this checker re-kicks playback
     * until the first frame renders. Self-guarding tick — dies on page change or once rendering.
     */
    private void schedulePlaybackStartChecker(final int pos) {
        if (playbackStartChecker != null) {
            AndroidUtilities.cancelRunOnUIThread(playbackStartChecker);
        }
        playbackStartChecker = new Runnable() {
            @Override
            public void run() {
                if (currentPosition != pos || currentReelFirstFrame || isPaused) return; // moved on / rendering / backgrounded
                // Yield while a recovery rebuild is pending (currentPlayer==null during its 250ms
                // drain window) — a same-tick re-kick would recreate the cancel/rebuild race the
                // delay exists to avoid. Also never re-kick a reel the user deliberately paused.
                if (currentPlayer == null && !userPaused
                        && System.currentTimeMillis() - lastStuckRecoveryMs >= STUCK_REBUILD_DELAY_MS + STUCK_TICK_MS) {
                    FileLog.d("svipe: no player after page change — re-kicking pos=" + pos);
                    resolveAndPlay(pos); // resolves if needed, then startPlayback (guards inside)
                }
                AndroidUtilities.runOnUIThread(this, PLAYBACK_START_DEADLINE_MS);
            }
        };
        AndroidUtilities.runOnUIThread(playbackStartChecker, PLAYBACK_START_DEADLINE_MS);
    }

    /** Build the next reel's player ahead of time: prepared, paused, buffering at LOW priority. */
    private void prepareNextPlayer(int pos) {
        releaseNextPlayer();
        if (!SvipePreloadPlan.shouldPrepareNextPlayer(pos, items.size())) return;
        FeedItem item = items.get(pos);
        if (item.mo == null) {
            resolveItem(item, () -> {
                if (currentPosition + 1 == pos && nextPlayer == null) prepareNextPlayer(pos);
            });
            return;
        }
        try {
            TLRPC.Document doc = item.mo.getDocument();
            if (doc == null) return;
            VideoPlayer p = new VideoPlayer(true, false); // Svipe: pauseOther=true -> mutual exclusion with music
            p.setIsReels();
            p.setLooping(true);
            // VideoPlayer NPEs if a state change arrives with no delegate — give the idle player
            // a no-op one; startPlayback swaps in the real holder-bound delegate on use.
            p.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override
                public void onStateChanged(boolean playWhenReady, int playbackState) {}
                @Override
                public void onError(VideoPlayer player, Exception e) { FileLog.e(e); }
                @Override
                public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {}
                @Override
                public void onRenderedFirstFrame() {}
                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {}
                @Override
                public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) { return false; }
            });
            // Buffer the next reel at NORMAL — fast enough to be ready by the swipe, but still below
            // the current reel's HIGH stream and above the LOW background full-downloads, so it never
            // starves what's playing. (Promotion in startPlayback re-maps everything to HIGH.)
            ArrayList<VideoPlayer.Quality> qualities = playbackQualitiesFor(item.mo);
            if (qualities != null) {
                for (TLRPC.Document d : ladderVideoDocs(qualities)) {
                    FileStreamLoadOperation.setPriorityForDocument(d, FileLoader.PRIORITY_NORMAL);
                }
                p.preparePlayer(qualities, cachedQualityOf(qualities));
            } else {
                int reference = FileLoader.getInstance(account).getFileReference(item.mo);
                VideoPlayer.VideoUri vu = VideoPlayer.VideoUri.of(account, doc, null, reference, false);
                FileStreamLoadOperation.setPriorityForDocument(doc, FileLoader.PRIORITY_NORMAL);
                p.preparePlayer(vu.uri, "other");
            }
            p.setPlayWhenReady(false);
            nextPlayer = p;
            nextPlayerPos = pos;
            FileLog.d("svipe: prepared next player pos=" + pos);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void releaseNextPlayer() {
        if (nextPlayer != null) {
            try { nextPlayer.releasePlayer(true); } catch (Exception ignore) {}
            nextPlayer = null;
        }
        nextPlayerPos = -1;
    }

    private void togglePlayPause() {
        if (currentPlayer == null) return;
        userPaused = !userPaused;
        if (userPaused) {
            currentPlayer.pause();
            pauseWatchClock();
        } else {
            currentPlayer.play();
            watchStartMs = System.currentTimeMillis();
        }
        ReelsHolder h = holderAt(currentPosition);
        if (h != null) h.setPaused(userPaused);
    }

    /** True when a single tap landed on the current reel's action rail or info box (channel + caption),
     *  i.e. on a control that handles its own click — so the tap must not toggle play/pause. */
    private boolean tapOnControls(MotionEvent e) {
        int pos = currentPosition;
        if (pos < 0 || pos >= items.size()) pos = layoutManager.findFirstVisibleItemPosition();
        ReelsHolder h = holderAt(pos);
        if (h == null) return false;
        return pointInView(h.actionRail, e) || pointInView(h.infoBox, e);
    }

    private static boolean pointInView(View v, MotionEvent e) {
        if (v == null || v.getVisibility() != View.VISIBLE || v.getWidth() == 0 || v.getHeight() == 0) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float x = e.getRawX(), y = e.getRawY();
        return x >= loc[0] && x < loc[0] + v.getWidth() && y >= loc[1] && y < loc[1] + v.getHeight();
    }

    /** Invoke the action for whichever control the tap landed on (rail buttons, follow, channel,
     *  caption); returns true if one handled it. Driven by the list-level gesture detector so a
     *  RecyclerView scroll-claim can't swallow the tap the way a child onClick would. */
    private boolean dispatchControlTap(MotionEvent e) {
        int pos = currentPosition;
        if (pos < 0 || pos >= items.size()) pos = layoutManager.findFirstVisibleItemPosition();
        ReelsHolder h = holderAt(pos);
        if (h == null) return false;
        FeedItem it = itemFor(h);
        if (it == null) return false;
        if (pointInView(h.likeIcon, e) || pointInView(h.likeCount, e)) { toggleLike(it, h); return true; }
        if (pointInView(h.commentIcon, e) || pointInView(h.commentCount, e)) {
            // "Izoh" (0) posts consume the tap but do nothing (no comments thread to open).
            if (it.mo != null && it.mo.getRepliesCount() > 0) openCommentsSheet(it);
            return true;
        }
        if (pointInView(h.shareIcon, e) || pointInView(h.shareCount, e)) { share(it); return true; }
        if (pointInView(h.moreIcon, e)) { showMore(it, h); return true; }
        if (pointInView(h.followBtn, e)) { toggleFollow(it, h); return true; }
        if (pointInView(h.avatar, e) || pointInView(h.channelName, e)) { openComments(it); return true; }
        if (pointInView(h.title, e)) {
            h.titleExpanded = !h.titleExpanded;
            h.title.setMaxLines(h.titleExpanded ? 100 : 2);
            return true;
        }
        return false;
    }

    // ===================== Pinch-to-zoom on the playing video =====================
    // Telegram's own player lets you pinch a playing video so it floats above all UI and snaps back on
    // release. We reproduce that for reels: transform the live video view in place and fade every
    // overlay during the gesture, which reads identically — the video alone, on top of everything.

    /** Route a possible two-finger pinch. Returns true while the zoom owns the gesture (so the list
     *  stops paging and single-tap/play-pause are bypassed). Fed from the list-level touch listener. */
    private boolean handlePinch(MotionEvent e) {
        final int action = e.getActionMasked();
        if (!pinchClaimed) {
            if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) {
                ReelsHolder h = holderAt(currentPosition);
                if (h == null) return false; // nothing to zoom — let paging/taps proceed
                startPinch(e, h);
                return true;
            }
            return false;
        }
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                if (pinchActive && e.getPointerCount() >= 2) updatePinch(e);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (pinchActive && e.getPointerCount() == 2) finishPinch(); // 2 -> 1 finger: release
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pinchActive) finishPinch();
                pinchClaimed = false;
                break;
        }
        return true; // keep swallowing the rest of this gesture (e.g. the lone remaining finger)
    }

    private void startPinch(MotionEvent e, ReelsHolder h) {
        if (peeking) cancelPeek(); // a pinch supersedes a peek; the pinch manages chrome from here
        if (pinchFinishAnimator != null) { pinchFinishAnimator.cancel(); pinchFinishAnimator = null; }
        pinchHolder = h;
        pinchClaimed = true;
        pinchActive = true;
        pinchStartDistance = Math.max(1f, (float) Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)));
        pinchCenterX = (e.getX(0) + e.getX(1)) / 2f;
        pinchCenterY = (e.getY(0) + e.getY(1)) / 2f;
        pinchScale = 1f;
        pinchTransX = pinchTransY = 0f;
        // Anchor the zoom at the initial pinch point; dragging the two fingers pans it (Telegram-style).
        // The page/aspect fill the screen, so the list-local event coords are the aspect's pivot coords.
        if (h.aspect != null) { h.aspect.setPivotX(pinchCenterX); h.aspect.setPivotY(pinchCenterY); }
        if (h.cover != null) { h.cover.setPivotX(pinchCenterX); h.cover.setPivotY(pinchCenterY); }
        // Lift the floating bottom tab bar out of the way so the zoomed video rises above it too.
        if (mainTabsController != null) mainTabsController.setTabsVisible(false);
    }

    private void updatePinch(MotionEvent e) {
        if (pinchHolder == null) return;
        float dist = (float) Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0));
        float cx = (e.getX(0) + e.getX(1)) / 2f;
        float cy = (e.getY(0) + e.getY(1)) / 2f;
        pinchScale = Math.max(1f, Math.min(PINCH_MAX_SCALE, dist / pinchStartDistance));
        pinchTransX = cx - pinchCenterX;
        pinchTransY = cy - pinchCenterY;
        applyPinch(pinchHolder, pinchScale, pinchTransX, pinchTransY);
    }

    private void applyPinch(ReelsHolder h, float scale, float tx, float ty) {
        if (h == null) return;
        if (h.aspect != null) {
            h.aspect.setScaleX(scale); h.aspect.setScaleY(scale);
            h.aspect.setTranslationX(tx); h.aspect.setTranslationY(ty);
        }
        if (h.cover != null) {
            h.cover.setScaleX(scale); h.cover.setScaleY(scale);
            h.cover.setTranslationX(tx); h.cover.setTranslationY(ty);
        }
        // Fade all chrome out as the zoom grows — fully gone by 1.5x — so nothing covers the video.
        float chrome = Math.max(0f, Math.min(1f, 1f - (scale - 1f) / 0.5f));
        setChromeAlpha(h, chrome);
    }

    /** Fade everything over the video: the page's overlays (rail/caption/gradient) except the video
     *  itself, plus the root overlays (scrub bar, status, back, input) except the reel list. */
    private void setChromeAlpha(ReelsHolder h, float alpha) {
        if (h != null && h.itemView instanceof ViewGroup) {
            ViewGroup page = (ViewGroup) h.itemView;
            for (int i = 0; i < page.getChildCount(); i++) {
                View c = page.getChildAt(i);
                if (c == h.aspect || c == h.cover) continue;
                c.setAlpha(alpha);
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c != listView) c.setAlpha(alpha);
        }
    }

    private void finishPinch() {
        pinchActive = false;
        // Bring the bottom tab bar back as the video springs home.
        if (mainTabsController != null) mainTabsController.setTabsVisible(true);
        final ReelsHolder h = pinchHolder;
        if (h == null) return;
        final float fromScale = pinchScale, fromTx = pinchTransX, fromTy = pinchTransY;
        if (pinchFinishAnimator != null) pinchFinishAnimator.cancel();
        pinchFinishAnimator = ValueAnimator.ofFloat(1f, 0f);
        pinchFinishAnimator.setDuration(220);
        pinchFinishAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        pinchFinishAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            applyPinch(h, 1f + (fromScale - 1f) * t, fromTx * t, fromTy * t);
        });
        pinchFinishAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                resetPinchTransform(h);
                if (pinchHolder == h) pinchHolder = null;
                pinchFinishAnimator = null;
            }
        });
        pinchFinishAnimator.start();
    }

    /** Hard-reset a holder's zoom transform + restore every overlay alpha to 1 (page + root). */
    private void resetPinchTransform(ReelsHolder h) {
        resetHolderZoom(h);
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c != listView) c.setAlpha(1f);
        }
    }

    /** Reset just the holder's own views (video transform + its page-overlay alphas) — also used
     *  defensively when a holder is (re)bound so a recycled one never starts mid-zoom. */
    private void resetHolderZoom(ReelsHolder h) {
        if (h == null) return;
        if (h.aspect != null) {
            h.aspect.setScaleX(1f); h.aspect.setScaleY(1f);
            h.aspect.setTranslationX(0f); h.aspect.setTranslationY(0f);
        }
        if (h.cover != null) {
            h.cover.setScaleX(1f); h.cover.setScaleY(1f);
            h.cover.setTranslationX(0f); h.cover.setTranslationY(0f);
        }
        if (h.itemView instanceof ViewGroup) {
            ViewGroup page = (ViewGroup) h.itemView;
            for (int i = 0; i < page.getChildCount(); i++) page.getChildAt(i).setAlpha(1f);
        }
    }

    /** Wired by MainTabsActivity when this is the "Reels" tab — used so the pinch-zoom can hide the
     *  floating bottom tab bar (it lives in MainTabsActivity, above this fragment). */
    public void setMainTabsActivityController(MainTabsActivityController controller) {
        this.mainTabsController = controller;
    }

    // ===================== Long-press peek (hide all chrome) =====================
    // Press and hold one finger on a reel to peek the bare video: rail, caption, scrub bar, the
    // bottom tab bar and even the pause icon all fade away. Lift to bring them back. Playback is
    // never touched — a playing reel keeps playing, a paused one stays paused (no pause icon while held).

    private void startPeek(ReelsHolder h) {
        if (peeking || pinchClaimed) return;
        peeking = true;
        peekHolder = h;
        if (mainTabsController != null) mainTabsController.setTabsVisible(false);
        animatePeek(h, 0f); // fade all chrome out
        try { listView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); } catch (Exception ignore) {}
    }

    private void endPeek() {
        if (!peeking) return;
        peeking = false;
        if (mainTabsController != null) mainTabsController.setTabsVisible(true);
        final ReelsHolder h = peekHolder;
        if (h != null) animatePeek(h, 1f); // fade all chrome back in
    }

    /** Drop the peek WITHOUT restoring chrome — used when a pinch takes over (the pinch owns chrome). */
    private void cancelPeek() {
        peeking = false;
        if (peekAnimator != null) { peekAnimator.cancel(); peekAnimator = null; }
        peekHolder = null;
    }

    private void animatePeek(ReelsHolder h, float to) {
        if (peekAnimator != null) peekAnimator.cancel();
        peekAnimator = ValueAnimator.ofFloat(to == 0f ? 1f : 0f, to);
        peekAnimator.setDuration(180);
        peekAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        peekAnimator.addUpdateListener(a -> setChromeAlpha(h, (float) a.getAnimatedValue()));
        peekAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                setChromeAlpha(h, to); // snap to exact target
                if (to == 1f && peekHolder == h) peekHolder = null;
                peekAnimator = null;
            }
        });
        peekAnimator.start();
    }

    private void endPeekOnUp(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) endPeek();
    }

    private void pauseWatchClock() {
        if (watchStartMs > 0) {
            watchedAccumMs += System.currentTimeMillis() - watchStartMs;
            watchStartMs = 0;
        }
    }

    private void releaseCurrentPlayer() {
        if (currentPlayer != null) {
            try { currentPlayer.releasePlayer(true); } catch (Exception ignore) {}
            currentPlayer = null;
        }
        // Reset the scrub bar so the incoming reel never briefly shows the previous reel's position;
        // hide it until the poll learns the new clip's duration (short clips then stay hidden, no flash).
        if (seekBar != null) {
            seekBar.setProgress(0f);
            seekBar.setVisibility(View.GONE);
        }
    }

    // ---------------- action handlers ----------------

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
        if (mo == null) return false;
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> chosen = mo.getChoosenReactions();
        for (int i = 0; i < chosen.size(); i++) {
            if (LIKE_EMOJI.equals(chosen.get(i).emojicon)) return true;
        }
        return false;
    }

    private static CharSequence captionOf(MessageObject mo) {
        if (mo == null) return null;
        if (mo.caption != null && mo.caption.length() > 0) return mo.caption;
        if (mo.messageOwner != null && mo.messageOwner.message != null && mo.messageOwner.message.length() > 0) {
            return mo.messageOwner.message;
        }
        return null;
    }

    private void toggleLike(FeedItem item, ReelsHolder holder) {
        if (item == null || item.mo == null) return;
        setLike(item, holder, !item.liked, false);
    }

    /**
     * Apply a like/unlike using the locally tracked {@link FeedItem#liked} state as the source of
     * truth (the message's own reaction list proved unreliable for unliking — emoji variation
     * selectors made the equality check miss). When {@code newLiked == item.liked} we no-op the
     * network call but still let the caller show the heart-burst animation.
     */
    private void setLike(FeedItem item, ReelsHolder holder, boolean newLiked, boolean big) {
        if (item == null || item.mo == null) return;
        if (item.liked == newLiked) return;
        ReactionsLayoutInBubble.VisibleReaction heart = ReactionsLayoutInBubble.VisibleReaction.fromEmojicon(LIKE_EMOJI);
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> visible = new ArrayList<>();
        if (newLiked) visible.add(heart);
        SendMessagesHelper.getInstance(account).sendReaction(item.mo, visible, newLiked ? heart : null, big, true, this, null);
        item.liked = newLiked;
        item.likeCount = Math.max(0, item.likeCount + (newLiked ? 1 : -1));
        if (holder != null) {
            holder.setLiked(newLiked);
            holder.setLikeCount(item.likeCount);
        }
        sendEvent(newLiked ? "LIKE" : "UNLIKE", item);
    }

    /** TikTok-style flying heart at the double-tap point. */
    private void showHeartBurst(FrameLayout page, float x, float y) {
        if (page == null) return;
        final ImageView heart = new ImageView(page.getContext());
        heart.setImageResource(R.drawable.media_like_active);
        heart.setColorFilter(0xFFFF2E38);
        int size = AndroidUtilities.dp(110);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.leftMargin = (int) x - size / 2;
        lp.topMargin = (int) y - size / 2;
        heart.setLayoutParams(lp);
        heart.setScaleX(0f);
        heart.setScaleY(0f);
        heart.setRotation(-14f);
        page.addView(heart);
        heart.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(200)
                .withEndAction(() -> heart.animate()
                        .scaleX(1.25f).scaleY(1.25f).alpha(0f)
                        .translationY(-AndroidUtilities.dp(50))
                        .setStartDelay(220)
                        .setDuration(280)
                        .withEndAction(() -> page.removeView(heart))
                        .start())
                .start();
    }

    private void openComments(FeedItem item) {
        if (item == null || item.chat == null) return;
        Bundle args = new Bundle();
        args.putLong("chat_id", item.channelId);
        args.putInt("message_id", item.messageId);
        try { presentFragment(new ChatActivity(args)); } catch (Exception e) { FileLog.e(e); }
    }

    // Instagram-style comment panel over the reel (the reel keeps playing behind it).
    private void openCommentsSheet(final FeedItem item) {
        if (item == null || item.mo == null || getContext() == null) return;
        try {
            SvipeReelsCommentsSheet sheet = new SvipeReelsCommentsSheet(getContext(), currentAccount, item.chat, item.messageId, item.mo);
            sheet.setListener(newCount -> {
                if (item.mo != null && item.mo.messageOwner != null && item.mo.messageOwner.replies != null) {
                    item.mo.messageOwner.replies.replies = newCount;
                }
                ReelsHolder h = holderAt(currentPosition);
                if (h != null && itemFor(h) == item) {
                    h.setCommentCount(item.mo != null ? item.mo.getRepliesCount() : newCount);
                }
            });
            sheet.show();
        } catch (Exception e) { FileLog.e(e); }
    }

    private void share(FeedItem item) {
        if (item == null || getParentActivity() == null) return;
        String link = (item.shareUrl != null && !item.shareUrl.isEmpty())
                ? item.shareUrl
                : (item.username != null && !item.username.isEmpty()
                        ? "https://t.me/" + item.username + "/" + item.messageId : null);
        if (link == null) return;
        // Caption that rides UNDER the shared video: promo line, one blank line, then the bare URL
        // (no scheme — Telegram still auto-links svipe.uz/<code>).
        final String caption = "watch reels on telegram with svipe\n\n" + link.replaceFirst("^https?://", "");
        try {
            TLRPC.Document d = item.mo != null ? item.mo.getDocument() : null;
            if (d instanceof TLRPC.TL_document) {
                // Send the ACTUAL video as a clean media message (no "forwarded from" header, no
                // original caption) carrying OUR caption — the recipient watches it in Telegram and
                // gets the install link. We keep ShareAlert's familiar picker but override the send to
                // a document send (parentObject = the post, so the file_reference can be re-fetched).
                final TLRPC.TL_document document = (TLRPC.TL_document) d;
                final MessageObject parent = item.mo;
                ArrayList<MessageObject> messages = new ArrayList<>();
                messages.add(item.mo);
                ShareAlert alert = new ShareAlert(getParentActivity(), messages, null, false, null, false) {
                    @Override
                    protected void sendInternal(boolean withSound) {
                        for (int a = 0; a < selectedDialogs.size(); a++) {
                            long key = selectedDialogs.keyAt(a);
                            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                                    document, null, null, key, null, null, caption, null, null, null,
                                    withSound, 0, 0, 0, parent, null, false);
                            SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
                        }
                        dismiss();
                    }
                };
                showDialog(alert);
            } else {
                // No resolved video yet — fall back to sharing the caption (promo text + link) only.
                ShareAlert alert = new ShareAlert(getParentActivity(), null, caption, false, link, false);
                showDialog(alert);
            }
            sendEvent("SHARE", item); // share intent, not confirmed delivery — good enough a signal
        } catch (Exception e) { FileLog.e(e); }
    }

    private void showMore(FeedItem item, ReelsHolder h) {
        if (item == null || getParentActivity() == null || h == null || h.moreIcon == null) return;
        // Telegram-style popup menu (an icon + label per row) anchored to the "more" (⋮) rail button —
        // same look as the chat/audio-player overflow menus, via ItemOptions.
        ItemOptions.makeOptions(this, h.moreIcon)
                .setGravity(Gravity.RIGHT)
                .add(R.drawable.msg_share, "Ulashish", () -> share(item))
                .add(R.drawable.msg_copy, getString(R.string.CopyLink), () -> copyLink(item))
                .add(R.drawable.msg_channel, getString(R.string.SvipeReelsGoToChannel), () -> openComments(item))
                .add(R.drawable.msg2_block2, getString(R.string.SvipeReelsNotInterested), () -> {
                    sendEvent("NOT_INTERESTED", item);
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, getString(R.string.SvipeReelsLessLikeThis)).show();
                })
                .add(R.drawable.msg_disable, getString(R.string.SvipeReelsBlockChannel), () -> blockChannel(item, h))
                .add(R.drawable.msg_report, "Shikoyat", true, () -> reportMessage(item))
                .show();
    }

    private void reportMessage(FeedItem item) {
        if (item == null || item.mo == null || getParentActivity() == null) return;
        try {
            // Telegram 12.9.0 made ReportBottomSheet.open(...) private and routes reporting through
            // the public openMessage(...) entry point; feed the reel's already-resolved MessageObject.
            ReportBottomSheet.openMessage(this, item.mo);
        } catch (Exception e) { FileLog.e(e); }
    }

    private void copyLink(FeedItem item) {
        if (item == null || getParentActivity() == null) return;
        // Prefer the owned, attributable svipe.uz preview link that arrived with the feed; fall back to
        // the raw t.me post only if the backend didn't supply one.
        String link = (item.shareUrl != null && !item.shareUrl.isEmpty())
                ? item.shareUrl
                : "https://t.me/" + item.username + "/" + item.messageId;
        try {
            ClipboardManager cm = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("link", link));
            BulletinFactory.of(this).createCopyLinkBulletin().show();
        } catch (Exception e) { FileLog.e(e); }
    }

    /**
     * Block a channel: drop all its reels from the feed right now + filter it from this session's
     * loads (the backend BLOCK_CHANNEL event makes it durable). A Telegram-native undo snackbar
     * restores everything if tapped; the event is only sent on commit (when the snackbar times out).
     */
    private void blockChannel(FeedItem item, ReelsHolder h) {
        if (item == null || item.channelId == 0 || getParentActivity() == null) return;
        final long channelId = item.channelId;
        final ArrayList<FeedItem> snapshot = new ArrayList<>(items);
        final int snapshotPos = currentPosition;

        blockedChannels.add(channelId);
        if (svipeBlockedChannels != null) svipeBlockedChannels.add(channelId); // persist so the block survives restarts + shows in the management screen
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).channelId == channelId) items.remove(i);
        }
        adapter.notifyDataSetChanged();
        if (items.isEmpty()) {
            releaseCurrentPlayer();
            setStatus(getString(R.string.SvipeReelsEmpty));
            loadMore();
        } else {
            final int newPos = Math.min(Math.max(currentPosition, 0), items.size() - 1);
            currentPosition = -1; // force checkCurrentPage to (re)start whatever reel now sits at this slot
            layoutManager.scrollToPosition(newPos);
            AndroidUtilities.runOnUIThread(this::checkCurrentPage, 120);
        }

        BulletinFactory.of(this).createUndoBulletin(
                getString(R.string.SvipeReelsChannelBlocked),
                () -> { // undo — restore the feed exactly as it was
                    blockedChannels.remove(channelId);
                    if (svipeBlockedChannels != null) svipeBlockedChannels.remove(channelId); // undo the persistent block too
                    items.clear();
                    items.addAll(snapshot);
                    adapter.notifyDataSetChanged();
                    currentPosition = -1;
                    layoutManager.scrollToPosition(Math.min(Math.max(snapshotPos, 0), Math.max(items.size() - 1, 0)));
                    AndroidUtilities.runOnUIThread(this::checkCurrentPage, 120);
                },
                () -> sendEvent("BLOCK_CHANNEL", item) // commit — tell the backend to stop recommending it
        ).show();
    }

    private void toggleFollow(FeedItem item, ReelsHolder holder) {
        if (item == null || item.chat == null) return;
        TLRPC.User self = MessagesController.getInstance(account).getUser(UserConfig.getInstance(account).getClientUserId());
        if (ChatObject.isInChat(item.chat)) {
            MessagesController.getInstance(account).deleteParticipantFromChat(item.channelId, self);
            item.chat.left = true;
            sendEvent("UNFOLLOW", item);
        } else {
            MessagesController.getInstance(account).addUserToChat(item.channelId, self, 0, null, this, null);
            item.chat.left = false;
            sendEvent("FOLLOW", item);
        }
        if (holder != null) holder.setFollowing(!item.chat.left);
    }

    private void updateActions(int pos) {
        ReelsHolder h = holderAt(pos);
        if (h == null || pos < 0 || pos >= items.size()) return;
        FeedItem item = items.get(pos);
        if (item.chat != null) {
            h.avatar.setForUserOrChat(item.chat, new AvatarDrawable(item.chat));
            h.channelName.setText(item.chat.title != null ? item.chat.title : ("@" + item.username));
            h.setVerified(item.chat.verified);
            h.setFollowing(ChatObject.isInChat(item.chat));
        }
        if (item.mo != null) {
            h.setLikeCount(item.likeCount);
            h.setLiked(item.liked);
            h.setCommentCount(item.mo.getRepliesCount());
            h.setShareCount(item.mo.messageOwner.forwards);
            h.setTitle(captionOf(item.mo));
            if (h.cover.getVisibility() != View.VISIBLE) {
                // bound before the resolve finished — backfill the thumbnail
                h.setCover(item.mo);
            }
        }
        // The chat/mo just enriched for the current reel — (re)resolve the comment thread and apply
        // the enabled/disabled state of the bottom input bar now that we know it.
        if (pos == currentPosition) {
            refreshCommentTargetForCurrent();
        }
    }

    /**
     * Stuck-reel diagnostics over the existing telemetry pipe. PLAY_FAILED is already whitelisted
     * server-side but deliberately inert (not an exposure event, neutral for the recommender), so
     * it can carry arbitrary payload fields (backend stores payload JSONB verbatim). Nothing shows
     * it in the app — it exists to be queried from the prod video_event table while chasing the
     * real-device "reel stuck on spinner" bug. Kinds: auto_recovery / recovered / manual_rescue.
     */
    private void sendDiag(FeedItem item, String kind, JSONObject extra) {
        try {
            JSONObject p = extra != null ? extra : new JSONObject();
            p.put("diag", kind);
            // 'net' alone conflates no-connectivity/metered-WiFi with mobile — read it WITH
            // 'online' (ConnectivityManager truth) and 'conn' (MTProto connection state).
            p.put("net", ApplicationLoader.getAutodownloadNetworkType());
            p.put("online", ApplicationLoader.isNetworkOnline());
            p.put("conn", ConnectionsManager.getInstance(account).getConnectionState());
            sendEvent("PLAY_FAILED", item, p);
        } catch (Exception ignore) {}
    }

    private void sendEvent(String type, FeedItem item) {
        sendEvent(type, item, null);
    }

    private void sendEvent(String type, FeedItem item, JSONObject payload) {
        if (item == null) return;
        try {
            JSONObject ev = new JSONObject();
            ev.put("channel_id", item.channelId);
            ev.put("message_id", item.messageId);
            ev.put("event_type", type);
            // The item's own page id, not the latest one — endless paging would misattribute.
            if (item.recId != null) ev.put("recommendation_id", item.recId);
            if (payload != null) ev.put("payload", payload);
            JSONArray a = new JSONArray();
            a.put(ev);
            JSONObject batch = new JSONObject();
            batch.put("events", a);
            postEvents(batch, false);
        } catch (Exception ignore) {}
    }

    /** POST with one silent re-auth retry on 401: watch signals must survive token expiry. */
    private void postEvents(JSONObject batch, boolean retried) {
        if (token == null) return;
        SvipeApi.post("/v1/events", batch, token, (r, c, e) -> {
            if (c == 401 && !retried) {
                SvipeAuth.invalidateAccessToken(account);
                SvipeAuth.ensureToken(account, t -> {
                    if (t != null) {
                        token = t;
                        postEvents(batch, true);
                    }
                });
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (currentPlayer != null && !userPaused) {
            currentPlayer.play();
            watchStartMs = System.currentTimeMillis();
        }
        if (reelEnterView != null) reelEnterView.onResume();
        if (root != null) root.onResume();
        startPositionUpdates();
        // onPause killed the stall watchdog + start checker; re-arm them. The watchdog now covers
        // rendering reels too (PLAY phase, mid-play starvation), so it re-arms unconditionally;
        // the checker only matters until the first frame. scheduleStuckWatchdog resets the arm
        // time, so returning to a long-buffering reel gets a fresh deadline, not an instant fire.
        if (currentPosition >= 0 && currentPosition < items.size()) {
            if (currentPlayer != null) {
                scheduleStuckWatchdog(currentPosition, currentPlayer);
            }
            if (!currentReelFirstFrame) {
                schedulePlaybackStartChecker(currentPosition);
            }
        }
        Bulletin.addDelegate(this, bulletinDelegate);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (currentPlayer != null) currentPlayer.pause();
        pauseWatchClock();
        if (reelEnterView != null) {
            reelEnterView.onPause();
            reelEnterView.closeKeyboard();
        }
        if (root != null) root.onPause();
        stopPositionUpdates();
        Bulletin.removeDelegate(this);
    }

    /**
     * MainTabsActivity blocks tab swiping unless the visible fragment opts in via
     * TabFragmentDelegate — without this, a horizontal swipe on Reels went nowhere.
     * Vertical reel paging is unaffected: the pager only claims horizontal-dominant drags.
     */
    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        // Close the emoji/sticker popup first, then the keyboard, before leaving the player.
        if (reelEnterView != null) {
            if (reelEnterView.isPopupShowing()) {
                if (invoked) reelEnterView.hidePopup(true, false);
                return false;
            }
            if (reelEnterView.isKeyboardVisible()) {
                if (invoked) reelEnterView.closeKeyboard();
                return false;
            }
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didUpdateConnectionState);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoadFailed);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.didUpdateConnectionState);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadFailed);
        flushWatchEvent(currentPosition);
        if (reelQueue != null) reelQueue.persist();
        if (reelEnterView != null) {
            try { reelEnterView.onDestroy(); } catch (Exception ignore) {}
            reelEnterView = null;
        }
        stopPositionUpdates();
        positionUpdateHandler = null;
        updateProgressRunnable = null;
        releaseCurrentPlayer();
        releaseNextPlayer();
        // Cancel the CURRENT reel's stream rungs (their high-priority pull is wasteful once the
        // screen is gone) — but not an in-flight offline-queue download of this reel. The LOW-priority
        // background full-downloads for the ahead window are deliberately left running so they finish
        // and persist; any that complete are picked up on next cold start by the validate-on-load check.
        if (currentPosition >= 0 && currentPosition < items.size()) {
            cancelReelStreams(items.get(currentPosition));
        }
        super.onFragmentDestroy();
    }

    // ==== video progress / scrub bar ====

    /** Continuous (~30fps) poll that drives the seek bar from the current player; skips while scrubbing. */
    private void startPositionUpdates() {
        if (positionUpdateHandler == null) {
            positionUpdateHandler = new Handler(Looper.getMainLooper());
        }
        if (updateProgressRunnable == null) {
            updateProgressRunnable = () -> {
                if (seekBar != null && currentPlayer != null && !seekBar.dragging) {
                    final long dur = currentPlayer.getDuration();
                    if (dur > 0) {
                        // Show the scrub bar only once we know the clip is long enough to be worth scrubbing.
                        seekBar.setVisibility(dur < MIN_SEEKBAR_DURATION_MS ? View.GONE : View.VISIBLE);
                        seekBar.setProgress((float) currentPlayer.getCurrentPosition() / dur);
                    }
                }
                if (positionUpdateHandler != null) {
                    positionUpdateHandler.postDelayed(updateProgressRunnable, 32);
                }
            };
        }
        positionUpdateHandler.removeCallbacks(updateProgressRunnable);
        positionUpdateHandler.post(updateProgressRunnable);
    }

    private void stopPositionUpdates() {
        if (positionUpdateHandler != null && updateProgressRunnable != null) {
            positionUpdateHandler.removeCallbacks(updateProgressRunnable);
        }
        // Kill the stall watchdog + start checker too, so a stuck reel can't auto-rebuild + resume
        // audio while the fragment is paused/backgrounded.
        cancelStuckWatchdog();
        if (playbackStartChecker != null) {
            AndroidUtilities.cancelRunOnUIThread(playbackStartChecker);
        }
    }

    /**
     * Thin always-on progress line over the video, draggable to seek the current reel. No time text.
     * Drawn on a tall-enough hit strip; the right action-rail column is left untouched so its buttons
     * keep working, and a horizontal scrub asks the parent tab pager not to steal the gesture.
     */
    private class SeekBarView extends View {
        float progress;          // 0..1 playback fraction (read by the poller)
        boolean dragging;        // poller skips updates while true
        private float dragFraction;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SeekBarView(Context c) {
            super(c);
            trackPaint.setColor(0x40FFFFFF);
            playedPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            thumbPaint.setColor(0xFFFFFFFF);
        }

        void setProgress(float p) {
            if (!dragging) {
                progress = Math.max(0f, Math.min(1f, p));
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth();
            final float cy = getHeight() / 2f;
            final float half = AndroidUtilities.dp(dragging ? 2.5f : 1.25f);
            final float frac = dragging ? dragFraction : progress;
            final float playedW = w * frac;
            canvas.drawRoundRect(0, cy - half, w, cy + half, half, half, trackPaint);
            if (playedW > 0) {
                canvas.drawRoundRect(0, cy - half, playedW, cy + half, half, half, playedPaint);
            }
            if (dragging) {
                canvas.drawCircle(playedW, cy, AndroidUtilities.dp(7), thumbPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            final int w = getWidth();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Leave the right rail column (and a zero-width pre-layout view) to the list below.
                    if (w <= 0 || e.getX() > w - AndroidUtilities.dp(62)) {
                        return false;
                    }
                    dragging = true;
                    dragFraction = clamp01(e.getX() / w);
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        dragFraction = clamp01(e.getX() / (float) w);
                        invalidate();
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        dragging = false;
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        if (e.getActionMasked() == MotionEvent.ACTION_UP && currentPlayer != null) {
                            final long dur = currentPlayer.getDuration();
                            if (dur > 0) {
                                progress = dragFraction;
                                currentPlayer.seekTo((long) (dragFraction * dur));
                            }
                        }
                        invalidate();
                        return true;
                    }
                    return false;
            }
            return false;
        }

        private float clamp01(float v) {
            return Math.max(0f, Math.min(1f, v));
        }
    }

    @Override
    public void didReceivedNotification(int id, int notificationAccount, Object... args) {
        if (id == NotificationCenter.didUpdateConnectionState) {
            int state = ConnectionsManager.getInstance(account).getConnectionState();
            if (SvipeFeedRetry.shouldRetry(state, feedLoadFailed, loadingFeed)) {
                if (items.isEmpty()) {
                    loadFeed();
                } else {
                    loadMore(); // an append failed offline — finish it without resetting the pager
                }
            }
            // A current reel whose resolve failed while offline recovers the moment the link returns.
            if (state == ConnectionsManager.ConnectionStateConnected
                    && currentPosition >= 0 && currentPosition < items.size()) {
                FeedItem cur = items.get(currentPosition);
                if (cur.mo == null && !cur.resolving && currentPlayer == null) {
                    cur.resolveAttempts = 0; // fresh retry budget now that we are back online
                    resolveAndPlay(currentPosition);
                }
            }
        } else if (id == NotificationCenter.fileLoaded) {
            String fileName = args.length > 0 && args[0] instanceof String ? (String) args[0] : null;
            FeedItem it = fileName != null ? fileNameToItem.remove(fileName) : null;
            if (it != null) {
                // Clean up by the doc the download actually targeted (a rendition under HLS —
                // mo.getDocument() would be the top rung and miss).
                long id2 = it.downloadDocId != 0 ? it.downloadDocId
                        : (it.mo != null && it.mo.getDocument() != null ? it.mo.getDocument().id : 0);
                if (id2 != 0) fullDownloadStarted.remove(id2); // completed — allow re-download if later evicted
                it.downloadDocId = 0;
                enqueueResolved(it, true); // serialize + mark downloaded + persist
                if (currentPosition >= 0) ensureFullDownloadsAhead(currentPosition); // a slot filled — top up
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            String fileName = args.length > 0 && args[0] instanceof String ? (String) args[0] : null;
            FeedItem it = fileName != null ? fileNameToItem.remove(fileName) : null;
            if (it != null) {
                long id2 = it.downloadDocId != 0 ? it.downloadDocId
                        : (it.mo != null && it.mo.getDocument() != null ? it.mo.getDocument().id : 0);
                if (id2 != 0) fullDownloadStarted.remove(id2); // allow a future retry
                it.downloadDocId = 0;
            }
        }
    }

    private FeedItem itemFor(ReelsHolder h) {
        int pos = h.getAdapterPosition();
        return pos >= 0 && pos < items.size() ? items.get(pos) : null;
    }

    // ---------------- holder / adapter ----------------

    private static class ReelsHolder extends RecyclerView.ViewHolder {
        final AspectRatioFrameLayout aspect;
        final TextureView textureView;
        final BackupImageView cover; // video thumbnail shown until the first frame renders
        final ProgressBar loading;
        final ImageView pausedIcon;
        final ImageView likeIcon;
        final TextView likeCount;
        final ImageView commentIcon;
        final TextView commentCount;
        final TextView shareCount;
        final BackupImageView avatar;
        final TextView channelName;
        final ImageView verifiedBadge;
        final TextView followBtn;
        final TextView title;
        boolean titleExpanded;
        View actionRail;   // right column (like/comment/share/more) — taps here must NOT toggle pause
        View infoBox;      // channel row + caption — taps here must NOT toggle pause
        ImageView shareIcon, moreIcon;  // not in the ctor — needed for list-level tap dispatch

        ReelsHolder(FrameLayout root, AspectRatioFrameLayout aspect, TextureView tv, BackupImageView cover, ProgressBar pb,
                    ImageView paused, ImageView likeIcon, TextView likeCount, ImageView commentIcon,
                    TextView commentCount, TextView shareCount, BackupImageView avatar, TextView channelName,
                    ImageView verifiedBadge, TextView followBtn, TextView title) {
            super(root);
            this.aspect = aspect;
            this.textureView = tv;
            this.cover = cover;
            this.loading = pb;
            this.pausedIcon = paused;
            this.likeIcon = likeIcon;
            this.likeCount = likeCount;
            this.commentIcon = commentIcon;
            this.commentCount = commentCount;
            this.shareCount = shareCount;
            this.avatar = avatar;
            this.channelName = channelName;
            this.verifiedBadge = verifiedBadge;
            this.followBtn = followBtn;
            this.title = title;
        }

        void setShareCount(int n) { shareCount.setText(n > 0 ? String.valueOf(n) : getString(R.string.SvipeReelsShare)); }

        void setVerified(boolean verified) {
            verifiedBadge.setVisibility(verified ? View.VISIBLE : View.GONE);
        }

        void setTitle(CharSequence text) {
            if (text == null || text.length() == 0) {
                title.setVisibility(View.GONE);
            } else {
                title.setVisibility(View.VISIBLE);
                title.setText(text);
            }
        }

        void setCover(MessageObject mo) {
            TLRPC.Document doc = mo != null ? mo.getDocument() : null;
            TLRPC.PhotoSize thumb = doc != null ? FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 320) : null;
            if (thumb != null) {
                cover.setImage(ImageLocation.getForDocument(thumb, doc), "360_640", null, null, mo);
                cover.setVisibility(View.VISIBLE);
            } else {
                cover.setImageDrawable(null);
                cover.setVisibility(View.GONE);
            }
        }

        void hideCover() { cover.setVisibility(View.GONE); }

        void showLoading(boolean show) { loading.setVisibility(show ? View.VISIBLE : View.GONE); }
        void setPaused(boolean paused) { pausedIcon.setVisibility(paused ? View.VISIBLE : View.GONE); }
        void setLikeCount(int n) { likeCount.setText(n > 0 ? String.valueOf(n) : getString(R.string.SvipeReelsLike)); }
        void setLiked(boolean liked) {
            likeIcon.setImageResource(liked ? R.drawable.media_like_active : R.drawable.media_like);
            likeIcon.setColorFilter(liked ? 0xFFFF2E38 : 0xFFFFFFFF);
        }
        void setCommentCount(int n) {
            commentCount.setText(n > 0 ? String.valueOf(n) : getString(R.string.SvipeReelsComment));
            commentIcon.setAlpha(n > 0 ? 1f : 0.4f);
            commentCount.setAlpha(n > 0 ? 1f : 0.4f);
        }
        void setFollowing(boolean following) {
            followBtn.setText(getString(following ? R.string.SvipeReelsSubscribed : R.string.SvipeReelsSubscribe));
            followBtn.setVisibility(following ? View.GONE : View.VISIBLE);
        }
    }

    private class ReelsAdapter extends RecyclerListView.SelectionAdapter {
        private final Context ctx;

        ReelsAdapter(Context context) { ctx = context; }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) { return false; }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout page = new FrameLayout(ctx);
            page.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            page.setBackgroundColor(0xFF000000);

            // Thumbnail behind the video: visible from the instant the page appears until the
            // player renders its first frame (the TextureView is transparent until then).
            BackupImageView cover = new BackupImageView(ctx);
            cover.getImageReceiver().setAspectFit(true);
            page.addView(cover, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

            // Fit-center video.
            AspectRatioFrameLayout aspect = new AspectRatioFrameLayout(ctx);
            aspect.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            TextureView tv = new TextureView(ctx);
            aspect.addView(tv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            page.addView(aspect, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

            View gradient = new View(ctx);
            gradient.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xDD000000, 0x00000000}));
            FrameLayout.LayoutParams gradientLp = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.BOTTOM);
            gradientLp.height = bottomInset + AndroidUtilities.dp(240);
            page.addView(gradient, gradientLp);

            ProgressBar pb = new ProgressBar(ctx);
            page.addView(pb, LayoutHelper.createFrame(46, 46, Gravity.CENTER));

            // Center pause indicator = Telegram's own video-player play button, 1:1: the exact
            // PlayPauseDrawable (Theme.playPauseAnimator morph) inside circle_big — identical to
            // PhotoViewer's playDrawable. That animator is built only by createChatResources (when a
            // chat opens), so on a cold reels start it can be null and the glyph renders empty (a bare
            // circle) — initialise it here so the play triangle always shows.
            if (Theme.playPauseAnimator == null) {
                Theme.createChatResources(ctx, false);
            }
            ImageView paused = new ImageView(ctx);
            PlayPauseDrawable pausedGlyph = new PlayPauseDrawable(28);
            pausedGlyph.setPause(false, false); // static play triangle — "tap to resume"
            android.graphics.drawable.Drawable pauseCircle = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.circle_big);
            CombinedDrawable pausedDrawable = new CombinedDrawable(pauseCircle != null ? pauseCircle.mutate() : null, pausedGlyph);
            pausedDrawable.setCustomSize(AndroidUtilities.dp(64), AndroidUtilities.dp(64));
            pausedDrawable.setIconSize(AndroidUtilities.dp(28), AndroidUtilities.dp(28));
            paused.setImageDrawable(pausedDrawable);
            paused.setVisibility(View.GONE);
            page.addView(paused, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

            // Right action rail.
            LinearLayout rail = new LinearLayout(ctx);
            rail.setOrientation(LinearLayout.VERTICAL);
            rail.setGravity(Gravity.CENTER_HORIZONTAL);

            // Rail icons use a 48dp touch target (icon kept visually ~28-34dp via padding) so they
            // are reliably tappable over the play/pause video surface.
            ImageView likeIcon = new ImageView(ctx);
            likeIcon.setImageResource(R.drawable.media_like);
            likeIcon.setColorFilter(0xFFFFFFFF);
            likeIcon.setPadding(AndroidUtilities.dp(7), AndroidUtilities.dp(7), AndroidUtilities.dp(7), AndroidUtilities.dp(7));
            rail.addView(likeIcon, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));
            TextView likeCount = railLabel(ctx, "Like");
            rail.addView(likeCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

            ImageView commentIcon = new ImageView(ctx);
            commentIcon.setImageResource(R.drawable.menu_comments);
            commentIcon.setColorFilter(0xFFFFFFFF);
            commentIcon.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
            rail.addView(commentIcon, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));
            TextView commentCount = railLabel(ctx, "Izoh");
            rail.addView(commentCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

            ImageView shareIcon = new ImageView(ctx);
            shareIcon.setImageResource(R.drawable.media_share);
            shareIcon.setColorFilter(0xFFFFFFFF);
            shareIcon.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
            rail.addView(shareIcon, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));
            TextView shareCount = railLabel(ctx, "Ulashish");
            rail.addView(shareCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

            ImageView moreIcon = new ImageView(ctx);
            moreIcon.setImageResource(R.drawable.msg_actions);
            moreIcon.setColorFilter(0xFFFFFFFF);
            moreIcon.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
            rail.addView(moreIcon, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));

            FrameLayout.LayoutParams railLp = LayoutHelper.createFrame(56, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 6, 0);
            // Lifted to leave a row for the scrub bar between this cluster and the tab bar.
            railLp.bottomMargin = bottomInset + AndroidUtilities.dp(30);
            page.addView(rail, railLp);

            // Bottom channel bar.
            LinearLayout channelBar = new LinearLayout(ctx);
            channelBar.setOrientation(LinearLayout.HORIZONTAL);
            channelBar.setGravity(Gravity.CENTER_VERTICAL);

            BackupImageView avatar = new BackupImageView(ctx);
            avatar.setRoundRadius(AndroidUtilities.dp(18));
            channelBar.addView(avatar, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

            TextView channelName = new TextView(ctx);
            channelName.setTextColor(0xFFFFFFFF);
            channelName.setTextSize(15);
            channelName.setSingleLine(true);
            channelName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // Cap the name to the space left after avatar/badge/Obuna so a long name ellipsizes
            // instead of pushing the button onto a second line.
            channelName.setMaxWidth(Math.max(AndroidUtilities.dp(90), AndroidUtilities.displaySize.x - AndroidUtilities.dp(230)));
            channelName.setShadowLayer(AndroidUtilities.dp(3), 0, AndroidUtilities.dp(1), 0x90000000);
            channelBar.addView(channelName, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

            // Verified badge (blue area + white check), hidden unless the channel is verified.
            ImageView verifiedBadge = new ImageView(ctx);
            try {
                android.graphics.drawable.Drawable area = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.verified_area).mutate();
                android.graphics.drawable.Drawable check = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.verified_check).mutate();
                area.setColorFilter(0xFF55ACEE, android.graphics.PorterDuff.Mode.SRC_IN);
                check.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
                verifiedBadge.setImageDrawable(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{area, check}));
            } catch (Exception e) { FileLog.e(e); }
            verifiedBadge.setVisibility(View.GONE);
            channelBar.addView(verifiedBadge, LayoutHelper.createLinear(17, 17, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

            TextView followBtn = new TextView(ctx);
            followBtn.setText(getString(R.string.SvipeReelsSubscribe));
            followBtn.setTextColor(0xFFFFFFFF);
            followBtn.setTextSize(13);
            followBtn.setSingleLine(true); // never wrap to a 2nd line
            followBtn.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(6));
            GradientDrawable followBg = new GradientDrawable();
            followBg.setCornerRadius(AndroidUtilities.dp(16));
            followBg.setColor(0xFF2F6DF6);
            followBtn.setBackground(followBg);
            channelBar.addView(followBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            // Video title (caption) under the channel bar — 2 lines, tap to expand (native-player style).
            TextView title = new TextView(ctx);
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(13);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setLineSpacing(AndroidUtilities.dp(1), 1f);
            title.setShadowLayer(AndroidUtilities.dp(3), 0, AndroidUtilities.dp(1), 0x90000000);
            title.setVisibility(View.GONE);

            LinearLayout bottomBox = new LinearLayout(ctx);
            bottomBox.setOrientation(LinearLayout.VERTICAL);
            bottomBox.addView(channelBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
            bottomBox.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 8, 0, 0));

            FrameLayout.LayoutParams bottomLp = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 14, 0, 64, 0);
            // Lifted to leave a row for the scrub bar between the caption and the tab bar.
            bottomLp.bottomMargin = bottomInset + AndroidUtilities.dp(30);
            page.addView(bottomBox, bottomLp);

            ReelsHolder holder = new ReelsHolder(page, aspect, tv, cover, pb, paused, likeIcon, likeCount,
                    commentIcon, commentCount, shareCount, avatar, channelName, verifiedBadge, followBtn, title);
            holder.actionRail = rail;
            holder.infoBox = bottomBox;
            holder.shareIcon = shareIcon;
            holder.moreIcon = moreIcon;
            // NOTE: control taps (rail buttons, follow, channel, caption) are dispatched at the LIST
            // level in dispatchControlTap — NOT via per-child click listeners. RecyclerView sends a
            // child an ACTION_CANCEL the instant it claims the touch for vertical paging, so a child's
            // onClick is unreliable (the share button "didn't press well"). The list-level gesture
            // detector observes every tap reliably — the same path that drives play/pause + double-tap.

            return holder;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ReelsHolder h = (ReelsHolder) holder;
            resetHolderZoom(h); // a recycled holder must never start mid-pinch-zoom
            FeedItem item = items.get(position);
            h.showLoading(true);
            h.setPaused(false);
            h.setCover(item.mo); // visible even while the page is only peeking in mid-swipe
            // A recycled TextureView still shows the previous reel's frozen frame — hide it until
            // this reel's own first frame arrives (the cover thumbnail shows through instead).
            h.textureView.setAlpha(0f);
            h.channelName.setText("@" + item.username);
            h.setLikeCount(item.mo != null ? item.likeCount : 0);
            h.setLiked(item.liked);
            h.setCommentCount(item.mo != null ? item.mo.getRepliesCount() : 0);
            h.setShareCount(item.mo != null ? item.mo.messageOwner.forwards : 0);
            h.titleExpanded = false;
            h.title.setMaxLines(2);
            h.setTitle(item.mo != null ? captionOf(item.mo) : null);
            if (item.chat != null) {
                h.avatar.setForUserOrChat(item.chat, new AvatarDrawable(item.chat));
                h.channelName.setText(item.chat.title != null ? item.chat.title : ("@" + item.username));
                h.setVerified(item.chat.verified);
                h.setFollowing(ChatObject.isInChat(item.chat));
            } else {
                AvatarDrawable ad = new AvatarDrawable();
                ad.setInfo(0, item.username, null);
                h.avatar.setImageDrawable(ad);
                h.setVerified(false);
                h.setFollowing(false);
            }
        }

        @Override
        public int getItemCount() { return items.size(); }
    }

    private static TextView railLabel(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(12);
        t.setShadowLayer(AndroidUtilities.dp(3), 0, AndroidUtilities.dp(1), 0x90000000);
        return t;
    }
}
