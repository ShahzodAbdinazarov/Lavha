package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure k-anonymity helpers of the message-sync peer probe: the id hash, the bucket prefix a client
 * reveals, and matching a peer against the hashes the server returned. The networked control plane is
 * Android-coupled and not unit tested here.
 */
public class SvipeMessageSyncTest {

    @Test
    public void userHashIsLowercaseHex64AndDeterministic() {
        String h = SvipeMessageSync.userHash(777);
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]{64}"));
        assertEquals(h, SvipeMessageSync.userHash(777));   // deterministic
    }

    @Test
    public void differentIdsHashDifferently() {
        assertFalse(SvipeMessageSync.userHash(1).equals(SvipeMessageSync.userHash(2)));
    }

    @Test
    public void bucketIsThePrefixOfTheHash() {
        String h = SvipeMessageSync.userHash(12345);
        assertEquals(h.substring(0, 4), SvipeMessageSync.kanonBucket(12345, 4));
        assertEquals(h.substring(0, 4), SvipeMessageSync.kanonBucket(12345, SvipeMessageSync.KANON_PREFIX_LEN));
    }

    @Test
    public void matchesPeerFindsTheHashInTheBucket() {
        long peer = 900_123;
        Set<String> returned = new HashSet<>();
        returned.add(SvipeMessageSync.userHash(peer));
        returned.add(SvipeMessageSync.userHash(555));      // a different user sharing the bucket
        assertTrue(SvipeMessageSync.matchesPeer(peer, returned));
    }

    @Test
    public void matchesPeerRejectsAbsentOrEmpty() {
        long peer = 900_123;
        assertFalse(SvipeMessageSync.matchesPeer(peer, Collections.singleton(SvipeMessageSync.userHash(555))));
        assertFalse(SvipeMessageSync.matchesPeer(peer, new HashSet<>()));
        assertFalse(SvipeMessageSync.matchesPeer(peer, null));
    }

    // ---- merge key (content-addressed, side-invariant) ----

    @Test
    public void mergeKeyIsSideInvariantForTheSameMessage() {
        // Same author, date, text, media on both devices -> identical key (the mid differs, but is not used).
        String ch = SvipeMessageSync.contentHash("see you at 8", 0);
        assertEquals(SvipeMessageSync.mergeKey(1001, 1700, ch),
                SvipeMessageSync.mergeKey(1001, 1700, ch));
        assertTrue(SvipeMessageSync.mergeKey(1001, 1700, ch).matches("[0-9a-f]{64}"));
    }

    @Test
    public void differentTextGivesDifferentKey() {
        String a = SvipeMessageSync.contentHash("alpha", 0);
        String b = SvipeMessageSync.contentHash("beta", 0);
        assertFalse(SvipeMessageSync.mergeKey(1, 5, a).equals(SvipeMessageSync.mergeKey(1, 5, b)));
    }

    @Test
    public void contentHashSeparatesTextFromMediaId() {
        // "a" + media 12 must not collide with "a 12" + no media, etc. — the separator guards this.
        assertFalse(SvipeMessageSync.contentHash("a", 12).equals(SvipeMessageSync.contentHash("a 12", 0)));
        assertFalse(SvipeMessageSync.contentHash("", 12).equals(SvipeMessageSync.contentHash("", 13)));
    }

    @Test
    public void differentAuthorOrDateGivesDifferentKey() {
        String ch = SvipeMessageSync.contentHash("hi", 0);
        assertFalse(SvipeMessageSync.mergeKey(1, 5, ch).equals(SvipeMessageSync.mergeKey(2, 5, ch)));
        assertFalse(SvipeMessageSync.mergeKey(1, 5, ch).equals(SvipeMessageSync.mergeKey(1, 6, ch)));
    }
}
