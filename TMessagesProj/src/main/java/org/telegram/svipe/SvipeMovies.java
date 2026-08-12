package org.telegram.svipe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

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

        /**
         * The chip label in the USER's language.
         *
         * <p>{@link #title} is the server's single-language display name (Uzbek — see
         * {@code app/movies/categories.py}, where the taxonomy is a frozen, hard-coded tuple) and is
         * only a FALLBACK. The taxonomy is fixed and small, so the stable {@link #slug} is what
         * identifies a shelf and the text belongs in the langpack like every other string in this
         * app; that also lets {@code Tools/check_svipe_strings.py} enforce uz+ru on it, which a JSON
         * payload never could. A slug added on the server after this build shipped falls back to the
         * server title: readable, never blank.
         */
        public String label() {
            final int res = categoryRes(slug);
            return res == 0 ? title : LocaleController.getString(res);
        }
    }

    /**
     * Category slug -> string resource. Written out rather than resolved by name through
     * {@code Resources.getIdentifier}: a reflective lookup does not survive R8 resource shrinking,
     * and this way an unknown slug is a compile-time-visible {@code 0}, not a crash.
     */
    private static int categoryRes(String slug) {
        if (slug == null) return 0;
        switch (slug) {
            case "komediya":    return R.string.SvipeVideoCategoryComedy;
            case "jangari":     return R.string.SvipeVideoCategoryAction;
            case "drama":       return R.string.SvipeVideoCategoryDrama;
            case "qorqinchli":  return R.string.SvipeVideoCategoryHorror;
            case "fantastika":  return R.string.SvipeVideoCategorySciFi;
            case "multfilm":    return R.string.SvipeVideoCategoryCartoons;
            case "anime":       return R.string.SvipeVideoCategoryAnime;
            case "serial":      return R.string.SvipeVideoCategorySeries;
            case "konsert":     return R.string.SvipeVideoCategoryConcerts;
            case "sport":       return R.string.SvipeVideoCategorySport;
            case "talim":       return R.string.SvipeVideoCategoryEducation;
            case "yangiliklar": return R.string.SvipeVideoCategoryNews;
            case "hujjatli":    return R.string.SvipeVideoCategoryDocumentary;
            case "kino":        return R.string.SvipeVideoCategoryMovies;
            default:            return 0;
        }
    }

    /**
     * The genre line under a film card, in the user's language.
     *
     * <p>{@code Movie.genres} is RAW caption text, lower-cased by the parser
     * ({@code app/movies/parse.py:282}) in whatever language the uploader typed — "боевик", "komediya" — and
     * can never be translated. The server also sends the film's own taxonomy slugs, so the localized
     * shelf name is used instead and the raw word stays only as the last resort. "kino" is skipped:
     * every film lands there, so it is a catch-all, not a genre.
     */
    public static String genreLabel(Movie m) {
        if (m == null) return "";
        for (String slug : m.categories) {
            if ("kino".equals(slug)) continue;
            final int res = categoryRes(slug);
            if (res != 0) return LocaleController.getString(res);
        }
        return m.genres.isEmpty() ? "" : m.genres.get(0);
    }

    /**
     * The second line of a film's row — "2016 · ★ 7.7", falling back to its shelf name when the film
     * carries neither. It sits where an ordinary video row shows "channel · views · age", so the two
     * are the same card with a different sentence in the same slot (SvipeWideVideoCell#bind).
     */
    public static String cardMeta(Movie m) {
        if (m == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        if (m.year > 0) {
            sb.append(m.year);
        }
        if (m.rating() > 0) {
            if (sb.length() > 0) sb.append("  •  ");
            sb.append(String.format(java.util.Locale.US, "★ %.1f", m.rating()));
        }
        return sb.length() == 0 ? genreLabel(m) : sb.toString();
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
        /** Taxonomy slugs this film sits in, server order. The localizable twin of {@link #genres}. */
        public final List<String> categories = new ArrayList<>();
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
        /**
         * Where this viewer stopped, when the show arrived as the "continue watching" offer:
         * {@link #resumeIndex} is the episode to open and {@link #resumeMs} the second to open it at
         * (0 when the last one was finished and the offer is the NEXT episode). -1 means the card is
         * an ordinary shelf card and opens wherever the local progress says.
         */
        public int resumeIndex = -1;
        public long resumeMs;
        /** Inline blur for the poster post, when the server had one (see SvipeThumb). */
        public String posterThumbB64;

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
            r.thumbB64 = s.posterThumbB64;
            r.width = 16;
            r.height = 9;
            return r;
        }
    }

    public interface SeriesCallback {
        void onResult(List<Series> series, Integer nextOffset, String error);
    }

    /**
     * One episode of a show — a Telegram post, plus where it sits in the running order.
     *
     * <p>Nothing is copied anywhere to make this playable: the episode stays in whatever channel
     * published it and we hold a reference to it. That is the whole difference between this playlist
     * and the channel-building design it replaces — a playlist here costs one row, not a channel.
     */
    public static class Episode {
        public long channelId;
        public int messageId;
        public String username;
        public String postUrl;
        public int season;        // 0 when the caption never said
        public int episode;       // 0 when the caption never said
        public int durationMs;
        public String title;

        /** The list position as a human reads it: "S2 · 7-qism", or a plain index when unnumbered. */
        public String label(int index) {
            if (episode > 0 && season > 0) {
                return LocaleController.formatString(R.string.SvipeSeasonEpisode, season, episode);
            }
            if (episode > 0) {
                return LocaleController.formatString(R.string.SvipeEpisodeNo, episode);
            }
            return LocaleController.formatString(R.string.SvipeEpisodeNo, index + 1);
        }

        /**
         * The same episode shaped as a feed reference, which is the only currency the player and the
         * watch page understand. 16:9 because a show is long-form by definition — the aspect decides
         * which player opens, and a missing one would route an episode into the reels player.
         */
        public SvipeDiscover.Item asItem() {
            SvipeDiscover.Item r = new SvipeDiscover.Item();
            r.channelId = channelId;
            r.messageId = messageId;
            r.username = username;
            r.width = 16;
            r.height = 9;
            r.durationMs = durationMs;
            return r;
        }
    }

    /** A show together with its episodes, in playlist order. This IS the playlist. */
    public static class SeriesPage {
        public Series series;
        public final List<Episode> episodes = new ArrayList<>();
        /** {@code svipe.uz/<code>} for the SHOW — a page that sells the app, not a Telegram link. */
        public String shareUrl;

        public boolean isEmpty() {
            return episodes.isEmpty();
        }
    }

    public interface SeriesPageCallback {
        void onResult(SeriesPage page, String error);
    }

    public static class ActorPage {
        public Actor actor;
        public final List<Movie> movies = new ArrayList<>();
        public Integer nextOffset;
        /**
         * The rest of their filmography — films the global index credits them with that we cannot
         * play. Listed under the ones we can, so the page shows the performer rather than our slice
         * of them. Empty when the local performer was never linked to a global identity: a name alone
         * cannot tell two people apart, and a wrong filmography is worse than none.
         */
        public final List<Suggestion> alsoIn = new ArrayList<>();
    }

    /**
     * A film that exists but that we do not have. {@link #sourceUrl} non-null means we can actually go
     * and get it, so the row is tappable; the rest are informational, because a tap must never promise
     * something nothing can deliver.
     */
    public static class Suggestion {
        public String title;
        public int year;
        public String qid;
        public String sourceUrl;
        public boolean requestable;
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
    /** What the viewer left unfinished: a show at an episode, or a lone video at a second. */
    public static class Continue {
        public Series series;              // set when the offer is a show
        public SvipeDiscover.Item video;   // set when it is a single long video
        public long positionMs;
        /** 0..1 of the video already watched — the card draws it as a bar along the picture. */
        public float progress;
    }

    public interface ContinueCallback {
        /** The offer, or null when there is nothing to carry on with. */
        void onResult(Continue offer, String error);
    }

    /**
     * What this viewer left unfinished — {@code GET /v1/videos/continue}.
     *
     * <p>Server-side, and derived from telemetry the app was already sending, so it survives a
     * reinstall and follows the account to another device. The answer is ONE offer: a show at the
     * episode to resume, or a lone video at its second. Rendered as the ordinary stacked playlist
     * card, pinned near the top of the Video tab — a refresh may move it down, but never changes what
     * it resumes.
     */
    public static void continueWatching(int account, ContinueCallback cb) {
        get(account, "/v1/videos/continue", (res, err) -> {
            if (res == null || !res.optBoolean("available", false)) {
                cb.onResult(null, err);
                return;
            }
            final Continue offer = new Continue();
            offer.positionMs = res.optLong("position_ms", 0);
            final long fullMs = res.optLong("duration_ms", 0);
            offer.progress = fullMs > 0 ? Math.min(1f, offer.positionMs / (float) fullMs) : 0f;
            final long channelId = res.optLong("channel_id");
            final int messageId = res.optInt("message_id");
            final String username = res.isNull("username") ? null : res.optString("username", null);
            final String blur = res.isNull("thumb_b64") ? null : res.optString("thumb_b64", null);
            if ("series".equals(res.optString("kind")) && res.optLong("series_id") > 0) {
                final Series s = new Series();
                s.id = res.optLong("series_id");
                s.title = res.isNull("series_title") ? null : res.optString("series_title", null);
                s.episodeCount = res.optInt("episode_count", 0);
                s.resumeIndex = res.optInt("index", 0);
                s.resumeMs = offer.positionMs;
                // The poster is the EPISODE being resumed rather than the show's own: the card should
                // show where the viewer IS, not where the show starts.
                s.posterChannelId = channelId;
                s.posterMessageId = messageId;
                s.posterUsername = username;
                s.posterThumbB64 = blur;
                if (s.title == null || s.title.isEmpty()) {
                    s.title = username != null ? ("@" + username) : "";
                }
                offer.series = s;
            } else {
                final SvipeDiscover.Item v = new SvipeDiscover.Item();
                v.channelId = channelId;
                v.messageId = messageId;
                v.username = username;
                v.thumbB64 = blur;
                v.width = 16;
                v.height = 9;
                offer.video = v;
            }
            // Whoever opens this reference next opens it where it was left — the player consumes the
            // request once, so an ordinary later visit goes back to the local mark.
            org.telegram.svipe.video.SvipeVideoPlayerController.requestStartAt(
                    channelId, messageId, offer.positionMs);
            cb.onResult(offer, null);
        });
    }

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

    /** A show and every episode it has, in order — the playlist behind the show page. */
    public static void seriesDetail(int account, long seriesId, SeriesPageCallback cb) {
        get(account, "/v1/series/" + seriesId, (res, err) -> {
            if (res == null || !res.has("series")) {
                cb.onResult(null, err != null ? err : "empty");
                return;
            }
            SeriesPage page = new SeriesPage();
            page.series = parseSeries(res.optJSONObject("series"));
            page.shareUrl = res.isNull("share_url") ? null : res.optString("share_url", null);
            JSONArray arr = res.optJSONArray("episodes");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Episode e = new Episode();
                e.channelId = o.optLong("channel_id");
                e.messageId = o.optInt("message_id");
                e.username = o.isNull("username") ? null : o.optString("username", null);
                e.postUrl = o.isNull("post_url") ? null : o.optString("post_url", null);
                e.season = o.isNull("season") ? 0 : o.optInt("season");
                e.episode = o.isNull("episode") ? 0 : o.optInt("episode");
                e.durationMs = o.optInt("duration_ms");
                e.title = o.isNull("title") ? null : o.optString("title", null);
                if (e.messageId != 0) page.episodes.add(e);
            }
            cb.onResult(page, null);
        });
    }

    public static void movie(int account, long movieId, MovieCallback cb) {
        get(account, "/v1/movies/" + movieId, (res, err) -> {
            if (res == null || !res.has("movie")) {
                cb.onResult(null, err != null ? err : "empty");
                return;
            }
            cb.onResult(parseDetail(res), null);
        });
    }

    /**
     * Both film endpoints answer with the SAME shape, so they are read by the same parser. They were
     * not: by-post skipped the versions, because the watch page only wanted the cast back then — and
     * the moment that page grew a "Variants" tab it silently had nothing to put in it.
     */
    private static Series parseSeries(JSONObject o) {
        Series s = new Series();
        if (o == null) return s;
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
        return s;
    }

    private static MovieDetail parseDetail(JSONObject res) {
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
        return d;
    }

    /**
     * The film a Telegram post is a copy of — the watch page's reverse lookup for its own tabs.
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
                    cb.onResult(parseDetail(res), null);
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
                    parseSuggestions(res.optJSONArray("also_in"), p.alsoIn);
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
        JSONArray cats = o.optJSONArray("categories");
        for (int i = 0; cats != null && i < cats.length(); i++) {
            String s = cats.optString(i, null);
            if (s != null && !s.isEmpty()) m.categories.add(s);
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

    private static void parseSuggestions(JSONArray arr, List<Suggestion> out) {
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String title = o.optString("title", "");
            if (title.isEmpty()) {
                continue;
            }
            Suggestion s = new Suggestion();
            s.title = title;
            s.year = o.optInt("year");
            s.qid = o.isNull("qid") ? null : o.optString("qid", null);
            s.sourceUrl = o.isNull("source_url") ? null : o.optString("source_url", null);
            s.requestable = o.optBoolean("requestable") && s.sourceUrl != null && !s.sourceUrl.isEmpty();
            out.add(s);
        }
    }

    /**
     * "Get me this one" — the tap on a film we don't have but could fetch. Records demand only; the
     * fetch is a worker's job, because a film is a gigabyte. Asking twice raises its queue position
     * rather than duplicating the work.
     */
    public static void requestFilm(int account, Suggestion s, AckCallback cb) {
        if (s == null || s.sourceUrl == null || s.sourceUrl.isEmpty()) {
            cb.onResult(false, "not requestable");
            return;
        }
        SvipeAuth.ensureToken(account, token -> {
            if (token == null) {
                cb.onResult(false, "auth");
                return;
            }
            String path = "/v1/movies/request?source_url=" + encode(s.sourceUrl)
                    + (s.title != null && !s.title.isEmpty() ? "&title=" + encode(s.title) : "");
            SvipeApi.post(path, null, token, (res, code, err) ->
                    cb.onResult(code >= 200 && code < 300, err));
        });
    }
}
