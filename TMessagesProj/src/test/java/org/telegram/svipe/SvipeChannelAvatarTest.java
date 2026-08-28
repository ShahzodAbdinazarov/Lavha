package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.svipe.video.SvipeChannelAvatar;

/**
 * The channel-picture scrape, pinned against the two markups t.me actually serves.
 *
 * <p>The failure this guards is specific and was measured on 2026-08-28: the plain page
 * {@code https://t.me/durov} carries the real JPEG, while {@code https://t.me/s/durov} renders the
 * same slot as a letter placeholder with {@code data-content} and NO {@code src}. A parser that
 * matched on the class name alone would take the placeholder's markup for a URL and every channel in
 * the app would show a broken picture instead of the letter it is supposed to fall back to. So both
 * the page we read and the requirement of a real {@code src} are asserted here.
 */
public class SvipeChannelAvatarTest {

    /** Verbatim from https://t.me/durov, 2026-08-28 (token truncated). */
    private static final String PLAIN_PAGE =
            "<div class=\"tgme_page_photo\">"
            + "<img class=\"tgme_page_photo_image\" src=\"https://cdn4.telesco.pe/file/ltij8obumq.jpg\">"
            + "</div>";

    /** Verbatim shape from https://t.me/s/durov, 2026-08-28 — the letter placeholder. */
    private static final String S_PAGE_PLACEHOLDER =
            "<i class=\"tgme_page_photo_image bgcolor2\" data-content=\"PD\"></i>";

    /** The wrapped variant some previews use. */
    private static final String WRAPPED_PAGE =
            "<i class=\"tgme_page_photo_image\">"
            + "<img src=\"https://cdn1.telesco.pe/file/abc.jpg\"></i>";

    @Test
    public void readsTheJpegOffThePlainPage() {
        assertEquals("https://cdn4.telesco.pe/file/ltij8obumq.jpg",
                SvipeChannelAvatar.parseAvatarUrl(PLAIN_PAGE));
    }

    @Test
    public void readsTheWrappedVariant() {
        assertEquals("https://cdn1.telesco.pe/file/abc.jpg",
                SvipeChannelAvatar.parseAvatarUrl(WRAPPED_PAGE));
    }

    /** The whole reason this class reads the plain page and not /s/. */
    @Test
    public void theLetterPlaceholderIsNotAPicture() {
        assertNull(SvipeChannelAvatar.parseAvatarUrl(S_PAGE_PLACEHOLDER));
    }

    @Test
    public void aChannelWithNoPictureIsNullNotAnError() {
        assertNull(SvipeChannelAvatar.parseAvatarUrl("<html><body>nothing here</body></html>"));
        assertNull(SvipeChannelAvatar.parseAvatarUrl(""));
        assertNull(SvipeChannelAvatar.parseAvatarUrl(null));
    }

    /** The plain page, never {@code /s/} — see the class docs for what /s/ returns instead. */
    @Test
    public void readsThePlainPage() {
        assertEquals("https://t.me/durov", SvipeChannelAvatar.pageUrl("durov"));
        assertFalse(SvipeChannelAvatar.pageUrl("durov").contains("/s/"));
    }

    @Test
    public void unescapesHtmlEntitiesInTheUrl() {
        assertEquals("https://cdn4.telesco.pe/file/a.jpg?x=1&y=2",
                SvipeChannelAvatar.parseAvatarUrl(
                        "<img class=\"tgme_page_photo_image\" "
                        + "src=\"https://cdn4.telesco.pe/file/a.jpg?x=1&amp;y=2\">"));
    }

    // ---- host allow-list: the page is untrusted input and this URL is about to be fetched ----

    @Test
    public void acceptsTheCdnHostsTelegramActuallyUses() {
        assertTrue(SvipeChannelAvatar.isAllowed("https://cdn4.telesco.pe/file/a.jpg"));
        assertTrue(SvipeChannelAvatar.isAllowed("https://cdn1.telesco.pe/file/a.jpg"));
        assertTrue(SvipeChannelAvatar.isAllowed("https://t.me/i/userpic/320/x.jpg"));
    }

    @Test
    public void refusesEverythingElse() {
        assertFalse(SvipeChannelAvatar.isAllowed("http://cdn4.telesco.pe/file/a.jpg"));  // not https
        assertFalse(SvipeChannelAvatar.isAllowed("https://evil.example.com/a.jpg"));
        assertFalse(SvipeChannelAvatar.isAllowed("https://telesco.pe.evil.com/a.jpg"));
        assertFalse(SvipeChannelAvatar.isAllowed(null));
        assertFalse(SvipeChannelAvatar.isAllowed(""));
    }

    /** {@code https://cdn4.telesco.pe@evil.com/} is evil.com, and the naive check reads it as ours. */
    @Test
    public void refusesUserinfoSmugglingAHost() {
        assertFalse(SvipeChannelAvatar.isAllowed("https://cdn4.telesco.pe@evil.com/a.jpg"));
    }

    /** A page that returned markup, not a URL, must not be fetched as one. */
    @Test
    public void aParsedUrlOnAForeignHostIsTreatedAsNoPicture() {
        assertNull(SvipeChannelAvatar.parseAvatarUrl(
                "<img class=\"tgme_page_photo_image\" src=\"https://evil.example.com/a.jpg\">"));
    }
}
