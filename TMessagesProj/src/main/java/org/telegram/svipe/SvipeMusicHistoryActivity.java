package org.telegram.svipe;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Settings "Music listening history": the tracks this user recently played, newest first, paged from
 * GET /v1/music/history. Tapping a row plays it through the app's own {@link SvipeMusicQueue} +
 * MediaController stack — the same playback path the Music tab uses (streaming, caching, lock-screen
 * controls all come from the platform), so the mini-player / AudioPlayerAlert light up exactly as
 * they do from anywhere else in the app. A tap on the currently-playing row toggles pause.
 */
public class SvipeMusicHistoryActivity extends BaseFragment
        implements NotificationCenter.NotificationCenterDelegate {

    private static final int PAGE_SIZE = 40;
    private static final int PLAY_WINDOW = 60;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView emptyView;

    private final ArrayList<SvipeMusic.Track> tracks = new ArrayList<>();
    private int nextOffset = 0;
    private boolean loading;
    private boolean endReached;
    private boolean firstLoadDone;
    private boolean playRequestInFlight;

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.messagePlayingDidStart);
        getNotificationCenter().addObserver(this, NotificationCenter.messagePlayingDidReset);
        getNotificationCenter().addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.messagePlayingDidStart);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagePlayingDidReset);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.SvipeMusicListeningHistory));
        if (AndroidUtilities.isTablet()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        emptyView = new TextView(context);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        emptyView.setText(LocaleController.getString(R.string.SvipeNoListeningHistory));
        emptyView.setVisibility(View.GONE);
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        adapter = new ListAdapter();
        final LinearLayoutManager layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        listView = new RecyclerListView(context);
        listView.setLayoutManager(layoutManager);
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < tracks.size()) {
                playFrom(position);
            }
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0) {
                    return;
                }
                if (!loading && !endReached
                        && layoutManager.findLastVisibleItemPosition() >= tracks.size() - 5) {
                    loadPage();
                }
            }
        });

        loadPage();
        return fragmentView;
    }

    // ---- data ----

    private void loadPage() {
        if (loading || endReached) {
            return;
        }
        loading = true;
        final int offset = nextOffset;
        SvipeMusic.musicHistory(currentAccount, offset, PAGE_SIZE, (items, recId, nextCursor, error) -> {
            loading = false;
            firstLoadDone = true;
            if (items == null) {
                updateEmpty();
                return;
            }
            final int before = tracks.size();
            tracks.addAll(items);
            if (nextCursor != null && !nextCursor.isEmpty()) {
                try {
                    nextOffset = Integer.parseInt(nextCursor);
                } catch (NumberFormatException e) {
                    nextOffset = tracks.size();
                }
            } else {
                endReached = true;
            }
            if (items.isEmpty()) {
                endReached = true;
            }
            if (adapter != null) {
                if (before == 0) {
                    adapter.notifyDataSetChanged();
                } else {
                    adapter.notifyItemRangeInserted(before, items.size());
                }
            }
            updateEmpty();
        });
    }

    private void updateEmpty() {
        if (emptyView != null) {
            emptyView.setVisibility(firstLoadDone && tracks.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /** Play the tapped track, queueing a window of the following rows so playback continues down the list. */
    private void playFrom(int index) {
        final SvipeMusic.Track track = tracks.get(index);
        final MediaController mc = MediaController.getInstance();
        final SvipeMusicQueue active = SvipeMusicQueue.getActive();
        final MessageObject playing = mc.getPlayingMessageObject();
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
        final ArrayList<SvipeMusic.Track> window = new ArrayList<>();
        for (int i = index; i < tracks.size() && window.size() < PLAY_WINDOW; i++) {
            window.add(tracks.get(i));
        }
        SvipeMusicResolver.resolve(currentAccount, window, resolved -> {
            playRequestInFlight = false;
            SvipeMusicQueue queue = new SvipeMusicQueue(currentAccount, SvipeMusicQueue.SOURCE_SECTION,
                    LocaleController.getString(R.string.SvipeMusicListeningHistory), false);
            queue.appendResolved(window, resolved);
            MessageObject first = queue.messageForKey(track.key());
            if (first == null && !queue.list.isEmpty()) {
                first = queue.list.get(0);
            }
            if (first != null) {
                queue.play(first);
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
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
        SvipeMusic.Track playingTrack = active.trackFor(playing);
        if (playingTrack == null || !playingTrack.key().equals(track.key())) {
            return false;
        }
        paused[0] = MediaController.getInstance().isMessagePaused();
        return true;
    }

    private Drawable coverPlaceholder() {
        Drawable base = Theme.createRoundRectDrawable(AndroidUtilities.dp(8), Theme.getColor(Theme.key_dialogSearchBackground));
        Drawable icon = ApplicationLoader.applicationContext.getResources().getDrawable(R.drawable.search_music_filled).mutate();
        icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), PorterDuff.Mode.MULTIPLY));
        CombinedDrawable cd = new CombinedDrawable(base, icon);
        cd.setCustomSize(AndroidUtilities.dp(46), AndroidUtilities.dp(46));
        cd.setIconSize(AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        return cd;
    }

    // ---- list ----

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return tracks.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TrackCell cell = new TrackCell(parent.getContext());
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((TrackCell) holder.itemView).bind(tracks.get(position));
        }
    }

    /** Cover placeholder + play/pause overlay + title + performer·duration. Mirrors the Music tab row. */
    private class TrackCell extends FrameLayout {

        private final ImageView cover;
        private final ImageView playOverlay;
        private final TextView titleView;
        private final TextView subtitleView;

        TrackCell(Context context) {
            super(context);
            setBackground(Theme.getSelectorDrawable(false));

            cover = new ImageView(context);
            cover.setScaleType(ImageView.ScaleType.FIT_XY);
            addView(cover, LayoutHelper.createFrame(46, 46, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

            playOverlay = new ImageView(context);
            playOverlay.setScaleType(ImageView.ScaleType.CENTER);
            playOverlay.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(8), 0x66000000));
            playOverlay.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.MULTIPLY));
            playOverlay.setVisibility(GONE);
            addView(playOverlay, LayoutHelper.createFrame(46, 46, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 74, 0, 16, 0));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(titleView);

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(62), MeasureSpec.EXACTLY));
        }

        void bind(SvipeMusic.Track t) {
            titleView.setText(t.title != null && !t.title.isEmpty() ? t.title : LocaleController.getString(R.string.AudioUnknownTitle));
            String performer = t.performer != null && !t.performer.isEmpty() ? t.performer : LocaleController.getString(R.string.AudioUnknownArtist);
            String dur = AndroidUtilities.formatShortDuration(Math.max(0, t.durationS));
            subtitleView.setText(performer + " · " + dur);

            cover.setImageDrawable(coverPlaceholder());

            boolean[] paused = new boolean[1];
            boolean playing = isTrackPlaying(t, paused);
            titleView.setTextColor(Theme.getColor(playing ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundWhiteBlackText));
            if (playing) {
                playOverlay.setVisibility(VISIBLE);
                playOverlay.setImageResource(paused[0] ? R.drawable.ic_play : R.drawable.ic_pause);
            } else {
                playOverlay.setVisibility(GONE);
            }
        }
    }
}
