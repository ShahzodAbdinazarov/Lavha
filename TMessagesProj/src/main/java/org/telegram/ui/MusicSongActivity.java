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
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.svipe.SvipeFavKey;
import org.telegram.svipe.SvipeFavourite;
import org.telegram.svipe.SvipeFavouritesSet;
import org.telegram.svipe.SvipeMusic;
import org.telegram.svipe.SvipeMusicQueue;
import org.telegram.svipe.SvipeMusicResolver;
import org.telegram.svipe.SvipeVibe;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.svipe.SvipeMusicDislikes;
import org.telegram.ui.Cells.SharedAudioCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.Components.ProfileActionsView;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;

import java.io.File;
import java.util.ArrayList;
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
public class MusicSongActivity extends ProfileStyleActivity implements NotificationCenter.NotificationCenterDelegate {

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
    protected CharSequence getTabTitle() {
        return getString(R.string.SvipeMusicVersions);
    }

    // The artists live in the card above the tabs — the slot a real profile gives Description /
    // Invite Link — rather than behind a tab of their own: a song almost always has one artist, and a
    // one-row tab would be both odd and invisible until scrolled to. UserCell is the profile's own
    // person row, and it takes an explicit name/status, so it does not need a peer.

    @Override
    protected int getHeaderRowCount() {
        return detail == null ? 0 : detail.artists.size();
    }

    @Override
    protected View createHeaderRow(Context context) {
        return new UserCell(context, 6, 0, false, getResourceProvider());
    }

    @Override
    protected void bindHeaderRow(View view, int position) {
        if (detail == null || position >= detail.artists.size()) {
            return;
        }
        final SvipeMusic.Artist a = detail.artists.get(position);
        final UserCell cell = (UserCell) view;
        final AvatarDrawable avatar = new AvatarDrawable();
        avatar.setInfo(a.id, a.name, null);
        cell.setData(null, a.name, artistStatus(a), 0, position != detail.artists.size() - 1);
        cell.avatarImageView.setImageDrawable(avatar);
    }

    @Override
    protected void onHeaderRowClick(int position) {
        if (detail != null && position < detail.artists.size()) {
            SvipeMusic.Artist a = detail.artists.get(position);
            presentFragment(new MusicArtistActivity(a.id, a.name));
        }
    }

    /**
     * A peer row's status line says something about that peer, so an artist's says how much they have
     * — the way a channel's says its subscriber count. The role label only stands in when the count is
     * missing (an older backend, or an artist not yet counted), since repeating "Ijrochi" down every
     * row tells the reader nothing.
     */
    private String artistStatus(SvipeMusic.Artist a) {
        if (a.songCount > 0) {
            return LocaleController.formatPluralString("SvipeMusicSongCount", a.songCount);
        }
        return "featured".equals(a.role) ? getString(R.string.SvipeMusicFeatured) : getString(R.string.SvipeMusicArtist);
    }

    @Override
    protected void onCreateActions(ProfileActionsView view) {
        view.addAction(ProfileActionsView.ActionButton.PLAY, ProfileActionsView.KEY_PLAY);
        view.addAction(ProfileActionsView.ActionButton.VIBE, ProfileActionsView.KEY_VIBE);
        if (isFavouritable()) {
            view.addAction(favouriteButton(), ProfileActionsView.KEY_LIKE);
        }
        view.addAction(ProfileActionsView.ActionButton.SHARE, ProfileActionsView.KEY_SHARE);
    }

    @Override
    protected void onActionClick(int key, float x, float y) {
        if (key == ProfileActionsView.KEY_PLAY) {
            MessageObject mo = defaultMessage();
            if (queue != null && mo != null) {
                queue.play(mo);
            }
        } else if (key == ProfileActionsView.KEY_VIBE) {
            startVibe();
        } else if (key == ProfileActionsView.KEY_LIKE) {
            toggleFavourite();
        } else if (key == ProfileActionsView.KEY_SHARE) {
            share();
        }
    }

    /* ---------------- Svipe: favourite ("like") toggle ---------------- */

    /**
     * A negative song id is an A5 Deezer placeholder — a track we do not host yet, which exists only as
     * a search result. The backend rejects favouriting one, so the heart is not offered at all rather
     * than shown and then failing. The id is final, so this never changes while the page is open.
     */
    private boolean isFavouritable() {
        return songId > 0;
    }

    private ProfileActionsView.ActionButton favouriteButton() {
        return SvipeFavouritesSet.getInstance(currentAccount).isFavourite(SvipeFavKey.song(songId).key)
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
     * Flip the favourite state. The local store is the source of truth and mutates synchronously — it
     * posts {@code svipeFavouritesChanged}, which repaints the heart — while the backend call it makes
     * is fire-and-forget.
     *
     * <p>Adding waits for the song detail: the stored entry carries the version to play and the post to
     * fall back to, and {@link SvipeFavouritesSet#merge} only fills those in when it ADOPTS an entry, so
     * one saved blank would stay a dead row in the Music list forever. Removing needs no metadata at
     * all (it is keyed by song id), so it is always allowed.
     */
    private void toggleFavourite() {
        if (!isFavouritable()) {
            return;
        }
        final SvipeFavKey key = SvipeFavKey.song(songId);
        final SvipeFavouritesSet set = SvipeFavouritesSet.getInstance(currentAccount);
        if (detail == null && !set.isFavourite(key.key)) {
            // Normally this window is under the page's progress spinner, but a terminal load failure
            // leaves detail null for the life of the fragment — and nothing retries it. Say so instead of
            // leaving a heart that swallows every tap in silence.
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.MusicLoadFailed)).show();
            return;
        }
        set.toggle(favouriteEntry(key));
    }

    /**
     * The entry to store, populated exactly as {@link SvipeFavouritesSet#merge} populates one it adopts
     * from the server — same fields, same meanings — so a row favourited here and one synced down from
     * another device render and play identically.
     */
    private SvipeFavourite favouriteEntry(SvipeFavKey key) {
        SvipeFavourite f = SvipeFavourite.of(key);
        f.title = detail != null ? detail.shownTitle() : initialTitle;
        f.artist = detail != null ? detail.shownArtist() : null;
        f.isPublic = true;      // a catalog song always lives in a public channel
        SvipeMusic.Track t = defaultVersion();
        if (t == null && detail != null) {
            t = detail.defaultTrack;
        }
        if (t != null) {
            f.channelId = t.channelId;
            f.messageId = t.messageId;
            f.username = t.username;
            f.durationS = t.durationS;
            // Where to open it if it ever fails to resolve — without this the row would be a dead tap.
            f.dialogId = -t.channelId;
        }
        return f;
    }

    /** ⋮ item ids. Local to this page — the action bar dispatches by id within one fragment. */
    private static final int MENU_DISLIKE = 1;

    private ActionBarMenuSubItem dislikeItem;

    @Override
    protected void onCreateActionBarMenu(ActionBarMenu menu) {
        if (songId <= 0) {
            return;   // a Deezer placeholder has no catalog row yet, so there is nothing to refuse
        }
        final ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        other.setContentDescription(getString(R.string.AccDescrMoreOptions));
        dislikeItem = other.addSubItem(MENU_DISLIKE, R.drawable.svipe_heart_off_24,
                getString(R.string.SvipeMusicDislike));
        refreshDislikeItem();
    }

    @Override
    protected void onActionBarItemClick(int id) {
        if (id != MENU_DISLIKE) {
            return;
        }
        final boolean nowDisliked = SvipeMusicDislikes.getInstance(currentAccount).toggleSong(songId);
        refreshDislikeItem();
        // Said out loud, because nothing else on this page changes: a refusal is an instruction to a
        // recommender the user cannot see, and a menu that closes in silence looks like it did nothing.
        BulletinFactory.of(this).createSimpleBulletin(
                nowDisliked ? R.raw.chats_infotip : R.raw.contact_check,
                getString(nowDisliked ? R.string.SvipeMusicDislikedSong
                        : R.string.SvipeMusicUndislikedSong)).show();
    }

    private void refreshDislikeItem() {
        if (dislikeItem == null) {
            return;
        }
        final boolean disliked = SvipeMusicDislikes.getInstance(currentAccount).isSongDisliked(songId);
        dislikeItem.setTextAndIcon(getString(disliked ? R.string.SvipeMusicUndislike
                : R.string.SvipeMusicDislike), R.drawable.svipe_heart_off_24);
    }

    @Override
    public boolean onFragmentCreate() {
        // GLOBAL, not per-account: the heart must also light up when the song is favourited from the
        // mini player or the Music tab, which post globally (see FragmentContextView).
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.svipeFavouritesChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.svipeMusicDislikesChanged);
        // One-shot per process: what this user refused on another device, so the menu opens with the
        // right word on it rather than offering to dislike something they already did.
        SvipeMusicDislikes.getInstance(currentAccount).syncFromServer();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.svipeFavouritesChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.svipeMusicDislikesChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.svipeFavouritesChanged) {
            refreshFavouriteAction();
        } else if (id == NotificationCenter.svipeMusicDislikesChanged) {
            refreshDislikeItem();
        }
    }

    /**
     * Shares the song as the owned {@code svipe.uz/<code>} link, mirroring how a reel is shared: the
     * ACTUAL audio rides along as a clean document send carrying our caption, so the recipient plays
     * the track inside Telegram and sees where it came from.
     *
     * <p>The link points at the SONG, not at the version playing right now — the song's default moves
     * with the crowd vote, and the page resolves it at open time.
     */
    private void share() {
        if (detail == null || getParentActivity() == null) return;
        SvipeMusic.SongVersion v = defaultVersion();
        String link = detail.shareUrl != null && !detail.shareUrl.isEmpty()
                ? detail.shareUrl
                : (v != null && v.username != null && !v.username.isEmpty()
                        ? "https://t.me/" + v.username + "/" + v.messageId : null);
        if (link == null) return;
        // Promo line, blank line, then the bare URL — no scheme, because Telegram auto-links
        // svipe.uz/<code> anyway and the raw text reads cleaner under the audio.
        final String caption = getString(R.string.SvipeMusicShareCaption) + "\n\n"
                + link.replaceFirst("^https?://", "");
        try {
            MessageObject mo = defaultMessage();
            TLRPC.Document d = mo != null ? mo.getDocument() : null;
            if (d instanceof TLRPC.TL_document) {
                final TLRPC.TL_document document = (TLRPC.TL_document) d;
                final MessageObject parent = mo;
                ArrayList<MessageObject> messages = new ArrayList<>();
                messages.add(mo);
                ShareAlert alert = new ShareAlert(getParentActivity(), messages, null, false, null, false) {
                    @Override
                    protected void sendInternal(boolean withSound) {
                        for (int a = 0; a < selectedDialogs.size(); a++) {
                            long key = selectedDialogs.keyAt(a);
                            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                                    document, null, null, key, null, null, caption, null, null, null,
                                    withSound, 0, 0, 0, parent, null, false);
                            SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
                        }
                        dismiss();
                    }
                };
                showDialog(alert);
            } else {
                // Versions resolve asynchronously (see load()), so tapping Share the instant the page
                // opens legitimately finds no audio yet — share the text and link alone.
                showDialog(new ShareAlert(getParentActivity(), null, caption, false, link, false));
            }
            if (v != null) {
                SvipeMusic.sendEvent(currentAccount, v, "SHARE", null); // intent, not delivery
            }
        } catch (Exception e) {
            FileLog.e(e);
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
                refreshFavouriteAction();   // a sync may have landed while the detail was in flight
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                    notifyListChanged();
                }
            });
        });
    }

    /** Fills the profile header: album art -> avatar, title -> name, artists -> status. */
    private void bindHeader() {
        String t = detail.shownTitle() != null && !detail.shownTitle().isEmpty() ? detail.shownTitle() : getString(R.string.AudioUnknownTitle);
        if (detail.variantLabel != null && !detail.variantLabel.isEmpty()) {
            t = t + " (" + detail.variantLabel + ")";
        }
        setProfileTitle(t);

        String line = detail.shownArtist();
        setProfileSubtitle(line.isEmpty() ? getString(R.string.AudioUnknownArtist) : line);
        onlineTextView.setOnClickListener(detail.artists.isEmpty() ? null :
                v -> presentFragment(new MusicArtistActivity(detail.artists.get(0).id, detail.artists.get(0).name)));

        // Telegram's gradient+initials tile from the song title, replaced by real album art when we have
        // one — exactly how a profile falls back when a peer has no photo.
        avatarDrawable.setInfo(songId, t, null);
        MessageObject mo = defaultMessage();
        TLRPC.Document doc = mo != null ? mo.getDocument() : null;
        TLRPC.PhotoSize thumbSize = doc != null ? FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 360) : null;
        if (!(thumbSize instanceof TLRPC.TL_photoSize) && !(thumbSize instanceof TLRPC.TL_photoSizeProgressive)) {
            thumbSize = null;
        }
        ImageLocation thumbLocation = thumbSize != null ? ImageLocation.getForDocument(thumbSize, doc) : null;
        // Prefer the backend's Deezer album cover (the real, enriched art) when the song was enriched;
        // else fall back to the audio file's own artwork URL (an iTunes lookup off the embedded tags).
        // Both are full-size, so the embedded ~360px doc thumb stays only as the placeholder behind them
        // (it goes to mush once the header is pulled open to full width — same trick AudioPlayerAlert uses).
        String artworkUrl = detail.coverUrl != null && !detail.coverUrl.isEmpty()
                ? detail.coverUrl
                : (mo != null ? mo.getArtworkUrl(false) : null);
        if (artworkUrl != null && !artworkUrl.isEmpty()) {
            coverArtworkUrl = artworkUrl;
            avatarKeepsRound = false;          // a real (square) cover -> square off when expanded
            avatarImage.setImage(ImageLocation.getForPath(artworkUrl), null, thumbLocation, null, avatarDrawable, null, 0, 1, mo);
        } else if (thumbLocation != null) {
            coverArtworkUrl = null;
            avatarKeepsRound = false;
            avatarImage.setImage(thumbLocation, null, avatarDrawable, mo);
        } else {
            coverArtworkUrl = null;
            avatarKeepsRound = true;           // no image -> initials tile stays a circle, even expanded
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
        if (position >= 0 && position < detail.versions.size()) {
            return detail.versions.get(position);
        }
        return null;
    }

    /** The version this song plays by default — the crowd default, else the first listed. */
    private SvipeMusic.SongVersion defaultVersion() {
        if (detail == null || detail.versions.isEmpty()) {
            return null;
        }
        for (SvipeMusic.SongVersion v : detail.versions) {
            if (v.isDefault) {
                return v;
            }
        }
        return detail.versions.get(0);
    }

    private MessageObject defaultMessage() {
        SvipeMusic.SongVersion pick = defaultVersion();
        return pick == null ? null : moByKey.get(pick.key());
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

    /**
     * "My vibe by track": plays this song and then keeps going with what the backend finds similar.
     *
     * <p>Seeded on the default version, the one the Play button would start, so the wave is built
     * around the recording the user would actually have heard.
     */
    private void startVibe() {
        SvipeMusic.SongVersion v = defaultVersion();
        if (v == null) {
            // No version means the detail load never landed, and nothing retries it — say so rather
            // than leaving a button that does nothing.
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.MusicLoadFailed)).show();
            return;
        }
        SvipeVibe.start(currentAccount, v, true, null, started -> {
            if (!started) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.MusicLoadFailed)).show();
            }
        });
    }

    private void showVersionMenu(SvipeMusic.SongVersion v) {
        if (getParentActivity() == null) {
            return;
        }
        String perf = v.performer != null && !v.performer.isEmpty()
                ? v.performer : (v.username != null ? "@" + v.username : getString(R.string.AudioUnknownArtist));
        String votes = LocaleController.formatPluralString("SvipeMusicVoteCount", v.voteCount);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(quality(v) + "  ·  " + perf);
        b.setMessage(votes + (v.isMyDefault ? "\n\n" + getString(R.string.SvipeMusicYourDefault) : ""));
        if (!v.isMyDefault) {
            b.setPositiveButton(getString(R.string.SvipeMusicMakeDefault), (dialog, which) -> setMyDefault(v));
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
            return true;
        }

        @Override
        public int getItemCount() {
            if (detail == null) {
                return 0;
            }
            return detail.versions.size();
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
            SvipeMusic.SongVersion v = detail.versions.get(position);
                SharedAudioCell cell = (SharedAudioCell) holder.itemView;
                MessageObject mo = moByKey.get(v.key());
                if (mo != null) {
                    cell.setMessageObject(mo, position != getItemCount() - 1);
                }
                cell.setChecked(v.isMyDefault, false); // native check marks the user's own default
        }
    }
}
