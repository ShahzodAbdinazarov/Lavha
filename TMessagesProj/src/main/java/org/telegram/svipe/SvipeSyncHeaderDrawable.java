package org.telegram.svipe;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AttachableDrawable;
import org.telegram.ui.Components.AvatarDrawable;

/**
 * The header for the message-sync consent dialog: [ your avatar ] → [ Svipe mark ], the exact
 * two-avatar-with-arrow layout Telegram uses for a bot permission request (see
 * {@code BotLocation.BotUserLocationDrawable}). We keep that native look so the ask reads as a
 * familiar "grant access" prompt — only the right side is the Svipe logo instead of a bot, and the
 * badge on your avatar is a message-history glyph instead of a location pin.
 */
public class SvipeSyncHeaderDrawable extends Drawable implements AttachableDrawable {

    private static final int SVIPE_BLUE = 0xFF12A1FC;

    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ImageReceiver userImageReceiver = new ImageReceiver();
    private final Drawable badgeDrawable;
    private final Drawable svipeMark;

    public SvipeSyncHeaderDrawable(Context context, TLRPC.User user) {
        arrowPaint.setColor(0xFFFFFFFF);
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeWidth(dp(2));
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        whitePaint.setColor(0xFFFFFFFF);

        badgeDrawable = context.getResources().getDrawable(R.drawable.outline_message_time_24).mutate();
        badgeDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogTopBackground), PorterDuff.Mode.SRC_IN));

        svipeMark = context.getResources().getDrawable(R.drawable.svipe_icon_monochrome).mutate();
        svipeMark.setColorFilter(new PorterDuffColorFilter(SVIPE_BLUE, PorterDuff.Mode.SRC_IN));

        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(user);
        userImageReceiver.setForUserOrChat(user, avatarDrawable);
        userImageReceiver.setRoundRadius(dp(25));
    }

    @Override
    public void onAttachedToWindow(ImageReceiver parent) {
        userImageReceiver.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow(ImageReceiver parent) {
        userImageReceiver.onDetachedFromWindow();
    }

    @Override
    public void setParent(View view) {
        userImageReceiver.setParentView(view);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect bounds = getBounds();
        ringPaint.setColor(Theme.getColor(Theme.key_dialogTopBackground));

        final float width = dp(50 + 36 + 50);

        // Left: your avatar.
        userImageReceiver.setImageCoords(bounds.centerX() - width / 2f, bounds.centerY() - dp(25), dp(50), dp(50));
        userImageReceiver.draw(canvas);

        // Badge on your avatar (message-history glyph) — mirrors the location pin's placement.
        final float lcx = bounds.centerX() - width / 2f + dp(25 + 16), lcy = bounds.centerY() + dp(16);
        canvas.drawCircle(lcx, lcy, dp(14), ringPaint);
        canvas.drawCircle(lcx, lcy, dp(12), whitePaint);
        badgeDrawable.setBounds((int) (lcx - dp(9)), (int) (lcy - dp(9)), (int) (lcx + dp(9)), (int) (lcy + dp(9)));
        badgeDrawable.draw(canvas);

        // Arrow.
        canvas.drawLine(bounds.centerX() - dp(3.33f), bounds.centerY() - dp(7), bounds.centerX() + dp(3.33f), bounds.centerY(), arrowPaint);
        canvas.drawLine(bounds.centerX() - dp(3.33f), bounds.centerY() + dp(7), bounds.centerX() + dp(3.33f), bounds.centerY(), arrowPaint);

        // Right: the Svipe mark on a white disc (stands out on the blue header).
        final float rcx = bounds.centerX() + width / 2f - dp(25), rcy = bounds.centerY();
        canvas.drawCircle(rcx, rcy, dp(25), whitePaint);
        svipeMark.setBounds((int) (rcx - dp(25)), (int) (rcy - dp(25)), (int) (rcx + dp(25)), (int) (rcy + dp(25)));
        svipeMark.draw(canvas);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }
}
