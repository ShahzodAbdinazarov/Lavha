package org.telegram.svipe.video;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.VideoPlayer;

import java.util.ArrayList;

/**
 * Telegram ABR ladder (multi-quality) helpers, shared by the reels player and the long-form player.
 *
 * Popular channels' videos arrive with server-made renditions (480/720/1080) + per-rendition HLS
 * manifests in media.alt_documents. Playing through VideoPlayer's qualities path gives
 * Instagram-style adaptive streaming: ExoPlayer picks a rung the CURRENT bandwidth can sustain and
 * switches mid-play, so a slowing connection degrades quality instead of buffering.
 *
 * Extracted verbatim from ReelsActivity so the long-form player streams, prioritises, cancels and
 * budgets bytes by exactly the same rules — a second implementation of the reference/cache-flag
 * discipline below is how the two players would silently drift apart.
 */
public class SvipeVideoLadder {

    /**
     * Rendition cap for a phone-sized surface (a reel tile, an inline watch-page player): the
     * highest rung at or below this is sharp at roughly half the bytes of a 1080p source.
     */
    public static final int MAX_P_PHONE = 720;

    /**
     * Rendition cap for a long-form player filling the whole screen in landscape, where 720p on a
     * modern display is visibly soft. Only ever a cap — {@link #targetRendition} still prefers a
     * rung already on disk and never downloads a second copy of the same video.
     */
    public static final int MAX_P_FULLSCREEN = 1080;

    private SvipeVideoLadder() {}

    /**
     * The quality ladder for a video, or null when the message carries none (small channels) —
     * callers then use the legacy single-document path. Recomputed per call: VideoUri cached-flags
     * are resolved at build time, so a fresh call sees files downloaded since the last one.
     *
     * reference=0: inspection only (doc ids, sizes, cached-flags for priorities / cancels / budget /
     * presence). A real MTProto file reference is minted ONLY on the playback path
     * ({@link #playbackQualitiesFor}), because {@link FileLoader#getFileReference} inserts into a
     * process-lifetime map that is never pruned — calling it in the per-swipe inspection loops would
     * leak one MessageObject-retaining entry per call.
     */
    public static ArrayList<VideoPlayer.Quality> qualitiesFor(int account, MessageObject mo) {
        return buildQualities(account, mo, 0);
    }

    /** Ladder built with a live file reference embedded in each stream URI — for preparePlayer only. */
    public static ArrayList<VideoPlayer.Quality> playbackQualitiesFor(int account, MessageObject mo) {
        return buildQualities(account, mo, FileLoader.getInstance(account).getFileReference(mo));
    }

    public static ArrayList<VideoPlayer.Quality> buildQualities(int account, MessageObject mo, int reference) {
        if (mo == null || mo.messageOwner == null) return null;
        TLRPC.MessageMedia media = mo.messageOwner.media;
        if (!(media instanceof TLRPC.TL_messageMediaDocument) || media.alt_documents.isEmpty()) return null;
        try {
            // useFileDatabaseQueue=false — same flag the legacy VideoUri.of path uses here, so
            // "cached" means exactly "the player can open it from disk right now".
            ArrayList<VideoPlayer.Quality> q = VideoPlayer.getQualities(
                    account, media.document, media.alt_documents, reference, false, false);
            return q == null || q.isEmpty() ? null : q;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Every doc a video's playback touches — video rungs AND their HLS manifests — for cancels. */
    public static ArrayList<TLRPC.Document> ladderDocsWithManifests(int account, MessageObject mo) {
        ArrayList<VideoPlayer.Quality> qs = qualitiesFor(account, mo);
        ArrayList<TLRPC.Document> docs = ladderVideoDocs(qs);
        if (qs != null) {
            for (VideoPlayer.Quality q : qs) {
                for (VideoPlayer.VideoUri u : q.uris) {
                    if (u.manifestDocument != null) docs.add(u.manifestDocument);
                }
            }
        }
        return docs;
    }

    /** Every playable rendition document of the ladder — the unit for priorities and cancels. */
    public static ArrayList<TLRPC.Document> ladderVideoDocs(ArrayList<VideoPlayer.Quality> qualities) {
        ArrayList<TLRPC.Document> docs = new ArrayList<>();
        if (qualities == null) return docs;
        for (VideoPlayer.Quality q : qualities) {
            for (VideoPlayer.VideoUri u : q.uris) {
                if (u.document != null) docs.add(u.document);
            }
        }
        return docs;
    }

    /** {@link #targetRendition(ArrayList, int)} at the phone cap — the reels/offline-queue rule. */
    public static VideoPlayer.VideoUri targetRendition(ArrayList<VideoPlayer.Quality> qualities) {
        return targetRendition(qualities, MAX_P_PHONE);
    }

    /**
     * The ONE file a video is stored as (offline queue, Wi-Fi top-ups, dwell escalation, an explicit
     * Download tap): a rendition already on disk if any (never download a second copy of the same
     * video), else the highest rung at or below {@code maxP} — sharp on the surface it plays on at a
     * fraction of the source bytes — preferring the smaller file inside a rung (the more efficient
     * codec), else the smallest rung available.
     */
    public static VideoPlayer.VideoUri targetRendition(ArrayList<VideoPlayer.Quality> qualities, int maxP) {
        if (qualities == null) return null;
        VideoPlayer.VideoUri best = null, smallest = null;
        for (VideoPlayer.Quality q : qualities) {
            for (VideoPlayer.VideoUri u : q.uris) {
                if (u.document == null) continue;
                if (u.isCached()) return u;
                if (smallest == null || u.size < smallest.size) smallest = u;
                int p = Math.min(u.width, u.height);
                if (p <= maxP + 55) { // the same rung tolerance Quality.p() uses
                    int bp = best == null ? 0 : Math.min(best.width, best.height);
                    if (best == null || bp < p || (bp == p && u.size < best.size)) {
                        best = u;
                    }
                }
            }
        }
        return best != null ? best : smallest;
    }

    /** The Quality wrapping a fully-cached rendition (pin it -> plays from disk, works offline), or null for AUTO. */
    public static VideoPlayer.Quality cachedQualityOf(ArrayList<VideoPlayer.Quality> qualities) {
        if (qualities == null) return null;
        for (VideoPlayer.Quality q : qualities) {
            for (VideoPlayer.VideoUri u : q.uris) {
                if (u.isCached()) return q;
            }
        }
        return null;
    }

    /** Width/height are known from the document long before the first frame — no layout jump. */
    public static float videoAspect(TLRPC.Document doc) {
        if (doc == null) return 0f;
        for (int i = 0; i < doc.attributes.size(); i++) {
            TLRPC.DocumentAttribute a = doc.attributes.get(i);
            if (a instanceof TLRPC.TL_documentAttributeVideo && a.h > 0) {
                return (float) a.w / a.h;
            }
        }
        return 0f;
    }

    /**
     * Anything at least this long is treated as LONG-FORM rather than a reel.
     *
     * MUST stay in sync with the server's ``longform_min_duration_ms`` (svipe-backend
     * app/config.py) — that is the floor of the long-form pipe, so it is exactly the shortest clip
     * that can reach the long-form player from the Video tab. It was 5 min here while the server's
     * floor was 3, which let every 3-5 minute horizontal video escape all three reels guards: it
     * looped, it got a full cacheType-0 pull after 3 seconds of dwell, and it was persisted into the
     * 600 MB offline reels cushion.
     */
    public static final long LONG_FORM_MIN_DURATION_MS = 3 * 60 * 1000L;

    /**
     * Long-form guard. Three reels behaviours are actively harmful for a 40-minute video and are
     * switched off for these items:
     *   - looping (a lecture must end, not restart);
     *   - the "dwelled past MIN_WATCHED_MS -> pull the whole file" escalation in ReelsActivity's
     *     startPlayback (3 seconds of glancing would fetch hundreds of MB);
     *   - offline-queue persistence, since one such file exceeds a large slice of the whole
     *     {@link org.telegram.svipe.SvipeQueuePlan#MAX_QUEUE_BYTES} cushion and would evict every
     *     cached reel.
     * Streaming playback and all watch telemetry are unaffected.
     */
    public static boolean isLongForm(TLRPC.Document doc) {
        if (doc == null) return false;
        for (int i = 0; i < doc.attributes.size(); i++) {
            TLRPC.DocumentAttribute a = doc.attributes.get(i);
            if (a instanceof TLRPC.TL_documentAttributeVideo) {
                return (long) (a.duration * 1000.0) >= LONG_FORM_MIN_DURATION_MS;
            }
        }
        return false;
    }

    /**
     * Whether this video should loop: the user's explicit per-message choice when they made one
     * (the loop toggle persists through {@link VideoPlayer#saveLooping}), else the long-form guard —
     * a reel loops, a lecture ends.
     */
    public static boolean savedLoop(MessageObject mo) {
        if (mo == null) return false;
        Boolean saved = VideoPlayer.getLooping(mo);
        if (saved != null) return saved;
        return !isLongForm(mo.getDocument());
    }
}
