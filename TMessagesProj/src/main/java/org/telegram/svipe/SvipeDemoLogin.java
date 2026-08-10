package org.telegram.svipe;

import android.util.Base64;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLRPC;

/**
 * The demo sign-in a store reviewer (and our own testers) use: the whole login happens inside the
 * app, over Telegram's own QR-login exchange, and never sends an SMS.
 *
 * Why not the obvious thing. The first version handed reviewers the demo account's REAL phone number
 * and a secret web page that read the incoming login code out of the account. It worked once and got
 * worse with every use: each sign-in is a fresh authorization on one shared account, Telegram caps
 * how many an account may hold, and past that cap it refuses every new login outright. Twenty
 * testers in, the demo account was a demo account nobody could log into — and the codes themselves
 * were being handed out by a URL that was, in effect, the account's password.
 *
 * What happens instead (core.telegram.org/api/qr-login, the exchange the desktop clients use):
 *   1. this client asks Telegram for a login token — auth.exportLoginToken, no phone, no SMS;
 *   2. it POSTs the token to our backend together with the code the tester typed;
 *   3. the backend, holding the demo account's session, accepts the token for us — and prunes the
 *      account's oldest authorizations first, so the ceiling can never be reached;
 *   4. this client asks for the token again and Telegram hands back the authorization, migrating to
 *      the account's own datacenter when it lives elsewhere.
 *
 * Nothing secret ships in the APK: the code is typed at the code screen, exactly where a real login
 * code would go, so the reviewer's instructions stay "a phone number and a code".
 */
public class SvipeDemoLogin {

    /**
     * The demo account, digits only. Typing it at the phone screen is what turns this path on; every
     * other number takes the ordinary SMS route, untouched.
     */
    private static final String DEMO_PHONE = "998331505332";

    /** Digits in the demo code — the code screen shows exactly this many boxes. */
    public static final int CODE_LENGTH = 6;

    /** How long we keep asking Telegram for the accepted token before giving up. */
    private static final int POLL_ATTEMPTS = 12;
    private static final long POLL_DELAY_MS = 700;

    private static final int UNAUTHORIZED_FLAGS = ConnectionsManager.RequestFlagWithoutLogin
            | ConnectionsManager.RequestFlagEnableUnauthorized
            | ConnectionsManager.RequestFlagFailOnServerErrors;

    public interface Callback {
        void onSuccess(TLRPC.TL_auth_authorization authorization);

        /** {@code wrongCode} true when the tester simply mistyped — the UI shakes instead of alerting. */
        void onError(String message, boolean wrongCode);
    }

    public static boolean isDemoPhone(String phone) {
        if (phone == null) {
            return false;
        }
        return DEMO_PHONE.equals(phone.replaceAll("[^0-9]", ""));
    }

    /**
     * A sent-code response we make up ourselves, so the phone screen can advance to the code screen
     * without asking Telegram to send anything. Typed as "code sent to your Telegram app" because
     * that page has no resend timer to run down and no SMS that will never arrive.
     */
    public static TLRPC.auth_SentCode sentCodeStub() {
        TLRPC.TL_auth_sentCode sentCode = new TLRPC.TL_auth_sentCode();
        sentCode.type = new TLRPC.TL_auth_sentCodeTypeApp();
        sentCode.type.length = CODE_LENGTH;
        sentCode.phone_code_hash = "";
        return sentCode;
    }

    /** Runs the whole exchange. Callbacks arrive on the UI thread, exactly once. */
    public static void signIn(int account, String code, Callback callback) {
        exportLoginToken(account, 0, (response, error) -> {
            if (!(response instanceof TLRPC.TL_auth_loginToken)) {
                callback.onError(describe(error, "EXPORT_FAILED"), false);
                return;
            }
            final byte[] token = ((TLRPC.TL_auth_loginToken) response).token;
            JSONObject body = new JSONObject();
            try {
                body.put("token", Base64.encodeToString(token, Base64.NO_WRAP));
                body.put("code", code);
            } catch (Exception e) {
                FileLog.e(e);
                callback.onError("BAD_REQUEST", false);
                return;
            }
            SvipeApi.post("/v1/auth/demo/accept", body, null, (result, httpCode, err) -> {
                if (httpCode == 403) {           // the backend rejected the typed code
                    callback.onError("PHONE_CODE_INVALID", true);
                    return;
                }
                if (httpCode < 200 || httpCode >= 300) {
                    callback.onError(err != null ? err : ("HTTP_" + httpCode), false);
                    return;
                }
                pollForAuthorization(account, POLL_ATTEMPTS, callback);
            });
        });
    }

    /**
     * After the backend accepts, the same export call starts answering with the authorization (or
     * with the datacenter it lives on). Acceptance and this poll race, hence the retries.
     */
    private static void pollForAuthorization(int account, int attemptsLeft, Callback callback) {
        exportLoginToken(account, 0, (response, error) -> {
            if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                finish(((TLRPC.TL_auth_loginTokenSuccess) response).authorization, callback);
                return;
            }
            if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
                TLRPC.TL_auth_loginTokenMigrateTo migrate = (TLRPC.TL_auth_loginTokenMigrateTo) response;
                importLoginToken(account, migrate.dc_id, migrate.token, callback);
                return;
            }
            if (response instanceof TLRPC.TL_auth_loginToken && attemptsLeft > 1) {
                AndroidUtilities.runOnUIThread(() -> pollForAuthorization(account, attemptsLeft - 1, callback), POLL_DELAY_MS);
                return;
            }
            callback.onError(describe(error, "ACCEPT_TIMEOUT"), false);
        });
    }

    /** The account lives on another datacenter: finish the login there. */
    private static void importLoginToken(int account, int dcId, byte[] token, Callback callback) {
        TLRPC.TL_auth_importLoginToken req = new TLRPC.TL_auth_importLoginToken();
        req.token = token;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                finish(((TLRPC.TL_auth_loginTokenSuccess) response).authorization, callback);
            } else {
                callback.onError(describe(error, "IMPORT_FAILED"), false);
            }
        }), null, null, UNAUTHORIZED_FLAGS, dcId, ConnectionsManager.ConnectionTypeGeneric, true);
    }

    private static void finish(TLRPC.auth_Authorization authorization, Callback callback) {
        if (authorization instanceof TLRPC.TL_auth_authorization) {
            callback.onSuccess((TLRPC.TL_auth_authorization) authorization);
        } else {
            // authorizationSignUpRequired: the demo account exists, so this means we signed in as
            // nobody. Nothing sensible to show the tester beyond the fact that it failed.
            callback.onError("SIGN_UP_REQUIRED", false);
        }
    }

    private static void exportLoginToken(int account, int dcId, RequestDelegate delegate) {
        TLRPC.TL_auth_exportLoginToken req = new TLRPC.TL_auth_exportLoginToken();
        req.api_id = BuildVars.APP_ID;
        req.api_hash = BuildVars.APP_HASH;
        ConnectionsManager.getInstance(account).sendRequest(req,
                (response, error) -> AndroidUtilities.runOnUIThread(() -> delegate.run(response, error)),
                UNAUTHORIZED_FLAGS);
    }

    private static String describe(TLRPC.TL_error error, String fallback) {
        return error != null && error.text != null ? error.text : fallback;
    }

    private SvipeDemoLogin() {
    }
}
