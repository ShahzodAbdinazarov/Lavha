package org.telegram.svipe;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ImageLoader;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The blurred stand-in a card can draw before anything has been fetched.
 *
 * <p>Telegram attaches a {@code photoStrippedSize} to every media document: two hundred-odd bytes of
 * a 50x50 JPEG that travel INSIDE the message, so nobody ever downloads them. Our backend keeps that
 * blob per reference (clients report it — see {@code SvipeObserved}) and hands it to the client with
 * the list itself, which means a grid can be fully painted from one HTTP response: no
 * resolveUsername, no getMessages, no file request, and no image of ours stored anywhere — the blur
 * IS Telegram's own placeholder, not a copy of the video.
 *
 * <p>The sharp thumbnail still arrives the ordinary way, over MTProto, and replaces this when it
 * does. What this removes is the empty grey rectangle in between.
 */
public final class SvipeThumb {

    private SvipeThumb() {
    }

    /** Decoded blurs, keyed by the base64 itself. Small and bounded — these are 50x50 bitmaps. */
    private static final LinkedHashMap<String, Drawable> cache =
            new LinkedHashMap<String, Drawable>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Drawable> eldest) {
                    return size() > 200;
                }
            };

    /** A drawable for the reference's inline blur, or null when the backend sent none. */
    public static synchronized Drawable of(SvipeDiscover.Item item) {
        final String b64 = item == null ? null : item.thumbB64;
        if (b64 == null || b64.isEmpty()) {
            return null;
        }
        final Drawable cached = cache.get(b64);
        if (cached != null) {
            return cached;
        }
        try {
            final byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            // "b" is the same filter Telegram's own message cells use for a stripped size, so the
            // blur here looks like the blur everywhere else in the app.
            final Bitmap bitmap = ImageLoader.getStrippedPhotoBitmap(bytes, "b");
            if (bitmap == null) {
                return null;
            }
            final Drawable d = new BitmapDrawable(
                    ApplicationLoader.applicationContext.getResources(), bitmap);
            cache.put(b64, d);
            return d;
        } catch (Exception ignore) {
            return null;
        }
    }
}
