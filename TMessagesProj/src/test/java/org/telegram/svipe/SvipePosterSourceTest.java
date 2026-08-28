package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.svipe.video.SvipePosterSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The page-batched poster fetch: what one {@code t.me/s/} page answers, and what it does not.
 *
 * <p>Two failures are guarded here.
 *
 * <p><b>One request per card.</b> A grid binds fifteen cards at once. If each one fetched its own
 * 130 KB channel page, a single screenful would be fifteen requests for what is very largely the same
 * page — the exact waste the server-side pipeline was built to avoid and which moving the work to the
 * device would otherwise re-introduce, on somebody's mobile data. {@link SvipePosterSource#split} is
 * what makes one page answer the whole screenful, and it is asserted directly.
 *
 * <p><b>A miss must be a miss, not a retry.</b> A post the page rendered and gave no video frame has
 * none — it is a photo post, a document card, or the channel has previews off — and asking again
 * forever is the repetition the whole path exists to stop. A post BELOW the page's window is a
 * different thing entirely: it simply was not on this page. Confusing the two either grinds t.me
 * forever or gives up on frames that are one page back.
 */
public class SvipePosterSourceTest {

    /** The shape t.me/s/&lt;channel&gt; serves, verbatim in structure (URLs truncated). */
    private static final String PAGE =
            "<div class=\"tgme_widget_message\" data-post=\"durov/100\">"
            + "  <a class=\"tgme_widget_message_video_thumb\" "
            + "style=\"background-image:url('https://cdn4.telesco.pe/file/one.jpg')\"></a>"
            + "</div>"
            + "<div class=\"tgme_widget_message\" data-post=\"durov/101\">"
            + "  <div class=\"tgme_widget_message_photo\"></div>"   // a photo post: no frame
            + "</div>"
            + "<div class=\"tgme_widget_message\" data-post=\"durov/102\">"
            + "  <a class=\"tgme_widget_message_video_thumb\" "
            + "style=\"background-image:url('https://cdn4.telesco.pe/file/three.jpg')\"></a>"
            + "</div>";

    private static Set<Integer> ids(int... values) {
        final Set<Integer> out = new LinkedHashSet<>();
        for (int v : values) out.add(v);
        return out;
    }

    // ---- parsing ----

    @Test
    public void pairsEachFrameWithItsOwnPost() {
        final SvipePosterSource.PageData page = SvipePosterSource.parsePage(PAGE);
        assertEquals(2, page.thumbs.size());
        assertEquals("https://cdn4.telesco.pe/file/one.jpg", page.thumbs.get(100));
        assertEquals("https://cdn4.telesco.pe/file/three.jpg", page.thumbs.get(102));
        // 101 is a photo post and must NOT inherit its neighbour's picture.
        assertFalse(page.thumbs.containsKey(101));
    }

    @Test
    public void reportsTheWindowThePageCovered() {
        final SvipePosterSource.PageData page = SvipePosterSource.parsePage(PAGE);
        assertEquals(100, page.oldest);
        assertEquals(102, page.newest);
        assertFalse(page.isEmpty());
    }

    @Test
    public void aPageWithNoPostsIsEmptyNotAnError() {
        assertTrue(SvipePosterSource.parsePage("<html>gone</html>").isEmpty());
        assertTrue(SvipePosterSource.parsePage("").isEmpty());
        assertTrue(SvipePosterSource.parsePage(null).isEmpty());
    }

    /** The page is untrusted input; a background-image on a foreign host is not ours to fetch. */
    @Test
    public void refusesAFrameOnAForeignHost() {
        final SvipePosterSource.PageData page = SvipePosterSource.parsePage(
                "<div data-post=\"x/5\"><a class=\"tgme_widget_message_video_thumb\" "
                + "style=\"background-image:url('https://evil.example.com/a.jpg')\"></a></div>");
        assertTrue(page.thumbs.isEmpty());
        assertEquals(5, page.newest);   // the post is still on the page; only its picture is refused
    }

    // ---- batching: one page answers a screenful ----

    @Test
    public void onePageAnswersEveryCardInItsWindow() {
        final SvipePosterSource.PageData page = SvipePosterSource.parsePage(PAGE);
        final SvipePosterSource.Split split = SvipePosterSource.split(ids(100, 101, 102), page);
        assertEquals(Arrays.asList(100, 102), split.hits);
        assertEquals(Arrays.asList(101), split.misses);
        assertTrue(split.older.isEmpty());
    }

    /** The first page asked for is the newest wanted post, exclusive — that is what {@code ?before=} means. */
    @Test
    public void asksForThePageAboveTheNewestWantedPost() {
        assertEquals(103, SvipePosterSource.firstBefore(ids(100, 101, 102)));
        assertEquals(0, SvipePosterSource.firstBefore(new LinkedHashSet<Integer>()));
    }

    @Test
    public void buildsTheSlashSPageUrl() {
        assertEquals("https://t.me/s/durov?before=520", SvipePosterSource.pageUrl("durov", 520));
        assertEquals("https://t.me/s/durov", SvipePosterSource.pageUrl("durov", 0));
    }

    // ---- the miss / retry distinction ----

    @Test
    public void aPostThePageRenderedWithoutAFrameIsADefiniteMiss() {
        final SvipePosterSource.Split split =
                SvipePosterSource.split(ids(101), SvipePosterSource.parsePage(PAGE));
        assertEquals(Arrays.asList(101), split.misses);
        assertTrue("a rendered post without a frame must never be retried", split.older.isEmpty());
    }

    @Test
    public void aPostBelowTheWindowIsNotAMissAndIsAskedForAgain() {
        final SvipePosterSource.Split split =
                SvipePosterSource.split(ids(42), SvipePosterSource.parsePage(PAGE));
        assertEquals(Arrays.asList(42), split.older);
        assertTrue("a post this page never reached must not be tombstoned", split.misses.isEmpty());
    }

    @Test
    public void anEmptyPageTombstonesNothing() {
        final SvipePosterSource.PageData empty = SvipePosterSource.parsePage("<html></html>");
        final SvipePosterSource.Split split = SvipePosterSource.split(ids(1, 2, 3), empty);
        assertTrue(split.hits.isEmpty());
        assertTrue("a channel that answered nothing says nothing about any post",
                split.misses.isEmpty());
        assertEquals(Arrays.asList(1, 2, 3), split.older);
    }

    /** Every wanted id lands in exactly one bucket, or a card waits forever on nothing. */
    @Test
    public void everyWantedIdIsAccountedForExactlyOnce() {
        final SvipePosterSource.PageData page = SvipePosterSource.parsePage(PAGE);
        final Set<Integer> wanted = ids(99, 100, 101, 102, 500);
        final SvipePosterSource.Split split = SvipePosterSource.split(wanted, page);
        final List<Integer> all = new ArrayList<>();
        all.addAll(split.hits);
        all.addAll(split.misses);
        all.addAll(split.older);
        assertEquals(wanted.size(), all.size());
        assertEquals(wanted, new LinkedHashSet<>(all));
    }

    /** A post newer than the page must not be tombstoned by a page that never showed it. */
    @Test
    public void aPostAboveTheWindowIsNotAMiss() {
        final SvipePosterSource.Split split =
                SvipePosterSource.split(ids(500), SvipePosterSource.parsePage(PAGE));
        assertTrue(split.misses.isEmpty());
        assertEquals(Arrays.asList(500), split.older);
    }
}
