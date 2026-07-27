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
}
