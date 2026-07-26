package org.telegram.svipe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes what {@link SvipeAvatarKeeper} captured locally into the shared archive, so a profile
 * photo one Svipe user caught before it was deleted can be recovered by anyone else allowed to see
 * that person. Phase 2 of docs/svipe-avatar-sync-plan.md — the capture half; reading the pool back
 * comes next.
 *
 * Every profile view reports two things to {@code POST /v1/avatars/observed}: which photo ids are
 * LIVE right now (this is what later proves to the server that a viewer may see this person at all)
 * and which captured ids have GONE. The server answers with the deleted ones it does not hold yet,
 * and only those are uploaded — straight to storage through a presigned URL, never through the API.
 *
 * Two rules keep it from lying or being a nuisance:
 *
 * <p><b>Never guess a deletion.</b> Telegram delivers the photo list in pages, and the model keeps
 * {@code null} placeholders for pages it has not fetched. A missing id in a partial list means "not
 * loaded yet", not "deleted" — so deletions are only reported when the live set is provably complete
 * ({@link #liveSetComplete}). The tab may render an unconfirmed guess; an upload may not.
 *
 * <p><b>Report rarely, upload politely.</b> A per-subject signature + interval means reopening the
 * same profile is silent, while an actual change reports immediately; uploads are Wi-Fi-only by
 * default, capped per visit, and chained one at a time.
 */
public class SvipeAvatarSync {

    /** Don't re-report an unchanged profile more often than this. */
    static final long MIN_REPORT_INTERVAL_MS = 6L * 60 * 60 * 1000;
    /** Photos uploaded per profile view — a backlog drains over several visits instead of one burst. */
    static final int MAX_UPLOADS_PER_VISIT = 3;
    /** Archived photos pulled back per profile view. */
    static final int MAX_DOWNLOADS_PER_VISIT = 5;
    /** Matches the server's per-photo cap; a bigger file would only be rejected after we sent it. */
    static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;

    // ---- pure logic (JVM-testable, no Android) ----

    /**
     * Is the live photo list complete enough to conclude that a captured photo was deleted?
     *
     * <p>{@code slots} is the model's list length (it is pre-sized to the server's total count, with
     * nulls for unfetched pages) and {@code loadedPhotos} how many of those are real. They match only
     * when every page has arrived; a cache-only or still-loading list can never justify an upload.
     */
    static boolean liveSetComplete(int slots, int loadedPhotos, boolean loaded, boolean fromCache) {
        return loaded && !fromCache && slots > 0 && slots == loadedPhotos;
    }

    /** Captured ids that are no longer live — the deletions we report. */
    static ArrayList<Long> deletedIds(List<SvipeAvatarStore.Photo> captured, Set<Long> liveIds) {
        ArrayList<Long> gone = new ArrayList<>();
        if (captured == null) {
            return gone;
        }
        for (SvipeAvatarStore.Photo p : captured) {
            if (p != null && p.photoId != 0 && !liveIds.contains(p.photoId)) {
                gone.add(p.photoId);
            }
        }
        return gone;
    }

    /**
     * What this profile view is claiming. Order-independent (the model reorders pages freely), so an
     * identical profile produces an identical signature and stays silent until something really moves.
     */
    static String signature(Set<Long> liveIds, List<Long> deletedIds) {
        long liveSum = 0, liveXor = 0, goneSum = 0, goneXor = 0;
        for (Long id : liveIds) {
            liveSum += id;
            liveXor ^= id;
        }
        for (Long id : deletedIds) {
            goneSum += id;
            goneXor ^= id;
        }
        return liveIds.size() + ":" + liveSum + ":" + liveXor + "|"
                + deletedIds.size() + ":" + goneSum + ":" + goneXor;
    }

    /** Report when something changed, or when the last report has aged out. */
    static boolean shouldReport(String lastSignature, String signature, long lastAtMs, long nowMs,
                                long minIntervalMs) {
        if (lastSignature == null || !lastSignature.equals(signature)) {
            return true;
        }
        return nowMs - lastAtMs >= minIntervalMs;
    }

    /**
     * Of the ids the server still wants, the ones we can actually supply: a photo we never persisted
     * (capture happened before the bytes landed, or the file was evicted) is skipped rather than
     * retried forever.
     */
    static ArrayList<Long> pickUploads(List<Long> missing, Set<Long> haveLocalFile, int max) {
        ArrayList<Long> out = new ArrayList<>();
        if (missing == null) {
            return out;
        }
        for (Long id : missing) {
            if (id != null && id != 0 && haveLocalFile.contains(id)) {
                out.add(id);
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return out;
    }

    /** Archived ids the pool offers that this device cannot render yet — everything else is a no-op. */
    static ArrayList<Long> pickDownloads(List<Long> archived, Set<Long> haveLocalFile, int max) {
        ArrayList<Long> out = new ArrayList<>();
        if (archived == null) {
            return out;
        }
        for (Long id : archived) {
            if (id != null && id != 0 && !haveLocalFile.contains(id)) {
                out.add(id);
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return out;
    }

    // ---- Android glue ----

    // Per-subject memory of what we last told the server, so reopening a profile is free.
    private static final ConcurrentHashMap<Long, String> lastSignature = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> lastReportAt = new ConcurrentHashMap<>();
    // Photos whose upload is running or failed this session — never attempted twice in a row.
    private static final Set<Long> attempted = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Called on the UI thread from {@link SvipeAvatarKeeper} once a profile's photo list has been seen.
     * Snapshots the live set here (the model is UI-thread owned) and does everything else off it.
     */
    public static void onProfileSeen(int account, long userId) {
        if (userId <= 0 || !SvipeConfig.isAvatarSyncEnabled(account)) {
            return;
        }
        final HashSet<Long> live = new HashSet<>();
        int slots = 0;
        boolean loaded = false, fromCache = true;
        try {
            MessagesController.DialogPhotos dp = MessagesController.getInstance(account).getDialogPhotos(userId);
            if (dp == null) {
                return;
            }
            loaded = dp.loaded;
            fromCache = dp.fromCache;
            ArrayList<TLRPC.Photo> snapshot = new ArrayList<>(dp.photos);
            slots = snapshot.size();
            for (TLRPC.Photo photo : snapshot) {
                if (photo instanceof TLRPC.TL_photo && photo.id != 0) {
                    live.add(photo.id);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        final boolean complete = liveSetComplete(slots, live.size(), loaded, fromCache);
        Utilities.globalQueue.postRunnable(() -> report(account, userId, live, complete));
    }

    private static void report(int account, long userId, HashSet<Long> live, boolean complete) {
        try {
            List<SvipeAvatarStore.Photo> captured = SvipeAvatarStore.getInstance().getForUser(userId);
            // A partial list is not evidence of deletion — report the live ids only and wait.
            ArrayList<Long> gone = complete ? deletedIds(captured, live) : new ArrayList<>();
            if (live.isEmpty() && gone.isEmpty()) {
                return;
            }
            String sig = signature(live, gone);
            long now = System.currentTimeMillis();
            if (!shouldReport(lastSignature.get(userId), sig, orZero(lastReportAt.get(userId)), now,
                    MIN_REPORT_INTERVAL_MS)) {
                return;
            }
            lastSignature.put(userId, sig);
            lastReportAt.put(userId, now);

            HashMap<Long, Integer> dates = new HashMap<>();
            for (SvipeAvatarStore.Photo p : captured) {
                dates.put(p.photoId, p.date);
            }
            JSONObject body = new JSONObject();
            body.put("subject_tg_id", userId);
            body.put("live_photo_ids", new JSONArray(new ArrayList<>(live)));
            JSONArray deleted = new JSONArray();
            for (Long id : gone) {
                JSONObject o = new JSONObject();
                o.put("photo_id", id);
                Integer d = dates.get(id);
                if (d != null && d != 0) {
                    o.put("photo_date", d);
                }
                deleted.put(o);
            }
            body.put("deleted", deleted);
            // Any live id doubles as the proof that Telegram shows this person to us — see fetchArchive.
            long proof = live.isEmpty() ? 0 : live.iterator().next();
            postObserved(account, userId, proof, body, false);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void postObserved(int account, long userId, long proofPhotoId, JSONObject body,
                                     boolean retried) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                return;
            }
            SvipeApi.post("/v1/avatars/observed", body, token, (res, code, err) -> {
                if (code == 401 && !retried) {
                    SvipeAuth.invalidateAccessToken(account);
                    postObserved(account, userId, proofPhotoId, body, true);
                    return;
                }
                if (res == null || code < 200 || code >= 300) {
                    // Let the next profile view try again rather than burning a report window.
                    lastSignature.remove(userId);
                    return;
                }
                // Read the pool back for this person. Deliberately after the report above: the live
                // ids we just sent are what makes our own proof verifiable server-side.
                fetchArchive(account, userId, proofPhotoId, token);
                JSONArray missing = res.optJSONArray("missing");
                if (missing == null || missing.length() == 0 || !res.optBoolean("upload_enabled", false)) {
                    return;
                }
                if (SvipeConfig.isAvatarSyncWifiOnly(account) && !ApplicationLoader.isConnectedToWiFi()) {
                    // The live-set report above already went out; the bytes can wait for Wi-Fi.
                    lastSignature.remove(userId);
                    return;
                }
                ArrayList<Long> ids = new ArrayList<>();
                for (int i = 0; i < missing.length(); i++) {
                    long id = missing.optLong(i);
                    if (id != 0 && !attempted.contains(id)) {
                        ids.add(id);
                    }
                }
                Utilities.globalQueue.postRunnable(() -> uploadNext(account, userId, filterToLocal(userId, ids), 0));
            });
        });
    }

    /**
     * Pull this person's archived photos out of the shared pool into the local store, so the profile
     * "Profile Images" tab shows them exactly like the ones this device captured itself.
     *
     * <p>The request carries a photo id the caller's own Telegram client is showing right now. That is
     * the proof the server checks: only someone Telegram lets see this person could have obtained it.
     * With no live photo there is no proof, so we do not even ask — except for our own archive, which
     * needs none (and is how a fresh install gets its own deleted photos back).
     */
    private static void fetchArchive(int account, long userId, long proofPhotoId, String token) {
        boolean self = userId == UserConfig.getInstance(account).getClientUserId();
        if (proofPhotoId == 0 && !self) {
            return;
        }
        String path = "/v1/avatars/" + userId + (proofPhotoId != 0 ? "?proof_photo_id=" + proofPhotoId : "");
        SvipeApi.get(path, token, (res, code, err) -> {
            // 403 is the normal answer for "Telegram would not show you this person" — nothing to do.
            if (res == null || code < 200 || code >= 300) {
                return;
            }
            JSONArray photos = res.optJSONArray("photos");
            if (photos == null || photos.length() == 0) {
                return;
            }
            ArrayList<Long> ids = new ArrayList<>();
            HashMap<Long, String> urls = new HashMap<>();
            HashMap<Long, Integer> dates = new HashMap<>();
            for (int i = 0; i < photos.length(); i++) {
                JSONObject o = photos.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                long id = o.optLong("photo_id");
                String url = o.optString("url", null);
                if (id == 0 || url == null || url.isEmpty()) {
                    continue;
                }
                ids.add(id);
                urls.put(id, url);
                dates.put(id, o.optInt("photo_date"));
            }
            Utilities.globalQueue.postRunnable(() -> {
                HashSet<Long> have = new HashSet<>();
                for (Long id : ids) {
                    if (SvipeAvatarStore.getInstance().hasFile(userId, id)) {
                        have.add(id);
                    }
                }
                downloadNext(account, userId, pickDownloads(ids, have, MAX_DOWNLOADS_PER_VISIT),
                        urls, dates, 0, false);
            });
        });
    }

    /** One at a time, and the tab is told only once, after the batch — not per photo. */
    private static void downloadNext(int account, long userId, ArrayList<Long> ids,
                                     HashMap<Long, String> urls, HashMap<Long, Integer> dates,
                                     int index, boolean anyArrived) {
        if (ids == null || index >= ids.size()) {
            if (anyArrived) {
                AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account)
                        .postNotificationName(NotificationCenter.svipeAvatarArchiveUpdated, userId));
            }
            return;
        }
        final long photoId = ids.get(index);
        final File dest = SvipeAvatarStore.getInstance().fileFor(userId, photoId);
        SvipeApi.getFile(urls.get(photoId), dest, (code, err) -> {
            boolean ok = code >= 200 && code < 300 && dest.exists() && dest.length() > 0;
            if (ok) {
                // Same ledger the local capture writes to, so the tab needs no notion of "remote".
                Integer d = dates.get(photoId);
                SvipeAvatarStore.getInstance().record(userId, photoId, d == null ? 0 : d);
            }
            final boolean arrived = anyArrived || ok;
            Utilities.globalQueue.postRunnable(() ->
                    downloadNext(account, userId, ids, urls, dates, index + 1, arrived));
        });
    }

    private static ArrayList<Long> filterToLocal(long userId, ArrayList<Long> ids) {
        HashSet<Long> have = new HashSet<>();
        for (Long id : ids) {
            File f = SvipeAvatarStore.getInstance().fileFor(userId, id);
            if (f != null && f.exists() && f.length() > 0 && f.length() <= MAX_UPLOAD_BYTES) {
                have.add(id);
            }
        }
        return pickUploads(ids, have, MAX_UPLOADS_PER_VISIT);
    }

    /** Uploads one photo and only then starts the next — polite with battery and bandwidth. */
    private static void uploadNext(int account, long userId, ArrayList<Long> ids, int index) {
        if (ids == null || index >= ids.size()) {
            return;
        }
        final long photoId = ids.get(index);
        final File file = SvipeAvatarStore.getInstance().fileFor(userId, photoId);
        if (file == null || !file.exists() || file.length() == 0) {
            uploadNext(account, userId, ids, index + 1);
            return;
        }
        attempted.add(photoId);
        final String sha = sha256(file);
        final long size = file.length();
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                return;
            }
            JSONObject body = new JSONObject();
            try {
                body.put("subject_tg_id", userId);
                body.put("photo_id", photoId);
                body.put("bytes", size);
                if (sha != null) {
                    body.put("sha256", sha);
                }
            } catch (Exception e) {
                FileLog.e(e);
                return;
            }
            SvipeApi.post("/v1/avatars/upload-url", body, token, (res, code, err) -> {
                if (res == null || code < 200 || code >= 300) {
                    // 403/429 mean the subject opted out or we hit a quota — either way, stop here.
                    return;
                }
                if (res.optBoolean("already_stored", false)) {
                    Utilities.globalQueue.postRunnable(() -> uploadNext(account, userId, ids, index + 1));
                    return;
                }
                String url = res.optString("url", null);
                if (url == null || url.isEmpty()) {
                    return;
                }
                SvipeApi.putFile(url, file, "image/jpeg", (putCode, putErr) -> {
                    if (putCode < 200 || putCode >= 300) {
                        return;
                    }
                    commit(account, userId, photoId, sha, token, () ->
                            Utilities.globalQueue.postRunnable(() -> uploadNext(account, userId, ids, index + 1)));
                });
            });
        });
    }

    private static void commit(int account, long userId, long photoId, String sha, String token,
                               Runnable then) {
        JSONObject body = new JSONObject();
        try {
            body.put("subject_tg_id", userId);
            body.put("photo_id", photoId);
            if (sha != null) {
                body.put("sha256", sha);
            }
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        SvipeApi.post("/v1/avatars/commit", body, token, (res, code, err) -> {
            if (then != null) {
                then.run();
            }
        });
    }

    /** Content hash sent with the upload: the server pins it to the first uploader, so a later, different
     *  upload for the same photo id is refused instead of quietly replacing the real photo. */
    static String sha256(File file) {
        FileInputStream in = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(file);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignore) {}
            }
        }
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
