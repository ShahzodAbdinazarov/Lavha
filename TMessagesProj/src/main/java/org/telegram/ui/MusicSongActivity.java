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
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.svipe.SvipeMusic;
import org.telegram.svipe.SvipeMusicQueue;
import org.telegram.svipe.SvipeMusicResolver;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
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
 * Canonical song page. It is the native profile screen ({@link ProfileStyleActivity} carries
 * ProfileActivity's real header) with the song standing in for the peer: album art as the avatar,
 * title as the name, artists as the status, and Play/Shuffle in the action row. Where a channel
 * profile lists its shared media, this lists the song's versions — each a native
 * {@link SharedAudioCell}, so artwork, play/pause and download state come from the resolved audio
 * {@link MessageObject} for free.
 *
 * <p>Tap a version to play it; long-press to make it your own default (a crowd vote).
 */
public class MusicSongActivity extends ProfileStyleActivity {

    private static final int ROW_SECTION = 0;
    private static final int ROW_VERSION = 1;

    private final long songId;
    private final String initialTitle;

    private ListAdapter adapter;
    private FrameLayout stateOverlay;

    private SvipeMusic.SongDetail detail;
    private SvipeMusicQueue queue;
    private String coverArtworkUrl;
    private final HashMap<String, MessageObject> moByKey = new HashMap<>();
    private boolean setInFlight;

    public MusicSongActivity(long songId, String initialTitle) {
        this.songId = songId;
        this.initialTitle = initialTitle;
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        avatarDrawable.setProfile(true);
        if (initialTitle != null) {
            setProfileTitle(initialTitle);
            avatarDrawable.setInfo(songId, initialTitle, null);
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
        if (key == ProfileActionsView.KEY_PLAY) {
            MessageObject mo = defaultMessage();
            if (queue != null && mo != null) {
                queue.play(mo);
            }
        } else if (key == ProfileActionsView.KEY_SHUFFLE) {
            playShuffled();
        }
    }

    @Override
    protected void onListItemClick(View view, int position) {
        SvipeMusic.SongVersion v = versionAt(position);
        if (v != null) {
            play(v);
        }
    }

    @Override
    protected boolean onListItemLongClick(View view, int position) {
        SvipeMusic.SongVersion v = versionAt(position);
        if (v != null) {
            showVersionMenu(v);
            return true;
        }
        return false;
    }

    private void load() {
        showProgress(true);
        SvipeMusic.song(currentAccount, songId, (song, error) -> {
            if (song == null) {
                showProgress(false);
                showMessage(getString(R.string.MusicLoadFailed));
                return;
            }
            detail = song;
            // Resolve every version to a real audio MessageObject so SharedAudioCell can render it and
            // the whole song becomes one playable queue (tap = play within the queue).
            ArrayList<SvipeMusic.Track> tracks = new ArrayList<>(song.versions);
            SvipeMusicResolver.resolve(currentAccount, tracks, resolved -> {
                queue = new SvipeMusicQueue(currentAccount, SvipeMusicQueue.SOURCE_SECTION,
                        song.title != null ? song.title : "", false);
                queue.appendResolved(tracks, new HashMap<>(resolved));
                moByKey.clear();
                for (SvipeMusic.SongVersion v : song.versions) {
                    MessageObject mo = queue.messageForKey(v.key());
                    if (mo != null) {
                        moByKey.put(v.key(), mo);
                    }
                }
                showProgress(false);
                bindHeader();
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                    notifyListChanged();
                }
            });
        });
    }

    /** Fills the profile header: album art -> avatar, title -> name, artists -> status. */
    private void bindHeader() {
        String t = detail.title != null && !detail.title.isEmpty() ? detail.title : getString(R.string.AudioUnknownTitle);
        if (detail.variantLabel != null && !detail.variantLabel.isEmpty()) {
            t = t + " (" + detail.variantLabel + ")";
        }
        setProfileTitle(t);

        String line = detail.artistLine();
        setProfileSubtitle(line.isEmpty() ? getString(R.string.AudioUnknownArtist) : line);
        onlineTextView.setOnClickListener(detail.artists.isEmpty() ? null :
                v -> presentFragment(new MusicArtistActivity(detail.artists.get(0).id, detail.artists.get(0).name)));

        // Telegram's gradient+initials tile from the song title, replaced by the default version's real
        // album art when it has one — exactly how a profile falls back when a peer has no photo.
        avatarDrawable.setInfo(songId, t, null);
        MessageObject mo = defaultMessage();
        TLRPC.Document doc = mo != null ? mo.getDocument() : null;
        TLRPC.PhotoSize thumbSize = doc != null ? FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 360) : null;
        if (!(thumbSize instanceof TLRPC.TL_photoSize) && !(thumbSize instanceof TLRPC.TL_photoSizeProgressive)) {
            thumbSize = null;
        }
        ImageLocation thumbLocation = thumbSize != null ? ImageLocation.getForDocument(thumbSize, doc) : null;
        // The document thumb is only ~360px, which goes to mush once the header is pulled open to full
        // width. AudioPlayerAlert has the same problem and solves it the same way: load the full-size
        // cover art off the audio's artwork URL and keep the thumb as the placeholder behind it.
        String artworkUrl = mo != null ? mo.getArtworkUrl(false) : null;
        if (artworkUrl != null && !artworkUrl.isEmpty()) {
            coverArtworkUrl = artworkUrl;
            avatarImage.setImage(ImageLocation.getForPath(artworkUrl), null, thumbLocation, null, avatarDrawable, null, 0, 1, mo);
        } else if (thumbLocation != null) {
            coverArtworkUrl = null;
            avatarImage.setImage(thumbLocation, null, avatarDrawable, mo);
        } else {
            coverArtworkUrl = null;
            avatarImage.getImageReceiver().setImageBitmap(avatarDrawable);
        }
        onAvatarChanged();
    }

    /**
     * Pulling the expanded cover all the way open shows the artwork full-screen. Only the artwork URL
     * is worth opening — the album art Telegram embeds in these audio files is a 120x120 thumb.
     *
     * <p>Handed over as a PhotoEntry pointing at the already-downloaded file rather than as a
     * SearchImage: PhotoViewer's view-only path (SELECT_TYPE_NO_SELECT) hard-casts every entry to
     * PhotoEntry, so a SearchImage ClassCastExceptions there. The header has already displayed this
     * URL, so ImageLoader has it cached at exactly this path.
     */
    @Override
    protected Object getExpandedPhotoObject() {
        if (coverArtworkUrl == null) {
            return null;
        }
        File file = ImageLoader.getHttpFilePath(coverArtworkUrl, "jpg");
        if (file == null || !file.exists()) {
            return null;
        }
        return new MediaController.PhotoEntry(0, 0, 0, file.getAbsolutePath(), 0, false, 0, 0, 0);
    }

    private SvipeMusic.SongVersion versionAt(int position) {
        if (detail == null) {
            return null;
        }
        int idx = position - 1; // ROW_SECTION precedes the version rows
        if (idx >= 0 && idx < detail.versions.size()) {
            return detail.versions.get(idx);
        }
        return null;
    }

    private MessageObject defaultMessage() {
        if (detail == null || detail.versions.isEmpty()) {
            return null;
        }
        SvipeMusic.SongVersion pick = detail.versions.get(0);
        for (SvipeMusic.SongVersion v : detail.versions) {
            if (v.isDefault) {
                pick = v;
                break;
            }
        }
        return moByKey.get(pick.key());
    }

    private void play(SvipeMusic.SongVersion v) {
        if (queue == null) {
            return;
        }
        MessageObject mo = moByKey.get(v.key());
        if (mo != null) {
            queue.play(mo);
        }
    }

    /** Starts on a random version — the rest of the queue follows in its normal order. */
    private void playShuffled() {
        if (queue == null || detail == null || detail.versions.isEmpty()) {
            return;
        }
        ArrayList<SvipeMusic.SongVersion> shuffled = new ArrayList<>(detail.versions);
        Collections.shuffle(shuffled);
        for (SvipeMusic.SongVersion v : shuffled) {
            MessageObject mo = moByKey.get(v.key());
            if (mo != null) {
                queue.play(mo);
                return;
            }
        }
    }

    private void showVersionMenu(SvipeMusic.SongVersion v) {
        if (getParentActivity() == null) {
            return;
        }
        String perf = v.performer != null && !v.performer.isEmpty()
                ? v.performer : (v.username != null ? "@" + v.username : getString(R.string.AudioUnknownArtist));
        String votes = v.voteCount == 1 ? "1 kishi tanlagan" : v.voteCount + " kishi tanlagan";
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(quality(v) + "  ·  " + perf);
        b.setMessage(votes + (v.isMyDefault ? "\n\nBu sizning standart versiyangiz." : ""));
        if (!v.isMyDefault) {
            b.setPositiveButton("Menga standart qil", (dialog, which) -> setMyDefault(v));
        }
        b.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void setMyDefault(SvipeMusic.SongVersion v) {
        if (setInFlight) {
            return;
        }
        setInFlight = true;
        SvipeMusic.setDefault(currentAccount, songId, v.channelId, v.messageId, (ack, error) -> {
            setInFlight = false;
            if (error == null) {
                load(); // reload to reflect new my-default + vote counts
            }
        });
    }

    private static String quality(SvipeMusic.Track t) {
        if (t.size > 0 && t.durationS > 0) {
            long kbps = Math.round((t.size * 8.0) / t.durationS / 1000.0);
            return kbps + " kbps";
        }
        if (t.size > 0) {
            return String.format("%.1f MB", t.size / (1024.0 * 1024.0));
        }
        return "audio";
    }

    private void showProgress(boolean show) {
        if (stateOverlay == null) {
            return;
        }
        stateOverlay.removeAllViews();
        if (show) {
            RadialProgressView p = new RadialProgressView(getParentActivity());
            p.setSize(dp(30));
            stateOverlay.addView(p, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }
    }

    private void showMessage(String msg) {
        if (stateOverlay == null) {
            return;
        }
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
            return holder.getItemViewType() == ROW_VERSION; // only versions are tappable/playable
        }

        @Override
        public int getItemCount() {
            if (detail == null) {
                return 0;
            }
            return 1 + detail.versions.size(); // section + versions
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? ROW_SECTION : ROW_VERSION;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            if (viewType == ROW_SECTION) {
                view = createTabPillRow(context, getString(R.string.SvipeMusicVersions));
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
                SvipeMusic.SongVersion v = detail.versions.get(position - 1);
                SharedAudioCell cell = (SharedAudioCell) holder.itemView;
                MessageObject mo = moByKey.get(v.key());
                if (mo != null) {
                    cell.setMessageObject(mo, position != getItemCount() - 1);
                }
                cell.setChecked(v.isMyDefault, false); // native check marks the user's own default
            }
        }
    }
}
