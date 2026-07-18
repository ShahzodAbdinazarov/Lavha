package org.telegram.svipe;

/**
 * Stable identity for a favourited song.
 *
 * <p>The heart in the mini player has to work for whatever is playing, and "whatever is playing"
 * arrives in three very different shapes. Each gets its own key kind, tried in this order:
 *
 * <ol>
 *   <li>{@link #KIND_SONG} — the audio maps to a canonical catalog song. This is the only kind the
 *       backend ever hears about, so it is the only one that survives a reinstall or reaches a second
 *       device.</li>
 *   <li>{@link #KIND_MSG} — a message in a PUBLIC channel we do not (yet) have in the catalog. It is
 *       identified by its channel + message, which anyone could resolve, but we still keep it on the
 *       device only.</li>
 *   <li>{@link #KIND_DOC} — anything else: a private chat, a private channel, Saved Messages. Keyed by
 *       the audio document id and never sent anywhere. This is the "not in a public channel -> only I
 *       can see it" case.</li>
 * </ol>
 *
 * <p>Two things must NEVER be used as identity:
 * <ul>
 *   <li>{@code MessageObject.getId()} — {@link SvipeMusicQueue} mints synthetic, process-local ids for
 *       catalog playback. Use {@code getRealId()}.</li>
 *   <li>{@code access_hash} / {@code file_reference} — both are session-scoped and expire; FileRefController
 *       re-fetches them at play time.</li>
 * </ul>
 *
 * <p>Deliberately pure JVM (no Android imports) so the whole precedence rule is unit-testable — deep UI
 * classes are not testable in this project.
 */
public final class SvipeFavKey {

    public static final int KIND_SONG = 1;
    public static final int KIND_MSG = 2;
    public static final int KIND_DOC = 3;

    public final int kind;
    public final long songId;       // KIND_SONG only, else 0
    public final long channelId;    // KIND_MSG only, else 0
    public final int messageId;     // KIND_MSG only, else 0
    public final long documentId;   // KIND_DOC only, else 0
    public final String key;

    private SvipeFavKey(int kind, long songId, long channelId, int messageId, long documentId, String key) {
        this.kind = kind;
        this.songId = songId;
        this.channelId = channelId;
        this.messageId = messageId;
        this.documentId = documentId;
        this.key = key;
    }

    public static SvipeFavKey song(long songId) {
        return new SvipeFavKey(KIND_SONG, songId, 0, 0, 0, "song:" + songId);
    }

    public static SvipeFavKey message(long channelId, int messageId) {
        return new SvipeFavKey(KIND_MSG, 0, channelId, messageId, 0, "msg:" + channelId + ":" + messageId);
    }

    public static SvipeFavKey document(long documentId) {
        return new SvipeFavKey(KIND_DOC, 0, 0, 0, documentId, "doc:" + documentId);
    }

    /**
     * The precedence rule, expressed over primitives so it can be tested without a MessageObject.
     *
     * @param songId     canonical song id, or 0 when unknown
     * @param dialogId   MessageObject#getDialogId (negative for channels/chats)
     * @param realId     MessageObject#getRealId — never getId()
     * @param publicPeer the dialog is a channel with a username
     * @param documentId the audio document id, or 0 when there is no document
     * @return the key, or null when the audio carries no usable identity (heart must then stay hidden)
     */
    public static SvipeFavKey of(long songId, long dialogId, int realId, boolean publicPeer, long documentId) {
        if (songId != 0) {
            return song(songId);
        }
        // A public channel post is addressable by (channel, message); dialogId is negative there, and the
        // catalog stores the channel id positive, so flip the sign to match.
        if (publicPeer && dialogId < 0 && realId != 0) {
            return message(-dialogId, realId);
        }
        if (documentId != 0) {
            return document(documentId);
        }
        return null;
    }

    /** True when the backend is allowed to know about this favourite. */
    public boolean isSyncable() {
        return kind == KIND_SONG && songId > 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SvipeFavKey && key.equals(((SvipeFavKey) o).key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }
}
