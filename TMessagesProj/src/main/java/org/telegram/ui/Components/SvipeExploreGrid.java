package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.svipe.SvipeDiscover;
import org.telegram.svipe.SvipeVideoWarmer;
import org.telegram.svipe.SvipeMovies;
import org.telegram.svipe.SvipeVideoSearchHistory;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.SvipeWideVideoCell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Instagram-style Explore grid for the Search section, in three modes:
 *
 * <p><b>BROWSE</b> (the empty, unfocused state) is fed by TWO fully independent server pipes, each
 * with its own algorithm, its own paging cursor and a single orientation: SHORTS from
 * GET /v1/discover (vertical) and LONG-FORM from GET /v1/videos (horizontal). Neither side mixes
 * orientations — the CLIENT composes the display order: one full-width long card, then
 * {@link #SHORTS_ROWS_PER_LONG} COMPLETE rows of {@link #SPAN_COUNT} shorts, repeating.
 *
 * <p>That is the whole point of the split. When one interleaved list arrived from the server, any
 * gap in it (references with no public username are dropped, the ⋮ menu removes items) turned a run
 * of 3 verticals into a run of 1 or 2 and GridLayoutManager left a lone tile beside two empty cells.
 * Emitting the rows here makes alignment STRUCTURAL: a row is only emitted once SPAN_COUNT shorts are
 * in hand, so a half-filled row cannot be expressed. Each pipe pages, fails and runs dry on its own —
 * a dead long-form pipe degrades to a pure shorts grid, never to an empty screen.
 *
 * <p><b>SEARCH</b> (once the user types) queries BOTH pipes — GET /v1/discover/search (shorts) and
 * GET /v1/videos/search (long-form) — and composes them with the same rhythm as browse, since the two
 * corpora are disjoint server-side and one endpoint can never return the other's videos. <b>RECENTS</b>
 * (empty field, focused) shows the user's recently-tapped reels — a local
 * {@link SvipeVideoSearchHistory} ledger storing the tapped video REFERENCE, not the query — under a
 * "Recent" header, where tap re-opens that reel and long-press removes it. Both are single lists, as
 * is an injected {@link PageLoader} (the standalone watch-history screen): one stream has no rhythm to
 * compose, so it is displayed in the order it arrived.
 *
 * <p>Every mode resolves its references to Telegram messages (batched per channel) to render video
 * thumbnails, and pages on scroll.
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

    /** Host hook for taps on a film / show card — the grid cannot present a fragment itself. */
    public interface MovieDelegate {
        /** A show card: the grid raises it, the host presents, because only a fragment can present. */
        void onSeriesTapped(SvipeMovies.Series series);
    }

    private static final int SPAN_COUNT = 3;
    /**
     * The display rhythm, and the ONE place it lives: after each long-form card the grid emits this
     * many COMPLETE rows of {@code SPAN_COUNT} shorts. Retune freely — the composer, the skeleton and
     * the two pipes' page sizes all derive from it, and no server change is needed because the server
     * never sees the rhythm.
     */
    private static final int SHORTS_ROWS_PER_LONG = 1;
    private static final int PAGE_SIZE = 60;
    /**
     * Long-form page size. Sized so one long page can back one shorts page at the current rhythm
     * ({@code 60 shorts / 3 per row / 1 row per long} = 20 cards), which keeps both pipes running out
     * at roughly the same time instead of one starving the composer.
     */
    private static final int LONG_PAGE_SIZE = PAGE_SIZE / (SPAN_COUNT * SHORTS_ROWS_PER_LONG);
    /**
     * Compose at least this many cells before trusting the scroll to drive further paging. A page that
     * lands short (or whose remainder is held back for an incomplete row) can compose into less than a
     * screenful, and then there is nothing to scroll — so a page that DID deliver items tops itself up
     * until the grid is deep enough to page itself.
     */
    private static final int MIN_COMPOSED_CELLS = SPAN_COUNT * 6;
    /**
     * How many rhythm units (one long card + its rows of shorts) each pipe keeps buffered ahead of the
     * composer. Expressed in units so both pipes are prefetched at the same depth whatever the ratio is.
     */
    private static final int PREFETCH_UNITS = 2;
    /**
     * Consecutive failures a pipe is allowed before the grid stops chasing it by itself and waits for
     * the user (a scroll or a pull-to-refresh). It bounds the self-heal loop below: without a cap, an
     * outage during the first page would spin requests as fast as they can fail.
     *
     * <p>Hitting the cap also RETIRES the long-form pipe for this content generation, which the shorts
     * pipe deliberately never does: a server without /v1/videos fails deterministically and forever and
     * the grid is complete without it, whereas the shorts ARE the grid and must stay retryable.
     */
    private static final int MAX_PIPE_FAILURES = 2;
    private static final int SKELETON_COUNT = 15;   // ~5 rows of shimmer placeholders
    private static final int SEARCH_DEBOUNCE_MS = 350;
    private static final int TYPE_PHOTO = 0;      // a reel thumbnail — used for BOTH browse/search results and recents
    private static final int TYPE_SKELETON = 1;
    private static final int TYPE_RECENT_HEADER = 2;
    private static final int TYPE_EMPTY = 3;
    // 3 was TYPE_RECENT_ROW (a recent-search QUERY text row); recents now reuse the reel-thumbnail cell.
    private static final int TYPE_PHOTO_WIDE = 4;  // a HORIZONTAL/long-form video: full-width 16:9 card
    private static final int TYPE_SKELETON_WIDE = 5;
    private static final int TYPE_CATEGORY_CHIPS = 6;  // the YouTube-style chip strip, always row 0
    // 7 was TYPE_MOVIE, a film card of its own. A film is now the SAME full-width row as any other
    // long video — TYPE_PHOTO_WIDE, SvipeWideVideoCell — with the film's title and "year · ★ rating"
    // passed in as the two text lines. One row renderer for the whole Video tab is the point: every
    // chip has to look like the tab it was opened from, and a second cell class is how they drifted.
    private static final int MOVIE_PAGE_SIZE = 30;
    /** The app-start warm-up is offered to the grid once per instance, on its first browse load. */
    private boolean warmTried;

    private final int account;
    private final GridLayoutManager layoutManager;
    private final GridAdapter adapter;
    /**
     * The DISPLAY list — exactly what the adapter renders, in order. In browse mode it is composed from
     * the two pipes below (see {@link #composeBrowse}); in search / page-loader mode it IS the stream.
     */
    private final ArrayList<GridItem> items = new ArrayList<>();
    // username (lowercase) -> already resolved chat, so a channel is resolved once across pages.
    private final HashMap<String, TLRPC.Chat> resolvedChats = new HashMap<>();

    private boolean loadingSingle;   // the single-stream path: search results / injected page loader
    private boolean startedFirstLoad;
    /** True once any page has been asked for, so a grid that has not started yet is not "empty". */
    private boolean loadAttempted;
    private Integer nextOffset = 0;  // single-stream cursor; null when that stream is exhausted
    private OnReelTapListener tapListener;
    /** Where the last tapped cell's picture is, in window coordinates — the open animation starts there. */
    private final Rect lastTapRect = new Rect();
    /**
     * Host fragment, needed by the wide cards' ⋮ menu. ItemOptions.downFragment special-cases a
     * DialogsActivity that has the main tabs and redirects the popup to the MainTabsActivity layer —
     * that redirect is what makes the menu draw above the floating bottom tab bar, so the fragment
     * form of makeOptions is required here, not the ViewGroup one. ShareAlert and ReportBottomSheet
     * need it too. Null until {@link #setFragment} is called; the menu is a no-op until then.
     */
    private BaseFragment fragment;

    /**
     * The wide cards' ⋮ menu owns its own actions, wording and events (see {@link SvipeWideVideoCell});
     * the grid only supplies the host fragment and does the list surgery a removal implies, since the
     * cell cannot know which of the pipes / recents lists a reference is sitting in.
     */
    private final SvipeWideVideoCell.Delegate cellDelegate = new SvipeWideVideoCell.Delegate() {
        @Override
        public BaseFragment fragment() {
            return fragment;
        }

        @Override
        public void onRefRemoved(SvipeDiscover.Item ref) {
            removeRef(ref);
        }

        @Override
        public void onChannelBlocked(long channelId) {
            removeChannel(channelId);
        }
    };

    // ---- browse / search / recents mode ----
    // When searchActive: the SAME two pipes below hold the two searches for activeQuery
    // (/v1/discover/search -> shorts, /v1/videos/search -> longs) and items holds their composition.
    // Otherwise items hold the composed browse grid, and showRecents toggles the recents on top of it.
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

    // ---- browse mode: the two independent source pipes ----
    // Sources, NOT display: nothing here is rendered until composeBrowse() emits it into `items`. Each
    // pipe keeps its own cursor (null = exhausted) and its own in-flight flag, so one can page, stall or
    // die without touching the other. `composed*` is how much of each pipe the last composition
    // consumed — the rest is buffered, which is how "run low" is measured.
    private final ArrayList<GridItem> shorts = new ArrayList<>();
    private final ArrayList<GridItem> longs = new ArrayList<>();
    private Integer shortsOffset = 0;
    private Integer longsOffset = 0;
    private boolean loadingShorts;
    private boolean loadingLongs;
    private int composedShorts;
    private int composedLongs;
    /**
     * The "carry on where you stopped" card, or null. Fetched separately from both pipes
     * ({@code GET /v1/videos/continue}) because it must not take part in their caching or rotation:
     * a refresh may move it down the page, but what it resumes never changes.
     */
    private GridItem continueCard;
    /** {@code channel:message} of that card, so the cell knows to draw the resume bar on it. */
    private String continueKey;
    /** How far into that video the viewer is, 0..1 — the fill of the bar. */
    private float continueProgress;
    /** Bumped on every refresh, so the continue card does not sit welded to the first slot. */
    private int continueSlotSpin;
    private int shortFailures;
    private int longFailures;

    // ---- pluggable pager (standalone history screens) ----
    // ---- category chips + film catalog ----
    private final ArrayList<SvipeMovies.Category> categories = new ArrayList<>();
    /**
     * The strip as it is RENDERED: the shelves this user opens most, first.
     *
     * <p>Separate from {@link #categories} (the server's taxonomy order) because the two answer
     * different questions — the server says what exists, this says what this person reaches for. "All"
     * is not in here at all; it is synthetic and always the first chip.
     *
     * <p>Recomputed only when the tab is entered ({@link #rebuildChipOrder}). Re-sorting while the
     * strip is on screen would move chips under the finger that is tapping them.
     */
    private final ArrayList<SvipeMovies.Category> chipOrder = new ArrayList<>();
    private SvipeMovies.Category selectedCategory;   // null = "Hammasi" (the unfiltered dual-pipe feed)
    private boolean categoriesRequested;

    /**
     * What a shelf had on it, kept so coming BACK to a chip costs nothing.
     *
     * <p>Switching used to throw the old shelf away and re-fetch it, which made a chip strip unusable
     * on a bad connection: tapping "Comedy" and then "All" put the user back on a shimmer they had
     * already waited through once, and if the request never landed the grid stayed blank. A shelf
     * already seen is data the device is holding — it must render instantly and with no network at
     * all, and a fetch on top of it is a refresh, not a prerequisite.
     */
    private static class Shelf {
        final ArrayList<GridItem> shorts = new ArrayList<>();
        final ArrayList<GridItem> longs = new ArrayList<>();
        final ArrayList<GridItem> singles = new ArrayList<>();   // film / serial shelves: one stream
        Integer shortsOffset = 0;
        Integer longsOffset = 0;
        Integer nextOffset = 0;
        boolean single;

        boolean isEmpty() {
            return shorts.isEmpty() && longs.isEmpty() && singles.isEmpty();
        }
    }

    /** Shelves kept, least-recently-used first out. Access-ordered, so the LRU is the real LRU. */
    private static final int MAX_CACHED_SHELVES = 6;
    /** What is kept of a deep shelf. A returning user gets the top of it, not their scroll position. */
    private static final int CACHED_SHORTS = 60;
    private static final int CACHED_LONGS = 20;
    private static final int CACHED_SINGLES = 60;
    /** Shelves warmed in the background per grid, on top of whatever the user actually opens. */
    private static final int MAX_PREFETCHED_SHELVES = 2;

    private final LinkedHashMap<String, Shelf> shelves =
            new LinkedHashMap<String, Shelf>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Shelf> eldest) {
                    return size() > MAX_CACHED_SHELVES;
                }
            };
    private final HashSet<String> prefetching = new HashSet<>();
    private int prefetchBudget = MAX_PREFETCHED_SHELVES;
    /** Consecutive single-stream failures, the film/serial twin of {@link #shortFailures}. */
    private int singleFailures;
    private MovieDelegate movieDelegate;

    private PageLoader pageLoader;          // null -> the dual-pipe browse feed
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
        /**
         * Full-span long-form card vs one 3-up tile, fixed at construction and never recomputed.
         *
         * For the browse grid this is the PIPE the item arrived on, not its pixels: the composer places
         * a long-pipe item in a full-span slot, so the span lookup must agree even if that item's
         * server-sent dimensions were odd — otherwise a 1-span cell would land in a full-span slot and
         * reopen the very hole this design closes. Single-stream lists (search, recents, an injected page
         * loader) have no pipe to go on, so they fall back to the server-sent dimensions as before.
         */
        final boolean wide;
        MessageObject mo;
        boolean resolved;
        boolean resolving;

        GridItem(SvipeDiscover.Item ref) {
            // Films and shows take a full row, the same as the long-video card on the unfiltered
            // feed. They used to sit two-up, which made a shelf look like a different app from the
            // tab it was opened from — the poster is 16:9 either way, so one per row costs nothing
            // and the whole Video tab reads as one thing.
            this(ref, ref != null && (ref instanceof SvipeMovies.PosterRef
                    || ref instanceof SvipeMovies.SeriesRef || ref.isLandscape()));
        }

        /** The film behind a poster reference, or null for an ordinary video tile/card. */
        SvipeMovies.Movie movie() {
            if (ref instanceof SvipeMovies.PosterRef) {
                return ((SvipeMovies.PosterRef) ref).movie;
            }
            // A show renders with the SAME card as a film — same poster, same title line — so the
            // card never has to know which of the two it is holding.
            if (ref instanceof SvipeMovies.SeriesRef) {
                return ((SvipeMovies.SeriesRef) ref).series.asCard();
            }
            return null;
        }

        /** The show behind a series reference, or null. */
        SvipeMovies.Series series() {
            return ref instanceof SvipeMovies.SeriesRef ? ((SvipeMovies.SeriesRef) ref).series : null;
        }

        GridItem(SvipeDiscover.Item ref, boolean wide) {
            this.ref = ref;
            this.wide = wide;
        }

        /**
         * Which of the three columns this tile occupies, decided by the composer that placed it.
         * Asking the layout manager instead was unreliable — a wide card eats all three spans, so
         * neither the adapter index nor the span index it reports lines up with the row as drawn.
         */
        int tileColumn = -1;
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
        // The "Recent" header, the no-results notice and every long-form video card span the full
        // width; shorts keep their 3-up column. In browse mode the composer only ever emits shorts in
        // complete runs of SPAN_COUNT, so every run lands as one full row with no holes.
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                final int spans = layoutManager.getSpanCount();
                if (position < headerRows()) {
                    return spans;   // the chip strip and the "Recent" header both span the row
                }
                if (showingSkeleton()) {
                    return isWideSkeleton(position - headerRows()) ? spans : 1;
                }
                if (searchEmpty()) {
                    return spans;
                }
                return isWideAt(position) ? spans : 1;
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
            // The failure notice IS the retry button, so it has to come before everything else — it is
            // the one row that means something with no content behind it.
            if (position >= 0 && position < adapter.getItemCount()
                    && adapter.getItemViewType(position) == TYPE_EMPTY) {
                if (loadFailed()) {
                    retryShelf();
                }
                return;
            }
            if (tapListener == null) {
                return;
            }
            if (hasRecents()) {
                // Recents view: the "Recent" header at 0, then recent reel thumbnails. Tapping one
                // re-OPENS that reel (seeded like a live result), never a re-search.
                final int idx = position - headerRows();
                if (idx < 0 || idx >= recentRows.size()) {
                    return;
                }
                final ArrayList<SvipeDiscover.Item> single = new ArrayList<>(1);
                single.add(recentRows.get(idx));
                tapListener.onReelTap(single, 0);
                return;
            }
            // Browse / search grid. `items` is the DISPLAY list, so the seed list handed to
            // ReelsActivity.ofDiscoverSeed is in exactly the order on screen and the index is the
            // tapped cell's own — composition can't desynchronise the two.
            final int idx = position - headerRows();
            if (idx < 0 || idx >= items.size()) {
                return;
            }
            // A SHOW is the one card that is not a video: its "poster" stands for a whole playlist,
            // so it opens the playlist channel rather than playing one episode out of order.
            final SvipeMovies.Series tappedSeries = items.get(idx).series();
            if (tappedSeries != null) {
                if (movieDelegate != null) {
                    movieDelegate.onSeriesTapped(tappedSeries);
                }
                return;
            }
            // A FILM plays, exactly like a card on the unfiltered feed. It used to open a separate
            // profile screen that played nothing, so a tap under Comedy did something different from
            // the same tap under All. The watch page is the film page now: it starts the default copy
            // (the poster IS that copy) and offers the cast and the other copies in its own tabs.
            // Record a tapped SEARCH RESULT as a recent (browse taps aren't results, so store nothing).
            if (searchActive) {
                history.add(items.get(idx).ref);
            }
            final ArrayList<SvipeDiscover.Item> refs = new ArrayList<>(items.size());
            for (GridItem gi : items) {
                refs.add(gi.ref);
            }
            rememberTapRect(view);
            tapListener.onReelTap(refs, idx);
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
                // Every "may I load?" test lives inside the loaders (per pipe in browse mode), so this
                // only decides WHEN to ask.
                if (layoutManager.findLastVisibleItemPosition() >= items.size() - SPAN_COUNT * 2) {
                    loadPage();
                }
            }
        });
    }

    public void setMovieDelegate(MovieDelegate delegate) {
        this.movieDelegate = delegate;
    }

    /**
     * Fetch the chip row once. Failure is silent and non-fatal: with no categories the strip simply
     * does not render and the grid is exactly the feed it was before categories existed — which is
     * also what an older backend produces.
     */
    private void loadCategories() {
        if (categoriesRequested) {
            return;
        }
        categoriesRequested = true;
        SvipeMovies.categories(account, (list, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (list == null || list.isEmpty()) {
                return;
            }
            categories.clear();
            categories.addAll(list);   // the server's taxonomy order — the strip's order is chipOrder
            rebuildChipOrder();
            adapter.notifyDataSetChanged();
        }));
    }

    /**
     * Order the strip by how often this user opens each shelf, most first.
     *
     * <p>A chip strip is a row of a dozen names of which one is always the next tap, and which one is
     * personal: somebody who watches serials opens that shelf every session and scrolls past Sport
     * every time. The counts are the same local, decaying table the prefetch reads
     * ({@link org.telegram.svipe.SvipeCategoryStats}), so the shelf that gets warmed in the background
     * is also the one that moves to the front — one signal, two uses.
     *
     * <p>A stable sort, so shelves the user has never opened keep the server's own order among
     * themselves rather than being shuffled by a tie-break nobody chose.
     */
    private void rebuildChipOrder() {
        final java.util.Map<String, Integer> counts =
                org.telegram.svipe.SvipeCategoryStats.counts(account);
        final ArrayList<SvipeMovies.Category> ordered = new ArrayList<>(categories);
        java.util.Collections.sort(ordered, (a, b) -> {
            final int ca = counts.containsKey(a.slug) ? counts.get(a.slug) : 0;
            final int cb = counts.containsKey(b.slug) ? counts.get(b.slug) : 0;
            return cb - ca;
        });
        chipOrder.clear();
        chipOrder.addAll(ordered);
    }

    /**
     * Switch shelves. Three destinations, decided by the server's {@code film} flag:
     * <ul>
     *   <li>null -> back to the unfiltered dual-pipe browse feed;</li>
     *   <li>a film shelf -> the Zona-style film catalog from {@code /v1/movies};</li>
     *   <li>a non-film shelf (Seriallar, Konsertlar, Ta'lim) -> the same long-video list the tab
     *       already renders, filtered with {@code /v1/videos?cat=}.</li>
     * </ul>
     * Both filtered modes are SINGLE streams, so they take the existing page-loader path and there is
     * no second composer to keep in sync.
     */
    private void selectCategory(SvipeMovies.Category category) {
        // Everything the shelf being left is holding, kept in memory. This is what makes coming back
        // free — and it has to happen BEFORE the state below is cleared.
        saveShelf(shelfKey(selectedCategory));
        org.telegram.svipe.SvipeCategoryStats.noteClick(account, category == null ? null : category.slug);
        selectedCategory = category;
        stopScroll();
        contentSeq++;              // orphan every in-flight page from the previous shelf
        items.clear();
        shorts.clear();
        longs.clear();
        shortsOffset = 0;
        longsOffset = 0;
        composedShorts = 0;
        composedLongs = 0;
        shortFailures = 0;
        longFailures = 0;
        singleFailures = 0;
        loadingShorts = false;
        loadingLongs = false;
        loadingSingle = false;
        nextOffset = 0;
        // One span count for every shelf: film cards are full-row now, so there is nothing left for a
        // second grid shape to do.
        layoutManager.setSpanCount(SPAN_COUNT);
        pageLoader = pageLoaderFor(category);
        final Shelf cached = shelves.get(shelfKey(category));
        final boolean restored = cached != null && restoreShelf(cached);
        if (!restored) {
            // Asked for BEFORE the rebind, so the very first frame of the new shelf is the shimmer and
            // not a flash of the empty notice. The switch itself never waits for the answer: the chip
            // is selected, the grid is the new shelf, and the network fills it in behind that.
            loadPage();
        }
        adapter.notifyDataSetChanged();
        scrollToPosition(0);
        if (restored && items.size() < MIN_COMPOSED_CELLS) {
            loadPage();   // held, but too shallow to scroll: top it up without blanking what is there
        }
        schedulePrefetch();
    }

    /** The stream behind a chip, or null for the two-pipe browse feed (which every plain chip uses). */
    private PageLoader pageLoaderFor(SvipeMovies.Category category) {
        if (category == null) {
            return null;
        } else if ("serial".equals(category.slug)) {
            // The serial shelf lists SHOWS, not loose episodes: an episode is only useful next to its
            // siblings, and the server has already grouped them (app/movies/series.py).
            return (offset, limit, refresh, cb) -> SvipeMovies.series(
                    account, offset, Math.min(limit, MOVIE_PAGE_SIZE),
                    (series, next, error) -> {
                        if (series == null) {
                            cb.onResult(null, null, error);
                            return;
                        }
                        final ArrayList<SvipeDiscover.Item> refs = new ArrayList<>(series.size());
                        for (SvipeMovies.Series one : series) {
                            refs.add(SvipeMovies.SeriesRef.of(one));
                        }
                        cb.onResult(refs, next, null);
                    });
        } else if (category.film) {
            final String slug = category.slug;
            return (offset, limit, refresh, cb) -> SvipeMovies.movies(
                    account, slug, null, offset, Math.min(limit, MOVIE_PAGE_SIZE),
                    (movies, next, error) -> {
                        if (movies == null) {
                            cb.onResult(null, null, error);
                            return;
                        }
                        final ArrayList<SvipeDiscover.Item> refs = new ArrayList<>(movies.size());
                        for (SvipeMovies.Movie m : movies) {
                            refs.add(SvipeMovies.PosterRef.of(m));
                        }
                        cb.onResult(refs, next, null);
                    });
        } else {
            // Everything else (Konsertlar, Sport, Ta'lim...) stays the tab it was opened from: the
            // same two pipes, the same 3-up-plus-wide-card rhythm, with the chip applied to BOTH.
            // Swapping to a single long-video list here made a shelf look like a different screen,
            // and made every short video invisible under every chip.
            return null;
        }
    }

    // ---- shelf memory ---------------------------------------------------------------------------

    private static String shelfKey(SvipeMovies.Category category) {
        return category == null ? org.telegram.svipe.SvipeCategoryStats.ALL : category.slug;
    }

    private SvipeMovies.Category categoryBySlug(String slug) {
        if (slug == null || slug.isEmpty()) {
            return null;
        }
        for (SvipeMovies.Category c : categories) {
            if (slug.equals(c.slug)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Put the shelf on screen into memory.
     *
     * <p>Only the top of it: a shelf scrolled fifty rows deep comes back at its top, which is what
     * every app does with a tab you left. The cursors are pulled back to the trim point with it, so
     * the next page continues where the kept content ends instead of leaving a hole in the middle of
     * a catalog.
     */
    private void saveShelf(String key) {
        if (key == null || searchActive) {
            return;
        }
        final Shelf s = new Shelf();
        s.single = pageLoader != null;
        if (s.single) {
            final int keep = Math.min(items.size(), CACHED_SINGLES);
            s.singles.addAll(items.subList(0, keep));
            s.nextOffset = keep < items.size() ? keep : nextOffset;
        } else {
            final int keepShorts = Math.min(shorts.size(), CACHED_SHORTS);
            final int keepLongs = Math.min(longs.size(), CACHED_LONGS);
            s.shorts.addAll(shorts.subList(0, keepShorts));
            s.longs.addAll(longs.subList(0, keepLongs));
            s.shortsOffset = keepShorts < shorts.size() ? keepShorts : shortsOffset;
            s.longsOffset = keepLongs < longs.size() ? keepLongs : longsOffset;
        }
        if (s.isEmpty()) {
            return;   // nothing worth remembering — and caching an empty shelf would suppress its retry
        }
        shelves.put(key, s);
    }

    /** Put a remembered shelf back on screen. Returns false when it turned out to hold nothing. */
    private boolean restoreShelf(Shelf s) {
        if (s.single) {
            items.addAll(s.singles);
            nextOffset = s.nextOffset;
        } else {
            shorts.addAll(s.shorts);
            longs.addAll(s.longs);
            shortsOffset = s.shortsOffset;
            longsOffset = s.longsOffset;
            composeBrowse();
        }
        // A shelf that was PREFETCHED has references and no messages behind them — nothing has ever
        // asked Telegram about it. Without this its cards would come up as blank rectangles, which is
        // a worse first impression than the shimmer the prefetch exists to avoid. Already-resolved
        // items are skipped inside, so a shelf the user really visited costs nothing here.
        resolveThumbnails(items);
        return !items.isEmpty();
    }

    // ---- prefetch: have the next shelf before it is asked for ------------------------------------

    /**
     * Warm the shelves this user actually opens, in the order they open them.
     *
     * <p>A chip strip has one obvious next tap and it is not the same tap for everybody — somebody who
     * watches serials opens that shelf every session and never touches Sport. {@link
     * org.telegram.svipe.SvipeCategoryStats} counts the taps locally; this spends two page-0 fetches a
     * session on the top two, so the second shelf of a session opens with content already in it rather
     * than with a shimmer and a round-trip.
     *
     * <p>Deferred rather than immediate: the shelf the user is LOOKING at owns the connection until it
     * has something to show, and a prefetch that competes with it makes the visible thing slower.
     */
    private void schedulePrefetch() {
        AndroidUtilities.cancelRunOnUIThread(prefetchRunnable);
        if (prefetchBudget <= 0 || categories.isEmpty()) {
            return;
        }
        AndroidUtilities.runOnUIThread(prefetchRunnable, 2500);
    }

    private final Runnable prefetchRunnable = new Runnable() {
        @Override
        public void run() {
            if (prefetchBudget <= 0 || searchActive || isLoading() || items.isEmpty()) {
                return;   // nothing to spare: the visible shelf comes first, always
            }
            for (String slug : org.telegram.svipe.SvipeCategoryStats.topSlugs(
                    account, MAX_PREFETCHED_SHELVES, shelfKey(selectedCategory))) {
                if (prefetchBudget <= 0) {
                    return;
                }
                if (shelves.containsKey(slug) || prefetching.contains(slug)) {
                    continue;
                }
                if (!slug.isEmpty() && categoryBySlug(slug) == null) {
                    continue;   // a slug the server has since dropped
                }
                prefetchBudget--;
                prefetchShelf(slug);
            }
        }
    };

    /** Fetch one shelf's first page into the cache, off screen. Failure is silent: it is a bonus. */
    private void prefetchShelf(final String slug) {
        prefetching.add(slug);
        final SvipeMovies.Category category = categoryBySlug(slug);
        final PageLoader loader = pageLoaderFor(category);
        final Shelf shelf = new Shelf();
        if (loader != null) {
            shelf.single = true;
            loader.load(0, PAGE_SIZE, false, (result, next, error) -> AndroidUtilities.runOnUIThread(() -> {
                prefetching.remove(slug);
                if (result == null || result.isEmpty()) {
                    return;
                }
                for (SvipeDiscover.Item ref : result) {
                    shelf.singles.add(new GridItem(ref));
                }
                shelf.nextOffset = next;
                shelves.put(slug, shelf);
            }));
            return;
        }
        final String cat = slug.isEmpty() ? null : slug;
        final int[] pending = {2};
        final Runnable settle = () -> {
            if (--pending[0] != 0) {
                return;
            }
            prefetching.remove(slug);
            if (!shelf.isEmpty()) {
                shelves.put(slug, shelf);
            }
        };
        SvipeDiscover.load(account, null, cat, 0, PAGE_SIZE, false,
                (result, next, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (result != null) {
                        for (SvipeDiscover.Item ref : result) {
                            shelf.shorts.add(new GridItem(ref, false));
                        }
                        shelf.shortsOffset = next;
                    }
                    settle.run();
                }));
        SvipeDiscover.videos(account, null, cat, 0, LONG_PAGE_SIZE, false,
                (result, next, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (result != null) {
                        for (SvipeDiscover.Item ref : result) {
                            shelf.longs.add(new GridItem(ref, true));
                        }
                        shelf.longsOffset = next;
                    }
                    settle.run();
                }));
    }

    public void setOnReelTapListener(OnReelTapListener listener) {
        this.tapListener = listener;
    }

    /** Host fragment for the wide cards' ⋮ menu, share sheet and report flow. See {@link #fragment}. */
    public void setFragment(BaseFragment fragment) {
        this.fragment = fragment;
    }

    /**
     * Route paging through a custom endpoint (e.g. reels watch-history) instead of the two browse
     * pipes. One stream means there is no rhythm to compose, so such a grid shows its items in the
     * order the endpoint returned them.
     */
    public void setPageLoader(PageLoader loader) {
        this.pageLoader = loader;
    }

    /**
     * True when the grid is fed by the two independent pipes — the live explore feed AND a typed query,
     * which searches BOTH corpora (/v1/discover/search + /v1/videos/search) and composes them with the
     * same rhythm. Only an injected page loader is a single stream and takes the plain append path.
     *
     * <p>Search must be dual-stream for the same reason browse is: the two catalogs are disjoint
     * server-side (vertical vs landscape), so a search that asks only the shorts endpoint can never
     * return a single long-form video — which is exactly what it used to do.
     */
    private boolean dualStream() {
        return pageLoader == null;
    }

    /** The shelf both pipes are filtered on, or null on the unfiltered feed and on card shelves. */
    private String catSlug() {
        return selectedCategory == null ? null : selectedCategory.slug;
    }

    /** Any page in flight, on either pipe or on the single stream. */
    private boolean isLoading() {
        return loadingSingle || loadingShorts || loadingLongs;
    }

    /**
     * The single-stream fetch: the injected page loader (a history screen). Search does NOT come here —
     * it is dual-stream, because one search endpoint can only ever see one of the two disjoint corpora.
     */
    private void requestPage(int offset, int limit, boolean refresh, SvipeDiscover.Callback cb) {
        if (pageLoader != null) {
            pageLoader.load(offset, limit, refresh, cb);
        } else {
            // Unreachable: without a page loader the grid is dual-stream. Kept as a safe fallback rather
            // than a silent no-op that would look like a dead feed.
            SvipeDiscover.load(account, null, offset, limit, refresh, cb);
        }
    }

    /**
     * The picture of the cell that was tapped, in window coordinates. A wide card's poster is its
     * top 16:9 — the title row underneath is not part of what the player should grow out of.
     */
    private void rememberTapRect(View cell) {
        if (cell == null) {
            lastTapRect.setEmpty();
            return;
        }
        final int[] loc = new int[2];
        cell.getLocationInWindow(loc);
        final int w = cell.getWidth();
        final int posterHeight = Math.min(cell.getHeight(), Math.round(w * 9f / 16f));
        lastTapRect.set(loc[0], loc[1], loc[0] + w, loc[1] + posterHeight);
    }

    /** Hand the last tap's rect to whoever is about to open a player from it. */
    public boolean getLastTapRect(Rect out) {
        if (lastTapRect.isEmpty()) return false;
        out.set(lastTapRect);
        return true;
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
     *   <li>non-empty query → SEARCH OUR videos (debounced) via BOTH {@code /v1/discover/search} and
     *       {@code /v1/videos/search}, paged and composed independently;</li>
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
        if (searchActive) {
            return;
        }
        // Entering the tab is the one moment the strip may be re-ordered: nothing is being tapped and
        // the row is about to be laid out anyway.
        if (!categories.isEmpty()) {
            rebuildChipOrder();
            // The host may call this from inside a layout pass; RecyclerView refuses to be notified
            // there, so the rebind waits a frame rather than crashing the tab it is decorating.
            if (isComputingLayout()) {
                AndroidUtilities.runOnUIThread(adapter::notifyDataSetChanged);
            } else {
                adapter.notifyDataSetChanged();
            }
        }
        if (!startedFirstLoad) {
            startedFirstLoad = true;
            loadPage();
            return;
        }
        // Coming BACK to a shelf that died while the connection was down: try it again, without
        // making the user find the notice and tap it. Whatever killed it is usually over by now.
        if (loadFailed()) {
            retryShelf();
        }
    }

    /** Reload the browse grid if it is currently empty and nothing is in flight (used after a search). */
    private void ensureBrowseLoaded() {
        if (!searchActive && !isLoading() && items.isEmpty()) {
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
        // A typed search owns the whole surface (see showChips()): it is global, never scoped to the
        // chip that happened to be selected. Drop the shelf's page loader AND its span count, or the
        // query would be answered by /v1/movies and the 3-up composition would land in a 2-column grid.
        selectedCategory = null;
        pageLoader = null;
        layoutManager.setSpanCount(SPAN_COUNT);
        recentRows.clear();
        recentItems.clear();
        resetContent();
        loadPage();                  // dual-stream: both search endpoints, one per pipe
    }

    /** Clear the display, both pipes and all paging so the next loadPage starts fresh; older loads land stale. */
    private void resetContent() {
        contentSeq++;
        items.clear();
        shorts.clear();
        longs.clear();
        nextOffset = 0;
        shortsOffset = 0;
        longsOffset = 0;
        composedShorts = 0;
        composedLongs = 0;
        shortFailures = 0;
        longFailures = 0;
        loadingSingle = false;
        loadingShorts = false;
        loadingLongs = false;
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

    /**
     * True while the category chip strip occupies row 0. It shows in browse mode only — a typed
     * search and the recents view each own the whole surface — and it stays up during the skeleton so
     * the categories are tappable before the first page has landed.
     */
    private boolean showChips() {
        return !searchActive && !hasRecents() && !categories.isEmpty();
    }

    /**
     * Rows the grid spends on a header before the content starts. Chips and the "Recent" header are
     * mutually exclusive, so this is 0 or 1 — every list-offset computation must go through it, or a
     * cell would bind the item next to the one it draws.
     */
    private int headerRows() {
        return hasRecents() ? 1 : (showChips() ? 1 : 0);
    }

    /** The GridItem list currently backing the reel thumbnails: recents when up, else browse/search. */
    private List<GridItem> currentGrid() {
        return hasRecents() ? recentItems : items;
    }

    /**
     * True when the cell at this ADAPTER position is a full-width long-form card. Decided from the
     * GridItem's fixed {@code wide} flag (the pipe it came from, or the server-sent dimensions for a
     * single stream), so it is stable from the first layout pass — deriving it from the resolved
     * Telegram document instead would make cells change span/height as MTProto resolves, reflowing the
     * grid under the user's finger.
     */
    private boolean isWideAt(int position) {
        final int idx = position - headerRows();
        final List<GridItem> grid = currentGrid();
        if (idx < 0 || idx >= grid.size()) {
            return false;
        }
        return grid.get(idx).wide;
    }

    /**
     * Shimmer placeholders mirror the real rhythm (one full-width card, then whole 3-up rows) so the
     * skeleton doesn't re-flow into a different shape the moment the first page lands.
     */
    private static boolean isWideSkeleton(int position) {
        return position % (1 + SPAN_COUNT * SHORTS_ROWS_PER_LONG) == 0;
    }

    /** The adapter position a resolved GridItem should notify at, or -1 if it isn't the visible grid. */
    private int adapterPositionOf(GridItem gi) {
        final int idx = currentGrid().indexOf(gi);
        return idx < 0 ? -1 : headerRows() + idx;
    }

    /**
     * Nothing to show and nothing on its way — a committed search that found nothing, OR a category
     * shelf the server has no inventory for. Both must draw the notice: an empty grid with no notice
     * is a black void, which is exactly how a shelf with no content used to look (the chip strip on
     * top of bare window background, and not one pixel of content under it).
     */
    private boolean searchEmpty() {
        return !hasRecents() && items.isEmpty() && !isLoading() && !refreshing && loadAttempted;
    }

    /**
     * The empty screen is a FAILURE, not an answer — the pipes gave up without ever delivering.
     *
     * <p>This is the state the unfiltered feed used to render as nothing at all: chips on top of bare
     * window background, no notice, no spinner and no way to ask again short of killing the app. On a
     * bad connection that is where every shelf ended up.
     */
    private boolean loadFailed() {
        return searchEmpty() && !searchActive
                && (shortFailures > 0 || longFailures > 0 || singleFailures > 0);
    }

    /** The empty-state tap: forget the failures, un-retire the pipes, ask again. */
    private void retryShelf() {
        shortFailures = 0;
        longFailures = 0;
        singleFailures = 0;
        if (shortsOffset == null) {
            shortsOffset = 0;
        }
        if (longsOffset == null) {
            longsOffset = 0;
        }
        if (nextOffset == null) {
            nextOffset = 0;
        }
        adapter.notifyDataSetChanged();   // swap the notice for the shimmer straight away
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
                        && pullDistance >= PULL_THRESHOLD && !isLoading() && !refreshing;
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
     * Pull-to-refresh: fetch a fresh (server-rotated) page 0 of EVERY stream feeding the grid while
     * keeping the current content on screen under the spinner, then swap in one pass. The old content
     * is NOT cleared up-front — that clear-then-reload is what made the grid flash the skeleton (and
     * stale recycled thumbnails) and look like it "reverted". With refresh=1 the server rotates to a
     * different window, so the swap shows genuinely new content rather than the identical list.
     */
    private void triggerRefresh() {
        if (isLoading() || searchActive) {
            animatePullTo(0f);   // a page load is already in flight (or we're showing search results)
            return;
        }
        refreshing = true;
        startSpin();
        animatePullTo(PULL_THRESHOLD);   // settle at the resting position while loading
        if (dualStream()) {
            refreshBothPipes();
        } else {
            refreshSingleStream();
        }
    }

    /**
     * Refresh BOTH browse pipes and swap once. The two answers are collected in a {@link RefreshBatch}
     * so the grid re-lays out a single time — swapping per pipe would show a shorts-only grid for a
     * frame and then reflow when the long cards arrive. A pipe whose refresh FAILED keeps its existing
     * items and cursor: mixing fresh shorts with the long cards we already have beats throwing content
     * away.
     */
    private void refreshBothPipes() {
        final int seq = contentSeq;
        continueSlotSpin++;
        loadContinue(seq);
        final RefreshBatch batch = new RefreshBatch();
        loadingShorts = true;    // both flags also block scroll-pagination until the swap completes
        loadingLongs = true;
        SvipeDiscover.load(account, null, catSlug(), 0, PAGE_SIZE, true, (result, next, error) -> {
            loadingShorts = false;
            batch.shorts = result;
            batch.shortsNext = next;
            if (--batch.pending == 0) {
                applyRefresh(seq, batch);
            }
        });
        SvipeDiscover.videos(account, null, catSlug(), 0, LONG_PAGE_SIZE, true, (result, next, error) -> {
            loadingLongs = false;
            batch.longs = result;
            batch.longsNext = next;
            if (--batch.pending == 0) {
                applyRefresh(seq, batch);
            }
        });
    }

    /** The two refreshed page-0s, held until both have answered so the swap happens exactly once. */
    private static class RefreshBatch {
        int pending = 2;
        List<SvipeDiscover.Item> shorts;
        List<SvipeDiscover.Item> longs;
        Integer shortsNext;
        Integer longsNext;
    }

    private void applyRefresh(int seq, RefreshBatch batch) {
        finishRefresh();
        if (seq != contentSeq) {
            return;   // a search / mode change landed mid-refresh: that content owns the grid now
        }
        if (batch.shorts == null && batch.longs == null) {
            return;   // both failed: keep the current grid, never blank or revert
        }
        // Keep resolvedChats as a warm cache so channels that reappear keep their thumbnails.
        final ArrayList<GridItem> fresh = new ArrayList<>();
        if (batch.shorts != null) {
            shorts.clear();
            shortsOffset = batch.shortsNext;
            shortFailures = 0;
            fresh.addAll(fillPipe(shorts, batch.shorts, false));
        }
        if (batch.longs != null) {
            longs.clear();
            longsOffset = batch.longsNext;
            longFailures = 0;
            fresh.addAll(fillPipe(longs, batch.longs, true));
        }
        composeBrowse();
        adapter.notifyDataSetChanged();
        scrollToPosition(0);
        if (!fresh.isEmpty()) {
            resolveThumbnails(fresh);
        }
    }

    /** Single-stream refresh (an injected page loader): the same keep-then-swap, one endpoint. */
    private void refreshSingleStream() {
        loadingSingle = true;    // block scroll-pagination until the swap completes
        final int seq = contentSeq;
        requestPage(0, PAGE_SIZE, true, (result, next, error) -> {
            loadingSingle = false;
            finishRefresh();
            if (seq != contentSeq || result == null) {
                return;   // stale, or a network/auth failure: keep the current grid
            }
            items.clear();
            nextOffset = next;
            final ArrayList<GridItem> fresh = fillPipe(items, result, null);
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

    /** The shimmer placeholder grid is shown while the first page (initial or refresh) loads. */
    private boolean showingSkeleton() {
        return !hasRecents() && items.isEmpty() && (isLoading() || refreshing);
    }

    /** Ask for more content: both browse pipes, or the single stream behind search / a page loader. */
    private void loadPage() {
        loadAttempted = true;
        // Every content path funnels through here — ensureLoaded() does NOT, because the Search
        // section it lives in is permanently in search mode and returns early from it. loadCategories()
        // self-guards, so calling it on every page is free after the first.
        loadCategories();
        if (dualStream()) {
            loadBothPipes();
        } else {
            loadSinglePage();
        }
    }

    // ---- browse mode: two independent pipes + client-side composition ----

    /**
     * Page whichever pipe is running low. Each is gated on its own in-flight flag and its own cursor, so
     * the shorts keep flowing while the long-form pipe is slow, exhausted or dead — and vice versa.
     * "Low" is measured on the BUFFER (what the last composition did not consume), because that is what
     * the composer will need next, not on the pipe's total length.
     */
    private void loadBothPipes() {
        final int seq = contentSeq;   // pin these requests to the current browse content
        final boolean wasIdle = !isLoading();
        if (continueCard == null && shorts.isEmpty() && longs.isEmpty()) {
            loadContinue(seq);        // first browse load: ask what is unfinished, once
        }
        // First browse load of the session: the app-start warm-up may already hold both page-0s.
        // They go through the ordinary page path, so composition, cursors and failure handling are
        // identical to a live fetch — the only difference is that nothing was waited for.
        if (!warmTried && !searchActive && pageLoader == null && selectedCategory == null
                && shorts.isEmpty() && longs.isEmpty() && !loadingShorts && !loadingLongs) {
            warmTried = true;
            SvipeVideoWarmer.Warm warm = SvipeVideoWarmer.take();
            if (warm != null) {
                onPipePage(seq, false, warm.shorts, warm.shortsNext);
                onPipePage(seq, true, warm.longs, warm.longsNext);
                return;
            }
        }
        if (!loadingShorts && shortsOffset != null
                && shorts.size() - composedShorts < SPAN_COUNT * SHORTS_ROWS_PER_LONG * PREFETCH_UNITS) {
            loadingShorts = true;
            final int offset = shortsOffset;
            // A typed query swaps this pipe's SOURCE, never its plumbing: same cursor, same failure
            // counter, same composition. Browse and search are two sources for one pipe, not two modes.
            if (searchActive) {
                SvipeDiscover.search(account, activeQuery, offset, PAGE_SIZE,
                        (result, next, error) -> onPipePage(seq, false, result, next));
            } else {
                SvipeDiscover.load(account, null, catSlug(), offset, PAGE_SIZE, false,
                        (result, next, error) -> onPipePage(seq, false, result, next));
            }
        }
        if (!loadingLongs && longsOffset != null && longs.size() - composedLongs < PREFETCH_UNITS) {
            loadingLongs = true;
            final int offset = longsOffset;
            // The long corpus is disjoint from the shorts one server-side, so it needs its OWN query
            // endpoint — /v1/discover/search physically cannot return a landscape video.
            if (searchActive) {
                SvipeDiscover.videosSearch(account, activeQuery, offset, LONG_PAGE_SIZE,
                        (result, next, error) -> onPipePage(seq, true, result, next));
            } else {
                SvipeDiscover.videos(account, null, catSlug(), offset, LONG_PAGE_SIZE, false,
                        (result, next, error) -> onPipePage(seq, true, result, next));
            }
        }
        // Reveal the shimmer for a FIRST page only — i.e. the grid was idle and empty, so it had nothing
        // on screen and cannot be mid-scroll. Notifying the adapter from inside a scroll callback (this
        // method is also reached from onScrolled) is exactly what RecyclerView refuses to do.
        if (wasIdle && isLoading() && items.isEmpty()) {
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * One pipe's page landed. Appends it to that pipe and recomposes the display.
     *
     * <p>The first composition deliberately waits for BOTH pipes to settle: composing a lone shorts page
     * would paint a pure 3-up grid and then splice long cards into it a moment later, reflowing the
     * whole screen. Later pages compose immediately — by then the rhythm is established and composition
     * only appends.
     *
     * <p>A failure or an empty page is not a dead end. The composer holds a slot open for a pipe that is
     * merely behind, so a pipe that is actually BROKEN has to be recognised as done, or the grid would
     * sit on a slot that never fills — that is what the failure cap and the empty-page retirement below
     * are for, and it is why a dead /v1/videos ends up as a plain shorts grid rather than a blank screen.
     */
    private void onPipePage(int seq, boolean longPipe, List<SvipeDiscover.Item> result, Integer next) {
        if (seq != contentSeq) {
            return;   // mode / query changed under us (or a refresh reset the pipes) — drop this page
        }
        final boolean wasSkeleton = showingSkeleton();
        if (longPipe) {
            loadingLongs = false;
        } else {
            loadingShorts = false;
        }
        ArrayList<GridItem> fresh = null;
        if (result == null) {
            // Failed page: keep the cursor so a retry is still possible, but count the failure. At the
            // cap the long-form pipe is retired outright — a server that has no /v1/videos fails on
            // every single call, and the grid must stop waiting for cards that will never come.
            if (longPipe) {
                if (++longFailures >= MAX_PIPE_FAILURES) {
                    longsOffset = null;
                }
            } else {
                shortFailures++;
            }
        } else if (longPipe && result.isEmpty()) {
            // An empty long page means there is nothing more to place, whatever next_offset says —
            // retire the pipe or the composer would hold a card slot open for a card that never comes.
            longsOffset = null;
            longFailures = 0;
        } else {
            if (longPipe) {
                longsOffset = next;
                longFailures = 0;
            } else {
                shortsOffset = next;
                shortFailures = 0;
            }
            fresh = fillPipe(longPipe ? longs : shorts, result, longPipe);
            // Resolve thumbnails off the PIPE, not the display: a page buffered behind the skeleton (or
            // held back for an incomplete row) warms up meanwhile, so it paints filled when composed.
            resolveThumbnails(fresh);
        }
        if (items.isEmpty() && isLoading()) {
            return;   // nothing on screen yet and the other pipe may still deliver — keep the skeleton
        }
        recomposeBrowse(wasSkeleton);
        // Self-heal: while the grid is too shallow to scroll it cannot page itself, so top it up here.
        // A page that delivered always qualifies; a failed page qualifies only while that pipe is under
        // its failure cap, which is what keeps an outage from spinning requests as fast as they fail.
        final boolean retryable = fresh != null
                || (longPipe ? longFailures : shortFailures) < MAX_PIPE_FAILURES;
        if (retryable && items.size() < MIN_COMPOSED_CELLS) {
            loadBothPipes();
        }
    }

    /**
     * Rebuild the display list from the two pipes and tell the adapter as cheaply as possible.
     * Composition is append-only while the pipes only grow, so the previous display is normally a
     * PREFIX of the new one and a range-insert keeps the scroll position; anything else (a removal, a
     * refresh, the skeleton being replaced) needs a full rebind.
     */
    private void recomposeBrowse(boolean wasSkeleton) {
        final ArrayList<GridItem> before = new ArrayList<>(items);
        composeBrowse();
        if (hasRecents()) {
            // Browse loaded BEHIND the recents view — staged silently; it renders when the recents view
            // is dismissed (that transition does a full notify).
            return;
        }
        if (!wasSkeleton && !before.isEmpty() && isPrefixOf(before, items)) {
            final int added = items.size() - before.size();
            if (added > 0) {
                adapter.notifyItemRangeInserted(recentHeaderRows() + before.size(), added);
            }
            return;
        }
        adapter.notifyDataSetChanged();
        // The visible shelf has content: now there is room to have the next one ready.
        schedulePrefetch();
    }

    /**
     * Compose the DISPLAY list: one long-form card, then {@link #SHORTS_ROWS_PER_LONG} COMPLETE rows of
     * {@link #SPAN_COUNT} shorts, repeating. This is where row alignment is won — a row is emitted only
     * once SPAN_COUNT shorts are in hand, so the grid physically cannot contain a half-filled row.
     *
     * <p>A pipe that is merely BEHIND (dry but still pageable) stops composition rather than being
     * skipped, so the rhythm survives a slow page instead of degenerating into a long run of one shape.
     * A pipe that is DONE (exhausted or retired) is composed around: with no long cards left the rest of
     * the shorts fill the screen as a plain 3-up grid, and with no shorts left the long cards continue
     * on their own. The leftover shorts that never made a full row are appended only when NOTHING more
     * can arrive — holding them back keeps every earlier row whole and keeps the display append-only
     * (see {@link #recomposeBrowse}), and once both pipes are done the tail can no longer move.
     */
    /**
     * Ask what this viewer left unfinished and pin it near the top.
     *
     * <p>Best-effort in every direction: no card when there is nothing to continue, when the call
     * fails, or when the answer arrives after the user has moved on ({@code seq} guard). The card is
     * an ordinary stacked playlist card — the show it belongs to, opened at the episode and second
     * the server named.
     */
    private void loadContinue(final int seq) {
        org.telegram.svipe.SvipeMovies.continueWatching(account, (offer, error) -> {
            if (seq != contentSeq) {
                return;
            }
            if (offer == null) {
                continueCard = null;
            } else if (offer.series != null) {
                continueCard = new GridItem(org.telegram.svipe.SvipeMovies.SeriesRef.of(offer.series));
            } else {
                // A lone video: the ordinary full-width card, with the "carry on" line under it.
                continueCard = new GridItem(offer.video, true);
            }
            continueKey = continueCard == null ? null
                    : continueCard.ref.channelId + ":" + continueCard.ref.messageId;
            continueProgress = offer == null ? 0f : offer.progress;
            if (continueCard != null) {
                // It is not part of either pipe, so nothing else would ever resolve it — and an
                // unresolved card has no title, no channel, no views and no picture.
                final ArrayList<GridItem> one = new ArrayList<>(1);
                one.add(continueCard);
                resolveThumbnails(one);
            }
            if (!items.isEmpty() || continueCard != null) {
                composeBrowse();
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void composeBrowse() {
        items.clear();
        int si = 0;
        int li = 0;
        // The offer is placed as one of the LONG cards, never as an extra row: the page owes the eye
        // one wide card and three tiles per round, and a card squeezed in between broke that rhythm.
        // Which round it lands in moves with the refresh, so a pull-down visibly changes the page.
        final int continueRound = continueCard == null ? -1 : (continueSlotSpin % 2);
        int round = 0;
        while (true) {
            boolean placed = false;
            if (round == continueRound) {
                items.add(continueCard);       // this round's wide card IS the offer
                placed = true;
            } else if (li < longs.size()) {
                items.add(longs.get(li++));
                placed = true;
            } else if (longsOffset != null) {
                break;   // more long cards are on their way: leave the slot for them
            }
            boolean rowShort = false;
            for (int row = 0; row < SHORTS_ROWS_PER_LONG; row++) {
                if (shorts.size() - si < SPAN_COUNT) {
                    rowShort = true;
                    break;
                }
                for (int k = 0; k < SPAN_COUNT; k++) {
                    final GridItem tile = shorts.get(si++);
                    tile.tileColumn = k;
                    items.add(tile);
                }
                placed = true;
            }
            if (rowShort && shortsOffset != null) {
                break;   // the next row is incomplete but still fillable: wait for the page
            }
            if (!placed) {
                break;   // both pipes are done and gave nothing this round
            }
            round++;
        }
        if (shortsOffset == null && longsOffset == null) {
            int tail = 0;
            while (si < shorts.size()) {
                final GridItem tile = shorts.get(si++);
                tile.tileColumn = tail++ % SPAN_COUNT;
                items.add(tile);
            }
        }
        composedShorts = si;
        composedLongs = li;
    }

    // ---- single stream (search results / injected page loader) ----

    /** Append the next page of the one stream in play, in the order the endpoint returned it. */
    private void loadSinglePage() {
        if (loadingSingle || nextOffset == null) {
            return;
        }
        loadingSingle = true;
        if (items.isEmpty()) {
            adapter.notifyDataSetChanged();   // reveal the skeleton grid while the first page loads
        }
        final int offset = nextOffset;
        final int seq = contentSeq;             // pin this request to the current search/history content
        requestPage(offset, PAGE_SIZE, false, (result, next, error) -> {
            if (seq != contentSeq) {
                return;   // mode / query changed under us — this page is stale, drop it
            }
            final boolean wasSkeleton = showingSkeleton();
            loadingSingle = false;
            if (refreshing) {
                finishRefresh();
            }
            if (result == null) {
                // Failed load: drop the skeleton placeholders and put up the notice, which on a
                // network failure is also the retry.
                singleFailures++;
                adapter.notifyDataSetChanged();
                return;
            }
            singleFailures = 0;
            nextOffset = next;
            final int before = items.size();
            final ArrayList<GridItem> fresh = fillPipe(items, result, null);
            if (hasRecents()) {
                // Search loaded BEHIND the recents view — stage the data silently; it renders when the
                // recents view is dismissed (that transition does a full notify).
            } else if (wasSkeleton || before == 0) {
                // The item count changes wholesale (skeleton/empty -> real size), so a full rebind.
                adapter.notifyDataSetChanged();
            } else if (!fresh.isEmpty()) {
                adapter.notifyItemRangeInserted(headerRows() + before, fresh.size());
            }
            resolveThumbnails(fresh);
        });
    }

    /**
     * Wrap references in GridItems, append them to {@code target} and return the new ones so their
     * thumbnails can be resolved. {@code wide} is the browse pipe's orientation, or null for a single
     * stream, where the cell shape comes from the reference's own dimensions.
     */
    private ArrayList<GridItem> fillPipe(ArrayList<GridItem> target, List<SvipeDiscover.Item> refs, Boolean wide) {
        final ArrayList<GridItem> fresh = new ArrayList<>(refs.size());
        for (SvipeDiscover.Item ref : refs) {
            // Dropped HERE, as the page arrives, and never during composition: the layout owes the
            // page one long card and three tiles per round, and pulling an item out mid-round leaves
            // a row of two. The pipe is what may be short, never the rhythm.
            if (continueKey != null && continueKey.equals(ref.channelId + ":" + ref.messageId)) {
                continue;
            }
            final GridItem gi = wide == null ? new GridItem(ref) : new GridItem(ref, wide);
            target.add(gi);
            fresh.add(gi);
        }
        return fresh;
    }

    /** True when {@code a} is element-for-element the first {@code a.size()} entries of {@code b}. */
    private static boolean isPrefixOf(List<GridItem> a, List<GridItem> b) {
        if (a.size() > b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
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

        // A grid page is a dozen different channels and every poster comes off the Telegram message,
        // so this is the second-biggest source of contacts.resolveUsername after the watch page —
        // and when it is flood-limited the grid draws a wall of blank cards. Ask what the device
        // already knows first, and never fire into an open flood window. See SvipeChannelResolve.
        org.telegram.svipe.SvipeChannelResolve.lookup(account, channelId, local -> {
            if (local != null) {
                resolvedChats.put(username, local);
                fetchMessagesForGroup(local, group);
                return;
            }
            // A channel we cannot address yet: take each poster off the post's own public link
            // instead of buying the channel. One messages.getWebPage per card is more CALLS than one
            // resolve per channel, but they are calls on a bucket that does not flood — 30 of them
            // measured 0.9 s, while the 30 resolves they replace are what closes the whole app down
            // for half an hour. See SvipeWebRef.
            webPageGroup(account, username, channelId, group);
        });
    }

    /** Posters through the link-preview path — no channel, no resolveUsername. */
    private void webPageGroup(final int account, final String username, final long channelId,
                              final ArrayList<GridItem> group) {
        final int[] pending = {group.size()};
        final ArrayList<GridItem> missed = new ArrayList<>();
        for (GridItem gi : group) {
            final GridItem item = gi;
            org.telegram.svipe.video.SvipeWebRef.fetch(account, username, item.ref.messageId,
                    channelId, (mo, page) -> {
                item.resolving = false;
                if (mo == null || mo.getDocument() == null) {
                    // No preview for this post. Collect it: if the whole group came back empty the
                    // channel is worth ONE resolve, rather than a column of permanently blank cards.
                    // No preview: hand it to the channel path, which can tell "deleted" from
                    // "not previewable" — and reports the dead ones.
                    missed.add(item);
                    if (--pending[0] == 0) {
                        sendResolveForGroup(account, username, channelId, missed);
                    }
                    return;
                }
                item.mo = mo;
                item.resolved = true;
                org.telegram.svipe.SvipeObserved.note(account, channelId, item.ref.messageId, mo);
                final int pos = adapterPositionOf(item);
                if (pos >= 0) {
                    adapter.notifyItemChanged(pos);
                }
                if (--pending[0] == 0 && !missed.isEmpty()) {
                    sendResolveForGroup(account, username, channelId, missed);
                }
            });
        }
    }

    private void sendResolveForGroup(final int account, final String username, final long channelId,
                                     final ArrayList<GridItem> group) {
        final MessagesController mc = MessagesController.getInstance(account);
        final ConnectionsManager cm = ConnectionsManager.getInstance(account);
        if (org.telegram.svipe.SvipeChannelResolve.blocked(account)
                || org.telegram.svipe.SvipeChannelResolve.exhausted(account)) {
            for (GridItem gi : group) {
                gi.resolving = false;
            }
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        // A grid page is a dozen channels at once — exactly the burst Telegram floods on. Paced,
        // and behind the player: a poster can arrive a second late, a tapped video cannot.
        org.telegram.svipe.SvipeChannelResolve.pace(false, () -> {
        org.telegram.svipe.SvipeChannelResolve.spend(account);
        cm.sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            org.telegram.svipe.SvipeChannelResolve.sent();
            if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                org.telegram.messenger.FileLog.d("svipe: grid resolve @" + username + " failed: "
                        + (error == null ? "shape" : error.text));
                org.telegram.svipe.SvipeChannelResolve.noteError(account, error);
                for (GridItem gi : group) {
                    gi.resolving = false;
                }
                if (isChannelDead(error)) {
                    // The HANDLE itself is gone — a renamed channel, a deleted one, one that went
                    // private. Nothing about these references can ever resolve, so the cards stop
                    // pretending to load: they leave the grid and the server is told, exactly as a
                    // deleted post is. That endless shimmer was this case, unhandled.
                    final ArrayList<SvipeDiscover.Item> dead = new ArrayList<>();
                    for (GridItem gi : group) {
                        dead.add(gi.ref);
                    }
                    for (SvipeDiscover.Item ref : dead) {
                        org.telegram.svipe.SvipeObserved.noteGone(account, ref.channelId, ref.messageId);
                        removeRef(ref);
                    }
                }
                return;
            }
            TLRPC.TL_contacts_resolvedPeer rp = (TLRPC.TL_contacts_resolvedPeer) response;
            // Persisted, so tomorrow's cold start draws this grid without a single resolve.
            org.telegram.svipe.SvipeChannelResolve.remember(account, rp);
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
        });
    }

    /**
     * True when the error says the REFERENCE can never work again, as opposed to "not right now".
     * A handle that no longer exists is the quiet one: nothing retries it and nothing removes it, so
     * the tile shimmers for as long as the app is open.
     */
    private static boolean isChannelDead(TLRPC.TL_error error) {
        if (error == null || error.text == null) {
            return false;
        }
        final String t = error.text;
        return t.contains("USERNAME_NOT_OCCUPIED") || t.contains("USERNAME_INVALID")
                || t.contains("CHANNEL_PRIVATE") || t.contains("CHANNEL_INVALID")
                || t.contains("PEER_ID_INVALID");
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
            final ArrayList<SvipeDiscover.Item> dead = new ArrayList<>();
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
                } else {
                    // The channel answered and this post was NOT in the answer (deleted, or no longer
                    // a video). That is a fact, not a network blip — the tile can never fill, so it
                    // leaves the grid instead of spinning forever, and the server is told so the next
                    // person is not served the same hole.
                    dead.add(gi.ref);
                }
            }
            for (SvipeDiscover.Item ref : dead) {
                org.telegram.svipe.SvipeObserved.noteGone(account, ref.channelId, ref.messageId);
                removeRef(ref);
            }
        }));
    }

    // ---- adapter / cell ----

    private class GridAdapter extends SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int type = holder.getItemViewType();
            return type == TYPE_PHOTO || type == TYPE_PHOTO_WIDE || type == TYPE_EMPTY;
        }

        @Override
        public int getItemViewType(int position) {
            if (position < headerRows()) {
                return hasRecents() ? TYPE_RECENT_HEADER : TYPE_CATEGORY_CHIPS;
            }
            if (showingSkeleton()) {
                return isWideSkeleton(position - headerRows()) ? TYPE_SKELETON_WIDE : TYPE_SKELETON;
            }
            if (searchEmpty()) {
                return TYPE_EMPTY;
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
                case TYPE_CATEGORY_CHIPS: {
                    SvipeCategoryChips chips = new SvipeCategoryChips(ctx);
                    chips.setCategories(chipOrder,
                            selectedCategory == null ? null : selectedCategory.slug);
                    chips.setDelegate(SvipeExploreGrid.this::selectCategory);
                    view = chips;
                    break;
                }
                case TYPE_EMPTY:
                    view = createEmptyView(ctx);
                    break;
                case TYPE_PHOTO_WIDE:
                    SvipeWideVideoCell wide = new SvipeWideVideoCell(ctx, account);
                    wide.setDelegate(cellDelegate);
                    view = wide;
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
            if (holder.getItemViewType() == TYPE_CATEGORY_CHIPS) {
                final SvipeCategoryChips chips = (SvipeCategoryChips) holder.itemView;
                final String selected = selectedCategory == null ? null : selectedCategory.slug;
                // Rebuild ONLY when the order actually changed; otherwise move the highlight and leave
                // the strip's scroll position alone.
                if (chips.shows(chipOrder)) {
                    chips.setSelectedSlug(selected);
                } else {
                    chips.setCategories(chipOrder, selected);
                }
                return;
            }
            final int type = holder.getItemViewType();
            if (type == TYPE_EMPTY) {
                // Two different nothings: a search that found nothing vs a shelf with no content yet.
                ((TextView) holder.itemView).setText(LocaleController.getString(
                        loadFailed() ? R.string.SvipeVideoOffline
                                : (searchActive ? R.string.NoResult : R.string.SvipeVideoCategoryEmpty)));
                return;
            }
            if (type != TYPE_PHOTO && type != TYPE_PHOTO_WIDE) {
                return;   // skeleton / chips / recent header self-render, nothing to bind
            }
            final GridItem gi = currentGrid().get(position - headerRows());
            if (type == TYPE_PHOTO_WIDE) {
                // EVERY card on this tab is the same card, whichever chip it was found under: channel
                // avatar, channel name, views, age, and two lines of the post's own caption.
                //
                // Film shelves used to override both text lines with the catalog's title and
                // "year · ★ rating". It read as a different screen from the tab it was opened from —
                // no channel, no view count, no "a day ago" — and that difference is exactly what the
                // chip strip must NOT have: a chip filters the feed, it does not change what a row is.
                // The film's own title and rating still exist where they belong: on its page.
                final SvipeMovies.Series show = gi.series();
                // "Where you stopped" is drawn ON the picture as a progress bar, so the text lines
                // stay what they are everywhere else.
                final boolean carryOn = (show != null && show.resumeIndex >= 0)
                        || (continueKey != null && gi.ref != null
                            && continueKey.equals(gi.ref.channelId + ":" + gi.ref.messageId));
                ((SvipeWideVideoCell) holder.itemView).bind(gi.ref, gi.mo, chatHintFor(gi),
                        null, null, carryOn ? continueProgress : 0f);
                return;
            }
            PortraitImageView iv = (PortraitImageView) holder.itemView;
            // Column position drives the outer margins, so a row of three lines up with the wide card.
            // Asked of the layout manager rather than derived from the index: a wide card eats three
            // spans, so position % 3 says nothing about which column a tile actually lands in.
            // The composer stamped the column when it laid the row out; a single-stream list (search,
            // a shelf) is a plain 3-up grid, so its index does say which column an item is in.
            final int col = gi.tileColumn >= 0 ? gi.tileColumn
                    : (position - headerRows()) % SPAN_COUNT;
            iv.setEdges(col == 0, col == SPAN_COUNT - 1);
            SvipeWideVideoCell.bindThumb(iv, gi.mo, false, gi.ref);
        }

        @Override
        public int getItemCount() {
            final int header = headerRows();
            if (showingSkeleton()) {
                return header + SKELETON_COUNT;
            }
            if (searchEmpty()) {
                return header + 1;
            }
            return header + currentGrid().size();
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

    /**
     * The single centred notice shown when a single stream came back with nothing — a committed
     * search or a category shelf. The wording is set in {@code onBindViewHolder}, because the same
     * view serves both and only the bind knows which one is on screen.
     */
    private View createEmptyView(Context context) {
        TextView tv = new TextView(context);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(48), AndroidUtilities.dp(20), AndroidUtilities.dp(20));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        // Tappable on purpose: when the reason is the network, the notice IS the retry button. A dead
        // end with no way back is what this whole state exists to replace.
        tv.setOnClickListener(v -> {
            if (loadFailed()) {
                retryShelf();
            }
        });
        return tv;
    }

    /**
     * Portrait cell (3:2). Shows the shimmer placeholder until the Telegram thumbnail bitmap is
     * available — so a cell never flashes black while /v1/discover items are resolving their thumbs.
     */
    /**
     * A short's tile. Rounded and spaced like the wide card above it, in proportion: a third of the
     * width means a third of the radius and half the gap, so the two shapes read as one system rather
     * than a card sitting on a mosaic. The inset is drawn INSIDE the view — the grid's span layout
     * owns the outer geometry, and an item decoration would have to fight it.
     */
    private static class PortraitImageView extends BackupImageView {
        private final SvipeWideVideoCell.Shimmer shimmer = new SvipeWideVideoCell.Shimmer();
        private final RectF rect = new RectF();
        /** Half of the gap between two tiles; the outer edge gets the card's own margin. */
        static final int TILE_GAP_DP = 2;
        private boolean firstInRow;
        private boolean lastInRow;

        PortraitImageView(Context context) {
            super(context);
            setRoundRadius(AndroidUtilities.dp(8));
        }

        /** Which column this tile sits in, so the row lines up with the card's 12dp margins. */
        void setEdges(boolean first, boolean last) {
            if (first != firstInRow || last != lastInRow || getPaddingTop() == 0) {
                firstInRow = first;
                lastInRow = last;
                // Applied HERE, not in onMeasure: padding set during a measure pass only takes effect
                // on the NEXT one, so every tile drew the previous tile's margins — which is why the
                // row looked like the gaps had been assigned at random.
                final int outer = AndroidUtilities.dp(SvipeWideVideoCell.CARD_SIDE_MARGIN_DP);
                final int gap = AndroidUtilities.dp(TILE_GAP_DP);
                setPadding(first ? outer : gap, gap, last ? outer : gap, gap);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int height = Math.round(width * 3f / 2f);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // The picture has to be told to live inside the padding — BackupImageView otherwise sets
            // the image coords to the whole view on every draw and paints straight over the gaps,
            // which is why only the tiles with NO image looked spaced.
            final int innerW = getWidth() - getPaddingLeft() - getPaddingRight();
            final int innerH = getHeight() - getPaddingTop() - getPaddingBottom();
            drawFromStart = true;
            setSize(innerW, innerH);
            if (!getImageReceiver().hasBitmapImage()) {
                final float inset = AndroidUtilities.dp(1);
                rect.set(getPaddingLeft() + inset, getPaddingTop() + inset,
                        getWidth() - getPaddingRight() - inset,
                        getHeight() - getPaddingBottom() - inset);
                shimmer.draw(canvas, rect, AndroidUtilities.dp(8), this);
            }
            canvas.save();
            canvas.translate(getPaddingLeft(), getPaddingTop());
            super.onDraw(canvas);
            canvas.restore();
        }
    }

    /**
     * The channel this grid resolved for a reference's @username, handed to the cell as a fallback for
     * the MessagesController lookup: {@link #resolvedChats} is this grid's own warm cache and survives
     * a refresh, so a channel keeps its avatar even if the controller has since dropped the chat.
     */
    private TLRPC.Chat chatHintFor(GridItem gi) {
        if (gi.ref == null || gi.ref.username == null) {
            return null;
        }
        return resolvedChats.get(gi.ref.username.toLowerCase());
    }

    /**
     * Drop one reference from the grid (a "not interested" tap should make it disappear). Removal now
     * happens on the SOURCES — both pipes plus the recents — and the display is simply recomposed, which
     * is what keeps the rows whole: a tile leaving a 3-up row no longer punches a hole in it, the
     * remaining shorts just shift up and the leftover waits for the next page.
     */
    private void removeRef(SvipeDiscover.Item ref) {
        boolean changed = dropWhere(shorts, gi -> gi.ref == ref);
        changed |= dropWhere(longs, gi -> gi.ref == ref);
        changed |= dropWhere(items, gi -> gi.ref == ref);
        changed |= dropWhere(recentItems, gi -> gi.ref == ref);
        if (changed) {
            recentRows.remove(ref);
            afterRemoval();
        }
    }

    /**
     * Drop every reference from a channel the user just blocked — from both browse pipes, the search
     * list AND the recents, since any of them can be the list on screen when the menu is used.
     */
    private void removeChannel(long channelId) {
        final GridItemFilter sameChannel = gi -> gi.ref != null && gi.ref.channelId == channelId;
        boolean changed = dropWhere(shorts, sameChannel);
        changed |= dropWhere(longs, sameChannel);
        changed |= dropWhere(items, sameChannel);
        changed |= dropWhere(recentItems, sameChannel);
        if (changed) {
            for (int i = recentRows.size() - 1; i >= 0; i--) {
                if (recentRows.get(i).channelId == channelId) {
                    history.remove(recentRows.get(i));
                    recentRows.remove(i);
                }
            }
            afterRemoval();
        }
    }

    /**
     * Re-render after a removal. Browse recomposes from the pruned pipes; a single stream was pruned in
     * place. Either way the display shrank, so it is a full rebind, and the browse grid may now be short
     * enough to need another page.
     */
    private void afterRemoval() {
        if (dualStream()) {
            composeBrowse();
        }
        adapter.notifyDataSetChanged();
        if (dualStream() && items.size() < MIN_COMPOSED_CELLS) {
            loadBothPipes();
        }
    }

    private interface GridItemFilter {
        boolean matches(GridItem gi);
    }

    /** Prune one list in place — a ⋮ removal has to reach every list a reference can be sitting in. */
    private static boolean dropWhere(ArrayList<GridItem> list, GridItemFilter f) {
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (f.matches(list.get(i))) {
                list.remove(i);
                changed = true;
            }
        }
        return changed;
    }

    /** No-data placeholder cell: pure shimmer, shown while {@code items} is still empty. */
    private static class SkeletonCell extends View {
        private final SvipeWideVideoCell.Shimmer shimmer = new SvipeWideVideoCell.Shimmer();
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

}
