package org.telegram.svipe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * In-app self-update for the non-Play (direct-download) builds, mimicking Telegram's website build.
 *
 * <p>The Play build ({@code uz.svipe.app}) updates through Google Play, so Telegram's own MTProto
 * update check is disabled for every Svipe build (the regular {@code ApplicationLoaderImpl} reports
 * {@code isStandalone()==false} and {@code isBeta()==false}). For the {@code .beta} (dev) and
 * {@code .web} (prod) builds this class instead polls the Svipe backend over plain HTTPS
 * ({@code GET /api/app/update}), and on a newer version downloads the signed APK from the returned
 * URL, verifies its SHA-256, and launches the system installer via {@link FileProvider}.
 *
 * <p>{@link SvipeConfig#baseUrl()} already routes beta -> lavha-dev and prod -> svipe.uz, so each
 * build checks the matching environment automatically.
 */
public class SvipeUpdater {

    private static final long CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000; // throttle background checks to 6h
    private static final String PREFS = "svipe_updater";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_PENDING_PATH = "pending_path";
    private static final String KEY_PENDING_VC = "pending_vc";

    private static volatile boolean busy;       // a check or download is in flight
    private static volatile boolean promptOpen;  // an update dialog is on screen

    /** Only the direct-download builds self-update; the Play build ({@code uz.svipe.app}) uses Google Play. */
    public static boolean isSelfUpdateBuild() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return false;
        String pkg = ctx.getPackageName();
        return pkg != null && (pkg.endsWith(".beta") || pkg.endsWith(".web"));
    }

    /** Called from LaunchActivity.onResume — resumes a pending install, else throttled background check. */
    public static void maybeCheck(Activity activity) {
        if (activity == null || !isSelfUpdateBuild()) return;
        if (resumePendingInstall(activity)) return;
        SharedPreferences p = prefs();
        if (System.currentTimeMillis() - p.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return;
        check(activity, false);
    }

    /** Manual "check for updates" entry point (ignores the throttle). */
    public static void checkNow(Activity activity) {
        if (activity != null && isSelfUpdateBuild()) check(activity, true);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int currentVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

    private static void check(Activity activity, boolean force) {
        if (busy || promptOpen) return;
        busy = true;
        final int currentVc = currentVersionCode(activity);
        SvipeApi.get("/api/app/update?version_code=" + currentVc, null, (res, code, err) -> {
            busy = false;
            prefs().edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
            if (res == null || !res.optBoolean("available", false)) return;
            int newVc = res.optInt("version_code", 0);
            if (newVc <= currentVc) return;
            String url = res.optString("url", null);
            if (TextUtils.isEmpty(url)) return;
            promptUpdate(activity, res, newVc, url);
        });
    }

    private static void promptUpdate(Activity activity, JSONObject res, int newVc, String url) {
        if (promptOpen || activity.isFinishing()) return;
        promptOpen = true;
        boolean force = res.optBoolean("can_not_skip", false);
        String version = res.optString("version_name", "");
        String changelog = res.optString("changelog", "");
        long size = res.optLong("size", 0);
        String sha256 = res.optString("sha256", "");

        StringBuilder msg = new StringBuilder();
        msg.append("Svipe'ning yangi versiyasi tayyor");
        if (!TextUtils.isEmpty(version)) msg.append(" (v").append(version).append(")");
        msg.append(".");
        if (size > 0) msg.append("\nHajmi: ").append(humanSize(size));
        if (!TextUtils.isEmpty(changelog)) msg.append("\n\n").append(changelog);

        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle("Yangilanish mavjud");
        b.setMessage(msg.toString());
        b.setPositiveButton("Yangilash", (d, w) -> startDownload(activity, url, newVc, sha256));
        if (!force) {
            b.setNegativeButton("Keyinroq", null);
        }
        AlertDialog dialog = b.create();
        dialog.setCancelable(!force);
        dialog.setCanceledOnTouchOutside(!force);
        dialog.setOnDismissListener(d -> promptOpen = false);
        dialog.show();
    }

    private static void startDownload(Activity activity, String url, int newVc, String sha256) {
        if (busy) return;
        busy = true;
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_LOADING);
        progress.setCanCancel(false);
        progress.setMessage("Yangilanish yuklab olinmoqda…");
        progress.show();

        Utilities.globalQueue.postRunnable(() -> {
            File out = null;
            String error = null;
            HttpURLConnection conn = null;
            try {
                File dir = new File(activity.getExternalFilesDir(null), "updates");
                if (!dir.exists()) dir.mkdirs();
                out = new File(dir, "svipe-" + newVc + ".apk");
                if (out.exists()) out.delete();

                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

                long total = conn.getContentLength(); // int is fine: APKs are well under 2 GB (getContentLengthLong is API 24+)
                MessageDigest md = TextUtils.isEmpty(sha256) ? null : MessageDigest.getInstance("SHA-256");
                try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    long done = 0;
                    int n;
                    int lastPct = -1;
                    while ((n = is.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        if (md != null) md.update(buf, 0, n);
                        done += n;
                        final long fdone = done;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                AndroidUtilities.runOnUIThread(() -> {
                                    progress.setProgress(pct);
                                    progress.setMessage("Yuklab olinmoqda… " + pct + "%");
                                });
                            }
                        } else {
                            AndroidUtilities.runOnUIThread(() ->
                                    progress.setMessage("Yuklab olinmoqda… " + humanSize(fdone)));
                        }
                    }
                    fos.flush();
                }

                if (md != null) {
                    String got = toHex(md.digest());
                    if (!got.equalsIgnoreCase(sha256)) {
                        throw new Exception("checksum mismatch");
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage();
                if (out != null && out.exists()) out.delete();
                out = null;
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Exception ignore) {}
            }

            final File apk = out;
            final String ferr = error;
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                try { progress.dismiss(); } catch (Exception ignore) {}
                if (apk == null) {
                    new AlertDialog.Builder(activity)
                            .setTitle("Yuklab olishda xatolik")
                            .setMessage("Yangilanishni yuklab bo'lmadi" + (ferr != null ? " (" + ferr + ")" : "") + ". Keyinroq qayta urinib ko'ring yoki saytdan qo'lda yuklab oling.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                prefs().edit()
                        .putString(KEY_PENDING_PATH, apk.getAbsolutePath())
                        .putInt(KEY_PENDING_VC, newVc)
                        .apply();
                installApk(activity, apk);
            });
        });
    }

    /** If a downloaded-but-not-yet-installed APK is waiting, install it (handles the unknown-sources round-trip). */
    private static boolean resumePendingInstall(Activity activity) {
        SharedPreferences p = prefs();
        String path = p.getString(KEY_PENDING_PATH, null);
        int vc = p.getInt(KEY_PENDING_VC, 0);
        if (path == null) return false;
        // Already updated (installed >= pending) -> clear and stop nagging.
        if (currentVersionCode(activity) >= vc) {
            clearPending();
            return false;
        }
        File apk = new File(path);
        if (!apk.exists()) {
            clearPending();
            return false;
        }
        installApk(activity, apk);
        return true;
    }

    private static void clearPending() {
        prefs().edit().remove(KEY_PENDING_PATH).remove(KEY_PENDING_VC).apply();
    }

    private static void installApk(Activity activity, File apk) {
        // Android O+ requires the user to allow "install unknown apps" for this app first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Ruxsat kerak")
                    .setMessage("Yangilanishni o'rnatish uchun Svipe'ga \"Noma'lum ilovalarni o'rnatish\" ruxsatini bering, so'ng yana \"Yangilash\"ni bosing.")
                    .setPositiveButton("Sozlamalar", (d, w) -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    })
                    .setNegativeButton("Bekor qilish", null)
                    .show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", apk);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1L << 20) return String.format(Locale.US, "%.1f MB", bytes / (float) (1 << 20));
        if (bytes >= 1L << 10) return String.format(Locale.US, "%.0f KB", bytes / (float) (1 << 10));
        return bytes + " B";
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
