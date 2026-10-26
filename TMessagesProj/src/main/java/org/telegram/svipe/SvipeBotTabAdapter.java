package org.telegram.svipe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.SharedDocumentCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MediaActionDrawable;
import org.telegram.ui.Components.RecyclerListView;

/**
 * The cards of a bot tab, drawn the way the bot chose (svipe.uz/bots/profile-tabs#layouts): tiles in
 * the media grid's columns ({@code grid}, {@code media}, {@code gifs}, {@code gifts}, tall
 * {@code stories}), or full-width rows ({@code list}, {@code files}, {@code links}, {@code music},
 * {@code voice}, {@code people}). Files use the app's own document cell. Images and audio are ours
 * (see {@link SvipeBotTab}); a card without an image shows its title alone.
 */
public class SvipeBotTabAdapter extends RecyclerListView.SelectionAdapter {

    private static final int TYPE_TILE = 0, TYPE_ROW = 1, TYPE_STORY = 2, TYPE_GIFT = 3, TYPE_FILE = 4,
            TYPE_LINK = 5, TYPE_AUDIO = 6, TYPE_PERSON = 7;

    private final Context context;
    private SvipeBotTab.State state;
    private Runnable onNearEnd;

    public SvipeBotTabAdapter(Context context) {
        this.context = context;
    }

    /** Layouts drawn as full-width rows; the others are tiles in the grid's columns. */
    public static boolean isRowLayout(String layout) {
        switch (layout == null ? "" : layout) {
            case "list":
            case "files":
            case "links":
            case "music":
            case "voice":
            case "people":
                return true;
        }
        return false;
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

    /**
     * A tap on a card that the tab handles itself: a music or voice card plays (or pauses) here, as
     * in the profile's own audio tabs. Returns false when the card should open its link.
     */
    public boolean onCardClick(int position) {
        SvipeBotTab.Card card = getCard(position);
        String layout = state != null ? state.layout : "";
        if (card == null || card.audio == null || !("music".equals(layout) || "voice".equals(layout))) {
            return false;
        }
        Player.toggle(card.audio, this::notifyDataSetChanged);
        notifyDataSetChanged();
        return true;
    }

    /** Stops a card that is playing (the profile closed). */
    public static void stopAudio() {
        Player.stop();
    }

    @Override
    public boolean isEnabled(RecyclerView.ViewHolder holder) {
        return true;
    }

    @Override
    public int getItemViewType(int position) {
        switch (state != null ? state.layout : "grid") {
            case "list": return TYPE_ROW;
            case "stories": return TYPE_STORY;
            case "gifts": return TYPE_GIFT;
            case "files": return TYPE_FILE;
            case "links": return TYPE_LINK;
            case "music":
            case "voice": return TYPE_AUDIO;
            case "people": return TYPE_PERSON;
            default: return TYPE_TILE;
        }
    }

    @Override
    public int getItemCount() {
        return state != null && state.active ? state.cards.size() : 0;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view;
        switch (viewType) {
            case TYPE_ROW: view = new RowCell(context, false); break;
            case TYPE_PERSON: view = new RowCell(context, true); break;
            case TYPE_STORY: view = new TileCell(context, 1.25f); break;
            case TYPE_GIFT: view = new GiftCell(context); break;
            case TYPE_FILE: view = new SharedDocumentCell(context); break;
            case TYPE_LINK: view = new LinkCell(context); break;
            case TYPE_AUDIO: view = new AudioCell(context); break;
            default: view = new TileCell(context, 1f); break;
        }
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
        boolean divider = position < getItemCount() - 1;
        View v = holder.itemView;
        if (v instanceof RowCell) {
            ((RowCell) v).set(card, divider);
        } else if (v instanceof GiftCell) {
            ((GiftCell) v).set(card);
        } else if (v instanceof SharedDocumentCell) {
            String name = card.fileName != null ? card.fileName : card.title;
            String ext = null;
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1 && name.length() - dot <= 5) {
                ext = name.substring(dot + 1);
            }
            StringBuilder value = new StringBuilder();
            if (card.size > 0) value.append(AndroidUtilities.formatFileSize(card.size));
            if (!TextUtils.isEmpty(card.subtitle)) {
                if (value.length() > 0) value.append(" · ");
                value.append(card.subtitle);
            }
            ((SharedDocumentCell) v).setTextAndValueAndTypeAndThumb(card.title, value.toString(), ext, card.image, 0, divider);
        } else if (v instanceof LinkCell) {
            ((LinkCell) v).set(card, divider);
        } else if (v instanceof AudioCell) {
            ((AudioCell) v).set(card, "music".equals(state.layout), divider);
        } else if (v instanceof TileCell) {
            // The original grid labels its tiles; media-like layouts show only the picture, as Telegram's do.
            ((TileCell) v).set(card, "grid".equals(state.layout));
        }
    }

    private static String clock(int seconds) {
        return AndroidUtilities.formatShortDuration(Math.max(0, seconds));
    }

    /** A grid tile: the image, a video's length in the corner, and for the original grid the title. */
    static class TileCell extends FrameLayout {
        private final float aspect;
        private final BackupImageView image;
        private final TextView title, badge, duration;
        private final Paint shade = new Paint();
        private final RectF rect = new RectF();
        private boolean shaded;

        TileCell(Context context, float aspect) {
            super(context);
            this.aspect = aspect;
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
            badge = pill(context);
            addView(badge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT, 5, 5, 5, 0));
            duration = pill(context);
            addView(duration, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 5, 5));
        }

        private static TextView pill(Context context) {
            TextView t = new TextView(context);
            t.setTextColor(0xffffffff);
            t.setTextSize(11);
            t.setTypeface(AndroidUtilities.bold());
            t.setPadding(AndroidUtilities.dp(5), AndroidUtilities.dp(1), AndroidUtilities.dp(5), AndroidUtilities.dp(1));
            t.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(4), 0x99000000));
            return t;
        }

        void set(SvipeBotTab.Card card, boolean withTitle) {
            if (card.image != null) {
                image.setImage(card.image, null, null);
                setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            } else {
                image.setImageDrawable(null);
                setBackgroundColor(Theme.getColor(Theme.key_avatar_backgroundBlue));
            }
            boolean showTitle = withTitle || card.image == null;
            title.setText(card.title);
            title.setVisibility(showTitle ? VISIBLE : GONE);
            shaded = showTitle;
            badge.setText(card.badge);
            badge.setVisibility(TextUtils.isEmpty(card.badge) ? GONE : VISIBLE);
            duration.setText(card.isVideo && card.duration > 0 ? clock(card.duration) : null);
            duration.setVisibility(card.isVideo && card.duration > 0 ? VISIBLE : GONE);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = (int) (width * aspect);
            // A one-pixel gap on the right and bottom, like the media grid.
            super.onMeasure(MeasureSpec.makeMeasureSpec(width - 1, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height - 1, MeasureSpec.EXACTLY));
            setMeasuredDimension(width, height);
        }

        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            boolean result = super.drawChild(canvas, child, drawingTime);
            if (child == image && shaded) {
                int h = getHeight() - 1, top = h - AndroidUtilities.dp(48);
                shade.setShader(new LinearGradient(0, top, 0, h, 0x00000000, 0x99000000, Shader.TileMode.CLAMP));
                rect.set(0, top, getWidth() - 1, h);
                canvas.drawRect(rect, shade);
            }
            return result;
        }
    }

    /** A gift: the picture on a rounded card, its price below, and a ribbon in the corner. */
    static class GiftCell extends FrameLayout {
        private final BackupImageView image;
        private final TextView price, ribbon;

        GiftCell(Context context) {
            super(context);
            FrameLayout card = new FrameLayout(context);
            card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_windowBackgroundWhite)));
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 4, 4, 4, 4));
            image = new BackupImageView(context);
            card.addView(image, LayoutHelper.createFrame(72, 72, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 14, 0, 0));
            price = new TextView(context);
            price.setTextSize(12);
            price.setTypeface(AndroidUtilities.bold());
            price.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
            price.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(3), AndroidUtilities.dp(10), AndroidUtilities.dp(3));
            price.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(13), Theme.getColor(Theme.key_featuredStickers_addButton)));
            price.setSingleLine(true);
            card.addView(price, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, 0, 0, 10));
            ribbon = new TextView(context);
            ribbon.setTextSize(9);
            ribbon.setTypeface(AndroidUtilities.bold());
            ribbon.setTextColor(0xffffffff);
            ribbon.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(1), AndroidUtilities.dp(6), AndroidUtilities.dp(1));
            ribbon.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(6), Theme.getColor(Theme.key_text_RedBold)));
            card.addView(ribbon, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.RIGHT, 0, 6, 6, 0));
        }

        void set(SvipeBotTab.Card card) {
            if (card.image != null) {
                image.setImage(card.image, null, null);
            } else {
                image.setImageDrawable(null);
            }
            String label = !TextUtils.isEmpty(card.price) ? card.price : card.title;
            price.setText(label);
            price.setVisibility(TextUtils.isEmpty(label) ? GONE : VISIBLE);
            ribbon.setText(card.ribbon);
            ribbon.setVisibility(TextUtils.isEmpty(card.ribbon) ? GONE : VISIBLE);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY));
        }
    }

    /** A list row: a rounded image (round for people), title and subtitle, and the badge on the right. */
    static class RowCell extends FrameLayout {
        private final BackupImageView image;
        private final TextView title, subtitle, badge;
        private final boolean person;
        private boolean divider;

        RowCell(Context context, boolean person) {
            super(context);
            this.person = person;
            setWillNotDraw(false);
            image = new BackupImageView(context);
            image.setRoundRadius(AndroidUtilities.dp(person ? 23 : 8));
            int size = person ? 46 : 48;
            addView(image, LayoutHelper.createFrame(size, size, Gravity.LEFT | Gravity.CENTER_VERTICAL, person ? 12 : 14, 0, 0, 0));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(context);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setTextSize(16);
            if (person) {
                title.setTypeface(AndroidUtilities.bold());
            }
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            subtitle = new TextView(context);
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            subtitle.setTextSize(person ? 14 : 13);
            subtitle.setMaxLines(person ? 1 : 2);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitle);
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.CENTER_VERTICAL, person ? 70 : 74, 8, 84, 8));
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
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(person ? 58 : 68), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (divider) {
                canvas.drawLine(AndroidUtilities.dp(person ? 70 : 74), getHeight() - 1, getWidth(), getHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    /** A link, like the profile's Links tab: preview on the left, title, text and the address. */
    static class LinkCell extends FrameLayout {
        private final BackupImageView image;
        private final TextView letter, title, description, url;
        private boolean divider;

        LinkCell(Context context) {
            super(context);
            setWillNotDraw(false);
            letter = new TextView(context);
            letter.setTextColor(0xffffffff);
            letter.setTextSize(20);
            letter.setGravity(Gravity.CENTER);
            letter.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(4), Theme.getColor(Theme.key_sharedMedia_linkPlaceholder)));
            addView(letter, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.TOP, 12, 10, 0, 0));
            image = new BackupImageView(context);
            image.setRoundRadius(AndroidUtilities.dp(4));
            addView(image, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.TOP, 12, 10, 0, 0));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(context);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setTextSize(14);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            description = new TextView(context);
            description.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            description.setTextSize(14);
            description.setMaxLines(3);
            description.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(description, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            url = new TextView(context);
            url.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
            url.setTextSize(14);
            url.setSingleLine(true);
            url.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(url, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.TOP, 64, 9, 12, 10));
        }

        void set(SvipeBotTab.Card card, boolean divider) {
            this.divider = divider;
            letter.setText(card.title.isEmpty() ? "" : card.title.substring(0, 1).toUpperCase());
            if (card.image != null) {
                image.setImage(card.image, "40_40", null);
                image.setVisibility(VISIBLE);
            } else {
                image.setImageDrawable(null);
                image.setVisibility(INVISIBLE);
            }
            title.setText(card.title);
            String text = card.description != null ? card.description : card.subtitle;
            description.setText(text);
            description.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);
            url.setText(card.urlText != null ? card.urlText : card.link);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int h = Math.max(getMeasuredHeight(), AndroidUtilities.dp(60));
            setMeasuredDimension(getMeasuredWidth(), h);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (divider) {
                canvas.drawLine(AndroidUtilities.dp(64), getHeight() - 1, getWidth(), getHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    /** Music or a voice message, like the profile's audio tabs: a round play button, title, performer, length. */
    static class AudioCell extends FrameLayout {
        private final BackupImageView cover;
        private final View button;
        private final MediaActionDrawable icon = new MediaActionDrawable();
        private final TextView title, subtitle;
        private boolean divider;

        AudioCell(Context context) {
            super(context);
            setWillNotDraw(false);
            cover = new BackupImageView(context);
            cover.setRoundRadius(AndroidUtilities.dp(22));
            addView(cover, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
            button = new View(context) {
                @Override
                protected void onDraw(Canvas canvas) {
                    int s = AndroidUtilities.dp(44);
                    icon.setBounds(0, 0, s, s);
                    icon.draw(canvas);
                }
            };
            icon.setColor(0xffffffff);
            icon.setBackColor(Theme.getColor(Theme.key_chat_inLoader));
            icon.setIcon(MediaActionDrawable.ICON_PLAY, false);
            addView(button, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(context);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setTextSize(15);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            subtitle = new TextView(context);
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            subtitle.setTextSize(13);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.CENTER_VERTICAL, 70, 0, 16, 0));
        }

        void set(SvipeBotTab.Card card, boolean music, boolean divider) {
            this.divider = divider;
            // Music shows its cover behind the button; a voice message only the button.
            if (music && card.image != null) {
                cover.setImage(card.image, null, null);
                cover.setVisibility(VISIBLE);
                icon.setBackColor(0x66000000);
            } else {
                cover.setImageDrawable(null);
                cover.setVisibility(INVISIBLE);
                icon.setBackColor(Theme.getColor(Theme.key_chat_inLoader));
            }
            boolean playing = card.audio != null && Player.isPlaying(card.audio);
            icon.setIcon(playing ? MediaActionDrawable.ICON_PAUSE : MediaActionDrawable.ICON_PLAY, false);
            button.setAlpha(card.audio != null ? 1f : 0.5f);
            button.invalidate();
            title.setText(card.title);
            StringBuilder line = new StringBuilder();
            String who = card.performer != null ? card.performer : card.subtitle;
            if (!TextUtils.isEmpty(who)) line.append(who);
            if (card.duration > 0) {
                if (line.length() > 0) line.append(" · ");
                line.append(clock(card.duration));
            }
            subtitle.setText(line);
            subtitle.setVisibility(line.length() == 0 ? GONE : VISIBLE);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(56), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (divider) {
                canvas.drawLine(AndroidUtilities.dp(70), getHeight() - 1, getWidth(), getHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    /** One card's audio at a time, streamed from our server. */
    static final class Player {
        private static MediaPlayer player;
        private static String current;

        static boolean isPlaying(String url) {
            return url.equals(current) && player != null;
        }

        static void toggle(String url, Runnable changed) {
            if (url.equals(current)) {
                stop();
                return;
            }
            stop();
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                mp.setDataSource(url);
                mp.setOnPreparedListener(MediaPlayer::start);
                mp.setOnCompletionListener(p -> {
                    stop();
                    AndroidUtilities.runOnUIThread(changed);
                });
                mp.setOnErrorListener((p, what, extra) -> {
                    stop();
                    AndroidUtilities.runOnUIThread(changed);
                    return true;
                });
                mp.prepareAsync();
                player = mp;
                current = url;
            } catch (Exception e) {
                FileLog.e(e);
                stop();
            }
        }

        static void stop() {
            if (player != null) {
                try {
                    player.release();
                } catch (Exception ignore) {
                }
            }
            player = null;
            current = null;
        }
    }
}
