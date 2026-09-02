package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The rules of the limit ledger — the row that leaves the device.
 *
 * <p>The ledger exists to answer one question about a real person: what has using Svipe cost their
 * Telegram account, and when. Every rule tested here protects one half of that answer.
 */
public class SvipeLimitLogTest {

    /** 2026-09-01 14:25:36 UTC, as epoch millis. */
    private static final long AT = 1_788_272_736_000L;

    private static JSONObject ok(String subject) {
        return SvipeLimitLog.row(SvipeLimitLog.RESOLVE_USERNAME, SvipeLimitLog.REEL_PLAY,
                SvipeLimitLog.OK, 0, null, subject, "reels", AT);
    }

    /**
     * The call time is the point of the whole table. A ledger is uploaded when the app next goes to
     * the background — hours later, sometimes days — so a row that carried no time of its own would
     * put every call at the moment of the upload and make a burst indistinguishable from a trickle.
     */
    @Test
    public void theRowCarriesWhenTheCallHappened() throws Exception {
        assertEquals("2026-09-01T14:25:36Z", ok("somechannel").getString("client_ts"));
    }

    /** Seconds, not minutes: three resolves inside one second is a burst, three across an hour is
     *  normal browsing, and only the second half of the timestamp tells those apart. */
    @Test
    public void theTimeKeepsItsSeconds() throws Exception {
        final String ts = ok("somechannel").getString("client_ts");
        assertFalse("a time rounded to the minute cannot explain a burst", ts.endsWith(":00Z"));
        assertTrue(ts.endsWith("Z"));   // UTC, so two devices in different places are comparable
    }

    /** A 420 is the row that matters. Anything else is an ordinary failure — a handle that no longer
     *  exists, a network blip — and calling that a limit would make the count meaningless. */
    @Test
    public void onlyAFloodCountsAsAFlood() {
        assertEquals(SvipeLimitLog.FLOOD, SvipeLimitLog.outcomeFor("FLOOD_WAIT_13101"));
        assertEquals(13101, SvipeLimitLog.waitFor("FLOOD_WAIT_13101"));
        assertEquals(SvipeLimitLog.FLOOD, SvipeLimitLog.outcomeFor("FLOOD_PREMIUM_WAIT_60"));

        assertEquals(SvipeLimitLog.ERROR, SvipeLimitLog.outcomeFor("USERNAME_NOT_OCCUPIED"));
        assertEquals(0, SvipeLimitLog.waitFor("USERNAME_NOT_OCCUPIED"));
        assertEquals(SvipeLimitLog.ERROR, SvipeLimitLog.outcomeFor(null));
    }

    /** Telegram has answered five-figure waits, and the server refuses a batch carrying more than a
     *  day. Clamping on the device means one absurd row cannot cost the client every other row in
     *  the same upload. */
    @Test
    public void anAbsurdWaitIsClampedRatherThanLosingTheBatch() throws Exception {
        final JSONObject o = SvipeLimitLog.row(SvipeLimitLog.RESOLVE_USERNAME,
                SvipeLimitLog.REEL_PLAY, SvipeLimitLog.FLOOD, 999_999, "FLOOD_WAIT_999999",
                "somechannel", "reels", AT);
        assertEquals(86_400, o.getInt("wait_s"));
    }

    /** No wait means no field, not a zero: "the account waited 0 seconds" and "nothing happened to
     *  the account" are the same sentence, and only one of them is true. */
    @Test
    public void anOrdinaryCallCarriesNoWait() {
        assertFalse(ok("somechannel").has("wait_s"));
    }

    /** A call we refused to make is recorded too. It is the clearest evidence the user is AT a
     *  limit — the video does not play — and counting only what went out would leave the worst
     *  moment as the one moment with no row at all. */
    @Test
    public void aCallOurOwnGateRefusedIsStillARow() throws Exception {
        final JSONObject blocked = SvipeLimitLog.row(SvipeLimitLog.RESOLVE_USERNAME,
                SvipeLimitLog.REEL_PLAY, SvipeLimitLog.BLOCKED, 2222, null, "somechannel",
                "reels", AT);
        assertEquals("blocked", blocked.getString("outcome"));
        assertEquals(2222, blocked.getInt("wait_s"));

        final JSONObject budget = SvipeLimitLog.row(SvipeLimitLog.RESOLVE_USERNAME,
                SvipeLimitLog.GRID_TILE, SvipeLimitLog.BUDGET, 0, null, "somechannel", "search", AT);
        assertEquals("budget", budget.getString("outcome"));
    }

    /** The subject is what the call was ABOUT, and it may only ever be public. A phone lookup passes
     *  none for exactly that reason, and the row must survive without one. */
    @Test
    public void aRowWithNothingPublicToNameIsStillValid() throws Exception {
        final JSONObject o = SvipeLimitLog.row(SvipeLimitLog.RESOLVE_PHONE,
                SvipeLimitLog.PHONE_LOOKUP, SvipeLimitLog.OK, 0, null, null, "numbers", AT);
        assertFalse("a phone number must never become a subject", o.has("subject"));
        assertEquals("contacts.resolvePhone", o.getString("method"));
    }

    /** A reference without a handle still names a channel; "id:123" is addressable by a human
     *  reading the page, an empty cell is not. */
    @Test
    public void aSubjectFallsBackToTheChannelId() {
        assertEquals("somechannel", SvipeLimitLog.subject("somechannel", 777));
        assertEquals("id:777", SvipeLimitLog.subject(null, 777));
        assertEquals("id:777", SvipeLimitLog.subject("", 777));
        assertNull(SvipeLimitLog.subject(null, 0));
    }

    /** Long strings are trimmed here, not rejected there: the server caps these columns, and a batch
     *  refused for one over-long field would take every other call in it down as well. */
    @Test
    public void overlongFieldsAreTrimmedNotDropped() throws Exception {
        final String long65 = new String(new char[65]).replace('\0', 'a');
        final JSONObject o = SvipeLimitLog.row(SvipeLimitLog.RESOLVE_USERNAME,
                SvipeLimitLog.REEL_PLAY, SvipeLimitLog.ERROR, 0, long65, long65, "reels", AT);
        assertEquals(64, o.getString("subject").length());
        assertEquals(64, o.getString("error_text").length());
    }

    /** A row that cannot say what was spent or why is not worth sending; it would only be a number
     *  nobody could act on. */
    @Test
    public void aRowWithoutMethodOrReasonIsNotRecorded() {
        assertNull(SvipeLimitLog.row(null, SvipeLimitLog.REEL_PLAY, SvipeLimitLog.OK, 0, null,
                "x", "reels", AT));
        assertNull(SvipeLimitLog.row(SvipeLimitLog.RESOLVE_USERNAME, "", SvipeLimitLog.OK, 0, null,
                "x", "reels", AT));
    }

    /** An outcome the caller left blank is a successful call, not an unknown one. */
    @Test
    public void aMissingOutcomeMeansItWentThrough() throws Exception {
        assertEquals("ok", SvipeLimitLog.row(SvipeLimitLog.GET_WEB_PAGE, SvipeLimitLog.WEB_REF,
                null, 0, null, "somechannel", "reels", AT).getString("outcome"));
    }
}
