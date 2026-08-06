package org.telegram.svipe;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;

import java.util.ArrayList;

/**
 * "My vibe by track": a vibe seeded by one song, so the queue opens on music the backend judges
 * similar to it rather than on the user's general taste.
 *
 * <p>The backend has always accepted a seed ({@code /v1/music/vibe?seed_channel_id&seed_message_id});
 * this is the client side of it. Three places start one and they must behave identically, so they all
 * come through here: the song page's action row, the player's overflow menu, and the automatic
 * hand-off when a finite queue runs out (see {@link SvipeMusicQueue}).
 */
public class SvipeVibe {

    public interface Callback {
        void onDone(boolean started);
    }

    /**
     * Only one start may be in flight. Two overlapping starts would each install a playlist, and the
     * loser's would win by arriving second — the user would hear the vibe of whichever request the
     * network happened to finish last.
     */
    private static boolean inFlight;

    public static boolean isStarting() {
        return inFlight;
    }

    /**
     * Starts a fresh vibe and plays it.
     *
     * @param seed        the track to build the vibe around, or null to start the listener's own
     *                    unseeded vibe. Null is a normal case, not a failure: the button is offered on
     *                    anything playable, and plenty of audio — a file someone sent in a chat — has
     *                    no catalog identity to seed from. The backend tolerates an unknown seed by
     *                    falling back to trending, but asking it for the personal vibe instead gives
     *                    the listener something chosen for them rather than for everyone.
     * @param includeSeed whether the seed itself opens the queue. True when the user asked for the
     *                    vibe of a song they are looking at or listening to — dropping it would answer
     *                    a tap on that song with somebody else's music. False when the seed has just
     *                    finished playing, where replaying it would be a stutter, not a continuation.
     *                    Ignored without a seed.
     * @param resumeFrom  the currently-playing object to inherit the playback position from, or null
     *                    to open the seed from the start. Only consulted when includeSeed is true.
     */
    public static void start(int account, SvipeMusic.Track seed, boolean includeSeed,
                             MessageObject resumeFrom, Callback cb) {
        start(account, seed, includeSeed, resumeFrom, null, cb);
    }

    /**
     * Starts a vibe seeded by a whole list — what a finite queue (favourites, a search) should flow
     * into when it runs out. The list decides what comes next; the track that happened to be last is
     * only carried so the queue still has a catalog identity to report events against.
     *
     * @param seedKeys "channelId:messageId" of every catalog track the list held
     */
    public static void startFromList(int account, java.util.List<String> seedKeys,
                                     SvipeMusic.Track last, Callback cb) {
        start(account, last, false, null, seedKeys, cb);
    }

    private static void start(int account, SvipeMusic.Track seed, boolean includeSeed,
                              MessageObject resumeFrom, java.util.List<String> seedKeys, Callback cb) {
        if (inFlight) {
            done(cb, false);
            return;
        }
        final boolean fromList = seedKeys != null && !seedKeys.isEmpty();
        final boolean seeded = seed != null;
        final boolean openOnSeed = seeded && includeSeed;
        inFlight = true;
        SvipeMusic.vibe(account, null, seeded ? seed.channelId : null, seeded ? seed.messageId : null,
                seedKeys, (items, recId, cursor, error) -> {
            if (items == null || items.isEmpty()) {
                inFlight = false;
                done(cb, false);
                return;
            }
            // Rotates the vibe epoch for the next session. The backend ignores which track carries it,
            // so an unseeded start can ride the first item.
            SvipeMusic.sendEvent(account, seeded ? seed : items.get(0), "VIBE_OPEN", null);

            SvipeMusicQueue queue = new SvipeMusicQueue(account,
                    seeded ? SvipeMusicQueue.SOURCE_SEED : SvipeMusicQueue.SOURCE_VIBE,
                    LocaleController.getString(R.string.MusicMyVibe), true);
            queue.recommendationId = recId;
            if (seeded && !fromList) {
                // The seed rides the cursor too, so every page this queue pulls later stays on the same
                // wave instead of silently degrading into the generic vibe at track six.
                //
                // Never when a list seeded this: the cursor already carries every one of those seeds,
                // and a single seed sent beside it would OUTRANK the cursor server-side — page two
                // would narrow back down to one track, which is the bug this whole path exists to fix.
                queue.setVibeSeed(seed.channelId, seed.messageId);
            }
            queue.setCursor(cursor);

            ArrayList<SvipeMusic.Track> tracks = new ArrayList<>();
            if (openOnSeed) {
                tracks.add(seed);
            }
            tracks.addAll(items);

            SvipeMusicResolver.resolve(account, tracks, resolved -> {
                queue.appendResolved(tracks, resolved);
                inFlight = false;
                if (queue.list.isEmpty()) {
                    done(cb, false);
                    return;
                }
                MessageObject first = queue.list.get(0);
                SvipeMusic.Track firstTrack = queue.trackFor(first);
                if (SvipeVibePlan.carriesProgress(openOnSeed, resumeFrom != null,
                        firstTrack == null ? null : firstTrack.key(), seeded ? seed.key() : null)) {
                    carryProgress(resumeFrom, first);
                }
                done(cb, queue.play(first));
            });
        });
    }

    /**
     * Hands the seek position over to the queue's own copy of the track. The queue mints a fresh
     * MessageObject per entry, so the object being played is never the one the player was showing;
     * without this the song would restart from zero. playMessage only takes its seek branch when
     * audioProgress is non-zero, so both fields have to travel.
     */
    private static void carryProgress(MessageObject from, MessageObject to) {
        to.audioProgress = from.audioProgress;
        to.audioProgressMs = from.audioProgressMs;
        to.audioProgressSec = from.audioProgressSec;
    }

    private static void done(Callback cb, boolean started) {
        if (cb != null) {
            cb.onDone(started);
        }
    }
}
