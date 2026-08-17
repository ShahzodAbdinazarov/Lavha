package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.TextureView;
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
import org.telegram.svipe.SvipeGuest;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.VideoPlayer;

import java.util.ArrayList;

/**
 * Svipe for somebody who has not signed in: reels, and nothing else.
 *
 * <p><b>Why this is its own screen rather than a mode of {@link ReelsActivity}.</b> That screen is
 * built on the account — it resolves each post over MTProto and streams the bytes through
 * {@code FileLoader}, both of which need an auth key. A guest has none. What they get instead is a
 * plain HTTPS mp4 from Telegram's public CDN, handed over by our backend, played by the same
 * ExoPlayer wrapper with none of the resolve machinery in front of it. Threading "is this a guest"
 * through three and a half thousand lines to express that would have been the expensive way to say
 * something simple.
 *
 * <p><b>No tabs.</b> A guest has one surface. Music, Video, Chats and Profile all need an account
 * to mean anything, and a bottom bar offering four doors that each open onto a sign-up prompt reads
 * as a broken app rather than a generous one. So the bar is not drawn, and the only door out is the
 * one button that says what it does.
 *
 * <p><b>Every action is a wall, deliberately.</b> Liking, commenting and following are not features
 * we are withholding — they physically happen on the user's Telegram account. The prompt says that,
 * because "sign up to continue" with no reason is what makes people close an app.
 */
public class SvipeGuestReelsActivity extends BaseFragment {

    private final ArrayList<SvipeGuest.Item> items = new ArrayList<>();
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private Adapter adapter;
    private TextView emptyView;

    private VideoPlayer player;
    private TextureView boundTexture;
    private int playingIndex = -1;
    private Integer cursor = 0;
    private boolean loading;

    @Override
    public View createView(Context context) {
        // Full bleed, the way every reel surface in the app is: the action bar keeps its back
        // gesture and its slot in the fragment stack, it just stops taking a strip of the video.
        actionBar.setAddToContainer(false);
        FrameLayout root = new FrameLayout(context);
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
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.SvipeGuestEnd));
        emptyView.setTextColor(0xB3FFFFFF);
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // The only door out, and it says where it goes. Drawn over the video rather than under it,
        // because there is no bottom bar to put it in.
        TextView signUp = new TextView(context);
        signUp.setText(LocaleController.getString(R.string.SvipeGuestSignUp));
        signUp.setTextColor(Color.WHITE);
        signUp.setTextSize(15);
        signUp.setTypeface(AndroidUtilities.bold());
        signUp.setGravity(Gravity.CENTER);
        signUp.setBackground(org.telegram.ui.ActionBar.Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(20), 0x33FFFFFF, 0x55FFFFFF));
        signUp.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(18), 0);
        signUp.setOnClickListener(v -> openSignUp());
        root.addView(signUp, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 40,
                Gravity.TOP | Gravity.RIGHT, 0,
                12 + AndroidUtilities.statusBarHeight / AndroidUtilities.density, 12, 0));

        fragmentView = root;
        loadMore();
        return root;
    }

    /** Ask for the next page. One in flight at a time; an empty page ends the feed honestly. */
    private void loadMore() {
        if (loading || cursor == null) {
            return;
        }
        loading = true;
        final int at = cursor;
        SvipeGuest.reels(at, (result, next, error) -> AndroidUtilities.runOnUIThread(() -> {
            loading = false;
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
        final SvipeGuest.Item item = items.get(position);
        SvipeGuest.media(item, (resolved, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (playingIndex != position || fragmentView == null) {
                return;   // swiped away while the URL was being resolved
            }
            if (resolved == null || resolved.mediaUrl.isEmpty()) {
                // Nothing to play and nothing to say about it: move on rather than park the guest
                // in front of a card that never starts.
                if (position + 1 < items.size() && listView != null) {
                    listView.smoothScrollToPosition(position + 1);
                }
                return;
            }
            final RecyclerView.ViewHolder holder = listView.findViewHolderForAdapterPosition(position);
            if (!(holder instanceof Holder)) {
                return;
            }
            start(((Holder) holder).page, resolved);
        }));
    }

    private void start(PageView page, SvipeGuest.Item item) {
        releasePlayer();
        try {
            player = new VideoPlayer(true, false);
            boundTexture = page.texture;
            player.setTextureView(page.texture);
            player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override public void onStateChanged(boolean playWhenReady, int playbackState) {}
                @Override public void onError(VideoPlayer p, Exception e) {}
                @Override public void onVideoSizeChanged(int w, int h, int rotation, float ratio) {
                    page.setVideoSize(w, h);
                }
                @Override public void onRenderedFirstFrame() {
                    page.onFirstFrame();
                }
                @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
                @Override public boolean onSurfaceDestroyed(android.graphics.SurfaceTexture st) { return false; }
            });
            player.setLooping(true);
            // A plain https mp4 on Telegram's public CDN. No document, no FileLoader, no auth key —
            // this is the whole reason a guest can watch anything at all.
            player.preparePlayer(Uri.parse(item.mediaUrl), "other");
            player.play();
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
        boundTexture = null;
    }

    /**
     * What a guest is told when they reach for something an account owns.
     *
     * <p>The wording matters more than the wall. "Sign up to continue" with no reason reads as a
     * toll; saying that a like happens on their own Telegram account is both true and the reason
     * they would want one.
     */
    private void wall() {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.SvipeGuestWallTitle))
                .setMessage(LocaleController.getString(R.string.SvipeGuestWallText))
                .setPositiveButton(LocaleController.getString(R.string.SvipeGuestSignUp),
                        (d, w) -> openSignUp())
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void openSignUp() {
        // The guest identity is not carried into the account: from here on the person is the account.
        presentFragment(new LoginActivity(), true);
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

    /** One full-screen reel: poster underneath, video on top of it, text and actions over both. */
    private class PageView extends FrameLayout {
        final TextureView texture;
        final BackupImageView poster;
        final TextView title;
        final TextView caption;

        PageView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);

            poster = new BackupImageView(context);
            poster.getImageReceiver().setAspectFit(false);
            addView(poster, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            texture = new TextureView(context);
            texture.setOpaque(false);
            texture.setAlpha(0f);   // revealed on the first frame, so no black flash over the poster
            addView(texture, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            title = new TextView(context);
            title.setTextColor(Color.WHITE);
            title.setTextSize(15);
            title.setTypeface(AndroidUtilities.bold());
            title.setShadowLayer(AndroidUtilities.dp(3), 0, AndroidUtilities.dp(1), 0x66000000);
            addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.LEFT, 16, 0, 72, 46));

            caption = new TextView(context);
            caption.setTextColor(0xCCFFFFFF);
            caption.setTextSize(14);
            caption.setMaxLines(2);
            caption.setShadowLayer(AndroidUtilities.dp(3), 0, AndroidUtilities.dp(1), 0x66000000);
            addView(caption, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.LEFT, 16, 0, 72, 22));

            addAction(context, "♥", 0);
            addAction(context, "💬", 1);
            addAction(context, "↗", 2);
        }

        private void addAction(Context context, String glyph, int index) {
            TextView b = new TextView(context);
            b.setText(glyph);
            b.setTextSize(24);
            b.setTextColor(Color.WHITE);
            b.setGravity(Gravity.CENTER);
            b.setOnClickListener(v -> wall());
            addView(b, LayoutHelper.createFrame(52, 52, Gravity.BOTTOM | Gravity.RIGHT,
                    0, 0, 6, 60 + index * 58));
        }

        void bind(SvipeGuest.Item item) {
            texture.setAlpha(0f);
            poster.setAlpha(1f);   // a recycled page still carries the last card's faded-out poster
            videoW = videoH = 0;
            title.setText(item.title);
            caption.setText(item.caption);
            if (item.posterUrl != null && !item.posterUrl.isEmpty()) {
                poster.setImage(ImageLocation.getForPath(item.posterUrl), "720_720",
                        (android.graphics.drawable.Drawable) null, (Object) null);
            } else {
                poster.getImageReceiver().clearImage();
            }
        }

        void onFirstFrame() {
            AndroidUtilities.runOnUIThread(() -> {
                texture.animate().alpha(1f).setDuration(120).start();
                // And take the poster away. It exists to cover the gap before the first frame; left
                // underneath a video that does not cover the whole screen it becomes a second,
                // stretched copy of the same picture showing around the edges of the first.
                poster.animate().alpha(0f).setDuration(120).start();
            });
        }

        /**
         * Centre-crop the video into the page.
         *
         * <p>A reel surface is full-bleed and the catalogue is not: these are real posts, and a
         * 720x734 clip in a 1080x2400 window either fills it or leaves two thirds of the screen
         * showing whatever is behind. Scaling by the LARGER ratio and centring is what every reel
         * player does — the edges are cropped, which is the right thing to lose.
         */
        void setVideoSize(int w, int h) {
            if (w <= 0 || h <= 0) {
                return;
            }
            videoW = w;
            videoH = h;
            applyCrop();
        }

        private int videoW, videoH;

        private void applyCrop() {
            final int vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) {
                return;
            }
            final float scale = Math.max(vw / (float) videoW, vh / (float) videoH);
            final float dw = videoW * scale, dh = videoH * scale;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(dw / vw, dh / vh);
            m.postTranslate((vw - dw) / 2f, (vh - dh) / 2f);
            texture.setTransform(m);
            texture.invalidate();
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (changed) {
                applyCrop();
            }
        }
    }

    private class Holder extends RecyclerView.ViewHolder {
        final PageView page;

        Holder(PageView view) {
            super(view);
            page = view;
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;   // a reel is not a row you tap; its actions are their own views
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            PageView v = new PageView(parent.getContext());
            v.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((Holder) holder).page.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
