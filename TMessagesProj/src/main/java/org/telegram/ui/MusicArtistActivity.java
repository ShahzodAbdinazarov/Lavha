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
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.svipe.SvipeMusic;
import org.telegram.svipe.SvipeMusicQueue;
import org.telegram.svipe.SvipeMusicResolver;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.SharedAudioCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ProfileActionsView;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * Artist page. Same native profile shell as {@link MusicSongActivity}: the artist stands in for the
 * peer (initials tile as the avatar, name as the name, song count as the status, Play/Shuffle in the
 * action row), and where a channel profile lists shared media this lists the artist's canonical songs
 * as native {@link SharedAudioCell}s. Tap a song to open its version picker.
 */
public class MusicArtistActivity extends ProfileStyleActivity {

    private static final int ROW_SECTION = 0;
    private static final int ROW_SONG = 1;

    private final long artistId;
    private final String initialName;

    private ListAdapter adapter;
    private FrameLayout stateOverlay;

    private SvipeMusic.ArtistPage page;
    private SvipeMusicQueue queue;
    /** Songs whose default track resolved to a real audio message — the only ones that can be drawn/played. */
    private final ArrayList<SvipeMusic.Song> songs = new ArrayList<>();
    private final HashMap<Long, MessageObject> moBySongId = new HashMap<>();

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
    protected void onCreateActions(ProfileActionsView view) {
        view.addAction(ProfileActionsView.ActionButton.PLAY, ProfileActionsView.KEY_PLAY);
        view.addAction(ProfileActionsView.ActionButton.SHUFFLE, ProfileActionsView.KEY_SHUFFLE);
    }

    @Override
    protected void onActionClick(int key, float x, float y) {
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

    @Override
    protected void onListItemClick(View view, int position) {
        int idx = position - 1; // ROW_SECTION precedes the song rows
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
                showMessage("Yuklashda xatolik");
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
        String name = page.artist != null && page.artist.name != null && !page.artist.name.isEmpty()
                ? page.artist.name
                : (initialName != null ? initialName : getString(R.string.AudioUnknownArtist));
        setProfileTitle(name);

        int n = page.songCount > 0 ? page.songCount : songs.size();
        setProfileSubtitle(n == 1 ? "1 qo'shiq" : n + " qo'shiq");

        // Artists have no photo, so this is always Telegram's gradient+initials tile — the same
        // fallback a peer without an avatar gets.
        avatarDrawable.setInfo(artistId, name, null);
        avatarImage.getImageReceiver().setImageBitmap(avatarDrawable);
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
            return holder.getItemViewType() == ROW_SONG;
        }

        @Override
        public int getItemCount() {
            return songs.isEmpty() ? 0 : 1 + songs.size(); // section + songs
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? ROW_SECTION : ROW_SONG;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            if (viewType == ROW_SECTION) {
                view = createTabPillRow(context, getString(R.string.SvipeMusicSongs));
            } else {
                view = new SharedAudioCell(context, getResourceProvider());
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == ROW_SECTION) {
                // the tab pill is static
            } else {
                SvipeMusic.Song s = songs.get(position - 1);
                MessageObject mo = moBySongId.get(s.id);
                SharedAudioCell cell = (SharedAudioCell) holder.itemView;
                if (mo != null) {
                    cell.setMessageObject(mo, position != getItemCount() - 1);
                }
            }
        }
    }
}
