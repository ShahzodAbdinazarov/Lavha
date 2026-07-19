package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.svipe.SvipeArtistFavourite;
import org.telegram.svipe.SvipeArtistFavouritesSet;
import org.telegram.svipe.SvipeMusic;
import org.telegram.svipe.SvipeMusicQueue;
import org.telegram.svipe.SvipeMusicResolver;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.SharedAudioCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ProfileActionsView;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * Artist page. Same native profile shell as {@link MusicSongActivity}: the artist stands in for the
 * peer (initials tile as the avatar, name as the name, song count as the status, Play/Shuffle in the
 * action row), and where a channel profile lists shared media this lists the artist's canonical songs
 * as native {@link SharedAudioCell}s. Tap a song to open its version picker.
 */
public class MusicArtistActivity extends ProfileStyleActivity implements NotificationCenter.NotificationCenterDelegate {

    private final long artistId;
    private final String initialName;

    private ListAdapter adapter;
    private FrameLayout stateOverlay;

    private SvipeMusic.ArtistPage page;
    private SvipeMusicQueue queue;
    /** Songs whose default track resolved to a real audio message — the only ones that can be drawn/played. */
    private final ArrayList<SvipeMusic.Song> songs = new ArrayList<>();
    private final HashMap<Long, MessageObject> moBySongId = new HashMap<>();
    private String artistPhotoUrl;   // Deezer artist photo shown as the avatar (null -> initials tile)

    public MusicArtistActivity(long artistId, String initialName) {
        this.artistId = artistId;
        this.initialName = initialName;
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        avatarDrawable.setProfile(true);
        if (initialName != null) {
            setProfileTitle(initialName);
            avatarDrawable.setInfo(artistId, initialName, null);
            avatarImage.getImageReceiver().setImageBitmap(avatarDrawable);
        }

        stateOverlay = new FrameLayout(context);
        ((FrameLayout) view).addView(stateOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        load();
        return view;
    }

    @Override
    protected RecyclerListView.SelectionAdapter createListAdapter() {
        return adapter = new ListAdapter();
    }

    @Override
    protected CharSequence getTabTitle() {
        return getString(R.string.SvipeMusicSongs);
    }

    @Override
    protected void onCreateActions(ProfileActionsView view) {
        view.addAction(ProfileActionsView.ActionButton.PLAY, ProfileActionsView.KEY_PLAY);
        view.addAction(ProfileActionsView.ActionButton.SHUFFLE, ProfileActionsView.KEY_SHUFFLE);
        if (isFavouritable()) {
            view.addAction(favouriteButton(), ProfileActionsView.KEY_LIKE);
        }
    }

    @Override
    protected void onActionClick(int key, float x, float y) {
        // Liking is answered before the playback guard below: the artist id arrives with the fragment,
        // so the heart works from the first frame, while Play/Shuffle genuinely need a resolved queue.
        if (key == ProfileActionsView.KEY_LIKE) {
            toggleFavourite();
            return;
        }
        if (queue == null || songs.isEmpty()) {
            return;
        }
        if (key == ProfileActionsView.KEY_PLAY) {
            MessageObject mo = moBySongId.get(songs.get(0).id);
            if (mo != null) {
                queue.play(mo);
            }
        } else if (key == ProfileActionsView.KEY_SHUFFLE) {
            ArrayList<SvipeMusic.Song> shuffled = new ArrayList<>(songs);
            Collections.shuffle(shuffled);
            for (SvipeMusic.Song s : shuffled) {
                MessageObject mo = moBySongId.get(s.id);
                if (mo != null) {
                    queue.play(mo);
                    return;
                }
            }
        }
    }

    /* ---------------- Svipe: favourite ("like") toggle ---------------- */

    /** An artist only ever exists as a catalog row, so a real id is the whole precondition. */
    private boolean isFavouritable() {
        return artistId > 0;
    }

    private ProfileActionsView.ActionButton favouriteButton() {
        return SvipeArtistFavouritesSet.getInstance(currentAccount).isFavourite(artistId)
                ? ProfileActionsView.ActionButton.LIKE_ACTIVE
                : ProfileActionsView.ActionButton.LIKE;
    }

    /** Repaints the heart from the store. Cheap and idempotent, so callers may fire it unconditionally. */
    private void refreshFavouriteAction() {
        if (actionsView != null && isFavouritable()) {
            actionsView.updateAction(ProfileActionsView.KEY_LIKE, favouriteButton());
        }
    }

    /**
     * Flip the favourite state. Local store first (it posts {@code svipeArtistFavouritesChanged}, which
     * repaints the heart), backend fire-and-forget.
     *
     * <p>Unlike the song page this never has to wait for the load. The artist id IS the identity, and
     * the cached name/photo are display-only — {@link SvipeArtistFavouritesSet#merge} refreshes them on
     * every sync, for entries it already has as well as ones it adopts. So liking before the page has
     * landed stores {@link #initialName} (the name the caller was already showing) and the row is
     * complete either way.
     */
    private void toggleFavourite() {
        if (!isFavouritable()) {
            return;
        }
        SvipeArtistFavouritesSet.getInstance(currentAccount).toggle(favouriteEntry());
    }

    private SvipeArtistFavourite favouriteEntry() {
        SvipeMusic.Artist a = page != null ? page.artist : null;
        if (a != null && a.id > 0) {
            return SvipeArtistFavourite.of(a);
        }
        // Page not loaded yet (or it carried no artist object): keep what the caller handed us, and let
        // the next sync fill in the real name, photo and song count.
        SvipeArtistFavourite f = new SvipeArtistFavourite();
        f.artistId = artistId;
        f.name = initialName;
        f.songCount = page != null ? page.songCount : 0;
        return f;
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.svipeArtistFavouritesChanged);
        // One-shot per process, so the first artist page opened pulls the favourite singers the user
        // starred on another device. The song set gets the same treatment from the Music tab.
        SvipeArtistFavouritesSet.getInstance(currentAccount).syncFromServer();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.svipeArtistFavouritesChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.svipeArtistFavouritesChanged) {
            refreshFavouriteAction();
        }
    }

    @Override
    protected void onListItemClick(View view, int position) {
        int idx = position;
        if (idx >= 0 && idx < songs.size()) {
            SvipeMusic.Song s = songs.get(idx);
            presentFragment(new MusicSongActivity(s.id, s.title));
        }
    }

    private void load() {
        showProgress(true);
        SvipeMusic.artist(currentAccount, artistId, 0, 50, (result, error) -> {
            if (result == null) {
                showProgress(false);
                showMessage(getString(R.string.MusicLoadFailed));
                return;
            }
            page = result;

            // Resolve each song's default version so the rows are real SharedAudioCells and the whole
            // discography becomes one queue — the same trick the song page uses for its versions.
            ArrayList<SvipeMusic.Track> tracks = new ArrayList<>();
            ArrayList<SvipeMusic.Song> ordered = new ArrayList<>();
            for (SvipeMusic.Song s : result.songs) {
                if (s.defaultTrack != null) {
                    tracks.add(s.defaultTrack);
                    ordered.add(s);
                }
            }
            SvipeMusicResolver.resolve(currentAccount, tracks, resolved -> {
                queue = new SvipeMusicQueue(currentAccount, SvipeMusicQueue.SOURCE_SECTION,
                        result.artist != null && result.artist.name != null ? result.artist.name : "", false);
                queue.appendResolved(tracks, new HashMap<>(resolved));
                songs.clear();
                moBySongId.clear();
                for (SvipeMusic.Song s : ordered) {
                    MessageObject mo = queue.messageForKey(s.defaultTrack.key());
                    if (mo != null) {
                        songs.add(s);
                        moBySongId.put(s.id, mo);
                    }
                }
                showProgress(false);
                bindHeader();
                refreshFavouriteAction();   // a sync may have landed while the page was in flight
                if (songs.isEmpty()) {
                    showMessage(getString(R.string.NoResult));
                }
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                    notifyListChanged();
                }
            });
        });
    }

    private void bindHeader() {
        SvipeMusic.Artist artist = page.artist;
        String name = artist != null && artist.shownName() != null && !artist.shownName().isEmpty()
                ? artist.shownName()
                : (initialName != null ? initialName : getString(R.string.AudioUnknownArtist));
        setProfileTitle(name);

        int n = page.songCount > 0 ? page.songCount : songs.size();
        setProfileSubtitle(LocaleController.formatPluralString("SvipeMusicSongCount", n));

        // Real Deezer artist photo when the artist has been enriched; else Telegram's gradient+initials
        // tile — the same fallback a peer without an avatar gets.
        avatarDrawable.setInfo(artistId, name, null);
        String photo = artist != null ? artist.photoUrl : null;
        if (photo != null && !photo.isEmpty()) {
            artistPhotoUrl = photo;
            avatarKeepsRound = false;          // a real photo -> square off when expanded
            avatarImage.setImage(ImageLocation.getForPath(photo), null, avatarDrawable, null);
        } else {
            artistPhotoUrl = null;
            avatarKeepsRound = true;           // no photo -> initials tile stays a circle, even expanded
            avatarImage.getImageReceiver().setImageBitmap(avatarDrawable);
        }
        onAvatarChanged();
    }

    /**
     * Pulling the expanded avatar open shows the artist photo full-screen — handed over as a PhotoEntry
     * pointing at the already-cached file, exactly as {@link MusicSongActivity} does for cover art.
     */
    @Override
    protected Object getExpandedPhotoObject() {
        if (artistPhotoUrl == null) {
            return null;
        }
        File file = ImageLoader.getHttpFilePath(artistPhotoUrl, "jpg");
        if (file == null || !file.exists()) {
            return null;
        }
        return new MediaController.PhotoEntry(0, 0, 0, file.getAbsolutePath(), 0, false, 0, 0, 0);
    }

    private void showProgress(boolean show) {
        if (stateOverlay == null) return;
        stateOverlay.removeAllViews();
        if (show) {
            RadialProgressView p = new RadialProgressView(getParentActivity());
            p.setSize(dp(30));
            stateOverlay.addView(p, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }
    }

    private void showMessage(String msg) {
        if (stateOverlay == null) return;
        stateOverlay.removeAllViews();
        TextView tv = new TextView(getParentActivity());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tv.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        tv.setText(msg);
        stateOverlay.addView(tv, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return songs.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View view = new SharedAudioCell(parent.getContext(), getResourceProvider());
            view.setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            SvipeMusic.Song s = songs.get(position);
                MessageObject mo = moBySongId.get(s.id);
                SharedAudioCell cell = (SharedAudioCell) holder.itemView;
                if (mo != null) {
                    cell.setMessageObject(mo, position != getItemCount() - 1);
                }
        }
    }
}
