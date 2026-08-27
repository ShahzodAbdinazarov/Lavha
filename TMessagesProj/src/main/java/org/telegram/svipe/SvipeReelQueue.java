package org.telegram.svipe;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted, ordered ready-queue of fully-downloaded (or downloading) reels. Stored as a single
 * JSON blob in the account's main settings (small — a couple dozen entries — so one atomic
 * read/write is simpler and safer than per-key storage, matching {@link SvipeAuth}).
 *
 * Head of the list = play next. Identity is channelId:messageId (a reposted document is a distinct
 * feed item); documentId is only a secondary index for fileLoaded reverse-lookup and file checks.
 *
 * The pure list operations (dedup-append, trim to caps + byte budget) are static and JVM-testable;
 * the prefs/JSON shell around them is the only Android-coupled part.
 */
public class SvipeReelQueue {

    public static class Entry {
        public long channelId;
        public int messageId;
        public String username;
        public String shareUrl;   // owned svipe.uz/<code> preview link, carried so cold-start reels share it too
        public Integer topicId;   // nullable
        public String recId;      // recommendation_id of the page it arrived with
        /**
         * When this client received the page {@link #recId} names (epoch ms), 0 for an entry written
         * before this field existed.
         *
         * <p>The id alone is not enough to attribute an event: the server drops the context behind it
         * after {@code REC_TTL_SECONDS}, and this queue outlives that by days — an entry restored on a
         * cold start used to replay a page id that had been dead since the previous session, which
         * produced no duplicate keys at all. The age travels with the entry so the cold start can tell.
         */
        public long recAtMs;
        public String messageB64; // Base64 of SerializedData(message) — the playable payload
        public long documentId;   // secondary index for file lookups / dedup
        public long sizeBytes;    // document size, for the disk budget
        public boolean downloaded;// true once the full file (cacheType 0) is confirmed present
    }

    private final int account;
    private final ArrayList<Entry> entries = new ArrayList<>();

    public SvipeReelQueue(int account) {
        this.account = account;
        load();
    }

    public synchronized List<Entry> list() {
        return new ArrayList<>(entries);
    }

    /**
     * Rebuild an entry's playable message from the stored blob. The blob format is this class's
     * business, so anything that needs the message (the cold-start restore, the app-start warm-up's
     * "is it really on disk" check) goes through here rather than re-implementing the decode.
     */
    public static org.telegram.messenger.MessageObject messageOf(int account, Entry e) {
        if (e == null || e.messageB64 == null || e.messageB64.isEmpty()) return null;
        try {
            byte[] bytes = android.util.Base64.decode(e.messageB64, android.util.Base64.NO_WRAP);
            org.telegram.tgnet.SerializedData data = new org.telegram.tgnet.SerializedData(bytes);
            int constructor = data.readInt32(false);
            org.telegram.tgnet.TLRPC.Message m =
                    org.telegram.tgnet.TLRPC.Message.TLdeserialize(data, constructor, false);
            if (m == null) return null;
            return new org.telegram.messenger.MessageObject(account, m, false, false);
        } catch (Exception ex) {
            FileLog.e(ex);
            return null;
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long totalBytes() {
        long sum = 0;
        for (Entry e : entries) sum += e.sizeBytes;
        return sum;
    }

    public synchronized boolean contains(long channelId, int messageId) {
        return indexOfKey(entries, channelId, messageId) >= 0;
    }

    public synchronized boolean containsDoc(long documentId) {
        for (Entry e : entries) if (e.documentId == documentId) return true;
        return false;
    }

    /**
     * Append (or refresh in place) an entry, then trim to the count cap + byte budget.
     *
     * <p>Stamps the page age here rather than at the call site: every caller has the recommendation id
     * and none of them has a reason to think about how long it stays usable, so the one place that
     * cannot be forgotten is this one.
     */
    public synchronized void enqueue(Entry e) {
        stampPageAge(e, System.currentTimeMillis());
        dedupAppend(entries, e);
        trim(entries, SvipeQueuePlan.MAX_ENTRIES, SvipeQueuePlan.MAX_QUEUE_BYTES);
    }

    public synchronized void markDownloaded(long channelId, int messageId) {
        int i = indexOfKey(entries, channelId, messageId);
        if (i >= 0) entries.get(i).downloaded = true;
    }

    public synchronized void markDownloadedByDoc(long documentId) {
        for (Entry e : entries) if (e.documentId == documentId) e.downloaded = true;
    }

    public synchronized Entry removeByMessageId(long channelId, int messageId) {
        int i = indexOfKey(entries, channelId, messageId);
        return i >= 0 ? entries.remove(i) : null;
    }

    // ---- pure list operations (JVM-testable) ----

    public static int indexOfKey(List<Entry> list, long channelId, int messageId) {
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            if (e.channelId == channelId && e.messageId == messageId) return i;
        }
        return -1;
    }

    /**
     * Give an entry the age of the recommendation page it came from, if it has none yet.
     *
     * <p>The register knows when the page arrived, which is older than "now" for every reel except the
     * first of a page — and that difference is the whole point: a reel queued at the end of a long
     * page must not read as freshly recommended. {@code nowMs} is the fallback for a page the register
     * has forgotten, and for the ordinary case of an entry with no page at all.
     */
    public static void stampPageAge(Entry e, long nowMs) {
        if (e != null && e.recAtMs <= 0) {
            e.recAtMs = SvipeRecAttribution.mintedAt(e.recId, nowMs);
        }
    }

    /** Remove any existing entry with the same channel:message, then append the new one at the tail. */
    public static void dedupAppend(List<Entry> list, Entry e) {
        int i = indexOfKey(list, e.channelId, e.messageId);
        if (i >= 0) list.remove(i);
        list.add(e);
    }

    /** Drop entries from the FRONT (oldest) until within both the count cap and the byte budget. */
    public static void trim(List<Entry> list, int maxEntries, long maxBytes) {
        int over = SvipeQueuePlan.overflowCount(list.size(), maxEntries);
        // Keep at least one entry (same invariant the byte-budget loop below enforces) so playback
        // always has something — never let a small/zero cap empty the whole queue.
        for (int i = 0; i < over && list.size() > 1; i++) list.remove(0);
        long total = 0;
        for (Entry e : list) total += e.sizeBytes;
        while (list.size() > 1 && total > maxBytes) {
            total -= list.remove(0).sizeBytes;
        }
    }

    // ---- prefs/JSON shell ----

    private SharedPreferences prefs() {
        return MessagesController.getMainSettings(account);
    }

    private void load() {
        entries.clear();
        try {
            String blob = prefs().getString(SvipeConfig.PREF_REEL_QUEUE, null);
            if (blob == null || blob.isEmpty()) return;
            JSONObject root = new JSONObject(blob);
            JSONArray arr = root.optJSONArray("entries");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.channelId = o.optLong("ch");
                e.messageId = o.optInt("msg");
                e.username = o.isNull("u") ? null : o.optString("u", null);
                e.shareUrl = o.isNull("su") ? null : o.optString("su", null);
                e.topicId = o.isNull("t") ? null : o.optInt("t");
                e.recId = o.isNull("rec") ? null : o.optString("rec", null);
                e.recAtMs = o.optLong("rat");
                // Hand the age back to the register, so an event about this reel is attributed only
                // while the server can still answer for the page — and never after a cold start days
                // later. A legacy entry has no stamp, reads as unknown, and goes out unattributed.
                SvipeRecAttribution.remember(e.recId, e.recAtMs);
                e.messageB64 = o.optString("b64", null);
                e.documentId = o.optLong("doc");
                e.sizeBytes = o.optLong("size");
                e.downloaded = o.optBoolean("dl");
                if (e.messageB64 != null && !e.messageB64.isEmpty()) entries.add(e);
            }
        } catch (Exception ex) {
            FileLog.e(ex);
        }
    }

    public synchronized void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("ch", e.channelId);
                o.put("msg", e.messageId);
                if (e.username != null) o.put("u", e.username);
                if (e.shareUrl != null) o.put("su", e.shareUrl);
                if (e.topicId != null) o.put("t", (int) e.topicId);
                if (e.recId != null) o.put("rec", e.recId);
                if (e.recAtMs > 0) o.put("rat", e.recAtMs);
                o.put("b64", e.messageB64);
                o.put("doc", e.documentId);
                o.put("size", e.sizeBytes);
                o.put("dl", e.downloaded);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("v", 1);
            root.put("entries", arr);
            prefs().edit().putString(SvipeConfig.PREF_REEL_QUEUE, root.toString()).apply();
        } catch (Exception ex) {
            FileLog.e(ex);
        }
    }
}
