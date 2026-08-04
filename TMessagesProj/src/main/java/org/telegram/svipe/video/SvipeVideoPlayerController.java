package org.telegram.svipe.video;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.View;

import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.analytics.AnalyticsListener;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileStreamLoadOperation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.svipe.SvipeDiscover;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.SvipeWatchActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Process singleton owning the long-form (horizontal, YouTube-style) player: the {@link VideoPlayer},
 * the video being watched, the resume position and — crucially — the player's STATE.
 *
 * {@link #mode} is an explicit field and the single source of truth for what the player is, and
 * {@link #setMode} is its ONLY writer. Nothing may derive the mode from the window aspect, the
 * configuration or the display size: that is exactly how Telegram's PhotoViewer silently drops out of
 * fullscreen when the device is turned back to portrait (it re-computes "am I fullscreen" from
 * {@code parentWidth > parentHeight} on every measure pass, and its onConfigurationChanged is empty).
 * There is deliberately no {@code OrientationEventListener} anywhere in this player: PhotoViewer's is
 * the other half of the same bug, handing the orientation back to the sensor mid-fullscreen.
 *
 * The surface lives in {@link SvipeVideoStage}, an overlay above the whole fragment stack, so a mode
 * change is pure geometry: the player is never rebuilt and playback never restarts.
 */
public class SvipeVideoPlayerController {

    /** Nothing is open; the stage is GONE and swallows no touches. */
    public static final int MODE_CLOSED = 0;
    /** 16:9 over the watch page's pinned placeholder. */
    public static final int MODE_INLINE = 1;
    /** The whole window, letterboxed. Orientation-sticky — a portrait sensor reading never exits. */
    public static final int MODE_FULLSCREEN = 2;
    /** Floating bar above every screen, surviving fragment navigation. */
    public static final int MODE_MINI = 3;

    /** A video within this much of its duration counts as finished, so play restarts it. */
    private static final long END_EPSILON_MS = 250;

    /** How long the "Up next" preview counts down before autoplay advances, YouTube-style. */
    private static final int UP_NEXT_COUNTDOWN_S = 5;

    /** Autoplay is a player-wide preference, not a per-video one. Default ON, as on YouTube. */
    private static final String PREF_AUTOPLAY = "svipe_video_autoplay";

    /** What the chrome, and later the autoplay step, need to know about playback. */
    public interface Observer {
        /** Playback started or stopped — including the Music tab taking the audio away from us. */
        default void onPlayingChanged(boolean playing) {}

        /**
         * The video reached its end. Fired BEFORE autoplay decides whether to advance, so an observer
         * sees the end of every video whether or not another one follows it.
         */
        default void onVideoEnded(SvipeRefResolver.VideoRef ref) {}

        /** A different video is now loaded (an open, or an in-place swap from the related list). */
        default void onVideoChanged(SvipeRefResolver.VideoRef ref) {}

        /** The mode changed. Read {@link #getMode()}; never infer it from the window. */
        default void onModeChanged(int mode) {}
    }

    private static volatile SvipeVideoPlayerController instance;

    public static SvipeVideoPlayerController getInstance() {
        if (instance == null) {
            synchronized (SvipeVideoPlayerController.class) {
                if (instance == null) instance = new SvipeVideoPlayerController();
            }
        }
        return instance;
    }

    private SvipeVideoStage stage;
    private int account = UserConfig.selectedAccount;

    private int mode = MODE_CLOSED;
    private SvipeRefResolver.VideoRef current;
    private VideoPlayer player;
    private boolean firstFrameSeen;

    private final ArrayList<Observer> observers = new ArrayList<>();

    /** The watch page currently showing this video, if any — the one that owns the inline hole. */
    private SvipeWatchActivity watchPage;
    /** What to re-present when the mini bar is dragged back up. Outlives the page. */
    private SvipeDiscover.Item restoreItem;

    /**
     * The activity's orientation request before we asked for landscape, and
     * {@link ActivityInfo#SCREEN_ORIENTATION_UNSPECIFIED} whenever we hold nothing.
     *
     * Resetting it on exit is not tidiness: PhotoViewer captures its equivalent exactly once behind
     * {@code if (prevOrientation == -10)} and never clears it, so if the first fullscreen of the
     * process happens while some other screen holds a portrait lock, that foreign lock becomes the
     * permanent "restore" value for the rest of the process.
     */
    private int savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    /** Where the watch page's placeholder is, in window coordinates. Survives a stage swap. */
    private final Rect inlineRect = new Rect();

    /**
     * Position to resume from when the player has to be rebuilt (a new stage after the activity was
     * recreated, or a reopen of the same video). Playback across mode changes never uses it — the
     * player is not touched by a transition.
     */
    private long resumeMs;

    /**
     * Watch clock + event poster. Owned rather than observed: the leave event is built from the
     * duration and position, so it has to be flushed at one exact point in the teardown — the top of
     * {@link #closePlayback()}, before the player is released.
     */
    private final SvipeVideoTelemetry telemetry = new SvipeVideoTelemetry(new SvipeVideoTelemetry.Source() {
        @Override
        public long positionMs() {
            return getPositionMs();
        }

        @Override
        public long durationMs() {
            return getDurationMs();
        }
    });

    /**
     * Set just before an autoplay advance and consumed by the {@link #open} it triggers — the one way
     * the player can tell the telemetry that IT chose this video, not the user.
     */
    private boolean pendingAutoplay;
    /**
     * Related references already consumed by autoplay. A related tap rebuilds the page's list from the
     * pipe, so without this the same two videos would autoplay into each other forever.
     */
    private final HashSet<String> autoplayedKeys = new HashSet<>();
    /**
     * The related list a watch page last published. Kept because the mini bar outlives the page and
     * still has to know what to play next.
     */
    private final ArrayList<SvipeDiscover.Item> relatedSnapshot = new ArrayList<>();

    /** The candidate the "Up next" countdown is running for, or null when no countdown is up. */
    private SvipeDiscover.Item upNext;
    private int upNextSecondsLeft;

    private final Runnable upNextTick = new Runnable() {
        @Override
        public void run() {
            if (upNext == null) {
                return;
            }
            if (--upNextSecondsLeft <= 0) {
                playUpNextNow();
                return;
            }
            if (stage != null) {
                stage.getControls().showUpNext(upNextMessage(), upNextSecondsLeft, UP_NEXT_COUNTDOWN_S);
            }
            AndroidUtilities.runOnUIThread(this, 1000);
        }
    };

    private SvipeVideoPlayerController() {
        // The overlay outlives every watch page, so it is the overlay that registers, once.
        SvipeWatchActivity.setPlayerHoleListener(holeListener);
    }

    // ---------------- stage binding ----------------

    /** Called by the stage itself when it attaches to the window. */
    public void attachStage(SvipeVideoStage newStage) {
        stage = newStage;
        if (current == null) {
            stage.onModeChanged(MODE_CLOSED, false);
            return;
        }
        // A different stage means a different TextureView, which only happens when the activity was
        // recreated. setTextureView is a once-per-video call (see D2), so the player is rebuilt onto
        // the new surface from the saved position instead of being re-pointed at it.
        stage.setInlineRect(inlineRect);
        stage.onModeChanged(mode, false);
        stage.showCover(current.mo);
        stage.getControls().setVideo(current.mo, current.chat);
        if (current.mo != null) startPlayback();
    }

    public void detachStage(SvipeVideoStage oldStage) {
        if (stage == oldStage) stage = null;
    }

    public SvipeVideoStage getStage() {
        return stage;
    }

    // ---------------- state ----------------

    public int getMode() {
        return mode;
    }

    public boolean isOpen() {
        return mode != MODE_CLOSED && current != null;
    }

    public SvipeRefResolver.VideoRef getCurrent() {
        return current;
    }

    /** The account the open video belongs to — pinned at open, so a later switch cannot mismatch it. */
    public int getAccount() {
        return account;
    }

    /** The live player, or null before the reference resolved. Never cached by callers. */
    public VideoPlayer getPlayer() {
        return player;
    }

    public void addObserver(Observer observer) {
        if (observer != null && !observers.contains(observer)) observers.add(observer);
    }

    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    // ---------------- open ----------------

    /**
     * Play {@code ref} inline over {@code inlineWindowRect}. Reopening the video already playing is a
     * no-op beyond returning it to inline, so a re-tap from the watch page never restarts it.
     */
    public void open(SvipeRefResolver.VideoRef ref, Rect inlineWindowRect) {
        open(ref, inlineWindowRect, true);
    }

    /**
     * @param resolveHere false when the caller (the watch page) is already resolving this reference and
     *                    will hand the message over — otherwise both would spend the same two MTProto
     *                    round-trips on it.
     */
    private void open(SvipeRefResolver.VideoRef ref, Rect inlineWindowRect, boolean resolveHere) {
        if (ref == null) return;
        if (inlineWindowRect != null) setInlineRect(inlineWindowRect);
        if (current != null && current.sameAs(ref) && player != null) {
            toInline();
            return;
        }
        cancelUpNext();
        // Swapping the loaded video must NOT pass through MODE_CLOSED: an autoplay advance out of
        // fullscreen would restore the orientation and show the system bars for one frame before asking
        // for them back, and a minimised advance would grow the bar into an inline player with no page
        // under it. Fullscreen and mini are the user's state, not the video's.
        releaseCurrentVideo();
        account = UserConfig.selectedAccount;
        current = ref;
        resumeMs = 0;
        final boolean autoplayed = pendingAutoplay;
        pendingAutoplay = false;
        telemetry.onOpen(account, ref, autoplayed);
        setMode(mode == MODE_MINI || mode == MODE_FULLSCREEN ? mode : MODE_INLINE, false);
        if (stage != null) {
            stage.setInlineRect(inlineRect);
            stage.showCover(ref.mo);
            stage.getControls().setVideo(ref.mo, ref.chat);
        }
        for (int i = 0; i < observers.size(); i++) observers.get(i).onVideoChanged(ref);
        if (ref.mo != null) {
            startPlayback();
        } else if (resolveHere) {
            SvipeRefResolver.resolve(account, ref, () -> {
                if (current != ref || player != null) return;
                if (stage != null) {
                    stage.showCover(ref.mo);
                    stage.getControls().setVideo(ref.mo, ref.chat);
                }
                if (ref.mo != null) startPlayback();
            }, resolveDelegate);
        }
    }

    /** The watch page moved or was re-laid out; the stage follows it. */
    public void setInlineRect(Rect windowRect) {
        if (windowRect == null) return;
        inlineRect.set(windowRect);
        if (stage != null) stage.setInlineRect(inlineRect);
    }

    // ---------------- transitions ----------------

    public void enterFullscreen() {
        if (!isOpen() || mode == MODE_FULLSCREEN) return;
        setMode(MODE_FULLSCREEN, true);
    }

    public void exitFullscreen() {
        if (mode != MODE_FULLSCREEN) return;
        setMode(MODE_INLINE, true);
    }

    /**
     * Forward a drag-towards-mini to the page underneath so it travels with the picture. Only the
     * page the player is actually hosted in ever hears about it.
     */
    public void notifyPageDragAway(float translationY, float alpha) {
        final SvipeWatchActivity page = watchPage;
        if (page != null) page.setDragAway(translationY, alpha);
    }

    /** The drag ended without committing: the page comes back. */
    public void notifyPageDragCancelled() {
        final SvipeWatchActivity page = watchPage;
        if (page != null) page.resetDragAway();
    }

    /** The page the player is hosted in, or null when it is playing over something else. */
    public SvipeWatchActivity getWatchPage() {
        return watchPage;
    }

    public void toMini() {
        if (!isOpen() || mode == MODE_MINI) return;
        setMode(MODE_MINI, true);
    }

    public void toInline() {
        if (!isOpen() || mode == MODE_INLINE) return;
        setMode(MODE_INLINE, true);
    }

    /** Dismiss the player for good: the mini bar's ✕, a side-swipe, or the activity going away. */
    public void close() {
        final SvipeWatchActivity page = watchPage;
        watchPage = null;
        restoreItem = null;
        if (page != null && stage != null && mode != MODE_CLOSED && mode != MODE_MINI) {
            // Leave the way the page leaves: the picture slides out to the right with it. Releasing
            // the player first would blink the video away and then slide an empty page, which is the
            // exit reading as two events instead of one.
            stage.animateOutToRight(this::closePlayback);
            page.finishFragment();
            return;
        }
        closePlayback();
        // A watch page still in the stack would be left showing a black hole where the video was.
        if (page != null) page.finishFragment();
    }

    /**
     * Tear the playback down without touching the watch page — this is also the path an
     * {@link #open} of a different video takes, and it must not close the page that asked for it.
     */
    private void closePlayback() {
        if (current == null && mode == MODE_CLOSED) return;
        cancelUpNext();
        releaseCurrentVideo();
        setMode(MODE_CLOSED, false);
    }

    /**
     * Unload the current video without deciding what the player becomes next — shared by a close and by
     * an {@link #open} of a different video.
     *
     * The telemetry flush is FIRST and must stay there: the leave event is built from the duration and
     * the position, and releasing the player takes both away.
     */
    private void releaseCurrentVideo() {
        final SvipeRefResolver.VideoRef closed = current;
        telemetry.flush();
        releasePlayer();
        current = null;
        cancelStreams(closed);
    }

    /**
     * Leave the watch page but keep playing in the mini bar — the drag-down gesture, the chrome's back
     * affordance and the watch page's own back all land here, which is what YouTube does.
     */
    public void minimise() {
        if (!isOpen()) return;
        final SvipeWatchActivity page = watchPage;
        watchPage = null;
        // Geometry first: the picture shrinks into the bar while the page slides away underneath,
        // rather than waiting for the fragment's close animation to finish.
        toMini();
        if (page != null) page.finishFragment();
    }

    /** Grow the mini bar back into a full watch page at the same playback position. */
    public void restoreFromMini() {
        if (mode != MODE_MINI) return;
        final SvipeDiscover.Item item = restoreItem;
        final LaunchActivity activity = LaunchActivity.instance;
        if (item == null || activity == null) {
            return;   // nothing to restore INTO — an inline player with no page under it is worse
        }
        toInline();   // the bar grows into the hole the re-presented page is about to report
        activity.presentFragment(new SvipeWatchActivity(item));
    }

    /**
     * The chrome's back chevron: fullscreen collapses to inline; inline steps back through the
     * videos the user opened and only closes once that trail is empty — the same rule the back key
     * follows. Wiring it straight to close() made every related tap a one-way trip.
     */
    public void onBackAffordance() {
        if (mode == MODE_FULLSCREEN) {
            exitFullscreen();
            return;
        }
        final SvipeWatchActivity page = watchPage;
        if (page != null && page.stepBackInHistory()) {
            return;
        }
        close();
    }

    /**
     * The activity's back key, consulted before the fragment stack. Only fullscreen is ours — leaving
     * the watch page is the fragment's own back, which then arrives here as a close callback.
     */
    public boolean handleBackPressed() {
        if (mode == MODE_FULLSCREEN) {
            exitFullscreen();
            return true;
        }
        return false;
    }

    /**
     * THE ONLY WRITER OF {@link #mode}. Every rule that makes fullscreen survive rotation lives here:
     * the orientation request is applied as a side effect of the state change, never as its cause, and
     * it is a hint layered on top of this boolean — at targetSdk 36 Android already ignores
     * setRequestedOrientation on displays >= 600 dp, so fullscreen has to look right in portrait too.
     */
    private void setMode(int newMode, boolean animated) {
        if (mode == newMode) return;
        final int previous = mode;
        mode = newMode;
        if (previous == MODE_FULLSCREEN) {
            leaveFullscreenPresentation();
        } else if (newMode == MODE_FULLSCREEN) {
            enterFullscreenPresentation();
        }
        if (stage != null) {
            stage.getGestures().cancelTransientState();
            stage.onModeChanged(mode, animated);
        }
        // Going fullscreen is the strongest engagement signal a long-form watch produces, and every
        // mode change is a natural point to flush what the watch clock knows so far.
        telemetry.onModeChanged();
        for (int i = 0; i < observers.size(); i++) observers.get(i).onModeChanged(mode);
    }

    /**
     * The system bars are hidden by TOGGLING ONLY THESE FLAGS: the activity already carries
     * {@code SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | LAYOUT_HIDE_NAVIGATION} plus whatever light-bar flags
     * the current screen set, so a bitwise toggle hides the bars without moving a single pixel of
     * layout and without clobbering the fork's own status-bar handling.
     */
    private static final int FULLSCREEN_UI_FLAGS = View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

    private void enterFullscreenPresentation() {
        final Activity activity = activityOf();
        if (activity == null) return;
        try {
            savedOrientation = activity.getRequestedOrientation();
            // SENSOR_LANDSCAPE, not one pinned landscape: both landscapes are allowed and the player
            // flips live between them, which PhotoViewer cannot do (it pins whichever landscape the
            // display happened to be in at tap time).
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            setSystemBarsHidden(activity, true);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void leaveFullscreenPresentation() {
        final Activity activity = activityOf();
        if (activity != null) {
            try {
                activity.setRequestedOrientation(savedOrientation);
                setSystemBarsHidden(activity, false);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if (stage != null) {
            SvipeVideoGestures.releaseBrightness(stage.getContext());
        }
    }

    private static void setSystemBarsHidden(Activity activity, boolean hidden) {
        final View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (decor == null) return;
        final int flags = decor.getSystemUiVisibility();
        final int updated = hidden ? (flags | FULLSCREEN_UI_FLAGS) : (flags & ~FULLSCREEN_UI_FLAGS);
        if (flags != updated) decor.setSystemUiVisibility(updated);
    }

    private Activity activityOf() {
        Activity activity = stage != null ? AndroidUtilities.findActivity(stage.getContext()) : null;
        if (activity == null) activity = LaunchActivity.instance;
        return activity != null && !activity.isFinishing() ? activity : null;
    }

    // ---------------- playback ----------------

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public long getPositionMs() {
        return player != null ? Math.max(0, player.getCurrentPosition()) : resumeMs;
    }

    public long getDurationMs() {
        return player != null ? Math.max(0, player.getDuration()) : 0;
    }

    /** How much of the stream is buffered, for the seek bar's secondary track. */
    public long getBufferedMs() {
        return player != null ? Math.max(0, player.getBufferedPosition()) : 0;
    }

    /** @param fast CLOSEST_SYNC — right for a scrub in progress; EXACT for the frame the user picked. */
    public void seekTo(long positionMs, boolean fast) {
        if (player == null) return;
        cancelUpNext();   // scrubbing back into the video is a decision not to advance
        try {
            player.seekTo(Math.max(0, positionMs), fast);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public float getPlaybackSpeed() {
        return player != null ? player.getPlaybackSpeed() : savedSpeed(current != null ? current.mo : null);
    }

    /**
     * Apply a speed WITHOUT persisting it — the long-press 2x boost, which must not survive the finger.
     * The user's chosen speed goes through {@link #chooseSpeed}.
     */
    public void setPlaybackSpeed(float speed) {
        if (player != null) player.setPlaybackSpeed(speed);
    }

    /**
     * The user picked a speed from the menu: apply it and remember it per message, in the same
     * {@code playback_speed} store PhotoViewer uses — so a speed set on a video in either player is the
     * speed it plays at in both.
     */
    public void chooseSpeed(float speed) {
        setPlaybackSpeed(speed);
        final MessageObject mo = current != null ? current.mo : null;
        if (mo == null) return;
        try {
            final SharedPreferences prefs = ApplicationLoader.applicationContext
                    .getSharedPreferences("playback_speed", Activity.MODE_PRIVATE);
            final String key = "speed" + mo.getDialogId() + "_" + mo.getId();
            if (Math.abs(speed - 1f) < 0.001f) {
                prefs.edit().remove(key).apply();
            } else {
                prefs.edit().putFloat(key, speed).apply();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static float savedSpeed(MessageObject mo) {
        if (mo == null) return 1f;
        try {
            return ApplicationLoader.applicationContext
                    .getSharedPreferences("playback_speed", Activity.MODE_PRIVATE)
                    .getFloat("speed" + mo.getDialogId() + "_" + mo.getId(), 1f);
        } catch (Exception e) {
            return 1f;
        }
    }

    /**
     * Whether this video loops. Persisted per message, so the choice survives a reopen.
     *
     * <p>The DEFAULT is off for everything this player opens, which is not what
     * {@code SvipeVideoLadder.savedLoop} answers: that helper falls back to the reels rule (loop unless
     * the document is at least three minutes long), so a two-minute LANDSCAPE video — which routes here
     * because the Video tab's cards are landscape, not because of its length — would loop forever. On a
     * watch page that is wrong twice over: YouTube does not loop, and a looping video never reaches
     * STATE_ENDED, so it would silently disable autoplay for exactly those videos.
     */
    public boolean isLooping() {
        return loopSetting(current != null ? current.mo : null);
    }

    private static boolean loopSetting(MessageObject mo) {
        final Boolean saved = VideoPlayer.getLooping(mo);
        return saved != null && saved;
    }

    /**
     * A user toggle is an explicit override of the long-form no-loop guard, not a violation of it: the
     * guard only picks the DEFAULT (a reel loops, a lecture ends).
     */
    public void setLooping(boolean looping) {
        final MessageObject mo = current != null ? current.mo : null;
        if (mo == null) return;
        VideoPlayer.saveLooping(looping, mo);
        if (player != null) player.setLooping(looping);
    }

    /**
     * Pick a rendition, or {@link VideoPlayer#QUALITY_AUTO} for adaptive. Persisted per message through
     * VideoPlayer's own store, which {@link #startPlayback} reads back on the next open.
     */
    public void selectQuality(int index) {
        if (player == null) return;
        try {
            player.setSelectedQuality(index);
            final MessageObject mo = current != null ? current.mo : null;
            if (mo != null) {
                VideoPlayer.saveQuality(index == VideoPlayer.QUALITY_AUTO ? null : player.getQuality(index), mo);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void togglePlayPause() {
        if (player == null) return;
        cancelUpNext();
        if (player.isPlaying()) {
            player.pause();
        } else {
            // Play on a finished video starts it over instead of doing nothing.
            final long duration = getDurationMs();
            if (duration > 0 && getPositionMs() >= duration - END_EPSILON_MS) {
                seekTo(0, false);
            }
            player.setPlayWhenReady(true);
            player.play();
        }
    }

    /**
     * Playback engine wiring for long-form. Deliberately NOT {@code setIsReels()}: that LoadControl
     * caps read-ahead at 6s/10s, which would make scrubbing a 40-minute video stutter — the default
     * buffering profile is the right one here. {@code pauseOther=true} is what gives the Music tab
     * mutual exclusion for free (VideoPlayer observes playerDidStartPlaying and MediaController
     * consumes ours), exactly as the reels player gets it.
     */
    /**
     * Route every dead end here. What this player did before was leave a black rectangle up with no
     * message, no spinner and no way to try again — indistinguishable from "the video does not play
     * at all", which is exactly how it was reported.
     */
    private void showPlaybackError() {
        if (stage == null) return;
        stage.showError(() -> {
            stage.showError(null);
            final SvipeWatchActivity page = watchPage;
            if (current != null && current.mo == null && page != null) {
                page.retryResolve();   // the MTProto resolve is what failed — run it again
            } else {
                startPlayback();
            }
        });
    }

    private void startPlayback() {
        if (stage == null || current == null || current.mo == null) return;
        final MessageObject mo = current.mo;
        final TLRPC.Document doc = mo.getDocument();
        if (doc == null) return;
        releasePlayer();
        try {
            final VideoPlayer p = new VideoPlayer(true, false);
            // Published before the delegate is installed: a first frame is only ever accepted from
            // the player this field points at, so a late frame from a released one cannot uncover a
            // newly opened video.
            firstFrameSeen = false;
            player = p;
            p.setLooping(loopSetting(mo));
            // Width/height come from the document, so the surface is already the right shape before
            // the first frame — no layout jump when playback starts.
            final float ar = SvipeVideoLadder.videoAspect(doc);
            if (ar > 0) stage.setAspectRatio(ar, 0);
            // This video owns the bandwidth: stream reads at HIGH so playback and seeks stay smooth.
            // Under HLS every rung the selector may pick must be HIGH — the priority map is
            // per-document. No dwell escalation to a full download: completing a 40-minute file
            // after a glance would be hundreds of MB (an explicit Download tap is a separate path).
            final ArrayList<VideoPlayer.Quality> qualities = SvipeVideoLadder.playbackQualitiesFor(account, mo);
            if (qualities != null) {
                for (TLRPC.Document d : SvipeVideoLadder.ladderVideoDocs(qualities)) {
                    FileStreamLoadOperation.setPriorityForDocument(d, FileLoader.PRIORITY_HIGH);
                }
            } else {
                FileStreamLoadOperation.setPriorityForDocument(doc, FileLoader.PRIORITY_HIGH);
            }
            p.setTextureView(stage.getTextureView()); // exactly once per opened video
            // setDelegate MUST precede preparePlayer: VideoPlayer reports the first state change
            // during prepare and dereferences the delegate with no null check.
            p.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override
                public void onStateChanged(boolean playWhenReady, int playbackState) {
                    if (p != player) return;
                    notifyPlaying(playWhenReady && playbackState != Player.STATE_ENDED
                            && playbackState != Player.STATE_IDLE);
                    // Buffering time separates "watched four seconds and left" from "waited for a
                    // frame and gave up" — the leave classifier must not read the second as a rejection.
                    telemetry.onBuffering(playbackState == Player.STATE_BUFFERING);
                    if (playbackState == Player.STATE_ENDED) {
                        // STATE_ENDED is new plumbing here on purpose: reels never reaches it because
                        // reels loop, and a long-form video the user turned looping ON for does not
                        // either (that is ExoPlayer's repeat mode, below the state machine).
                        telemetry.onEnded();
                        final SvipeRefResolver.VideoRef ended = current;
                        for (int i = 0; i < observers.size(); i++) observers.get(i).onVideoEnded(ended);
                        onPlaybackEnded();
                    }
                }

                @Override
                public void onError(VideoPlayer failed, Exception e) {
                    FileLog.e(e);
                    if (p == player) {
                        telemetry.onPlayFailed("player_error");
                        showPlaybackError();   // a swallowed error reads as "it just doesn't play"
                    }
                }

                @Override
                public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                    if (unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270) { int t = width; width = height; height = t; }
                    float ratio = height == 0 ? 1f : (width * pixelWidthHeightRatio) / height;
                    if (stage != null) stage.setAspectRatio(ratio, unappliedRotationDegrees);
                }

                // The live callback is the AnalyticsListener overload (with EventTime) — the no-arg
                // one exists in the delegate interface but VideoPlayer never routes the player's real
                // event to it. Handle both, once.
                @Override
                public void onRenderedFirstFrame() { handleFirstFrame(p); }

                @Override
                public void onRenderedFirstFrame(AnalyticsListener.EventTime eventTime) { handleFirstFrame(p); }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {}

                @Override
                public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) { return false; }
            });
            if (qualities != null) {
                // The rendition the user last picked FOR THIS VIDEO wins, which is what makes the
                // quality menu persist across a reopen. Failing that, a fully-cached rendition is pinned
                // (plays from disk, works offline); failing that AUTO — ExoPlayer starts on a rung the
                // bandwidth estimate sustains and adapts mid-play instead of buffering.
                VideoPlayer.Quality select = VideoPlayer.getSavedQuality(qualities, mo);
                final boolean saved = select != null;
                if (select == null) select = SvipeVideoLadder.cachedQualityOf(qualities);
                FileLog.d("svipe: long-form play hls rungs=" + qualities.size() + " source="
                        + (saved ? "user-choice" : select != null ? "LOCAL-cache" : "network-auto"));
                p.preparePlayer(qualities, select);
            } else {
                int reference = FileLoader.getInstance(account).getFileReference(mo);
                VideoPlayer.VideoUri vu = VideoPlayer.VideoUri.of(account, doc, null, reference, false);
                FileLog.d("svipe: long-form play source=" + (vu.isCached() ? "LOCAL-cache" : "network"));
                p.preparePlayer(vu.uri, "other");
            }
            if (resumeMs > 0) {
                try { p.seekTo(resumeMs); } catch (Exception ignore) {}
            }
            // After preparePlayer, which is where ExoPlayer's own default would otherwise win.
            p.setPlaybackSpeed(savedSpeed(mo));
            telemetry.onPlayRequested();
            p.setPlayWhenReady(true);
            p.play();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void notifyPlaying(boolean playing) {
        // Watching a video is the one activity where the user touches nothing for minutes, so the
        // screen timeout has to be held off while — and only while — something is actually playing.
        // Hung on the stage view rather than the window: a view's keep-screen-on is released when it
        // detaches, so no code path can leave the display pinned on after the player is gone.
        if (stage != null) stage.setKeepScreenOn(playing);
        if (stage != null) stage.getControls().setPlaying(playing);
        telemetry.onPlayingChanged(playing);
        for (int i = 0; i < observers.size(); i++) observers.get(i).onPlayingChanged(playing);
    }

    private void handleFirstFrame(VideoPlayer from) {
        if (firstFrameSeen || from != player) return;
        firstFrameSeen = true;
        AndroidUtilities.runOnUIThread(() -> {
            if (stage != null && from == player) stage.onFirstFrame();
            if (from == player) telemetry.onFirstFrame();
        });
    }

    private void releasePlayer() {
        if (player == null) return;
        try { resumeMs = Math.max(0, player.getCurrentPosition()); } catch (Exception ignore) {}
        try { player.releasePlayer(true); } catch (Exception ignore) {}
        player = null;
        firstFrameSeen = false;
    }

    /**
     * Releasing the player only detaches the stream listener — the FileLoader operation keeps pulling
     * the whole file at PRIORITY_HIGH, which for a 40-minute video is pure waste (and starves the
     * Reels tab's prefetch window) once nobody is watching. Under HLS any rung, or a rung's manifest,
     * may own the in-flight op, so all of them are reset.
     */
    private void cancelStreams(SvipeRefResolver.VideoRef ref) {
        if (ref == null || ref.mo == null) return;
        try {
            ArrayList<TLRPC.Document> docs = SvipeVideoLadder.ladderDocsWithManifests(account, ref.mo);
            if (docs.isEmpty()) {
                TLRPC.Document d = ref.mo.getDocument();
                if (d != null) docs.add(d);
            }
            for (TLRPC.Document d : docs) {
                FileLoader.getInstance(account).cancelLoadFile(d);
            }
        } catch (Exception e) { FileLog.e(e); }
    }

    // ---------------- autoplay to the next related video ----------------

    /** The toggle behind the switch in the player chrome. Player-wide and persisted; default ON. */
    public boolean isAutoplayEnabled() {
        try {
            return MessagesController.getMainSettings(account).getBoolean(PREF_AUTOPLAY, true);
        } catch (Exception e) {
            return true;
        }
    }

    public void setAutoplayEnabled(boolean enabled) {
        try {
            MessagesController.getMainSettings(account).edit().putBoolean(PREF_AUTOPLAY, enabled).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (!enabled) cancelUpNext();
    }

    /**
     * The video finished. With autoplay off the player simply stops on the last frame (the centre play
     * affordance restarts it); with autoplay on, the "Up next" preview counts down and then advances,
     * which is the behaviour the owner asked for and the reason the toggle is a real switch.
     */
    private void onPlaybackEnded() {
        if (!isAutoplayEnabled()) {
            return;
        }
        final SvipeDiscover.Item next = pickUpNext();
        if (next == null) {
            return;   // nothing left to play: stopping at the end is the correct outcome
        }
        if (mode == MODE_MINI) {
            // The mini bar has no room for a preview, and a countdown nobody can see is just a delay.
            upNext = next;
            playUpNextNow();
            return;
        }
        upNext = next;
        upNextSecondsLeft = UP_NEXT_COUNTDOWN_S;
        if (stage != null) {
            stage.getControls().showUpNext(upNextMessage(), upNextSecondsLeft, UP_NEXT_COUNTDOWN_S);
        }
        AndroidUtilities.cancelRunOnUIThread(upNextTick);
        AndroidUtilities.runOnUIThread(upNextTick, 1000);
    }

    /** The first related reference autoplay has not already used, or null. */
    private SvipeDiscover.Item pickUpNext() {
        syncRelatedSnapshot();
        for (int i = 0; i < relatedSnapshot.size(); i++) {
            final SvipeDiscover.Item item = relatedSnapshot.get(i);
            if (item == null) continue;
            if (current != null && item.channelId == current.channelId && item.messageId == current.messageId) {
                continue;
            }
            if (autoplayedKeys.contains(keyOf(item))) continue;
            return item;
        }
        return null;
    }

    /**
     * Copy the live page's related list. The page is the owner of that list (it pages the endpoint), but
     * the mini bar outlives the page, so the last copy is what autoplay falls back to.
     */
    private void syncRelatedSnapshot() {
        if (watchPage == null) return;
        final List<SvipeDiscover.Item> related = watchPage.getRelatedItems();
        if (related.isEmpty()) return;
        relatedSnapshot.clear();
        relatedSnapshot.addAll(related);
    }

    private static String keyOf(SvipeDiscover.Item item) {
        return item.channelId + ":" + item.messageId;
    }

    /** The resolved message of the up-next candidate, for its title and thumbnail. Null in mini. */
    private MessageObject upNextMessage() {
        return watchPage != null && upNext != null ? watchPage.relatedMessage(upNext) : null;
    }

    /** Skip the rest of the countdown. Also the panel's tap target. */
    public void playUpNextNow() {
        final SvipeDiscover.Item next = upNext;
        cancelUpNext();
        if (next == null) return;
        autoplayedKeys.add(keyOf(next));
        pendingAutoplay = true;
        if (watchPage != null) {
            // The page swaps in place and re-reports, which comes back as onWatchPageOpened -> open().
            watchPage.openItem(next);
        } else {
            final SvipeRefResolver.VideoRef ref = SvipeRefResolver.VideoRef.of(next);
            ref.recId = next.recId;
            open(ref, null);
        }
        pendingAutoplay = false;
    }

    /** Stop the countdown and take the preview down. Every user interaction lands here. */
    public void cancelUpNext() {
        upNext = null;
        upNextSecondsLeft = 0;
        AndroidUtilities.cancelRunOnUIThread(upNextTick);
        if (stage != null) stage.getControls().hideUpNext();
    }

    private final SvipeRefResolver.Delegate resolveDelegate = new SvipeRefResolver.Delegate() {
        @Override
        public void onResolved(SvipeRefResolver.Ref ref) {
            // Nothing to enrich here: the watch page reads title/channel/actions off the resolved
            // MessageObject itself, and the queued start-playback callback runs right after this.
        }

        @Override
        public void onFailed(SvipeRefResolver.Ref ref, boolean retryable) {
            // No retry loop yet — the stall watchdog extraction owns recovery. Waiters must still be
            // woken so the queued start-playback intent cannot leak (it no-ops with a null message).
            FileLog.d("svipe: long-form resolve failed retryable=" + retryable);
            AndroidUtilities.runOnUIThread(() -> SvipeRefResolver.drainCallbacks(ref));
        }
    };

    // ---------------- the watch page (this is the only coupling between page and player) ----------------

    private final SvipeWatchActivity.PlayerHoleListener holeListener = new SvipeWatchActivity.PlayerHoleListener() {
        @Override
        public void onWatchPageOpened(SvipeWatchActivity page) {
            watchPage = page;
            restoreItem = page.getWatchItem();
            final SvipeRefResolver.VideoRef ref = SvipeRefResolver.VideoRef.of(page.getWatchItem());
            ref.mo = page.getWatchMessage();
            ref.chat = page.getWatchChat();
            // VideoRef.of copies only the fields the resolver needs; the recommendation id is the
            // telemetry's, so it is attached here.
            ref.recId = page.getWatchItem().recId;
            final Rect hole = new Rect();
            // Where the user tapped, so the picture grows out of that card instead of appearing from
            // nowhere. Handed over before open() so the very first layout can start there.
            if (stage != null) stage.setOpenFromRect(page.getOpenFromRect());
            // resolveHere=false: the page is already spending the two MTProto round-trips and hands
            // the message over through onWatchItemResolved.
            open(ref, page.getPlayerHoleRect(hole) ? hole : null, false);
        }

        @Override
        public void onWatchItemResolved(SvipeWatchActivity page) {
            if (page != watchPage || current == null || current.mo != null) return;
            current.mo = page.getWatchMessage();
            current.chat = page.getWatchChat();
            if (current.mo == null) {
                // The page's MTProto resolve came back empty (network, a private channel, a deleted
                // post). There is nothing to play and nothing else will call us — say so and offer a
                // retry instead of leaving a black player up forever.
                FileLog.d("svipe: long-form watch item did not resolve -> error + retry");
                telemetry.onPlayFailed("resolve_failed");
                showPlaybackError();
                return;
            }
            if (stage != null) {
                stage.showCover(current.mo);
                stage.getControls().setVideo(current.mo, current.chat);
            }
            startPlayback();
        }

        @Override
        public void onPlayerHoleChanged(SvipeWatchActivity page, Rect windowRect) {
            if (page == watchPage) setInlineRect(windowRect);
        }

        @Override
        public void onWatchPageHidden(SvipeWatchActivity page) {
            // Something was presented over the page (a channel, a profile). The overlay draws above
            // every fragment, so an inline player would hang over a screen it has nothing to do with.
            if (page != watchPage) return;
            syncRelatedSnapshot();   // last chance: autoplay in the mini bar has no page to ask
            watchPage = null;
            toMini();
            // The buried page can never host the player again — the mini bar's restore presents a
            // fresh one — so it is dropped instead of being revealed later with a black hole in it.
            // Deferred one frame to stay out of the navigation layout's own transition bookkeeping.
            AndroidUtilities.runOnUIThread(page::removeSelfFromStack);
        }

        @Override
        public void onWatchPageVisible(SvipeWatchActivity page) {
            // Guarded to MINI on purpose: a visibility callback must never be able to write the mode
            // out of fullscreen. Only the five transition methods decide that.
            if (page == watchPage && mode == MODE_MINI) toInline();
        }

        @Override
        public void onWatchPageClosed(SvipeWatchActivity page) {
            if (watchPage == page) {
                syncRelatedSnapshot();
                watchPage = null;
            }
            if (mode == MODE_CLOSED || mode == MODE_MINI) return;
            // Back closes the video. Parking it in the mini bar on the way out reads as the player
            // refusing to leave: the user asked for the page to go away, and a video still playing
            // over the next screen is not what they asked for. Minimising stays available on
            // purpose — the drag-down gesture — where it is something the user did deliberately.
            close();
        }
    };

    // ---------------- activity lifecycle (LaunchActivity forwards) ----------------

    /**
     * Backgrounding pauses playback: background audio is explicitly out of scope, and a paused player
     * keeps its position so returning resumes exactly where the user left off. The brightness override
     * goes back to the system here too — it is a window attribute, so leaving it set would dim (or
     * blaze) whatever the user opens next.
     */
    public void onActivityPause() {
        if (stage != null) {
            SvipeVideoGestures.releaseBrightness(stage.getContext());
            stage.getGestures().cancelTransientState();
        }
        // A countdown must not advance to a video nobody is looking at.
        cancelUpNext();
        telemetry.onBackground();
        if (player == null) return;
        try { resumeMs = Math.max(0, player.getCurrentPosition()); } catch (Exception ignore) {}
        try { player.pause(); } catch (Exception ignore) {}
    }

    /** The activity owning {@code destroyedStage} is going away — nothing may outlive its surface. */
    public void onActivityDestroyed(SvipeVideoStage destroyedStage) {
        if (destroyedStage != null && stage != destroyedStage) return;
        watchPage = null;   // the fragment stack is being torn down; finishing a fragment here is not
        restoreItem = null;
        closePlayback();
        stage = null;
    }
}
