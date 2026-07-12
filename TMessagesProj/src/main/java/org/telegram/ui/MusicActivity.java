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
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.svipe.SvipeMusic;
import org.telegram.svipe.SvipeMusicQueue;
import org.telegram.svipe.SvipeMusicResolver;
import org.telegram.svipe.SvipeMusicTelemetry;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.RecyclerListView;

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
    private static final int ROW_SONG = 6;      // canonical song card (search results)

    private static final int SEARCH_MIN_CHARS = 2;
    private static final int SEARCH_PAGE = 50;
    private static final int PLAY_WINDOW = 60;

    private boolean hasMainTabs;
    private int additionNavigationBarHeight;

    private FrameLayout root;
    private EditTextBoldCursor searchField;
    private ImageView searchClear;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private ListAdapter adapter;
    private VibeScreen vibeScreen;
    private MiniPlayerView miniPlayer;

    private final ArrayList<SvipeMusic.Section> sections = new ArrayList<>();
    private boolean homeLoading;
    private boolean homeLoaded;
    private boolean homeFailed;

    private String query = "";
    private String searchedQuery;
    // Search now returns canonical SONGS (1 card = 1 real song); tapping opens the version picker.
    private final ArrayList<SvipeMusic.Song> songResults = new ArrayList<>();
    private final ArrayList<SvipeMusic.Track> searchResults = new ArrayList<>();
    private boolean searchLoading;
    private boolean searchFailed;
    private Runnable pendingSearch;

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
        nc.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        SvipeMusicTelemetry.getInstance(currentAccount).attach();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        super.onFragmentDestroy();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View createView(Context context) {
        hasOwnBackground = true;
        actionBar.setAddToContainer(false);

        root = new FrameLayout(context);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        // Full-screen music-reactive backdrop + centered hero button (the "home" screen). It sits at
        // the very back and spans under the status bar, so the gradient reaches the top edge.
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
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Floating search bar over everything — no title, just the pill. Container is transparent so
        // the backdrop shows through around it (Yandex-Music style).
        FrameLayout searchContainer = new FrameLayout(context);
        searchContainer.setPadding(0, AndroidUtilities.statusBarHeight + dp(8), 0, dp(8));
        root.addView(searchContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        FrameLayout searchBox = new FrameLayout(context);
        searchBox.setBackground(Theme.createRoundRectDrawable(dp(12), getThemedColor(Theme.key_dialogSearchBackground)));
        searchContainer.addView(searchBox, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 42, Gravity.TOP, 16, 0, 16, 0));

        ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.outline_header_search);
        searchIcon.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogSearchHint), PorterDuff.Mode.MULTIPLY));
        searchBox.addView(searchIcon, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        searchField = new EditTextBoldCursor(context);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        searchField.setHint(getString(R.string.MusicSearchHint));
        searchField.setHintTextColor(getThemedColor(Theme.key_dialogSearchHint));
        searchField.setTextColor(getThemedColor(Theme.key_dialogSearchText));
        searchField.setBackground(null);
        searchField.setSingleLine(true);
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchField.setCursorColor(getThemedColor(Theme.key_dialogSearchText));
        searchField.setCursorSize(dp(19));
        searchField.setCursorWidth(1.5f);
        searchBox.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 44, 0, 40, 0));
        searchField.addTextChangedListener(new TextWatcher() {
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

        searchClear = new ImageView(context);
        searchClear.setImageResource(R.drawable.miniplayer_close);
        searchClear.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogSearchHint), PorterDuff.Mode.MULTIPLY));
        searchClear.setScaleType(ImageView.ScaleType.CENTER);
        searchClear.setVisibility(View.GONE);
        searchClear.setOnClickListener(v -> {
            searchField.setText("");
            AndroidUtilities.hideKeyboard(searchField);
        });
        searchBox.addView(searchClear, LayoutHelper.createFrame(40, LayoutHelper.MATCH_PARENT, Gravity.RIGHT));

        miniPlayer = new MiniPlayerView(context);
        miniPlayer.setVisibility(View.GONE);
        int miniBottom = AndroidUtilities.navigationBarHeight + additionNavigationBarHeight + dp(6);
        root.addView(miniPlayer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 58, Gravity.BOTTOM, 10, 0, 10, miniBottom / AndroidUtilities.density));

        fragmentView = root;
        updateRows();
        ensureHomeLoaded();
        miniPlayer.update(false);
        return root;
    }

    // Reserve room for the floating search bar (status bar + pill + margins) so the first result
    // isn't hidden underneath it.
    private int listTopPadding() {
        return AndroidUtilities.statusBarHeight + dp(58);
    }

    private int listBottomPadding() {
        int pad = AndroidUtilities.navigationBarHeight + additionNavigationBarHeight + dp(12);
        if (miniPlayer != null && miniPlayer.getVisibility() == View.VISIBLE) {
            pad += dp(66);
        }
        return pad;
    }

    private void refreshListPadding() {
        if (listView != null) {
            listView.setPadding(0, listTopPadding(), 0, listBottomPadding());
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
        searchClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        if (pendingSearch != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingSearch);
            pendingSearch = null;
        }
        if (query.length() < SEARCH_MIN_CHARS) {
            searchedQuery = null;
            songResults.clear();
            searchLoading = false;
            searchFailed = false;
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
            searchLoading = false;
            searchedQuery = q;
            songResults.clear();
            if (items == null) {
                searchFailed = true;
            } else {
                searchFailed = false;
                songResults.addAll(items);
            }
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
                    Row r = new Row(ROW_SONG);
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

    // Swap between the search list and the immersive vibe screen based on whether a query is active.
    private void updateMode() {
        boolean search = inSearchMode();
        if (listView != null) {
            listView.setVisibility(search ? View.VISIBLE : View.GONE);
        }
        if (vibeScreen != null) {
            vibeScreen.setVisibility(search ? View.GONE : View.VISIBLE);
            if (!search) {
                vibeScreen.update();
            }
        }
        // Home shows the dark immersive backdrop (light status-bar icons); search shows the opaque
        // white list (dark icons). Re-apply on every switch.
        if (getParentActivity() != null) {
            AndroidUtilities.setLightStatusBar(getParentActivity().getWindow(), isLightStatusBar());
        }
    }

    private void refreshVibe() {
        if (vibeScreen != null && vibeScreen.getVisibility() == View.VISIBLE) {
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
        } else if (row.type == ROW_SONG) {
            if (row.song != null) {
                presentFragment(new MusicSongActivity(row.song.id, row.song.title));
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
                        if (miniPlayer != null) {
                            miniPlayer.update(false);
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
            refreshVibe();
            if (miniPlayer != null) {
                miniPlayer.update(id != NotificationCenter.messagePlayingProgressDidChanged);
            }
        } else if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            if (miniPlayer != null) {
                miniPlayer.updateProgressOnly();
            }
        }
    }

    @Override
    public boolean isLightStatusBar() {
        // Home = dark immersive aura -> want light (white) status-bar icons -> not a light bar.
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
        if (listView != null) {
            listView.smoothScrollToPosition(0);
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
            return type == ROW_TRACK || type == ROW_RETRY || type == ROW_SONG;
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
            String title = s.title != null && !s.title.isEmpty() ? s.title : getString(R.string.AudioUnknownTitle);
            if (s.variantLabel != null && !s.variantLabel.isEmpty()) {
                title = title + " (" + s.variantLabel + ")";
            }
            titleView.setText(title);
            letterView.setText(title.isEmpty() ? "♪" : title.substring(0, 1).toUpperCase());
            String artistLine = s.artistLine();
            subtitleView.setText(artistLine.isEmpty() ? getString(R.string.AudioUnknownArtist) : artistLine);
            badgeView.setText(s.versionCount > 1 ? (s.versionCount + "  ›") : "›");
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

        VibeScreen(Context context) {
            super(context);

            // Tapping anywhere on the vibe screen toggles play/pause (or starts the vibe). The
            // floating search bar sits on top as a separate view, so it still gets its own taps.
            setOnClickListener(v -> onVibeTap());

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

    /* mini player */

    private class MiniPlayerView extends FrameLayout {

        private final BackupImageView cover;
        private final TextView titleView;
        private final TextView subtitleView;
        private final ImageView playPause;
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;

        MiniPlayerView(Context context) {
            super(context);
            setWillNotDraw(false);
            setBackground(Theme.createRoundRectDrawable(dp(14), getThemedColor(Theme.key_windowBackgroundGray)));
            progressPaint.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
            progressPaint.setStrokeWidth(dp(2));

            cover = new BackupImageView(context);
            cover.setRoundRadius(dp(8));
            addView(cover, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL, 10, 0, 0, 0));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 60, 0, 54, 0));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(titleView);

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            subtitleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

            playPause = new ImageView(context);
            playPause.setScaleType(ImageView.ScaleType.CENTER);
            playPause.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.MULTIPLY));
            playPause.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(22)));
            playPause.setOnClickListener(v -> {
                MediaController mc = MediaController.getInstance();
                MessageObject mo = mc.getPlayingMessageObject();
                if (mo == null) {
                    return;
                }
                if (mc.isMessagePaused()) {
                    mc.playMessage(mo);
                } else {
                    mc.pauseMessage(mo);
                }
            });
            addView(playPause, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

            setOnClickListener(v -> {
                if (getParentActivity() == null) {
                    return;
                }
                showDialog(new AudioPlayerAlert(getParentActivity(), getResourceProvider()));
            });
        }

        void update(boolean animated) {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            boolean show = mo != null && mo.isMusic();
            boolean wasVisible = getVisibility() == VISIBLE;
            if (!show) {
                if (wasVisible) {
                    setVisibility(GONE);
                    refreshListPadding();
                }
                return;
            }
            if (!wasVisible) {
                setVisibility(VISIBLE);
                if (animated) {
                    setAlpha(0f);
                    setTranslationY(dp(24));
                    animate().alpha(1f).translationY(0f).setDuration(220)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                }
                refreshListPadding();
            }
            titleView.setText(mo.getMusicTitle());
            subtitleView.setText(mo.getMusicAuthor());
            playPause.setImageResource(MediaController.getInstance().isMessagePaused() ? R.drawable.ic_play : R.drawable.ic_pause);

            TLRPC.Document doc = mo.getDocument();
            TLRPC.PhotoSize ps = doc != null ? FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 90) : null;
            if (ps != null && !(ps instanceof TLRPC.TL_photoSizeEmpty)) {
                cover.setImage(ImageLocation.getForDocument(ps, doc), "40_40", coverPlaceholder(), MusicActivity.this);
            } else {
                cover.setImageDrawable(coverPlaceholder());
            }
            updateProgressOnly();
        }

        void updateProgressOnly() {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            float p = mo != null ? mo.audioProgress : 0f;
            if (Math.abs(p - progress) > 0.003f) {
                progress = p;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (progress > 0f) {
                float w = (getWidth() - dp(20)) * Math.min(1f, progress);
                canvas.drawLine(dp(10), getHeight() - dp(3), dp(10) + w, getHeight() - dp(3), progressPaint);
            }
        }
    }
}
