package org.telegram.svipe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One stored favourite: its {@link SvipeFavKey} identity plus everything needed to draw the row and to
 * play it again later.
 *
 * <p>Title/artist are cached for DISPLAY ONLY — {@code MessageObject.getMusicTitle()} falls back to a
 * localized "Unknown title", so it can never be part of the identity.
 *
 * <p>Pure JVM (org.json is available on the JVM test classpath) so serialization is unit-testable.
 */
public final class SvipeFavourite {

    private static final int BLOB_VERSION = 1;

    public String key;
    public int kind;
    public long songId;
    public long channelId;
    public int messageId;
    public long documentId;
    public String username;     // public channel handle, for re-resolving; null when private
    public long dialogId;       // where to re-fetch a private copy from
    public String title;
    public String artist;
    public int durationS;
    public boolean isPublic;
    public long addedAt;        // epoch ms — list order (newest first)

    public static SvipeFavourite of(SvipeFavKey k) {
        SvipeFavourite f = new SvipeFavourite();
        f.key = k.key;
        f.kind = k.kind;
        f.songId = k.songId;
        f.channelId = k.channelId;
        f.messageId = k.messageId;
        f.documentId = k.documentId;
        return f;
    }

    /** True when this entry may be mirrored to the backend (catalog songs only). */
    public boolean isSyncable() {
        return kind == SvipeFavKey.KIND_SONG && songId > 0;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("key", key);
            o.put("kind", kind);
            o.put("song_id", songId);
            o.put("channel_id", channelId);
            o.put("message_id", messageId);
            o.put("document_id", documentId);
            if (username != null) o.put("username", username);
            o.put("dialog_id", dialogId);
            if (title != null) o.put("title", title);
            if (artist != null) o.put("artist", artist);
            o.put("duration_s", durationS);
            o.put("is_public", isPublic);
            o.put("added_at", addedAt);
        } catch (Exception ignore) {
            // JSONObject.put only throws on NaN/Infinity keys, none of which occur here.
        }
        return o;
    }

    /** Returns null for anything unusable, so one corrupt entry can never take the whole list down. */
    public static SvipeFavourite fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        String key = o.optString("key", null);
        if (key == null || key.isEmpty()) {
            return null;
        }
        SvipeFavourite f = new SvipeFavourite();
        f.key = key;
        f.kind = o.optInt("kind");
        f.songId = o.optLong("song_id");
        f.channelId = o.optLong("channel_id");
        f.messageId = o.optInt("message_id");
        f.documentId = o.optLong("document_id");
        f.username = o.isNull("username") ? null : o.optString("username", null);
        f.dialogId = o.optLong("dialog_id");
        f.title = o.isNull("title") ? null : o.optString("title", null);
        f.artist = o.isNull("artist") ? null : o.optString("artist", null);
        f.durationS = o.optInt("duration_s");
        f.isPublic = o.optBoolean("is_public", false);
        f.addedAt = o.optLong("added_at");
        return f;
    }

    /** {@code {"v":1,"items":[...]}} — versioned so the shape can change later without a crash. */
    public static String serialize(List<SvipeFavourite> items) {
        JSONObject root = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (SvipeFavourite f : items) {
                if (f != null && f.key != null) {
                    arr.put(f.toJson());
                }
            }
            root.put("v", BLOB_VERSION);
            root.put("items", arr);
        } catch (Exception ignore) {
        }
        return root.toString();
    }

    /** Never throws: a malformed blob yields an empty list rather than losing the app. */
    public static List<SvipeFavourite> deserialize(String blob) {
        ArrayList<SvipeFavourite> out = new ArrayList<>();
        if (blob == null || blob.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONObject(blob).optJSONArray("items");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                SvipeFavourite f = fromJson(arr.optJSONObject(i));
                if (f != null) {
                    out.add(f);
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }
}
