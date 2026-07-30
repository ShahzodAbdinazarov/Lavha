package org.telegram.svipe.video;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;

/**
 * "Download for offline" as a pill for the watch page's action row: idle → progress ring → done, with
 * a tap on the ring cancelling.
 *
 * <p><b>This is a USER-INITIATED download and is deliberately not gated by the long-form guards.</b>
 * Those guards ({@code SvipeVideoLadder.isLongForm}) exist to stop the reels player pulling a whole
 * 40-minute file after three seconds of accidental dwell, and to keep such a file out of the offline
 * reels cushion. An explicit Download tap is the opposite intent, so nothing here consults them — and
 * nothing here weakens them either.
 *
 * <p>Everything under the hood is Telegram's own, which has had offline downloads far longer than
 * YouTube: {@code putInDownloadsStore} before {@link FileLoader#loadFile} is what routes the file
 * through DownloadController, so it appears in the app's Downloads list and survives a restart, and
 * progress arrives on {@code fileLoadProgressChanged} exactly as it does for a chat attachment.
 *
 * <p><b>Trap:</b> the file to download is the ladder's target RENDITION, not {@code mo.getDocument()}.
 * On a laddered post the top document is an HLS manifest wrapper; downloading it neither plays offline
 * nor matches the file name the progress notifications carry.
 */
public class SvipeDownloadButton extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final int STATE_IDLE = 0;
    private static final int STATE_LOADING = 1;
    private static final int STATE_DONE = 2;

    private final RingView ring;
    private final TextView label;

    private int account;
    private MessageObject message;
    private TLRPC.Document document;
    private String fileName;
    private int state = STATE_IDLE;
    private boolean observing;

    public SvipeDownloadButton(Context context, int account) {
        super(context);
        // Taken in the constructor, not in bind(): the NotificationCenter observers are registered on
        // attach, which for a recycled row happens before the next bind().
        this.account = account;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(14), 0);
        setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(18),
                Theme.getColor(Theme.key_windowBackgroundGray), Theme.getColor(Theme.key_listSelector)));

        ring = new RingView(context);
        addView(ring, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

        label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL));

        setOnClickListener(v -> onTap());
        applyState(STATE_IDLE, 0f);
    }

    /**
     * Point the button at a video. Safe to call on every rebind: it re-reads the on-disk and in-flight
     * state, so a row that scrolled away and back shows the truth rather than a stale label.
     */
    public void bind(MessageObject mo) {
        this.message = mo;
        this.document = targetDocument(account, mo);
        this.fileName = document != null ? FileLoader.getAttachFileName(document) : null;
        if (document == null) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        refreshFromLoader();
    }

    /**
     * The single file this video is stored as: a rendition already on disk if there is one, else the
     * sharpest rung a full screen can use — downloading for offline is the one case where the 720p
     * phone cap is the wrong choice, since the file will be watched fullscreen later. Falls back to the
     * post's own document for the small-channel case that carries no ladder at all.
     */
    public static TLRPC.Document targetDocument(int account, MessageObject mo) {
        if (mo == null) {
            return null;
        }
        final VideoPlayer.VideoUri target =
                SvipeVideoLadder.targetRendition(SvipeVideoLadder.qualitiesFor(account, mo),
                        SvipeVideoLadder.MAX_P_FULLSCREEN);
        return target != null && target.document != null ? target.document : mo.getDocument();
    }

    /**
     * The downloaded file of {@code mo}'s target rendition, or null when it is not on disk. Both
     * locations are probed in the same order {@code VideoPlayer.VideoUri.updateCached} uses: a tracked
     * download lands in the shared media folder, a cache fill in the cache folder, and only the file
     * system knows which happened.
     */
    public static File downloadedFile(int account, MessageObject mo) {
        final TLRPC.Document doc = targetDocument(account, mo);
        if (doc == null) {
            return null;
        }
        try {
            File file = FileLoader.getInstance(account).getPathToAttach(doc, null, false);
            if (file != null && file.exists()) {
                return file;
            }
            file = FileLoader.getInstance(account).getPathToAttach(doc, null, true);
            return file != null && file.exists() ? file : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void refreshFromLoader() {
        if (fileName == null) {
            return;
        }
        if (downloadedFile(account, message) != null) {
            applyState(STATE_DONE, 1f);
            return;
        }
        if (FileLoader.getInstance(account).isLoadingFile(fileName)) {
            applyState(STATE_LOADING, progressFromLoader());
            return;
        }
        applyState(STATE_IDLE, 0f);
    }

    /** Progress read synchronously, so a rebind mid-download does not start the ring back at zero. */
    private float progressFromLoader() {
        final long[] sizes = ImageLoader.getInstance().getFileProgressSizes(fileName);
        if (sizes == null || sizes[1] <= 0) {
            return 0f;
        }
        return Math.min(1f, sizes[0] / (float) sizes[1]);
    }

    private void onTap() {
        if (document == null || message == null) {
            return;
        }
        if (state == STATE_LOADING) {
            FileLoader.getInstance(account).cancelLoadFile(document);
            applyState(STATE_IDLE, 0f);
            return;
        }
        if (state == STATE_DONE) {
            return;   // the pill is the state; "Save to gallery" is the player's ⋮ action
        }
        // putInDownloadsStore MUST be set before loadFile: FileLoader reads it off the parent object to
        // decide whether this is a tracked download (Downloads list + downloading_documents row) or an
        // invisible cache fill.
        message.putInDownloadsStore = true;
        FileLoader.getInstance(account).loadFile(document, message, FileLoader.PRIORITY_NORMAL_UP, 0);
        applyState(STATE_LOADING, progressFromLoader());
    }

    private void applyState(int newState, float progress) {
        state = newState;
        ring.set(newState, progress);
        final int labelRes;
        switch (newState) {
            case STATE_LOADING:
                labelRes = R.string.Downloading;
                break;
            case STATE_DONE:
                labelRes = R.string.SvipeVideoDownloaded;
                break;
            default:
                labelRes = R.string.SvipeVideoDownload;
                break;
        }
        label.setText(LocaleController.getString(labelRes));
        setContentDescription(label.getText());
    }

    // ---------------- FileLoader notifications ----------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!observing) {
            observing = true;
            final NotificationCenter nc = NotificationCenter.getInstance(account);
            nc.addObserver(this, NotificationCenter.fileLoaded);
            nc.addObserver(this, NotificationCenter.fileLoadFailed);
            nc.addObserver(this, NotificationCenter.fileLoadProgressChanged);
        }
        refreshFromLoader();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (observing) {
            observing = false;
            final NotificationCenter nc = NotificationCenter.getInstance(account);
            nc.removeObserver(this, NotificationCenter.fileLoaded);
            nc.removeObserver(this, NotificationCenter.fileLoadFailed);
            nc.removeObserver(this, NotificationCenter.fileLoadProgressChanged);
        }
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (fileName == null || args.length == 0 || !fileName.equals(args[0])) {
            return;
        }
        if (id == NotificationCenter.fileLoaded) {
            applyState(STATE_DONE, 1f);
        } else if (id == NotificationCenter.fileLoadFailed) {
            applyState(STATE_IDLE, 0f);
        } else if (id == NotificationCenter.fileLoadProgressChanged && args.length >= 3) {
            try {
                final long done = (Long) args[1];
                final long total = (Long) args[2];
                applyState(STATE_LOADING, total > 0 ? Math.min(1f, done / (float) total) : 0f);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    /**
     * Idle arrow, a determinate ring around a stop square while loading, a tick when the file is on
     * disk. Hand-drawn because every download indicator in the app is welded into a specific cell:
     * RadialProgress2 needs a parent that feeds it invalidation and colour keys per state.
     */
    private static class RingView extends ImageView {

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stop = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private int state = STATE_IDLE;
        private float progress;

        RingView(Context context) {
            super(context);
            setScaleType(ScaleType.CENTER_INSIDE);
            final int color = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(AndroidUtilities.dp(1.5f));
            track.setColor(Theme.multAlpha(color, .25f));
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(AndroidUtilities.dp(1.5f));
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(color);
            stop.setColor(color);
        }

        void set(int state, float progress) {
            this.state = state;
            this.progress = Math.max(0f, Math.min(1f, progress));
            final int icon = state == STATE_DONE ? R.drawable.msg_check_s
                    : (state == STATE_IDLE ? R.drawable.msg_download : 0);
            if (icon == 0) {
                setImageDrawable(null);
            } else {
                final Drawable d = ContextCompat.getDrawable(getContext(), icon);
                if (d != null) {
                    d.mutate().setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText),
                            PorterDuff.Mode.SRC_IN);
                }
                setImageDrawable(d);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (state == STATE_LOADING) {
                final float inset = AndroidUtilities.dp(1.5f);
                rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
                canvas.drawArc(rect, 0, 360, false, track);
                canvas.drawArc(rect, -90, 360 * progress, false, arc);
                final float half = AndroidUtilities.dp(3);
                final float cx = getWidth() / 2f, cy = getHeight() / 2f;
                rect.set(cx - half, cy - half, cx + half, cy + half);
                canvas.drawRoundRect(rect, AndroidUtilities.dp(1), AndroidUtilities.dp(1), stop);
                return;
            }
            super.onDraw(canvas);
        }
    }
}
