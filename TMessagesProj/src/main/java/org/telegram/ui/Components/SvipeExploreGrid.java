package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.core.graphics.ColorUtils;
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
    private static final int SKELETON_COUNT = 15;   // ~5 rows of shimmer placeholders
    private static final int TYPE_PHOTO = 0;
    private static final int TYPE_SKELETON = 1;

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

    // --- pull-to-refresh: native, drawn in dispatchDraw. The grid must stay a RecyclerListView
    // (DialogsActivity casts svipeExploreGrid to one), so we can't wrap it in a SwipeRefreshLayout
    // nor addView() an overlay (RecyclerView would reclaim that child on the next layout pass). ---
    private static final int PULL_THRESHOLD = AndroidUtilities.dp(72);
    private static final int MAX_PULL = AndroidUtilities.dp(150);
    private final Paint spinnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF spinnerRect = new RectF();
    private int touchSlop;
    private float pullStartY = -1f;   // -1 = no pull candidate captured
    private boolean pulling;
    private boolean refreshing;
    private float pullDistance;       // damped, px
    private float spinRotation;       // degrees, indeterminate spin while refreshing
    private ValueAnimator pullAnimator;
    private ValueAnimator spinAnimator;

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

        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        spinnerPaint.setStyle(Paint.Style.STROKE);
        spinnerPaint.setStrokeCap(Paint.Cap.ROUND);
        spinnerPaint.setStrokeWidth(AndroidUtilities.dp(2.5f));
        spinnerPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        chipPaint.setStyle(Paint.Style.FILL);
        chipPaint.setColor(Theme.getColor(Theme.key_dialogBackground));
        chipPaint.setShadowLayer(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(1), 0x40000000);

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

    /**
     * Re-tap-the-active-tab → scroll to the top. The grid can be very deep (endless scroll), so a
     * plain smoothScrollToPosition(0) from far down would crawl through every row — jump close first,
     * then smooth-scroll the last stretch for a clean finish. No-op when already at the top.
     */
    public void scrollToTop() {
        stopScroll();
        if (layoutManager.findFirstVisibleItemPosition() <= 0) {
            return;
        }
        if (layoutManager.findFirstVisibleItemPosition() > SPAN_COUNT * 4) {
            scrollToPosition(SPAN_COUNT * 4);
        }
        smoothScrollToPosition(0);
    }

    // ---- pull-to-refresh ----

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // Only a candidate when resting at the very top and not already refreshing.
            pullStartY = (!refreshing && !canScrollVertically(-1)) ? e.getY() : -1f;
        } else if (action == MotionEvent.ACTION_MOVE && pullStartY >= 0 && !pulling) {
            // If a child consumed the DOWN, the MOVE stream routes through here — claim a clear
            // downward drag past slop (horizontal tab swipes / upward scrolls are left untouched).
            final float dy = e.getY() - pullStartY;
            if (dy > touchSlop && !canScrollVertically(-1)) {
                pulling = true;
                disallowParentIntercept(true);
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            pullStartY = -1f;
        }
        return super.onInterceptTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();
        // The grid's item views don't consume ACTION_DOWN, so the MOVE stream is delivered straight
        // to onTouchEvent (not onInterceptTouchEvent). Detect and run the top-pull from here.
        if (action == MotionEvent.ACTION_DOWN) {
            pullStartY = (!refreshing && !canScrollVertically(-1)) ? e.getY() : -1f;
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!pulling && pullStartY >= 0 && (e.getY() - pullStartY) > touchSlop && !canScrollVertically(-1)) {
                pulling = true;
                disallowParentIntercept(true);
            }
            if (pulling) {
                final float raw = Math.max(0f, e.getY() - pullStartY);
                pullDistance = Math.min(raw * 0.5f, MAX_PULL);   // rubber-band damping
                invalidate();
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (pulling) {
                pulling = false;
                disallowParentIntercept(false);
                final boolean trigger = action == MotionEvent.ACTION_UP
                        && pullDistance >= PULL_THRESHOLD && !loading && !refreshing;
                pullStartY = -1f;
                if (trigger) {
                    triggerRefresh();
                } else {
                    animatePullTo(0f);
                }
                return true;
            }
            pullStartY = -1f;
        }
        return super.onTouchEvent(e);
    }

    private void disallowParentIntercept(boolean disallow) {
        final ViewParent p = getParent();
        if (p != null) {
            p.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    /**
     * Pull-to-refresh: fetch a fresh (server-rotated) page 0 while keeping the current grid on
     * screen under the spinner, then swap the whole list in one pass when it lands. The old content
     * is NOT cleared up-front — that clear-then-reload is what made the grid flash the skeleton (and
     * stale recycled thumbnails) and look like it "reverted". With refresh=1 the server rotates to a
     * different window, so the swap shows genuinely new content rather than the identical list.
     */
    private void triggerRefresh() {
        if (loading) {
            animatePullTo(0f);   // a page load is already in flight; don't stack a refresh on it
            return;
        }
        refreshing = true;
        loading = true;          // block scroll-pagination until the swap completes
        startSpin();
        animatePullTo(PULL_THRESHOLD);   // settle at the resting position while loading
        SvipeDiscover.load(account, null, 0, PAGE_SIZE, true, (result, next, error) -> {
            loading = false;
            refreshing = false;
            stopSpin();
            animatePullTo(0f);
            if (result == null) {
                return;   // network/auth failure: keep the current grid, never blank or revert
            }
            // Atomic swap: replace the list in one notify so there's no empty/skeleton frame. Keep
            // resolvedChats as a warm cache so channels that reappear keep their thumbnails.
            items.clear();
            nextOffset = next;
            final ArrayList<GridItem> fresh = new ArrayList<>(result.size());
            for (SvipeDiscover.Item ref : result) {
                GridItem gi = new GridItem(ref);
                items.add(gi);
                fresh.add(gi);
            }
            adapter.notifyDataSetChanged();
            scrollToPosition(0);
            if (!fresh.isEmpty()) {
                resolveThumbnails(fresh);
            }
        });
    }

    private void finishRefresh() {
        refreshing = false;
        stopSpin();
        animatePullTo(0f);
    }

    private void startSpin() {
        if (spinAnimator != null) {
            return;
        }
        spinAnimator = ValueAnimator.ofFloat(0f, 360f);
        spinAnimator.setDuration(900);
        spinAnimator.setRepeatCount(ValueAnimator.INFINITE);
        spinAnimator.setInterpolator(new LinearInterpolator());
        spinAnimator.addUpdateListener(a -> {
            spinRotation = (float) a.getAnimatedValue();
            invalidate();
        });
        spinAnimator.start();
    }

    private void stopSpin() {
        if (spinAnimator != null) {
            spinAnimator.cancel();
            spinAnimator = null;
        }
    }

    private void animatePullTo(float target) {
        if (pullAnimator != null) {
            pullAnimator.cancel();
        }
        pullAnimator = ValueAnimator.ofFloat(pullDistance, target);
        pullAnimator.setDuration(220);
        pullAnimator.setInterpolator(new DecelerateInterpolator());
        pullAnimator.addUpdateListener(a -> {
            pullDistance = (float) a.getAnimatedValue();
            invalidate();
        });
        pullAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                pullDistance = target;
                if (target == 0f) {
                    spinRotation = 0f;
                }
                invalidate();
            }
        });
        pullAnimator.start();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (pullDistance <= 0f && !refreshing) {
            return;
        }
        final float progress = Math.min(1f, pullDistance / PULL_THRESHOLD);
        final float alpha = refreshing ? 1f : progress;
        final float cx = getWidth() / 2f;
        final float cy = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(30) + pullDistance;
        final int chipR = AndroidUtilities.dp(18);
        final int arcR = AndroidUtilities.dp(10);
        // The familiar white "puck" with a soft shadow, brand-coloured progress arc inside.
        chipPaint.setAlpha((int) (255 * alpha));
        canvas.drawCircle(cx, cy, chipR, chipPaint);
        spinnerPaint.setAlpha((int) (255 * alpha));
        spinnerRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR);
        if (refreshing) {
            canvas.drawArc(spinnerRect, spinRotation, 270f, false, spinnerPaint);
        } else {
            canvas.drawArc(spinnerRect, -90f, Math.max(12f, 360f * progress), false, spinnerPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        resetPull();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // The grid is toggled GONE the moment a query is typed; a pull/refresh in flight at that
        // instant may never get its ACTION_UP/CANCEL, so reset here too (no-op when idle).
        if (visibility != VISIBLE) {
            resetPull();
        }
    }

    /** Clear all pull/refresh state and always release the parent intercept lock (pairs the true). */
    private void resetPull() {
        stopSpin();
        if (pullAnimator != null) {
            pullAnimator.cancel();
            pullAnimator = null;
        }
        disallowParentIntercept(false);
        pulling = false;
        refreshing = false;
        pullStartY = -1f;
        pullDistance = 0f;
        spinRotation = 0f;
    }

    /** The 3-column shimmer placeholder grid is shown while the first page (initial or refresh) loads. */
    private boolean showingSkeleton() {
        return items.isEmpty() && (loading || refreshing);
    }

    private void loadPage() {
        if (loading || nextOffset == null) {
            return;
        }
        loading = true;
        if (items.isEmpty()) {
            adapter.notifyDataSetChanged();   // reveal the skeleton grid while the first page loads
        }
        final int offset = nextOffset;
        SvipeDiscover.load(account, null, offset, PAGE_SIZE, (result, next, error) -> {
            final boolean wasSkeleton = showingSkeleton();
            loading = false;
            if (refreshing) {
                finishRefresh();
            }
            if (result == null) {
                if (wasSkeleton) {
                    adapter.notifyDataSetChanged();   // failed load: drop the skeleton placeholders
                }
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
            if (wasSkeleton) {
                // The item count changes wholesale (SKELETON_COUNT -> real size), so a full rebind.
                adapter.notifyDataSetChanged();
            } else if (!fresh.isEmpty()) {
                adapter.notifyItemRangeInserted(before, fresh.size());
            }
            if (!fresh.isEmpty()) {
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
            return holder.getItemViewType() == TYPE_PHOTO;
        }

        @Override
        public int getItemViewType(int position) {
            return showingSkeleton() ? TYPE_SKELETON : TYPE_PHOTO;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view = viewType == TYPE_SKELETON
                    ? new SkeletonCell(parent.getContext())
                    : new PortraitImageView(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() != TYPE_PHOTO) {
                return;   // skeleton placeholders self-animate, nothing to bind
            }
            PortraitImageView iv = (PortraitImageView) holder.itemView;
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
            return showingSkeleton() ? SKELETON_COUNT : items.size();
        }
    }

    /**
     * Portrait cell (3:2). Shows the shimmer placeholder until the Telegram thumbnail bitmap is
     * available — so a cell never flashes black while /v1/discover items are resolving their thumbs.
     */
    private static class PortraitImageView extends BackupImageView {
        private final GridShimmer shimmer = new GridShimmer();
        private final RectF rect = new RectF();

        PortraitImageView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int height = Math.round(width * 3f / 2f);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!getImageReceiver().hasBitmapImage()) {
                final float inset = AndroidUtilities.dp(1);
                rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
                shimmer.draw(canvas, rect, AndroidUtilities.dp(3), this);
            }
            super.onDraw(canvas);
        }
    }

    /** No-data placeholder cell (3:2): pure shimmer, shown while {@code items} is still empty. */
    private static class SkeletonCell extends View {
        private final GridShimmer shimmer = new GridShimmer();
        private final RectF rect = new RectF();

        SkeletonCell(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(width, Math.round(width * 3f / 2f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float inset = AndroidUtilities.dp(1);
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            shimmer.draw(canvas, rect, AndroidUtilities.dp(3), this);
        }
    }

    /**
     * Theme-aware grid shimmer: an opaque gray placeholder with a soft highlight band sweeping across.
     * The highlight is derived from the theme (only ~9% lighter in dark mode, so it isn't garish; a
     * stronger lift in light mode where contrast is naturally lower). Self-animates via invalidate().
     */
    private static class GridShimmer {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private LinearGradient gradient;
        private int gradientWidth, base, highlight;
        private float progress;
        private long lastUpdate;

        void draw(Canvas canvas, RectF rect, float rad, View view) {
            final int b = Theme.getColor(Theme.key_windowBackgroundGray);
            final boolean dark = (Color.red(b) * 0.299f + Color.green(b) * 0.587f + Color.blue(b) * 0.114f) < 128f;
            final int h = ColorUtils.blendARGB(b, Color.WHITE, dark ? 0.09f : 0.45f);
            final int w = AndroidUtilities.dp(200);
            if (gradient == null || base != b || highlight != h || gradientWidth != w) {
                base = b;
                highlight = h;
                gradientWidth = w;
                gradient = new LinearGradient(0, 0, w, 0,
                        new int[]{b, h, b}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
                paint.setShader(gradient);
            }
            final long now = System.currentTimeMillis();
            if (lastUpdate != 0) {
                progress += (now - lastUpdate) / 1100f;
                while (progress > 1f) {
                    progress -= 1f;
                }
            }
            lastUpdate = now;
            final float x = (rect.width() + gradientWidth * 2f) * progress - gradientWidth;
            matrix.reset();
            matrix.setTranslate(rect.left + x, 0);
            gradient.setLocalMatrix(matrix);
            canvas.drawRoundRect(rect, rad, rad, paint);
            if (view != null) {
                view.invalidate();
            }
        }
    }
}
