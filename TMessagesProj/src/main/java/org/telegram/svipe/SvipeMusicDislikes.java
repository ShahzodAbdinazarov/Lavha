package org.telegram.svipe;

import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * What this user has refused: songs and singers they said "not this" about.
 *
 * <p><b>Why this is not a favourites set.</b> Favourites are a LIST the user reads back, curates and
 * expects to survive being offline, which is why {@link SvipeFavouritesSet} carries merge rules,
 * pending removals and a sync clock. A dislike is not a list — nobody opens "songs I refused". It is
 * an instruction to the recommender, and the recommender lives on the server. So the server is the
 * source of truth here and this class is a cache of two id sets, kept for exactly one purpose: the
 * ⋮ menu has to know, without a round trip, whether to say "dislike" or "undo dislike".
 *
 * <p>Writes are optimistic and pushed immediately; if a push fails the local flip is rolled back,
 * because a refusal the server never heard is a refusal that will not be honoured, and a menu that
 * claims otherwise is worse than one that failed visibly.
 *
 * <p>Only CANONICAL ids live here (a catalog song, a catalog artist), which is all the song and
 * singer pages ever deal in. Audio we do not host has no id to refuse.
 */
public final class SvipeMusicDislikes {

    private static final SvipeMusicDislikes[] instances = new SvipeMusicDislikes[16];

    private static final String PREFS = "svipe_music_dislikes";
    private static final String KEY_SONGS = "songs_";
    private static final String KEY_ARTISTS = "artists_";

    private final int account;
    private final Set<Long> songs = new HashSet<>();
    private final Set<Long> artists = new HashSet<>();
    private boolean syncStarted;

    private SvipeMusicDislikes(int account) {
        this.account = account;
        restore();
    }

    public static synchronized SvipeMusicDislikes getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            return new SvipeMusicDislikes(account);
        }
        if (instances[account] == null) {
            instances[account] = new SvipeMusicDislikes(account);
        }
        return instances[account];
    }

    public synchronized boolean isSongDisliked(long songId) {
        return songId > 0 && songs.contains(songId);
    }

    public synchronized boolean isArtistDisliked(long artistId) {
        return artistId > 0 && artists.contains(artistId);
    }

    /**
     * Flip a song's refusal, optimistically.
     *
     * @return the state the UI should draw immediately; a failed push rolls it back and notifies
     */
    public boolean toggleSong(long songId) {
        if (songId <= 0) {
            return false;
        }
        final boolean nowDisliked;
        synchronized (this) {
            nowDisliked = !songs.contains(songId);
            if (nowDisliked) {
                songs.add(songId);
            } else {
                songs.remove(songId);
            }
            persist();
        }
        notifyChanged();
        final SvipeMusic.DislikeCallback cb = (id, disliked, favourite, error) -> {
            if (error != null) {
                rollbackSong(songId, nowDisliked);
                return;
            }
            adoptSong(songId, disliked);
            // The server drops the favourite when a dislike lands. Adopting that here is what keeps
            // the heart on the same page from staying filled after the user said the opposite.
            if (disliked && !favourite) {
                SvipeFavouritesSet.getInstance(account).removeSong(songId);
            }
        };
        if (nowDisliked) {
            SvipeMusic.dislikeSong(account, songId, cb);
        } else {
            SvipeMusic.undislikeSong(account, songId, cb);
        }
        return nowDisliked;
    }

    /** Flip a singer's refusal. Refusing a singer refuses their catalog — that is done server-side. */
    public boolean toggleArtist(long artistId) {
        if (artistId <= 0) {
            return false;
        }
        final boolean nowDisliked;
        synchronized (this) {
            nowDisliked = !artists.contains(artistId);
            if (nowDisliked) {
                artists.add(artistId);
            } else {
                artists.remove(artistId);
            }
            persist();
        }
        notifyChanged();
        final SvipeMusic.DislikeCallback cb = (id, disliked, favourite, error) -> {
            if (error != null) {
                rollbackArtist(artistId, nowDisliked);
                return;
            }
            adoptArtist(artistId, disliked);
            if (disliked && !favourite) {
                SvipeArtistFavouritesSet.getInstance(account).removeArtist(artistId);
            }
        };
        if (nowDisliked) {
            SvipeMusic.dislikeArtist(account, artistId, cb);
        } else {
            SvipeMusic.undislikeArtist(account, artistId, cb);
        }
        return nowDisliked;
    }

    /**
     * One-shot per process: adopt the server's two sets wholesale.
     *
     * <p>No merge, unlike favourites: the server owns this state, every write goes through it, and a
     * device that was offline has nothing of its own to defend — its failed writes were rolled back
     * at the time.
     */
    public void syncFromServer() {
        synchronized (this) {
            if (syncStarted) {
                return;
            }
            syncStarted = true;
        }
        SvipeMusic.dislikes(account, (songIds, artistIds, error) -> {
            if (songIds == null || artistIds == null) {
                return;     // offline / auth — keep the cache, try again next launch
            }
            synchronized (this) {
                songs.clear();
                songs.addAll(songIds);
                artists.clear();
                artists.addAll(artistIds);
                persist();
            }
            notifyChanged();
        });
    }

    private synchronized void adoptSong(long songId, boolean disliked) {
        if (disliked) {
            songs.add(songId);
        } else {
            songs.remove(songId);
        }
        persist();
    }

    private synchronized void adoptArtist(long artistId, boolean disliked) {
        if (disliked) {
            artists.add(artistId);
        } else {
            artists.remove(artistId);
        }
        persist();
    }

    private void rollbackSong(long songId, boolean attempted) {
        adoptSong(songId, !attempted);
        notifyChanged();
    }

    private void rollbackArtist(long artistId, boolean attempted) {
        adoptArtist(artistId, !attempted);
        notifyChanged();
    }

    private void notifyChanged() {
        // GLOBAL, like the two favourite sets: a song page, an artist page and the mini player can
        // all be alive at once, and the id is declared global for exactly that reason. Posting
        // per-account here would leave every one of them showing the state from before the tap.
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.svipeMusicDislikesChanged));
    }

    private void persist() {
        try {
            prefs().edit()
                    .putStringSet(KEY_SONGS + account, ids(songs))
                    .putStringSet(KEY_ARTISTS + account, ids(artists))
                    .apply();
        } catch (Exception ignore) {
        }
    }

    private void restore() {
        try {
            addAll(songs, prefs().getStringSet(KEY_SONGS + account, Collections.emptySet()));
            addAll(artists, prefs().getStringSet(KEY_ARTISTS + account, Collections.emptySet()));
        } catch (Exception ignore) {
        }
    }

    private static void addAll(Set<Long> target, Set<String> raw) {
        for (String s : raw) {
            try {
                target.add(Long.parseLong(s));
            } catch (NumberFormatException ignore) {
            }
        }
    }

    private static Set<String> ids(Set<Long> src) {
        final HashSet<String> out = new HashSet<>();
        for (Long v : src) {
            out.add(String.valueOf(v));
        }
        return out;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }
}
