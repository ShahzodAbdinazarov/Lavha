package org.telegram.svipe.video;

import android.graphics.drawable.Drawable;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.ui.Components.BackupImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A public channel's picture, on the device, with no {@code contacts.resolveUsername} behind it.
 *
 * <p>Until now a channel avatar in this app could only come from a {@link org.telegram.tgnet.TLRPC.Chat},
 * and a {@code Chat} could only come from a resolve. That is why the grid and the reels bar draw a
 * coloured letter so often: the picture was never missing, it was merely behind a flood-limited MTProto
 * call that a picture is not worth spending. The plain public page has carried it all along —
 *
 * <pre>{@code <img class="tgme_page_photo_image" src="https://cdn4.telesco.pe/file/….jpg">}</pre>
 *
 * <p>— for the cost of one HTTPS GET with no session, no {@code access_hash} and nothing taken from
 * the flood budget the rest of the app lives on.
 *
 * <p><b>It must be the plain page, not {@code /s/}.</b> Measured 2026-08-28 against {@code t.me/durov}:
 * {@code https://t.me/durov} carries the real JPEG above, while {@code https://t.me/s/durov} renders
 * the very same slot as a letter placeholder — {@code class="tgme_page_photo_image bgcolor2"
 * data-content="PD"} with no {@code src} at all. Scraping {@code /s/} for an avatar returns nothing,
 * every time, and looks exactly like a channel that has no picture. The two pages are read for two
 * different things: this one for the avatar, {@code /s/} for {@link SvipePosterSource}'s frames.
 *
 * <p>The tokenised CDN URL is never stored and never handed to a view — see {@link SvipeWebImage} for
 * why, and for what is stored instead.
 */
public final class SvipeChannelAvatar {

    private SvipeChannelAvatar() {}

    /** Receives the file holding the avatar, or null when this channel has none we can read. */
    public interface Callback {
        void run(File file);
    }

    /**
     * Two markups for one picture. The plain page writes the {@code <img>} directly; some previews
     * wrap it in an {@code <i>}. Both REQUIRE a {@code src}, which is also what makes them immune to
     * the letter placeholder: that variant carries {@code data-content} and no source at all, and a
     * pattern that demanded only the class name would match it and return the page's own HTML.
     */
    private static final Pattern[] PHOTO = {
            Pattern.compile("<img class=\"tgme_page_photo_image\"[^>]*src=\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("<i class=\"tgme_page_photo_image[^\"]*\"[^>]*>\\s*<img[^>]+src=\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE),
    };

    /** Hosts a channel picture is ever served from. Anything else is not ours to follow. */
    private static final String[] ALLOWED_HOST_SUFFIXES = {
            ".telesco.pe", ".cdn-telegram.org", ".telegram-cdn.org", ".t.me",
    };

    /**
     * How long "this channel has no readable picture" is remembered.
     *
     * <p>Long, because it is a stable fact — a channel without a photo this minute has none this
     * afternoon — and because the alternative is re-reading a 40 KB page for every card of a channel
     * that will never have one. Not permanent, because channels do get pictures.
     */
    private static final long MISS_TTL_MS = 6 * 60 * 60 * 1000L;
    /** A network failure says nothing about the channel, only about the last few seconds of radio. */
    private static final long FAIL_TTL_MS = 60 * 1000L;
    /** Handles in a feed page, several times over. Nothing here is large. */
    private static final int MISS_ENTRIES = 512;

    private static final class Miss {
        boolean transient_;
        long atMs;
    }

    /** Access-ordered so the least recently asked-about handle falls out first. */
    private static final LinkedHashMap<String, Miss> misses =
            new LinkedHashMap<String, Miss>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Miss> eldest) {
                    return size() > MISS_ENTRIES;
                }
            };
    /** Callers waiting on a page already in the air, so one channel is read once however many
     *  cards of it are on screen. */
    private static final HashMap<String, ArrayList<Callback>> pending = new HashMap<>();

    /** Which handle a view is currently showing, so a late answer never paints a recycled cell. */
    private static final WeakHashMap<View, String> bound = new WeakHashMap<>();

    // ---- pure parsing (JVM-testable, no Android) ----

    /**
     * The channel picture's URL from a plain {@code t.me/<handle>} page, or null when it has none.
     *
     * <p>Null covers every ordinary case at once: a channel with no photo, the letter placeholder the
     * {@code /s/} page serves, a 404 body, and markup we do not recognise. None of them is an error.
     */
    public static String parseAvatarUrl(String html) {
        if (html == null || html.isEmpty()) return null;
        for (Pattern p : PHOTO) {
            final Matcher m = p.matcher(html);
            if (m.find()) {
                final String url = unescape(m.group(1));
                if (isAllowed(url)) return url;
            }
        }
        return null;
    }

    /** The page this class reads. Public so a test can pin it — the {@code /s/} form is wrong here. */
    public static String pageUrl(String handle) {
        return "https://t.me/" + handle;
    }

    /** True for an https URL on a host Telegram actually serves pictures from. */
    public static boolean isAllowed(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        int host = "https://".length();
        int end = url.length();
        for (int i = host; i < url.length(); i++) {
            final char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        final String h = url.substring(host, end).toLowerCase(java.util.Locale.US);
        if (h.isEmpty() || h.indexOf('@') >= 0) return false;   // no userinfo smuggling a host
        if (h.equals("t.me")) return true;
        for (String suffix : ALLOWED_HOST_SUFFIXES) {
            if (h.endsWith(suffix)) return true;
        }
        return false;
    }

    private static String unescape(String s) {
        return s == null ? null : s.replace("&amp;", "&");
    }

    // ---- the device ----

    /** The avatar we already hold, or null. Never blocks; safe on the UI thread. */
    public static File cached(String username) {
        return SvipeWebImage.hit(SvipeWebImage.avatarFile(username));
    }

    /** True when we KNOW this channel has no picture, so a caller can stop asking. */
    public static boolean knownMiss(String username) {
        final String handle = SvipeWebImage.normaliseHandle(username);
        if (handle == null) return true;
        synchronized (misses) {
            final Miss m = misses.get(handle);
            return m != null && !m.transient_
                    && System.currentTimeMillis() - m.atMs < MISS_TTL_MS;
        }
    }

    /**
     * Get this channel's avatar, reading its public page if we do not already hold it.
     *
     * <p>The callback runs on the UI thread with null when there is no picture, which is a normal
     * answer and never an error a caller must handle as one. Idempotent and cheap to over-call: a
     * file we hold answers without touching the network, and a second caller arriving while the same
     * page is in the air rides along on that fetch rather than sending its own.
     */
    public static void load(final String username, final Callback cb) {
        final String handle = SvipeWebImage.normaliseHandle(username);
        if (handle == null) {
            if (cb != null) AndroidUtilities.runOnUIThread(() -> cb.run(null));
            return;
        }
        final File have = cached(handle);
        if (have != null) {
            if (cb != null) AndroidUtilities.runOnUIThread(() -> cb.run(have));
            return;
        }
        synchronized (misses) {
            final Miss m = misses.get(handle);
            if (m != null && System.currentTimeMillis() - m.atMs
                    < (m.transient_ ? FAIL_TTL_MS : MISS_TTL_MS)) {
                if (cb != null) AndroidUtilities.runOnUIThread(() -> cb.run(null));
                return;
            }
        }
        synchronized (pending) {
            ArrayList<Callback> waiters = pending.get(handle);
            if (waiters != null) {
                if (cb != null) waiters.add(cb);
                return;
            }
            waiters = new ArrayList<>();
            if (cb != null) waiters.add(cb);
            pending.put(handle, waiters);
        }
        SvipeTme.submit(() -> fetch(handle));
    }

    /** Read ahead of the viewer and keep the answer. Fire and forget. */
    public static void warm(String username) {
        load(username, null);
    }

    private static void fetch(String handle) {
        File out = null;
        boolean failed = false;
        try {
            final SvipeTme.Page page = SvipeTme.html(pageUrl(handle));
            failed = !page.answered;
            final String url = parseAvatarUrl(page.body);
            if (url != null) {
                // Straight from the tokenised URL into a file named after the CHANNEL. From here on
                // the token is irrelevant; nothing above this line is ever kept.
                out = SvipeWebImage.store(url, SvipeWebImage.avatarFile(handle));
                if (out == null) failed = true;   // a dead token or a dropped socket, not a "no photo"
            }
        } catch (Throwable t) {
            FileLog.e(t);
            failed = true;
        }
        if (out == null) {
            final Miss m = new Miss();
            m.transient_ = failed;
            m.atMs = System.currentTimeMillis();
            synchronized (misses) {
                misses.put(handle, m);
            }
        }
        drain(handle, out);
    }

    private static void drain(String handle, File file) {
        final ArrayList<Callback> waiters;
        synchronized (pending) {
            waiters = pending.remove(handle);
        }
        if (waiters == null || waiters.isEmpty()) return;
        AndroidUtilities.runOnUIThread(() -> {
            for (int i = 0; i < waiters.size(); i++) {
                try { waiters.get(i).run(file); } catch (Exception e) { FileLog.e(e); }
            }
        });
    }

    /**
     * Draw this channel's picture into {@code view}, falling back to {@code placeholder} until (or
     * unless) there is one. The one call every call site needs.
     *
     * <p>Safe on a recycled view: the handle is remembered against the view, and an answer that
     * arrives after the cell was rebound for a different channel is dropped instead of painting the
     * wrong face. Safe to call on every bind — a picture already on disk is applied synchronously,
     * with no fetch and no flicker.
     */
    public static void apply(BackupImageView view, String username, Drawable placeholder) {
        if (view == null) return;
        final String handle = SvipeWebImage.normaliseHandle(username);
        synchronized (bound) {
            bound.put(view, handle == null ? "" : handle);
        }
        if (handle == null) {
            if (placeholder != null) view.setImageDrawable(placeholder);
            return;
        }
        final File have = cached(handle);
        if (have != null) {
            set(view, have, placeholder);
            return;
        }
        if (placeholder != null) view.setImageDrawable(placeholder);
        load(handle, file -> {
            if (file == null) return;                     // the placeholder is already the answer
            final String still;
            synchronized (bound) {
                still = bound.get(view);
            }
            if (!handle.equals(still)) return;            // the cell moved on; not ours to paint
            set(view, file, placeholder);
        });
    }

    /**
     * Stop caring about this view: an answer still in the air for it will be dropped.
     *
     * <p>For the call site that decided to paint the avatar some other way — a resolved
     * {@code TLRPC.Chat} whose photo Telegram already has. Without it, a scrape started for the same
     * view one bind ago would land afterwards and paint over the better picture.
     */
    public static void detach(View view) {
        if (view == null) return;
        synchronized (bound) {
            bound.put(view, "");
        }
    }

    private static void set(BackupImageView view, File file, Drawable placeholder) {
        try {
            // A local path: the cache key Telegram derives from it is stable forever, which is the
            // entire point of having downloaded the bytes rather than kept the URL.
            view.setImage(ImageLocation.getForPath(file.getAbsolutePath()), "50_50",
                    placeholder, null);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
