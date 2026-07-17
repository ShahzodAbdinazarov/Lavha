package org.telegram.svipe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Search-history query collapse (the pure part of {@link SvipeSearchLog}): during one search visit the
 * settled queries a user types fold into "the few distinct things they tried" — progressive typing
 * collapses, a different attempt appends, an exact repeat is a no-op. Pure JVM test (no network).
 */
public class SvipeSearchLogTest {

    private static ArrayList<String> fold(String... qs) {
        ArrayList<String> out = new ArrayList<>();
        for (String q : qs) SvipeSearchLog.collapse(out, q);
        return out;
    }

    @Test
    public void progressiveTypingCollapsesToLatest() {
        // "lo" -> "lov" -> "love" is one attempt, kept as the final string.
        assertEquals(Arrays.asList("love"), fold("lo", "lov", "love"));
    }

    @Test
    public void backspaceAlsoCollapses() {
        // typing then deleting stays one attempt (last-prefix relationship).
        assertEquals(Arrays.asList("lov"), fold("love", "lov"));
    }

    @Test
    public void distinctAttemptsAppend() {
        assertEquals(Arrays.asList("love", "ozoda"), fold("lo", "love", "ozoda"));
    }

    @Test
    public void exactRepeatIsNoop() {
        assertEquals(Arrays.asList("love"), fold("love", "love", "love"));
    }

    @Test
    public void blankAndNullIgnored() {
        assertEquals(Arrays.asList("love"), fold(null, "  ", "love", "   "));
    }

    @Test
    public void mixedSession() {
        // A genuine prefix chain collapses to its final form; a different attempt appends and refines.
        assertEquals(Arrays.asList("barcelona", "real madrid"),
                fold("barc", "barcel", "barcelona", "real", "real madrid"));
    }

    @Test
    public void nonPrefixVariantsBothKept() {
        // "barca" and "barcelona" DIVERGE at index 4 (a vs e) -> not a prefix pair -> two attempts.
        assertEquals(Arrays.asList("barca", "barcelona"), fold("barca", "barcelona"));
    }
}
