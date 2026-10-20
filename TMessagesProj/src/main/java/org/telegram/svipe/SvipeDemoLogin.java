package org.telegram.svipe;

import android.os.Build;
import android.util.Base64;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The Play reviewer's way in: the demo number with a fixed code instead of the one Telegram sends.
 *
 * <p>The number lives on the server (app/api/demo_login.py) and the app only gets a slow hash of it,
 * so this never sends anybody else's number anywhere: on the code screen it hashes the number being
 * signed in and asks the server only when it matches. The server checks the typed code, reads the
 * real one out of the account and hands it back; for two-step verification it computes the SRP proof
 * itself, so the password never reaches the device. The session this creates is closed by the server
 * an hour later.
 */
public final class SvipeDemoLogin {

    public interface Callback<T> {
        void run(T result);
    }

    private static JSONObject config;
    private static String phone;
    private static String code;

    /** Calls back with true when {@code number} is the demo number. Never fails loudly: false. */
    public static void matches(String number, Callback<Boolean> cb) {
        if (config != null) {
            check(number, cb);
            return;
        }
        SvipeApi.get("/v1/auth/demo/config", null, (result, httpCode, error) -> {
            if (result == null) {
                cb.run(false);
                return;
            }
            config = result;
            check(number, cb);
        });
    }

    private static void check(String number, Callback<Boolean> cb) {
        final JSONObject c = config;
        if (!c.optBoolean("enabled")) {
            cb.run(false);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            boolean same = false;
            try {
                byte[] hash = pbkdf2(digits(number).getBytes(StandardCharsets.UTF_8),
                        c.optString("salt").getBytes(StandardCharsets.UTF_8), c.optInt("iterations"));
                same = Utilities.bytesToHex(hash).equalsIgnoreCase(c.optString("hash"));
            } catch (Exception e) {
                FileLog.e(e);
            }
            final boolean result = same;
            AndroidUtilities.runOnUIThread(() -> cb.run(result));
        });
    }

    /** Swaps our code for Telegram's real one; null when the code is not ours (or anything failed). */
    public static void exchange(String number, String typed, Callback<String> cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("phone", digits(number));
            body.put("code", typed);
            // What Telegram will list the new session as — how the server tells it apart.
            body.put("device_model", Build.MANUFACTURER + Build.MODEL);
        } catch (Exception e) {
            cb.run(null);
            return;
        }
        SvipeApi.post("/v1/auth/demo/code", body, null, (result, httpCode, error) -> {
            String real = result != null ? result.optString("code", null) : null;
            if (real != null && !real.isEmpty()) {
                phone = digits(number);
                code = typed;
                cb.run(real);
            } else {
                cb.run(null);
            }
        });
    }

    /** True right after {@link #exchange} succeeded for this number. */
    public static boolean active(String number) {
        return phone != null && phone.equals(digits(number));
    }

    /** The two-step verification proof, from the server; null when it can't be had. */
    public static void proof(TL_account.Password password, Callback<TLRPC.TL_inputCheckPasswordSRP> cb) {
        if (phone == null || !(password.current_algo instanceof TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow)) {
            cb.run(null);
            return;
        }
        TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow algo =
                (TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow) password.current_algo;
        JSONObject body = new JSONObject();
        try {
            body.put("phone", phone);
            body.put("code", code);
            body.put("srp_id", password.srp_id);
            body.put("srp_B", b64(password.srp_B));
            body.put("salt1", b64(algo.salt1));
            body.put("salt2", b64(algo.salt2));
            body.put("g", algo.g);
            body.put("p", b64(algo.p));
        } catch (Exception e) {
            cb.run(null);
            return;
        }
        SvipeApi.post("/v1/auth/demo/password", body, null, (result, httpCode, error) -> {
            if (result == null) {
                cb.run(null);
                return;
            }
            try {
                TLRPC.TL_inputCheckPasswordSRP srp = new TLRPC.TL_inputCheckPasswordSRP();
                srp.srp_id = result.getLong("srp_id");
                srp.A = Base64.decode(result.getString("A"), Base64.DEFAULT);
                srp.M1 = Base64.decode(result.getString("M1"), Base64.DEFAULT);
                cb.run(srp);
            } catch (Exception e) {
                FileLog.e(e);
                cb.run(null);
            }
        });
    }

    public static void reset() {
        phone = null;
        code = null;
    }

    private static String digits(String number) {
        return number == null ? "" : number.replaceAll("\\D", "");
    }

    private static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    /** PBKDF2-HMAC-SHA256 with a 32-byte output; SecretKeyFactory has it only from Android 8. */
    private static byte[] pbkdf2(byte[] password, byte[] salt, int iterations) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        mac.update(salt);
        mac.update(new byte[]{0, 0, 0, 1});
        byte[] u = mac.doFinal();
        byte[] out = u.clone();
        for (int i = 1; i < iterations; i++) {
            u = mac.doFinal(u);
            for (int j = 0; j < out.length; j++) {
                out[j] ^= u[j];
            }
        }
        return out;
    }
}
