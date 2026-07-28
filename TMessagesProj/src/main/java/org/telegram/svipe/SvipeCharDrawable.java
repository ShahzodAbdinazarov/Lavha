package org.telegram.svipe;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

import org.telegram.messenger.AndroidUtilities;

/**
 * Draws a single glyph (the ♪ music note, U+266A) as a Drawable, scaled to fill its bounds' height
 * and centred. Used as the music-indexed channel badge so we can render the actual symbol instead of
 * a vector path. Honours {@link #setColorFilter} (a PorterDuff SRC_IN filter tints the glyph), so it
 * is a drop-in for the previous VectorDrawable badge at every call site.
 */
public class SvipeCharDrawable extends Drawable {

    public static final String NOTE = "\u266A"; // eighth note

    /** Supplies the glyph colour live at draw time — e.g. to track an animating title colour. */
    public interface ColorSupplier {
        int getColor();
    }

    private final String glyph;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect measure = new Rect();
    private ColorSupplier colorSupplier;

    public SvipeCharDrawable() {
        this(NOTE);
    }

    public SvipeCharDrawable(String glyph) {
        this.glyph = glyph;
        paint.setColor(0xFFFFFFFF);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
    }

    /** Drive the glyph colour from a live source (e.g. a SimpleTextView's current text colour), so the
     *  badge follows an animating/theme-dependent title instead of a fixed colour. Returns this. */
    public SvipeCharDrawable colorSupplier(ColorSupplier supplier) {
        this.colorSupplier = supplier;
        return this;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.height() <= 0 || glyph == null || glyph.isEmpty()) {
            return;
        }
        if (colorSupplier != null) {
            paint.setColorFilter(null);
            paint.setColor(colorSupplier.getColor());
        }
        // Two-pass fit: size the glyph so its ink box exactly fills the bounds height.
        paint.setTextSize(b.height());
        paint.getTextBounds(glyph, 0, glyph.length(), measure);
        if (measure.height() > 0) {
            paint.setTextSize(b.height() * (float) b.height() / measure.height());
            paint.getTextBounds(glyph, 0, glyph.length(), measure);
        }
        float y = b.exactCenterY() - (measure.top + measure.bottom) / 2f;
        canvas.drawText(glyph, b.exactCenterX(), y, paint);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
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
