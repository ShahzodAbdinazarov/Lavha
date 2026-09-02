package org.telegram.svipe;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Seamless auth for the forked client, fully invisible in the UI. Token chain:
 *   1. cached access token (shared prefs)
 *   2. POST /v1/auth/refresh with the stored refresh token (plain HTTPS, no Telegram traffic)
 *   3. Mini App initData: messages.requestWebView on @Svipe_auth_bot's menu button — Telegram
 *      returns a signed tgWebAppData payload, the backend verifies it offline. No chat, no
 *      message, nothing syncs to other devices; the web view is never rendered.
 *   4. legacy fallback: "/start <nonce>" to the bot + poll (kept until initData is proven in prod)
 */
public class SvipeAuth {

    public interface TokenCallback {
        void run(String token);
    }

    private interface BotCallback {
        void run(long botId);
    }

    public static String getStoredToken(int account) {
        SharedPreferences p = MessagesController.getMainSettings(account);
        String t = p.getString(SvipeConfig.PREF_TOKEN, null);
        long exp = p.getLong(SvipeConfig.PREF_EXPIRES, 0);
        if (t != null && t.length() > 0 && System.currentTimeMillis() < exp - 60000L) {
            return t;
        }
        return null;
    }

    // Single-flight: concurrent callers for the same account share ONE auth flow instead of each
    // firing its own /refresh (+ bot /start), which would race and clobber the stored tokens.
    private static final java.util.HashMap<Integer, java.util.ArrayList<TokenCallback>> inFlight = new java.util.HashMap<>();

    /**
     * Deadlines. Every link in the chain must answer, or be treated as having answered "no" — an
     * auth flow that simply never calls back used to park every later caller in {@link #inFlight}
     * forever, killing reels, music, video and telemetry for the whole process until a restart.
     * The one observed cause was a FLOOD_WAIT on the bot's contacts.resolveUsername: without
     * RequestFlagFailOnServerErrors, tgnet swallows a 420 and silently re-queues the request behind
     * the wait (measured: FLOOD_WAIT_4473 — 74 minutes), so the Java callback never runs.
     */
    private static final long CHAIN_DEADLINE_MS = 30_000;   // whole auth chain
    private static final long MTPROTO_STEP_MS = 8_000;      // resolving the bot: cheap, cached, or floods
    // The web-app call gets longer: timing it out early would push a merely-slow link onto the
    // legacy /start fallback, which sends a real message and leaves a bot chat in the user's list.
    private static final long WEBVIEW_STEP_MS = 12_000;

    public static void ensureToken(int account, TokenCallback cb) {
        String stored = getStoredToken(account);
        if (stored != null) {
            cb.run(stored);
            return;
        }
        synchronized (inFlight) {
            java.util.ArrayList<TokenCallback> waiters = inFlight.get(account);
            if (waiters != null) { // an auth flow is already running for this account — just wait for it
                waiters.add(cb);
                return;
            }
            waiters = new java.util.ArrayList<>();
            waiters.add(cb);
            inFlight.put(account, waiters);
        }
        // Completes EXACTLY once, from whichever comes first: the chain or its deadline. A late real
        // answer is not wasted — the token it stores is picked up by the next ensureToken call.
        final AtomicBoolean settled = new AtomicBoolean();
        final long startedAt = System.currentTimeMillis();
        final TokenCallback finish = token -> {
            if (!settled.compareAndSet(false, true)) return;
            // Auth is the first thing standing between a new user and their first reel, and the one
            // step we cannot see from a log line on someone else's phone.
            SvipePerf.sample("auth_latency", System.currentTimeMillis() - startedAt)
                    .context(token != null ? "ok" : "failed")
                    .submit(account);
            final java.util.ArrayList<TokenCallback> waiters;
            synchronized (inFlight) {
                waiters = inFlight.remove(account);
            }
            if (waiters == null) return;
            AndroidUtilities.runOnUIThread(() -> {
                for (TokenCallback w : waiters) {
                    try { w.run(token); } catch (Exception ignore) {}
                }
            });
        };
        AndroidUtilities.runOnUIThread(() -> {
            if (!settled.get()) {
                FileLog.d("svipe: auth chain deadline hit — releasing waiters so callers can retry");
                finish.run(null);
            }
        }, CHAIN_DEADLINE_MS);
        authChain(account, finish);
    }

    /**
     * {@link #ensureToken} with ONE retry, for the surfaces whose only alternative is an empty screen.
     *
     * <p>The auth chain has three legs (refresh, bot web-app, legacy /start) and each can come up
     * empty for reasons that are gone a second later: the process just started and MTProto has not
     * connected, the network flipped, a leg hit its deadline. A single "no" used to leave the Video
     * tab, the shorts grid and Music BLANK UNTIL THE APP WAS KILLED — measured on a device whose
     * refresh token was valid the whole time, whose access token had been expired for 25 hours, and
     * where one manual POST to /v1/auth/refresh answered 200 straight away.
     *
     * <p>Exactly one retry: a chain that fails twice, seconds apart, is not a flap.
     */
    public static void ensureTokenRetrying(int account, TokenCallback cb) {
        ensureToken(account, token -> {
            if (token != null) {
                cb.run(token);
                return;
            }
            AndroidUtilities.runOnUIThread(() -> ensureToken(account, cb), AUTH_RETRY_DELAY_MS);
        });
    }

    /** Long enough for a flapping network or a cold MTProto to settle, short enough to feel instant. */
    private static final long AUTH_RETRY_DELAY_MS = 2500;

    private static void authChain(int account, TokenCallback cb) {
        final long deadlineAt = System.currentTimeMillis() + CHAIN_DEADLINE_MS;
        refreshToken(account, refreshed -> {
            if (refreshed != null) {
                cb.run(refreshed);
                return;
            }
            webAppAuth(account, webApp -> {
                if (webApp != null) {
                    cb.run(webApp);
                    return;
                }
                // The legacy fallback sends "/start" TO the bot, so it needs the same peer the
                // web-app path just failed to get. When the bot itself is what we could not
                // resolve (a flood window, typically), there is nothing left to try this round —
                // and pretending otherwise would fire three backend calls plus a poll loop for a
                // flow that cannot possibly complete.
                if (!botKnown(account)) {
                    cb.run(null);
                    return;
                }
                legacyBotAuth(account, deadlineAt, cb);
            });
        });
    }

    /** Drops the cached access token so the next ensureToken() re-authenticates (e.g. after 401). */
    public static void invalidateAccessToken(int account) {
        MessagesController.getMainSettings(account).edit()
                .remove(SvipeConfig.PREF_TOKEN)
                .remove(SvipeConfig.PREF_EXPIRES)
                .apply();
    }

    private static void refreshToken(int account, TokenCallback cb) {
        SharedPreferences p = MessagesController.getMainSettings(account);
        String refresh = p.getString(SvipeConfig.PREF_REFRESH, null);
        if (refresh == null || refresh.isEmpty()) {
            cb.run(null);
            return;
        }
        JSONObject body = new JSONObject();
        try { body.put("refresh_token", refresh); } catch (Exception ignore) {}
        SvipeApi.post("/v1/auth/refresh", body, null, (res, code, err) -> {
            if (res != null && "ok".equals(res.optString("status"))) {
                storeTokens(account, res);
                cb.run(res.optString("access_token"));
            } else {
                if (code == 401) {
                    // Revoked or expired server-side — forget it so we don't ride a dead token.
                    p.edit().remove(SvipeConfig.PREF_REFRESH).apply();
                }
                cb.run(null);
            }
        });
    }

    private static void webAppAuth(int account, TokenCallback cb) {
        resolveBot(account, botId -> {
            if (botId == 0) {
                AndroidUtilities.runOnUIThread(() -> cb.run(null));
                return;
            }
            MessagesController mc = MessagesController.getInstance(account);
            TLRPC.TL_messages_requestWebView req = new TLRPC.TL_messages_requestWebView();
            req.bot = mc.getInputUser(botId);
            req.peer = mc.getInputPeer(botId);
            req.platform = "android";
            req.url = SvipeConfig.webAppUrl();
            req.flags |= 2;
            req.from_bot_menu = true;
            final AtomicBoolean answered = new AtomicBoolean();
            // FailOnServerErrors: hand us a 420/500 instead of parking the request behind an internal
            // retry we would never hear about. The timeout covers the rest (no response at all).
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (!answered.compareAndSet(false, true)) return;
                String initData = null;
                if (error == null && response instanceof TLRPC.TL_webViewResultUrl) {
                    initData = SvipeInitData.extract(((TLRPC.TL_webViewResultUrl) response).url);
                } else if (error != null) {
                    FileLog.d("svipe: auth requestWebView failed: " + error.text);
                }
                if (initData == null) {
                    AndroidUtilities.runOnUIThread(() -> cb.run(null));
                    return;
                }
                JSONObject body = new JSONObject();
                try { body.put("init_data", initData); } catch (Exception ignore) {}
                SvipeApi.post("/v1/auth/telegram/webapp", body, null, (res, code, err) -> {
                    if (res != null && "ok".equals(res.optString("status"))) {
                        storeTokens(account, res);
                        cb.run(res.optString("access_token"));
                    } else {
                        cb.run(null);
                    }
                });
            }, ConnectionsManager.RequestFlagFailOnServerErrors);
            AndroidUtilities.runOnUIThread(() -> {
                if (answered.compareAndSet(false, true)) {
                    FileLog.d("svipe: auth requestWebView timed out");
                    cb.run(null);
                }
            }, WEBVIEW_STEP_MS);
        });
    }

    // ---- legacy deep-link flow (fallback only) ----

    private static void legacyBotAuth(int account, long deadlineAt, TokenCallback cb) {
        SvipeApi.post("/v1/auth/telegram/start", new JSONObject(), null, (res, code, err) -> {
            if (res == null) { cb.run(null); return; }
            String nonce = res.optString("nonce", null);
            if (nonce == null || nonce.isEmpty()) { cb.run(null); return; }
            sendStartToBot(account, nonce);
            pollToken(account, nonce, 0, deadlineAt, cb);
        });
    }

    /**
     * The auth bot's peer id. Three sources, cheapest first — the network resolve is the LAST resort
     * because contacts.resolveUsername is flood-limited per account and a new user's budget is
     * already being spent resolving feed channels. Once known, the id is remembered forever
     * ({@link SvipeConfig#prefAuthBotId()}), so this costs a round-trip once per install.
     */
    private static void resolveBot(int account, BotCallback cb) {
        final MessagesController mc = MessagesController.getInstance(account);
        final String username = SvipeConfig.botUsername();
        // 1. Telegram's own username cache (populated by any earlier resolve, contact or dialog).
        TLObject cached = mc.getUserOrChat(username);
        if (cached instanceof TLRPC.User && ((TLRPC.User) cached).id != 0) {
            long id = ((TLRPC.User) cached).id;
            rememberBotId(account, id);
            cb.run(id);
            return;
        }
        // 2. Our own note of it — only trusted while the user object (and its access_hash) is loaded.
        long remembered = MessagesController.getMainSettings(account).getLong(SvipeConfig.prefAuthBotId(), 0);
        if (remembered != 0 && mc.getUser(remembered) != null) {
            cb.run(remembered);
            return;
        }
        // 3. Ask the server, bounded on both sides: FailOnServerErrors so a FLOOD_WAIT comes back as
        // an error instead of being re-queued behind the wait, and a timeout for silence. While a
        // known flood window is open we don't ask at all — the answer is already known, and hammering
        // a flood-limited method is how a short wait becomes a long one.
        // 3. Search for the bot by name. contacts.search answers with whole user objects —
        // access_hash included, which is the only thing the resolve was ever for — and it draws on a
        // different budget from contacts.resolveUsername. This matters most for exactly the user we
        // care about: an account with an empty chat list has nothing in the username cache, so step 1
        // always misses and a fresh install spends its one resolve here. Measured on the test
        // account: the resolve answered FLOOD_WAIT_5711 and every Svipe tab then said "No
        // connection", because no token means no request at all. (A t.me/<bot> link was tried first
        // and is not an option — messages.getWebPage returns a profile page with no users on it.)
        searchBot(account, id -> {
            if (id != 0) {
                rememberBotId(account, id);
                cb.run(id);
                return;
            }
            resolveBotOverContacts(account, cb);
        });
    }

    /**
     * Look the bot up by name and read its user out of the results. 0 when nothing matches.
     *
     * <p>Only an exact username match counts. Search is a fuzzy method — it will happily return
     * whatever else is called something similar — and sending a user's auth handshake to the wrong
     * bot is worse than not authenticating at all.
     */
    private static void searchBot(int account, BotCallback cb) {
        final String username = SvipeConfig.botUsername();
        final TLRPC.TL_contacts_search req = new TLRPC.TL_contacts_search();
        req.q = username;
        req.limit = 5;
        final AtomicBoolean answered = new AtomicBoolean();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (!answered.compareAndSet(false, true)) return;
            long botId = 0;
            if (error == null && response instanceof TLRPC.TL_contacts_found) {
                TLRPC.TL_contacts_found res = (TLRPC.TL_contacts_found) response;
                MessagesController.getInstance(account).putUsers(res.users, false);
                MessagesController.getInstance(account).putChats(res.chats, false);
                if (res.users != null) {
                    for (TLRPC.User u : res.users) {
                        if (u != null && u.username != null
                                && u.username.equalsIgnoreCase(username)) {
                            botId = u.id;
                            break;
                        }
                    }
                }
            }
            FileLog.d("svipe: auth bot via search -> " + botId
                    + (error != null ? " (" + error.text + ")" : ""));
            cb.run(botId);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
        AndroidUtilities.runOnUIThread(() -> {
            if (answered.compareAndSet(false, true)) {
                FileLog.d("svipe: auth bot search timed out");
                cb.run(0);
            }
        }, MTPROTO_STEP_MS);
    }

    /** Last resort: the rationed method, and only while no flood window is open. */
    private static void resolveBotOverContacts(int account, BotCallback cb) {
        final MessagesController mc = MessagesController.getInstance(account);
        final String username = SvipeConfig.botUsername();
        long floodUntil = MessagesController.getMainSettings(account).getLong(SvipeConfig.PREF_AUTH_BOT_FLOOD_UNTIL, 0);
        if (floodUntil > System.currentTimeMillis()) {
            SvipeLimitLog.denied(account, SvipeLimitLog.RESOLVE_USERNAME, SvipeLimitLog.AUTH_BOT,
                    false, (int) ((floodUntil - System.currentTimeMillis()) / 1000), username, "auth");
            cb.run(0);
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        final AtomicBoolean answered = new AtomicBoolean();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (!answered.compareAndSet(false, true)) return;
            long botId = 0;
            if (error == null && response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer rp = (TLRPC.TL_contacts_resolvedPeer) response;
                mc.putUsers(rp.users, false);
                mc.putChats(rp.chats, false);
                if (rp.users != null && !rp.users.isEmpty()) {
                    botId = rp.users.get(0).id;
                    rememberBotId(account, botId);
                }
                SvipeLimitLog.ok(account, SvipeLimitLog.RESOLVE_USERNAME, SvipeLimitLog.AUTH_BOT,
                        username, "auth");
            } else if (error != null) {
                FileLog.d("svipe: auth bot resolve failed: " + error.text);
                SvipeLimitLog.failed(account, SvipeLimitLog.RESOLVE_USERNAME, SvipeLimitLog.AUTH_BOT,
                        error, username, "auth");
                rememberFloodWait(account, error.text);
            }
            cb.run(botId);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
        AndroidUtilities.runOnUIThread(() -> {
            if (answered.compareAndSet(false, true)) {
                FileLog.d("svipe: auth bot resolve timed out");
                cb.run(0);
            }
        }, MTPROTO_STEP_MS);
    }

    /** Do we hold the auth bot's peer without going to the network? */
    private static boolean botKnown(int account) {
        MessagesController mc = MessagesController.getInstance(account);
        if (mc.getUserOrChat(SvipeConfig.botUsername()) instanceof TLRPC.User) return true;
        long remembered = MessagesController.getMainSettings(account).getLong(SvipeConfig.prefAuthBotId(), 0);
        return remembered != 0 && mc.getUser(remembered) != null;
    }

    /** Note how long Telegram told us to wait, so we stop asking until it is over. */
    private static void rememberFloodWait(int account, String errorText) {
        int seconds = SvipeFloodWait.secondsIn(errorText);
        if (seconds <= 0) return;
        try {
            MessagesController.getMainSettings(account).edit()
                    .putLong(SvipeConfig.PREF_AUTH_BOT_FLOOD_UNTIL, System.currentTimeMillis() + seconds * 1000L)
                    .apply();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static void rememberBotId(int account, long botId) {
        if (botId == 0) return;
        try {
            MessagesController.getMainSettings(account).edit()
                    .putLong(SvipeConfig.prefAuthBotId(), botId).apply();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static void sendStartToBot(int account, String nonce) {
        resolveBot(account, botId -> {
            if (botId == 0) return;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    SendMessagesHelper.SendMessageParams params =
                            SendMessagesHelper.SendMessageParams.of("/start " + nonce, botId);
                    params.notify = false;
                    SendMessagesHelper.getInstance(account).sendMessage(params);
                } catch (Exception ignore) {}
            });
        });
    }

    /**
     * Polls until the bot's /start lands — bounded by the chain deadline as well as by the attempt
     * count, so a poll loop can never outlive the flow that started it and pile up on top of the
     * next one (callers retry on their own schedule).
     */
    private static void pollToken(int account, String nonce, int attempt, long deadlineAt, TokenCallback cb) {
        if (attempt > 20 || System.currentTimeMillis() >= deadlineAt) { cb.run(null); return; }
        JSONObject body = new JSONObject();
        try { body.put("nonce", nonce); } catch (Exception ignore) {}
        SvipeApi.post("/v1/auth/telegram/poll", body, null, (res, code, err) -> {
            if (res != null && "ok".equals(res.optString("status"))) {
                storeTokens(account, res);
                cb.run(res.optString("access_token"));
            } else if (code == 404) {
                cb.run(null);
            } else {
                AndroidUtilities.runOnUIThread(() -> pollToken(account, nonce, attempt + 1, deadlineAt, cb), 1500);
            }
        });
    }

    private static void storeTokens(int account, JSONObject res) {
        SharedPreferences.Editor e = MessagesController.getMainSettings(account).edit();
        e.putString(SvipeConfig.PREF_TOKEN, res.optString("access_token"));
        // Only overwrite the refresh token when the response actually carries a new one — a refresh
        // response that omits it must NOT wipe the stored token (that would force a re-login).
        String refresh = res.optString("refresh_token", "");
        if (!refresh.isEmpty()) {
            e.putString(SvipeConfig.PREF_REFRESH, refresh);
        }
        e.putLong(SvipeConfig.PREF_EXPIRES, System.currentTimeMillis() + res.optInt("expires_in", 3600) * 1000L);
        e.apply();
    }
}
