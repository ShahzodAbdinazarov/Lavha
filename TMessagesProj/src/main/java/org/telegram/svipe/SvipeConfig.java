package org.telegram.svipe;

import org.telegram.messenger.BuildVars;

/** Svipe backend connection constants. Prod (release) -> svipe.uz; dev/beta build -> lavha-dev. */
public class SvipeConfig {
    private static final String PROD_BASE_URL = "https://svipe.uz";
    private static final String DEV_BASE_URL = "https://lavha-dev.abdinazarov.uz";
    private static final String PROD_BOT = "Svipe_auth_bot";
    private static final String DEV_BOT = "Lavha_auth_bot";

    /** Beta/debug build talks to the dev backend + dev bot; release/standalone -> prod (svipe.uz + Svipe bot). */
    public static String baseUrl() {
        return BuildVars.isBetaApp() ? DEV_BASE_URL : PROD_BASE_URL;
    }

    /** Each environment has its OWN auth bot, which owns its own webhook + menu web-app. */
    public static String botUsername() {
        return BuildVars.isBetaApp() ? DEV_BOT : PROD_BOT;
    }

    /** The bot menu web-app lives on the same host as the backend it authenticates against. */
    public static String webAppUrl() {
        return baseUrl() + "/webapp";
    }

    public static final String PREF_TOKEN = "svipe_access_token";
    public static final String PREF_REFRESH = "svipe_refresh_token";
    public static final String PREF_EXPIRES = "svipe_token_expires";

    /** Persisted offline ready-queue (JSON blob) and the watched-reel ledger (JSON array). */
    public static final String PREF_REEL_QUEUE = "svipe_reel_queue";
    public static final String PREF_REEL_WATCHED = "svipe_reel_watched";

    /** Favourite songs: the local store (JSON blob) + when it last reconciled with the backend. */
    public static final String PREF_MUSIC_FAVOURITES = "svipe_music_favourites";
    public static final String PREF_MUSIC_FAV_SYNCED_AT = "svipe_music_favourites_synced_at";
    /** Song ids un-favourited locally whose DELETE the backend has not acknowledged yet. */
    public static final String PREF_MUSIC_FAV_PENDING_REMOVALS = "svipe_music_favourites_pending_removals";

    /** Favourite singers: same three-part store as the songs above, kept separate so the two lists
     *  can never collide on a key (song ids and artist ids share a namespace only by accident). */
    public static final String PREF_MUSIC_ARTIST_FAVOURITES = "svipe_music_artist_favourites";
    public static final String PREF_MUSIC_ARTIST_FAV_SYNCED_AT = "svipe_music_artist_favourites_synced_at";
    /** Artist ids un-favourited locally whose DELETE the backend has not acknowledged yet. */
    public static final String PREF_MUSIC_ARTIST_FAV_PENDING_REMOVALS = "svipe_music_artist_favourites_pending_removals";
}
