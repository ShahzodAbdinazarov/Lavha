package org.telegram.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Base64;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
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
import org.telegram.svipe.SvipeFeedRetry;
import org.telegram.svipe.SvipePreloadPlan;
import org.telegram.svipe.SvipeQueuePlan;
import org.telegram.svipe.SvipeReelQueue;
import org.telegram.svipe.SvipeWatchedSet;
import org.telegram.svipe.SvipeWatchEvent;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileStreamLoadOperation;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;
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

    private FrameLayout root;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private ReelsAdapter adapter;
    private TextView statusView;

    private final ArrayList<FeedItem> items = new ArrayList<>();
    private String recommendationId;
    private String token;
    private boolean loadingFeed;
    private boolean feedLoadFailed; // auto-retried via didUpdateConnectionState when network returns
    private boolean feedExhausted;  // append returned nothing new; reset on a fresh load

    // Watch clock for the CURRENT reel: dwell since shown, play time accumulated across
    // pause/resume. Flushed into REPLAY/VIDEO_END/SWIPE_AWAY when the user leaves the reel.
    private long itemShownMs;
    private long watchStartMs;
    private long watchedAccumMs;

    private VideoPlayer currentPlayer;
    private int currentPosition = -1;
    private boolean userPaused;
    private int bottomInset;

    // Stories trick: the next reel's player is created in advance and buffers PAUSED at LOW
    // priority; the swipe just attaches it to the texture — no setup, no buffer ramp-up.
    private VideoPlayer nextPlayer;
    private int nextPlayerPos = -1;
    private long playRequestMs; // for the "svipe: first frame" timing log

    // Persistent offline ready-queue: play instantly from disk on open, refill in the background.
    private SvipeReelQueue reelQueue;
    private SvipeWatchedSet watchedSet;
    private boolean coldStartDone; // the instant (queue) path has run; feed loads now MERGE, not clear
    private final HashMap<String, FeedItem> fileNameToItem = new HashMap<>(); // full-download completion lookup
    private final HashSet<Long> fullDownloadStarted = new HashSet<>();        // docIds with a cacheType-0 load in flight

    private static class FeedItem {
        long channelId;
        int messageId;
        String username;
        Integer topicId;
        String recId;         // recommendation_id of the page this item arrived with
        MessageObject mo;     // filled after MTProto resolution
        TLRPC.Chat chat;
        boolean liked;        // local like state (authoritative for the UI)
        int likeCount;        // total reactions count, kept in sync locally
        boolean resolving;    // an MTProto resolve is in flight (prevents duplicate prefetch)
        boolean preloadStarted;                          // a head-preload was requested
        int preloadPriority = FileLoader.PRIORITY_LOW;   // set by prefetchAround before resolve
        boolean preloadBypassGate;                       // next-in-line skips the data-saving gate
        boolean fromQueue;                               // restored from the persisted offline queue
        boolean fullDownloadStarted;                     // a full (cacheType 0) download was requested
    }

    public ReelsActivity(Bundle args) {
        super(args);
    }

    /**
     * Build a reels player seeded with a discover/explore list, opened at the tapped reel.
     * The tapped item plays first; the rest of the grid follows (rotated), then the personalized
     * feed appends below it. Used by the Search section's Explore grid.
     */
    public static ReelsActivity ofDiscoverSeed(java.util.List<org.telegram.svipe.SvipeDiscover.Item> all, int start) {
        Bundle args = new Bundle();
        final int n = all == null ? 0 : all.size();
        if (n > 0) {
            if (start < 0 || start >= n) start = 0;
            long[] chans = new long[n];
            int[] msgs = new int[n];
            String[] users = new String[n];
            int[] topics = new int[n];
            for (int i = 0; i < n; i++) {
                org.telegram.svipe.SvipeDiscover.Item it = all.get((start + i) % n);
                chans[i] = it.channelId;
                msgs[i] = it.messageId;
                users[i] = it.username;
                topics[i] = it.topicId != null ? it.topicId : -1;
            }
            args.putLongArray("seed_channels", chans);
            args.putIntArray("seed_messages", msgs);
            args.putStringArray("seed_usernames", users);
            args.putIntArray("seed_topics", topics);
        }
        return new ReelsActivity(args);
    }

    /** Cold-start variant for the discover seed: populate the pager from args, then merge the feed. */
    private boolean playSeedIfPresent() {
        Bundle args = getArguments();
        if (args == null) return false;
        long[] chans = args.getLongArray("seed_channels");
        int[] msgs = args.getIntArray("seed_messages");
        String[] users = args.getStringArray("seed_usernames");
        if (chans == null || msgs == null || users == null || chans.length == 0) return false;
        int[] topics = args.getIntArray("seed_topics");
        boolean any = false;
        for (int i = 0; i < chans.length; i++) {
            if (users[i] == null || users[i].isEmpty()) continue;
            FeedItem it = new FeedItem();
            it.channelId = chans[i];
            it.messageId = msgs[i];
            it.username = users[i];
            it.topicId = (topics != null && i < topics.length && topics[i] >= 0) ? topics[i] : null;
            items.add(it);
            any = true;
        }
        if (!any) return false;
        // The tapped reels are now the pager head; further feed loads MERGE (never clear) below them.
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

        // Keep all overlay UI above the floating native bottom tab bar (height 72dp + nav bar).
        bottomInset = AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(88);

        root = new FrameLayout(context);
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
            public boolean onSingleTapConfirmed(MotionEvent e) {
                togglePlayPause();
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
        });
        listView.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                tapDetector.onTouchEvent(e);
                return false; // observe only — never intercept paging
            }
            @Override
            public void onTouchEvent(RecyclerView rv, MotionEvent e) {}
            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallow) {}
        });

        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    checkCurrentPage();
                }
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        statusView = new TextView(context);
        statusView.setTextColor(0xFFFFFFFF);
        statusView.setTextSize(15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setText("Yuklanmoqda…");
        root.addView(statusView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        fragmentView = root;
        reelQueue = new SvipeReelQueue(account);
        watchedSet = new SvipeWatchedSet(account);
        if (!playSeedIfPresent()) {
            restoreQueueThenPlay();
        }
        return fragmentView;
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
                if (watchedSet.isWatched(e.channelId, e.messageId)) { skipWatched++; continue; } // never re-show watched
                if (e.downloaded) downloadedUnwatched++;
                MessageObject mo = deserializeMessage(e.messageB64);
                if (mo == null || mo.getDocument() == null) { skipDeser++; continue; }
                // validate-on-load: drop entries whose cached file was evicted (keepMedia auto-delete).
                // Same flags (useFileDatabaseQueue=false) the player uses in VideoUri.of below, so a
                // "present" item is exactly one the player can actually play from disk offline.
                File f = FileLoader.getInstance(account).getPathToAttach(mo.getDocument(), null, false, false);
                if (f == null || !f.exists()) { skipNoFile++; continue; }
                FeedItem it = new FeedItem();
                it.channelId = e.channelId;
                it.messageId = e.messageId;
                it.username = e.username;
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
                    setStatus("Yuklanmoqda…"); // genuinely empty queue -> online fallback shows the spinner
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
        if (!append && !playing) setStatus("Kirilmoqda…");
        SvipeAuth.ensureToken(account, t -> {
            if (t == null) {
                loadingFeed = false;
                feedLoadFailed = true;
                if (!append && !playing) setStatus("Svipe'ga kirib bo'lmadi. Internet qaytsa o'zi qayta urinadi.");
                return;
            }
            token = t;
            if (!append && !playing) setStatus("Lenta yuklanmoqda…");
            SvipeApi.get("/v1/feed", token, (res, code, err) -> {
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
                                ? "Internet yo'q. Ulanish qaytishi bilan lenta yuklanadi…"
                                : "Lenta yuklanmadi (" + code + ")");
                    }
                    return;
                }
                String recId = res.isNull("recommendation_id") ? null : res.optString("recommendation_id", null);
                recommendationId = recId;
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
                        if (watchedSet != null && watchedSet.isWatched(channelId, messageId)) continue;
                        FeedItem it = new FeedItem();
                        it.channelId = channelId;
                        it.messageId = messageId;
                        it.username = username;
                        it.topicId = o.isNull("topic_id") ? null : o.optInt("topic_id");
                        it.recId = recId;
                        items.add(it);
                        added++;
                    }
                }
                if (additive) {
                    if (added == 0) {
                        feedExhausted = true; // stop asking until the next fresh load
                    } else {
                        adapter.notifyItemRangeInserted(before, added);
                        // A merge can newly satisfy the download-ahead target — top it up.
                        if (playing && currentPosition >= 0) ensureFullDownloadsAhead(currentPosition);
                    }
                } else {
                    setStatus(items.isEmpty() ? "Hozircha video yo'q" : null);
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
                if (currentPosition == fpos && item.mo != null) {
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
        if (item.resolving) return;
        // Queue-restored item: we already hold a playable MessageObject, only the chat is missing
        // (needed for the action rail). Fill it with one resolveUsername round-trip — skip getMessages.
        if (item.mo != null && item.chat == null) { resolveChatOnly(item, onResolved); return; }
        item.resolving = true;
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = item.username.toLowerCase();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                AndroidUtilities.runOnUIThread(() -> { item.resolving = false; hideLoadingFor(item); });
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
                AndroidUtilities.runOnUIThread(() -> { item.resolving = false; hideLoadingFor(item); });
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
                    AndroidUtilities.runOnUIThread(() -> { item.resolving = false; hideLoadingFor(item); });
                    return;
                }
                TLRPC.messages_Messages mm = (TLRPC.messages_Messages) resp2;
                MessagesController.getInstance(account).putUsers(mm.users, false);
                MessagesController.getInstance(account).putChats(mm.chats, false);
                if (mm.messages == null || mm.messages.isEmpty()) {
                    AndroidUtilities.runOnUIThread(() -> { item.resolving = false; hideLoadingFor(item); });
                    return;
                }
                final MessageObject mo = new MessageObject(account, mm.messages.get(0), false, true);
                TLRPC.Document doc = mo.getDocument();
                if (doc == null || !MessageObject.isVideoDocument(doc)) {
                    AndroidUtilities.runOnUIThread(() -> { item.resolving = false; hideLoadingFor(item); });
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    item.resolving = false;
                    item.mo = mo;
                    item.chat = fchat;
                    item.liked = isLiked(mo);
                    item.likeCount = totalReactions(mo);
                    preloadMedia(item);
                    if (onResolved != null) onResolved.run();
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

    /**
     * Read-ahead per {@link SvipePreloadPlan}: resolve + head-preload the ahead window (the rest at
     * LOW), and cancel any started preload that fell out of the window. The immediate next reel
     * (pos+1) is skipped here — {@link #ensureFullDownloadsAhead(int)} owns it with a FULL download.
     * Behind reels are never touched — their bytes stay in cache.
     */
    private void prefetchAround(int pos) {
        for (int i = pos + 1; i <= pos + PREFETCH_AHEAD && i < items.size(); i++) {
            if (i == pos + 1) continue; // full download supersedes head-preload for the next reel
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

    /** Is this item's full video file already present on disk (not just head-preloaded)? */
    private boolean fileFullyPresent(FeedItem it) {
        if (it == null || it.mo == null) return false;
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
    private void ensureFullDownloadsAhead(int pos) {
        if (reelQueue == null) return;
        int have = countDownloadedUnwatchedAhead(pos);
        for (int i = pos + 1; i < items.size() && SvipeQueuePlan.needsMoreDownloads(have); i++) {
            FeedItem it = items.get(i);
            if (watchedSet != null && watchedSet.isWatched(it.channelId, it.messageId)) continue;
            if (it.mo == null) {
                resolveItem(it, () -> ensureFullDownloadsAhead(currentPosition)); // retry once resolved
                continue;
            }
            TLRPC.Document doc = it.mo.getDocument();
            if (doc == null) continue;
            if (fileFullyPresent(it)) {
                enqueueResolved(it, true); // already on disk — make sure it's persisted
                have++;
                continue;
            }
            // Respect the disk budget; stop starting new downloads once we'd blow past it.
            if (!SvipeQueuePlan.withinByteBudget(reelQueue.totalBytes(), doc.size, SvipeQueuePlan.MAX_QUEUE_BYTES)
                    || reelQueue.size() >= SvipeQueuePlan.MAX_ENTRIES) {
                break;
            }
            if (!fullDownloadStarted.contains(doc.id)) {
                fullDownloadStarted.add(doc.id);
                it.fullDownloadStarted = true;
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
        e.topicId = it.topicId;
        e.recId = it.recId;
        TLRPC.Document doc = it.mo.getDocument();
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
            TLRPC.Document doc = item.mo.getDocument();
            if (doc == null) return;
            // A repost can share the same file as the current reel — never cancel that one.
            FeedItem cur = currentPosition >= 0 && currentPosition < items.size() ? items.get(currentPosition) : null;
            TLRPC.Document curDoc = cur != null && cur.mo != null ? cur.mo.getDocument() : null;
            if (curDoc != null && curDoc.id == doc.id) return;
            FileLoader.getInstance(account).cancelLoadFile(doc);
        } catch (Exception e) { FileLog.e(e); }
    }

    private void hideLoadingFor(FeedItem item) {
        int idx = items.indexOf(item);
        if (idx < 0) return;
        ReelsHolder h = holderAt(idx);
        if (h != null) h.showLoading(false);
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
                player = new VideoPlayer(false, false);
                player.setIsReels();
                player.setLooping(true);
            }
            // This reel owns the bandwidth now: stream reads at HIGH and the whole file keeps
            // pulling at HIGH so loops and seeks never stall (cancelled when swiped away).
            FileStreamLoadOperation.setPriorityForDocument(doc, FileLoader.PRIORITY_HIGH);
            if (prepared) {
                FileLoader.getInstance(account).loadFile(doc, mo, FileLoader.PRIORITY_HIGH, 0);
            }
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
                int reference = FileLoader.getInstance(account).getFileReference(mo);
                VideoPlayer.VideoUri vu = VideoPlayer.VideoUri.of(account, doc, null, reference, false);
                FileLog.d("svipe: play pos=" + pos + " source=" + (vu.isCached() ? "LOCAL-cache" : "network")
                        + " fromQueue=" + item.fromQueue);
                player.preparePlayer(vu.uri, "other");
            }
            player.setPlayWhenReady(true);
            player.play();
            currentPlayer = player;
            watchStartMs = System.currentTimeMillis();
        } catch (Exception e) {
            FileLog.e(e);
            holder.showLoading(false);
        }
    }

    /** Build the next reel's player ahead of time: prepared, paused, buffering at LOW priority. */
    private void prepareNextPlayer(int pos) {
        releaseNextPlayer();
        if (!SvipePreloadPlan.shouldPrepareNextPlayer(SharedConfig.deviceIsHigh(), pos, items.size())) return;
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
            int reference = FileLoader.getInstance(account).getFileReference(item.mo);
            VideoPlayer.VideoUri vu = VideoPlayer.VideoUri.of(account, doc, null, reference, false);
            VideoPlayer p = new VideoPlayer(false, false);
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
            // Buffer quietly: LOW priority keeps the playing reel in charge of the bandwidth.
            FileStreamLoadOperation.setPriorityForDocument(doc, FileLoader.PRIORITY_LOW);
            p.preparePlayer(vu.uri, "other");
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

    private void share(FeedItem item) {
        if (item == null || item.mo == null || getParentActivity() == null) return;
        ArrayList<MessageObject> list = new ArrayList<>();
        list.add(item.mo);
        try {
            ShareAlert alert = new ShareAlert(getParentActivity(), list, null, true, null, false);
            showDialog(alert);
            sendEvent("SHARE", item); // share intent, not confirmed delivery — good enough a signal
        } catch (Exception e) { FileLog.e(e); }
    }

    private void showMore(FeedItem item) {
        if (item == null || getParentActivity() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        CharSequence[] options = {"Shikoyat (Report)", "Kanalga o'tish", "Havolani nusxalash", "Qiziq emas"};
        b.setItems(options, (dialog, which) -> {
            if (which == 0) {
                reportMessage(item);
            } else if (which == 1) {
                openComments(item);
            } else if (which == 2) {
                copyLink(item);
            } else if (which == 3) {
                sendEvent("NOT_INTERESTED", item);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, "Bunday videolar kamroq ko'rsatiladi").show();
            }
        });
        showDialog(b.create());
    }

    private void reportMessage(FeedItem item) {
        if (item == null || getParentActivity() == null) return;
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(item.messageId);
        try {
            ReportBottomSheet.open(account, getParentActivity(), -item.channelId, false, ids,
                    BulletinFactory.of(this), getResourceProvider(), new byte[0], null, status -> {});
        } catch (Exception e) { FileLog.e(e); }
    }

    private void copyLink(FeedItem item) {
        if (item == null || getParentActivity() == null) return;
        String link = "https://t.me/" + item.username + "/" + item.messageId;
        try {
            ClipboardManager cm = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("link", link));
            BulletinFactory.of(this).createCopyLinkBulletin().show();
        } catch (Exception e) { FileLog.e(e); }
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
    }

    @Override
    public void onPause() {
        super.onPause();
        if (currentPlayer != null) currentPlayer.pause();
        pauseWatchClock();
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
        releaseCurrentPlayer();
        releaseNextPlayer();
        // Cancel only the CURRENT reel's stream (its high-priority pull is wasteful once the screen
        // is gone). The LOW-priority background full-downloads for the ahead window are deliberately
        // left running so they finish and persist; any that complete are picked up on next cold start
        // by the file-exists validate-on-load check.
        if (currentPosition >= 0 && currentPosition < items.size()) {
            FeedItem cur = items.get(currentPosition);
            if (cur.mo != null && cur.mo.getDocument() != null
                    && !fullDownloadStarted.contains(cur.mo.getDocument().id)) {
                try { FileLoader.getInstance(account).cancelLoadFile(cur.mo.getDocument()); } catch (Exception ignore) {}
            }
        }
        super.onFragmentDestroy();
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
        } else if (id == NotificationCenter.fileLoaded) {
            String fileName = args.length > 0 && args[0] instanceof String ? (String) args[0] : null;
            FeedItem it = fileName != null ? fileNameToItem.remove(fileName) : null;
            if (it != null) {
                if (it.mo != null && it.mo.getDocument() != null) {
                    fullDownloadStarted.remove(it.mo.getDocument().id); // completed — allow re-download if later evicted
                }
                enqueueResolved(it, true); // serialize + mark downloaded + persist
                if (currentPosition >= 0) ensureFullDownloadsAhead(currentPosition); // a slot filled — top up
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            String fileName = args.length > 0 && args[0] instanceof String ? (String) args[0] : null;
            FeedItem it = fileName != null ? fileNameToItem.remove(fileName) : null;
            if (it != null && it.mo != null && it.mo.getDocument() != null) {
                fullDownloadStarted.remove(it.mo.getDocument().id); // allow a future retry
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
        final TextView pausedIcon;
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

        ReelsHolder(FrameLayout root, AspectRatioFrameLayout aspect, TextureView tv, BackupImageView cover, ProgressBar pb,
                    TextView paused, ImageView likeIcon, TextView likeCount, ImageView commentIcon,
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

        void setShareCount(int n) { shareCount.setText(n > 0 ? String.valueOf(n) : "Ulashish"); }

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
        void setLikeCount(int n) { likeCount.setText(n > 0 ? String.valueOf(n) : "Like"); }
        void setLiked(boolean liked) {
            likeIcon.setImageResource(liked ? R.drawable.media_like_active : R.drawable.media_like);
            likeIcon.setColorFilter(liked ? 0xFFFF2E38 : 0xFFFFFFFF);
        }
        void setCommentCount(int n) {
            commentCount.setText(n > 0 ? String.valueOf(n) : "Izoh");
            commentIcon.setAlpha(n > 0 ? 1f : 0.4f);
            commentCount.setAlpha(n > 0 ? 1f : 0.4f);
        }
        void setFollowing(boolean following) {
            followBtn.setText(following ? "Obuna ✓" : "Obuna");
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

            TextView paused = new TextView(ctx);
            paused.setText("▍▍");
            paused.setTextColor(0xCCFFFFFF);
            paused.setTextSize(40);
            paused.setVisibility(View.GONE);
            page.addView(paused, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

            // Right action rail.
            LinearLayout rail = new LinearLayout(ctx);
            rail.setOrientation(LinearLayout.VERTICAL);
            rail.setGravity(Gravity.CENTER_HORIZONTAL);

            ImageView likeIcon = new ImageView(ctx);
            likeIcon.setImageResource(R.drawable.media_like);
            likeIcon.setColorFilter(0xFFFFFFFF);
            rail.addView(likeIcon, LayoutHelper.createLinear(34, 34, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 2));
            TextView likeCount = railLabel(ctx, "Like");
            rail.addView(likeCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 18));

            ImageView commentIcon = new ImageView(ctx);
            commentIcon.setImageResource(R.drawable.menu_comments);
            commentIcon.setColorFilter(0xFFFFFFFF);
            rail.addView(commentIcon, LayoutHelper.createLinear(32, 32, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 2));
            TextView commentCount = railLabel(ctx, "Izoh");
            rail.addView(commentCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 18));

            ImageView shareIcon = new ImageView(ctx);
            shareIcon.setImageResource(R.drawable.media_share);
            shareIcon.setColorFilter(0xFFFFFFFF);
            rail.addView(shareIcon, LayoutHelper.createLinear(32, 32, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 2));
            TextView shareCount = railLabel(ctx, "Ulashish");
            rail.addView(shareCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 18));

            ImageView moreIcon = new ImageView(ctx);
            moreIcon.setImageResource(R.drawable.msg_actions);
            moreIcon.setColorFilter(0xFFFFFFFF);
            rail.addView(moreIcon, LayoutHelper.createLinear(28, 28, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));

            FrameLayout.LayoutParams railLp = LayoutHelper.createFrame(56, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 6, 0);
            railLp.bottomMargin = bottomInset + AndroidUtilities.dp(8);
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
            followBtn.setText("Obuna");
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
            bottomLp.bottomMargin = bottomInset + AndroidUtilities.dp(8);
            page.addView(bottomBox, bottomLp);

            ReelsHolder holder = new ReelsHolder(page, aspect, tv, cover, pb, paused, likeIcon, likeCount,
                    commentIcon, commentCount, shareCount, avatar, channelName, verifiedBadge, followBtn, title);
            title.setOnClickListener(v -> {
                holder.titleExpanded = !holder.titleExpanded;
                holder.title.setMaxLines(holder.titleExpanded ? 100 : 2);
            });

            View.OnClickListener like = v -> toggleLike(itemFor(holder), holder);
            likeIcon.setOnClickListener(like);
            likeCount.setOnClickListener(like);
            View.OnClickListener comment = v -> { FeedItem it = itemFor(holder); if (it != null && it.mo != null && it.mo.getRepliesCount() > 0) openComments(it); };
            commentIcon.setOnClickListener(comment);
            commentCount.setOnClickListener(comment);
            View.OnClickListener shareClick = v -> share(itemFor(holder));
            shareIcon.setOnClickListener(shareClick);
            shareCount.setOnClickListener(shareClick);
            moreIcon.setOnClickListener(v -> showMore(itemFor(holder)));
            followBtn.setOnClickListener(v -> toggleFollow(itemFor(holder), holder));
            channelName.setOnClickListener(v -> openComments(itemFor(holder)));
            avatar.setOnClickListener(v -> openComments(itemFor(holder)));

            return holder;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ReelsHolder h = (ReelsHolder) holder;
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
