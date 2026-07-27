package org.telegram.svipe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The pure "is the terms sheet due?" gate. The dialog + persistence are Android-coupled and not unit
 * tested here; only the version comparison that decides whether to show it is.
 */
public class SvipeTermsTest {

    @Test
    public void dueWhenNeverAccepted() {
        assertTrue(SvipeTerms.shouldShow(0, 1));
    }

    @Test
    public void notDueWhenAcceptedCurrentVersion() {
        assertFalse(SvipeTerms.shouldShow(1, 1));
    }

    @Test
    public void dueAgainWhenTermsVersionBumps() {
        assertTrue(SvipeTerms.shouldShow(1, 2));
    }

    @Test
    public void notDueWhenAcceptedNewerThanCurrent() {
        // A downgrade must never re-prompt: accepted version ahead of the build's is still satisfied.
        assertFalse(SvipeTerms.shouldShow(2, 1));
    }
}
