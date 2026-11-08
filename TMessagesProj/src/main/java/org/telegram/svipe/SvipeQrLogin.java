package org.telegram.svipe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Sign in by scanning a QR code with Telegram on another device, the way Telegram Desktop does it:
 * the code carries a login token (auth.exportLoginToken), the other device accepts it (Settings →
 * Devices → Link Desktop Device), and this one picks the authorization up. No phone number and no
 * code are typed here; a 2FA password, if the account has one, is still asked on the next screen.
 */
public final class SvipeQrLogin {

    public interface Listener {
        void onAuthorized(TLRPC.TL_auth_authorization authorization);

        /** The account has a cloud password: the caller shows its password screen. */
        void onPasswordNeeded();
    }

    /** How often the token is asked again: it reports the scan, and a fresh one replaces an expired code. */
    private static final long POLL_MS = 3000;

    private final Activity activity;
    private final int account;
    private final Listener listener;
    private final ImageView qrView;
    private final TextView statusView;
    private AlertDialog dialog;
    private byte[] shownToken;
    private boolean finished;
    private int requestId;

    private final Runnable poll = this::requestToken;

    public static void show(Activity activity, int account, Listener listener) {
        if (activity == null) return;
        new SvipeQrLogin(activity, account, listener).open();
    }

    private SvipeQrLogin(Activity activity, int account, Listener listener) {
        this.activity = activity;
        this.account = account;
        this.listener = listener;
        final Context context = activity;
        qrView = new ImageView(context);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrView.setBackgroundColor(Color.WHITE);
        qrView.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        statusView.setGravity(Gravity.CENTER_HORIZONTAL);
        statusView.setText(LocaleController.getString(R.string.SvipeQrLoginText));
    }

    private void open() {
        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(qrView, LayoutHelper.createLinear(240, 240, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 0));
        layout.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 16, 24, 8));
        dialog = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.SvipeQrLoginTitle))
                .setView(layout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialog.setOnDismissListener(d -> stop());
        dialog.show();
        requestToken();
    }

    private void stop() {
        finished = true;
        AndroidUtilities.cancelRunOnUIThread(poll);
        if (requestId != 0) {
            ConnectionsManager.getInstance(account).cancelRequest(requestId, false);
            requestId = 0;
        }
    }

    private void requestToken() {
        if (finished) return;
        final TLRPC.TL_auth_exportLoginToken req = new TLRPC.TL_auth_exportLoginToken();
        req.api_id = BuildVars.APP_ID;
        req.api_hash = BuildVars.APP_HASH;
        requestId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            requestId = 0;
            if (finished) return;
            onTokenAnswer(response, error);
        }), ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private void onTokenAnswer(TLObject response, TLRPC.TL_error error) {
        if (error != null) {
            if (error.text != null && error.text.contains("SESSION_PASSWORD_NEEDED")) {
                done(null, true);
                return;
            }
            statusView.setText(error.text);
            AndroidUtilities.runOnUIThread(poll, POLL_MS);
            return;
        }
        if (response instanceof TLRPC.TL_auth_loginToken) {
            showToken(((TLRPC.TL_auth_loginToken) response).token);
            AndroidUtilities.runOnUIThread(poll, POLL_MS);
        } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
            // The account lives on another data center: move there and redeem the token on it.
            final TLRPC.TL_auth_loginTokenMigrateTo migrate = (TLRPC.TL_auth_loginTokenMigrateTo) response;
            ConnectionsManager.getInstance(account).setDefaultDatacenterId(migrate.dc_id);
            importToken(migrate.token);
        } else if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
            onAuthorization(((TLRPC.TL_auth_loginTokenSuccess) response).authorization);
        }
    }

    private void importToken(byte[] token) {
        final TLRPC.TL_auth_importLoginToken req = new TLRPC.TL_auth_importLoginToken();
        req.token = token;
        requestId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            requestId = 0;
            if (finished) return;
            if (error != null) {
                if (error.text != null && error.text.contains("SESSION_PASSWORD_NEEDED")) {
                    done(null, true);
                } else {
                    statusView.setText(error.text);
                    AndroidUtilities.runOnUIThread(poll, POLL_MS);
                }
            } else if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                onAuthorization(((TLRPC.TL_auth_loginTokenSuccess) response).authorization);
            } else {
                onTokenAnswer(response, null);
            }
        }), ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private void onAuthorization(TLRPC.auth_Authorization authorization) {
        if (authorization instanceof TLRPC.TL_auth_authorization) {
            done((TLRPC.TL_auth_authorization) authorization, false);
        } else {
            // auth.authorizationSignUpRequired cannot come from a QR login: the account exists.
            statusView.setText(LocaleController.getString(R.string.ErrorOccurred));
        }
    }

    private void done(TLRPC.TL_auth_authorization authorization, boolean passwordNeeded) {
        stop();
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Exception ignore) {
            }
        }
        if (passwordNeeded) {
            listener.onPasswordNeeded();
        } else {
            listener.onAuthorized(authorization);
        }
    }

    private void showToken(byte[] token) {
        if (token == null || Arrays.equals(token, shownToken)) return;
        shownToken = token;
        final String link = "tg://login?token=" + Base64.encodeToString(token, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        try {
            final HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            final Bitmap bitmap = new TelegramQRCodeWriter().encode(link, 768, 768, hints, null);
            qrView.setImageBitmap(bitmap);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
