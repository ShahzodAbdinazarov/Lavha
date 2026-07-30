package org.telegram.svipe.video;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.StatsController;
import org.telegram.svipe.SvipeDiscover;
import org.telegram.svipe.SvipeLongWatch;
import org.telegram.tgnet.ConnectionsManager;

/**
 * The long-form player's watch clock and event poster: one instance, owned by
 * {@link SvipeVideoPlayerController}, driven entirely by calls from it (not by an observer — the leave
 * event has to be flushed at a precise point in the teardown, before the player is released).
 *
 * <p><b>What the recommender gets, and why it is shaped this way.</b> The long-form value model needs
 * watch TIME, not completion: at 40 minutes a completion rate measures the upload's length. So this
 * sends a 30-second HEARTBEAT while playing and a terminal event classified by
 * {@link SvipeLongWatch} — which resolves to HEARTBEAT for the common "watched a good chunk and left"
 * case. HEARTBEAT is deliberately neither an exposure event nor a rewarded one server-side: it has no
 * reward branch, so it is NEUTRAL for the bandit, while the raw payload is persisted forever and the
 * real labels stay re-derivable once a long-form value term exists.
 *
 * <p>IMPRESSION is sent for the WATCHED video only, never for a related row that merely scrolled into
 * view: exposure events add the reference to the user's long-TTL "seen" set, and the long-form pipe
 * drops seen references — impressions on scroll would strip the user's own Video tab.
 */
public class SvipeVideoTelemetry {

    /** Cadence of the in-play heartbeat. Matches what the recsys ingests for reels-scale sessions. */
    private static final long HEARTBEAT_MS = 30_000;
    /**
     * Floor between two heartbeats. Pausing, backgrounding and every mode change each flush one, and
     * a user flicking in and out of fullscreen must not turn that into a request storm.
     */
    private static final long MIN_HEARTBEAT_GAP_MS = 5_000;

    /** Playback numbers only the player knows, read at the moment an event is built. */
    public interface Source {
        long positionMs();

        long durationMs();
    }

    private final Source source;

    private int account;
    private long channelId;
    private int messageId;
    private String recId;
    private boolean autoplay;

    /**
     * The duration, cached the first time the player reports one. The leave event is built while the
     * player is being torn down, when {@link Source#durationMs()} has already gone to zero.
     */
    private long durationMs;
    private long lastPositionMs;

    private long shownAtMs;          // dwell base; 0 = nothing is being tracked
    private long watchedAccumMs;     // playing time, excluding pauses
    private long watchStartMs;       // 0 while paused
    private long bufferingAccumMs;
    private long bufferingStartMs;   // 0 while not stalled
    private long playRequestedAtMs;
    private long ttffMs = -1;        // -1 = no frame has rendered yet
    private long lastHeartbeatMs;

    private boolean playStartSent;
    private boolean endedNaturally;

    /**
     * Armed only while playback is actually running. A timer that kept firing while the app is
     * backgrounded (playback pauses, the watch clock is closed) would post an identical event every
     * thirty seconds for as long as the process lived.
     */
    private final Runnable heartbeatTick = new Runnable() {
        @Override
        public void run() {
            if (shownAtMs == 0 || watchStartMs == 0) {
                return;
            }
            postHeartbeat();
            AndroidUtilities.runOnUIThread(this, HEARTBEAT_MS);
        }
    };

    public SvipeVideoTelemetry(Source source) {
        this.source = source;
    }

    // ---------------- lifecycle of one watched video ----------------

    /**
     * A video became the watched one. Flushes whatever was being tracked first, so an autoplay
     * advance emits the previous video's leave event before the next one's IMPRESSION.
     *
     * @param autoplay true when the player, not the user, chose this video
     */
    public void onOpen(int account, SvipeRefResolver.VideoRef ref, boolean autoplay) {
        flush();
        if (ref == null) {
            return;
        }
        this.account = account;
        this.channelId = ref.channelId;
        this.messageId = ref.messageId;
        this.recId = ref.recId;
        this.autoplay = autoplay;
        durationMs = 0;
        lastPositionMs = 0;
        watchedAccumMs = 0;
        watchStartMs = 0;
        bufferingAccumMs = 0;
        bufferingStartMs = 0;
        ttffMs = -1;
        playStartSent = false;
        endedNaturally = false;
        shownAtMs = System.currentTimeMillis();
        playRequestedAtMs = shownAtMs;
        lastHeartbeatMs = shownAtMs;
        post("IMPRESSION", basePayload());
        AndroidUtilities.cancelRunOnUIThread(heartbeatTick);
    }

    /** The player was (re)prepared and asked to play — resets the time-to-first-frame clock. */
    public void onPlayRequested() {
        if (shownAtMs == 0) {
            return;
        }
        playRequestedAtMs = System.currentTimeMillis();
        ttffMs = -1;
    }

    public void onPlayingChanged(boolean playing) {
        if (shownAtMs == 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (playing) {
            if (watchStartMs == 0) {
                watchStartMs = now;
            }
            if (!playStartSent) {
                playStartSent = true;
                post("PLAY_START", basePayload());
            }
            AndroidUtilities.cancelRunOnUIThread(heartbeatTick);
            AndroidUtilities.runOnUIThread(heartbeatTick, HEARTBEAT_MS);
        } else if (watchStartMs > 0) {
            watchedAccumMs += now - watchStartMs;
            watchStartMs = 0;
            AndroidUtilities.cancelRunOnUIThread(heartbeatTick);
            // A pause is a real boundary in a long watch: flush what we know rather than wait out the
            // heartbeat, because the user may leave from here.
            postHeartbeat();
        }
    }

    /** Mid-playback stalls. Counted so a network-caused bail is not read as a rejection. */
    public void onBuffering(boolean buffering) {
        if (shownAtMs == 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (buffering) {
            if (bufferingStartMs == 0) {
                bufferingStartMs = now;
            }
        } else if (bufferingStartMs > 0) {
            bufferingAccumMs += now - bufferingStartMs;
            bufferingStartMs = 0;
        }
    }

    public void onFirstFrame() {
        if (shownAtMs == 0 || ttffMs >= 0) {
            return;
        }
        ttffMs = Math.max(0, System.currentTimeMillis() - playRequestedAtMs);
        final JSONObject payload = basePayload();
        put(payload, "time_to_first_frame_ms", ttffMs);
        post("FIRST_FRAME", payload);
    }

    /** STATE_ENDED. Recorded rather than posted: the terminal event is still the leave event. */
    public void onEnded() {
        endedNaturally = true;
    }

    /** A mode change is a strong engagement signal (fullscreen especially) and a natural flush point. */
    public void onModeChanged() {
        postHeartbeat();
    }

    /** Backgrounding pauses playback; flush before the process may be killed. */
    public void onBackground() {
        if (shownAtMs == 0) {
            return;
        }
        postHeartbeat(true);
    }

    /**
     * Playback gave up on this video. Not an exposure or rewarded event server-side, so it can carry
     * whatever diagnostic fields help — the same shape ReelsActivity's stuck-reel diagnostics use.
     */
    public void onPlayFailed(String kind) {
        if (shownAtMs == 0) {
            return;
        }
        final JSONObject payload = basePayload();
        put(payload, "kind", kind);
        try {
            payload.put("online", ApplicationLoader.isNetworkOnline());
            payload.put("conn", ConnectionsManager.getInstance(account).getConnectionState());
        } catch (Exception e) {
            FileLog.e(e);
        }
        post("PLAY_FAILED", payload);
    }

    /**
     * The watched video is going away (closed, released, or replaced by an autoplay advance). MUST be
     * called BEFORE the player is released: the duration and position come from it.
     */
    public void flush() {
        if (shownAtMs == 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        snapshot();
        if (watchStartMs > 0) {
            watchedAccumMs += now - watchStartMs;
            watchStartMs = 0;
        }
        if (bufferingStartMs > 0) {
            bufferingAccumMs += now - bufferingStartMs;
            bufferingStartMs = 0;
        }
        final long dwell = now - shownAtMs;
        final String type = SvipeLongWatch.classify(watchedAccumMs, durationMs, lastPositionMs,
                endedNaturally, bufferingAccumMs, Math.max(0, ttffMs));
        final JSONObject payload = basePayload();
        put(payload, "dwell_ms", dwell);
        if (ttffMs >= 0) {
            put(payload, "time_to_first_frame_ms", ttffMs);
        }
        shownAtMs = 0;   // before posting: a failed post must not be able to double-flush
        AndroidUtilities.cancelRunOnUIThread(heartbeatTick);
        post(type, payload);
    }

    // ---------------- payload ----------------

    private void postHeartbeat() {
        postHeartbeat(false);
    }

    private void postHeartbeat(boolean force) {
        if (shownAtMs == 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (!force && now - lastHeartbeatMs < MIN_HEARTBEAT_GAP_MS) {
            return;
        }
        lastHeartbeatMs = now;
        post("HEARTBEAT", basePayload());
    }

    /** Keep the cached duration/position fresh while the player is still alive to be asked. */
    private void snapshot() {
        if (source == null) {
            return;
        }
        final long duration = source.durationMs();
        if (duration > 0) {
            durationMs = duration;
        }
        final long position = source.positionMs();
        if (position > 0) {
            lastPositionMs = position;
        }
    }

    private JSONObject basePayload() {
        snapshot();
        final long watched = watchedAccumMs + (watchStartMs > 0 ? System.currentTimeMillis() - watchStartMs : 0);
        final long buffering = bufferingAccumMs
                + (bufferingStartMs > 0 ? System.currentTimeMillis() - bufferingStartMs : 0);
        final JSONObject payload = new JSONObject();
        put(payload, "watched_ms", watched);
        put(payload, "position_ms", lastPositionMs);
        if (durationMs > 0) {
            put(payload, "video_duration_ms", durationMs);
        }
        put(payload, "buffering_ms", buffering);
        try {
            payload.put("autoplay", autoplay);
            payload.put("network_type", networkType());
        } catch (Exception e) {
            FileLog.e(e);
        }
        return payload;
    }

    private static void put(JSONObject payload, String key, long value) {
        try {
            payload.put(key, value);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void put(JSONObject payload, String key, String value) {
        try {
            payload.put(key, value);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** The payload field is typed as a string server-side, so the int type has to be named here. */
    private static String networkType() {
        switch (ApplicationLoader.getAutodownloadNetworkType()) {
            case StatsController.TYPE_WIFI:
                return "wifi";
            case StatsController.TYPE_ROAMING:
                return "roaming";
            default:
                return "mobile";
        }
    }

    private void post(String eventType, JSONObject payload) {
        if (channelId == 0) {
            return;
        }
        SvipeDiscover.sendEvent(account, channelId, messageId, eventType, payload, recId, null);
        FileLog.d("svipe: long-form event " + eventType + " " + channelId + ":" + messageId + " " + payload);
    }
}
