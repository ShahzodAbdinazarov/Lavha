package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.svipe.video.SvipeWebImage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The cache key, which is the whole answer to the expiring-CDN-token problem, and the eviction plan.
 *
 * <p>A {@code cdn*.telesco.pe} URL carries a token that dies in hours. That single fact is why the
 * backend copied every poster into R2 and served a stable address of its own. On the device the
 * answer is different and cheaper: the tokenised URL is used once and thrown away, and the bytes are
 * filed under a name derived ONLY from what the picture is of. If anything URL-derived ever leaks
 * into that name, every expiry becomes a cache miss and a re-download, and the app is back to paying
 * for the token — so the derivation is asserted here to be a pure function of (channel, message) or
 * (handle), and of nothing else.
 */
public class SvipeWebImageTest {

    // ---- names: stable across every token that ever expired ----

    @Test
    public void aPosterIsNamedForItsPostAndNothingElse() {
        assertEquals("p_1001570408_139619.jpg", SvipeWebImage.posterName(1001570408L, 139619));
        // Same post, and therefore the same name, no matter which page or token it came from.
        assertEquals(SvipeWebImage.posterName(1001570408L, 139619),
                SvipeWebImage.posterName(1001570408L, 139619));
    }

    @Test
    public void differentPostsNeverShareAName() {
        assertFalse(SvipeWebImage.posterName(1L, 2).equals(SvipeWebImage.posterName(1L, 3)));
        assertFalse(SvipeWebImage.posterName(1L, 2).equals(SvipeWebImage.posterName(2L, 2)));
        // The separator has to survive ids that would otherwise run together: 1|23 vs 12|3.
        assertFalse(SvipeWebImage.posterName(1L, 23).equals(SvipeWebImage.posterName(12L, 3)));
    }

    @Test
    public void anAvatarIsNamedForItsHandleCaseFolded() {
        assertEquals("c_durov.jpg", SvipeWebImage.avatarName("durov"));
        assertEquals("c_durov.jpg", SvipeWebImage.avatarName("Durov"));
        assertEquals("c_durov.jpg", SvipeWebImage.avatarName("@DUROV"));
        assertEquals("c_durov.jpg", SvipeWebImage.avatarName("  durov  "));
    }

    // ---- handles: this string becomes both a URL path segment and a filename ----

    @Test
    public void normalisesAHandle() {
        assertEquals("my_channel1", SvipeWebImage.normaliseHandle("@My_Channel1"));
    }

    @Test
    public void refusesAnythingThatIsNotAHandle() {
        assertNull(SvipeWebImage.normaliseHandle(null));
        assertNull(SvipeWebImage.normaliseHandle(""));
        assertNull(SvipeWebImage.normaliseHandle("@"));
        assertNull(SvipeWebImage.normaliseHandle("has space"));
        assertNull("a handle is a path segment; a slash would change the page fetched",
                SvipeWebImage.normaliseHandle("a/b"));
        assertNull("a handle is also a filename; .. would escape the cache directory",
                SvipeWebImage.normaliseHandle(".."));
        assertNull(SvipeWebImage.normaliseHandle("a?b=c"));
        assertNull(SvipeWebImage.normaliseHandle(new String(new char[65]).replace((char) 0, 'x')));
    }

    @Test
    public void aRefusedHandleHasNoFileName() {
        assertNull(SvipeWebImage.avatarName("a/b"));
        assertNull(SvipeWebImage.avatarName(null));
    }

    // ---- eviction ----

    private static SvipeWebImage.Entry e(String name, long bytes, long modified) {
        return new SvipeWebImage.Entry(name, bytes, modified);
    }

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    @Test
    public void keepsEverythingWhileItFitsAndIsFresh() {
        final List<SvipeWebImage.Entry> files = Arrays.asList(
                e("a", 10, NOW - 1000), e("b", 10, NOW - 2000));
        assertTrue(SvipeWebImage.evictionPlan(files, 1000, 7 * DAY, NOW).isEmpty());
    }

    @Test
    public void dropsWhatIsOlderThanTheAgeLimitEvenWithRoomToSpare() {
        final List<SvipeWebImage.Entry> files = Arrays.asList(
                e("fresh", 10, NOW - DAY), e("stale", 10, NOW - 8 * DAY));
        assertEquals(Arrays.asList("stale"),
                SvipeWebImage.evictionPlan(files, 1_000_000, 7 * DAY, NOW));
    }

    @Test
    public void overBudgetItIsStrictLruOldestTouchedFirst() {
        final List<SvipeWebImage.Entry> files = Arrays.asList(
                e("newest", 100, NOW - 1000),
                e("oldest", 100, NOW - 3000),
                e("middle", 100, NOW - 2000));
        // Budget fits one file; the two least recently touched go, oldest first.
        assertEquals(Arrays.asList("oldest", "middle"),
                SvipeWebImage.evictionPlan(files, 100, 7 * DAY, NOW));
    }

    @Test
    public void stopsAsSoonAsItIsUnderBudget() {
        final List<SvipeWebImage.Entry> files = Arrays.asList(
                e("a", 100, NOW - 1000), e("b", 100, NOW - 2000), e("c", 100, NOW - 3000));
        final List<String> doomed = SvipeWebImage.evictionPlan(files, 250, 7 * DAY, NOW);
        assertEquals("only as many as it takes to fit", Arrays.asList("c"), doomed);
    }

    /** A file touched during this scroll must outlive one downloaded an hour ago. */
    @Test
    public void aFileBeingLookedAtSurvivesAnOlderOne() {
        final List<SvipeWebImage.Entry> files = Arrays.asList(
                e("onScreen", 100, NOW),
                e("longAgo", 100, NOW - 60 * 60 * 1000));
        assertEquals(Arrays.asList("longAgo"),
                SvipeWebImage.evictionPlan(files, 100, 7 * DAY, NOW));
    }

    @Test
    public void survivesAnEmptyOrRaggedDirectory() {
        assertTrue(SvipeWebImage.evictionPlan(new ArrayList<>(), 0, DAY, NOW).isEmpty());
        final List<SvipeWebImage.Entry> ragged = new ArrayList<>();
        ragged.add(null);
        ragged.add(e("a", 10, NOW));
        assertTrue(SvipeWebImage.evictionPlan(ragged, 1000, DAY, NOW).isEmpty());
    }

    /** Age is applied before the budget, so a stale file never holds room a live scroll wants. */
    @Test
    public void ageIsAppliedBeforeTheBudget() {
        final List<SvipeWebImage.Entry> files = Arrays.asList(
                e("stale", 100, NOW - 8 * DAY),
                e("fresh", 100, NOW - 1000));
        // The stale one alone frees enough; the fresh one is not touched.
        assertEquals(Arrays.asList("stale"), SvipeWebImage.evictionPlan(files, 100, 7 * DAY, NOW));
    }
}
