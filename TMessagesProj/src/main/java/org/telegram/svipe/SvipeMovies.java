package org.telegram.svipe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Backend client for the MOVIE surface — the Zona-style film catalog layered over the long-video
 * corpus (backend: {@code app/movies/}, {@code app/api/movies.py}).
 *
 * <p>References only, exactly like {@link SvipeDiscover}: a "poster" is a
 * {@code (channel_id, message_id)} pair whose Telegram message thumbnail IS the poster art, resolved
 * client-side over MTProto the same way {@link SvipeMusic} resolves song artwork. Nothing here ever
 * carries an image URL, and nothing is downloaded to our server.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /v1/videos/categories} — the chip row above the Video tab. Server order is the
 *       render order and must NOT be re-sorted: "Kino" is deliberately last so it only appears once
 *       the strip is scrolled.</li>
 *   <li>{@code GET /v1/movies} — one page of film cards for a category (or a title search).</li>
 *   <li>{@code GET /v1/movies/{id}} — MovieProfile: the film, its versions and its cast.</li>
 *   <li>{@code GET /v1/movies/actors/{id}} — ActorProfile: the performer and their filmography.</li>
 *   <li>{@code POST/DELETE /v1/movies/{id}/default} — "watch this version", the crowd vote.</li>
 * </ul>
 *
 * <p>A build that predates these endpoints simply never calls them, so no client-level gate is
 * needed — the same reasoning that let {@code /v1/videos} ship without one.
 */
public final class SvipeMovies {

    private SvipeMovies() {
    }

    // ---------------------------------------------------------------------------------------------
    // models
    // ---------------------------------------------------------------------------------------------

    /** One chip in the row above the grid. */
    public static class Category {
        public String slug;
        public String title;
        /**
         * True when this shelf can hold films, i.e. tapping it opens the Zona-style film catalog
         * instead of the plain long-video list. Decided by the SERVER so the two layouts are never
         * inferred from the payload shape.
         */
        public boolean film;
        public int count;
    }

    /** A film card in a grid. */
    public static class Movie {
        public long id;
        public String title;
        public int year;              // 0 = unknown
        public String kind = "movie"; // movie | cartoon | anime | doc
        public int runtimeS;
        public String country;
        public final List<String> genres = new ArrayList<>();
        public double kpRating;       // 0 = absent
        public double imdbRating;
        public int versionCount;
        public long posterChannelId;
        public int posterMessageId;
        public String posterUsername;

        /** The best rating we have, or 0 — KinoPoisk first, because that is what our channels print. */
        public double rating() {
            return kpRating > 0 ? kpRating : imdbRating;
        }

        public boolean hasPoster() {
            return posterChannelId != 0 && posterMessageId != 0 && posterUsername != null;
        }
    }

    /** One Telegram copy of a film — a row in the MovieProfile "Variantlar" tab. */
    public static class Version {
        public long channelId;
        public int messageId;
        public String username;
        public long durationMs;
        public int width, height;
        public long size;
        public String quality;
        public String language;
        public String channelTitle;
        public long tgViews;
        public int votes;
        public boolean isDefault;

        /** A reference the existing player/grid code can consume unchanged. */
        public SvipeDiscover.Item toItem() {
            SvipeDiscover.Item it = new SvipeDiscover.Item();
            it.channelId = channelId;
            it.messageId = messageId;
            it.username = username;
            it.width = width;
            it.height = height;
            it.durationMs = (int) durationMs;
            return it;
        }
    }

    public static class Actor {
        public long id;
        public String name;
        public int movieCount;
        public long artChannelId;
        public int artMessageId;
        public String artUsername;
    }

    public static class MovieDetail {
        public Movie movie;
        public String director;
        public final List<Version> versions = new ArrayList<>();
        public final List<Actor> actors = new ArrayList<>();
        public Version myDefault;
    }

    /**
     * A film's poster, shaped as a feed reference so the existing grid machinery (thumbnail
     * resolution, cell binding, tap routing) applies to a film card without a parallel code path.
     * The film itself rides along; {@code instanceof PosterRef} is how the grid tells a film card
     * from a video tile.
     */
    public static class PosterRef extends SvipeDiscover.Item {
        public Movie movie;

        public static PosterRef of(Movie m) {
            PosterRef r = new PosterRef();
            r.movie = m;
            r.channelId = m.posterChannelId;
            r.messageId = m.posterMessageId;
            r.username = m.posterUsername;
            r.width = 16;
            r.height = 9;
            r.durationMs = m.runtimeS * 1000;
            return r;
        }
    }

    /** A show. {@code tgUsername} is the generated public channel holding every episode in order. */
    public static class Series {
        public long id;
        public String title;
        public int year;
        public int episodeCount;
        public int seasonCount;
        public String country;
        public final List<String> genres = new ArrayList<>();
        public long posterChannelId;
        public int posterMessageId;
        public String posterUsername;
        public String tgUsername;      // null until the server has built the playlist channel
        public String channelStatus = "pending";

        public boolean hasChannel() {
            return tgUsername != null && !tgUsername.isEmpty();
        }

        /**
         * A display-only {@link Movie} so a show can be rendered by the SAME card as a film. Only the
         * fields the card reads are filled; nothing downstream treats it as a real film, because the
         * grid keeps the Series itself on the reference.
         */
        public Movie asCard() {
            Movie m = new Movie();
            m.id = id;
            m.title = title;
            m.year = year;
            m.kind = "series";
            m.versionCount = episodeCount;
            m.posterChannelId = posterChannelId;
            m.posterMessageId = posterMessageId;
            m.posterUsername = posterUsername;
            m.genres.addAll(genres);
            return m;
        }
    }

    /** A show's poster, shaped as a feed reference — the {@link PosterRef} of the series world. */
    public static class SeriesRef extends SvipeDiscover.Item {
        public Series series;

        public static SeriesRef of(Series s) {
            SeriesRef r = new SeriesRef();
            r.series = s;
            r.channelId = s.posterChannelId;
            r.messageId = s.posterMessageId;
            r.username = s.posterUsername;
            r.width = 16;
            r.height = 9;
            return r;
        }
    }

    public interface SeriesCallback {
        void onResult(List<Series> series, Integer nextOffset, String error);
    }

    public static class ActorPage {
        public Actor actor;
        public final List<Movie> movies = new ArrayList<>();
        public Integer nextOffset;
    }

    public interface CategoriesCallback {
        void onResult(List<Category> categories, String error);
    }

    public interface MoviesCallback {
        void onResult(List<Movie> movies, Integer nextOffset, String error);
    }

    public interface MovieCallback {
        void onResult(MovieDetail detail, String error);
    }

    public interface ActorCallback {
        void onResult(ActorPage page, String error);
    }

    public interface AckCallback {
        void onResult(boolean ok, String error);
    }

    // ---------------------------------------------------------------------------------------------
    // requests
    // ---------------------------------------------------------------------------------------------

    public static void categories(int account, CategoriesCallback cb) {
        get(account, "/v1/videos/categories", (res, err) -> {
            if (res == null) {
                cb.onResult(null, err);
                return;
            }
            List<Category> out = new ArrayList<>();
            JSONArray arr = res.optJSONArray("items");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Category c = new Category();
                c.slug = o.optString("slug", "");
                c.title = o.optString("title", c.slug);
                c.film = o.optBoolean("film");
                c.count = o.optInt("count");
                if (!c.slug.isEmpty()) out.add(c);
            }
            cb.onResult(out, null);
        });
    }

    /** One page of the film grid. {@code categorySlug} null = every film; {@code query} null = no search. */
    public static void movies(int account, String categorySlug, String query, int offset, int limit,
                              MoviesCallback cb) {
        StringBuilder path = new StringBuilder("/v1/movies?limit=").append(limit)
                .append("&offset=").append(offset);
        if (categorySlug != null && !categorySlug.isEmpty()) {
            path.append("&cat=").append(encode(categorySlug));
        }
        if (query != null && !query.isEmpty()) {
            path.append("&q=").append(encode(query));
        }
        get(account, path.toString(), (res, err) -> {
            if (res == null) {
                cb.onResult(null, null, err);
                return;
            }
            List<Movie> out = new ArrayList<>();
            parseMovies(res.optJSONArray("items"), out);
            Integer next = res.isNull("next_offset") ? null : Integer.valueOf(res.optInt("next_offset"));
            cb.onResult(out, next, null);
        });
    }

    /** One page of shows, biggest first. */
    public static void series(int account, int offset, int limit, SeriesCallback cb) {
        get(account, "/v1/series?limit=" + limit + "&offset=" + offset, (res, err) -> {
            if (res == null) {
                cb.onResult(null, null, err);
                return;
            }
            List<Series> out = new ArrayList<>();
            JSONArray arr = res.optJSONArray("items");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Series s = new Series();
                s.id = o.optLong("id");
                s.title = o.optString("title", "");
                s.year = o.isNull("year") ? 0 : o.optInt("year");
                s.episodeCount = o.optInt("episode_count");
                s.seasonCount = o.optInt("season_count");
                s.country = o.isNull("country") ? null : o.optString("country", null);
                JSONArray g = o.optJSONArray("genres");
                for (int j = 0; g != null && j < g.length(); j++) {
                    String v = g.optString(j, null);
                    if (v != null && !v.isEmpty()) s.genres.add(v);
                }
                s.posterChannelId = o.optLong("poster_channel_id");
                s.posterMessageId = o.optInt("poster_message_id");
                s.posterUsername = o.isNull("poster_username") ? null : o.optString("poster_username", null);
                s.tgUsername = o.isNull("tg_username") ? null : o.optString("tg_username", null);
                s.channelStatus = o.optString("channel_status", "pending");
                out.add(s);
            }
            Integer next = res.isNull("next_offset") ? null : Integer.valueOf(res.optInt("next_offset"));
            cb.onResult(out, next, null);
        });
    }

    public static void movie(int account, long movieId, MovieCallback cb) {
        get(account, "/v1/movies/" + movieId, (res, err) -> {
            if (res == null || !res.has("movie")) {
                cb.onResult(null, err != null ? err : "empty");
                return;
            }
            MovieDetail d = new MovieDetail();
            d.movie = parseMovie(res.optJSONObject("movie"));
            d.director = res.isNull("director") ? null : res.optString("director", null);
            JSONArray vs = res.optJSONArray("versions");
            for (int i = 0; vs != null && i < vs.length(); i++) {
                Version v = parseVersion(vs.optJSONObject(i));
                if (v != null) d.versions.add(v);
            }
            JSONArray as = res.optJSONArray("actors");
            for (int i = 0; as != null && i < as.length(); i++) {
                Actor a = parseActor(as.optJSONObject(i));
                if (a != null) d.actors.add(a);
            }
            d.myDefault = parseVersion(res.optJSONObject("my_default"));
            cb.onResult(d, null);
        });
    }

    /**
     * The film a Telegram post is a copy of — the watch page's reverse lookup for its actor row.
     * A 404 (most long videos are not films) arrives as {@code detail == null}, which is a normal
     * outcome and not an error worth surfacing.
     */
    public static void movieByPost(int account, long channelId, int messageId, MovieCallback cb) {
        get(account, "/v1/movies/by-post?channel_id=" + channelId + "&message_id=" + messageId,
                (res, err) -> {
                    if (res == null || !res.has("movie")) {
                        cb.onResult(null, err);
                        return;
                    }
                    MovieDetail d = new MovieDetail();
                    d.movie = parseMovie(res.optJSONObject("movie"));
                    d.director = res.isNull("director") ? null : res.optString("director", null);
                    JSONArray as = res.optJSONArray("actors");
                    for (int i = 0; as != null && i < as.length(); i++) {
                        Actor a = parseActor(as.optJSONObject(i));
                        if (a != null) d.actors.add(a);
                    }
                    d.myDefault = parseVersion(res.optJSONObject("my_default"));
                    cb.onResult(d, null);
                });
    }

    public static void actor(int account, long actorId, int offset, int limit, ActorCallback cb) {
        get(account, "/v1/movies/actors/" + actorId + "?limit=" + limit + "&offset=" + offset,
                (res, err) -> {
                    if (res == null || !res.has("actor")) {
                        cb.onResult(null, err != null ? err : "empty");
                        return;
                    }
                    ActorPage p = new ActorPage();
                    p.actor = parseActor(res.optJSONObject("actor"));
                    parseMovies(res.optJSONArray("items"), p.movies);
                    p.nextOffset = res.isNull("next_offset") ? null : Integer.valueOf(res.optInt("next_offset"));
                    cb.onResult(p, null);
                });
    }

    /** Pin a version for this user and cast a vote for everyone else's default. */
    public static void setDefault(int account, long movieId, long channelId, int messageId, AckCallback cb) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                cb.onResult(false, "auth");
                return;
            }
            String path = "/v1/movies/" + movieId + "/default?channel_id=" + channelId
                    + "&message_id=" + messageId;
            SvipeApi.post(path, null, token, (res, code, err) ->
                    cb.onResult(code >= 200 && code < 300, err));
        });
    }

    public static void clearDefault(int account, long movieId, AckCallback cb) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                cb.onResult(false, "auth");
                return;
            }
            SvipeApi.delete("/v1/movies/" + movieId + "/default", token, (res, code, err) ->
                    cb.onResult(code >= 200 && code < 300, err));
        });
    }

    // ---------------------------------------------------------------------------------------------
    // plumbing
    // ---------------------------------------------------------------------------------------------

    private interface Raw {
        void onResult(JSONObject res, String error);
    }

    /** Token fetch + one silent re-auth on 401, mirroring {@link SvipeDiscover}'s feed path. */
    private static void get(int account, String path, Raw cb) {
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                cb.onResult(null, "auth");
                return;
            }
            request(account, path, token, false, cb);
        });
    }

    private static void request(int account, String path, String token, boolean retried, Raw cb) {
        SvipeApi.get(path, token, (res, code, err) -> {
            if (code == 401 && !retried) {
                SvipeAuth.invalidateAccessToken(account);
                SvipeAuth.ensureToken(account, t2 -> {
                    if (t2 == null) {
                        cb.onResult(null, "auth");
                        return;
                    }
                    request(account, path, t2, true, cb);
                });
                return;
            }
            if (res == null) {
                cb.onResult(null, err != null ? err : ("http " + code));
                return;
            }
            cb.onResult(res, null);
        });
    }

    private static void parseMovies(JSONArray arr, List<Movie> out) {
        for (int i = 0; arr != null && i < arr.length(); i++) {
            Movie m = parseMovie(arr.optJSONObject(i));
            if (m != null) out.add(m);
        }
    }

    private static Movie parseMovie(JSONObject o) {
        if (o == null) return null;
        Movie m = new Movie();
        m.id = o.optLong("id");
        m.title = o.optString("title", "");
        m.year = o.isNull("year") ? 0 : o.optInt("year");
        m.kind = o.optString("kind", "movie");
        m.runtimeS = o.isNull("runtime_s") ? 0 : o.optInt("runtime_s");
        m.country = o.isNull("country") ? null : o.optString("country", null);
        JSONArray g = o.optJSONArray("genres");
        for (int i = 0; g != null && i < g.length(); i++) {
            String s = g.optString(i, null);
            if (s != null && !s.isEmpty()) m.genres.add(s);
        }
        m.kpRating = o.isNull("kp_rating") ? 0 : o.optDouble("kp_rating", 0);
        m.imdbRating = o.isNull("imdb_rating") ? 0 : o.optDouble("imdb_rating", 0);
        m.versionCount = o.optInt("version_count");
        m.posterChannelId = o.optLong("poster_channel_id");
        m.posterMessageId = o.optInt("poster_message_id");
        m.posterUsername = o.isNull("poster_username") ? null : o.optString("poster_username", null);
        return m;
    }

    private static Version parseVersion(JSONObject o) {
        if (o == null) return null;
        Version v = new Version();
        v.channelId = o.optLong("channel_id");
        v.messageId = o.optInt("message_id");
        v.username = o.isNull("username") ? null : o.optString("username", null);
        v.durationMs = o.optLong("duration_ms");
        v.width = o.optInt("width");
        v.height = o.optInt("height");
        v.size = o.optLong("size");
        v.quality = o.isNull("quality") ? null : o.optString("quality", null);
        v.language = o.isNull("language") ? null : o.optString("language", null);
        v.channelTitle = o.isNull("channel_title") ? null : o.optString("channel_title", null);
        v.tgViews = o.optLong("tg_views");
        v.votes = o.optInt("votes");
        v.isDefault = o.optBoolean("is_default");
        return v;
    }

    private static Actor parseActor(JSONObject o) {
        if (o == null) return null;
        Actor a = new Actor();
        a.id = o.optLong("id");
        a.name = o.optString("name", "");
        a.movieCount = o.optInt("movie_count");
        a.artChannelId = o.optLong("art_channel_id");
        a.artMessageId = o.optInt("art_message_id");
        a.artUsername = o.isNull("art_username") ? null : o.optString("art_username", null);
        return a;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
