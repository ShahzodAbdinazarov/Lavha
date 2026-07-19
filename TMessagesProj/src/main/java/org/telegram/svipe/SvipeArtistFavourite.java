package org.telegram.svipe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One stored favourite singer: the catalog artist id plus everything needed to draw the row without a
 * network round-trip.
 *
 * <p>Simpler than {@link SvipeFavourite} on purpose. A song can be favourited in three different shapes
 * (catalog song / public post / private document), so it needs {@link SvipeFavKey}; an artist only ever
 * exists as a catalog row, so the {@code long} artist id IS the identity and every entry is syncable.
 *
 * <p>Name/photo are cached for DISPLAY ONLY and are refreshed from the server on every sync — Deezer
 * enrichment can land after we stored the entry, so the cached copy must never be treated as truth.
 *
 * <p>Pure JVM (org.json only, no Android imports) so serialization is unit-testable.
 */
public final class SvipeArtistFavourite {

    private static final int BLOB_VERSION = 1;

    public long artistId;
    public String name;         // canonical, tag-derived name
    public String displayName;  // enriched real name, null/empty when the artist was never enriched
    public String photoUrl;     // Deezer photo hotlink, null when there is none
    public int songCount;
    public long artChannelId;   // Telegram post to pull cover art from when there is no photoUrl
    public int artMessageId;
    public long addedAt;        // epoch ms — list order (newest first)

    /** Real name when the artist was enriched, else the canonical (tag-derived) name. */
    public String shownName() {
        return displayName != null && !displayName.isEmpty() ? displayName : name;
    }

    public static SvipeArtistFavourite of(SvipeMusic.Artist a) {
        if (a == null) {
            return null;
        }
        SvipeArtistFavourite f = new SvipeArtistFavourite();
        f.artistId = a.id;
        f.name = a.name;
        f.displayName = a.displayName;
        f.photoUrl = a.photoUrl;
        f.songCount = a.songCount;
        f.artChannelId = a.artChannelId;
        f.artMessageId = a.artMessageId;
        return f;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("artist_id", artistId);
            if (name != null) o.put("name", name);
            if (displayName != null) o.put("display_name", displayName);
            if (photoUrl != null) o.put("photo_url", photoUrl);
            o.put("song_count", songCount);
            o.put("art_channel_id", artChannelId);
            o.put("art_message_id", artMessageId);
            o.put("added_at", addedAt);
        } catch (Exception ignore) {
            // JSONObject.put only throws on NaN/Infinity values, none of which occur here.
        }
        return o;
    }

    /** Returns null for anything unusable, so one corrupt entry can never take the whole list down. */
    public static SvipeArtistFavourite fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        long artistId = o.optLong("artist_id");
        if (artistId <= 0) {
            return null;    // without an id there is nothing to sync, render or un-favourite
        }
        SvipeArtistFavourite f = new SvipeArtistFavourite();
        f.artistId = artistId;
        f.name = o.isNull("name") ? null : o.optString("name", null);
        f.displayName = o.isNull("display_name") ? null : o.optString("display_name", null);
        f.photoUrl = o.isNull("photo_url") ? null : o.optString("photo_url", null);
        f.songCount = o.optInt("song_count");
        f.artChannelId = o.optLong("art_channel_id");
        f.artMessageId = o.optInt("art_message_id");
        f.addedAt = o.optLong("added_at");
        return f;
    }

    /** {@code {"v":1,"items":[...]}} — versioned so the shape can change later without a crash. */
    public static String serialize(List<SvipeArtistFavourite> items) {
        JSONObject root = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (SvipeArtistFavourite f : items) {
                if (f != null && f.artistId > 0) {
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
    public static List<SvipeArtistFavourite> deserialize(String blob) {
        ArrayList<SvipeArtistFavourite> out = new ArrayList<>();
        if (blob == null || blob.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONObject(blob).optJSONArray("items");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                SvipeArtistFavourite f = fromJson(arr.optJSONObject(i));
                if (f != null) {
                    out.add(f);
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }
}
