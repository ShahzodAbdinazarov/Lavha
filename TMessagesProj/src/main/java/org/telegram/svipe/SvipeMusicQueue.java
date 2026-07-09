package org.telegram.svipe;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    private final HashMap<Integer, SvipeMusic.Track> trackBySyntheticId = new HashMap<>();
    private final HashSet<String> queuedKeys = new HashSet<>();

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
        if (infinite && cursor == null) {
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
            // loadMusic=false: no dialog-history auto-extension, playlist loops (forceLoopCurrentPlaylist)
            // instead of stopping — the extension below appends pages long before a wrap can happen.
            // setPlaylist synchronously posts messagePlayingDidStart for `first`; `installing` lets
            // getActive() resolve this queue during that window so the first PLAY_START is reported.
            ok = mc.setPlaylist(new ArrayList<>(list), first, 0, false, null);
        } finally {
            installing = null;
        }
        // setPlaylist -> clearPlaylist nulls currentSavedMusicList, so install ourselves after.
        mc.currentSavedMusicList = this;
        return ok;
    }

    /** Fetches the next vibe page when playback nears the end of the queue. */
    public void maybeExtend(MessageObject playing) {
        if (!infinite || endReached || loading) {
            return;
        }
        int idx = list.indexOf(playing);
        if (idx < 0) {
            return;
        }
        if (list.size() - 1 - idx <= 4) {
            load();
        }
    }

    /** Called by MediaController.loadMoreMusic() (AudioPlayerAlert scroll) and maybeExtend(). */
    @Override
    public void load() {
        if (loading || endReached || !infinite) {
            return;
        }
        loading = true;
        loadFailed = false;
        SvipeMusic.vibe(account, nextCursor, seedChannelId, seedMessageId, (items, recId, cursor, error) -> {
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
