package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.svipe.SvipeMovies;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;

import java.util.Locale;

/**
 * One film card in the Zona-style catalog grid.
 *
 * <p>The "poster" is the Telegram message thumbnail of the film's default copy — Uzbek film channels
 * put the official poster on the cover frame, so this is real artwork and it costs us nothing. It is
 * a LANDSCAPE 16:9 frame, not a portrait poster, because that is the shape a video thumbnail has;
 * pretending otherwise would letterbox every card. Title, year and rating sit under it.
 */
public class SvipeMovieCell extends FrameLayout {

    private final PosterView poster;
    private final TextView titleView;
    private final TextView metaView;
    private SvipeMovies.Movie movie;

    public SvipeMovieCell(Context context) {
        super(context);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        poster = new PosterView(context);
        column.addView(poster, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));

        titleView = new TextView(context);
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = AndroidUtilities.dp(6);
        column.addView(titleView, tlp);

        metaView = new TextView(context);
        metaView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
        metaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        metaView.setSingleLine(true);
        metaView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = AndroidUtilities.dp(2);
        column.addView(metaView, mlp);

        addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4),
                AndroidUtilities.dp(4), AndroidUtilities.dp(10));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int posterWidth = width - getPaddingLeft() - getPaddingRight();
        ViewGroup.LayoutParams lp = poster.getLayoutParams();
        lp.height = Math.max(1, (int) (posterWidth / (16f / 9f)));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public SvipeMovies.Movie getMovie() {
        return movie;
    }

    /**
     * @param mo the resolved Telegram message behind the poster reference, or null while it is still
     *           being fetched — the card renders its placeholder until then and never blocks.
     */
    public void bind(SvipeMovies.Movie movie, MessageObject mo) {
        this.movie = movie;
        if (movie == null) {
            titleView.setText("");
            metaView.setText("");
            poster.setMessage(null, 0);
            return;
        }
        titleView.setText(movie.title);
        metaView.setText(meta(movie));
        poster.setMessage(mo, movie.runtimeS);
    }

    private static String meta(SvipeMovies.Movie m) {
        StringBuilder sb = new StringBuilder();
        if (m.year > 0) {
            sb.append(m.year);
        }
        if (m.rating() > 0) {
            if (sb.length() > 0) sb.append("  •  ");
            sb.append(String.format(Locale.US, "★ %.1f", m.rating()));
        }
        if (sb.length() == 0) {
            sb.append(SvipeMovies.genreLabel(m));
        }
        return sb.toString();
    }

    /**
     * Rounded thumbnail with the runtime badge. A {@link BackupImageView} so the shared
     * {@link SvipeWideVideoCell#bindThumb} loader applies verbatim — one thumbnail path for the feed
     * card, the 3-up tile and this poster means one place where the size/filter choice lives.
     *
     * <p>The badge is drawn with a {@link TextPaint}, NOT a child TextView: a TextView that is never
     * added to a parent has no LayoutParams, and calling setText() on it from onDraw() crashes inside
     * TextView.checkForRelayout(). Drawing text is what a badge needs anyway.
     */
    private static class PosterView extends BackupImageView {
        private final Paint badgeBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint badgeText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private String duration;

        PosterView(Context context) {
            super(context);
            setRoundRadius(AndroidUtilities.dp(8));
            badgeText.setColor(0xFFFFFFFF);
            badgeText.setTextSize(AndroidUtilities.dp(11));
            badgeText.setTypeface(AndroidUtilities.bold());
        }

        void setMessage(MessageObject mo, int runtimeS) {
            duration = runtimeS > 0 ? AndroidUtilities.formatShortDuration(runtimeS) : null;
            SvipeWideVideoCell.bindThumb(this, mo, true);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            rect.set(0, 0, getWidth(), getHeight());
            badgeBg.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
            canvas.drawRoundRect(rect, AndroidUtilities.dp(8), AndroidUtilities.dp(8), badgeBg);
            super.onDraw(canvas);
            if (duration == null) {
                return;
            }
            final float textWidth = badgeText.measureText(duration);
            final float w = textWidth + AndroidUtilities.dp(10);
            final float h = AndroidUtilities.dp(17);
            final float left = getWidth() - w - AndroidUtilities.dp(6);
            final float top = getHeight() - h - AndroidUtilities.dp(6);
            rect.set(left, top, left + w, top + h);
            badgeBg.setColor(0x99000000);
            canvas.drawRoundRect(rect, AndroidUtilities.dp(4), AndroidUtilities.dp(4), badgeBg);
            canvas.drawText(duration, left + AndroidUtilities.dp(5),
                    top + h - AndroidUtilities.dp(5), badgeText);
        }
    }
}
