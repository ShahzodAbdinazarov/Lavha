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
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.svipe.SvipeDiscover;
import org.telegram.svipe.SvipeVideoSearchHistory;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Instagram-style Explore grid for the Search section. In the empty (no-query) state it BROWSES reel
 * references from GET /v1/discover; once the user types it SEARCHES OUR videos via
 * GET /v1/discover/search (same FeedItem reference shape, so the exact same reference→thumbnail
 * renderer + reels-open-on-tap is reused). Focusing the empty field shows the user's recently-tapped
 * reels (a local {@link SvipeVideoSearchHistory} ledger, storing the tapped video REFERENCE, not the
 * query) as the SAME reel thumbnails under a "Recent" header — tap re-opens that reel, long-press
 * removes it, or clear the whole history. Resolves each reference to a Telegram message (batched per
 * channel) to render its video thumbnail, and pages on scroll.
 */
public class SvipeExploreGrid extends RecyclerListView {

    public interface OnReelTapListener {
        void onReelTap(ArrayList<SvipeDiscover.Item> items, int position);
    }

    /**
     * Pluggable pager so the same grid can back a history screen, not just /v1/discover. When null the
     * grid loads the explore feed as before; a settings screen (e.g. reels watch-history) sets one that
     * routes to its own endpoint. {@code refresh} is honoured only if the endpoint supports it.
     */
    public interface PageLoader {
        void load(int offset, int limit, boolean refresh, SvipeDiscover.Callback cb);
    }

    private static final int SPAN_COUNT = 3;
    private static final int PAGE_SIZE = 60;
    private static final int SKELETON_COUNT = 15;   // ~5 rows of shimmer placeholders
    private static final int SEARCH_DEBOUNCE_MS = 350;
    private static final int TYPE_PHOTO = 0;      // a reel thumbnail — used for BOTH browse/search results and recents
    private static final int TYPE_SKELETON = 1;
    private static final int TYPE_RECENT_HEADER = 2;
    private static final int TYPE_EMPTY = 3;
    // 3 was TYPE_RECENT_ROW (a recent-search QUERY text row); recents now reuse the reel-thumbnail cell.
    private static final int TYPE_PHOTO_WIDE = 4;  // a HORIZONTAL/long-form video: full-width 16:9 card
    private static final int TYPE_SKELETON_WIDE = 5;
    // Widest card we will draw. Beyond this a "horizontal" video is a letterboxed banner, so clamp and
    // let the thumbnail crop instead of handing the row to a 30dp-tall sliver.
    private static final float MAX_CARD_ASPECT = 2.4f;

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

    // ---- browse / search / recents mode ----
    // When searchActive: items hold /v1/discover/search results for activeQuery. Otherwise items hold
    // the /v1/discover browse grid, and showRecents toggles the recent-search rows on top of it.
    private final SvipeVideoSearchHistory history;
    // The recent references (most-recent first) and their resolved-thumbnail GridItems, shown IN PLACE
    // of the browse grid while the empty field is focused. Kept parallel: recentItems[i] wraps recentRows[i].
    private final ArrayList<SvipeDiscover.Item> recentRows = new ArrayList<>();
    private final ArrayList<GridItem> recentItems = new ArrayList<>();
    private boolean searchActive;
    private String activeQuery;
    private boolean showRecents;
    private Runnable searchDebounce;
    // Bumped whenever the content is reset (new search / return to browse) so an in-flight page load
    // whose mode/query has since changed lands stale and is dropped instead of polluting the list.
    private int contentSeq;

    // ---- pluggable pager (standalone history screens) ----
    private PageLoader pageLoader;          // null -> default /v1/discover feed
    private final boolean pullEnabled;      // pull-to-refresh only makes sense for the live explore feed

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
    // Horizontal-swipe yield: a horizontal-dominant drag belongs to the parent tab pager. Tracked
    // from DOWN so we can bail BEFORE the RecyclerView claims it (which would intermittently steal
    // bottom-tab swipes when the grid is scrolled).
    private float downX, downY;
    private boolean horizontalSwipe;
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
        this(context, account, false);
    }

    /**
     * @param standalone true when the grid is the whole screen of a plain BaseFragment (its ActionBar
     *                   already reserves the top, and there is no bottom tab bar) — use tight insets and
     *                   drop pull-to-refresh. false is the Search-section embedding (search bar on top,
     *                   floating bottom tabs), which keeps the original insets + pull-to-refresh.
     */
    public SvipeExploreGrid(Context context, int account, boolean standalone) {
        super(context);
        this.account = account;
        this.history = new SvipeVideoSearchHistory(account);
        this.pullEnabled = !standalone;
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        layoutManager = new GridLayoutManager(context, SPAN_COUNT);
        // The "Recent" header, the no-results notice and every HORIZONTAL video card span the full
        // width; vertical reels keep their 3-up column. The server emits verticals in complete runs of
        // SPAN_COUNT, so each run lands as one full row with no holes.
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (showingSkeleton()) {
                    return isWideSkeleton(position) ? SPAN_COUNT : 1;
                }
                if (searchEmpty()) {
                    return SPAN_COUNT;
                }
                if (position < recentHeaderRows()) {
                    return SPAN_COUNT;
                }
                return isWideAt(position) ? SPAN_COUNT : 1;
            }
        });
        setLayoutManager(layoutManager);
        adapter = new GridAdapter();
        setAdapter(adapter);

        final int top = standalone ? AndroidUtilities.dp(1) : AndroidUtilities.statusBarHeight + AndroidUtilities.dp(58);
        final int bottom = (standalone ? AndroidUtilities.dp(1) : AndroidUtilities.dp(96)) + AndroidUtilities.navigationBarHeight;
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
            if (tapListener == null) {
                return;
            }
            if (hasRecents()) {
                // Recents view: the "Recent" header at 0, then recent reel thumbnails. Tapping one
                // re-OPENS that reel (seeded like a live result), never a re-search.
                final int idx = position - recentHeaderRows();
                if (idx < 0 || idx >= recentRows.size()) {
                    return;
                }
                final ArrayList<SvipeDiscover.Item> single = new ArrayList<>(1);
                single.add(recentRows.get(idx));
                tapListener.onReelTap(single, 0);
                return;
            }
            // Browse / search grid.
            if (position < 0 || position >= items.size()) {
                return;
            }
            // Record a tapped SEARCH RESULT as a recent (browse taps aren't results, so store nothing).
            if (searchActive) {
                history.add(items.get(position).ref);
            }
            final ArrayList<SvipeDiscover.Item> refs = new ArrayList<>(items.size());
            for (GridItem gi : items) {
                refs.add(gi.ref);
            }
            tapListener.onReelTap(refs, position);
        });

        // Long-press a recent thumbnail to remove just that entry (the per-item counterpart to the
        // header's Clear). Matches the original recents' immediate X-removal. No-op on browse/search.
        setOnItemLongClickListener((view, position) -> {
            if (!hasRecents()) {
                return false;
            }
            final int idx = position - recentHeaderRows();
            if (idx < 0 || idx >= recentRows.size()) {
                return false;
            }
            removeRecent(recentRows.get(idx));
            return true;
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

    /** Route paging through a custom endpoint (e.g. reels watch-history) instead of /v1/discover. */
    public void setPageLoader(PageLoader loader) {
        this.pageLoader = loader;
    }

    private void requestPage(int offset, int limit, boolean refresh, SvipeDiscover.Callback cb) {
        if (pageLoader != null) {
            pageLoader.load(offset, limit, refresh, cb);
        } else {
            SvipeDiscover.load(account, null, offset, limit, refresh, cb);
        }
    }

    /** True while showing OUR video-search results (vs the browse grid) — the host uses it to log clicks. */
    public boolean svipeIsSearchActive() {
        return searchActive;
    }

    /** The query whose results are currently shown (null while browsing). */
    public String svipeActiveQuery() {
        return activeQuery;
    }

    /**
     * Drive the grid from the search field. Called by the host on every text / focus change:
     * <ul>
     *   <li>non-empty query → SEARCH OUR videos (debounced) via {@code /v1/discover/search};</li>
     *   <li>empty + focused → show the recently-tapped reels (thumbnails) in place of the browse grid;</li>
     *   <li>empty + unfocused → the plain browse grid.</li>
     * </ul>
     */
    public void svipeSetSearchState(String rawText, boolean focused) {
        final String q = rawText == null ? "" : rawText.trim();
        if (q.length() >= 2) {   // matches the telemetry threshold; 1 char keeps the recents/browse view
            showRecents = false;
            if (!searchActive || !q.equals(activeQuery)) {
                scheduleSearch(q);
            }
            return;
        }
        // Empty / single-char query — browse content (recent rows overlaid when the field is focused).
        boolean changed = false;
        if (searchActive) {
            cancelPendingSearch();
            searchActive = false;
            activeQuery = null;
            resetContent();          // drop the search results; browse is reloaded below
            changed = true;
        }
        if (focused != showRecents) {
            changed = true;
        }
        showRecents = focused;
        if (showRecents) {
            refreshRecentRows();
        } else {
            recentRows.clear();
            recentItems.clear();
        }
        if (changed) {
            adapter.notifyDataSetChanged();
        }
        ensureBrowseLoaded();
    }

    /** Trigger the first page load once (called by the host when the grid first becomes visible). */
    public void ensureLoaded() {
        if (startedFirstLoad || searchActive) {
            return;
        }
        startedFirstLoad = true;
        loadPage();
    }

    /** Reload the browse grid if it is currently empty and nothing is in flight (used after a search). */
    private void ensureBrowseLoaded() {
        if (!searchActive && !loading && items.isEmpty()) {
            loadPage();
        }
    }

    private void scheduleSearch(String q) {
        cancelPendingSearch();
        searchDebounce = () -> {
            searchDebounce = null;
            runSearch(q);
        };
        AndroidUtilities.runOnUIThread(searchDebounce, SEARCH_DEBOUNCE_MS);
    }

    private void cancelPendingSearch() {
        if (searchDebounce != null) {
            AndroidUtilities.cancelRunOnUIThread(searchDebounce);
            searchDebounce = null;
        }
    }

    /** Commit a query: load its first page of OUR video results. Nothing is stored for the query
     *  itself — only a tapped result later becomes a recent (see the item-click handler). */
    private void runSearch(String q) {
        searchActive = true;
        activeQuery = q;
        showRecents = false;
        recentRows.clear();
        recentItems.clear();
        resetContent();
        loadPage();                  // loadPage routes to /v1/discover/search while searchActive
    }

    /** Clear the current list + paging so the next loadPage starts fresh; older loads land stale. */
    private void resetContent() {
        contentSeq++;
        items.clear();
        nextOffset = 0;
        loading = false;
        refreshing = false;
    }

    private void refreshRecentRows() {
        recentRows.clear();
        recentRows.addAll(history.getAll());
        // Wrap each stored reference in a GridItem and resolve its thumbnail exactly like a browse cell.
        recentItems.clear();
        for (SvipeDiscover.Item ref : recentRows) {
            recentItems.add(new GridItem(ref));
        }
        if (!recentItems.isEmpty()) {
            resolveThumbnails(new ArrayList<>(recentItems));
        }
    }

    private boolean hasRecents() {
        return showRecents && !recentRows.isEmpty();
    }

    /** 1 while the recents view is up (the single full-span "Recent" header), else 0. */
    private int recentHeaderRows() {
        return hasRecents() ? 1 : 0;
    }

    /** The GridItem list currently backing the reel thumbnails: recents when up, else browse/search. */
    private List<GridItem> currentGrid() {
        return hasRecents() ? recentItems : items;
    }

    /**
     * True when the video at this ADAPTER position is horizontal (a full-width card). Decided purely
     * from the server-sent dimensions on the reference, so it is stable from the first layout pass —
     * deriving it from the resolved Telegram document instead would make cells change span/height as
     * MTProto resolves, reflowing the grid under the user's finger.
     */
    private boolean isWideAt(int position) {
        final int idx = position - recentHeaderRows();
        final List<GridItem> grid = currentGrid();
        if (idx < 0 || idx >= grid.size()) {
            return false;
        }
        final GridItem gi = grid.get(idx);
        return gi.ref != null && gi.ref.isLandscape();
    }

    /**
     * Shimmer placeholders mirror the real mix (one full-width card, then a 3-up row) so the skeleton
     * doesn't re-flow into a different shape the moment the first page lands.
     */
    private static boolean isWideSkeleton(int position) {
        return position % (1 + SPAN_COUNT) == 0;
    }

    /** The adapter position a resolved GridItem should notify at, or -1 if it isn't the visible grid. */
    private int adapterPositionOf(GridItem gi) {
        final int idx = currentGrid().indexOf(gi);
        return idx < 0 ? -1 : recentHeaderRows() + idx;
    }

    /** A committed search that came back with nothing — show the single "no results" notice. */
    private boolean searchEmpty() {
        return searchActive && items.isEmpty() && !loading && !refreshing;
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
            downX = e.getX();
            downY = e.getY();
            horizontalSwipe = false;
            // Only a candidate when resting at the very top and not already refreshing.
            pullStartY = (pullEnabled && !refreshing && !searchActive && !hasRecents() && !canScrollVertically(-1)) ? e.getY() : -1f;
        } else if (action == MotionEvent.ACTION_MOVE && !pulling && !horizontalSwipe) {
            // A horizontal-dominant drag belongs to the parent tab pager — bail before the
            // RecyclerView claims it, and re-allow the parent to intercept (the RV may have already
            // disallowed it on a tiny vertical jitter).
            final float adx = Math.abs(e.getX() - downX);
            final float ady = Math.abs(e.getY() - downY);
            if (adx > touchSlop && adx > ady) {
                horizontalSwipe = true;
                disallowParentIntercept(false);
                return false;
            }
        }
        if (horizontalSwipe) {
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE && pullStartY >= 0 && !pulling) {
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
            downX = e.getX();
            downY = e.getY();
            horizontalSwipe = false;
            pullStartY = (pullEnabled && !refreshing && !searchActive && !hasRecents() && !canScrollVertically(-1)) ? e.getY() : -1f;
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!pulling && !horizontalSwipe) {
                final float adx = Math.abs(e.getX() - downX);
                final float ady = Math.abs(e.getY() - downY);
                if (adx > touchSlop && adx > ady) {
                    // Horizontal-dominant drag — hand it to the parent tab pager, don't consume it.
                    horizontalSwipe = true;
                    disallowParentIntercept(false);
                    return false;
                }
            }
            if (horizontalSwipe) {
                return false;
            }
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
        if (loading || searchActive) {
            animatePullTo(0f);   // a page load is already in flight (or we're showing search results)
            return;
        }
        refreshing = true;
        loading = true;          // block scroll-pagination until the swap completes
        startSpin();
        animatePullTo(PULL_THRESHOLD);   // settle at the resting position while loading
        requestPage(0, PAGE_SIZE, true, (result, next, error) -> {
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
        return !hasRecents() && items.isEmpty() && (loading || refreshing);
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
        final int seq = contentSeq;             // pin this request to the current browse/search content
        final SvipeDiscover.Callback cb = (result, next, error) -> {
            if (seq != contentSeq) {
                return;   // mode / query changed under us — this page is stale, drop it
            }
            final boolean wasSkeleton = showingSkeleton();
            loading = false;
            if (refreshing) {
                finishRefresh();
            }
            if (result == null) {
                // Failed load: drop the skeleton placeholders (or reveal the empty-search notice).
                adapter.notifyDataSetChanged();
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
            if (hasRecents()) {
                // Browse/search loaded BEHIND the recents view — stage the data silently; it renders
                // when the recents view is dismissed (that transition does a full notify).
            } else if (wasSkeleton || before == 0) {
                // The item count changes wholesale (skeleton/empty -> real size), so a full rebind.
                adapter.notifyDataSetChanged();
            } else if (!fresh.isEmpty()) {
                adapter.notifyItemRangeInserted(recentHeaderRows() + before, fresh.size());
            }
            if (!fresh.isEmpty()) {
                resolveThumbnails(fresh);
            }
        };
        if (searchActive) {
            SvipeDiscover.search(account, activeQuery, offset, PAGE_SIZE, cb);
        } else {
            // Browse mode: honour an injected PageLoader (e.g. reels-history) when set, else /v1/discover.
            requestPage(offset, PAGE_SIZE, false, cb);
        }
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
                    // Notify only if this GridItem is in the CURRENTLY visible grid (recents or browse).
                    final int pos = adapterPositionOf(gi);
                    if (pos >= 0) {
                        adapter.notifyItemChanged(pos);
                    }
                }
            }
        }));
    }

    // ---- adapter / cell ----

    private class GridAdapter extends SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int type = holder.getItemViewType();
            return type == TYPE_PHOTO || type == TYPE_PHOTO_WIDE;
        }

        @Override
        public int getItemViewType(int position) {
            if (showingSkeleton()) {
                return isWideSkeleton(position) ? TYPE_SKELETON_WIDE : TYPE_SKELETON;
            }
            if (searchEmpty()) {
                return TYPE_EMPTY;
            }
            if (position < recentHeaderRows()) {
                return TYPE_RECENT_HEADER;
            }
            return isWideAt(position) ? TYPE_PHOTO_WIDE : TYPE_PHOTO;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final Context ctx = parent.getContext();
            final View view;
            switch (viewType) {
                case TYPE_SKELETON:
                    view = new SkeletonCell(ctx, false);
                    break;
                case TYPE_SKELETON_WIDE:
                    view = new SkeletonCell(ctx, true);
                    break;
                case TYPE_RECENT_HEADER:
                    view = new RecentHeaderView(ctx);
                    break;
                case TYPE_EMPTY:
                    view = createEmptyView(ctx);
                    break;
                case TYPE_PHOTO_WIDE:
                    view = new WideImageView(ctx);
                    break;
                default:
                    view = new PortraitImageView(ctx);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final int type = holder.getItemViewType();
            if (type != TYPE_PHOTO && type != TYPE_PHOTO_WIDE) {
                return;   // skeleton / recent header / empty self-render, nothing to bind
            }
            BackupImageView iv = (BackupImageView) holder.itemView;
            GridItem gi = currentGrid().get(position - recentHeaderRows());
            if (type == TYPE_PHOTO_WIDE) {
                // The card's height comes from the video's own aspect, so bind it BEFORE setImage:
                // requestLayout during bind is what keeps the row height right on a recycled holder.
                ((WideImageView) iv).bindRef(gi.ref);
            }
            if (gi.mo != null && gi.mo.getDocument() != null) {
                TLRPC.Document doc = gi.mo.getDocument();
                // A full-width card is ~3x the pixel width of a 3-up tile, so it needs the larger
                // thumb and a matching filter or it renders visibly soft.
                final boolean wide = type == TYPE_PHOTO_WIDE;
                TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, wide ? 1000 : 320);
                TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 50);
                final String filter = wide ? "720_720" : "240_240";
                iv.setImage(
                        ImageLocation.getForDocument(big, doc), filter,
                        ImageLocation.getForDocument(small, doc), filter + "_b",
                        0, gi.mo);
            } else {
                iv.getImageReceiver().clearImage();
            }
        }

        @Override
        public int getItemCount() {
            if (showingSkeleton()) {
                return SKELETON_COUNT;
            }
            if (searchEmpty()) {
                return 1;
            }
            return recentHeaderRows() + currentGrid().size();
        }
    }

    // ---- recents (shown IN PLACE of the browse grid when the empty field is focused) ----

    private void removeRecent(SvipeDiscover.Item ref) {
        history.remove(ref);
        refreshRecentRows();
        adapter.notifyDataSetChanged();
    }

    private void clearHistory() {
        history.clear();
        recentRows.clear();
        recentItems.clear();
        adapter.notifyDataSetChanged();
    }

    /** Full-width "Recent searches" header with a Clear-all action on the trailing edge. */
    private class RecentHeaderView extends LinearLayout {
        RecentHeaderView(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(AndroidUtilities.dp(15), AndroidUtilities.dp(12), AndroidUtilities.dp(6), AndroidUtilities.dp(4));

            TextView title = new TextView(context);
            title.setText(LocaleController.getString(R.string.SvipeRecentSearches));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

            TextView clear = new TextView(context);
            clear.setText(LocaleController.getString(R.string.SvipeClearSearchHistory));
            clear.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            clear.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            clear.setBackground(Theme.getSelectorDrawable(false));
            clear.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(6));
            clear.setOnClickListener(v -> clearHistory());
            addView(clear, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        }
    }

    /** The single centred "No results" notice shown when a committed search returns nothing. */
    private View createEmptyView(Context context) {
        TextView tv = new TextView(context);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(48), AndroidUtilities.dp(20), AndroidUtilities.dp(20));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        tv.setText(LocaleController.getString(R.string.NoResult));
        return tv;
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

    /**
     * HORIZONTAL / long-form cell: a full-width card whose height follows the video's own aspect (so
     * the thumbnail is shown uncropped, unlike the portrait tiles which centre-crop), with a
     * YouTube-style duration badge in the bottom-right corner.
     *
     * The aspect comes off the /v1/discover reference, not the resolved Telegram document, so the row
     * measures correctly on the very first layout pass and never jumps when MTProto answers.
     */
    private static class WideImageView extends BackupImageView {
        private final GridShimmer shimmer = new GridShimmer();
        private final RectF rect = new RectF();
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint badgeText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private float aspect = 16f / 9f;
        private String duration;

        WideImageView(Context context) {
            super(context);
            badgePaint.setColor(0x99000000);
            badgeText.setColor(Color.WHITE);
            badgeText.setTextSize(AndroidUtilities.dp(11));
            badgeText.setTypeface(AndroidUtilities.bold());
        }

        void bindRef(SvipeDiscover.Item ref) {
            final float a = ref == null
                    ? 16f / 9f
                    : Math.min(MAX_CARD_ASPECT, Math.max(SvipeDiscover.LANDSCAPE_MIN_ASPECT, ref.aspect()));
            final int seconds = ref == null ? 0 : ref.durationMs / 1000;
            final String d = seconds > 0 ? AndroidUtilities.formatShortDuration(seconds) : null;
            if (a != aspect) {
                aspect = a;
                requestLayout();
            }
            if (!TextUtils.equals(d, duration)) {
                duration = d;
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int height = Math.max(1, Math.round(width / aspect));
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
            if (duration != null) {
                final float pad = AndroidUtilities.dp(4);
                final float margin = AndroidUtilities.dp(6);
                final float tw = badgeText.measureText(duration);
                final float th = badgeText.getTextSize();
                final float right = getWidth() - margin;
                final float bottom = getHeight() - margin;
                rect.set(right - tw - pad * 2, bottom - th - pad * 1.6f, right, bottom);
                canvas.drawRoundRect(rect, AndroidUtilities.dp(3), AndroidUtilities.dp(3), badgePaint);
                canvas.drawText(duration, rect.left + pad, rect.bottom - pad * 0.8f, badgeText);
            }
        }
    }

    /** No-data placeholder cell: pure shimmer, shown while {@code items} is still empty. */
    private static class SkeletonCell extends View {
        private final GridShimmer shimmer = new GridShimmer();
        private final RectF rect = new RectF();
        private final boolean wide;

        SkeletonCell(Context context, boolean wide) {
            super(context);
            this.wide = wide;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            // Wide placeholders stand in for a 16:9 card, narrow ones for a 2:3 portrait tile.
            final float ratio = wide ? 9f / 16f : 3f / 2f;
            setMeasuredDimension(width, Math.round(width * ratio));
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
