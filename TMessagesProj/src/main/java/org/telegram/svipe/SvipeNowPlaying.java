package org.telegram.svipe;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;

/**
 * Whether the thing playing right now is OURS, and what may be done to it.
 *
 * <p>Two kinds of audio play through the same player. A track from the Music tab is a canonical
 * catalog song on an endless queue — the app can be told "more like this" or "never this again", and
 * the queue moves on by itself. A voice message, or an mp3 somebody posted in a chat, is neither: it
 * has no catalog identity to attach an opinion to, and the list it plays in ends.
 *
 * <p>The media notification has room for five buttons and no room for both sets, so it asks this
 * class which audio it is showing. Ours gets like and dislike where shuffle and repeat would be;
 * everything else keeps the stock transport controls, because a like button on a colleague's voice
 * note is a button that cannot do anything.
 *
 * <p>Lives here rather than in {@code MusicPlayerService} on purpose: that file is upstream's, and
 * every line added to it is a line to re-merge on the next Telegram release.
 */
public final class SvipeNowPlaying {

    private SvipeNowPlaying() {
    }

    /**
     * The canonical song behind what is playing, or 0 when there is none.
     *
     * <p>0 is the answer for everything the catalog does not host: voice messages, round videos, an
     * mp3 from a private chat, and a public-channel track we have simply never indexed. The identity
     * comes from the same resolver the favourites heart uses, so the notification and the song page
     * can never disagree about what is playing.
     */
    public static long catalogSongId(int account, MessageObject mo) {
        if (mo == null || !mo.isMusic()) {
            return 0;
        }
        try {
            final SvipeFavKey key = SvipeMusicFavourites.keyFor(account, mo);
            return key != null && key.kind == SvipeFavKey.KIND_SONG ? key.songId : 0;
        } catch (Exception e) {
            return 0;   // a notification is never worth an exception reaching the player
        }
    }

    /** True when the notification should offer like/dislike instead of shuffle/repeat. */
    public static boolean isOurs(int account, MessageObject mo) {
        return catalogSongId(account, mo) > 0;
    }

    public static boolean isLiked(int account, MessageObject mo) {
        final long songId = catalogSongId(account, mo);
        return songId > 0 && SvipeFavouritesSet.getInstance(account).isFavourite("song:" + songId);
    }

    public static boolean isDisliked(int account, MessageObject mo) {
        final long songId = catalogSongId(account, mo);
        return songId > 0 && SvipeMusicDislikes.getInstance(account).isSongDisliked(songId);
    }

    /** Flip the heart for what is playing — the same call the mini player and the song page make. */
    public static void toggleLike(int account, MessageObject mo) {
        if (mo == null) {
            return;
        }
        final SvipeFavKey key = SvipeMusicFavourites.keyFor(account, mo);
        if (key == null || key.kind != SvipeFavKey.KIND_SONG) {
            return;
        }
        SvipeMusicFavourites.toggle(account, mo, key);
    }

    /**
     * Refuse what is playing — and move on.
     *
     * <p>Skipping is not decoration. "Do not recommend this" while the song keeps playing is the app
     * ignoring what it was just told; the queue is endless, so there is always a next track to go to.
     * Undoing a refusal deliberately does NOT skip: the user is fixing a mistake, and taking the song
     * away again would be a second one.
     */
    public static void toggleDislike(int account, MessageObject mo) {
        final long songId = catalogSongId(account, mo);
        if (songId <= 0) {
            return;
        }
        final boolean nowDisliked = SvipeMusicDislikes.getInstance(account).toggleSong(songId);
        if (nowDisliked) {
            MediaController.getInstance().playNextMessage();
        }
    }
}
