package org.telegram.lavha;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

/**
 * Seamless auth for the forked client, fully invisible in the UI. Token chain:
 *   1. cached access token (shared prefs)
 *   2. POST /v1/auth/refresh with the stored refresh token (plain HTTPS, no Telegram traffic)
 *   3. Mini App initData: messages.requestWebView on @Lavha_auth_bot's menu button — Telegram
 *      returns a signed tgWebAppData payload, the backend verifies it offline. No chat, no
 *      message, nothing syncs to other devices; the web view is never rendered.
 *   4. legacy fallback: "/start <nonce>" to the bot + poll (kept until initData is proven in prod)
 */
public class LavhaAuth {

    public interface TokenCallback {
        void run(String token);
    }

    private interface BotCallback {
        void run(long botId);
    }

    public static String getStoredToken(int account) {
        SharedPreferences p = MessagesController.getMainSettings(account);
        String t = p.getString(LavhaConfig.PREF_TOKEN, null);
        long exp = p.getLong(LavhaConfig.PREF_EXPIRES, 0);
        if (t != null && t.length() > 0 && System.currentTimeMillis() < exp - 60000L) {
            return t;
        }
        return null;
    }

    public static void ensureToken(int account, TokenCallback cb) {
        String stored = getStoredToken(account);
        if (stored != null) {
            cb.run(stored);
            return;
        }
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
                legacyBotAuth(account, cb);
            });
        });
    }

    /** Drops the cached access token so the next ensureToken() re-authenticates (e.g. after 401). */
    public static void invalidateAccessToken(int account) {
        MessagesController.getMainSettings(account).edit()
                .remove(LavhaConfig.PREF_TOKEN)
                .remove(LavhaConfig.PREF_EXPIRES)
                .apply();
    }

    private static void refreshToken(int account, TokenCallback cb) {
        SharedPreferences p = MessagesController.getMainSettings(account);
        String refresh = p.getString(LavhaConfig.PREF_REFRESH, null);
        if (refresh == null || refresh.isEmpty()) {
            cb.run(null);
            return;
        }
        JSONObject body = new JSONObject();
        try { body.put("refresh_token", refresh); } catch (Exception ignore) {}
        LavhaApi.post("/v1/auth/refresh", body, null, (res, code, err) -> {
            if (res != null && "ok".equals(res.optString("status"))) {
                storeTokens(account, res);
                cb.run(res.optString("access_token"));
            } else {
                if (code == 401) {
                    // Revoked or expired server-side — forget it so we don't ride a dead token.
                    p.edit().remove(LavhaConfig.PREF_REFRESH).apply();
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
            req.url = LavhaConfig.BASE_URL + "/webapp";
            req.flags |= 2;
            req.from_bot_menu = true;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                String initData = null;
                if (error == null && response instanceof TLRPC.TL_webViewResultUrl) {
                    initData = LavhaInitData.extract(((TLRPC.TL_webViewResultUrl) response).url);
                }
                if (initData == null) {
                    AndroidUtilities.runOnUIThread(() -> cb.run(null));
                    return;
                }
                JSONObject body = new JSONObject();
                try { body.put("init_data", initData); } catch (Exception ignore) {}
                LavhaApi.post("/v1/auth/telegram/webapp", body, null, (res, code, err) -> {
                    if (res != null && "ok".equals(res.optString("status"))) {
                        storeTokens(account, res);
                        cb.run(res.optString("access_token"));
                    } else {
                        cb.run(null);
                    }
                });
            });
        });
    }

    // ---- legacy deep-link flow (fallback only) ----

    private static void legacyBotAuth(int account, TokenCallback cb) {
        LavhaApi.post("/v1/auth/telegram/start", new JSONObject(), null, (res, code, err) -> {
            if (res == null) { cb.run(null); return; }
            String nonce = res.optString("nonce", null);
            if (nonce == null || nonce.isEmpty()) { cb.run(null); return; }
            sendStartToBot(account, nonce);
            pollToken(account, nonce, 0, cb);
        });
    }

    private static void resolveBot(int account, BotCallback cb) {
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = LavhaConfig.BOT_USERNAME;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            long botId = 0;
            if (error == null && response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer rp = (TLRPC.TL_contacts_resolvedPeer) response;
                mc.putUsers(rp.users, false);
                mc.putChats(rp.chats, false);
                if (rp.users != null && !rp.users.isEmpty()) {
                    botId = rp.users.get(0).id;
                }
            }
            cb.run(botId);
        });
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

    private static void pollToken(int account, String nonce, int attempt, TokenCallback cb) {
        if (attempt > 20) { cb.run(null); return; }
        JSONObject body = new JSONObject();
        try { body.put("nonce", nonce); } catch (Exception ignore) {}
        LavhaApi.post("/v1/auth/telegram/poll", body, null, (res, code, err) -> {
            if (res != null && "ok".equals(res.optString("status"))) {
                storeTokens(account, res);
                cb.run(res.optString("access_token"));
            } else if (code == 404) {
                cb.run(null);
            } else {
                AndroidUtilities.runOnUIThread(() -> pollToken(account, nonce, attempt + 1, cb), 1500);
            }
        });
    }

    private static void storeTokens(int account, JSONObject res) {
        SharedPreferences.Editor e = MessagesController.getMainSettings(account).edit();
        e.putString(LavhaConfig.PREF_TOKEN, res.optString("access_token"));
        e.putString(LavhaConfig.PREF_REFRESH, res.optString("refresh_token"));
        e.putLong(LavhaConfig.PREF_EXPIRES, System.currentTimeMillis() + res.optInt("expires_in", 3600) * 1000L);
        e.apply();
    }
}
