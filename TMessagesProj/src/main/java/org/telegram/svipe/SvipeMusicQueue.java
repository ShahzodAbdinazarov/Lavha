package org.telegram.svipe;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A playable music queue built from Svipe catalog tracks. Extends SavedMusicList so
 * MediaController's existing external-playlist machinery works unchanged: AudioPlayerAlert paginates
 * via loadMoreMusic() -> load(), and appended pages merge through NotificationCenter.musicListLoaded
 * (which re-sorts the playlist and fixes the playing index).
 *
 * Ordering trick: MediaController.sortPlaylist orders negative ids descending and auto-advance
 * traverses toward index 0, so play order equals ascending id order. Each queue entry gets a
 * synthetic negative id from a monotonically increasing counter (play order == mint order), while
 * messageOwner.realId keeps the REAL channel message id so FileRefController can repair expired
 * file_references via channels.getMessages (see FileRefController's MessageObject parent branch).
 */
public class SvipeMusicQueue extends MessagesController.SavedMusicList {

    public static final String SOURCE_VIBE = "vibe";
    public static final String SOURCE_SEED = "seed";
    public static final String SOURCE_SECTION = "section";
    public static final String SOURCE_SEARCH = "search";

    // Far below SharedConfig.getLastLocalId()'s range so synthetic queue ids can never collide
    // with other local message ids in this process.
    private static final AtomicInteger ID_COUNTER = new AtomicInteger(-1_900_000_000);

    /** The queue currently installed into MediaController, if any. */
    private static SvipeMusicQueue activeQueue;

    static {
        // Runs the first time anything touches this class, which is the first time a Svipe queue is
        // built — exactly when the handler starts being able to fire.
        MediaController.setPlaylistEndHandler(SvipeMusicQueue::onPlaylistEnded);
    }

    /**
     * A finite queue (favourites, a search, a section) has played its last track with repeat off.
     * Rather than falling silent, keep the music going on the wave of what just finished.
     *
     * <p>A self-paging queue is only left alone once the backend has said it has nothing more:
     * endReached is what distinguishes "out of recommendations" from "the last page load failed",
     * and the latter should recover rather than end the listening session.
     */
    private static boolean onPlaylistEnded(MessageObject last) {
        SvipeMusicQueue q = getActive();
        SvipeMusic.Track t = q == null ? null : q.trackFor(last);
        boolean exhausted = q != null && (q.infinite || q.continuing) && q.endReached;
        if (!SvipeVibePlan.handsOffToVibe(q != null, exhausted, t != null)) {
            return false;
        }
        // Reached only when the continuation never arrived — a page load that failed, or a list too
        // short to have crossed the prefetch mark. Seed it on the whole list all the same: what should
        // follow a favourites list is decided by the list, not by whichever track it happened to end on.
        SvipeVibe.startFromList(q.account, q.seedKeys(), t, null);
        return true;
    }

    // Set only while play()'s setPlaylist() runs. setPlaylist clears currentSavedMusicList and then
    // SYNCHRONOUSLY posts messagePlayingDidStart for the first track before returning, so getActive()
    // must fall back to this during that window or the first track's PLAY_START is lost.
    private static SvipeMusicQueue installing;

    public final int account;
    public final String source;
    public final String title;
    private final boolean infinite;

    public String recommendationId;
    private String nextCursor;
    private Long seedChannelId;
    private Integer seedMessageId;
    private boolean loadFailed;
    /**
     * Set once a finite list has started pulling a vibe continuation onto its own end. From then on
     * it pages like a vibe queue — same cursor, same "4 tracks left" trigger — so the listener never
     * hears the seam between the list they chose and the music that follows it.
     */
    private boolean continuing;

    private final HashMap<Integer, SvipeMusic.Track> trackBySyntheticId = new HashMap<>();
    private final HashSet<String> queuedKeys = new HashSet<>();

    /**
     * "channelId:messageId" -> canonical song id, for every catalog track this process has queued.
     * trackFor() only answers while THIS queue is the installed one, but a favourite must still know a
     * song's catalog identity after playback moved on to another queue — otherwise the same song would
     * be favourited once as "song:<id>" and once as "msg:<channel>:<message>". Bounded LRU.
     */
    private static final LinkedHashMap<String, Long> SONG_ID_BY_COMPOSITE =
        new LinkedHashMap<String, Long>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > 512;
            }
        };

    /** The catalog song for a resolved (channel, message), or 0 when this process never saw it. */
    public static synchronized long cachedSongId(long channelId, int messageId) {
        Long id = SONG_ID_BY_COMPOSITE.get(channelId + ":" + messageId);
        return id != null ? id : 0;
    }

    private static synchronized void cacheSongId(SvipeMusic.Track t) {
        if (t != null && t.songId != 0) {
            SONG_ID_BY_COMPOSITE.put(t.key(), t.songId);
        }
    }

    /**
     * Record a catalog identity learned somewhere other than a queue — today, the favourites code
     * looking a channel post up via /v1/music/track. Without this, the next identity lookup for the same
     * post would still come back empty and the favourite would be keyed twice.
     */
    public static synchronized void cacheSongId(long channelId, int messageId, long songId) {
        if (songId != 0) {
            SONG_ID_BY_COMPOSITE.put(channelId + ":" + messageId, songId);
        }
    }

    public SvipeMusicQueue(int account, String source, String title, boolean infinite) {
        super(account, 0);
        this.account = account;
        this.source = source;
        this.title = title;
        this.infinite = infinite;
        this.endReached = !infinite;
    }

    public void setVibeSeed(Long seedChannelId, Integer seedMessageId) {
        this.seedChannelId = seedChannelId;
        this.seedMessageId = seedMessageId;
    }

    public void setCursor(String cursor) {
        this.nextCursor = cursor;
        if ((infinite || continuing) && cursor == null) {
            endReached = true;
        }
    }

    public static SvipeMusicQueue getActive() {
        MediaController mc = MediaController.getInstance();
        if (activeQueue != null && mc.currentSavedMusicList == activeQueue) {
            return activeQueue;
        }
        // Non-null only inside play()'s setPlaylist call, when currentSavedMusicList is transiently null.
        return installing;
    }

    /**
     * The catalog song behind a playing message — the tolerant lookup, and the one every caller
     * should use.
     *
     * <p>{@link #getActive()} answers null for a moment on every track change: it only recognises the
     * queue once {@code MediaController.currentSavedMusicList} points at it, and between installing a
     * playlist and that field being adopted there is a window where the app knows perfectly well what
     * is playing and this class says "not mine". Anything drawing STATE from the answer — the heart in
     * the mini player, the like/dislike buttons in the notification — flickers back to its
     * not-our-song look for exactly that window. So the queue we last installed is consulted too, and
     * every answer is remembered per (channel, message) so a later miss cannot un-know it.
     */
    public static synchronized long songIdFor(MessageObject mo, long dialogId, int realId) {
        if (mo == null) {
            return 0;
        }
        SvipeMusicQueue q = getActive();
        if (q == null) {
            q = activeQueue;   // mid-swap: the list is ours, the controller has just not adopted it yet
        }
        if (q != null) {
            final SvipeMusic.Track t = q.trackFor(mo);
            if (t != null && t.songId != 0) {
                cacheSongId(t);
                return t.songId;
            }
        }
        if (dialogId < 0 && realId != 0) {
            return cachedSongId(-dialogId, realId);
        }
        return 0;
    }

    public SvipeMusic.Track trackFor(MessageObject mo) {
        if (mo == null) {
            return null;
        }
        return trackBySyntheticId.get(mo.getId());
    }

    /** Finds the queue entry for a catalog track key ("channelId:messageId"), or null. */
    public MessageObject messageForKey(String key) {
        for (int i = 0; i < list.size(); i++) {
            MessageObject mo = list.get(i);
            SvipeMusic.Track t = trackBySyntheticId.get(mo.getId());
            if (t != null && t.key().equals(key)) {
                return mo;
            }
        }
        return null;
    }

    /**
     * Appends resolved tracks (in play order) to this queue. resolved maps Track.key() -> real
     * channel message. Tracks that failed to resolve or duplicate already-queued ones are skipped.
     * Returns the created MessageObjects.
     */
    public ArrayList<MessageObject> appendResolved(List<SvipeMusic.Track> tracks, Map<String, TLRPC.Message> resolved) {
        ArrayList<MessageObject> added = new ArrayList<>();
        for (SvipeMusic.Track t : tracks) {
            if (!queuedKeys.add(t.key())) {
                continue;
            }
            TLRPC.Message real = resolved.get(t.key());
            if (real == null) {
                continue;
            }
            MessageObject mo = buildQueueMessage(account, real);
            if (mo == null || !mo.isMusic()) {
                continue;
            }
            trackBySyntheticId.put(mo.getId(), t);
            cacheSongId(t);
            list.add(mo);
            added.add(mo);
        }
        return added;
    }

    /** Wraps a real channel message into a synthetic-id copy that keeps repair metadata. */
    private static MessageObject buildQueueMessage(int account, TLRPC.Message real) {
        if (real.media == null || real.media.document == null) {
            return null;
        }
        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.id = ID_COUNTER.incrementAndGet();
        msg.realId = real.id;
        msg.peer_id = real.peer_id;
        msg.from_id = real.from_id != null ? real.from_id : real.peer_id;
        msg.date = real.date;
        msg.message = "";
        msg.media = real.media;
        msg.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA | TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        return new MessageObject(account, msg, false, true);
    }

    /**
     * Installs this queue into MediaController and starts playback at the given entry.
     * Call after the first page has been appended.
     */
    public boolean play(MessageObject first) {
        if (list.isEmpty()) {
            return false;
        }
        if (first == null) {
            first = list.get(0);
        }
        MediaController mc = MediaController.getInstance();
        activeQueue = this;
        installing = this;
        boolean ok;
        try {
            // loadMusic=false: our pages come from the catalog, so MediaController must not try to
            // extend this playlist out of a dialog's history.
            // setPlaylist synchronously posts messagePlayingDidStart for `first`; `installing` lets
            // getActive() resolve this queue during that window so the first PLAY_START is reported.
            ok = mc.setPlaylist(new ArrayList<>(list), first, 0, false, null);
        } finally {
            installing = null;
        }
        // ...but loadMusic=false also means forceLoopCurrentPlaylist, and that half is wrong here: it
        // wraps the queue unconditionally, which made the repeat setting a dead control and left every
        // list looping whether the listener asked for it or not. Undo it and let repeatMode decide —
        // the end of a finite queue then reaches onPlaylistEnded and flows into a vibe.
        mc.setForceLoopCurrentPlaylist(false);
        // setPlaylist -> clearPlaylist nulls currentSavedMusicList, so install ourselves after.
        mc.currentSavedMusicList = this;
        return ok;
    }

    /**
     * Fetches the next page when playback nears the end of the queue.
     *
     * <p>A finite list (favourites, a search) does this too. It used to wait until its last track had
     * finished and only then ask, which left a gap of silence exactly where the music should have
     * flowed on — and it seeded that request with the last track alone, so a five-artist favourites
     * list continued as one artist. Now the whole list seeds the continuation, and it is fetched
     * while there is still music playing. The decision itself lives in {@link SvipeVibePlan}.
     */
    public void maybeExtend(MessageObject playing) {
        if (!SvipeVibePlan.shouldFetchMore(
                list.size(), list.indexOf(playing), infinite || continuing, endReached, loading)) {
            return;
        }
        if (!infinite && !continuing) {
            // A finite queue is constructed already "ended" so nothing tries to page it. Opening the
            // continuation is what lifts that; the list keeps its own identity in the player (title,
            // source), it just stops falling silent at the bottom.
            continuing = true;
            endReached = false;
        }
        load();
    }

    /**
     * The keys of every catalog track in this queue, newest first, for seeding a continuation on the
     * whole list. Newest first because a long list is capped server-side: what the listener queued
     * most recently is the better description of what they want next.
     */
    private ArrayList<String> seedKeys() {
        ArrayList<String> keys = new ArrayList<>();
        for (int i = list.size() - 1; i >= 0; i--) {
            SvipeMusic.Track t = trackBySyntheticId.get(list.get(i).getId());
            if (t != null && !keys.contains(t.key())) {
                keys.add(t.key());
            }
        }
        return keys;
    }

    /** Called by MediaController.loadMoreMusic() (AudioPlayerAlert scroll) and maybeExtend(). */
    @Override
    public void load() {
        if (loading || endReached || (!infinite && !continuing)) {
            return;
        }
        loading = true;
        loadFailed = false;
        // A continuing finite list seeds on all of itself; a vibe queue keeps riding its own cursor,
        // which already carries whatever seeded it.
        final ArrayList<String> seeds = continuing && nextCursor == null ? seedKeys() : null;
        SvipeMusic.vibe(account, nextCursor, seedChannelId, seedMessageId, seeds, (items, recId, cursor, error) -> {
            if (items == null || items.isEmpty()) {
                loading = false;
                loadFailed = error != null;
                if (error == null) {
                    endReached = true;
                }
                return;
            }
            if (recId != null) {
                recommendationId = recId;
            }
            setCursor(cursor);
            SvipeMusicResolver.resolve(account, items, resolved -> {
                ArrayList<MessageObject> added = appendResolved(items, resolved);
                loading = false;
                if (!added.isEmpty()) {
                    totalCount = list.size();
                    // MediaController merges our grown list into its playlist (re-sort + fix the
                    // playing index) and notifies AudioPlayerAlert via moreMusicDidLoad.
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.musicListLoaded, this);
                }
            });
        });
    }

    /** Allows a manual retry after a failed page load (e.g. next maybeExtend tick). */
    public boolean lastLoadFailed() {
        return loadFailed;
    }
}
