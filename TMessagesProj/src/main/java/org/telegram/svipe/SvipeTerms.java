package org.telegram.svipe;

import android.app.Activity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;

/**
 * Mandatory Svipe terms &amp; privacy disclosure, shown once.
 *
 * <p>The app only ever presented Telegram's own Terms of Service (during sign-up); Svipe had none.
 * Google Play requires a prominent, in-app disclosure — not buried in a policy page — with an
 * affirmative acceptance before any personal data is collected. The message-sync feature is the
 * reason we need one: this sheet is where the user is told, in normal app usage, what it does and
 * that it is off by default and controllable. Actual collection only begins later, when the user
 * turns sync on in-context; this is the disclosure, not the consent to sync itself.
 *
 * <p>Shown once per {@link #SVIPE_TOS_VERSION}: bump the version to re-disclose materially changed
 * terms to every existing user. The accepted version is a single GLOBAL preference (per device, not
 * per account) so a multi-account user is not asked again per account.
 */
public final class SvipeTerms {

    private SvipeTerms() {}

    /** Bump when the disclosed terms change materially, so every user is re-shown the sheet once. */
    public static final int SVIPE_TOS_VERSION = 1;

    private static final String PREF_ACCEPTED = "svipe_tos_accepted_version";
    public static final String PRIVACY_URL = "https://svipe.uz/privacy";

    /** The live dialog, so rapid onResume calls / config changes never stack a second copy. */
    private static AlertDialog visibleDialog;

    /** Pure gate (JVM-testable): the sheet is due while the accepted version trails the current one. */
    public static boolean shouldShow(int acceptedVersion, int currentVersion) {
        return acceptedVersion < currentVersion;
    }

    /** The terms version this device has already accepted (0 = never). */
    public static int acceptedVersion() {
        try {
            return MessagesController.getGlobalMainSettings().getInt(PREF_ACCEPTED, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void markAccepted() {
        try {
            MessagesController.getGlobalMainSettings().edit().putInt(PREF_ACCEPTED, SVIPE_TOS_VERSION).apply();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    /**
     * Show the disclosure once, if due. No-op when already accepted or already on screen. The dialog is
     * not cancelable and has no decline path: the only way forward is to accept, which is what makes it
     * a real acceptance rather than a dismissible notice.
     */
    public static void maybeShow(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (visibleDialog != null && visibleDialog.isShowing()) {
            return;
        }
        if (!shouldShow(acceptedVersion(), SVIPE_TOS_VERSION)) {
            return;
        }
        try {
            CharSequence message = AndroidUtilities.replaceSingleTag(
                    LocaleController.getString(R.string.SvipeTermsMessage),
                    () -> Browser.openUrl(activity, PRIVACY_URL));

            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(LocaleController.getString(R.string.SvipeTermsTitle));
            builder.setMessage(message);
            builder.setPositiveButton(LocaleController.getString(R.string.SvipeTermsAccept),
                    (dialog, which) -> markAccepted());

            AlertDialog dialog = builder.create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.setCancelable(false);
            dialog.setOnDismissListener(d -> visibleDialog = null);
            visibleDialog = dialog;
            dialog.show();
        } catch (Exception e) {
            visibleDialog = null;
        }
    }
}
