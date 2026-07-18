package org.telegram.svipe;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

/**
 * Bridges a playing {@link MessageObject} to the pure-JVM favourites model.
 *
 * <p>Kept apart from {@link SvipeFavKey} / {@link SvipeFavourite} on purpose: those stay free of
 * Android and Telegram types so the identity and serialization rules can be unit-tested, which deep UI
 * classes in this project cannot be.
 */
public final class SvipeMusicFavourites {

    private SvipeMusicFavourites() {
    }

    /** Identity for this audio, or null when it has none (the heart then stays hidden). */
    public static SvipeFavKey keyFor(int account, MessageObject mo) {
        if (mo == null) {
            return null;
        }
        long dialogId = mo.getDialogId();
        int realId = mo.getRealId();     // NOT getId(): catalog playback uses synthetic, process-local ids
        long songId = songIdFor(mo, dialogId, realId);
        TLRPC.Document doc = mo.getDocument();
        return SvipeFavKey.of(songId, dialogId, realId, isPublicChannel(account, dialogId),
                doc != null ? doc.id : 0);
    }

    /** A ready-to-store entry for this audio, carrying the display metadata and provenance. */
    public static SvipeFavourite entryFor(int account, MessageObject mo, SvipeFavKey key) {
        SvipeFavourite f = SvipeFavourite.of(key);
        long dialogId = mo.getDialogId();
        f.dialogId = dialogId;
        f.title = mo.getMusicTitle();
        f.artist = mo.getMusicAuthor();
        f.durationS = (int) mo.getDuration();
        f.isPublic = isPublicChannel(account, dialogId);
        // Always keep where this came from, for EVERY kind — the favourites list uses (dialogId,
        // messageId) to open the original message when it cannot play the audio itself, and a private
        // chat (dialogId > 0) is exactly the case that needs that fallback most.
        if (f.messageId == 0) {
            f.messageId = mo.getRealId();
        }
        if (f.channelId == 0 && dialogId < 0) {
            f.channelId = -dialogId;
        }
        if (f.isPublic) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
            if (chat != null) {
                f.username = ChatObject.getPublicUsername(chat);
            }
        }
        f.addedAt = System.currentTimeMillis();
        return f;
    }

    /**
     * Flip the favourite state for whatever is playing.
     *
     * <p>When the entry is a public-channel post that we may in fact host, the catalog song id is
     * looked up in the background and the entry re-keyed onto it — otherwise favouriting the same song
     * once from the Music tab and once from the channel would leave two entries.
     */
    public static void toggle(int account, MessageObject mo, SvipeFavKey key) {
        if (mo == null || key == null) {
            return;
        }
        SvipeFavouritesSet set = SvipeFavouritesSet.getInstance(account);
        SvipeFavourite entry = entryFor(account, mo, key);
        boolean nowFavourite = set.toggle(entry);
        if (nowFavourite && key.kind == SvipeFavKey.KIND_MSG) {
            final String msgKey = key.key;
            SvipeMusic.trackSongId(account, key.channelId, key.messageId, songId -> {
                if (songId > 0) {
                    // Seed the identity cache FIRST. keyFor() is recomputed from scratch every time the
                    // heart refreshes, so without this the very next refresh would derive the old "msg:"
                    // key again, find nothing under it, and flash the heart back to empty.
                    SvipeMusicQueue.cacheSongId(key.channelId, key.messageId, songId);
                }
                set.upgradeToSong(msgKey, songId);
            });
        }
    }

    /**
     * The catalog song for this audio: from the live queue first, then from the per-process cache of
     * everything the catalog has resolved (the queue may since have been replaced).
     */
    private static long songIdFor(MessageObject mo, long dialogId, int realId) {
        SvipeMusicQueue active = SvipeMusicQueue.getActive();
        if (active != null) {
            SvipeMusic.Track t = active.trackFor(mo);
            if (t != null && t.songId != 0) {
                return t.songId;
            }
        }
        if (dialogId < 0 && realId != 0) {
            return SvipeMusicQueue.cachedSongId(-dialogId, realId);
        }
        return 0;
    }

    /**
     * Whether this dialog is a channel anyone can open. Unknown chats are treated as NOT public, so a
     * cold-start miss keeps the favourite device-local rather than leaking it — fail closed.
     */
    private static boolean isPublicChannel(int account, long dialogId) {
        if (dialogId >= 0) {
            return false;   // a user dialog is never public
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        return chat != null && ChatObject.isPublic(chat);
    }
}
