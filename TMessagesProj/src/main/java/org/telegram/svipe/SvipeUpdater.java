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
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * In-app self-update for the non-Play (direct-download) builds, mimicking Telegram's website build.
 *
 * <p>The Play build ({@code uz.svipe.app}) updates through Google Play; Telegram's own MTProto update
 * check is disabled for every Svipe build. For the {@code .beta} (dev) and {@code .web} (prod) builds
 * this polls the Svipe backend over HTTPS ({@code GET /api/app/update}; routed beta->lavha-dev,
 * web->svipe.uz by {@link SvipeConfig}). On a newer version it shows the native-style
 * {@link SvipeUpdateSheet} and exposes the update state to the native-style drawer banner
 * ({@link SvipeUpdateLayout}); downloading runs over HTTP with SHA-256 verification, installing via
 * FileProvider. The banner reflects download progress exactly like Telegram's side-menu update bar.
 */
public class SvipeUpdater {

    private static final long CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000; // throttle background checks to 6h
    private static final String PREFS = "svipe_updater";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_PENDING_PATH = "pending_path";
    private static final String KEY_PENDING_VC = "pending_vc";

    /** A newer version offered by the backend. */
    public static class Pending {
        final String version;
        final int versionCode;
        final long size;
        final String sha256;
        final String url;
        final String changelog;
        final boolean canNotSkip;
        Pending(String version, int versionCode, long size, String sha256, String url, String changelog, boolean canNotSkip) {
            this.version = version; this.versionCode = versionCode; this.size = size;
            this.sha256 = sha256; this.url = url; this.changelog = changelog; this.canNotSkip = canNotSkip;
        }
    }

    // UI-thread state, read by SvipeUpdateLayout (the drawer banner).
    private static volatile Pending pending;       // a newer version is available
    private static volatile boolean downloading;   // an HTTP download is in flight
    private static volatile float progress;        // 0..1 download progress
    private static volatile File readyFile;        // downloaded + verified APK, ready to install
    private static volatile boolean cancelRequested;

    private static boolean checking;
    private static boolean promptOpen;
    private static WeakReference<SvipeUpdateLayout> bannerRef;
    private static AlertDialog modalProgress; // fallback progress UI when no drawer banner is present

    /** Only the direct-download builds self-update; the Play build ({@code uz.svipe.app}) uses Google Play. */
    public static boolean isSelfUpdateBuild() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return false;
        String pkg = ctx.getPackageName();
        return pkg != null && (pkg.endsWith(".beta") || pkg.endsWith(".web"));
    }

    // ---- state accessors for the drawer banner ----
    public static boolean hasPending() { return pending != null; }
    public static boolean isDownloading() { return downloading; }
    public static float getProgress() { return progress; }
    public static boolean isReady() { return readyFile != null && readyFile.exists(); }
    public static long getPendingSize() { return pending != null ? pending.size : 0; }

    public static void setBanner(SvipeUpdateLayout banner) {
        bannerRef = banner == null ? null : new WeakReference<>(banner);
    }

    /** Availability/ready/downloading transitions: broadcast (drives the tabs banner) + refresh it. */
    private static void notifyState() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
            } catch (Exception ignore) {}
            refreshBanner();
        });
    }

    /** Per-percent progress: refresh ONLY the banner — no global broadcast (avoids ~100 app-wide relayouts). */
    private static void refreshBannerOnly() {
        AndroidUtilities.runOnUIThread(SvipeUpdater::refreshBanner);
    }

    private static void refreshBanner() {
        SvipeUpdateLayout b = bannerRef != null ? bannerRef.get() : null;
        if (b != null) b.refresh();
    }

    private static boolean hasBanner() {
        return bannerRef != null && bannerRef.get() != null;
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
        if (checking || downloading) return;
        checking = true;
        final int currentVc = currentVersionCode(activity);
        SvipeApi.get("/api/app/update?version_code=" + currentVc, null, (res, code, err) -> {
            checking = false;
            prefs().edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
            if (res == null || !res.optBoolean("available", false)) return;
            int newVc = res.optInt("version_code", 0);
            if (newVc <= currentVc) return;
            String url = res.optString("url", null);
            String sha = res.optString("sha256", "");
            // Fail closed: a side-loaded APK is only offered when we have both a URL and a checksum to verify it.
            if (TextUtils.isEmpty(url) || TextUtils.isEmpty(sha)) return;
            pending = new Pending(
                    res.optString("version_name", ""), newVc, res.optLong("size", 0),
                    sha, url, res.optString("changelog", ""),
                    res.optBoolean("can_not_skip", false));
            notifyState(); // surface the drawer banner
            promptUpdate(activity);
        });
    }

    private static void promptUpdate(Activity activity) {
        Pending u = pending;
        if (u == null || promptOpen || activity.isFinishing()) return;
        promptOpen = true;
        // Native-style bottom sheet (mirrors Telegram's UpdateAppAlertDialog), driven by our HTTP data.
        SvipeUpdateSheet sheet = new SvipeUpdateSheet(activity, u.version, u.size, u.changelog, u.canNotSkip,
                () -> startDownload(activity));
        sheet.setOnDismissListener(d -> promptOpen = false);
        sheet.show();
    }

    /** Begin (or resume) downloading the pending update. Triggered by the sheet or the drawer banner. */
    public static void startDownload(Activity activity) {
        final Pending u = pending;
        if (u == null) return;
        if (isReady()) { installApk(activity, readyFile); return; }
        if (downloading) return;
        downloading = true;
        cancelRequested = false;
        progress = 0f;
        notifyState();

        // Only show the modal progress dialog when there's no drawer banner to reflect progress
        // (e.g. on the pre-login intro screen). Otherwise the banner is the Telegram-style progress UI.
        if (!hasBanner()) {
            modalProgress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_LOADING);
            modalProgress.setCanCancel(false);
            modalProgress.setMessage("Yangilanish yuklab olinmoqda…");
            modalProgress.show();
        }

        // Don't capture the Activity in the long-running download (would leak it); resolve it weakly
        // at completion and skip UI if it's gone. The file path uses the app context, not the Activity.
        final WeakReference<Activity> actRef = new WeakReference<>(activity);
        new Thread(() -> {
            File out = null;
            String error = null;
            HttpURLConnection conn = null;
            try {
                Context appCtx = ApplicationLoader.applicationContext;
                File base = appCtx.getExternalFilesDir(null);
                if (base == null) base = appCtx.getFilesDir(); // external storage unavailable -> internal
                File dir = new File(base, "updates");
                if (!dir.exists()) dir.mkdirs();
                out = new File(dir, "svipe-" + u.versionCode + ".apk");
                if (out.exists()) out.delete();

                conn = (HttpURLConnection) new URL(u.url).openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

                long total = conn.getContentLength(); // int is fine: APKs are < 2 GB (getContentLengthLong is API 24+)
                MessageDigest md = MessageDigest.getInstance("SHA-256"); // pending always carries a sha (fail-closed)
                try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    long done = 0;
                    int n;
                    int lastPct = -1;
                    while ((n = is.read(buf)) != -1) {
                        if (cancelRequested) throw new InterruptedException("cancelled");
                        fos.write(buf, 0, n);
                        md.update(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                progress = pct / 100f;
                                AndroidUtilities.runOnUIThread(() -> {
                                    if (modalProgress != null) {
                                        modalProgress.setProgress(pct);
                                        modalProgress.setMessage("Yuklab olinmoqda… " + pct + "%");
                                    }
                                });
                                refreshBannerOnly(); // banner only — no global broadcast per percent
                            }
                        }
                    }
                    fos.flush();
                }
                if (!toHex(md.digest()).equalsIgnoreCase(u.sha256)) {
                    throw new Exception("checksum mismatch");
                }
            } catch (InterruptedException cancelled) {
                if (out != null && out.exists()) out.delete();
                out = null;
                error = null; // user-cancelled: no error dialog
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
            final boolean cancelled = cancelRequested;
            AndroidUtilities.runOnUIThread(() -> {
                downloading = false;
                progress = 0f;
                dismissModal();
                if (apk != null) {
                    readyFile = apk;
                    prefs().edit()
                            .putString(KEY_PENDING_PATH, apk.getAbsolutePath())
                            .putInt(KEY_PENDING_VC, u.versionCode)
                            .apply();
                }
                notifyState(); // ready/failed transition -> banner + listeners
                Activity act = actRef.get();
                if (act == null || act.isFinishing()) return; // gone: pending-install resumes on next launch
                if (apk == null) {
                    if (!cancelled) {
                        new AlertDialog.Builder(act)
                                .setTitle("Yuklab olishda xatolik")
                                .setMessage("Yangilanishni yuklab bo'lmadi" + (ferr != null ? " (" + ferr + ")" : "") + ". Keyinroq qayta urinib ko'ring yoki saytdan qo'lda yuklab oling.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                    return;
                }
                installApk(act, apk);
            });
        }, "svipe-apk-download").start();
    }

    public static void cancelDownload() {
        if (downloading) cancelRequested = true;
    }

    private static void dismissModal() {
        if (modalProgress != null) {
            try { modalProgress.dismiss(); } catch (Exception ignore) {}
            modalProgress = null;
        }
    }

    /** Drawer-banner tap: install if downloaded, cancel if downloading, else start the download. */
    public static void onBannerClick(Activity activity) {
        if (isReady()) {
            installApk(activity, readyFile);
        } else if (downloading) {
            cancelDownload();
        } else {
            startDownload(activity);
        }
    }

    /** If a downloaded-but-not-yet-installed APK is waiting, install it (handles the unknown-sources round-trip). */
    private static boolean resumePendingInstall(Activity activity) {
        SharedPreferences p = prefs();
        String path = p.getString(KEY_PENDING_PATH, null);
        int vc = p.getInt(KEY_PENDING_VC, 0);
        if (path == null) return false;
        if (currentVersionCode(activity) >= vc) { clearPending(); return false; }
        File apk = new File(path);
        if (!apk.exists()) { clearPending(); return false; }
        readyFile = apk;
        installApk(activity, apk);
        return true;
    }

    private static void clearPending() {
        readyFile = null;
        prefs().edit().remove(KEY_PENDING_PATH).remove(KEY_PENDING_VC).apply();
    }

    private static void installApk(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Ruxsat kerak")
                    .setMessage("Yangilanishni o'rnatish uchun Svipe'ga \"Noma'lum ilovalarni o'rnatish\" ruxsatini bering, so'ng yana bosing.")
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

    static String humanSize(long bytes) {
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
