package org.telegram.svipe;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

/**
 * The badge drawn before the name of a channel Svipe has indexed — the Reels tab's own mark, a play
 * triangle in a ring.
 *
 * <p>It replaces the ♪ note, which said something narrower than the badge now means: the note marked
 * channels whose MUSIC we carry, while this marks any channel our index holds video from. Same idea
 * as the tab, so the two read as one thing: this channel is inside the app.
 *
 * <p>Deliberately API-compatible with {@link SvipeCharDrawable} — same {@code colorSupplier} hook and
 * the same intrinsic size — so every call site is a one-word change. The colour supplier matters on
 * the profile header, where the title colour animates as the header expands and a fixed colour would
 * go invisible against a light background.
 */
public class SvipeIndexedBadge extends Drawable {

    public interface ColorSupplier {
        int getColor();
    }

    private final Drawable icon;
    private ColorSupplier colorSupplier;
    private int lastSuppliedColor;

    public SvipeIndexedBadge() {
        Drawable d = ContextCompat.getDrawable(
                ApplicationLoader.applicationContext, R.drawable.svipe_indexed_channel);
        // mutate() so tinting this badge can never bleed into another cell's shared constant state.
        icon = d != null ? d.mutate() : null;
    }

    /** Drive the colour from a live source, e.g. a title whose colour animates. Returns this. */
    public SvipeIndexedBadge colorSupplier(ColorSupplier supplier) {
        this.colorSupplier = supplier;
        return this;
    }

    @Override
    public void draw(Canvas canvas) {
        if (icon == null) {
            return;
        }
        if (colorSupplier != null) {
            int color = colorSupplier.getColor();
            if (color != lastSuppliedColor) {
                lastSuppliedColor = color;
                icon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            }
        }
        Rect b = getBounds();
        icon.setBounds(b);
        icon.draw(canvas);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        if (icon != null) {
            icon.setColorFilter(colorFilter);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        if (icon != null) {
            icon.setAlpha(alpha);
        }
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return AndroidUtilities.dp(14);
    }

    @Override
    public int getIntrinsicHeight() {
        return AndroidUtilities.dp(14);
    }
}
