package org.telegram.svipe;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.MessagesController;

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

    /** Auth-bot peer id, remembered per bot username. contacts.resolveUsername is one of Telegram's
     *  most flood-limited calls, and a fresh account spends its budget fast (the feed resolves a
     *  channel per item); paying it once for the bot and never again keeps first-launch auth off
     *  that budget entirely. Keyed by username so dev and prod bots can never be confused. */
    public static final String PREF_AUTH_BOT_ID_PREFIX = "svipe_auth_bot_id_";

    public static String prefAuthBotId() {
        return PREF_AUTH_BOT_ID_PREFIX + botUsername().toLowerCase();
    }

    /** When a FLOOD_WAIT on the bot resolve expires (epoch ms). Retrying inside the window is not
     *  just pointless — Telegram can extend the wait for it. */
    public static final String PREF_AUTH_BOT_FLOOD_UNTIL = "svipe_auth_bot_flood_until";

    /** Has this user ever opened the Music tab? Gates the app-start vibe warm-up: music is a place
     *  you choose to go, and warming it for someone who never goes there spends their data and their
     *  resolveUsername budget for nothing. See SvipeMusicWarmer. */
    public static final String PREF_MUSIC_USED = "svipe_music_used";

    /** Has this user ever opened the Video tab? Same gate as PREF_MUSIC_USED, same reason. */
    public static final String PREF_VIDEO_USED = "svipe_video_used";

    // ---- Bots as their own notification category (SvipeBotMute) ----
    // Telegram files bots under "Private chats", so silencing bots means silencing everyone. These
    // hold OUR rule; the mutes themselves are Telegram's own per-peer settings.
    public static final String PREF_BOT_MUTE = "svipe_bot_mute";
    public static final String PREF_BOT_MUTE_EXCEPTIONS = "svipe_bot_mute_exceptions";
    /** Peers WE muted, so switching the rule off never unmutes a bot the user silenced by hand. */
    public static final String PREF_BOT_MUTE_APPLIED = "svipe_bot_mute_applied";
    public static final String PREF_BOT_MUTE_UPDATED = "svipe_bot_mute_updated";

    /** Buffered performance samples awaiting upload (JSON array) — see SvipePerf. Survives a kill:
     *  a session that ended offline is exactly the session whose numbers we most want. */
    public static final String PREF_PERF_BUFFER = "svipe_perf_buffer";

    /** Persisted offline ready-queue (JSON blob) and the watched-reel ledger (JSON array). */
    public static final String PREF_REEL_QUEUE = "svipe_reel_queue";
    public static final String PREF_REEL_WATCHED = "svipe_reel_watched";
    /** Persistent set of blocked reels channel ids (JSON array of longs) — SvipeBlockedChannels. */
    public static final String PREF_REEL_BLOCKED_CHANNELS = "svipe_reel_blocked_channels";

    /** Recent search queries (JSON array, most-recent-first) — the two search-history stores. */
    public static final String PREF_MUSIC_SEARCH_HISTORY = "svipe_music_search_history";
    public static final String PREF_VIDEO_SEARCH_HISTORY = "svipe_video_search_history";

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

    // ---- Deleted/edited message archive: per-chat "Show in chat" toggle (default OFF) ----
    // Capture is ALWAYS on regardless of this; this only controls whether deleted messages stay
    // inline in the chat (with a red "Deleted" tag) and edit history is offered there.
    public static final String PREF_SHOW_IN_CHAT_PREFIX = "svipe_show_in_chat_";

    public static boolean isShowInChat(int account, long dialogId) {
        try {
            return MessagesController.getMainSettings(account).getBoolean(PREF_SHOW_IN_CHAT_PREFIX + dialogId, false);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setShowInChat(int account, long dialogId, boolean on) {
        try {
            MessagesController.getMainSettings(account).edit().putBoolean(PREF_SHOW_IN_CHAT_PREFIX + dialogId, on).apply();
        } catch (Exception e) {
            // best-effort
        }
    }

    // ---- Avatar archive sync (SvipeAvatarSync): pooling DELETED profile photos across Svipe apps ----
    // Local capture (SvipeAvatarKeeper) is independent of these and keeps working with sync off.
    public static final String PREF_AVATAR_SYNC = "svipe_avatar_sync";
    public static final String PREF_AVATAR_SYNC_WIFI_ONLY = "svipe_avatar_sync_wifi_only";

    public static boolean isAvatarSyncEnabled(int account) {
        try {
            return MessagesController.getMainSettings(account).getBoolean(PREF_AVATAR_SYNC, true);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setAvatarSyncEnabled(int account, boolean on) {
        try {
            MessagesController.getMainSettings(account).edit().putBoolean(PREF_AVATAR_SYNC, on).apply();
        } catch (Exception e) {
            // best-effort
        }
    }

    /** Uploads are metered traffic on someone else's behalf, so they default to Wi-Fi only. */
    public static boolean isAvatarSyncWifiOnly(int account) {
        try {
            return MessagesController.getMainSettings(account).getBoolean(PREF_AVATAR_SYNC_WIFI_ONLY, true);
        } catch (Exception e) {
            return true;
        }
    }

    // ---- Number history sync (SvipeNumberSync): pooling number<->account changes across Svipe apps ----
    // Local capture (SvipeNumberHistory) is independent of this and keeps working with sync off.
    //
    // On by default, like every other sharing default in the app and in Telegram itself: a fresh
    // account starts open and stays that way until its owner narrows it by hand. What keeps that
    // from being an exposure is the read side — the pool only ever answers about somebody whose
    // number Telegram already shows the person asking, so contributing cannot put anyone further
    // into the open than they already are.
    public static final String PREF_NUMBER_SYNC = "svipe_number_sync";

    public static boolean isNumberSyncEnabled(int account) {
        try {
            return MessagesController.getMainSettings(account).getBoolean(PREF_NUMBER_SYNC, true);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setNumberSyncEnabled(int account, boolean on) {
        try {
            MessagesController.getMainSettings(account).edit().putBoolean(PREF_NUMBER_SYNC, on).apply();
        } catch (Exception e) {
            // best-effort
        }
    }

    /** Cached copy of the server-side setting for MY OWN history, so the row shows a value at once. */
    public static final String PREF_NUMBER_VISIBILITY = "svipe_number_visibility";

    public static String getNumberVisibility(int account) {
        try {
            return MessagesController.getMainSettings(account).getString(PREF_NUMBER_VISIBILITY, "everyone");
        } catch (Exception e) {
            return "everyone";
        }
    }

    public static void setNumberVisibility(int account, String value) {
        try {
            MessagesController.getMainSettings(account).edit().putString(PREF_NUMBER_VISIBILITY, value).apply();
        } catch (Exception e) {
            // best-effort
        }
    }

    /** Last visibility the server told us, cached so the Privacy row can show a value immediately
     *  instead of blanking until a request comes back. The server stays the source of truth. */
    public static final String PREF_AVATAR_VISIBILITY = "svipe_avatar_visibility";

    public static String getAvatarVisibility(int account) {
        try {
            return MessagesController.getMainSettings(account).getString(PREF_AVATAR_VISIBILITY, "everyone");
        } catch (Exception e) {
            return "everyone";
        }
    }

    public static void setAvatarVisibility(int account, String value) {
        try {
            MessagesController.getMainSettings(account).edit().putString(PREF_AVATAR_VISIBILITY, value).apply();
        } catch (Exception e) {
            // best-effort
        }
    }

    public static void setAvatarSyncWifiOnly(int account, boolean on) {
        try {
            MessagesController.getMainSettings(account).edit().putBoolean(PREF_AVATAR_SYNC_WIFI_ONLY, on).apply();
        } catch (Exception e) {
            // best-effort
        }
    }

    // ---- Message sync (SvipeMessageSync): P2P archive of deleted/edited messages ----
    // Cached copy of my server-held mode ("" = not decided), so the Privacy row and the in-chat banner
    // can render instantly. The server stays the source of truth.
    public static final String PREF_MSG_SYNC_MODE = "svipe_msg_sync_mode";
    // Per-chat dismissal of the mode-picker banner (× tapped): don't nag again in that chat.
    public static final String PREF_MSG_SYNC_PROMPT_DISMISSED_PREFIX = "svipe_msg_sync_prompt_dismissed_";

    public static String getMsgSyncMode(int account) {
        try {
            return MessagesController.getMainSettings(account).getString(PREF_MSG_SYNC_MODE, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static void setMsgSyncMode(int account, String mode) {
        try {
            MessagesController.getMainSettings(account).edit()
                    .putString(PREF_MSG_SYNC_MODE, mode == null ? "" : mode).apply();
        } catch (Exception e) {
            // best-effort
        }
    }

    /** Has the user already decided a mode (any of the three)? Blank means never asked. */
    public static boolean hasMsgSyncMode(int account) {
        String m = getMsgSyncMode(account);
        return m != null && !m.isEmpty();
    }

    // ---- Consent prompt state (SvipeMsgSyncPrompt) ----
    // The big 3-option permission dialog is shown ONCE; after a rejection the user is nudged with a
    // snackbar (once per chat per day) and the big dialog auto-reappears once a month later, then only
    // when re-armed from Settings. These persist that state.
    public static final String PREF_MSG_SYNC_BIG_SHOWN = "svipe_msg_sync_big_shown";
    public static final String PREF_MSG_SYNC_NEXT_BIG_AT = "svipe_msg_sync_next_big_at"; // epoch ms; 0 = none
    public static final String PREF_MSG_SYNC_SNACKBAR_DAY_PREFIX = "svipe_msg_sync_snack_day_"; // per-chat epoch-day

    public static boolean isMsgSyncBigShown(int account) {
        try {
            return MessagesController.getMainSettings(account).getBoolean(PREF_MSG_SYNC_BIG_SHOWN, false);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setMsgSyncBigShown(int account, boolean shown) {
        try {
            MessagesController.getMainSettings(account).edit().putBoolean(PREF_MSG_SYNC_BIG_SHOWN, shown).apply();
        } catch (Exception e) {
            // best-effort
        }
    }

    /** When the big dialog is due to reappear (epoch ms), or 0 if none scheduled. */
    public static long getMsgSyncNextBigAt(int account) {
        try {
            return MessagesController.getMainSettings(account).getLong(PREF_MSG_SYNC_NEXT_BIG_AT, 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    public static void setMsgSyncNextBigAt(int account, long whenMs) {
        try {
            MessagesController.getMainSettings(account).edit().putLong(PREF_MSG_SYNC_NEXT_BIG_AT, whenMs).apply();
        } catch (Exception e) {
            // best-effort
        }
    }

    /** "Don't ask in a month": until this epoch ms, every sync prompt (snackbar + dialog) is suppressed. */
    public static final String PREF_MSG_SYNC_MUTED_UNTIL = "svipe_msg_sync_muted_until";

    public static long getMsgSyncMutedUntil(int account) {
        try {
            return MessagesController.getMainSettings(account).getLong(PREF_MSG_SYNC_MUTED_UNTIL, 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    public static void setMsgSyncMutedUntil(int account, long whenMs) {
        try {
            MessagesController.getMainSettings(account).edit().putLong(PREF_MSG_SYNC_MUTED_UNTIL, whenMs).apply();
        } catch (Exception e) {
            // best-effort
        }
    }

    /** Epoch-day the nudge snackbar was last shown in this chat (for the once-per-chat-per-day rule). */
    public static long getMsgSyncSnackbarDay(int account, long dialogId) {
        try {
            return MessagesController.getMainSettings(account)
                    .getLong(PREF_MSG_SYNC_SNACKBAR_DAY_PREFIX + dialogId, 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    public static void setMsgSyncSnackbarDay(int account, long dialogId, long epochDay) {
        try {
            MessagesController.getMainSettings(account).edit()
                    .putLong(PREF_MSG_SYNC_SNACKBAR_DAY_PREFIX + dialogId, epochDay).apply();
        } catch (Exception e) {
            // best-effort
        }
    }
}
