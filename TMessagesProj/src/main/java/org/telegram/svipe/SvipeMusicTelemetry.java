package org.telegram.svipe;

import android.util.SparseArray;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;

/**
 * Listens to MediaController playback notifications and reports catalog-track listening telemetry
 * to the music backend (PLAY_START on start; TRACK_END / SKIP with played_ms when the track
 * changes or playback stops). Also drives the infinite-vibe extension: each track start asks the
 * active queue to prefetch the next page when the tail is near.
 *
 * Natural track end resets audioProgress before the next messagePlayingDidStart fires, so progress
 * is snapshotted from messagePlayingProgressDidChanged while the track plays.
 */
public class SvipeMusicTelemetry implements NotificationCenter.NotificationCenterDelegate {

    private static final SparseArray<SvipeMusicTelemetry> instances = new SparseArray<>();

    public static synchronized SvipeMusicTelemetry getInstance(int account) {
        SvipeMusicTelemetry t = instances.get(account);
        if (t == null) {
            t = new SvipeMusicTelemetry(account);
            instances.put(account, t);
        }
        return t;
    }

    private final int account;
    private boolean attached;

    private MessageObject trackedMo;
    private SvipeMusic.Track trackedTrack;
    private String trackedSource;
    private String trackedRecId;
    private float lastProgress;

    private SvipeMusicTelemetry(int account) {
        this.account = account;
    }

    public void attach() {
        if (attached) {
            return;
        }
        attached = true;
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.addObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (id == NotificationCenter.messagePlayingDidStart) {
            MessageObject mo = (MessageObject) args[0];
            onPlayStart(mo);
        } else if (id == NotificationCenter.messagePlayingDidReset) {
            flushTracked();
        } else if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            if (trackedMo != null && args.length > 0 && args[0] instanceof Integer
                && (Integer) args[0] == trackedMo.getId()) {
                float p = trackedMo.audioProgress;
                if (p > lastProgress) {
                    lastProgress = p;
                }
            }
        }
    }

    private void onPlayStart(MessageObject mo) {
        if (mo == trackedMo) {
            return;
        }
        flushTracked();
        SvipeMusicQueue queue = SvipeMusicQueue.getActive();
        SvipeMusic.Track track = queue != null ? queue.trackFor(mo) : null;
        if (track == null) {
            return;
        }
        trackedMo = mo;
        trackedTrack = track;
        trackedSource = queue.source;
        trackedRecId = queue.recommendationId;
        lastProgress = 0f;
        SvipeMusic.sendEvent(account, track, "PLAY_START", basePayload());
        queue.maybeExtend(mo);
    }

    private void flushTracked() {
        if (trackedTrack == null) {
            return;
        }
        SvipeMusic.Track track = trackedTrack;
        MessageObject mo = trackedMo;
        String source = trackedSource;
        String recId = trackedRecId;
        float progress = Math.max(lastProgress, mo != null ? mo.audioProgress : 0f);
        trackedMo = null;
        trackedTrack = null;
        JSONObject payload = basePayloadFor(source, recId);
        try {
            payload.put("played_ms", (long) (progress * track.durationS * 1000L));
            payload.put("duration_ms", track.durationS * 1000L);
        } catch (Exception e) {
            FileLog.e(e);
        }
        SvipeMusic.sendEvent(account, track, progress >= 0.85f ? "TRACK_END" : "SKIP", payload);
    }

    private JSONObject basePayload() {
        return basePayloadFor(trackedSource, trackedRecId);
    }

    private static JSONObject basePayloadFor(String source, String recId) {
        JSONObject payload = new JSONObject();
        try {
            if (source != null) {
                payload.put("source", source);
            }
            if (recId != null) {
                payload.put("rec_id", recId);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return payload;
    }
}
