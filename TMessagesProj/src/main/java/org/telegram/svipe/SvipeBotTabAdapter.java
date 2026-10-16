package org.telegram.svipe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * The cards of a channel's bot tab: square tiles in the media grid's columns, or rows when the bot
 * chose a list. Images are ours (see {@link SvipeBotTab}); a card without one shows its title alone.
 */
public class SvipeBotTabAdapter extends RecyclerListView.SelectionAdapter {

    private static final int TYPE_TILE = 0, TYPE_ROW = 1;

    private final Context context;
    private SvipeBotTab.State state;
    private Runnable onNearEnd;

    public SvipeBotTabAdapter(Context context) {
        this.context = context;
    }

    public void setState(SvipeBotTab.State state) {
        this.state = state;
        notifyDataSetChanged();
    }

    /** Called while the last cards are being shown, to fetch the next page. */
    public void setOnNearEnd(Runnable onNearEnd) {
        this.onNearEnd = onNearEnd;
    }

    public SvipeBotTab.State getState() {
        return state;
    }

    public boolean isList() {
        return state != null && state.listLayout;
    }

    public SvipeBotTab.Card getCard(int position) {
        return state != null && position >= 0 && position < state.cards.size() ? state.cards.get(position) : null;
    }

    @Override
    public boolean isEnabled(RecyclerView.ViewHolder holder) {
        return true;
    }

    @Override
    public int getItemViewType(int position) {
        return isList() ? TYPE_ROW : TYPE_TILE;
    }

    @Override
    public int getItemCount() {
        return state != null && state.active ? state.cards.size() : 0;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = viewType == TYPE_ROW ? new RowCell(context) : new TileCell(context);
        view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT));
        return new RecyclerListView.Holder(view);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        SvipeBotTab.Card card = getCard(position);
        if (card == null) {
            return;
        }
        if (onNearEnd != null && state.cards.size() < state.total && position >= getItemCount() - 12) {
            onNearEnd.run();
        }
        if (holder.itemView instanceof RowCell) {
            ((RowCell) holder.itemView).set(card, position < getItemCount() - 1);
        } else {
            ((TileCell) holder.itemView).set(card);
        }
    }

    /** A square grid tile: the image, with the title (and badge) over a shade at the bottom. */
    static class TileCell extends FrameLayout {
        private final BackupImageView image;
        private final TextView title, badge;
        private final Paint shade = new Paint();
        private final RectF rect = new RectF();

        TileCell(Context context) {
            super(context);
            setWillNotDraw(false);
            image = new BackupImageView(context);
            image.getImageReceiver().setFileLoadingPriority(1);
            addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            title = new TextView(context);
            title.setTextColor(0xffffffff);
            title.setTextSize(13);
            title.setTypeface(AndroidUtilities.bold());
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.BOTTOM, 6, 0, 6, 5));
            badge = new TextView(context);
            badge.setTextColor(0xffffffff);
            badge.setTextSize(11);
            badge.setTypeface(AndroidUtilities.bold());
            badge.setPadding(AndroidUtilities.dp(5), AndroidUtilities.dp(1), AndroidUtilities.dp(5), AndroidUtilities.dp(1));
            badge.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(4), 0x99000000));
            addView(badge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT, 5, 5, 5, 0));
        }

        void set(SvipeBotTab.Card card) {
            if (card.image != null) {
                image.setImage(card.image, null, null);
                setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            } else {
                image.setImageDrawable(null);
                setBackgroundColor(Theme.getColor(Theme.key_avatar_backgroundBlue));
            }
            title.setText(card.title);
            badge.setText(card.badge);
            badge.setVisibility(TextUtils.isEmpty(card.badge) ? GONE : VISIBLE);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            // A one-pixel gap on the right and bottom, like the media grid.
            super.onMeasure(MeasureSpec.makeMeasureSpec(width - 1, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(width - 1, MeasureSpec.EXACTLY));
            setMeasuredDimension(width, width);
        }

        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            boolean result = super.drawChild(canvas, child, drawingTime);
            if (child == image) {
                int h = getHeight() - 1, top = h - AndroidUtilities.dp(48);
                shade.setShader(new LinearGradient(0, top, 0, h, 0x00000000, 0x99000000, Shader.TileMode.CLAMP));
                rect.set(0, top, getWidth() - 1, h);
                canvas.drawRect(rect, shade);
            }
            return result;
        }
    }

    /** A list row: a rounded image, title and subtitle, and the badge on the right. */
    static class RowCell extends FrameLayout {
        private final BackupImageView image;
        private final TextView title, subtitle, badge;
        private boolean divider;

        RowCell(Context context) {
            super(context);
            setWillNotDraw(false);
            image = new BackupImageView(context);
            image.setRoundRadius(AndroidUtilities.dp(8));
            addView(image, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(context);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setTextSize(16);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            subtitle = new TextView(context);
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            subtitle.setTextSize(13);
            subtitle.setMaxLines(2);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitle);
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.CENTER_VERTICAL, 74, 8, 84, 8));
            badge = new TextView(context);
            badge.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            badge.setTextSize(14);
            badge.setTypeface(AndroidUtilities.bold());
            badge.setSingleLine(true);
            addView(badge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
        }

        void set(SvipeBotTab.Card card, boolean divider) {
            this.divider = divider;
            if (card.image != null) {
                image.setImage(card.image, null, null);
            } else {
                image.setImageDrawable(null);
            }
            image.setVisibility(card.image != null ? VISIBLE : INVISIBLE);
            title.setText(card.title);
            subtitle.setText(card.subtitle);
            subtitle.setVisibility(TextUtils.isEmpty(card.subtitle) ? GONE : VISIBLE);
            badge.setText(card.badge);
            badge.setVisibility(TextUtils.isEmpty(card.badge) ? GONE : VISIBLE);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(68), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (divider) {
                canvas.drawLine(AndroidUtilities.dp(74), getHeight() - 1, getWidth(), getHeight() - 1, Theme.dividerPaint);
            }
        }
    }
}
