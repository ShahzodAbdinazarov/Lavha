package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Serialization of a stored favourite singer: a round-trip must be lossless, and a corrupt blob must
 *  degrade to an empty list rather than take the app down on start-up. */
public class SvipeArtistFavouriteTest {

    private static SvipeMusic.Artist artist(long id, String name) {
        SvipeMusic.Artist a = new SvipeMusic.Artist();
        a.id = id;
        a.name = name;
        return a;
    }

    private static SvipeArtistFavourite sample() {
        SvipeMusic.Artist a = artist(77, "ozoda");
        a.displayName = "Ozoda Nursaidova";
        a.photoUrl = "https://e-cdn.example/artist/77/xl.jpg";
        a.songCount = 42;
        a.artChannelId = 1001234L;
        a.artMessageId = 99;
        SvipeArtistFavourite f = SvipeArtistFavourite.of(a);
        f.addedAt = 1_700_000_000_000L;
        return f;
    }

    @Test
    public void ofMapsEveryFieldOfTheArtist() {
        SvipeArtistFavourite f = sample();
        assertEquals(77L, f.artistId);
        assertEquals("ozoda", f.name);
        assertEquals("Ozoda Nursaidova", f.displayName);
        assertEquals("https://e-cdn.example/artist/77/xl.jpg", f.photoUrl);
        assertEquals(42, f.songCount);
        assertEquals(1001234L, f.artChannelId);
        assertEquals(99, f.artMessageId);
    }

    @Test
    public void ofNullArtistIsNullNotACrash() {
        assertNull(SvipeArtistFavourite.of(null));
    }

    @Test
    public void roundTripKeepsEveryField() {
        SvipeArtistFavourite in = sample();
        SvipeArtistFavourite out = SvipeArtistFavourite.fromJson(in.toJson());
        assertNotNull(out);
        assertEquals(in.artistId, out.artistId);
        assertEquals(in.name, out.name);
        assertEquals(in.displayName, out.displayName);
        assertEquals(in.photoUrl, out.photoUrl);
        assertEquals(in.songCount, out.songCount);
        assertEquals(in.artChannelId, out.artChannelId);
        assertEquals(in.artMessageId, out.artMessageId);
        assertEquals(in.addedAt, out.addedAt);
    }

    @Test
    public void shownNameFallsBackToTheCanonicalName() {
        // Enrichment is optional; an un-enriched artist must still render its tag-derived name rather
        // than an empty row.
        SvipeArtistFavourite f = SvipeArtistFavourite.of(artist(1, "yulduz"));
        assertEquals("yulduz", f.shownName());
        f.displayName = "";
        assertEquals("yulduz", f.shownName());
        f.displayName = "Yulduz Usmonova";
        assertEquals("Yulduz Usmonova", f.shownName());
    }

    @Test
    public void listRoundTripPreservesOrder() {
        List<SvipeArtistFavourite> in = Arrays.asList(
                SvipeArtistFavourite.of(artist(1, "a")),
                SvipeArtistFavourite.of(artist(2, "b")),
                SvipeArtistFavourite.of(artist(3, "c")));
        List<SvipeArtistFavourite> out = SvipeArtistFavourite.deserialize(SvipeArtistFavourite.serialize(in));
        assertEquals(3, out.size());
        assertEquals(1L, out.get(0).artistId);
        assertEquals(2L, out.get(1).artistId);
        assertEquals(3L, out.get(2).artistId);
    }

    @Test
    public void nullOptionalsSurviveTheRoundTripAsNull() {
        SvipeArtistFavourite f = SvipeArtistFavourite.of(artist(5, "solo"));
        List<SvipeArtistFavourite> out = SvipeArtistFavourite.deserialize(
                SvipeArtistFavourite.serialize(Collections.singletonList(f)));
        assertEquals(1, out.size());
        assertNull(out.get(0).displayName);
        assertNull(out.get(0).photoUrl);
        assertEquals("solo", out.get(0).shownName());
    }

    @Test
    public void corruptBlobYieldsEmptyListNotACrash() {
        assertTrue(SvipeArtistFavourite.deserialize(null).isEmpty());
        assertTrue(SvipeArtistFavourite.deserialize("").isEmpty());
        assertTrue(SvipeArtistFavourite.deserialize("not json at all").isEmpty());
        assertTrue(SvipeArtistFavourite.deserialize("{").isEmpty());
        assertTrue(SvipeArtistFavourite.deserialize("[1,2,3]").isEmpty());      // array, not {v,items}
        assertTrue(SvipeArtistFavourite.deserialize("{\"v\":1}").isEmpty());     // no items
    }

    @Test
    public void entriesWithoutAUsableIdAreDropped() {
        // Without an id there is nothing to sync, render or un-favourite — a phantom row would be a
        // dead tap forever.
        assertNull(SvipeArtistFavourite.fromJson(null));
        assertNull(SvipeArtistFavourite.fromJson(new JSONObject()));
        assertTrue(SvipeArtistFavourite.deserialize("{\"v\":1,\"items\":[{\"artist_id\":0}]}").isEmpty());
        assertTrue(SvipeArtistFavourite.deserialize("{\"v\":1,\"items\":[{\"artist_id\":-3}]}").isEmpty());
    }

    @Test
    public void oneBadEntryDoesNotLoseTheGoodOnes() {
        String blob = "{\"v\":1,\"items\":[{\"artist_id\":1},{\"nope\":true},{\"artist_id\":2}]}";
        List<SvipeArtistFavourite> out = SvipeArtistFavourite.deserialize(blob);
        assertEquals(2, out.size());
        assertEquals(1L, out.get(0).artistId);
        assertEquals(2L, out.get(1).artistId);
    }

    @Test
    public void nullsAndIdlessEntriesAreSkippedOnSerialize() {
        ArrayList<SvipeArtistFavourite> in = new ArrayList<>();
        in.add(SvipeArtistFavourite.of(artist(1, "keep")));
        in.add(null);
        in.add(SvipeArtistFavourite.of(artist(0, "no id")));
        assertEquals(1, SvipeArtistFavourite.deserialize(SvipeArtistFavourite.serialize(in)).size());
    }
}
