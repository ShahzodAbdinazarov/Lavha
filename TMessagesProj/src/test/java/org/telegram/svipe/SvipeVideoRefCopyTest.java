package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.svipe.video.SvipeRefResolver;

/**
 * What {@link SvipeRefResolver.VideoRef#of} carries over from the reference the server sent.
 *
 * <p>The recommendation id is here for a reason it does not look like it needs one: it used to be the
 * single field the copy left behind, so every event the long-form player posted went out with no
 * {@code recommendation_id} at all and the server could never look up the clip's duplicate keys. It
 * was invisible because three call sites re-attached it by hand afterwards — and the ones that did
 * not were the whole bug. A test is how the copy stops being something callers have to remember.
 */
public class SvipeVideoRefCopyTest {

    private static SvipeDiscover.Item item() {
        SvipeDiscover.Item it = new SvipeDiscover.Item();
        it.channelId = 777L;
        it.messageId = 42;
        it.username = "somechannel";
        it.shareUrl = "https://svipe.uz/abc";
        it.topicId = 9;
        it.recId = "rec-page-1";
        it.playUrl = "https://cdn.example/video.mp4";
        it.width = 1080;
        it.height = 1920;
        it.durationMs = 15_000;
        return it;
    }

    @Test
    public void theRecommendationPageTravelsWithTheReference() {
        SvipeRefResolver.VideoRef ref = SvipeRefResolver.VideoRef.of(item());
        assertEquals("rec-page-1", ref.recId);
    }

    @Test
    public void everyFieldThePlayerAndTheTelemetryNeedIsCopied() {
        SvipeDiscover.Item it = item();
        SvipeRefResolver.VideoRef ref = SvipeRefResolver.VideoRef.of(it);
        assertEquals(it.channelId, ref.channelId);
        assertEquals(it.messageId, ref.messageId);
        assertEquals(it.username, ref.username);
        assertEquals(it.shareUrl, ref.shareUrl);
        assertEquals(it.topicId, ref.topicId);
        assertEquals(it.playUrl, ref.playUrl);
        assertEquals(it.width, ref.width);
        assertEquals(it.height, ref.height);
        assertEquals(it.durationMs, ref.durationMs);
    }

    @Test
    public void aReferenceFromTheUsersOwnChatsStaysMarkedLocal() {
        // The privacy gate the telemetry keys off: nothing about it may ever be posted.
        SvipeDiscover.Item it = item();
        it.local = true;
        it.recId = null;
        SvipeRefResolver.VideoRef ref = SvipeRefResolver.VideoRef.of(it);
        assertTrue(ref.local);
        assertNull(ref.recId);
    }

    @Test
    public void anUnattributedReferenceCopiesAsUnattributed() {
        SvipeDiscover.Item it = item();
        it.recId = null;
        assertNull(SvipeRefResolver.VideoRef.of(it).recId);
    }

    @Test
    public void aCopiedPageIsStillJudgedOnItsAgeBeforeItIsSent() {
        // Copying the id is not the same as being allowed to send it: an id this process never minted
        // (a page restored from a previous run) is refused by the attribution register.
        SvipeRecAttribution.reset();
        SvipeRefResolver.VideoRef ref = SvipeRefResolver.VideoRef.of(item());
        assertNull(SvipeRecAttribution.attributableId(ref.recId, 1_000L));

        SvipeRecAttribution.remember(ref.recId, 1_000L);
        assertEquals("rec-page-1", SvipeRecAttribution.attributableId(ref.recId, 1_000L));
        SvipeRecAttribution.reset();
    }
}
