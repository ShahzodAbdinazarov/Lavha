package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/**
 * The TTL contract with the backend, pinned to the exact wire shape {@code GET /v1/discover} returns.
 *
 * <p>This is here because the failure mode is invisible. If the field name drifts — or is guessed at
 * from an abbreviation in a message — nothing throws: the read finds nothing, the compiled-in default
 * quietly stays, and the client goes on refusing pages the server would still answer for. The bug
 * would look exactly like working code, and would only ever show up as attribution that is lower than
 * it should be. So the name itself is the assertion.
 *
 * <p>The payload below is the real one: server-side {@code DiscoverResponse}
 * (app/schemas/discover.py) with the values prod returns today.
 */
public class SvipeRecTtlWireTest {

    /** What /v1/discover actually answers with, field for field. */
    private static JSONObject discoverResponse() throws Exception {
        return new JSONObject(
                "{\"items\":[{\"channel_id\":1234567890,\"message_id\":42,\"username\":\"achannel\","
                        + "\"topic_id\":null,\"width\":1080,\"height\":1920,\"duration_ms\":15000,"
                        + "\"share_url\":\"https://svipe.uz/abc\",\"play_url\":null,\"poster_url\":null,"
                        + "\"thumb_b64\":null,\"obs\":false}],"
                        + "\"next_offset\":6,\"category\":null,"
                        + "\"recommendation_id\":\"0558298b51c049d1a7a6e644c7bcd757\","
                        + "\"recommendation_ttl_seconds\":432000}");
    }

    @Before
    public void setUp() {
        SvipeRecAttribution.reset();
    }

    @Test
    public void theServersTtlIsReadOffARealDiscoverResponse() throws Exception {
        assertEquals(432000L, SvipeRecAttribution.ttlSecondsIn(discoverResponse()));
        // 5 days, which is what the server moved to after measuring that a one-hour context lost
        // 35.7% of pages.
        assertEquals(5L, 432000L / 86400L);
    }

    @Test
    public void theFieldNameIsExactAndAnAbbreviationIsNotAccepted() throws Exception {
        assertEquals("recommendation_ttl_seconds", SvipeRecAttribution.TTL_FIELD);
        // The near-miss that would silently leave the default in place.
        JSONObject abbreviated = new JSONObject("{\"recommendation_ttl_secs\":432000}");
        assertEquals(0L, SvipeRecAttribution.ttlSecondsIn(abbreviated));
    }

    @Test
    public void aResponseWithNoTtlSaysNothingRatherThanZeroing() throws Exception {
        // /v1/feed carries a recommendation_id but no TTL; an absent field must not clear what a
        // discover response already taught the client.
        JSONObject feed = new JSONObject(
                "{\"recommendation_id\":\"abc\",\"cold_start\":false,\"graduated\":true,"
                        + "\"items\":[],\"page\":0,\"next_cursor\":null}");
        assertEquals(0L, SvipeRecAttribution.ttlSecondsIn(feed));
        assertEquals(0L, SvipeRecAttribution.ttlSecondsIn(null));
        // The server's own default for an empty grid is 0, which means "nothing to say".
        assertEquals(0L, SvipeRecAttribution.ttlSecondsIn(new JSONObject("{\"recommendation_ttl_seconds\":0}")));
        assertEquals(0L, SvipeRecAttribution.ttlSecondsIn(new JSONObject("{\"recommendation_ttl_seconds\":null}")));
    }

    @Test
    public void theFiveDayTtlIsWhatKeepsAnOvernightPageAttributable() throws Exception {
        final String recId = "0558298b51c049d1a7a6e644c7bcd757";
        final long minted = 1_800_000_000_000L;
        final long fourDaysLater = minted + 4L * 86400_000L;

        // With the compiled-in default (1 hour) a four-day-old page is dead...
        SvipeRecAttribution.remember(recId, minted);
        assertNull(SvipeRecAttribution.attributableId(recId, fourDaysLater));

        // ...and with the TTL the server actually states, it is still attributable. This is the whole
        // reason the number is read rather than compiled in: the server changed it, the client did not.
        SvipeRecAttribution.setTtlSeconds(SvipeRecAttribution.ttlSecondsIn(discoverResponse()));
        assertNotNull(SvipeRecAttribution.attributableId(recId, fourDaysLater));
        assertEquals(recId, SvipeRecAttribution.attributableId(recId, fourDaysLater));
    }

    @Test
    public void theClientStillStopsBeforeTheServerDoes() throws Exception {
        // The server keeps its copy an hour longer than it advertises (REC_TTL_GRACE_SECONDS), and
        // the client gives up a minute before what it was told. Both margins point the same way, so
        // an id the client is willing to send is always one the server can still answer for.
        SvipeRecAttribution.setTtlSeconds(432000L);
        final long minted = 1_800_000_000_000L;
        SvipeRecAttribution.remember("rec", minted);
        final long clientGivesUpAt = minted + 432000_000L - SvipeRecAttribution.SAFETY_MARGIN_MS;
        assertNull(SvipeRecAttribution.attributableId("rec", clientGivesUpAt));
        assertNotNull(SvipeRecAttribution.attributableId("rec", clientGivesUpAt - 1));
    }
}
