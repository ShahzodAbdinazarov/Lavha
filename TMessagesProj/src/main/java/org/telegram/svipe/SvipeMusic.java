package org.telegram.svipe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the music catalog endpoints (GET /v1/music/*). Same reference-only contract as the
 * reels feed: every track is a Telegram post reference (channel_id, message_id, username) plus
 * metadata (title/performer/duration) so lists render without resolving each row; the audio bytes
 * flow through Telegram's own FileLoader once the client resolves the message. Mirrors
 * SvipeDiscover's auth + 401-retry idiom.
 */
public class SvipeMusic {

    public static class Track {
        public long channelId;
        public int messageId;
        public String username;
        public String title;
        public String performer;
        public int durationS;
        public long size;
        public boolean hasThumb;
        public long songId;   // canonical song this track maps to; 0 = not canonicalized (no deep-link)

        public String key() {
            return channelId + ":" + messageId;
        }
    }

    public static class Section {
        public String key;
        public String title;
        public final ArrayList<Track> tracks = new ArrayList<>();
    }

    // ---------------- Canonical layer (Zona-style songs / artists) ----------------
    public static class Artist {
        public long id;
        public String name;
        public String role = "primary";     // "primary" | "featured"
        public int songCount;               // how many canonical songs this artist has
        public long artChannelId;
        public int artMessageId;
        // Deezer enrichment overlay — carried on the artist PAGE only (a chip on a song does not have
        // it). null/empty -> fall back to the canonical name and the initials tile.
        public String displayName;          // real name from one of the artist's enriched songs
        public String photoUrl;             // Deezer artist photo (xl) hotlink

        /** Real name when the artist was enriched, else the canonical (tag-derived) name. */
        public String shownName() {
            return displayName != null && !displayName.isEmpty() ? displayName : name;
        }
    }

    public static class Song {
        public long id;
        public String title;
        public String variantLabel;          // null | "remix" | "live" | ...
        public final ArrayList<Artist> artists = new ArrayList<>();
        public int versionCount = 1;
        public long artChannelId;
        public int artMessageId;
        public Track defaultTrack;           // the version to play for this user (may be null)
        // Owned svipe.uz/<code> link, minted server-side. Only the song DETAIL response carries one
        // (that is the only screen that shares), so this stays null on shelf/search cards.
        public String shareUrl;
        // Deezer enrichment overlay — real name + cover/photo URLs (hotlink; never downloaded to us).
        // null/empty -> fall back to the raw Telegram tag (title / artistLine()) and the letter/thumb art.
        public String displayTitle;
        public String displayArtist;
        public String coverUrl;
        public String coverSmallUrl;
        public String artistPhotoUrl;
        // A5 "missing-song": a Deezer track we don't host yet. playable=false, no versions; the row
        // shows name+cover+artist and the tap shows an "Adding…" hint instead of the version picker.
        public boolean playable = true;
        public long deezerTrackId;
        public String previewUrl;

        /** "Artist, Artist2 feat. Artist3" for one-line display. */
        public String artistLine() {
            if (artists.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            String prevRole = null;
            for (int i = 0; i < artists.size(); i++) {
                Artist a = artists.get(i);
                if (i > 0) sb.append("featured".equals(a.role) && !"featured".equals(prevRole) ? " feat. " : ", ");
                sb.append(a.name);
                prevRole = a.role;
            }
            return sb.toString();
        }

        /** Real title when the song was Deezer-enriched, else the raw Telegram tag. */
        public String shownTitle() {
            return displayTitle != null && !displayTitle.isEmpty() ? displayTitle : title;
        }

        /** Enriched one-line artist when present, else the tag-based {@link #artistLine()}. */
        public String shownArtist() {
            return displayArtist != null && !displayArtist.isEmpty() ? displayArtist : artistLine();
        }
    }

    public static class SongVersion extends Track {
        public int voteCount;
        public boolean isMyDefault;
        public boolean isDefault;            // the current crowd default
    }

    public static class SongDetail extends Song {
        public final ArrayList<SongVersion> versions = new ArrayList<>();
    }

    public static class SongSection {
        public String key;
        public String title;
        public final ArrayList<Song> songs = new ArrayList<>();
    }

    public static class ArtistPage {
        public Artist artist;
        public int songCount;
        public final ArrayList<Song> songs = new ArrayList<>();
        public String nextOffset;
    }

    public static class DefaultAck {
        public long songId;
        public long defaultChannelId;
        public int defaultMessageId;
    }

    public interface HomeCallback {
        /** sections==null on failure. */
        void onResult(List<Section> sections, String error);
    }

    public interface TracksCallback {
        /** items==null on failure. nextCursor==null when there are no more pages. */
        void onResult(List<Track> items, String recommendationId, String nextCursor, String error);
    }

    public static void home(int account, HomeCallback cb) {
        withToken(account, () -> cb.onResult(null, "auth"),
            token -> homeRequest(account, token, false, cb));
    }

    private static void homeRequest(int account, String token, boolean retried, HomeCallback cb) {
        SvipeApi.get("/v1/music/home", token, (res, code, err) -> {
            if (code == 401 && !retried) {
                reauth(account, () -> cb.onResult(null, "auth"),
                    t2 -> homeRequest(account, t2, true, cb));
                return;
            }
            if (res == null || !res.has("sections")) {
                cb.onResult(null, err != null ? err : ("http " + code));
                return;
            }
            ArrayList<Section> out = new ArrayList<>();
            JSONArray arr = res.optJSONArray("sections");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Section s = new Section();
                    s.key = o.optString("key");
                    s.title = o.optString("title");
                    parseTracks(o.optJSONArray("items"), s.tracks);
                    if (!s.tracks.isEmpty()) {
                        out.add(s);
                    }
                }
            }
            cb.onResult(out, null);
        });
    }

    public static void vibe(int account, String cursor, Long seedChannelId, Integer seedMessageId, TracksCallback cb) {
        StringBuilder path = new StringBuilder("/v1/music/vibe");
        char sep = '?';
        if (cursor != null && !cursor.isEmpty()) {
            path.append(sep).append("cursor=").append(urlEncode(cursor));
            sep = '&';
        }
        if (seedChannelId != null && seedMessageId != null) {
            path.append(sep).append("seed_channel_id=").append(seedChannelId)
                .append("&seed_message_id=").append(seedMessageId);
        }
        tracksGet(account, path.toString(), cb);
    }

    public static void search(int account, String query, int offset, int limit, TracksCallback cb) {
        tracksGet(account, "/v1/music/search?q=" + urlEncode(query) + "&limit=" + limit + "&offset=" + offset, cb);
    }

    public static void liked(int account, int offset, int limit, TracksCallback cb) {
        tracksGet(account, "/v1/music/liked?limit=" + limit + "&offset=" + offset, cb);
    }

    /** Shared GET for all endpoints returning {items, recommendation_id?, next_cursor?/next_offset?}. */
    private static void tracksGet(int account, String path, TracksCallback cb) {
        withToken(account, () -> cb.onResult(null, null, null, "auth"),
            token -> tracksRequest(account, path, token, false, cb));
    }

    private static void tracksRequest(int account, String path, String token, boolean retried, TracksCallback cb) {
        SvipeApi.get(path, token, (res, code, err) -> {
            if (code == 401 && !retried) {
                reauth(account, () -> cb.onResult(null, null, null, "auth"),
                    t2 -> tracksRequest(account, path, t2, true, cb));
                return;
            }
            if (res == null || !res.has("items")) {
                cb.onResult(null, null, null, err != null ? err : ("http " + code));
                return;
            }
            ArrayList<Track> out = new ArrayList<>();
            parseTracks(res.optJSONArray("items"), out);
            String recId = res.isNull("recommendation_id") ? null : res.optString("recommendation_id", null);
            String next;
            if (!res.isNull("next_cursor")) {
                next = res.optString("next_cursor", null);
            } else if (!res.isNull("next_offset")) {
                next = String.valueOf(res.optInt("next_offset"));
            } else {
                next = null;
            }
            cb.onResult(out, recId, next, null);
        });
    }

    /** Fire-and-forget telemetry. payload may be null. */
    public static void sendEvent(int account, Track track, String eventType, JSONObject payload) {
        if (track == null || eventType == null) {
            return;
        }
        try {
            JSONObject ev = new JSONObject();
            ev.put("channel_id", track.channelId);
            ev.put("message_id", track.messageId);
            ev.put("event_type", eventType);
            ev.put("client_ts", System.currentTimeMillis() / 1000.0);
            if (payload != null) {
                ev.put("payload", payload);
            }
            JSONObject batch = new JSONObject();
            JSONArray events = new JSONArray();
            events.put(ev);
            batch.put("events", events);
            postEvents(account, batch, false);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void postEvents(int account, JSONObject batch, boolean retried) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                return;
            }
            SvipeApi.post("/v1/music/events", batch, token, (res, code, err) -> {
                if (code == 401 && !retried) {
                    SvipeAuth.invalidateAccessToken(account);
                    postEvents(account, batch, true);
                }
            });
        });
    }

    // ---------------- Canonical song / artist endpoints ----------------
    public interface SongsCallback { void onResult(List<Song> items, String nextOffset, String error); }
    public interface SongHomeCallback { void onResult(List<SongSection> sections, String error); }
    public interface SongDetailCallback { void onResult(SongDetail song, String error); }
    public interface ArtistCallback { void onResult(ArtistPage page, String error); }
    public interface DefaultCallback { void onResult(DefaultAck ack, String error); }
    /** error==null on success; isFavourite is the state the SERVER now holds. */
    public interface FavouriteCallback { void onResult(long songId, boolean isFavourite, String error); }
    public interface TrackSongIdCallback { void onResult(long songId); }

    public static void songsHome(int account, SongHomeCallback cb) {
        withToken(account, () -> cb.onResult(null, "auth"),
            token -> songsHomeRequest(account, token, false, cb));
    }

    private static void songsHomeRequest(int account, String token, boolean retried, SongHomeCallback cb) {
        SvipeApi.get("/v1/music/songs/home", token, (res, code, err) -> {
            if (code == 401 && !retried) {
                reauth(account, () -> cb.onResult(null, "auth"), t2 -> songsHomeRequest(account, t2, true, cb));
                return;
            }
            if (res == null || !res.has("sections")) { cb.onResult(null, err != null ? err : ("http " + code)); return; }
            ArrayList<SongSection> out = new ArrayList<>();
            JSONArray arr = res.optJSONArray("sections");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    SongSection s = new SongSection();
                    s.key = o.optString("key");
                    s.title = o.optString("title");
                    parseSongs(o.optJSONArray("items"), s.songs);
                    if (!s.songs.isEmpty()) out.add(s);
                }
            }
            cb.onResult(out, null);
        });
    }

    public static void songsSearch(int account, String query, int offset, int limit, SongsCallback cb) {
        songsListGet(account, "/v1/music/songs/search?q=" + urlEncode(query) + "&limit=" + limit + "&offset=" + offset, cb);
    }

    private static void songsListGet(int account, String path, SongsCallback cb) {
        withToken(account, () -> cb.onResult(null, null, "auth"),
            token -> songsListRequest(account, path, token, false, cb));
    }

    private static void songsListRequest(int account, String path, String token, boolean retried, SongsCallback cb) {
        SvipeApi.get(path, token, (res, code, err) -> {
            if (code == 401 && !retried) {
                reauth(account, () -> cb.onResult(null, null, "auth"), t2 -> songsListRequest(account, path, t2, true, cb));
                return;
            }
            if (res == null || !res.has("items")) { cb.onResult(null, null, err != null ? err : ("http " + code)); return; }
            ArrayList<Song> out = new ArrayList<>();
            parseSongs(res.optJSONArray("items"), out);
            String next = res.isNull("next_offset") ? null : String.valueOf(res.optInt("next_offset"));
            cb.onResult(out, next, null);
        });
    }

    // ---------------- Favourites ----------------

    /** This user's favourite songs, newest first. Same {items, next_offset} shape as search. */
    public static void favourites(int account, int offset, int limit, SongsCallback cb) {
        songsListGet(account, "/v1/music/favourites?limit=" + limit + "&offset=" + offset, cb);
    }

    public static void favourite(int account, long songId, FavouriteCallback cb) {
        withToken(account, () -> ack(cb, songId, false, "auth"),
            token -> favouriteRequest(account, songId, true, token, false, cb));
    }

    public static void unfavourite(int account, long songId, FavouriteCallback cb) {
        withToken(account, () -> ack(cb, songId, true, "auth"),
            token -> favouriteRequest(account, songId, false, token, false, cb));
    }

    private static void favouriteRequest(int account, long songId, boolean add, String token,
                                         boolean retried, FavouriteCallback cb) {
        String path = "/v1/music/song/" + songId + "/favourite";
        SvipeApi.JsonCallback handler = (res, code, err) -> {
            if (code == 401 && !retried) {
                reauth(account, () -> ack(cb, songId, !add, "auth"),
                    t2 -> favouriteRequest(account, songId, add, t2, true, cb));
                return;
            }
            if (res == null || !res.has("song_id")) {
                ack(cb, songId, !add, err != null ? err : ("http " + code));
                return;
            }
            ack(cb, res.optLong("song_id"), res.optBoolean("is_favourite", add), null);
        };
        if (add) {
            SvipeApi.post(path, new JSONObject(), token, handler);
        } else {
            SvipeApi.delete(path, token, handler);
        }
    }

    private static void ack(FavouriteCallback cb, long songId, boolean isFavourite, String error) {
        if (cb != null) {
            cb.onResult(songId, isFavourite, error);
        }
    }

    /**
     * Which canonical song a raw channel post belongs to, so a favourite made while listening inside a
     * Telegram channel can be re-keyed onto the catalog song instead of living as a separate entry.
     * Reports songId 0 for anything we don't host.
     */
    public static void trackSongId(int account, long channelId, int messageId, TrackSongIdCallback cb) {
        withToken(account, () -> cb.onResult(0),
            token -> trackSongIdRequest(account, channelId, messageId, token, false, cb));
    }

    private static void trackSongIdRequest(int account, long channelId, int messageId, String token,
                                           boolean retried, TrackSongIdCallback cb) {
        SvipeApi.get("/v1/music/track?channel_id=" + channelId + "&message_id=" + messageId, token,
            (res, code, err) -> {
                if (code == 401 && !retried) {
                    reauth(account, () -> cb.onResult(0),
                        t2 -> trackSongIdRequest(account, channelId, messageId, t2, true, cb));
                    return;
                }
                cb.onResult(res == null ? 0 : res.optLong("song_id"));
            });
    }

    public static void song(int account, long songId, SongDetailCallback cb) {
        withToken(account, () -> cb.onResult(null, "auth"),
            token -> songRequest(account, songId, token, false, cb));
    }

    private static void songRequest(int account, long songId, String token, boolean retried, SongDetailCallback cb) {
        SvipeApi.get("/v1/music/song/" + songId, token, (res, code, err) -> {
            if (code == 401 && !retried) {
                reauth(account, () -> cb.onResult(null, "auth"), t2 -> songRequest(account, songId, t2, true, cb));
                return;
            }
            if (res == null || !res.has("id")) { cb.onResult(null, err != null ? err : ("http " + code)); return; }
            cb.onResult(parseSongDetail(res), null);
        });
    }

    public static void artist(int account, long artistId, int offset, int limit, ArtistCallback cb) {
        withToken(account, () -> cb.onResult(null, "auth"),
            token -> artistRequest(account, artistId, offset, limit, token, false, cb));
    }

    private static void artistRequest(int account, long artistId, int offset, int limit, String token, boolean retried, ArtistCallback cb) {
        SvipeApi.get("/v1/music/artist/" + artistId + "?limit=" + limit + "&offset=" + offset, token, (res, code, err) -> {
            if (code == 401 && !retried) {
                reauth(account, () -> cb.onResult(null, "auth"), t2 -> artistRequest(account, artistId, offset, limit, t2, true, cb));
                return;
            }
            if (res == null || !res.has("id")) { cb.onResult(null, err != null ? err : ("http " + code)); return; }
            ArtistPage p = new ArtistPage();
            Artist a = new Artist();
            a.id = res.optLong("id");
            a.name = res.optString("name", "");
            a.artChannelId = res.optLong("art_channel_id");
            a.artMessageId = res.optInt("art_message_id");
            a.displayName = res.isNull("display_name") ? null : res.optString("display_name", null);
            a.photoUrl = res.isNull("photo_url") ? null : res.optString("photo_url", null);
            p.artist = a;
            p.songCount = res.optInt("song_count");
            parseSongs(res.optJSONArray("songs"), p.songs);
            p.nextOffset = res.isNull("next_offset") ? null : String.valueOf(res.optInt("next_offset"));
            cb.onResult(p, null);
        });
    }

    public static void setDefault(int account, long songId, long channelId, int messageId, DefaultCallback cb) {
        withToken(account, () -> cb.onResult(null, "auth"),
            token -> setDefaultRequest(account, songId, channelId, messageId, token, false, cb));
    }

    private static void setDefaultRequest(int account, long songId, long channelId, int messageId, String token, boolean retried, DefaultCallback cb) {
        JSONObject body = new JSONObject();
        try { body.put("channel_id", channelId); body.put("message_id", messageId); } catch (Exception e) { FileLog.e(e); }
        SvipeApi.post("/v1/music/song/" + songId + "/default", body, token, (res, code, err) -> {
            if (code == 401 && !retried) {
                reauth(account, () -> cb.onResult(null, "auth"), t2 -> setDefaultRequest(account, songId, channelId, messageId, t2, true, cb));
                return;
            }
            handleDefaultAck(res, code, err, cb);
        });
    }

    public static void clearDefault(int account, long songId, DefaultCallback cb) {
        withToken(account, () -> cb.onResult(null, "auth"),
            token -> clearDefaultRequest(account, songId, token, false, cb));
    }

    private static void clearDefaultRequest(int account, long songId, String token, boolean retried, DefaultCallback cb) {
        SvipeApi.delete("/v1/music/song/" + songId + "/default", token, (res, code, err) -> {
            if (code == 401 && !retried) {
                reauth(account, () -> cb.onResult(null, "auth"), t2 -> clearDefaultRequest(account, songId, t2, true, cb));
                return;
            }
            handleDefaultAck(res, code, err, cb);
        });
    }

    private static void handleDefaultAck(JSONObject res, int code, String err, DefaultCallback cb) {
        if (res == null || !res.has("song_id")) { cb.onResult(null, err != null ? err : ("http " + code)); return; }
        DefaultAck ack = new DefaultAck();
        ack.songId = res.optLong("song_id");
        ack.defaultChannelId = res.isNull("default_channel_id") ? 0 : res.optLong("default_channel_id");
        ack.defaultMessageId = res.isNull("default_message_id") ? 0 : res.optInt("default_message_id");
        cb.onResult(ack, null);
    }

    /* internals */

    private interface TokenAction {
        void run(String token);
    }

    private static void withToken(int account, Runnable onAuthFail, TokenAction action) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                onAuthFail.run();
                return;
            }
            action.run(token);
        });
    }

    // Access token died: silent re-auth, one retry — same pattern as SvipeDiscover.
    private static void reauth(int account, Runnable onAuthFail, TokenAction retry) {
        SvipeAuth.invalidateAccessToken(account);
        SvipeAuth.ensureToken(account, t2 -> {
            if (t2 == null) {
                onAuthFail.run();
                return;
            }
            retry.run(t2);
        });
    }

    private static void parseTracks(JSONArray arr, List<Track> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            Track t = new Track();
            if (fillTrack(arr.optJSONObject(i), t)) out.add(t);
        }
    }

    /** Fill the reference-only track fields shared by Track / SongVersion. Returns false (skip) when
     * the row has no username — it can't be turned into a t.me handle or resolved to play. */
    private static boolean fillTrack(JSONObject o, Track t) {
        if (o == null) return false;
        String username = o.isNull("username") ? null : o.optString("username", null);
        if (username == null || username.isEmpty()) return false;
        t.channelId = o.optLong("channel_id");
        t.messageId = o.optInt("message_id");
        t.username = username;
        t.title = o.isNull("title") ? null : o.optString("title", null);
        t.performer = o.isNull("performer") ? null : o.optString("performer", null);
        t.durationS = o.optInt("duration_s");
        t.size = o.optLong("size");
        t.hasThumb = o.optBoolean("has_thumb", false);
        t.songId = o.optLong("song_id");
        return true;
    }

    private static void parseSongs(JSONArray arr, List<Song> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            Song s = parseSong(arr.optJSONObject(i));
            if (s != null) out.add(s);
        }
    }

    private static Artist parseArtist(JSONObject o) {
        if (o == null) return null;
        Artist a = new Artist();
        a.id = o.optLong("id");
        a.name = o.optString("name", "");
        a.role = o.optString("role", "primary");
        a.songCount = o.optInt("song_count");
        a.artChannelId = o.optLong("art_channel_id");
        a.artMessageId = o.optInt("art_message_id");
        return a;
    }

    private static Song parseSong(JSONObject o) {
        if (o == null) return null;
        Song s = new Song();
        s.id = o.optLong("id");
        s.title = o.optString("title", "");
        s.variantLabel = o.isNull("variant_label") ? null : o.optString("variant_label", null);
        s.versionCount = o.optInt("version_count", 1);
        s.artChannelId = o.optLong("art_channel_id");
        s.artMessageId = o.optInt("art_message_id");
        s.shareUrl = o.isNull("share_url") ? null : o.optString("share_url", null);
        s.displayTitle = o.isNull("display_title") ? null : o.optString("display_title", null);
        s.displayArtist = o.isNull("display_artist") ? null : o.optString("display_artist", null);
        s.coverUrl = o.isNull("cover_url") ? null : o.optString("cover_url", null);
        s.coverSmallUrl = o.isNull("cover_small_url") ? null : o.optString("cover_small_url", null);
        s.artistPhotoUrl = o.isNull("artist_photo_url") ? null : o.optString("artist_photo_url", null);
        s.playable = o.optBoolean("playable", true);
        s.deezerTrackId = o.optLong("deezer_track_id");
        s.previewUrl = o.isNull("preview_url") ? null : o.optString("preview_url", null);
        JSONArray arts = o.optJSONArray("artists");
        if (arts != null) {
            for (int i = 0; i < arts.length(); i++) {
                Artist a = parseArtist(arts.optJSONObject(i));
                if (a != null) s.artists.add(a);
            }
        }
        JSONObject dt = o.optJSONObject("default");
        if (dt != null) {
            Track t = new Track();
            if (fillTrack(dt, t)) s.defaultTrack = t;
        }
        return s;
    }

    private static SongDetail parseSongDetail(JSONObject o) {
        Song base = parseSong(o);
        if (base == null) return null;
        SongDetail d = new SongDetail();
        d.id = base.id;
        d.title = base.title;
        d.variantLabel = base.variantLabel;
        d.artists.addAll(base.artists);
        d.versionCount = base.versionCount;
        d.artChannelId = base.artChannelId;
        d.artMessageId = base.artMessageId;
        d.defaultTrack = base.defaultTrack;
        d.shareUrl = base.shareUrl;
        d.displayTitle = base.displayTitle;
        d.displayArtist = base.displayArtist;
        d.coverUrl = base.coverUrl;
        d.coverSmallUrl = base.coverSmallUrl;
        d.artistPhotoUrl = base.artistPhotoUrl;
        JSONArray vers = o.optJSONArray("versions");
        if (vers != null) {
            for (int i = 0; i < vers.length(); i++) {
                JSONObject vo = vers.optJSONObject(i);
                SongVersion v = new SongVersion();
                if (!fillTrack(vo, v)) continue;
                v.voteCount = vo.optInt("vote_count");
                v.isMyDefault = vo.optBoolean("is_my_default", false);
                v.isDefault = vo.optBoolean("is_default", false);
                d.versions.add(v);
            }
        }
        return d;
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
