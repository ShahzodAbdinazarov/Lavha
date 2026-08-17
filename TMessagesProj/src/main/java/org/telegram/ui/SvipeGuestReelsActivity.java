package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.svipe.SvipeCharDrawable;
import org.telegram.svipe.SvipeGuest;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.VideoPlayer;

import java.util.ArrayList;

/**
 * Svipe for somebody who has not signed in: reels, and nothing else.
 *
 * <p><b>The same reel, drawn by the same code.</b> Every page here comes from
 * {@link ReelsActivity#createPage} — the identical view tree the signed-in feed uses, down to the
 * action rail, the channel row and the caption. A guest surface with its own hand-built layout would
 * have looked almost right on the day it was written and wrong a week later, because only one of the
 * two would ever get the next change. There is one layout; this screen fills it with different data
 * and answers its taps differently.
 *
 * <p><b>Why the screen itself is separate.</b> {@link ReelsActivity} is built on the account: it
 * resolves each post over MTProto and streams the bytes through {@code FileLoader}, both of which
 * need an auth key a guest does not have. What a guest gets instead is a plain HTTPS mp4 on
 * Telegram's public CDN, handed over by our backend. Same picture, different plumbing.
 *
 * <p><b>This is the front door.</b> A fresh install lands here, not on the onboarding: reels need no
 * account, so asking for a phone number before showing any is asking a stranger to pay before they
 * can see what they are buying.
 *
 * <p><b>No tabs, and no sign-up button either.</b> Music, Video, Chats and Profile all need an
 * account to mean anything, and a bottom bar of four doors that each open onto a prompt reads as a
 * broken app rather than a generous one. A banner button on top of the video would be the same
 * mistake in a smaller way: every control on the page already leads to the offer, at the moment the
 * visitor actually wants the thing it unlocks. Nothing has to nag them in the meantime.
 *
 * <p><b>Every control is a wall, and the wall explains itself</b> — see
 * {@link org.telegram.svipe.SvipeGuestSignUpSheet}.
 */
public class SvipeGuestReelsActivity extends BaseFragment {

    private final ArrayList<SvipeGuest.Item> items = new ArrayList<>();
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private Adapter adapter;
    private TextView emptyView;
    private GestureDetector tapDetector;

    private VideoPlayer player;
    private int playingIndex = -1;
    /** Wall-clock of the tap/settle that opened the current reel, and of the URL landing. */
    private long openAtMs, urlAtMs;
    private Integer cursor = 0;
    private boolean loading;

    @Override
    public View createView(Context context) {
        // Full bleed, the way every reel surface in the app is: the action bar keeps its back gesture
        // and its slot in the fragment stack, it just stops taking a strip of the video.
        actionBar.setAddToContainer(false);
        // The detector sits on the ROOT and reads dispatchTouchEvent, not on the list. RecyclerListView
        // handles its own touches, so an OnItemTouchListener never saw the taps that land on the rail —
        // the first attempt let every press through to play/pause. dispatchTouchEvent is the one place
        // that sees a gesture before anybody can claim it.
        FrameLayout root = new FrameLayout(context) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (tapDetector != null) {
                    tapDetector.onTouchEvent(ev);
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        root.setBackgroundColor(Color.BLACK);

        listView = new RecyclerListView(context);
        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        listView.setLayoutManager(layoutManager);
        listView.setAdapter(adapter = new Adapter());
        // A reel surface is a pager that happens to be a list: one item fills the screen and a fling
        // settles on exactly one. PagerSnapHelper is the same primitive the rest of the app uses.
        new PagerSnapHelper().attachToRecyclerView(listView);
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    onSettled();
                }
            }
        });

        // Anything landing on a control opens the sign-in sheet; anything else toggles playback. Read
        // as a gesture rather than per-child onClick for the same reason the signed-in feed does it:
        // RecyclerView cancels a child's touch the instant it claims the gesture for vertical paging.
        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (hitsControl(e)) {
                    wall();
                } else {
                    togglePlayback();
                }
                return true;
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.SvipeGuestEnd));
        emptyView.setTextColor(0xB3FFFFFF);
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // The comment bar the signed-in feed carries, in the same slot and the same shape: 44dp tall,
        // 22dp corners, 7dp side insets, translucent dark, sitting above the navigation bar. It is not
        // a real ChatActivityEnterView — that one belongs to an account and would have nothing to send
        // to — but a guest should SEE where commenting happens rather than discover later that the
        // screen was missing a piece. Pressing it says why it cannot be used yet.
        FrameLayout commentBar = new FrameLayout(context);
        android.graphics.drawable.GradientDrawable commentBg = new android.graphics.drawable.GradientDrawable();
        commentBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        commentBg.setCornerRadius(AndroidUtilities.dp(22));
        commentBg.setColor(0xCC1C1C1E);
        commentBar.setBackground(commentBg);
        commentBar.setClickable(true);
        commentBar.setOnClickListener(v -> wall());

        TextView commentHint = new TextView(context);
        commentHint.setText(LocaleController.getString(R.string.SvipeCommentHint));
        commentHint.setTextColor(0xFF9E9E9E);
        commentHint.setTextSize(14);
        commentHint.setGravity(Gravity.CENTER_VERTICAL);
        commentBar.addView(commentHint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 18, 0, 48, 0));

        android.widget.ImageView sendIcon = new android.widget.ImageView(context);
        sendIcon.setImageResource(R.drawable.msg_send);
        sendIcon.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFF9E9E9E,
                android.graphics.PorterDuff.Mode.SRC_IN));
        commentBar.addView(sendIcon, LayoutHelper.createFrame(22, 22,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

        FrameLayout.LayoutParams commentLp = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44,
                Gravity.BOTTOM, 7, 0, 7, 0);
        commentLp.bottomMargin = AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(6);
        root.addView(commentBar, commentLp);

        fragmentView = root;
        loadMore();
        return root;
    }

    /** Is this the very first page? Only that one can come from the warm-up. */
    private boolean at0() {
        return items.isEmpty() && cursor != null && cursor == 0;
    }

    /** How much of the bottom the comment bar occupies — the inset every page lays its controls above. */
    private static int commentBarInset() {
        return AndroidUtilities.dp(44 + 6) + AndroidUtilities.navigationBarHeight;
    }

    /** Did this tap land on one of the reel's controls? Same hit-test the signed-in feed uses. */
    private boolean hitsControl(MotionEvent e) {
        final ReelsActivity.ReelsHolder h = holderAt(currentPosition());
        if (h == null) {
            return false;
        }
        return ReelsActivity.pointInView(h.likeIcon, e) || ReelsActivity.pointInView(h.likeCount, e)
                || ReelsActivity.pointInView(h.commentIcon, e) || ReelsActivity.pointInView(h.commentCount, e)
                || ReelsActivity.pointInView(h.shareIcon, e) || ReelsActivity.pointInView(h.shareCount, e)
                || ReelsActivity.pointInView(h.saveIcon, e) || ReelsActivity.pointInView(h.saveLabel, e)
                || ReelsActivity.pointInView(h.moreIcon, e) || ReelsActivity.pointInView(h.followBtn, e)
                || ReelsActivity.pointInView(h.avatar, e) || ReelsActivity.pointInView(h.channelName, e);
    }

    private void togglePlayback() {
        if (player == null) {
            return;
        }
        final ReelsActivity.ReelsHolder h = holderAt(currentPosition());
        try {
            if (player.isPlaying()) {
                player.pause();
                if (h != null) h.setPaused(true);
            } else {
                player.play();
                if (h != null) h.setPaused(false);
            }
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private int currentPosition() {
        if (layoutManager == null) {
            return -1;
        }
        int pos = playingIndex;
        if (pos < 0 || pos >= items.size()) {
            pos = layoutManager.findFirstVisibleItemPosition();
        }
        return pos;
    }

    private ReelsActivity.ReelsHolder holderAt(int position) {
        if (listView == null || position < 0) {
            return null;
        }
        RecyclerView.ViewHolder vh = listView.findViewHolderForAdapterPosition(position);
        return vh instanceof ReelsActivity.ReelsHolder ? (ReelsActivity.ReelsHolder) vh : null;
    }

    /** Ask for the next page. One in flight at a time; an empty page ends the feed honestly. */
    private void loadMore() {
        if (loading || cursor == null) {
            return;
        }
        if (at0()) {
            // The app started warming this the moment it knew there was no account. Wait for it
            // rather than racing it: firing a second request for the same page means two requests
            // and the screen still waiting on the slower one.
            loading = true;
            SvipeGuest.takeWarm(w -> {
                loading = false;
                if (w != null && w.items != null && !w.items.isEmpty()) {
                    org.telegram.messenger.FileLog.d("svipe-g: took warm page (" + w.items.size() + " items)");
                    items.addAll(w.items);
                    cursor = w.next;
                    if (adapter != null) {
                        adapter.notifyItemRangeInserted(0, w.items.size());
                    }
                    if (listView != null) {
                        listView.post(this::onSettled);
                    }
                    return;
                }
                loadMore();   // nothing held — ask, exactly as this would have without a warm-up
            });
            return;
        }
        loading = true;
        final int at = cursor;
        final long askedAt = android.os.SystemClock.elapsedRealtime();
        SvipeGuest.reels(at, (result, next, error) -> AndroidUtilities.runOnUIThread(() -> {
            loading = false;
            org.telegram.messenger.FileLog.d("svipe-g: reels page@" + at + " -> "
                    + (result == null ? "null" : result.size() + " items") + " in "
                    + (android.os.SystemClock.elapsedRealtime() - askedAt) + "ms");
            if (result == null || result.isEmpty()) {
                // Not a failure state: the server fails closed when its safety gate has nothing to
                // say, and a spinner that never resolves would be a lie about that.
                cursor = null;
                if (items.isEmpty() && emptyView != null) {
                    emptyView.setVisibility(View.VISIBLE);
                }
                return;
            }
            final int from = items.size();
            items.addAll(result);
            cursor = next;
            if (adapter != null) {
                adapter.notifyItemRangeInserted(from, result.size());
            }
            if (playingIndex < 0 && listView != null) {
                // AFTER the layout pass, not during it. notifyItemRangeInserted only schedules one,
                // so asking the layout manager what is on screen right here answers "nothing" — and
                // since nothing is scrolling yet, nothing would ever ask again.
                listView.post(SvipeGuestReelsActivity.this::onSettled);
            }
        }));
    }

    /** The pager has come to rest: play what is on screen, and warm the one after it. */
    private void onSettled() {
        if (layoutManager == null) {
            return;
        }
        int position = layoutManager.findFirstCompletelyVisibleItemPosition();
        if (position < 0) {
            // Mid-settle, or the very first frame after a page landed. The partially visible one is
            // the right answer then — a snap helper guarantees there is only one candidate.
            position = layoutManager.findFirstVisibleItemPosition();
        }
        if (position < 0 || position >= items.size()) {
            return;
        }
        if (position >= items.size() - 3) {
            loadMore();
        }
        if (position != playingIndex) {
            playAt(position);
        }
        // Resolve the NEXT one now. The server scrapes these one at a time and rate-limits itself,
        // so a swipe that has to wait for its own resolve is a swipe into a black rectangle.
        if (position + 1 < items.size()) {
            SvipeGuest.media(items.get(position + 1), (item, error) -> {});
        }
    }

    private void playAt(int position) {
        playingIndex = position;
        openAtMs = android.os.SystemClock.elapsedRealtime();
        urlAtMs = 0;
        final SvipeGuest.Item item = items.get(position);
        final ReelsActivity.ReelsHolder opening = holderAt(position);
        if (opening != null) {
            opening.showLoading(true);
        }
        SvipeGuest.media(item, (resolved, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (playingIndex != position || fragmentView == null) {
                return;   // swiped away while the URL was being resolved
            }
            urlAtMs = android.os.SystemClock.elapsedRealtime();
            org.telegram.messenger.FileLog.d("svipe-g: media #" + position + " -> "
                    + (resolved == null ? "none" : "url") + " +" + (urlAtMs - openAtMs) + "ms after open");
            final ReelsActivity.ReelsHolder h = holderAt(position);
            if (resolved == null || resolved.mediaUrl.isEmpty()) {
                // Nothing to play and nothing to say about it: move on rather than park the guest in
                // front of a card that never starts.
                if (h != null) h.showLoading(false);
                if (position + 1 < items.size() && listView != null) {
                    listView.smoothScrollToPosition(position + 1);
                }
                return;
            }
            if (h != null) {
                start(h, resolved);
            }
        }));
    }

    private void start(ReelsActivity.ReelsHolder h, SvipeGuest.Item item) {
        releasePlayer();
        try {
            final VideoPlayer p = new VideoPlayer(true, false);
            player = p;
            h.textureView.setAlpha(0f);
            p.setTextureView(h.textureView);
            p.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override public void onStateChanged(boolean playWhenReady, int playbackState) {}
                @Override public void onError(VideoPlayer from, Exception e) {}
                @Override public void onVideoSizeChanged(int w, int hh, int rotation, float ratio) {
                    if (w > 0 && hh > 0) {
                        // The page's AspectRatioFrameLayout does the fitting, exactly as it does for
                        // a signed-in reel — nothing here re-derives how a reel is framed.
                        h.aspect.setAspectRatio(w / (float) hh, rotation);
                    }
                }
                @Override public void onRenderedFirstFrame() {
                    final long now = android.os.SystemClock.elapsedRealtime();
                    org.telegram.messenger.FileLog.d("svipe-g: first-frame +"
                            + (openAtMs > 0 ? now - openAtMs : -1) + "ms after open, +"
                            + (urlAtMs > 0 ? now - urlAtMs : -1) + "ms after url");
                    AndroidUtilities.runOnUIThread(() -> {
                        if (player != p) return;
                        h.textureView.setAlpha(1f);
                        h.hideCover();
                        h.showLoading(false);
                    });
                }
                @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
                @Override public boolean onSurfaceDestroyed(android.graphics.SurfaceTexture st) { return false; }
            });
            p.setLooping(true);
            // A plain https mp4 on Telegram's public CDN. No document, no FileLoader, no auth key —
            // this is the whole reason a guest can watch anything at all.
            p.preparePlayer(Uri.parse(item.mediaUrl), "other");
            p.play();
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    private void releasePlayer() {
        if (player == null) {
            return;
        }
        try { player.releasePlayer(true); } catch (Exception ignore) {}
        player = null;
    }

    /**
     * What a guest is offered when they reach for something an account owns.
     *
     * <p>A sheet rather than an alert, and a list rather than a sentence: a wall with no answer to
     * "why would I" is a toll booth. See {@link org.telegram.svipe.SvipeGuestSignUpSheet}.
     */
    private void wall() {
        org.telegram.svipe.SvipeGuestSignUpSheet.show(getParentActivity(), this::openSignUp);
    }

    /**
     * Where "sign up" goes: the onboarding, pushed ON TOP of the feed rather than replacing it.
     *
     * <p>Not replacing it matters. Somebody who opens the sign-up flow, thinks better of it and
     * presses back should land where they were — still watching — instead of on an empty stack that
     * closes the app. And it is the INTRO rather than the login screen because the intro carries the
     * language switcher, which is the one thing a first-time visitor from anywhere may need before
     * they can read a word of the rest.
     */
    private void openSignUp() {
        presentFragment(new IntroActivity());
    }

    @Override
    public void onPause() {
        super.onPause();
        if (player != null) {
            try { player.pause(); } catch (Exception ignore) {}
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (player != null) {
            try { player.play(); } catch (Exception ignore) {}
        }
    }

    @Override
    public void onFragmentDestroy() {
        releasePlayer();
        super.onFragmentDestroy();
    }

    @Override
    public boolean isLightStatusBar() {
        return false;   // white text over video, always
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;   // taps are read at the list level, as in the signed-in feed
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            // The page's controls sit above whatever occupies the bottom of the screen. There is no tab
            // bar here, but there IS the comment bar, and passing 0 put the channel row underneath it.
            return ReelsActivity.createPage(parent.getContext(), commentBarInset());
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final ReelsActivity.ReelsHolder h = (ReelsActivity.ReelsHolder) holder;
            final SvipeGuest.Item item = items.get(position);

            h.showLoading(true);
            h.setPaused(false);
            // A recycled TextureView still shows the previous reel's frozen frame — hide it until
            // this reel's own first frame arrives, so the cover shows through instead.
            h.textureView.setAlpha(0f);

            // The cover is our stored poster rather than a Telegram thumbnail: a guest has no way to
            // ask Telegram for one, and this is the picture the share page already uses.
            if (item.posterUrl != null && !item.posterUrl.isEmpty()) {
                h.cover.setImage(ImageLocation.getForPath(item.posterUrl), "360_640",
                        (android.graphics.drawable.Drawable) null, (Object) null);
                h.cover.setVisibility(View.VISIBLE);
            } else {
                h.cover.setImageDrawable(null);
                h.cover.setVisibility(View.GONE);
            }

            h.channelName.setText(item.username != null && !item.username.isEmpty()
                    ? "@" + item.username : "");
            h.avatar.setImageDrawable(new SvipeCharDrawable(
                    item.username != null && !item.username.isEmpty()
                            ? item.username.substring(0, 1).toUpperCase() : "?"));
            h.setVerified(false);
            h.setTitle(item.caption);
            // Counts a guest cannot be shown: the reference carries no reaction or reply state, and
            // inventing zeros would be a claim about the post rather than about what we know.
            h.setLikeCount(0);
            h.setLiked(false);
            h.setCommentCount(0);
            h.setShareCount(0);
            h.setFollowing(false);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
