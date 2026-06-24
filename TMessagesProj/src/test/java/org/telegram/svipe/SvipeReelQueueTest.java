package org.telegram.svipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;

public class SvipeReelQueueTest {

    private static SvipeReelQueue.Entry entry(long ch, int msg, long bytes) {
        SvipeReelQueue.Entry e = new SvipeReelQueue.Entry();
        e.channelId = ch;
        e.messageId = msg;
        e.sizeBytes = bytes;
        e.messageB64 = "x";
        return e;
    }

    @Test
    public void dedupAppendMovesExistingKeyToTail() {
        ArrayList<SvipeReelQueue.Entry> list = new ArrayList<>();
        SvipeReelQueue.dedupAppend(list, entry(1, 10, 0));
        SvipeReelQueue.dedupAppend(list, entry(1, 11, 0));
        SvipeReelQueue.dedupAppend(list, entry(1, 10, 0)); // re-enqueue first
        assertEquals(2, list.size());
        assertEquals(11, list.get(0).messageId); // 10 moved to the end
        assertEquals(10, list.get(1).messageId);
    }

    @Test
    public void trimDropsOldestBeyondCountCap() {
        ArrayList<SvipeReelQueue.Entry> list = new ArrayList<>();
        for (int i = 0; i < 5; i++) SvipeReelQueue.dedupAppend(list, entry(1, i, 0));
        SvipeReelQueue.trim(list, 3, Long.MAX_VALUE);
        assertEquals(3, list.size());
        assertEquals(2, list.get(0).messageId); // 0 and 1 dropped from the front
        assertEquals(4, list.get(2).messageId);
    }

    @Test
    public void trimDropsOldestBeyondByteBudgetButKeepsAtLeastOne() {
        ArrayList<SvipeReelQueue.Entry> list = new ArrayList<>();
        list.add(entry(1, 0, 100));
        list.add(entry(1, 1, 100));
        list.add(entry(1, 2, 100));
        SvipeReelQueue.trim(list, 100, 150); // budget fits ~1 entry
        assertEquals(1, list.size());
        assertEquals(2, list.get(0).messageId); // newest survives
    }

    @Test
    public void trimNeverEmptiesAQueueThatOverflowsBytesWithOneHugeItem() {
        ArrayList<SvipeReelQueue.Entry> list = new ArrayList<>();
        list.add(entry(1, 0, 9999)); // single item bigger than budget
        SvipeReelQueue.trim(list, 100, 100);
        assertTrue(list.size() >= 1); // keep at least one so playback still has something
    }

    @Test
    public void indexOfKeyMatchesChannelAndMessage() {
        ArrayList<SvipeReelQueue.Entry> list = new ArrayList<>();
        list.add(entry(1, 10, 0));
        list.add(entry(2, 10, 0)); // same message id, different channel
        assertEquals(0, SvipeReelQueue.indexOfKey(list, 1, 10));
        assertEquals(1, SvipeReelQueue.indexOfKey(list, 2, 10));
        assertEquals(-1, SvipeReelQueue.indexOfKey(list, 3, 10));
    }
}
