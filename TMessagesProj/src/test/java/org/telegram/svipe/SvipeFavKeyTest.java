package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The favourite identity precedence rule. Pure JVM (no MessageObject, no Android), per the fork's
 * test constraints — the MessageObject plumbing lives in SvipeMusicFavourites and is verified on-device.
 */
public class SvipeFavKeyTest {

    private static final long CHANNEL_DIALOG = -1001234L;   // dialogId of a channel
    private static final long USER_DIALOG = 5555L;          // dialogId of a private chat
    private static final long DOC = 99999L;

    @Test
    public void catalogSongWinsOverEverythingElse() {
        // Even a public channel post with a document keys on the canonical song, so the same recording
        // is one favourite no matter which screen it was hearted from.
        SvipeFavKey k = SvipeFavKey.of(777, CHANNEL_DIALOG, 42, true, DOC);
        assertEquals(SvipeFavKey.KIND_SONG, k.kind);
        assertEquals("song:777", k.key);
        assertEquals(777, k.songId);
    }

    @Test
    public void publicChannelPostKeysOnChannelAndMessage() {
        SvipeFavKey k = SvipeFavKey.of(0, CHANNEL_DIALOG, 42, true, DOC);
        assertEquals(SvipeFavKey.KIND_MSG, k.kind);
        // dialogId is negative for channels; the catalog stores the channel id positive.
        assertEquals("msg:1001234:42", k.key);
        assertEquals(1001234L, k.channelId);
        assertEquals(42, k.messageId);
    }

    @Test
    public void privateChannelFallsBackToDocument() {
        // Not public -> must NOT be addressable by (channel, message): it stays a device-local doc key.
        SvipeFavKey k = SvipeFavKey.of(0, CHANNEL_DIALOG, 42, false, DOC);
        assertEquals(SvipeFavKey.KIND_DOC, k.kind);
        assertEquals("doc:99999", k.key);
    }

    @Test
    public void privateChatFallsBackToDocument() {
        SvipeFavKey k = SvipeFavKey.of(0, USER_DIALOG, 42, false, DOC);
        assertEquals(SvipeFavKey.KIND_DOC, k.kind);
        assertEquals("doc:99999", k.key);
    }

    @Test
    public void publicFlagOnAUserDialogIsIgnored() {
        // A positive dialogId is a user; it can never be a public channel post however it is flagged.
        SvipeFavKey k = SvipeFavKey.of(0, USER_DIALOG, 42, true, DOC);
        assertEquals(SvipeFavKey.KIND_DOC, k.kind);
    }

    @Test
    public void missingRealIdFallsBackToDocument() {
        // getRealId()==0 would otherwise mint "msg:<channel>:0", which collides across every post.
        SvipeFavKey k = SvipeFavKey.of(0, CHANNEL_DIALOG, 0, true, DOC);
        assertEquals(SvipeFavKey.KIND_DOC, k.kind);
    }

    @Test
    public void noIdentityAtAllReturnsNull() {
        // The heart must stay hidden rather than favourite something it cannot find again.
        assertNull(SvipeFavKey.of(0, USER_DIALOG, 42, false, 0));
        assertNull(SvipeFavKey.of(0, CHANNEL_DIALOG, 0, false, 0));
    }

    @Test
    public void onlyCatalogSongsAreSyncable() {
        // The privacy invariant: nothing but a catalog song may ever reach the backend.
        assertTrue(SvipeFavKey.of(777, CHANNEL_DIALOG, 42, true, DOC).isSyncable());
        assertFalse(SvipeFavKey.of(0, CHANNEL_DIALOG, 42, true, DOC).isSyncable());
        assertFalse(SvipeFavKey.of(0, USER_DIALOG, 42, false, DOC).isSyncable());
        // A Deezer placeholder carries a NEGATIVE song id and has no catalog row to point at.
        assertFalse(SvipeFavKey.song(-5).isSyncable());
    }

    @Test
    public void keysAreValueEqual() {
        assertEquals(SvipeFavKey.song(1), SvipeFavKey.song(1));
        assertEquals(SvipeFavKey.song(1).hashCode(), SvipeFavKey.song(1).hashCode());
        assertFalse(SvipeFavKey.song(1).equals(SvipeFavKey.song(2)));
        // Different kinds never collide on the same underlying number.
        assertFalse(SvipeFavKey.song(1).equals(SvipeFavKey.document(1)));
    }
}
