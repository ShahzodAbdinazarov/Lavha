package org.telegram.svipe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * A2 display overlay (mobile side): {@link SvipeMusic.Song#shownTitle()} / {@link SvipeMusic.Song#shownArtist()}
 * and {@link SvipeMusic.Artist#shownName()} must prefer the backend's Deezer-enriched name when present and
 * fall back to the raw Telegram tag otherwise. Pure POJO logic — no Android framework, so it runs as a
 * plain JVM unit test (UI rendering is verified on-device, per the fork's test constraints).
 */
public class SvipeMusicDisplayTest {

    private static SvipeMusic.Artist artist(String name, String role) {
        SvipeMusic.Artist a = new SvipeMusic.Artist();
        a.name = name;
        a.role = role;
        return a;
    }

    @Test
    public void shownTitlePrefersRealTitle() {
        SvipeMusic.Song s = new SvipeMusic.Song();
        s.title = "yomgir (uzhits.net)";
        s.displayTitle = "Yomg'ir";
        assertEquals("Yomg'ir", s.shownTitle());
    }

    @Test
    public void shownTitleFallsBackToTagWhenNullOrEmpty() {
        SvipeMusic.Song s = new SvipeMusic.Song();
        s.title = "yomgir (uzhits.net)";
        s.displayTitle = null;
        assertEquals("yomgir (uzhits.net)", s.shownTitle());
        s.displayTitle = "";                 // empty is treated as absent, not a blank title
        assertEquals("yomgir (uzhits.net)", s.shownTitle());
    }

    @Test
    public void shownArtistPrefersDisplayArtist() {
        SvipeMusic.Song s = new SvipeMusic.Song();
        s.artists.add(artist("Ozoda", "primary"));
        s.displayArtist = "Ozoda Nursaidova";
        assertEquals("Ozoda Nursaidova", s.shownArtist());
    }

    @Test
    public void shownArtistFallsBackToArtistLine() {
        SvipeMusic.Song s = new SvipeMusic.Song();
        s.artists.add(artist("Jony", "primary"));
        s.artists.add(artist("HammAli", "featured"));
        s.displayArtist = null;
        // The tag-based line still composes the multi-artist string (incl. the feat. join).
        assertEquals("Jony feat. HammAli", s.shownArtist());
        s.displayArtist = "";                // empty -> still fall back
        assertEquals("Jony feat. HammAli", s.shownArtist());
    }

    @Test
    public void artistShownNamePrefersDisplayNameElseName() {
        SvipeMusic.Artist a = artist("ozoda", "primary");
        a.displayName = "Ozoda Nursaidova";
        assertEquals("Ozoda Nursaidova", a.shownName());
        a.displayName = null;
        assertEquals("ozoda", a.shownName());
        a.displayName = "";
        assertEquals("ozoda", a.shownName());
    }
}
