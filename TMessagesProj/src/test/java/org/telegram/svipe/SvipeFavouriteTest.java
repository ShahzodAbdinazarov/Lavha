package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Serialization of the stored favourite: a round-trip must be lossless, and a corrupt blob must
 *  degrade to an empty list rather than take the app down on start-up. */
public class SvipeFavouriteTest {

    private static SvipeFavourite sample() {
        SvipeFavourite f = SvipeFavourite.of(SvipeFavKey.message(1001234L, 42));
        f.username = "musicchannel";
        f.dialogId = -1001234L;
        f.title = "Yomg'ir";
        f.artist = "Ozoda";
        f.durationS = 213;
        f.isPublic = true;
        f.addedAt = 1_700_000_000_000L;
        return f;
    }

    @Test
    public void roundTripKeepsEveryField() {
        SvipeFavourite in = sample();
        SvipeFavourite out = SvipeFavourite.fromJson(in.toJson());
        assertNotNull(out);
        assertEquals(in.key, out.key);
        assertEquals(in.kind, out.kind);
        assertEquals(in.channelId, out.channelId);
        assertEquals(in.messageId, out.messageId);
        assertEquals(in.username, out.username);
        assertEquals(in.dialogId, out.dialogId);
        assertEquals(in.title, out.title);
        assertEquals(in.artist, out.artist);
        assertEquals(in.durationS, out.durationS);
        assertEquals(in.isPublic, out.isPublic);
        assertEquals(in.addedAt, out.addedAt);
    }

    @Test
    public void listRoundTripPreservesOrder() {
        List<SvipeFavourite> in = Arrays.asList(
                SvipeFavourite.of(SvipeFavKey.song(1)),
                SvipeFavourite.of(SvipeFavKey.song(2)),
                SvipeFavourite.of(SvipeFavKey.document(3)));
        List<SvipeFavourite> out = SvipeFavourite.deserialize(SvipeFavourite.serialize(in));
        assertEquals(3, out.size());
        assertEquals("song:1", out.get(0).key);
        assertEquals("song:2", out.get(1).key);
        assertEquals("doc:3", out.get(2).key);
    }

    @Test
    public void nullAndEmptyOptionalsSurviveTheRoundTrip() {
        SvipeFavourite f = SvipeFavourite.of(SvipeFavKey.document(7));
        List<SvipeFavourite> out = SvipeFavourite.deserialize(
                SvipeFavourite.serialize(java.util.Collections.singletonList(f)));
        assertEquals(1, out.size());
        assertNull(out.get(0).username);
        assertNull(out.get(0).title);
        assertNull(out.get(0).artist);
    }

    @Test
    public void corruptBlobYieldsEmptyListNotACrash() {
        assertTrue(SvipeFavourite.deserialize(null).isEmpty());
        assertTrue(SvipeFavourite.deserialize("").isEmpty());
        assertTrue(SvipeFavourite.deserialize("not json at all").isEmpty());
        assertTrue(SvipeFavourite.deserialize("{").isEmpty());
        assertTrue(SvipeFavourite.deserialize("[1,2,3]").isEmpty());       // array, not the {v,items} shape
        assertTrue(SvipeFavourite.deserialize("{\"v\":1}").isEmpty());      // no items
    }

    @Test
    public void entriesWithoutAKeyAreDropped() {
        // A half-written entry must not become a phantom favourite with a null key.
        assertNull(SvipeFavourite.fromJson(new JSONObject()));
        assertNull(SvipeFavourite.fromJson(null));
    }

    @Test
    public void oneBadEntryDoesNotLoseTheGoodOnes() {
        String blob = "{\"v\":1,\"items\":[{\"key\":\"song:1\"},{\"nope\":true},{\"key\":\"doc:2\"}]}";
        List<SvipeFavourite> out = SvipeFavourite.deserialize(blob);
        assertEquals(2, out.size());
        assertEquals("song:1", out.get(0).key);
        assertEquals("doc:2", out.get(1).key);
    }

    @Test
    public void nullsInTheListAreSkippedOnSerialize() {
        ArrayList<SvipeFavourite> in = new ArrayList<>();
        in.add(SvipeFavourite.of(SvipeFavKey.song(1)));
        in.add(null);
        assertEquals(1, SvipeFavourite.deserialize(SvipeFavourite.serialize(in)).size());
    }

    @Test
    public void onlyCatalogSongsAreSyncable() {
        assertTrue(SvipeFavourite.of(SvipeFavKey.song(5)).isSyncable());
        assertFalse(SvipeFavourite.of(SvipeFavKey.message(1, 2)).isSyncable());
        assertFalse(SvipeFavourite.of(SvipeFavKey.document(3)).isSyncable());
    }
}
