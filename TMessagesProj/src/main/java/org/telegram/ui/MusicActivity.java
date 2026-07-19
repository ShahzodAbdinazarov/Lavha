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
import org.telegram.svipe.SvipeMusicTelemetry;
import org.telegram.svipe.SvipeSearchLog;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
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

    /** The panel's two tabs. Songs is index 0 and is what the panel opens on. */
    private static final int FAV_TAB_SONGS = 0;
    private static final int FAV_TAB_SINGERS = 1;

    private static final int SEARCH_MIN_CHARS = 2;
    private static final int SEARCH_PAGE = 50;
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
     * The panel holds two tabs over ONE list: the strip only chooses which collection the single
     * adapter reads from. A second RecyclerView would have to be threaded through the blur capture,
     * the insets, the drag's listAtTop() and the scroll-to-top handler for no gain. */
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
    private final ArrayList<SvipeMusic.Track> searchResults = new ArrayList<>();
    // Each result's default version resolved to a real audio MessageObject, so the row renders as a
    // native SharedAudioCell (album art + play/download + duration) exactly like the chats media search.
    // The queue only exists to wrap resolved channel messages into MessageObjects — it is never played.
    private final HashMap<Long, MessageObject> searchMo = new HashMap<>();
    private SvipeMusicQueue searchQueue;
    private boolean searchLoading;
    private boolean searchFailed;
    private Runnable pendingSearch;
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
                if (inSearchMode() || favPanel == null) {
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
        fragmentContextView.isInsideBubble = true;
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
        final RecyclerListView innerListView;
        private final FavAdapter favAdapter;

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

            innerListView = new RecyclerListView(context);
            innerListView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
            innerListView.setGlowColor(0);
            innerListView.setClipToPadding(false);
            innerListView.setVerticalScrollBarEnabled(false);
            innerListView.setAlpha(0f);
            innerListView.setAdapter(favAdapter = new FavAdapter());
            innerListView.setOnItemClickListener((view, position) -> onFavouriteClick(position));
            innerListView.setOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    if (iBlur3Active && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                        scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                    }
                }
            });
            addView(innerListView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            tabStrip = new ScrollSlidingTextTabStrip(context, getResourceProvider());
            tabStrip.setColors(Theme.key_profile_tabSelectedLine, Theme.key_profile_tabSelectedText, Theme.key_profile_tabText, Theme.key_profile_tabSelector);
            tabStrip.setUseMinimalWidth(true);
            tabStrip.addTextTab(FAV_TAB_SONGS, getString(R.string.SvipeFavouriteSongs));
            tabStrip.addTextTab(FAV_TAB_SINGERS, getString(R.string.SvipeFavouriteSingers));
            tabStrip.finishAddingTabs();
            // Songs is the default: the tab ids ARE the FAV_TAB_* indices, so the strip's id is the
            // adapter's mode and nothing has to map between them.
            tabStrip.setInitialTabId(FAV_TAB_SONGS);
            tabStrip.setDelegate(new ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate() {
                @Override
                public void onPageSelected(int page, boolean forward) {
                    // Data source only — the panel's travel, drag state and the hero's recede are
                    // untouched, so switching tabs never moves the sheet.
                    setFavTab(page);
                }

                @Override
                public void onPageScrolled(float progress) {
                    // No pager behind the strip; the list swaps outright on selection.
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

        /** Rows start below the strip and clear the bottom tabs. */
        void applyInsets() {
            innerListView.setPadding(0, panelHeaderHeight(), 0, listBottomPadding());
        }

        void notifyChanged() {
            favAdapter.notifyDataSetChanged();
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
            innerListView.setAlpha(contentAlpha);
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

        /** True when the list is scrolled to its very top (so a downward drag closes the panel). */
        private boolean listAtTop() {
            return !innerListView.canScrollVertically(-1);
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

        /** Select a tab as if it had been tapped — the strip's delegate drives {@link #setFavTab}. */
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
     * Rows of the favourites panel for WHICHEVER tab is selected: a resolved audio cell, a plain card,
     * an artist row, or the empty state. One adapter over two collections rather than two adapters —
     * everything around the list (blur capture, insets, the drag's listAtTop(), scroll-to-top) is wired
     * to the single innerListView, and {@link #favTab} is the only thing that varies.
     */
    private class FavAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            if (type == ROW_FAV_EMPTY) {
                return false;
            }
            if (type == ROW_FAV_ARTIST) {
                return true;    // an artist row always has a page to open
            }
            // Don't offer a ripple on a row whose tap could not do anything.
            int pos = holder.getAdapterPosition();
            return pos >= 0 && pos < favourites.size() && isFavouriteActionable(favourites.get(pos));
        }

        @Override
        public int getItemCount() {
            if (favTab == FAV_TAB_SINGERS) {
                return artistFavourites.isEmpty() ? 1 : artistFavourites.size();
            }
            return favourites.isEmpty() ? 1 : favourites.size();
        }

        @Override
        public int getItemViewType(int position) {
            if (favTab == FAV_TAB_SINGERS) {
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
            } else if (viewType == ROW_FAV_ARTIST) {
                // The song page's artist row verbatim: UserCell is the profile's own person row and
                // takes an explicit name/status, so it needs no peer behind it.
                view = new UserCell(context, 6, 0, false, getResourceProvider());
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
            if (type == ROW_FAV_EMPTY) {
                // One empty view serves both tabs — only the sentence differs, so it is set here rather
                // than baked in at create time: the view survives a tab switch by recycling.
                ((TextView) holder.itemView).setText(getString(favTab == FAV_TAB_SINGERS
                        ? R.string.SvipeFavouriteSingersEmpty : R.string.SvipeFavouritesEmpty));
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
     * Point the panel's one list at the other tab.
     *
     * <p>Deliberately touches nothing but the data source: the panel's translation, its drag state and
     * the hero's recede are all driven by {@code progress}, which this never reads or writes, so a tab
     * switch cannot move the sheet or interrupt a settle animation in flight.
     */
    private void setFavTab(int tab) {
        if (favTab == tab) {
            return;
        }
        favTab = tab;
        if (favPanel != null) {
            favPanel.notifyChanged();
            // The two lists have unrelated lengths, so without this the incoming one inherits the
            // outgoing one's scroll offset and can arrive already scrolled part-way down.
            favPanel.innerListView.scrollToPosition(0);
        }
    }

    /**
     * Rebuild the favourite singers from the local store. Nothing to resolve here: an artist row is
     * name + photo + count, all of it cached in the entry, and the tap opens a page rather than playing.
     */
    private void rebuildArtistFavourites() {
        artistFavourites.clear();
        artistFavourites.addAll(SvipeArtistFavouritesSet.getInstance(currentAccount).list());
        if (favPanel != null && favTab == FAV_TAB_SINGERS) {
            favPanel.notifyChanged();
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
            favPanel.notifyChanged();
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
                favPanel.notifyChanged();
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

    private void onFavouriteClick(int position) {
        if (favTab == FAV_TAB_SINGERS) {
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
                favPanel.notifyChanged();
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
        // The list now only serves search; "home" is the full-screen VibeScreen, not a row list.
        rows.clear();
        if (inSearchMode()) {
            if (searchLoading) {
                rows.add(new Row(ROW_LOADING));
            } else if (searchFailed) {
                rows.add(new Row(ROW_RETRY));
            } else if (songResults.isEmpty()) {
                rows.add(new Row(ROW_EMPTY));
            } else {
                for (SvipeMusic.Song s : songResults) {
                    // Native audio row once the default version resolved; letter-cell fallback otherwise.
                    Row r = new Row(searchMo.containsKey(s.id) ? ROW_SONG_AUDIO : ROW_SONG);
                    r.song = s;
                    rows.add(r);
                }
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateMode();
    }

    // Swap between the search list and the home scroller (vibe hero + favourites) based on whether a
    // query is active. The hero is a row of the home scroller now, so hiding that hides both.
    private void updateMode() {
        boolean search = inSearchMode();
        if (listView != null) {
            listView.setVisibility(search ? View.VISIBLE : View.GONE);
        }
        if (vibeScreen != null) {
            vibeScreen.setVisibility(search ? View.GONE : View.VISIBLE);
        }
        if (favPanel != null) {
            favPanel.setVisibility(search ? View.GONE : View.VISIBLE);
        }
        if (vibeScreen != null && !search) {
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
        } else if (row.type == ROW_SONG || row.type == ROW_SONG_AUDIO) {
            if (row.song != null) {
                // Search-history: a tapped result means they found what they searched for. Tapping a
                // Deezer placeholder is an even stronger demand signal (kind='deezer').
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
            SvipeMusic.sendEvent(currentAccount, items.get(0), "VIBE_OPEN", null);
            SvipeMusicQueue queue = new SvipeMusicQueue(currentAccount, SvipeMusicQueue.SOURCE_VIBE, getString(R.string.MusicMyVibe), true);
            queue.recommendationId = recId;
            queue.setCursor(cursor);
            SvipeMusicResolver.resolve(currentAccount, items, resolved -> {
                vibeLoading = false;
                cacheResolved(resolved);
                queue.appendResolved(items, resolved);
                if (!queue.list.isEmpty()) {
                    queue.play(queue.list.get(0));
                }
                refreshVibe();
            });
        });
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
        if (!inSearchMode()) {
            return false;
        }
        return androidx.core.graphics.ColorUtils.calculateLuminance(getThemedColor(Theme.key_windowBackgroundWhite)) > 0.7f;
    }

    /* MainTabsActivity.TabFragmentDelegate */

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
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
        // Home: unwind the favourites list, then close the panel, so a tab re-tap always lands back on
        // the hero rather than on a half-open panel.
        if (favPanel != null) {
            favPanel.innerListView.scrollToPosition(0);
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
            return type == ROW_TRACK || type == ROW_RETRY || type == ROW_SONG || type == ROW_SONG_AUDIO;
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
            } else if (viewType == ROW_SONG_AUDIO) {
                view = new SharedAudioCell(context, getResourceProvider());
            } else if (viewType == ROW_EMPTY || viewType == ROW_RETRY) {
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

    // A canonical song row (search results): letter cover + title (+variant) + artist line + a
    // version-count badge. Tapping opens the version picker (MusicSongActivity).
    private class SongCell extends FrameLayout {
        private final TextView letterView;
        private final BackupImageView coverImage;
        private final TextView titleView;
        private final TextView subtitleView;
        private final TextView badgeView;

        SongCell(Context context) {
            super(context);
            setPadding(dp(16), dp(6), dp(12), dp(6));

            FrameLayout cover = new FrameLayout(context);
            cover.setBackground(Theme.createRoundRectDrawable(dp(10), getThemedColor(Theme.key_windowBackgroundGray)));
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
            coverImage.setRoundRadius(dp(10));
            cover.addView(coverImage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

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

            // Real Deezer cover (small) when enriched; else clear it so the letter tile shows. Clearing is
            // required because cells are recycled — a stale cover must not bleed onto an unenriched song.
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
