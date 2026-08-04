package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.svipe.SvipeArtistFavourite;
import org.telegram.svipe.SvipeArtistFavouritesSet;
import org.telegram.svipe.SvipeFavourite;
import org.telegram.svipe.SvipeFavouritesSet;
import org.telegram.svipe.SvipeMusic;
import org.telegram.svipe.SvipeMusicQueue;
import org.telegram.svipe.SvipeMusicResolver;
import org.telegram.svipe.SvipeMusicWarmer;
import org.telegram.svipe.SvipeMusicSearchHistory;
import org.telegram.svipe.SvipeMusicTelemetry;
import org.telegram.svipe.SvipeSearchLog;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.SharedAudioCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.FragmentContextView;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FragmentSearchField;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScrollSlidingTextTabStrip;
import org.telegram.ui.Components.ViewPagerFixed;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.blur3.utils.Blur3Utils;
import org.telegram.ui.Components.chat.ViewPositionWatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The "Music" main tab: a Yandex-Music-style catalog over audio tracks indexed from Telegram music
 * channels by the Svipe backend. The backend serves references + metadata only; tapping a row
 * resolves the underlying channel messages via MTProto and plays them through Telegram's own
 * MediaController (streaming, caching, lock-screen controls all come from the platform).
 * "My Vibe" is an endless personalized queue that auto-extends as playback nears its tail.
 */
public class MusicActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private static final int ROW_SECTION = 1;
    private static final int ROW_TRACK = 2;
    private static final int ROW_LOADING = 3;
    private static final int ROW_EMPTY = 4;
    private static final int ROW_RETRY = 5;
    private static final int ROW_SONG = 6;      // canonical song card — fallback when the default version can't resolve
    private static final int ROW_SONG_AUDIO = 7; // canonical song as a native SharedAudioCell (default version resolved)
    // Rows of the pinned favourites panel (its own inner list, never mixed with search rows).
    private static final int ROW_FAV_AUDIO = 8;  // resolved to a real message -> native SharedAudioCell
    private static final int ROW_FAV_CARD = 9;   // not resolvable here (private source) -> lightweight card
    private static final int ROW_FAV_EMPTY = 10; // shared by both tabs; only the sentence differs
    private static final int ROW_FAV_ARTIST = 11; // a favourite singer -> native UserCell (profile person row)
    private static final int ROW_ARTIST = 12;     // a canonical artist search result -> rounded-SQUARE cell
    // 13 was ROW_RECENT (a recent-search QUERY row); recents now reuse the ROW_SONG / ROW_ARTIST cells.
    private static final int ROW_RECENT_HEADER = 14; // "Recent" + a "clear all" action
    private static final int ROW_RECENT_EMPTY = 15;  // "No recent searches" centred sentence
    private static final int ROW_ML_SONG = 16;    // a "most listened" song row (cover + listen-time label)
    private static final int ROW_FAV_LOADING = 17; // spinner while the most-listened page loads

    /** The panel's tabs. Songs is index 0 and is what the panel opens on. */
    private static final int FAV_TAB_SONGS = 0;
    private static final int FAV_TAB_SINGERS = 1;
    private static final int FAV_TAB_MOST_LISTENED = 2;

    private static final int SEARCH_MIN_CHARS = 2;
    private static final int SEARCH_PAGE = 50;
    private static final int ARTIST_SEARCH_LIMIT = 6;   // fetched; only MAX_ARTIST_ROWS are shown
    private static final int MAX_ARTIST_ROWS = 3;       // interleaved above the song results
    private static final int MOST_LISTENED_PAGE = 50;
    private static final int PLAY_WINDOW = 60;

    private boolean hasMainTabs;
    private int additionNavigationBarHeight;

    private FrameLayout root;
    private EditTextBoldCursor searchField;   // == fragmentSearchField.editText
    private FragmentSearchField fragmentSearchField;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private ListAdapter adapter;
    private VibeScreen vibeScreen;
    private FragmentContextView fragmentContextView;
    private FrameLayout contextWrap;

    /* "Home" is the vibe hero with the favourites panel pulled up over it. The panel rests
     * peeking above the bottom tabs and is dragged (or tapped) up until its tab strip meets the
     * now-playing bar; releasing past a threshold snaps it the rest of the way. Its travel drives
     * everything else: the hero recedes (scales down + fades) instead of scrolling away, and the
     * favourites list fades in from nothing.
     *
     * The panel holds three sub-tabs (Songs / Singers / Most listened) over a {@link ViewPagerFixed}:
     * the strip and the pager are two views of the same selection. Swiping the pager drags the pages
     * with the finger and snaps to the nearest neighbour on release, and the strip's underline tracks
     * that drag; tapping a tab animates the pager to it. {@link #favTab} just mirrors the visible page. */
    private FavouritesPanel favPanel;
    private int favTab = FAV_TAB_SONGS;
    private final ArrayList<SvipeFavourite> favourites = new ArrayList<>();
    private final ArrayList<SvipeArtistFavourite> artistFavourites = new ArrayList<>();
    // Favourite key -> its entry in the CURRENT favQueue, so rows render as native audio cells.
    private final HashMap<String, MessageObject> favMo = new HashMap<>();
    // Track.key() -> resolved channel message, kept across rebuilds so re-forming the queue is free.
    private final HashMap<String, TLRPC.Message> favResolvedMsgs = new HashMap<>();
    private SvipeMusicQueue favQueue;
    private boolean favResolving;

    /* Liquid glass (iBlur3) — the same pipeline DialogsActivity uses to frost its search pill and top
     * panel. When LiteMode liquid glass is enabled (S+), the search pill and the now-playing island
     * render real frosted glass over whatever is scrolling behind them; otherwise they keep the plain
     * solid look. */
    private ViewPositionWatcher viewPositionWatcher;
    private DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor;
    private BlurredBackgroundSourceRenderNode iBlur3SourceGlass;
    private BlurredBackgroundSourceColor iBlur3SourceColor;
    private BlurredBackgroundDrawableViewFactory iBlur3FactoryLiquidGlass;
    private IBlur3Capture iBlur3VibeCapture;
    private IBlur3Capture iBlur3PanelCapture;
    private IBlur3Capture iBlur3Capture;
    private boolean iBlur3Active;
    private final ArrayList<RectF> iBlur3Positions = new ArrayList<>();
    private final RectF iBlur3PositionTop = new RectF();
    {
        iBlur3Positions.add(iBlur3PositionTop);
    }

    private final ArrayList<SvipeMusic.Section> sections = new ArrayList<>();
    private boolean homeLoading;
    private boolean homeLoaded;
    private boolean homeFailed;

    private String query = "";
    private String searchedQuery;
    // Search now returns canonical SONGS (1 card = 1 real song); tapping opens the version picker.
    private final ArrayList<SvipeMusic.Song> songResults = new ArrayList<>();
    // Canonical artists matching the same query — interleaved above the songs (rounded-square rows), so
    // the two kinds of result are told apart at a glance (song covers are circles, artist art is square).
    private final ArrayList<SvipeMusic.Artist> artistResults = new ArrayList<>();
    private final ArrayList<SvipeMusic.Track> searchResults = new ArrayList<>();
    // Each result's default version resolved to a real audio MessageObject, so the row renders as a
    // native SharedAudioCell (album art + play/download + duration) exactly like the chats media search.
    // The queue only exists to wrap resolved channel messages into MessageObjects — it is never played.
    private final HashMap<Long, MessageObject> searchMo = new HashMap<>();
    private SvipeMusicQueue searchQueue;
    private boolean searchLoading;
    private boolean searchFailed;
    private Runnable pendingSearch;
    // Local recent-search ledger (SvipeMusicSearchHistory) shown when the field is focused but empty.
    private SvipeMusicSearchHistory searchHistory;
    private boolean searchFocused;
    // Song ids whose default version is being resolved on demand for an inline play (fallback rows).
    private final HashSet<Long> songResolving = new HashSet<>();

    // "Most listened" fav-panel tab, paged from the backend and cached for the process.
    private final ArrayList<SvipeMusic.ListenedSong> mostListened = new ArrayList<>();
    private boolean mostListenedLoading;
    private boolean mostListenedLoaded;
    private boolean mostListenedEndReached;
    // One per search visit: accumulates the query variants + the tapped result, reported to the backend
    // (search-history). Minted lazily on the first query, cleared when the field empties (visit over).
    private SvipeSearchLog musicSearchLog;

    private final HashSet<String> likedKeys = new HashSet<>();
    // Track.key() -> resolved real channel message; shared between thumbnail loading and playback
    // so each track is fetched from Telegram at most once per session.
    private final HashMap<String, TLRPC.Message> resolvedMessages = new HashMap<>();
    private final HashSet<String> resolvingKeys = new HashSet<>();
    private final ArrayList<SvipeMusic.Track> thumbQueue = new ArrayList<>();
    private Runnable thumbFlusher;

    private boolean playRequestInFlight;
    private boolean vibeLoading;

    private static class Row {
        final int type;
        SvipeMusic.Section section;
        SvipeMusic.Track track;
        SvipeMusic.Song song;
        SvipeMusic.Artist artist;
        // Non-null only for a recent-search row, so a tap / long-press can tell it from a live result.
        SvipeMusicSearchHistory.Item recentItem;

        Row(int type) {
            this.type = type;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();

    public MusicActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        if (arguments != null) {
            hasMainTabs = arguments.getBoolean("hasMainTabs", false);
        }
        additionNavigationBarHeight = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0;
        NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
        nc.addObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.addObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.svipeFavouritesChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.svipeArtistFavouritesChanged);
        SvipeMusicTelemetry.getInstance(currentAccount).attach();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.svipeFavouritesChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.svipeArtistFavouritesChanged);
        super.onFragmentDestroy();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View createView(Context context) {
        // This user does use Music: from the next launch the vibe page is prepared in advance.
        SvipeMusicWarmer.markUsed(currentAccount);
        hasOwnBackground = true;
        actionBar.setAddToContainer(false);

        // Build the liquid-glass sources/factory before any view that needs a glass background.
        initBlur3();

        // `root` drives the blur pipeline once per frame: DialogsActivity does the same from its
        // ContentView.dispatchDraw. Recapturing the scrolling content here (before children draw) keeps
        // the frosted pill/island in sync with whatever is behind them.
        root = new FrameLayout(context) {
            private float dragDownY, dragDownX;
            private boolean draggingPanel;
            private VelocityTracker heroVelocity;

            @Override
            protected void dispatchDraw(Canvas canvas) {
                if (iBlur3Active && Build.VERSION.SDK_INT >= 31 && scrollableViewNoiseSuppressor != null) {
                    blur3_InvalidateBlur();
                }
                super.dispatchDraw(canvas);
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                if (favPanel != null) {
                    favPanel.onRootLaidOut();
                }
            }

            /**
             * The favourites panel can also be pulled up from the My Vibe card itself, not just by its
             * own strip — so the drag is caught here, above the hero. Only an upward drag past the touch
             * slop is taken: anything shorter stays a tap and still toggles playback.
             */
            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (inSearchMode() || showingRecent() || favPanel == null) {
                    return super.onInterceptTouchEvent(ev);
                }
                int action = ev.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    dragDownY = ev.getRawY();
                    dragDownX = ev.getRawX();
                    draggingPanel = false;
                } else if (action == MotionEvent.ACTION_MOVE && !draggingPanel && !favPanel.isOpen()) {
                    float dy = ev.getRawY() - dragDownY;
                    // Below the search pill only, so dragging over the field never moves the panel.
                    boolean belowChrome = ev.getY() > AndroidUtilities.statusBarHeight + dp(58);
                    if (belowChrome && dy < -ViewConfiguration.get(getContext()).getScaledTouchSlop()
                            && Math.abs(dy) > Math.abs(ev.getRawX() - dragDownX)) {
                        draggingPanel = true;
                        dragDownY = ev.getRawY();
                        favPanel.externalDragBegin();
                        heroVelocity = VelocityTracker.obtain();
                        return true;
                    }
                }
                return super.onInterceptTouchEvent(ev);
            }

            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                if (!draggingPanel || favPanel == null) {
                    return super.onTouchEvent(ev);
                }
                if (heroVelocity != null) {
                    heroVelocity.addMovement(ev);
                }
                int action = ev.getAction();
                if (action == MotionEvent.ACTION_MOVE) {
                    favPanel.externalDragMove(ev.getRawY() - dragDownY);
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    float vy = 0;
                    if (heroVelocity != null) {
                        heroVelocity.computeCurrentVelocity(1000);
                        vy = heroVelocity.getYVelocity();
                        heroVelocity.recycle();
                        heroVelocity = null;
                    }
                    favPanel.externalDragEnd(vy);
                    draggingPanel = false;
                    return true;
                }
                return super.onTouchEvent(ev);
            }
        };
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        // Wire the blur pipeline to `root` as the content root. The capture draws whichever backdrop is
        // currently on screen (the immersive vibe home or the search list) into the blur source so the
        // pill/island frost it. Must run before iBlur3FactoryLiquidGlass.create() is used below.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            viewPositionWatcher = new ViewPositionWatcher(root);
            iBlur3FactoryLiquidGlass.setSourceRootView(viewPositionWatcher, root);
            iBlur3VibeCapture = (canvas, position) -> {
                if (vibeScreen != null) {
                    vibeScreen.draw(canvas);
                }
            };
            iBlur3PanelCapture = (canvas, position) -> {
                if (favPanel != null) {
                    favPanel.draw(canvas);
                }
            };
            iBlur3Capture = (canvas, position) -> {
                if (vibeScreen != null && vibeScreen.getVisibility() == View.VISIBLE) {
                    Blur3Utils.captureRelativeParent(iBlur3VibeCapture, canvas, position, vibeScreen, root, 255);
                }
                // The panel slides over the hero, so the frosted pill and island must sample it too.
                if (favPanel != null && favPanel.getVisibility() == View.VISIBLE) {
                    Blur3Utils.captureRelativeParent(iBlur3PanelCapture, canvas, position, favPanel, root, 255);
                }
                if (listView != null && listView.getVisibility() == View.VISIBLE) {
                    Blur3Utils.captureRelativeParent(listView, canvas, position, listView, root, 255);
                }
            };
            iBlur3Active = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) && scrollableViewNoiseSuppressor != null;
        }

        // Full-screen music-reactive backdrop + centered hero button (the "home" screen). It sits at
        // the very back and spans under the status bar, so the gradient reaches the top edge. The
        // favourites panel is pulled up OVER it rather than pushing it off screen.
        vibeScreen = new VibeScreen(context);
        root.addView(vibeScreen, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Search results list. Opaque so results stay readable; its content is pushed below the
        // floating search bar via top padding (see listTopPadding()).
        listView = new RecyclerListView(context);
        layoutManager = new LinearLayoutManager(context);
        listView.setLayoutManager(layoutManager);
        adapter = new ListAdapter();
        listView.setAdapter(adapter);
        listView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        listView.setClipToPadding(true);
        listView.setPadding(0, listTopPadding(), 0, listBottomPadding());
        listView.setOnItemClickListener((view, position) -> onRowClick(position));
        listView.setOnItemLongClickListener((view, position) -> onRecentLongClick(position));
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    AndroidUtilities.hideKeyboard(searchField);
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (iBlur3Active && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                }
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // The favourites panel rides above the hero and below the floating chrome.
        favPanel = new FavouritesPanel(context);
        root.addView(favPanel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Floating search bar over everything — no title, just the pill. Container is transparent so
        // the backdrop shows through around it (Yandex-Music style).
        FrameLayout searchContainer = new FrameLayout(context);
        searchContainer.setPadding(0, AndroidUtilities.statusBarHeight + dp(8), 0, dp(8));
        root.addView(searchContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // The rounded search pill is the SAME reusable component the Chats/Search tabs use
        // (FragmentSearchField) — one shared component across tabs, not a hand-matched copy. Its
        // rounded background, clear ("x") button and colours all come from there; here we only wire the
        // hint and the query listener. (Liquid-glass blur is attached separately.)
        fragmentSearchField = new FragmentSearchField(context, getResourceProvider());
        fragmentSearchField.setPadding(dp(4), dp(4), dp(4), dp(4));
        fragmentSearchField.editText.setHint(getString(R.string.MusicSearchHint));
        fragmentSearchField.editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onQueryChanged(s != null ? s.toString() : "");
            }
        });
        searchField = fragmentSearchField.editText;
        // Focused-but-empty shows the recent-search list (Telegram-native feel); blurring hides it again.
        searchField.setOnFocusChangeListener((v, hasFocus) -> {
            if (searchFocused == hasFocus) {
                return;
            }
            searchFocused = hasFocus;
            // Keep the X visible whenever the field is focused (even with no text) so search is
            // always dismissable back to My Vibe.
            fragmentSearchField.setCloseButtonVisible(hasFocus);
            if (!inSearchMode()) {
                updateRows();
            }
        });
        // X taps: clear the text if there is any, otherwise drop focus to leave search (return to My Vibe).
        fragmentSearchField.setCloseButtonOnClickListener(() -> {
            if (searchField.length() > 0) {
                searchField.getText().clear();
            } else {
                searchField.clearFocus();
                AndroidUtilities.hideKeyboard(searchField);
            }
        });
        // Real liquid-glass pill, exactly like DialogsActivity's search field. Falls back to the pill's
        // own solid background when glass isn't active.
        if (iBlur3Active) {
            fragmentSearchField.setupBlurredBackground(iBlur3FactoryLiquidGlass.create(fragmentSearchField, BlurredBackgroundProviderImpl.topPanel(getResourceProvider())));
        }
        searchContainer.addView(fragmentSearchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP, 7, 0, 7, 0));

        // Standard Telegram now-playing bar — the same one the chats/search list uses. It sits in a
        // fixed slot right under the floating search field and opens the full native player on tap.
        // Hosted in a wrapper exactly as the chats list does: in bubble mode the bar draws with a
        // transparent background and doesn't move its own top-margin, so the wrapper both paints the
        // player background and pins it in place. The bar toggles the wrapper's visibility with its
        // own, and we reflow the results list so nothing hides behind it. Passing the wrapper as the
        // padding view keeps the constructor from touching fragmentView (not assigned yet here).
        contextWrap = new FrameLayout(context);
        if (iBlur3Active) {
            // Same frosted glass the pill uses; the create() call subscribes it to the position watcher
            // so its blur tracks the island as it shows/hides. Rounded to match the original island.
            BlurredBackgroundDrawable islandGlass = iBlur3FactoryLiquidGlass.create(contextWrap, BlurredBackgroundProviderImpl.topPanel(getResourceProvider()));
            islandGlass.setRadius(dp(12));
            contextWrap.setBackground(islandGlass);
        } else {
            contextWrap.setBackground(Theme.createRoundRectDrawable(dp(12), getThemedColor(Theme.key_inappPlayerBackground)));
        }
        contextWrap.setClipToOutline(true);  // round the (transparent-bubble) bar's corners like the chats/search island
        contextWrap.setVisibility(View.GONE);
        float ctxTopDp = (AndroidUtilities.statusBarHeight + dp(58)) / AndroidUtilities.density;
        root.addView(contextWrap, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 16, ctxTopDp, 16, 0));
        fragmentContextView = new FragmentContextView(context, this, contextWrap, false, null) {
            @Override
            public void setVisibility(int visibility) {
                super.setVisibility(visibility);
                contextWrap.setVisibility(visibility);
                refreshListPadding();
            }
        };
        // Telegram 12.9.2 dropped FragmentContextView.isInsideBubble: the transparent, no-extra-padding
        // look it used to opt into is now what the view always does, so the flag has no replacement.
        contextWrap.addView(fragmentContextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fragmentView = root;
        updateRows();
        ensureHomeLoaded();
        // Local store first (instant, offline), then reconcile with the backend once per process.
        rebuildFavourites();
        rebuildArtistFavourites();
        SvipeFavouritesSet.getInstance(currentAccount).syncFromServer();
        SvipeArtistFavouritesSet.getInstance(currentAccount).syncFromServer();
        return root;
    }

    /* home: the vibe hero with the favourites panel pulled up over it */

    /** The strip's own slot inside the panel: an 8dp margin above a 50dp strip. */
    private static final int STRIP_MARGIN_DP = 8;
    private static final int STRIP_HEIGHT_DP = 50;
    /**
     * Breathing room the strip keeps from the chrome above and below it. The bottom gap is the larger
     * of the two on purpose: the collapsed pill and the main tab bar are both dark rounded surfaces, and
     * at the same 2dp they read as one glued-together blob rather than two separate controls.
     */
    private static final int STRIP_GAP_TOP_DP = 2;
    private static final int STRIP_GAP_BOTTOM_DP = 8;
    /**
     * Slack between the strip's 50dp box and the pill it actually draws inside it (its own 7dp padding
     * plus the blurred pill drawable's inset). Measured on device — the pill is drawn by
     * ScrollSlidingTextTabStrip's background, so there is no child view to read it off.
     */
    private static final int STRIP_PILL_INSET_DP = 11;

    /** Height of the panel's own header — the tab strip plus the margin above and below it. */
    private int panelHeaderHeight() {
        return dp(STRIP_MARGIN_DP + STRIP_HEIGHT_DP + STRIP_MARGIN_DP);
    }

    /** Where the pill the strip draws sits inside the panel, top and bottom. */
    private int stripPillTopInPanel() {
        return dp(STRIP_MARGIN_DP + STRIP_PILL_INSET_DP);
    }

    private int stripPillBottomInPanel() {
        return dp(STRIP_MARGIN_DP + STRIP_HEIGHT_DP - STRIP_PILL_INSET_DP);
    }

    /** Top edge of the visible main tab bar (it belongs to the host activity, so this is by constant). */
    private int bottomChromeTop() {
        int h = root != null && root.getHeight() > 0 ? root.getHeight() : AndroidUtilities.displaySize.y;
        int tabs = hasMainTabs
                ? dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN) : 0;
        return h - AndroidUtilities.navigationBarHeight - tabs;
    }

    /**
     * Bottom edge of the chrome the open panel tucks under: the now-playing bar when one is showing,
     * otherwise the floating search pill. Read off the real views so it stays correct whatever their
     * paddings are, with the layout formula only as a pre-layout fallback.
     */
    private int topChromeBottom() {
        if (contextWrap != null && contextWrap.getVisibility() == View.VISIBLE && contextWrap.getHeight() > 0) {
            return contextWrap.getBottom();
        }
        if (fragmentSearchField != null && fragmentSearchField.getHeight() > 0) {
            return ((View) fragmentSearchField.getParent()).getTop() + fragmentSearchField.getBottom();
        }
        return AndroidUtilities.statusBarHeight + dp(8) + dp(48);
    }

    /**
     * "Favourite songs" — a panel that rides over the vibe hero.
     *
     * <p>Collapsed it peeks above the bottom tabs showing only its strip; open, the strip rests under
     * the now-playing bar with the list below. It is dragged directly (or tapped open), and released
     * past a threshold — or with enough flick — it snaps the rest of the way rather than stopping where
     * the finger left it. Its travel is published as {@link #progress}, which the hero uses to recede
     * and the list uses to fade in.
     */
    private class FavouritesPanel extends FrameLayout {

        /** The list is fully opaque well before the panel is, so it never fades in at the last moment. */
        private static final float ALPHA_FULL_AT = 0.4f;
        /** Past this much travel a release opens rather than falls back. */
        private static final float SNAP_AT = 0.35f;

        final ScrollSlidingTextTabStrip tabStrip;
        /** The three sub-tab pages (Songs / Singers / Most listened) as an interactive swipe pager. */
        final ViewPagerFixed favPager;
        // One list + adapter per page, indexed by FAV_TAB_* (== page position). Pages are created lazily
        // by the pager (page 0 up front, 1/2 the first time they are swiped to), so entries stay null
        // until then; every list-touching helper below skips the nulls.
        private final RecyclerListView[] pageLists = new RecyclerListView[3];
        private final FavAdapter[] pageAdapters = new FavAdapter[3];

        private final Drawable panelBackground;
        private final int touchSlop;
        private VelocityTracker velocityTracker;
        private ValueAnimator settleAnimator;

        private float collapsedY = -1, expandedY;
        private float progress;                 // 0 = peeking, 1 = fully open
        private boolean dragging;
        private float downY, downX, dragStartTranslation;
        /** Tab the finger landed on while the panel was closed, or -1. See {@link #tabIdAt}. */
        private int pendingTabId = -1;

        FavouritesPanel(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

            // Transparent while collapsed so the aura shows through around the peeking pill; it fades to
            // the solid window background as the panel opens.
            panelBackground = new ColorDrawable(getThemedColor(Theme.key_windowBackgroundWhite));
            panelBackground.setAlpha(0);
            setBackground(panelBackground);

            // The three sub-tabs are real pages behind a ViewPagerFixed (Telegram's own interactive
            // tab pager, exactly as SharedMediaLayout uses it): the pages follow the finger, snap to the
            // nearest neighbour on release and spring back below the threshold. We keep our own strip on
            // top for the blur pill, so the pager runs WITHOUT its built-in TabsView and drives the strip
            // by hand instead (see updateStripFromPager / onScrollEnd below).
            favPager = new ViewPagerFixed(context) {
                @Override
                public boolean canScrollHorizontally(int direction) {
                    // The outer Search|Music|Profile pager decides who owns a horizontal swipe purely
                    // through canParentTabsSlide(); its findScrollingChild() must NOT short-circuit that
                    // by spotting a scrollable nested pager here (it only tests the backward direction,
                    // which would dead-swipe forward-at-last-page). Report "no" and let the gate decide.
                    // (Pairs with setAllowDisallowInterceptTouch(false) below, which is what actually lets
                    // this pager start tracking a horizontal drag over its own RecyclerListView pages.)
                    return false;
                }

                @Override
                public void onTabAnimationUpdate(boolean manual) {
                    super.onTabAnimationUpdate(manual);
                    updateStripFromPager();     // underline follows the drag / settle
                }

                @Override
                protected void onScrollEnd() {
                    super.onScrollEnd();
                    // Settled (or sprung back): make the strip and favTab match the page we landed on.
                    int id = getCurrentPosition();
                    tabStrip.selectTabWithId(id, 1f);
                    setFavTab(id);
                }
            };
            // The pages are vertical RecyclerListViews. On EVERY touch a RecyclerListView calls
            // requestDisallowInterceptTouchEvent(parent, false) on this pager (RecyclerListView#onTouchEvent),
            // and ViewPagerFixed's requestDisallowInterceptTouchEvent override reacts to ANY such call while
            // it is only tentatively tracking (maybeStartTracking && !startedTracking) by firing
            // onTouchEvent(null), which wipes maybeStartTracking. The net effect: over a list page — most
            // visibly in the empty area below a SHORT list, where the RecyclerView itself (not a row) handles
            // the down and reaches that call immediately — the pager could never accumulate the slop to begin
            // a horizontal drag, so a swipe there did nothing. SharedMediaLayout doesn't hit this (it isn't a
            // ViewPagerFixed and tracks in its own onTouchEvent); CachedMediaLayout, which IS a ViewPagerFixed
            // over RecyclerListView pages exactly like us, cures it the same way: opt out of that self-cancel.
            // The disallow flag itself still propagates (super() runs), so a genuine vertical list scroll still
            // locks this pager out — only the spurious "give up my drag" side-effect is removed.
            favPager.setAllowDisallowInterceptTouch(false);
            favPager.setAlpha(0f);
            favPager.setAdapter(new FavPagerAdapter(this));
            addView(favPager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            tabStrip = new ScrollSlidingTextTabStrip(context, getResourceProvider());
            tabStrip.setColors(Theme.key_profile_tabSelectedLine, Theme.key_profile_tabSelectedText, Theme.key_profile_tabText, Theme.key_profile_tabSelector);
            tabStrip.setUseMinimalWidth(true);
            tabStrip.addTextTab(FAV_TAB_SONGS, getString(R.string.SvipeFavouriteSongs));
            tabStrip.addTextTab(FAV_TAB_SINGERS, getString(R.string.SvipeFavouriteSingers));
            tabStrip.addTextTab(FAV_TAB_MOST_LISTENED, getString(R.string.SvipeMostListened));
            tabStrip.finishAddingTabs();
            // Songs is the default: the tab ids ARE the FAV_TAB_* indices, so the strip's id is the
            // adapter's mode and nothing has to map between them.
            tabStrip.setInitialTabId(FAV_TAB_SONGS);
            tabStrip.setDelegate(new ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate() {
                @Override
                public void onPageSelected(int page, boolean forward) {
                    // A tab TAP: the strip animates its own underline; mirror it by gliding the pager to
                    // that page. The panel's travel, drag state and the hero's recede are untouched, so
                    // switching sub-tabs never moves the sheet. favTab is committed by the pager's
                    // onScrollEnd once the page settles (ids ARE the FAV_TAB_* page positions).
                    if (favPager != null && favPager.getCurrentPosition() != page) {
                        favPager.scrollToPosition(page);
                    }
                }

                @Override
                public void onPageScrolled(float progress) {
                    // The pager drives the underline during a drag (updateStripFromPager); the strip's own
                    // tap animation drives it on a tap. Nothing to move from here.
                }
            });
            try {
                // SharedMediaLayout's own fallback when it is handed no liquid-glass factory: the same
                // pill drawable off a plain colour source, minus the render-node pipeline.
                final BlurredBackgroundSourceColor source = new BlurredBackgroundSourceColor();
                source.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
                final BlurredBackgroundDrawable pill = new BlurredBackgroundDrawableViewFactory(source)
                        .create(tabStrip, BlurredBackgroundProviderImpl.topPanel(getResourceProvider()));
                pill.setRadius(dp(18));
                pill.setPadding(dp(6.666f));
                tabStrip.setPadding(0, dp(7), 0, dp(7));
                tabStrip.setClipToPadding(false);
                tabStrip.setBackground(null);
                tabStrip.setBlurredBackground(pill);
                tabStrip.setOpen(false);
            } catch (Throwable ignore) {
                // No pill is better than no tab.
            }
            addView(tabStrip, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 50, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 8, 0, 8));

            applyInsets();
        }

        /** Rows start below the strip and clear the bottom tabs — for every page that exists yet. */
        void applyInsets() {
            for (RecyclerListView list : pageLists) {
                if (list != null) {
                    list.setPadding(0, panelHeaderHeight(), 0, listBottomPadding());
                }
            }
        }

        /** The list of the page currently on screen (the pager's front view), or null pre-layout. */
        RecyclerListView currentList() {
            View v = favPager != null ? favPager.getCurrentView() : null;
            return v instanceof RecyclerListView ? (RecyclerListView) v : null;
        }

        /** Refresh one page's list (by FAV_TAB_* id), if it has been created. */
        void notifyPage(int tabId) {
            if (tabId >= 0 && tabId < pageAdapters.length && pageAdapters[tabId] != null) {
                pageAdapters[tabId].notifyDataSetChanged();
            }
        }

        /** Refresh every page that exists (e.g. playback state changed). */
        void notifyChanged() {
            for (FavAdapter a : pageAdapters) {
                if (a != null) {
                    a.notifyDataSetChanged();
                }
            }
        }

        /**
         * While the pager drags (or settles), slide the strip's underline from the current tab toward
         * the neighbour the drag is revealing, by the same fraction the pages have travelled. Purely
         * visual: {@link ScrollSlidingTextTabStrip#selectTabWithId} moves the indicator and only commits
         * the strip's selection at a full 1f, which the pager's onScrollEnd guarantees on release.
         */
        void updateStripFromPager() {
            if (favPager == null) {
                return;
            }
            View cur = favPager.getCurrentView();
            if (cur == null) {
                return;
            }
            int width = cur.getMeasuredWidth();
            if (width <= 0) {
                return;
            }
            int nextPos = favPager.getNextPosition();
            float tx = cur.getTranslationX();
            if (nextPos == favPager.getCurrentPosition() || tx == 0f) {
                return;     // nothing in flight
            }
            // ids == page positions, so nextPos doubles as the target tab id. Held just below 1f so an
            // over-drag never commits the strip's selection mid-gesture (that would freeze the underline
            // on a spring-back); onScrollEnd is the single authority that commits it to the final page.
            tabStrip.selectTabWithId(nextPos, Math.min(0.999f, Math.abs(tx) / width));
        }

        boolean isOpen() {
            return progress > 0.5f;
        }

        /**
         * Recompute the travel, keeping the current state.
         *
         * <p>Collapsed, the strip's bottom edge sits {@link #STRIP_GAP_DP} above the main tab bar; open,
         * its top edge sits the same distance below the now-playing bar (or the search pill when nothing
         * is playing). When the now-playing bar appears or disappears under an open panel that second
         * number moves, so the panel glides to the new resting place instead of jumping.
         */
        void applyGeometry(boolean animate) {
            if (root == null || root.getHeight() == 0) {
                return;
            }
            // Both ends are expressed against the pill the strip DRAWS, not its 50dp box, so the 2dp
            // reads as 2dp on screen.
            float newCollapsed = bottomChromeTop() - dp(STRIP_GAP_BOTTOM_DP) - stripPillBottomInPanel();
            float newExpanded = topChromeBottom() + dp(STRIP_GAP_TOP_DP) - stripPillTopInPanel();
            if (newCollapsed == collapsedY && newExpanded == expandedY) {
                return;
            }
            collapsedY = newCollapsed;
            expandedY = newExpanded;
            if (animate && settleAnimator == null && !dragging) {
                float target = collapsedY + (expandedY - collapsedY) * progress;
                animate().translationY(target).setDuration(220)
                        .setInterpolator(CubicBezierInterpolator.DEFAULT).start();
            } else {
                setProgress(progress);
            }
        }

        void onRootLaidOut() {
            applyGeometry(false);
        }

        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            if (collapsedY < 0) {
                return;     // not measured yet; onRootLaidOut re-applies
            }
            setTranslationY(collapsedY + (expandedY - collapsedY) * progress);

            // The list is fully there at 40% of the travel, so a partial drag already reads as content
            // rather than as a mostly-blank sheet.
            float contentAlpha = Math.min(1f, progress / ALPHA_FULL_AT);
            favPager.setAlpha(contentAlpha);
            panelBackground.setAlpha((int) (contentAlpha * 255));
            invalidate();

            // The hero recedes instead of scrolling away: it shrinks and dims as the panel covers it.
            if (vibeScreen != null) {
                vibeScreen.setRecede(progress);
            }
        }

        void animateTo(boolean open) {
            if (settleAnimator != null) {
                settleAnimator.cancel();
            }
            float target = open ? 1f : 0f;
            settleAnimator = ValueAnimator.ofFloat(progress, target);
            // Slow enough to read as a movement rather than a cut, still short enough to feel direct.
            settleAnimator.setDuration(Math.max(260, (long) (460 * Math.abs(target - progress))));
            settleAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            settleAnimator.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
            settleAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    settleAnimator = null;
                }
            });
            settleAnimator.start();
        }

        /** True when the current page's list is scrolled to its very top (so a downward drag closes it). */
        private boolean listAtTop() {
            RecyclerListView cur = currentList();
            return cur == null || !cur.canScrollVertically(-1);
        }

        /**
         * Which tab is under this SCREEN point, or -1 for none. Screen coordinates because the caller only
         * has raw ones — the panel translates, so nothing view-local survives the drag.
         */
        private int tabIdAt(float rawX, float rawY) {
            ViewGroup container = tabStrip.getTabsContainer();
            ArrayList<Integer> ids = tabStrip.getTabIds();
            int[] loc = new int[2];
            for (int i = 0, n = Math.min(container.getChildCount(), ids.size()); i < n; i++) {
                View child = container.getChildAt(i);
                if (child == null || child.getVisibility() != View.VISIBLE) {
                    continue;
                }
                child.getLocationOnScreen(loc);
                if (rawX >= loc[0] && rawX <= loc[0] + child.getWidth()
                        && rawY >= loc[1] && rawY <= loc[1] + child.getHeight()) {
                    return ids.get(i);
                }
            }
            return -1;
        }

        /** Select a tab as if it had been tapped — the strip's delegate then glides the pager to it. */
        private void selectTab(int tabId) {
            ArrayList<Integer> ids = tabStrip.getTabIds();
            int position = ids.indexOf(tabId);
            if (position < 0) {
                return;
            }
            // Hand over the child view too: the one-argument scrollTo passes null, which leaves the
            // indicator animating towards whatever width and x the PREVIOUS selection left behind.
            tabStrip.scrollTo(tabId, position, tabStrip.getTabsContainer().getChildAt(position));
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                downY = ev.getRawY();
                downX = ev.getRawX();
                dragging = false;
                dragStartTranslation = progress;
                if (settleAnimator != null) {
                    settleAnimator.cancel();
                    settleAnimator = null;
                }
                pendingTabId = -1;
                // While closed the panel owns the whole gesture: the only thing showing is the strip,
                // and ScrollSlidingTextTabStrip is a HorizontalScrollView that would otherwise swallow
                // the tap and the drag before either ever reached us.
                if (!isOpen()) {
                    // Swallowing the tap also swallows the tab it landed on, and with two tabs that is a
                    // real choice, not noise — remember it so the release can honour it and the panel does
                    // not open on the list the user did not ask for.
                    pendingTabId = tabIdAt(ev.getRawX(), ev.getRawY());
                    return true;
                }
            } else if (ev.getAction() == MotionEvent.ACTION_MOVE && !dragging) {
                float dy = ev.getRawY() - downY;
                if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(ev.getRawX() - downX)) {
                    // Opening: any drag steers the panel. Open: only a downward drag, and only once the
                    // list has nothing left to give, so the list scrolls before the panel closes.
                    if (!isOpen() || (dy > 0 && listAtTop())) {
                        dragging = true;
                        pendingTabId = -1;      // a drag is not a tab tap
                        downY = ev.getRawY();
                        dragStartTranslation = progress;
                        return true;
                    }
                }
            }
            return super.onInterceptTouchEvent(ev);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain();
            }
            // Feed the tracker coordinates in a frame that does NOT move with the panel, or the measured
            // velocity is the finger's speed minus the panel's and always reads near zero.
            MotionEvent stable = MotionEvent.obtain(ev);
            stable.offsetLocation(0, getTranslationY());
            velocityTracker.addMovement(stable);
            stable.recycle();
            final int action = ev.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                downY = ev.getRawY();
                downX = ev.getRawX();
                dragging = false;
                dragStartTranslation = progress;
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                // RAW coordinates throughout: this view is what the drag is moving, so ev.getY() shifts
                // by exactly the amount we translate and the gesture would cancel itself out.
                float dy = ev.getRawY() - downY;
                if (!dragging && Math.abs(dy) > touchSlop) {
                    dragging = true;
                    pendingTabId = -1;          // a drag is not a tab tap
                    downY = ev.getRawY();
                    dy = 0;
                }
                if (dragging && collapsedY > 0) {
                    // Dragging up (negative dy) opens; the travel is collapsedY -> expandedY.
                    setProgress(dragStartTranslation + (-dy) / (collapsedY - expandedY));
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                velocityTracker.computeCurrentVelocity(1000);
                float vy = velocityTracker.getYVelocity();
                velocityTracker.recycle();
                velocityTracker = null;
                if (!dragging && action == MotionEvent.ACTION_UP) {
                    // A tap on the peeking strip opens the panel — the whole point of leaving it visible.
                    // If it landed on a tab, select that tab first so the panel opens showing it.
                    if (pendingTabId >= 0) {
                        selectTab(pendingTabId);
                        pendingTabId = -1;
                    }
                    animateTo(!isOpen());
                    return true;
                }
                pendingTabId = -1;
                settleFromGesture(vy, dragStartTranslation);
                dragging = false;
                return true;
            }
            return super.onTouchEvent(ev);
        }

        /** Released mid-drag: follow a flick, else fall to whichever end the travel is closest to. */
        void settleFromGesture(float velocityY, float startProgress) {
            boolean open;
            if (Math.abs(velocityY) > dp(400)) {
                open = velocityY < 0;                       // flicked: follow the flick
            } else {
                open = progress > (startProgress > 0.5f ? 1f - SNAP_AT : SNAP_AT);
            }
            animateTo(open);
        }

        /* ---- drag started somewhere else on the screen (the My Vibe card) ---- */

        void externalDragBegin() {
            if (settleAnimator != null) {
                settleAnimator.cancel();
                settleAnimator = null;
            }
            dragStartTranslation = progress;
        }

        void externalDragMove(float dyFromStart) {
            if (collapsedY > 0) {
                setProgress(dragStartTranslation + (-dyFromStart) / (collapsedY - expandedY));
            }
        }

        void externalDragEnd(float velocityY) {
            settleFromGesture(velocityY, dragStartTranslation);
        }
    }

    /**
     * The three favourites pages behind the pager. Each page is its own vertical {@link RecyclerListView}
     * bound to a {@link FavAdapter} fixed to that page's mode (Songs / Singers / Most listened), so the
     * pages keep independent scroll state and all render, click, empty/loading and paging behaviour is the
     * same code that drove the old single list — it just no longer swaps datasets under one view.
     *
     * <p>Each page position IS its FAV_TAB_* id, and each gets a distinct viewType so the pager caches one
     * persistent list per tab (viewsByType) and reuses it across swipes. No TabsView is created; the strip
     * on top is our own {@link ScrollSlidingTextTabStrip}.
     */
    private class FavPagerAdapter extends ViewPagerFixed.Adapter {

        // The panel this pager belongs to. Passed in (not read off the MusicActivity favPanel field)
        // because the pager's first page is created from inside the panel's own constructor, before that
        // field has been assigned — so the field would still be null when page 0 registers itself.
        private final FavouritesPanel panel;

        FavPagerAdapter(FavouritesPanel panel) {
            this.panel = panel;
        }

        @Override
        public int getItemCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            return position;    // one persistent list per tab
        }

        @Override
        public int getItemId(int position) {
            return position;    // ids == FAV_TAB_* positions
        }

        @Override
        public CharSequence getItemTitle(int position) {
            // Unused (no TabsView; our own strip carries the titles), but kept meaningful.
            if (position == FAV_TAB_SINGERS) return getString(R.string.SvipeFavouriteSingers);
            if (position == FAV_TAB_MOST_LISTENED) return getString(R.string.SvipeMostListened);
            return getString(R.string.SvipeFavouriteSongs);
        }

        @Override
        public View createView(int viewType) {
            final int mode = viewType;      // viewType == position == FAV_TAB_*
            Context context = panel.favPager.getContext();
            RecyclerListView list = new RecyclerListView(context);
            list.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
            list.setGlowColor(0);
            list.setClipToPadding(false);
            list.setVerticalScrollBarEnabled(false);
            list.setPadding(0, panelHeaderHeight(), 0, listBottomPadding());
            FavAdapter adapter = new FavAdapter(mode);
            list.setAdapter(adapter);
            list.setOnItemClickListener((view, position) -> onFavouriteClick(mode, position));
            list.setOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    if (iBlur3Active && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                        scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                    }
                    // Page the most-listened list as it nears the bottom.
                    if (mode == FAV_TAB_MOST_LISTENED && dy > 0 && !mostListenedLoading && !mostListenedEndReached) {
                        RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
                        if (lm instanceof LinearLayoutManager) {
                            LinearLayoutManager llm = (LinearLayoutManager) lm;
                            if (llm.findLastVisibleItemPosition() >= llm.getItemCount() - 5) {
                                loadMostListened(true);
                            }
                        }
                    }
                }
            });
            panel.pageLists[mode] = list;
            panel.pageAdapters[mode] = adapter;
            return list;
        }

        @Override
        public void bindView(View view, int position, int viewType) {
            // Data may have changed while the page was off-screen (or is loading on first reveal).
            if (position == FAV_TAB_MOST_LISTENED) {
                ensureMostListenedLoaded();
            }
            panel.notifyPage(position);
        }
    }

    /**
     * Rows of ONE favourites page: a resolved audio cell, a plain card, an artist row, a most-listened
     * row, or the empty/loading state. The adapter is fixed to a {@link #mode} (a FAV_TAB_* id) at
     * construction — one adapter per page — so it reads its own collection and never swaps.
     */
    private class FavAdapter extends RecyclerListView.SelectionAdapter {

        /** Which page (FAV_TAB_*) this adapter renders — replaces the old shared favTab read. */
        private final int mode;

        FavAdapter(int mode) {
            this.mode = mode;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            if (type == ROW_FAV_EMPTY || type == ROW_FAV_LOADING) {
                return false;
            }
            if (type == ROW_FAV_ARTIST || type == ROW_ML_SONG) {
                return true;    // an artist / most-listened row always has a page to open
            }
            // Don't offer a ripple on a row whose tap could not do anything.
            int pos = holder.getAdapterPosition();
            return pos >= 0 && pos < favourites.size() && isFavouriteActionable(favourites.get(pos));
        }

        @Override
        public int getItemCount() {
            if (mode == FAV_TAB_MOST_LISTENED) {
                if (mostListened.isEmpty()) {
                    return 1;   // spinner while loading, else the empty sentence
                }
                return mostListened.size();
            }
            if (mode == FAV_TAB_SINGERS) {
                return artistFavourites.isEmpty() ? 1 : artistFavourites.size();
            }
            return favourites.isEmpty() ? 1 : favourites.size();
        }

        @Override
        public int getItemViewType(int position) {
            if (mode == FAV_TAB_MOST_LISTENED) {
                if (mostListened.isEmpty()) {
                    return mostListenedLoading ? ROW_FAV_LOADING : ROW_FAV_EMPTY;
                }
                return ROW_ML_SONG;
            }
            if (mode == FAV_TAB_SINGERS) {
                return artistFavourites.isEmpty() ? ROW_FAV_EMPTY : ROW_FAV_ARTIST;
            }
            if (favourites.isEmpty()) {
                return ROW_FAV_EMPTY;
            }
            return favMo.containsKey(favourites.get(position).key) ? ROW_FAV_AUDIO : ROW_FAV_CARD;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            if (viewType == ROW_FAV_AUDIO) {
                view = new SharedAudioCell(context, getResourceProvider());
            } else if (viewType == ROW_FAV_CARD) {
                view = new FavouriteCell(context);
            } else if (viewType == ROW_ML_SONG) {
                view = new MostListenedCell(context);
            } else if (viewType == ROW_FAV_ARTIST) {
                // The song page's artist row verbatim: UserCell is the profile's own person row and
                // takes an explicit name/status, so it needs no peer behind it.
                view = new UserCell(context, 6, 0, false, getResourceProvider());
            } else if (viewType == ROW_FAV_LOADING) {
                org.telegram.ui.Components.RadialProgressView progress = new org.telegram.ui.Components.RadialProgressView(context);
                progress.setSize(dp(28));
                FrameLayout wrap = new FrameLayout(context);
                wrap.addView(progress, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 24, 0, 24));
                view = wrap;
            } else {
                TextView tv = new TextView(context);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                tv.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(dp(20), dp(40), dp(20), dp(28));
                view = tv;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final int type = holder.getItemViewType();
            if (type == ROW_FAV_LOADING) {
                return;
            }
            if (type == ROW_ML_SONG) {
                if (position < mostListened.size()) {
                    ((MostListenedCell) holder.itemView).bind(mostListened.get(position));
                }
                return;
            }
            if (type == ROW_FAV_EMPTY) {
                // This page's own empty sentence.
                int empty = mode == FAV_TAB_SINGERS ? R.string.SvipeFavouriteSingersEmpty
                        : mode == FAV_TAB_MOST_LISTENED ? R.string.MusicSearchEmpty
                        : R.string.SvipeFavouritesEmpty;
                ((TextView) holder.itemView).setText(getString(empty));
                return;
            }
            if (type == ROW_FAV_ARTIST) {
                if (position < artistFavourites.size()) {
                    bindArtistRow((UserCell) holder.itemView, artistFavourites.get(position),
                            position != getItemCount() - 1);
                }
                return;
            }
            if (position >= favourites.size()) {
                return;
            }
            SvipeFavourite f = favourites.get(position);
            if (type == ROW_FAV_AUDIO) {
                MessageObject mo = playingCopyOf(f);
                if (mo != null) {
                    ((SharedAudioCell) holder.itemView).setMessageObject(mo, position != getItemCount() - 1);
                }
            } else if (type == ROW_FAV_CARD) {
                ((FavouriteCell) holder.itemView).bind(f);
            }
        }
    }

    /**
     * An artist row, drawn exactly as {@link MusicSongActivity} draws the artists of a song: the real
     * (Deezer) photo when the artist has been enriched, and Telegram's gradient+initials tile when not
     * — the same fallback {@link MusicArtistActivity} shows in its own header.
     *
     * <p>The image is re-applied on EVERY bind: {@code UserCell.setData} leaves the avatar alone when it
     * is handed no peer, so a recycled row would otherwise keep the previous artist's photo.
     */
    private void bindArtistRow(UserCell cell, SvipeArtistFavourite f, boolean divider) {
        String shown = f.shownName();
        String name = shown != null && !shown.isEmpty() ? shown : getString(R.string.AudioUnknownArtist);
        cell.setData(null, name, artistStatus(f), 0, divider);
        AvatarDrawable avatar = new AvatarDrawable();
        avatar.setInfo(f.artistId, name, null);
        if (f.photoUrl != null && !f.photoUrl.isEmpty()) {
            cell.avatarImageView.setImage(ImageLocation.getForPath(f.photoUrl), "50_50", avatar, null);
        } else {
            cell.avatarImageView.setImage(null, "50_50", avatar, null);
        }
    }

    /**
     * The status line of an artist row, mirroring the song page's: how much they have, since repeating
     * "Artist" down every row tells the reader nothing. The label only stands in when the count is
     * missing (an older backend, or an artist not yet counted).
     */
    private String artistStatus(SvipeArtistFavourite f) {
        if (f.songCount > 0) {
            return LocaleController.formatPluralString("SvipeMusicSongCount", f.songCount);
        }
        return getString(R.string.SvipeMusicArtist);
    }

    /** A favourite we cannot render as a native audio row (private source, or not resolved yet). */
    private class FavouriteCell extends FrameLayout {
        private final TextView titleView;
        private final TextView subtitleView;

        FavouriteCell(Context context) {
            super(context);
            setPadding(dp(16), dp(8), dp(16), dp(8));
            setBackground(Theme.getSelectorDrawable(false));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(titleView);

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
        }

        void bind(SvipeFavourite f) {
            titleView.setText(f.title != null && !f.title.isEmpty() ? f.title : getString(R.string.AudioUnknownTitle));
            String artist = f.artist != null && !f.artist.isEmpty() ? f.artist : getString(R.string.AudioUnknownArtist);
            subtitleView.setText(f.durationS > 0
                    ? artist + " · " + AndroidUtilities.formatShortDuration(f.durationS)
                    : artist);
        }
    }

    /* favourites data */

    /**
     * Record which favourites page is now on screen. Each page is its own list, so there is no dataset
     * to swap or scroll here — this only mirrors the pager's selection and kicks off the most-listened
     * load the first time that page appears. The pager's onScrollEnd (drag) and the strip's tab tap both
     * drive this; the panel's translation, drag state and the hero's recede are untouched.
     */
    private void setFavTab(int tab) {
        if (favTab == tab) {
            return;
        }
        favTab = tab;
        if (tab == FAV_TAB_MOST_LISTENED) {
            ensureMostListenedLoaded();
        }
    }

    /** Load the first most-listened page the first time that tab is opened. */
    private void ensureMostListenedLoaded() {
        if (!mostListenedLoaded && !mostListenedLoading) {
            loadMostListened(false);
        }
    }

    /**
     * Load a page of this user's most-listened songs. {@code more=false} loads the first page (and shows
     * a spinner); {@code more=true} appends the next page as the list nears its bottom.
     */
    private void loadMostListened(boolean more) {
        if (mostListenedLoading || (more && mostListenedEndReached)) {
            return;
        }
        mostListenedLoading = true;
        if (!more) {
            notifyMostListened();   // swap the empty sentence for the spinner
        }
        final int offset = more ? mostListened.size() : 0;
        SvipeMusic.mostListenedSongs(currentAccount, offset, MOST_LISTENED_PAGE, (items, next, error) -> {
            mostListenedLoading = false;
            if (items == null) {
                // Leave what we have; a later tab re-open or scroll retries.
                if (!more) {
                    mostListenedLoaded = true;   // stop the spinner -> show the empty sentence
                }
                notifyMostListened();
                return;
            }
            mostListenedLoaded = true;
            if (!more) {
                mostListened.clear();
            }
            mostListened.addAll(items);
            mostListenedEndReached = next == null || items.isEmpty();
            notifyMostListened();
        });
    }

    private void notifyMostListened() {
        if (favPanel != null) {
            favPanel.notifyPage(FAV_TAB_MOST_LISTENED);
        }
    }

    /** Total listen-time as "Hh Mm" (or just "Mm" under an hour) for the most-listened label. */
    private String formatListenTime(long totalMs) {
        long minutes = Math.max(0, totalMs) / 60000L;
        long h = minutes / 60;
        long m = minutes % 60;
        return h > 0 ? (h + "h " + m + "m") : (m + "m");
    }

    /**
     * Rebuild the favourite singers from the local store. Nothing to resolve here: an artist row is
     * name + photo + count, all of it cached in the entry, and the tap opens a page rather than playing.
     */
    private void rebuildArtistFavourites() {
        artistFavourites.clear();
        artistFavourites.addAll(SvipeArtistFavouritesSet.getInstance(currentAccount).list());
        if (favPanel != null) {
            favPanel.notifyPage(FAV_TAB_SINGERS);
        }
    }

    /**
     * Rebuild the section from the local store, then resolve whatever can be played from a channel into
     * real audio messages so those rows render as native audio cells. The store is authoritative and
     * always available, so the list appears instantly and offline.
     */
    private void rebuildFavourites() {
        favourites.clear();
        favourites.addAll(SvipeFavouritesSet.getInstance(currentAccount).list());
        if (favPanel != null) {
            favPanel.notifyPage(FAV_TAB_SONGS);
        }
        resolveFavourites();
    }

    /**
     * Resolve every playable favourite and rebuild ONE queue holding all of them, in list order.
     *
     * <p>The whole queue is rebuilt rather than extended because it has to mirror the list exactly: a
     * queue containing only the newly-added entries would leave older rows pointing at MessageObjects
     * from a queue that is no longer installed, and playing one of those makes MediaController throw the
     * playlist away and fall back to a single track (no next/prev, no auto-advance). Resolved channel
     * messages are cached in favResolvedMsgs, so a rebuild costs a network round-trip only for entries
     * that have never been resolved.
     */
    private void resolveFavourites() {
        if (favResolving) {
            return;
        }
        final ArrayList<SvipeMusic.Track> playable = new ArrayList<>();
        final ArrayList<SvipeFavourite> owners = new ArrayList<>();
        final ArrayList<SvipeMusic.Track> toResolve = new ArrayList<>();
        for (SvipeFavourite f : favourites) {
            if (f.username == null || f.username.isEmpty() || f.channelId == 0 || f.messageId == 0) {
                continue;   // private/unresolvable entries stay as cards
            }
            SvipeMusic.Track t = new SvipeMusic.Track();
            t.channelId = f.channelId;
            t.messageId = f.messageId;
            t.username = f.username;
            t.title = f.title;
            t.performer = f.artist;
            t.durationS = f.durationS;
            t.songId = f.songId;
            playable.add(t);
            owners.add(f);
            if (!favResolvedMsgs.containsKey(t.key())) {
                toResolve.add(t);
            }
        }
        if (playable.isEmpty()) {
            favQueue = null;
            favMo.clear();
            return;
        }
        if (toResolve.isEmpty()) {
            buildFavQueue(playable, owners);
            return;
        }
        favResolving = true;
        SvipeMusicResolver.resolve(currentAccount, toResolve, resolved -> {
            favResolving = false;
            favResolvedMsgs.putAll(resolved);
            buildFavQueue(playable, owners);
            if (favPanel != null) {
                favPanel.notifyPage(FAV_TAB_SONGS);
            }
        });
    }

    private void buildFavQueue(List<SvipeMusic.Track> playable, List<SvipeFavourite> owners) {
        SvipeMusicQueue queue = new SvipeMusicQueue(currentAccount, SvipeMusicQueue.SOURCE_SECTION,
                getString(R.string.SvipeFavouriteSongs), false);
        queue.appendResolved(playable, favResolvedMsgs);
        favQueue = queue;
        favMo.clear();
        for (int i = 0; i < playable.size(); i++) {
            MessageObject mo = queue.messageForKey(playable.get(i).key());
            if (mo != null) {
                favMo.put(owners.get(i).key, mo);
            }
        }
    }

    private void onFavouriteClick(int mode, int position) {
        if (mode == FAV_TAB_MOST_LISTENED) {
            // A most-listened row opens the song page (same as a search song row), not inline playback.
            if (position >= 0 && position < mostListened.size()) {
                SvipeMusic.ListenedSong s = mostListened.get(position);
                presentFragment(new MusicSongActivity(s.id, s.shownTitle()));
            }
            return;
        }
        if (mode == FAV_TAB_SINGERS) {
            if (position >= 0 && position < artistFavourites.size()) {
                SvipeArtistFavourite a = artistFavourites.get(position);
                presentFragment(new MusicArtistActivity(a.artistId, a.shownName()));
            }
            return;
        }
        if (position < 0 || position >= favourites.size()) {
            return;
        }
        SvipeFavourite f = favourites.get(position);
        MessageObject mo = favMo.get(f.key);
        if (mo != null && favQueue != null) {
            MediaController mc = MediaController.getInstance();
            MessageObject playing = mc.getPlayingMessageObject();
            // Toggle whenever THIS song is the one playing, whatever queue it came from — otherwise
            // tapping the row of an already-playing vibe track would restart it from our own queue.
            if (playing != null && isSameTrack(playing, f)) {
                if (mc.isMessagePaused()) {
                    mc.playMessage(playing);
                } else {
                    mc.pauseMessage(playing);
                }
            } else {
                favQueue.play(mo);
            }
            if (favPanel != null) {
                favPanel.notifyPage(FAV_TAB_SONGS);
            }
            return;
        }
        // Nothing playable here (a private copy, or the channel would not resolve) — open where it
        // lives so it can still be played, rather than leaving the tap dead.
        if (f.messageId == 0) {
            return;     // no message to open; the row is not enabled either (see FavAdapter.isEnabled)
        }
        long dialogId = f.dialogId != 0 ? f.dialogId : (f.channelId != 0 ? -f.channelId : 0);
        if (dialogId == 0) {
            return;
        }
        android.os.Bundle args = new android.os.Bundle();
        if (dialogId > 0) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        args.putInt("message_id", f.messageId);
        if (MessagesController.getInstance(currentAccount).checkCanOpenChat(args, MusicActivity.this)) {
            presentFragment(new ChatActivity(args));
        }
    }

    /**
     * The MessageObject to bind this favourite to.
     *
     * <p>When the song is the one playing, this is the PLAYING object rather than our own copy of it.
     * MediaController identifies a track by (dialogId, {@code getId()}), and every queue mints its own
     * synthetic id for the same channel post — so binding our copy would leave the row drawn as idle
     * while the very same song plays. Handing the cell the playing object makes it show, and control,
     * the real playback state.
     */
    private MessageObject playingCopyOf(SvipeFavourite f) {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null && isSameTrack(playing, f)) {
            return playing;
        }
        return favMo.get(f.key);
    }

    /** Same underlying channel post, whichever queue minted the copy. */
    private boolean isSameTrack(MessageObject mo, SvipeFavourite f) {
        long dialogId = mo.getDialogId();
        return dialogId < 0 && f.channelId != 0
                && -dialogId == f.channelId && mo.getRealId() == f.messageId;
    }

    /** True when tapping this favourite can actually do something — play it, or open its message. */
    private boolean isFavouriteActionable(SvipeFavourite f) {
        if (favMo.containsKey(f.key)) {
            return true;
        }
        return f.messageId != 0 && (f.dialogId != 0 || f.channelId != 0);
    }


    /* Liquid glass (iBlur3) */

    // Builds the glass source + drawable factory, mirroring the LIQUID-GLASS half of
    // DialogsActivity's constructor. Below S there is no render-node pipeline, so the factory falls
    // back to a solid-colour source and every glass drawable it makes is a plain solid fill.
    private void initBlur3() {
        iBlur3SourceColor = new BlurredBackgroundSourceColor();
        iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scrollableViewNoiseSuppressor = new DownscaleScrollableNoiseSuppressor();
            iBlur3SourceGlass = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceGlass.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override
                public void renderNodeCalculateHash(IBlur3Hash hash) {
                    hash.add(getThemedColor(Theme.key_windowBackgroundWhite));
                    hash.add(SharedConfig.chatBlurEnabled());
                }

                @Override
                public void renderNodeUpdateDisplayList(Canvas canvas) {
                    canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    if (SharedConfig.chatBlurEnabled()) {
                        scrollableViewNoiseSuppressor.draw(canvas, DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
                    }
                }
            });
            iBlur3FactoryLiquidGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceGlass);
            iBlur3FactoryLiquidGlass.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
        } else {
            scrollableViewNoiseSuppressor = null;
            iBlur3SourceGlass = null;
            iBlur3FactoryLiquidGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);
        }
    }

    // One blur region covering the top strip where the search pill and the now-playing island float.
    // Simplified from DialogsActivity#blur3_InvalidateBlur (no bottom tab region here).
    private void blur3_InvalidateBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || scrollableViewNoiseSuppressor == null
                || root == null || iBlur3Capture == null) {
            return;
        }
        final int w = root.getMeasuredWidth();
        final int h = root.getMeasuredHeight();
        if (w == 0 || h == 0) {
            return;
        }
        iBlur3PositionTop.set(0, -dp(48), w, AndroidUtilities.statusBarHeight + dp(58) + dp(39) + dp(48));
        scrollableViewNoiseSuppressor.setupRenderNodes(iBlur3Positions, 1);
        scrollableViewNoiseSuppressor.invalidateResultRenderNodes(iBlur3Capture, w, h);
        if (iBlur3SourceGlass != null) {
            iBlur3SourceGlass.setSize(w, h);
            iBlur3SourceGlass.updateDisplayListIfNeeded();
        }
    }

    // Reserve room for the floating search bar (status bar + pill + margins) so the first result
    // isn't hidden underneath it.
    private int listTopPadding() {
        int pad = AndroidUtilities.statusBarHeight + dp(58);
        if (fragmentContextView != null && fragmentContextView.getVisibility() == View.VISIBLE) {
            pad += dp(39);  // clear the now-playing bar that sits under the search field
        }
        return pad;
    }

    private int listBottomPadding() {
        return AndroidUtilities.navigationBarHeight + additionNavigationBarHeight + dp(12);
    }

    private void refreshListPadding() {
        if (listView != null) {
            listView.setPadding(0, listTopPadding(), 0, listBottomPadding());
        }
        // The now-playing bar just appeared or went away, which moves where the strip must rest.
        if (favPanel != null) {
            favPanel.applyInsets();
            favPanel.applyGeometry(true);
        }
    }

    /* data */

    private void ensureHomeLoaded() {
        if (!homeLoaded && !homeLoading) {
            loadHome();
        }
    }

    private void loadHome() {
        homeLoading = true;
        homeFailed = false;
        updateRows();
        SvipeMusic.home(currentAccount, (result, error) -> {
            homeLoading = false;
            if (result == null) {
                homeFailed = true;
                updateRows();
                return;
            }
            homeLoaded = true;
            sections.clear();
            sections.addAll(result);
            for (SvipeMusic.Section s : result) {
                if ("liked".equals(s.key)) {
                    for (SvipeMusic.Track t : s.tracks) {
                        likedKeys.add(t.key());
                    }
                }
            }
            updateRows();
        });
    }

    private void onQueryChanged(String q) {
        query = q != null ? q.trim() : "";
        // FragmentSearchField shows/hides its own clear button from the text; nothing to do here.
        if (pendingSearch != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingSearch);
            pendingSearch = null;
        }
        if (query.length() < SEARCH_MIN_CHARS) {
            searchedQuery = null;
            songResults.clear();
            artistResults.clear();
            searchMo.clear();
            searchLoading = false;
            searchFailed = false;
            musicSearchLog = null;   // field cleared -> this search visit is over; next one is fresh
            updateRows();
            return;
        }
        searchLoading = true;
        updateRows();
        final String q2 = query;
        pendingSearch = () -> runSearch(q2);
        AndroidUtilities.runOnUIThread(pendingSearch, 350);
    }

    private void runSearch(String q) {
        // Artists matching the same query — resolved in parallel and interleaved above the songs. Its
        // own callback so a slow/failed artist lookup never holds up the song rows.
        SvipeMusic.artistsSearch(currentAccount, q, 0, ARTIST_SEARCH_LIMIT, (items, next, error) -> {
            if (!q.equals(query)) {
                return;
            }
            artistResults.clear();
            if (items != null) {
                for (int i = 0; i < items.size() && artistResults.size() < MAX_ARTIST_ROWS; i++) {
                    artistResults.add(items.get(i));
                }
            }
            updateRows();
        });
        SvipeMusic.songsSearch(currentAccount, q, 0, SEARCH_PAGE, (items, next, error) -> {
            if (!q.equals(query)) {
                return;
            }
            // Search-history: record this settled query + its result count against the visit.
            if (musicSearchLog == null) {
                musicSearchLog = new SvipeSearchLog(currentAccount, "music");
            }
            musicSearchLog.query(q, items != null ? items.size() : -1);
            searchedQuery = q;
            songResults.clear();
            searchMo.clear();
            if (items == null) {
                searchFailed = true;
                searchLoading = false;
                updateRows();
            } else {
                searchFailed = false;
                songResults.addAll(items);
                // Stay in the loading state until the default versions resolve, so the results appear
                // as finished native audio rows rather than flashing letter tiles first.
                resolveSearchDefaults(q);
            }
        });
    }

    /**
     * Resolves each result's default version to a real audio MessageObject (batched per channel, the
     * same two round-trips the song page and the reels pipeline use), so search rows render as native
     * {@link SharedAudioCell}s. Songs that carry no default, or whose channel fails to resolve, fall
     * back to the lightweight letter cell.
     */
    private void resolveSearchDefaults(String q) {
        final ArrayList<SvipeMusic.Track> defaults = new ArrayList<>();
        for (SvipeMusic.Song s : songResults) {
            if (s.defaultTrack != null) {
                defaults.add(s.defaultTrack);
            }
        }
        if (defaults.isEmpty()) {
            searchLoading = false;
            updateRows();
            return;
        }
        final SvipeMusicQueue queue = new SvipeMusicQueue(currentAccount, SvipeMusicQueue.SOURCE_SEARCH, "", false);
        SvipeMusicResolver.resolve(currentAccount, defaults, resolved -> {
            if (!q.equals(query)) {
                return; // a newer query already superseded this one
            }
            queue.appendResolved(defaults, resolved);
            searchQueue = queue;
            searchMo.clear();
            for (SvipeMusic.Song s : songResults) {
                if (s.defaultTrack != null) {
                    MessageObject mo = queue.messageForKey(s.defaultTrack.key());
                    if (mo != null) {
                        searchMo.put(s.id, mo);
                    }
                }
            }
            searchLoading = false;
            updateRows();
        });
    }

    private boolean inSearchMode() {
        return query.length() >= SEARCH_MIN_CHARS;
    }

    private void updateRows() {
        // The list serves search results AND the focused-but-empty recent-search list; "home" is the
        // full-screen VibeScreen, not a row list.
        rows.clear();
        if (inSearchMode()) {
            boolean empty = songResults.isEmpty() && artistResults.isEmpty();
            if (searchLoading && empty) {
                rows.add(new Row(ROW_LOADING));
            } else if (searchFailed && empty) {
                rows.add(new Row(ROW_RETRY));
            } else if (empty) {
                rows.add(new Row(ROW_EMPTY));
            } else {
                // Artists first, as a small rounded-square group, then the songs.
                for (SvipeMusic.Artist a : artistResults) {
                    Row r = new Row(ROW_ARTIST);
                    r.artist = a;
                    rows.add(r);
                }
                for (SvipeMusic.Song s : songResults) {
                    // Native audio row once the default version resolved; letter-cell fallback otherwise.
                    Row r = new Row(searchMo.containsKey(s.id) ? ROW_SONG_AUDIO : ROW_SONG);
                    r.song = s;
                    rows.add(r);
                }
            }
        } else if (showingRecent()) {
            List<SvipeMusicSearchHistory.Item> recents = history().getAll();
            if (recents.isEmpty()) {
                rows.add(new Row(ROW_RECENT_EMPTY));
            } else {
                rows.add(new Row(ROW_RECENT_HEADER));
                // Recents render with the SAME cells the live results use: a recent song looks like a
                // song (SongCell), a recent singer looks like a singer (ArtistCell). recentItem marks
                // them so a tap re-opens the item and a long-press removes just that entry.
                for (SvipeMusicSearchHistory.Item item : recents) {
                    Row r;
                    if (item.isSong()) {
                        r = new Row(ROW_SONG);
                        r.song = item.song;
                    } else {
                        r = new Row(ROW_ARTIST);
                        r.artist = item.artist;
                    }
                    r.recentItem = item;
                    rows.add(r);
                }
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateMode();
    }

    /** The recent-search list stands in for the vibe home when the field is focused but not yet a query. */
    private boolean showingRecent() {
        return searchFocused && !inSearchMode();
    }

    private SvipeMusicSearchHistory history() {
        if (searchHistory == null) {
            searchHistory = new SvipeMusicSearchHistory(currentAccount);
        }
        return searchHistory;
    }

    // Swap between the search list and the home scroller (vibe hero + favourites) based on whether a
    // query is active. The hero is a row of the home scroller now, so hiding that hides both.
    private void updateMode() {
        // The row list shows both real search results and the focused-but-empty recent list; the hero +
        // favourites panel show only when neither is up.
        boolean list = inSearchMode() || showingRecent();
        if (listView != null) {
            listView.setVisibility(list ? View.VISIBLE : View.GONE);
        }
        if (vibeScreen != null) {
            vibeScreen.setVisibility(list ? View.GONE : View.VISIBLE);
        }
        if (favPanel != null) {
            favPanel.setVisibility(list ? View.GONE : View.VISIBLE);
        }
        if (vibeScreen != null && !list) {
            vibeScreen.update();
        }
        // Home shows the dark immersive backdrop (light status-bar icons); search shows the opaque
        // white list (dark icons). Re-apply on every switch.
        if (getParentActivity() != null) {
            AndroidUtilities.setLightStatusBar(getParentActivity().getWindow(), isLightStatusBar());
        }
    }

    private void refreshVibe() {
        if (vibeScreen != null && vibeScreen.isShown()) {
            vibeScreen.update();
        }
    }

    /* playback */

    private void onRowClick(int position) {
        if (position < 0 || position >= rows.size()) {
            return;
        }
        Row row = rows.get(position);
        if (row.type == ROW_TRACK) {
            onTrackTap(row);
        } else if (row.type == ROW_ARTIST) {
            if (row.artist != null) {
                // Record the tapped RESULT (native-style): a fresh tap stores the item; a recent re-tap
                // just floats it back to the top. Tapping opens the artist page, exactly like a live row.
                history().add(row.artist);
                if (inSearchMode() && musicSearchLog != null) {
                    musicSearchLog.click(searchedQuery, "artist", "artist:" + row.artist.id, row.artist.shownName());
                }
                presentFragment(new MusicArtistActivity(row.artist.id, row.artist.shownName()));
            }
        } else if (row.type == ROW_SONG || row.type == ROW_SONG_AUDIO) {
            if (row.song != null) {
                // Record the tapped RESULT as a recent item (fresh tap stores it; a recent re-tap floats
                // it to the top). Opens the song page — same path as a live result.
                history().add(row.song);
                // Search-history (backend): a tapped result means they found what they searched for.
                // Tapping a Deezer placeholder is an even stronger demand signal (kind='deezer').
                if (inSearchMode() && musicSearchLog != null) {
                    musicSearchLog.click(searchedQuery, row.song.playable ? "song" : "deezer",
                            (row.song.playable ? "song:" : "deezer:") + Math.abs(row.song.id), row.song.shownTitle());
                }
                // Open the song page for both. A Deezer placeholder (negative id) opens too: the backend
                // serves a Deezer-only detail with an EMPTY version list, so the page shows its name +
                // cover + artist and simply has no versions yet.
                presentFragment(new MusicSongActivity(row.song.id, row.song.shownTitle()));
            }
        } else if (row.type == ROW_RETRY) {
            if (inSearchMode()) {
                searchLoading = true;
                updateRows();
                runSearch(query);
            } else {
                loadHome();
            }
        }
    }

    /**
     * A long-press on a recent-search row (a stored SONG or ARTIST) removes just that entry after a
     * confirm — the per-item counterpart to the header's "clear all". Live result rows (no recentItem)
     * ignore the long-press. Returns true when it consumed the gesture.
     */
    private boolean onRecentLongClick(int position) {
        if (position < 0 || position >= rows.size()) {
            return false;
        }
        Row row = rows.get(position);
        final SvipeMusicSearchHistory.Item item = row.recentItem;
        if (item == null || getParentActivity() == null) {
            return false;
        }
        String label = item.isSong()
                ? (item.song != null ? item.song.shownTitle() : null)
                : (item.artist != null ? item.artist.shownName() : null);
        if (label == null || label.isEmpty()) {
            label = getString(R.string.SvipeRecentSearches);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(getString(R.string.ClearSearchSingleAlertTitle));
        b.setMessage(LocaleController.formatString(R.string.ClearSearchSingleUserAlertText, label));
        b.setPositiveButton(getString(R.string.ClearSearchRemove), (dialog, which) -> {
            history().remove(item);
            updateRows();
        });
        b.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(b.create());
        return true;
    }

    /**
     * Inline play/pause for a SONG search row's cover, played through the tab's own search queue and
     * MediaController — the "never played" search queue finally gets to play. Tapping the cover toggles
     * this song; when it isn't the resolved default yet (a fallback row) its default version is resolved
     * once, appended to the search queue and played.
     */
    private void playSongInline(SvipeMusic.Song s) {
        if (s == null || !s.playable || s.defaultTrack == null) {
            return;
        }
        MediaController mc = MediaController.getInstance();
        MessageObject playing = mc.getPlayingMessageObject();
        if (playing != null && isSamePlayingTrack(playing, s.defaultTrack)) {
            if (mc.isMessagePaused()) {
                mc.playMessage(playing);
            } else {
                mc.pauseMessage(playing);
            }
            notifySearchRows();
            return;
        }
        MessageObject mo = searchMo.get(s.id);
        if (mo != null && searchQueue != null) {
            searchQueue.play(mo);
            notifySearchRows();
            return;
        }
        // Fallback row (default didn't resolve at search time): resolve just this one and play it.
        if (songResolving.contains(s.id)) {
            return;
        }
        songResolving.add(s.id);
        final long sid = s.id;
        final SvipeMusic.Track t = s.defaultTrack;
        final ArrayList<SvipeMusic.Track> one = new ArrayList<>();
        one.add(t);
        SvipeMusicResolver.resolve(currentAccount, one, resolved -> {
            songResolving.remove(sid);
            if (searchQueue == null) {
                searchQueue = new SvipeMusicQueue(currentAccount, SvipeMusicQueue.SOURCE_SEARCH, "", false);
            }
            searchQueue.appendResolved(one, resolved);
            MessageObject built = searchQueue.messageForKey(t.key());
            if (built != null) {
                searchMo.put(sid, built);
                searchQueue.play(built);
            }
            notifySearchRows();
        });
    }

    /**
     * Play/toggle a ROW_SONG_AUDIO cell's message from the search queue — wired to the native cell's own
     * radial play control (setNeedPlayMessageListener), so the play button plays inline while the row
     * body still opens the song page. Returns true so the cell adopts its playing state.
     */
    private boolean playSearchMo(MessageObject mo) {
        if (mo == null || searchQueue == null) {
            return false;
        }
        MediaController mc = MediaController.getInstance();
        if (mc.isPlayingMessage(mo)) {
            if (mc.isMessagePaused()) {
                mc.playMessage(mo);
            }
            return true;
        }
        searchQueue.play(mo);
        return true;
    }

    /** Same underlying channel post as this catalog track, whichever queue minted the playing copy. */
    private boolean isSamePlayingTrack(MessageObject playing, SvipeMusic.Track t) {
        if (playing == null || t == null) {
            return false;
        }
        long dialogId = playing.getDialogId();
        return dialogId < 0 && t.channelId != 0 && -dialogId == t.channelId && playing.getRealId() == t.messageId;
    }

    private void notifySearchRows() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /** Artist row status line: the song count when known, else the plain "Artist" label. */
    private String artistStatusForCount(int songCount) {
        if (songCount > 0) {
            return LocaleController.formatPluralString("SvipeMusicSongCount", songCount);
        }
        return getString(R.string.SvipeMusicArtist);
    }

    private void onVibeTap() {
        SvipeMusicQueue active = SvipeMusicQueue.getActive();
        MediaController mc = MediaController.getInstance();
        MessageObject playing = mc.getPlayingMessageObject();
        if (active != null && SvipeMusicQueue.SOURCE_VIBE.equals(active.source) && playing != null && active.trackFor(playing) != null) {
            // Vibe already installed: the hero button is a play/pause toggle.
            if (mc.isMessagePaused()) {
                mc.playMessage(playing);
            } else {
                mc.pauseMessage(playing);
            }
            refreshVibe();
            return;
        }
        startVibe();
    }

    private void startVibe() {
        if (vibeLoading) {
            return;
        }
        // The app-start warm-up may already hold a resolved page — then this is instant and no
        // network stands between the tap and the first note.
        SvipeMusicWarmer.Warm warm = SvipeMusicWarmer.take();
        if (warm != null && warm.items != null && !warm.items.isEmpty()) {
            installVibe(warm.items, warm.resolved, warm.recommendationId, warm.cursor);
            return;
        }
        vibeLoading = true;
        refreshVibe();
        SvipeMusic.vibe(currentAccount, null, null, null, (items, recId, cursor, error) -> {
            if (items == null || items.isEmpty()) {
                vibeLoading = false;
                refreshVibe();
                return;
            }
            // Fresh My Vibe session (cursor was null; pagination uses a separate path). Tells the
            // backend to rotate the vibe epoch for the next session. Rides on the first item since the
            // event needs a reference; the backend ignores the track for VIBE_OPEN.
            SvipeMusicResolver.resolve(currentAccount, items, resolved ->
                    installVibe(items, resolved, recId, cursor));
        });
    }

    /**
     * Build the vibe queue and start playing. Shared by the live path and the warmed one, so a
     * pre-fetched page goes through exactly the same installation — including VIBE_OPEN, which
     * marks the session the USER started and must not fire when a background warm-up fetched a page
     * they may never listen to.
     */
    private void installVibe(java.util.List<SvipeMusic.Track> items,
                             java.util.Map<String, org.telegram.tgnet.TLRPC.Message> resolved,
                             String recId, String cursor) {
        vibeLoading = false;
        if (items == null || items.isEmpty()) {
            refreshVibe();
            return;
        }
        SvipeMusic.sendEvent(currentAccount, items.get(0), "VIBE_OPEN", null);
        SvipeMusicQueue queue = new SvipeMusicQueue(currentAccount, SvipeMusicQueue.SOURCE_VIBE, getString(R.string.MusicMyVibe), true);
        queue.recommendationId = recId;
        queue.setCursor(cursor);
        cacheResolved(resolved);
        queue.appendResolved(items, resolved);
        if (!queue.list.isEmpty()) {
            queue.play(queue.list.get(0));
        }
        refreshVibe();
    }

    private void onTrackTap(Row row) {
        SvipeMusic.Track track = row.track;
        MediaController mc = MediaController.getInstance();
        SvipeMusicQueue active = SvipeMusicQueue.getActive();
        MessageObject playing = mc.getPlayingMessageObject();
        if (active != null && playing != null) {
            SvipeMusic.Track playingTrack = active.trackFor(playing);
            if (playingTrack != null && playingTrack.key().equals(track.key())) {
                if (mc.isMessagePaused()) {
                    mc.playMessage(playing);
                } else {
                    mc.pauseMessage(playing);
                }
                adapter.notifyDataSetChanged();
                return;
            }
            MessageObject queued = active.messageForKey(track.key());
            if (queued != null) {
                mc.findMessageInPlaylistAndPlay(queued);
                adapter.notifyDataSetChanged();
                return;
            }
        }
        if (playRequestInFlight) {
            return;
        }
        playRequestInFlight = true;

        final ArrayList<SvipeMusic.Track> queueTracks = new ArrayList<>();
        String source;
        String title;
        if (inSearchMode()) {
            int idx = searchResults.indexOf(track);
            if (idx < 0) idx = 0;
            for (int i = idx; i < searchResults.size() && queueTracks.size() < PLAY_WINDOW; i++) {
                queueTracks.add(searchResults.get(i));
            }
            source = SvipeMusicQueue.SOURCE_SEARCH;
            title = getString(R.string.MusicTabTitle);
        } else if (row.section != null) {
            List<SvipeMusic.Track> st = row.section.tracks;
            int idx = st.indexOf(track);
            if (idx < 0) idx = 0;
            for (int i = idx; i < st.size() && queueTracks.size() < PLAY_WINDOW; i++) {
                queueTracks.add(st.get(i));
            }
            source = SvipeMusicQueue.SOURCE_SECTION;
            title = row.section.title != null && !row.section.title.isEmpty() ? row.section.title : sectionTitle(row.section.key);
        } else {
            queueTracks.add(track);
            source = SvipeMusicQueue.SOURCE_SECTION;
            title = getString(R.string.MusicTabTitle);
        }

        final ArrayList<SvipeMusic.Track> toResolve = new ArrayList<>();
        for (SvipeMusic.Track t : queueTracks) {
            if (!resolvedMessages.containsKey(t.key())) {
                toResolve.add(t);
            }
        }
        final String queueTitle = title;
        final String queueSource = source;
        SvipeMusicResolver.resolve(currentAccount, toResolve, resolved -> {
            playRequestInFlight = false;
            cacheResolved(resolved);
            HashMap<String, TLRPC.Message> all = new HashMap<>();
            for (SvipeMusic.Track t : queueTracks) {
                TLRPC.Message m = resolvedMessages.get(t.key());
                if (m != null) {
                    all.put(t.key(), m);
                }
            }
            SvipeMusicQueue queue = new SvipeMusicQueue(currentAccount, queueSource, queueTitle, false);
            queue.appendResolved(queueTracks, all);
            MessageObject first = queue.messageForKey(track.key());
            if (first == null && !queue.list.isEmpty()) {
                first = queue.list.get(0);
            }
            if (first != null) {
                queue.play(first);
            }
            adapter.notifyDataSetChanged();
        });
    }

    private void cacheResolved(Map<String, TLRPC.Message> resolved) {
        resolvedMessages.putAll(resolved);
    }

    private void toggleLike(SvipeMusic.Track track) {
        boolean liked = likedKeys.contains(track.key());
        if (liked) {
            likedKeys.remove(track.key());
        } else {
            likedKeys.add(track.key());
        }
        SvipeMusic.sendEvent(currentAccount, track, liked ? "UNLIKE" : "LIKE", null);
        adapter.notifyDataSetChanged();
    }

    private String sectionTitle(String key) {
        if ("liked".equals(key)) {
            return getString(R.string.MusicSectionLiked);
        } else if ("trending".equals(key)) {
            return getString(R.string.MusicSectionTrending);
        } else if ("fresh".equals(key)) {
            return getString(R.string.MusicSectionFresh);
        }
        return "";
    }

    private boolean isTrackPlaying(SvipeMusic.Track track, boolean[] paused) {
        SvipeMusicQueue active = SvipeMusicQueue.getActive();
        if (active == null) {
            return false;
        }
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return false;
        }
        SvipeMusic.Track pt = active.trackFor(playing);
        if (pt == null || !pt.key().equals(track.key())) {
            return false;
        }
        if (paused != null) {
            paused[0] = MediaController.getInstance().isMessagePaused();
        }
        return true;
    }

    /* thumbnails */

    private void requestThumb(SvipeMusic.Track track) {
        String key = track.key();
        if (!track.hasThumb || resolvedMessages.containsKey(key) || resolvingKeys.contains(key)) {
            return;
        }
        resolvingKeys.add(key);
        thumbQueue.add(track);
        if (thumbFlusher == null) {
            thumbFlusher = () -> {
                thumbFlusher = null;
                final ArrayList<SvipeMusic.Track> batch = new ArrayList<>(thumbQueue);
                thumbQueue.clear();
                if (batch.isEmpty()) {
                    return;
                }
                SvipeMusicResolver.resolve(currentAccount, batch, resolved -> {
                    for (SvipeMusic.Track t : batch) {
                        resolvingKeys.remove(t.key());
                    }
                    if (!resolved.isEmpty()) {
                        cacheResolved(resolved);
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    }
                });
            };
            AndroidUtilities.runOnUIThread(thumbFlusher, 200);
        }
    }

    private TLRPC.PhotoSize thumbFor(SvipeMusic.Track track, TLRPC.Document[] outDoc) {
        TLRPC.Message m = resolvedMessages.get(track.key());
        if (m == null || m.media == null || m.media.document == null) {
            return null;
        }
        TLRPC.Document doc = m.media.document;
        TLRPC.PhotoSize ps = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 90);
        if (ps == null || ps instanceof TLRPC.TL_photoSizeEmpty) {
            return null;
        }
        if (outDoc != null) {
            outDoc[0] = doc;
        }
        return ps;
    }

    /* notifications */

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingDidStart || id == NotificationCenter.messagePlayingPlayStateChanged
            || id == NotificationCenter.messagePlayingDidReset) {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            if (favPanel != null) {
                favPanel.notifyChanged();
            }
            refreshVibe();
        } else if (id == NotificationCenter.svipeFavouritesChanged) {
            rebuildFavourites();
        } else if (id == NotificationCenter.svipeArtistFavouritesChanged) {
            rebuildArtistFavourites();
        }
    }

    @Override
    public boolean isLightStatusBar() {
        // Home = dark immersive aura -> want light (white) status-bar icons -> not a light bar. The
        // favourites panel never reaches the status bar (it rests under the search pill), so opening it
        // does not change this.
        if (!inSearchMode() && !showingRecent()) {
            return false;
        }
        return androidx.core.graphics.ColorUtils.calculateLuminance(getThemedColor(Theme.key_windowBackgroundWhite)) > 0.7f;
    }

    /* MainTabsActivity.TabFragmentDelegate */

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        // Nested order left->right: Search | Songs Singers Most-listened | Profile. When the favourites
        // panel is open the inner ViewPagerFixed owns the horizontal swipe and does the interactive
        // follow+snap between its sub-tabs; the outer Search|Music|Profile pager only takes the swipe when
        // the inner pager is already at its edge in that direction. Returning false here leaves the drag
        // to the inner pager (which starts tracking via its own onInterceptTouchEvent); true hands it to
        // the outer pager. `forward` = finger-left = toward the next page (then Profile).
        if (favPanel != null && favPanel.isOpen() && favPanel.favPager != null) {
            int pos = favPanel.favPager.getCurrentPosition();
            int count = favPanel.favPager.adapter != null ? favPanel.favPager.adapter.getItemCount() : 3;
            if (forward && pos < count - 1) {
                return false;   // inner has a next sub-tab -> it consumes the swipe
            }
            if (!forward && pos > 0) {
                return false;   // inner has a previous sub-tab -> it consumes the swipe
            }
            // At the inner edge in this direction -> let the outer pager slide to Search / Profile.
        }
        return true;
    }

    @Override
    public void onParentScrollToTop() {
        if (inSearchMode()) {
            if (listView != null) {
                listView.smoothScrollToPosition(0);
            }
            return;
        }
        // Home: unwind the current favourites page, then close the panel, so a tab re-tap always lands
        // back on the hero rather than on a half-open panel.
        if (favPanel != null) {
            RecyclerListView cur = favPanel.currentList();
            if (cur != null) {
                cur.scrollToPosition(0);
            }
            favPanel.animateTo(false);
        }
    }

    /* adapter */

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == ROW_TRACK || type == ROW_RETRY || type == ROW_SONG || type == ROW_SONG_AUDIO
                    || type == ROW_ARTIST;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            if (viewType == ROW_SECTION) {
                TextView tv = new TextView(context);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                tv.setTypeface(AndroidUtilities.bold());
                tv.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                tv.setPadding(dp(20), dp(16), dp(20), dp(8));
                view = tv;
            } else if (viewType == ROW_TRACK) {
                view = new TrackCell(context);
            } else if (viewType == ROW_SONG) {
                view = new SongCell(context);
            } else if (viewType == ROW_ARTIST) {
                view = new ArtistCell(context);
            } else if (viewType == ROW_RECENT_HEADER) {
                view = new GraySectionCell(context, getResourceProvider());
            } else if (viewType == ROW_SONG_AUDIO) {
                // The native cell's own radial play control plays inline; the row body opens the page.
                SharedAudioCell cell = new SharedAudioCell(context, getResourceProvider());
                cell.setCheckForButtonPress(true);
                cell.setNeedPlayMessageListener(mo -> playSearchMo(mo));
                view = cell;
            } else if (viewType == ROW_EMPTY || viewType == ROW_RETRY || viewType == ROW_RECENT_EMPTY) {
                TextView tv = new TextView(context);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                tv.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(dp(20), dp(28), dp(20), dp(28));
                view = tv;
            } else {
                org.telegram.ui.Components.RadialProgressView progress = new org.telegram.ui.Components.RadialProgressView(context);
                progress.setSize(dp(28));
                FrameLayout wrap = new FrameLayout(context);
                wrap.addView(progress, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 24, 0, 24));
                view = wrap;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (row.type == ROW_SECTION) {
                TextView tv = (TextView) holder.itemView;
                String t = row.section.title;
                if (t == null || t.isEmpty()) {
                    t = sectionTitle(row.section.key);
                }
                tv.setText(t);
            } else if (row.type == ROW_TRACK) {
                ((TrackCell) holder.itemView).bind(row.track);
            } else if (row.type == ROW_SONG) {
                ((SongCell) holder.itemView).bind(row.song);
            } else if (row.type == ROW_ARTIST) {
                ((ArtistCell) holder.itemView).bind(row.artist);
            } else if (row.type == ROW_RECENT_HEADER) {
                ((GraySectionCell) holder.itemView).setText(getString(R.string.SvipeRecentSearches),
                        getString(R.string.SvipeClearSearchHistory), v -> {
                            history().clear();
                            updateRows();
                        });
            } else if (row.type == ROW_RECENT_EMPTY) {
                ((TextView) holder.itemView).setText(getString(R.string.SvipeNoSearchHistory));
            } else if (row.type == ROW_SONG_AUDIO) {
                SharedAudioCell cell = (SharedAudioCell) holder.itemView;
                MessageObject mo = searchMo.get(row.song.id);
                if (mo != null) {
                    cell.setMessageObject(mo, position != getItemCount() - 1);
                }
            } else if (row.type == ROW_EMPTY) {
                ((TextView) holder.itemView).setText(getString(R.string.MusicSearchEmpty));
            } else if (row.type == ROW_RETRY) {
                ((TextView) holder.itemView).setText(getString(R.string.MusicLoadFailed));
            }
        }
    }

    /* cells */

    // A canonical song row (search results): a CIRCULAR cover with an inline play/pause overlay + title
    // (+variant) + artist line + a version-count badge. Tapping the cover plays the song inline; tapping
    // anywhere else on the row opens the version picker (MusicSongActivity). A trackless Deezer
    // placeholder has no playable track, so it shows no play button and its cover is muted.
    private class SongCell extends FrameLayout {
        private final FrameLayout cover;
        private final TextView letterView;
        private final BackupImageView coverImage;
        private final ImageView playOverlay;
        private final TextView titleView;
        private final TextView subtitleView;
        private final TextView badgeView;
        private SvipeMusic.Song song;

        SongCell(Context context) {
            super(context);
            setPadding(dp(16), dp(6), dp(12), dp(6));

            cover = new FrameLayout(context);
            // Circular (radius = half the 48dp cover) — deliberately unlike the rounded-SQUARE artist art.
            cover.setBackground(Theme.createRoundRectDrawable(dp(24), getThemedColor(Theme.key_windowBackgroundGray)));
            addView(cover, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            letterView = new TextView(context);
            letterView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            letterView.setTypeface(AndroidUtilities.bold());
            letterView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            letterView.setGravity(Gravity.CENTER);
            cover.addView(letterView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            // The real Deezer album cover, drawn over the letter tile once a song is enriched; while it
            // is transparent (unset / loading) the letter shows through, so an unenriched row is unchanged.
            coverImage = new BackupImageView(context);
            coverImage.setRoundRadius(dp(24));
            cover.addView(coverImage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            // Inline play/pause control over the circular cover; its own click plays the song inline while
            // the rest of the row still opens the song page.
            playOverlay = new ImageView(context);
            playOverlay.setScaleType(ImageView.ScaleType.CENTER);
            playOverlay.setBackground(Theme.createRoundRectDrawable(dp(24), 0x66000000));
            playOverlay.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.MULTIPLY));
            playOverlay.setOnClickListener(v -> {
                if (song != null) {
                    playSongInline(song);
                }
            });
            cover.addView(playOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 76, 0, 56, 0));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(titleView);

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            badgeView = new TextView(context);
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            badgeView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            badgeView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            addView(badgeView, LayoutHelper.createFrame(52, LayoutHelper.MATCH_PARENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        }

        void bind(SvipeMusic.Song s) {
            song = s;
            String title = s.shownTitle() != null && !s.shownTitle().isEmpty() ? s.shownTitle() : getString(R.string.AudioUnknownTitle);
            if (s.variantLabel != null && !s.variantLabel.isEmpty()) {
                title = title + " (" + s.variantLabel + ")";
            }
            titleView.setText(title);
            letterView.setText(title.isEmpty() ? "♪" : title.substring(0, 1).toUpperCase());
            String artistLine = s.shownArtist();
            subtitleView.setText(artistLine.isEmpty() ? getString(R.string.AudioUnknownArtist) : artistLine);
            // A Deezer placeholder (catalog-missing) shows "+" (addable) instead of a version count.
            badgeView.setText(!s.playable ? "+" : (s.versionCount > 1 ? (s.versionCount + "  ›") : "›"));

            // Playable songs get the inline play button; trackless placeholders are muted (no button).
            boolean canPlay = s.playable && s.defaultTrack != null;
            cover.setAlpha(canPlay ? 1f : 0.45f);
            if (canPlay) {
                playOverlay.setVisibility(VISIBLE);
                MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                boolean isPlaying = playing != null && isSamePlayingTrack(playing, s.defaultTrack)
                        && !MediaController.getInstance().isMessagePaused();
                playOverlay.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
            } else {
                playOverlay.setVisibility(GONE);
            }

            // Real Deezer cover (small) when enriched; else clear it so the letter tile shows. Clearing is
            // required because cells are recycled — a stale cover must not bleed onto an unenriched song.
            String coverUrl = s.coverSmallUrl != null && !s.coverSmallUrl.isEmpty() ? s.coverSmallUrl
                    : (s.coverUrl != null && !s.coverUrl.isEmpty() ? s.coverUrl : null);
            if (coverUrl != null) {
                coverImage.setVisibility(VISIBLE);
                coverImage.setImage(ImageLocation.getForPath(coverUrl), "48_48", (Drawable) null, null);
            } else {
                coverImage.setImageDrawable(null);
                coverImage.setVisibility(GONE);
            }
        }
    }

    // A canonical artist row (search results): a rounded-SQUARE cover (letter tile + Deezer photo), the
    // artist name, and a song-count status. The square art is deliberately unlike the circular song
    // cover, so artists and songs are told apart at a glance. Tapping opens the artist page.
    private class ArtistCell extends FrameLayout {
        private final TextView letterView;
        private final BackupImageView photoImage;
        private final TextView titleView;
        private final TextView subtitleView;

        ArtistCell(Context context) {
            super(context);
            setPadding(dp(16), dp(6), dp(12), dp(6));
            setBackground(Theme.getSelectorDrawable(false));

            FrameLayout art = new FrameLayout(context);
            art.setBackground(Theme.createRoundRectDrawable(dp(6), getThemedColor(Theme.key_windowBackgroundGray)));
            addView(art, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            letterView = new TextView(context);
            letterView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            letterView.setTypeface(AndroidUtilities.bold());
            letterView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            letterView.setGravity(Gravity.CENTER);
            art.addView(letterView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            photoImage = new BackupImageView(context);
            photoImage.setRoundRadius(dp(6));
            art.addView(photoImage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 76, 0, 16, 0));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(titleView);

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        void bind(SvipeMusic.Artist a) {
            String shown = a.shownName();
            String name = shown != null && !shown.isEmpty() ? shown : getString(R.string.AudioUnknownArtist);
            titleView.setText(name);
            letterView.setText(name.isEmpty() ? "♪" : name.substring(0, 1).toUpperCase());
            subtitleView.setText(artistStatusForCount(a.songCount));

            String photo = a.photoUrl != null && !a.photoUrl.isEmpty() ? a.photoUrl : null;
            if (photo != null) {
                photoImage.setVisibility(VISIBLE);
                photoImage.setImage(ImageLocation.getForPath(photo), "48_48", (Drawable) null, null);
            } else {
                photoImage.setImageDrawable(null);
                photoImage.setVisibility(GONE);
            }
        }
    }

    // A "most listened" song row (fav panel): a rounded-square cover, title, and a listen-time label
    // ("Hh Mm • N plays"). Tapping opens the song page.
    private class MostListenedCell extends FrameLayout {
        private final TextView letterView;
        private final BackupImageView coverImage;
        private final TextView titleView;
        private final TextView subtitleView;

        MostListenedCell(Context context) {
            super(context);
            setPadding(dp(16), dp(6), dp(12), dp(6));
            setBackground(Theme.getSelectorDrawable(false));

            FrameLayout cover = new FrameLayout(context);
            cover.setBackground(Theme.createRoundRectDrawable(dp(6), getThemedColor(Theme.key_windowBackgroundGray)));
            addView(cover, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            letterView = new TextView(context);
            letterView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            letterView.setTypeface(AndroidUtilities.bold());
            letterView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            letterView.setGravity(Gravity.CENTER);
            cover.addView(letterView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            coverImage = new BackupImageView(context);
            coverImage.setRoundRadius(dp(6));
            cover.addView(coverImage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 76, 0, 16, 0));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(titleView);

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        void bind(SvipeMusic.ListenedSong s) {
            String title = s.shownTitle() != null && !s.shownTitle().isEmpty() ? s.shownTitle() : getString(R.string.AudioUnknownTitle);
            titleView.setText(title);
            letterView.setText(title.isEmpty() ? "♪" : title.substring(0, 1).toUpperCase());
            subtitleView.setText(LocaleController.formatString("SvipeListenTimeLabel", R.string.SvipeListenTimeLabel,
                    formatListenTime(s.totalMs), s.plays));

            String cover = s.coverSmallUrl != null && !s.coverSmallUrl.isEmpty() ? s.coverSmallUrl
                    : (s.coverUrl != null && !s.coverUrl.isEmpty() ? s.coverUrl : null);
            if (cover != null) {
                coverImage.setVisibility(VISIBLE);
                coverImage.setImage(ImageLocation.getForPath(cover), "48_48", (Drawable) null, null);
            } else {
                coverImage.setImageDrawable(null);
                coverImage.setVisibility(GONE);
            }
        }
    }

    // The full-screen "My Vibe" home: a single hero play/pause button floating over a slow,
    // music-reactive gradient (Yandex-Music "Моя волна" style). The aura animates only while a vibe
    // track is actually playing, so the background "breathes" with the music.
    private class VibeScreen extends FrameLayout {

        private final AuraView aura;
        private final TextView titleView;
        private final TextView subtitleView;
        private final ImageView playButton;
        private final PlayPauseDrawable playPauseDrawable;
        private final org.telegram.ui.Components.RadialProgressView progressView;

        private float recede;

        VibeScreen(Context context) {
            super(context);

            // Tapping anywhere on the vibe screen toggles play/pause (or starts the vibe). The
            // floating search bar sits on top as a separate view, so it still gets its own taps.
            setOnClickListener(v -> onVibeTap());

            // Corners are square at rest and round out as the card recedes, so pulling the favourites
            // panel up reads as this card sinking backwards rather than as a torn-off rectangle.
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(28) * recede);
                }
            });
            setClipToOutline(true);

            aura = new AuraView(context);
            addView(aura, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            LinearLayout center = new LinearLayout(context);
            center.setOrientation(LinearLayout.VERTICAL);
            center.setGravity(Gravity.CENTER_HORIZONTAL);
            addView(center, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setTextColor(0xFFFFFFFF);
            titleView.setGravity(Gravity.CENTER);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setShadowLayer(dp(12), 0, dp(1), 0x66000000);
            center.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 36, 0, 36, 0));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            subtitleView.setTextColor(0xCCFFFFFF);
            subtitleView.setGravity(Gravity.CENTER);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            subtitleView.setShadowLayer(dp(10), 0, dp(1), 0x55000000);
            center.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 36, 8, 36, 0));

            // Telegram's native video-player play button, 1:1 — the translucent dark disc
            // (circle_big) + the animated white play/pause glyph on top, as one unit. Identical
            // dimensions to PhotoViewer: 64dp disc with PlayPauseDrawable(28).
            FrameLayout button = new FrameLayout(context);
            button.setBackground(ContextCompat.getDrawable(context, R.drawable.circle_big));
            button.setOnClickListener(v -> onVibeTap());
            center.addView(button, LayoutHelper.createLinear(64, 64, Gravity.CENTER_HORIZONTAL, 0, 44, 0, 0));

            playPauseDrawable = new PlayPauseDrawable(28);
            playPauseDrawable.setDuration(200);
            playPauseDrawable.setColor(0xFFFFFFFF);
            playPauseDrawable.setPause(false, false);

            playButton = new ImageView(context);
            playButton.setScaleType(ImageView.ScaleType.CENTER);
            playButton.setImageDrawable(playPauseDrawable);
            playPauseDrawable.setParent(playButton);
            button.addView(playButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            progressView = new org.telegram.ui.Components.RadialProgressView(context);
            progressView.setSize(dp(22));
            progressView.setProgressColor(0xFFFFFFFF);
            progressView.setVisibility(GONE);
            button.addView(progressView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

            update();
        }

        /**
         * How far this card has sunk behind the favourites panel, 0..1. It shrinks toward its own
         * centre, dims, and rounds its corners — the panel then slides up over it.
         */
        void setRecede(float progress) {
            recede = progress;
            float scale = 1f - 0.10f * progress;
            setPivotX(getWidth() / 2f);
            setPivotY(getHeight() / 2f);
            setScaleX(scale);
            setScaleY(scale);
            // Fade right out, and be GONE a little before the end of the travel: the search pill is
            // translucent, so a card still lingering behind it at the top reads as a smear.
            float alpha = Math.max(0f, 1f - progress / 0.85f);
            setAlpha(alpha);
            setVisibility(alpha <= 0.01f ? INVISIBLE : VISIBLE);
            invalidateOutline();
        }

        void update() {
            SvipeMusicQueue active = SvipeMusicQueue.getActive();
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            boolean isVibe = active != null && SvipeMusicQueue.SOURCE_VIBE.equals(active.source)
                && mo != null && active.trackFor(mo) != null;
            boolean playing = isVibe && !MediaController.getInstance().isMessagePaused();

            if (vibeLoading) {
                playButton.setVisibility(GONE);
                progressView.setVisibility(VISIBLE);
            } else {
                progressView.setVisibility(GONE);
                playButton.setVisibility(VISIBLE);
                // setPause(true) => pause bars (playing); setPause(false) => play triangle.
                playPauseDrawable.setPause(playing);
            }

            if (isVibe && mo != null) {
                titleView.setText(mo.getMusicTitle());
                subtitleView.setText(mo.getMusicAuthor());
            } else {
                titleView.setText(getString(R.string.MusicMyVibe));
                subtitleView.setText(getString(R.string.MusicMyVibeInfo));
            }

            aura.setPlaying(playing);
        }
    }

    // Ambient animated gradient: a handful of soft radial "blobs" drifting on slow circular paths.
    // Their overlap over a dark base yields a fluid, ever-shifting wash of colour. The drift only
    // advances while playing, so the screen visibly settles when the music is paused.
    private static class AuraView extends View {

        private static final int[] BLOB_COLORS = {0xFF7C4DFF, 0xFF3D5AFE, 0xFF00B0FF, 0xFFFF4081};

        private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float phase;
        private ValueAnimator animator;

        AuraView(Context context) {
            super(context);
        }

        void setPlaying(boolean playing) {
            if (playing) {
                if (animator == null) {
                    animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
                    animator.setDuration(18000);
                    animator.setRepeatCount(ValueAnimator.INFINITE);
                    animator.setInterpolator(new android.view.animation.LinearInterpolator());
                    animator.addUpdateListener(a -> {
                        phase = (float) a.getAnimatedValue();
                        invalidate();
                    });
                }
                if (!animator.isStarted()) {
                    animator.start();
                } else if (animator.isPaused()) {
                    animator.resume();
                }
            } else if (animator != null && animator.isRunning()) {
                animator.pause();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) {
                return;
            }
            canvas.drawColor(0xFF120A22);
            float radius = Math.max(w, h) * 0.8f;
            int save = canvas.saveLayer(0, 0, w, h, null);
            for (int i = 0; i < BLOB_COLORS.length; i++) {
                double t = phase * (0.6 + 0.16 * i) + i * (Math.PI * 2 / BLOB_COLORS.length);
                float cx = (float) (w * (0.5 + 0.32 * Math.cos(t)));
                float cy = (float) (h * (0.5 + 0.32 * Math.sin(t * 1.2 + i)));
                int rgb = BLOB_COLORS[i] & 0x00FFFFFF;
                RadialGradient rg = new RadialGradient(cx, cy, radius,
                    rgb | 0xCC000000, rgb, Shader.TileMode.CLAMP);
                blobPaint.setShader(rg);
                canvas.drawRect(0, 0, w, h, blobPaint);
            }
            canvas.restoreToCount(save);
            blobPaint.setShader(null);
        }
    }

    private class TrackCell extends FrameLayout {

        private final BackupImageView cover;
        private final ImageView playOverlay;
        private final TextView titleView;
        private final TextView subtitleView;
        private final ImageView likeView;
        private SvipeMusic.Track track;

        TrackCell(Context context) {
            super(context);
            setBackground(Theme.getSelectorDrawable(false));

            cover = new BackupImageView(context);
            cover.setRoundRadius(dp(8));
            addView(cover, LayoutHelper.createFrame(46, 46, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

            playOverlay = new ImageView(context);
            playOverlay.setScaleType(ImageView.ScaleType.CENTER);
            playOverlay.setBackground(Theme.createRoundRectDrawable(dp(8), 0x66000000));
            playOverlay.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.MULTIPLY));
            playOverlay.setVisibility(GONE);
            addView(playOverlay, LayoutHelper.createFrame(46, 46, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 74, 0, 56, 0));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(titleView);

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            likeView = new ImageView(context);
            likeView.setScaleType(ImageView.ScaleType.CENTER);
            likeView.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(20)));
            likeView.setOnClickListener(v -> {
                if (track != null) {
                    toggleLike(track);
                }
            });
            addView(likeView, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(62), MeasureSpec.EXACTLY));
        }

        void bind(SvipeMusic.Track t) {
            track = t;
            titleView.setText(t.title != null && !t.title.isEmpty() ? t.title : getString(R.string.AudioUnknownTitle));
            String performer = t.performer != null && !t.performer.isEmpty() ? t.performer : getString(R.string.AudioUnknownArtist);
            String dur = AndroidUtilities.formatShortDuration(Math.max(0, t.durationS));
            subtitleView.setText(performer + " · " + dur);

            boolean[] paused = new boolean[1];
            boolean playing = isTrackPlaying(t, paused);
            titleView.setTextColor(getThemedColor(playing ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundWhiteBlackText));
            if (playing) {
                playOverlay.setVisibility(VISIBLE);
                playOverlay.setImageResource(paused[0] ? R.drawable.ic_play : R.drawable.ic_pause);
            } else {
                playOverlay.setVisibility(GONE);
            }

            boolean liked = likedKeys.contains(t.key());
            likeView.setImageResource(liked ? R.drawable.media_like_active : R.drawable.media_like);
            likeView.setColorFilter(liked ? null : new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2), PorterDuff.Mode.MULTIPLY));

            TLRPC.Document[] doc = new TLRPC.Document[1];
            TLRPC.PhotoSize ps = thumbFor(t, doc);
            Drawable placeholder = coverPlaceholder();
            if (ps != null) {
                cover.setImage(ImageLocation.getForDocument(ps, doc[0]), "46_46", placeholder, MusicActivity.this);
            } else {
                cover.setImageDrawable(placeholder);
                requestThumb(t);
            }
        }
    }

    private Drawable coverPlaceholder() {
        Drawable base = Theme.createRoundRectDrawable(dp(8), getThemedColor(Theme.key_dialogSearchBackground));
        Drawable icon = org.telegram.messenger.ApplicationLoader.applicationContext.getResources().getDrawable(R.drawable.search_music_filled).mutate();
        icon.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2), PorterDuff.Mode.MULTIPLY));
        CombinedDrawable cd = new CombinedDrawable(base, icon);
        cd.setCustomSize(dp(46), dp(46));
        cd.setIconSize(dp(24), dp(24));
        return cd;
    }
}
