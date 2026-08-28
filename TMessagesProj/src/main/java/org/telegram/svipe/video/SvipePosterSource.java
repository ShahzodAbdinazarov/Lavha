package org.telegram.svipe.video;

import android.graphics.drawable.Drawable;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.ui.Components.BackupImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The frame a card draws, fetched by the phone, one channel page at a time.
 *
 * <p>This is the client-side replacement for the backend's {@code app/content/posters.py}. That
 * module scraped {@code t.me/s/<channel>} on a worker, copied every frame it found into R2 under a
 * {@code vp/} prefix and served a presigned URL, because a {@code cdn*.telesco.pe} link carries a
 * short-lived token and a server cannot hand out a link that dies in an hour. Reading the same page
 * here removes the round trip and the bucket both: the device is the only thing that ever needed the
 * picture, and it can keep the bytes itself ({@link SvipeWebImage}).
 *
 * <h3>One page, twenty posters — the request budget</h3>
 *
 * <p>A grid binds fifteen cards at once and the naive shape of this would be fifteen page fetches of
 * 130 KB each, mostly of the SAME page. So nothing here fetches per card. A card <i>registers
 * demand</i> ({@link #request}), demand is collected per channel for {@link #COALESCE_MS}, and then
 * ONE {@code https://t.me/s/<handle>?before=<n>} fetch answers every post in its window at once —
 * measured 2026-08-28 on {@code t.me/s/durov?before=520}: 19 posts, 130 KB, one request.
 *
 * <p>What is genuinely per-card is the poster image itself: 5-16 KB over the same kept-alive
 * connection, and only for posts something actually asked for — never the other nineteen on the page.
 * That is irreducible; a picture has to be transferred to be shown. The page fetch is the part that
 * would have been wasteful, and it is the part that is shared.
 *
 * <p>At most {@link #MAX_PAGES} pages are walked backwards per round, the same ceiling the backend
 * used, so a card scrolled to from far up a channel's history costs a bounded number of fetches and
 * then gives up until it is asked for again.
 *
 * <h3>What counts as "no poster"</h3>
 *
 * <p>A post inside a page's window that the page did not give a video thumb is a definitive miss, not
 * a retry: it is a photo post, a document card, a deleted post, or a channel with previews off. Those
 * do not become videos later. A post BELOW the window simply was not on this page and is asked for
 * again with an older {@code before}; a network failure is neither and is forgotten in a minute.
 */
public final class SvipePosterSource {

    private SvipePosterSource() {}

    /** Receives the file holding the frame, or null when this post has none. On the UI thread. */
    public interface Callback {
        void run(File file);
    }

    /** Each post is one {@code data-post="handle/id"} wrapper; the window between two is its markup. */
    private static final Pattern POST = Pattern.compile("data-post=\"[^\"/]+/(\\d+)\"");
    /** The frame is a CSS background on the video wrapper, exactly as the backend's regex had it. */
    private static final Pattern THUMB = Pattern.compile(
            "tgme_widget_message_video_thumb[^>]+background-image:url\\('([^']+)'");

    /**
     * How long demand is collected before a page is fetched.
     *
     * <p>A RecyclerView binds a screenful in a single frame, so almost all of a page's worth of
     * demand arrives inside a few milliseconds; this only has to outlast that. Long enough to
     * collapse a screenful into one fetch, short enough that a card is never visibly waiting on a
     * timer rather than on the network.
     */
    static final long COALESCE_MS = 120;

    /** Pages walked backwards per round, per channel. The backend used the same number. */
    static final int MAX_PAGES = 3;

    /** How long "this post has no frame" is remembered. Stable, so it is worth remembering. */
    private static final long MISS_TTL_MS = 6 * 60 * 60 * 1000L;
    /** A failure is about the radio, not the post. */
    private static final long FAIL_TTL_MS = 60 * 1000L;
    private static final int MISS_ENTRIES = 2048;

    // ---- pure parsing and batching (JVM-testable, no Android) ----

    /** One {@code t.me/s/} page reduced to what a poster needs: which posts, and their frames. */
    public static final class PageData {
        /** Message id -> tokenised CDN url, for the posts on this page that HAVE a video frame. */
        public final Map<Integer, String> thumbs;
        /** The oldest and newest message ids the page rendered at all, 0 when it rendered none. */
        public final int oldest;
        public final int newest;

        PageData(Map<Integer, String> thumbs, int oldest, int newest) {
            this.thumbs = thumbs;
            this.oldest = oldest;
            this.newest = newest;
        }

        public boolean isEmpty() {
            return newest <= 0;
        }
    }

    /**
     * Every post on one page, and the frame of each that has one.
     *
     * <p>Each {@code data-post} marker opens a window that runs to the next one; a frame found inside
     * that window belongs to that post. Scanning the whole page for thumbs and pairing them by order
     * would be wrong the moment one post in the middle is a photo.
     */
    public static PageData parsePage(String html) {
        final Map<Integer, String> thumbs = new LinkedHashMap<>();
        if (html == null || html.isEmpty()) {
            return new PageData(thumbs, 0, 0);
        }
        final List<int[]> marks = new ArrayList<>();   // {messageId, startIndex}
        final Matcher m = POST.matcher(html);
        while (m.find()) {
            try {
                marks.add(new int[]{Integer.parseInt(m.group(1)), m.start()});
            } catch (NumberFormatException ignore) {}
        }
        if (marks.isEmpty()) {
            return new PageData(thumbs, 0, 0);
        }
        int oldest = Integer.MAX_VALUE, newest = 0;
        for (int i = 0; i < marks.size(); i++) {
            final int id = marks.get(i)[0];
            if (id <= 0) continue;
            oldest = Math.min(oldest, id);
            newest = Math.max(newest, id);
            final int from = marks.get(i)[1];
            final int to = i + 1 < marks.size() ? marks.get(i + 1)[1] : html.length();
            final Matcher t = THUMB.matcher(html.substring(from, to));
            if (t.find()) {
                final String url = t.group(1).replace("&amp;", "&");
                if (SvipeChannelAvatar.isAllowed(url)) {
                    thumbs.put(id, url);
                }
            }
        }
        if (newest <= 0) {
            return new PageData(thumbs, 0, 0);
        }
        return new PageData(thumbs, oldest, newest);
    }

    /** The page to ask for first, given what is wanted: the newest wanted post, exclusive. */
    public static int firstBefore(Collection<Integer> wanted) {
        int max = 0;
        for (Integer id : wanted) {
            if (id != null && id > max) max = id;
        }
        return max <= 0 ? 0 : max + 1;
    }

    /** How one page answered the outstanding demand. Every wanted id lands in exactly one list. */
    public static final class Split {
        /** On the page, with a frame. */
        public final List<Integer> hits = new ArrayList<>();
        /** Inside the page's window and given no frame — a definitive "this post has none". */
        public final List<Integer> misses = new ArrayList<>();
        /** Below the window: not on this page, so another page back may still have it. */
        public final List<Integer> older = new ArrayList<>();
    }

    /**
     * Sort the outstanding demand against one page.
     *
     * <p>The window is the whole story. Inside it the page is authoritative — it rendered that post
     * and did not give it a video frame, so the post has none, and asking again would be the exact
     * repetition this whole path exists to stop. Below it the page simply did not reach, and the next
     * one back might. An empty page (a channel that is gone, private, or has previews off) answers
     * nothing and everything falls to {@code older}, where the page budget stops it.
     */
    public static Split split(Collection<Integer> wanted, PageData page) {
        final Split out = new Split();
        for (Integer boxed : wanted) {
            if (boxed == null) continue;
            final int id = boxed;
            if (page.thumbs.containsKey(id)) {
                out.hits.add(id);
            } else if (page.isEmpty() || id < page.oldest) {
                out.older.add(id);
            } else if (id > page.newest) {
                // Cannot happen with before = max(wanted)+1, but a channel that gained posts between
                // the request and the fetch would land here. Another page will not help; the next
                // round asks for it directly.
                out.older.add(id);
            } else {
                out.misses.add(id);
            }
        }
        return out;
    }

    /** The page url this class reads. {@code before} of 0 means the channel's newest page. */
    public static String pageUrl(String handle, int before) {
        return before > 0 ? "https://t.me/s/" + handle + "?before=" + before
                : "https://t.me/s/" + handle;
    }

    // ---- the device ----

    private static final class Miss {
        boolean transient_;
        long atMs;
    }

    private static final LinkedHashMap<String, Miss> misses =
            new LinkedHashMap<String, Miss>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Miss> eldest) {
                    return size() > MISS_ENTRIES;
                }
            };

    /** Demand for one channel, waiting to be turned into a page fetch. */
    private static final class Batch {
        final String handle;
        final long channelId;
        /** Insertion-ordered so the cards a viewer reached first are the first served. */
        final Set<Integer> wanted = new LinkedHashSet<>();
        final Map<Integer, ArrayList<Callback>> waiters = new HashMap<>();
        boolean running;

        Batch(String handle, long channelId) {
            this.handle = handle;
            this.channelId = channelId;
        }
    }

    private static final HashMap<String, Batch> batches = new HashMap<>();

    /** Which post a view is currently showing, so a late answer never paints a recycled cell. */
    private static final WeakHashMap<View, String> bound = new WeakHashMap<>();

    private static String missKey(long channelId, int messageId) {
        return channelId + "/" + messageId;
    }

    /** The frame we already hold, or null. Never blocks; safe on the UI thread. */
    public static File cached(long channelId, int messageId) {
        return SvipeWebImage.hit(SvipeWebImage.posterFile(channelId, messageId));
    }

    /** True when we KNOW this post has no frame, so a caller can stop asking. */
    public static boolean knownMiss(long channelId, int messageId) {
        synchronized (misses) {
            final Miss m = misses.get(missKey(channelId, messageId));
            return m != null && !m.transient_
                    && System.currentTimeMillis() - m.atMs < MISS_TTL_MS;
        }
    }

    /**
     * Register demand for one post's frame. Cheap, idempotent, and safe to call on every bind.
     *
     * <p>Does NOT fetch a page by itself — demand is pooled per channel for {@link #COALESCE_MS} and
     * a screenful becomes one page fetch. The callback runs on the UI thread, with null when the post
     * has no frame, which is a normal answer.
     */
    public static void request(final String username, final long channelId, final int messageId,
                               final Callback cb) {
        final String handle = SvipeWebImage.normaliseHandle(username);
        if (handle == null || channelId == 0 || messageId <= 0) {
            if (cb != null) AndroidUtilities.runOnUIThread(() -> cb.run(null));
            return;
        }
        final File have = cached(channelId, messageId);
        if (have != null) {
            if (cb != null) AndroidUtilities.runOnUIThread(() -> cb.run(have));
            return;
        }
        synchronized (misses) {
            final Miss m = misses.get(missKey(channelId, messageId));
            if (m != null && System.currentTimeMillis() - m.atMs
                    < (m.transient_ ? FAIL_TTL_MS : MISS_TTL_MS)) {
                if (cb != null) AndroidUtilities.runOnUIThread(() -> cb.run(null));
                return;
            }
        }
        final boolean schedule;
        synchronized (batches) {
            Batch b = batches.get(handle);
            if (b == null) {
                b = new Batch(handle, channelId);
                batches.put(handle, b);
            }
            b.wanted.add(messageId);
            if (cb != null) {
                ArrayList<Callback> list = b.waiters.get(messageId);
                if (list == null) {
                    list = new ArrayList<>(2);
                    b.waiters.put(messageId, list);
                }
                list.add(cb);
            }
            // Only the first arrival arms the timer; everything behind it joins the same page.
            schedule = !b.running;
            b.running = true;
        }
        if (schedule) {
            AndroidUtilities.runOnUIThread(() -> SvipeTme.submit(() -> drainChannel(handle)),
                    COALESCE_MS);
        }
    }

    /** Ask ahead of the viewer and keep the answer. Fire and forget. */
    public static void warm(String username, long channelId, int messageId) {
        request(username, channelId, messageId, null);
    }

    /**
     * Turn one channel's pooled demand into at most {@link #MAX_PAGES} page fetches. On the pool.
     */
    private static void drainChannel(String handle) {
        final Batch batch;
        final Set<Integer> wanted;
        final long channelId;
        synchronized (batches) {
            batch = batches.get(handle);
            if (batch == null) return;
            wanted = new LinkedHashSet<>(batch.wanted);
            channelId = batch.channelId;
            batch.wanted.clear();
        }
        try {
            final Map<Integer, File> got = new HashMap<>();
            final Set<Integer> definiteMiss = new HashSet<>();
            final Set<Integer> transientMiss = new HashSet<>();
            Set<Integer> outstanding = new LinkedHashSet<>(wanted);
            int before = firstBefore(outstanding);

            for (int pageNo = 0; pageNo < MAX_PAGES && !outstanding.isEmpty(); pageNo++) {
                final SvipeTme.Page page = SvipeTme.html(pageUrl(handle, before));
                if (!page.answered) {
                    // The radio, not the channel. Everything left keeps its chance.
                    transientMiss.addAll(outstanding);
                    outstanding = Collections.emptySet();
                    break;
                }
                final PageData data = parsePage(page.body);
                final Split split = split(outstanding, data);
                for (Integer id : split.hits) {
                    // One small GET per frame actually wanted — never for the rest of the page.
                    final File f = SvipeWebImage.store(data.thumbs.get(id),
                            SvipeWebImage.posterFile(channelId, id));
                    if (f != null) {
                        got.put(id, f);
                    } else {
                        transientMiss.add(id);   // a dead token; the next round mints a fresh one
                    }
                }
                definiteMiss.addAll(split.misses);
                if (data.isEmpty() || split.older.isEmpty()) {
                    outstanding = new LinkedHashSet<>(split.older);
                    break;
                }
                outstanding = new LinkedHashSet<>(split.older);
                // Walk backwards: the next page starts where this one ended.
                before = data.oldest;
            }
            // Whatever the page budget could not reach stays unanswered for now, and is asked for
            // again the next time a card of it is bound — as a transient, so it is not tombstoned.
            transientMiss.addAll(outstanding);

            final long now = System.currentTimeMillis();
            synchronized (misses) {
                for (Integer id : definiteMiss) {
                    final Miss m = new Miss();
                    m.transient_ = false;
                    m.atMs = now;
                    misses.put(missKey(channelId, id), m);
                }
                for (Integer id : transientMiss) {
                    final Miss m = new Miss();
                    m.transient_ = true;
                    m.atMs = now;
                    misses.put(missKey(channelId, id), m);
                }
            }
            deliver(handle, wanted, got);
        } catch (Throwable t) {
            FileLog.e(t);
            deliver(handle, wanted, Collections.emptyMap());
        } finally {
            final boolean again;
            synchronized (batches) {
                final Batch b = batches.get(handle);
                if (b == null) {
                    again = false;
                } else if (b.wanted.isEmpty()) {
                    b.running = false;
                    if (b.waiters.isEmpty()) batches.remove(handle);
                    again = false;
                } else {
                    // Demand that arrived while this round was in the air. Keep going rather than
                    // wait for another card to re-arm the timer.
                    again = true;
                }
            }
            if (again) {
                SvipeTme.submit(() -> drainChannel(handle));
            }
        }
    }

    private static void deliver(String handle, Set<Integer> answered, Map<Integer, File> got) {
        final Map<Integer, ArrayList<Callback>> toRun = new HashMap<>();
        synchronized (batches) {
            final Batch b = batches.get(handle);
            if (b == null) return;
            for (Integer id : answered) {
                final ArrayList<Callback> list = b.waiters.remove(id);
                if (list != null && !list.isEmpty()) toRun.put(id, list);
            }
        }
        if (toRun.isEmpty()) return;
        AndroidUtilities.runOnUIThread(() -> {
            for (Map.Entry<Integer, ArrayList<Callback>> e : toRun.entrySet()) {
                final File f = got.get(e.getKey());
                for (Callback cb : e.getValue()) {
                    try { cb.run(f); } catch (Exception ex) { FileLog.e(ex); }
                }
            }
        });
    }

    /**
     * Draw this post's frame into {@code view}, keeping {@code thumb} showing until it arrives.
     *
     * <p>The one call a card needs. Safe on a recycled view — a frame that arrives after the cell was
     * rebound for another post is dropped rather than painted onto the wrong card — and safe to call
     * on every bind, since a frame already on disk is applied with no fetch and no flicker.
     *
     * @return true when the frame was already on disk and is showing now
     */
    public static boolean apply(BackupImageView view, String username, long channelId,
                                int messageId, String filter, Drawable thumb) {
        if (view == null) return false;
        final String key = missKey(channelId, messageId);
        synchronized (bound) {
            bound.put(view, key);
        }
        final File have = cached(channelId, messageId);
        if (have != null) {
            set(view, have, filter, thumb);
            return true;
        }
        request(username, channelId, messageId, file -> {
            if (file == null) return;                  // the blur/shimmer already IS the answer
            final String still;
            synchronized (bound) {
                still = bound.get(view);
            }
            if (!key.equals(still)) return;            // the cell moved on; not ours to paint
            set(view, file, filter, thumb);
        });
        return false;
    }

    private static void set(BackupImageView view, File file, String filter, Drawable thumb) {
        try {
            view.setImage(ImageLocation.getForPath(file.getAbsolutePath()), filter, thumb, null);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
