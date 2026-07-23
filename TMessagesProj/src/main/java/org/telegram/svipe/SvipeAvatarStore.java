package org.telegram.svipe;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Local ledger of every profile photo we have ever *seen* for a user, so a photo the user later
 * deletes server-side can still be shown in the profile "Rasmlar" tab. Identity is the Telegram
 * photo_id; each record carries the photo's own {@code date} (when it was set) so the tab can list
 * photos "in the order they were put up", plus our first-seen timestamp for tie-breaking.
 *
 * Deletion is NOT stored here: the tab derives it at display time as (captured set) − (live current
 * set from photos.getUserPhotos). This keeps capture a pure append and avoids false positives from
 * paginated photo loads.
 *
 * Metadata lives as one small JSON array per user under a dedicated prefs file (keyed by userId, so
 * viewing one profile never rewrites another's blob). The actual image bytes are copied by
 * {@link SvipeAvatarKeeper} into {@link #dir()} under {@code getFilesDirFixed}, which survives every
 * in-app cache clear. userId is global across Telegram accounts, so this store is a single global
 * singleton rather than per-account.
 */
public class SvipeAvatarStore {

    /** One captured profile photo. */
    public static class Photo {
        public final long photoId;
        public final int date;        // TL_photo.date — when the user set this photo (unix seconds)
        public final long capturedAt; // when we first recorded it (ms), for tie-breaking

        public Photo(long photoId, int date, long capturedAt) {
            this.photoId = photoId;
            this.date = date;
            this.capturedAt = capturedAt;
        }
    }

    private static final String PREFS = "svipe_avatars";
    private static final String DIR = "svipe_avatars";
    private static final int MAX_PER_USER = 60; // generous cap; a user rarely has this many avatars

    // ---- pure list operations (JVM-testable, no Android) ----

    /** Insert if {@code photoId} is not already present. Returns true iff a new entry was added. */
    public static boolean upsert(List<Photo> list, long photoId, int date, long now) {
        for (Photo p : list) {
            if (p.photoId == photoId) return false;
        }
        list.add(new Photo(photoId, date, now));
        return true;
    }

    /**
     * Order "in the order photos were put up" — newest set first (matches a photo gallery), stable
     * tie-break by capture time then id so the grid never reshuffles between reads.
     */
    public static void sortBySetOrder(List<Photo> list) {
        Collections.sort(list, new Comparator<Photo>() {
            @Override
            public int compare(Photo a, Photo b) {
                if (a.date != b.date) return Integer.compare(b.date, a.date);
                if (a.capturedAt != b.capturedAt) return Long.compare(b.capturedAt, a.capturedAt);
                return Long.compare(b.photoId, a.photoId);
            }
        });
    }

    /** Drop the oldest-captured entries once over the per-user cap. Operates in place. */
    public static void trim(List<Photo> list, int maxPerUser) {
        if (list.size() <= maxPerUser) return;
        Collections.sort(list, new Comparator<Photo>() {
            @Override
            public int compare(Photo a, Photo b) {
                return Long.compare(a.capturedAt, b.capturedAt); // oldest first
            }
        });
        while (list.size() > maxPerUser) {
            list.remove(0);
        }
    }

    // ---- singleton + prefs/file shell ----

    private static volatile SvipeAvatarStore instance;

    public static SvipeAvatarStore getInstance() {
        SvipeAvatarStore local = instance;
        if (local == null) {
            synchronized (SvipeAvatarStore.class) {
                local = instance;
                if (local == null) {
                    local = new SvipeAvatarStore();
                    instance = local;
                }
            }
        }
        return local;
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String keyFor(long userId) {
        return "u" + userId;
    }

    /** Permanent directory for the copied image bytes (safe from every in-app cache clear). */
    public File dir() {
        return ApplicationLoader.getFilesDirFixed(DIR);
    }

    public File fileFor(long userId, long photoId) {
        return new File(dir(), userId + "_" + photoId + ".jpg");
    }

    public boolean hasFile(long userId, long photoId) {
        File f = fileFor(userId, photoId);
        return f.exists() && f.length() > 0;
    }

    /**
     * Record that we have seen {@code photoId} for {@code userId}. Returns true iff it was new (so the
     * caller knows to persist the image bytes). Thread-safe.
     */
    public synchronized boolean record(long userId, long photoId, int date) {
        if (userId <= 0 || photoId == 0) return false;
        List<Photo> list = loadUser(userId);
        boolean isNew = upsert(list, photoId, date, System.currentTimeMillis());
        if (isNew) {
            trim(list, MAX_PER_USER);
            saveUser(userId, list);
        }
        return isNew;
    }

    /** All captured photos for a user, ordered by {@link #sortBySetOrder}. */
    public synchronized List<Photo> getForUser(long userId) {
        List<Photo> list = loadUser(userId);
        sortBySetOrder(list);
        return list;
    }

    private List<Photo> loadUser(long userId) {
        ArrayList<Photo> list = new ArrayList<>();
        try {
            String blob = prefs().getString(keyFor(userId), null);
            if (blob == null || blob.isEmpty()) return list;
            JSONArray arr = new JSONArray(blob);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                long id = o.optLong("id");
                if (id == 0) continue;
                list.add(new Photo(id, o.optInt("d"), o.optLong("c")));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return list;
    }

    private void saveUser(long userId, List<Photo> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Photo p : list) {
                JSONObject o = new JSONObject();
                o.put("id", p.photoId);
                o.put("d", p.date);
                o.put("c", p.capturedAt);
                arr.put(o);
            }
            prefs().edit().putString(keyFor(userId), arr.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
