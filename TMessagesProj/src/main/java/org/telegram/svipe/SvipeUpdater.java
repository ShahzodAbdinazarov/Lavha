package org.telegram.svipe;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * In-app self-update for the non-Play (direct-download) builds, mimicking Telegram's website build.
 *
 * <p>The Play build ({@code uz.svipe.app}) updates through Google Play; Telegram's own MTProto update
 * check is disabled for every Svipe build. For the {@code .beta} (dev) and {@code .web} (prod) builds
 * this polls the Svipe backend over HTTPS ({@code GET /api/app/update}; routed beta->dev.svipe.uz,
 * web->svipe.uz by {@link SvipeConfig}). On a newer version it shows the native-style
 * {@link SvipeUpdateSheet} and exposes the update state to the native-style drawer banner
 * ({@link SvipeUpdateLayout}); downloading runs over HTTP with SHA-256 verification, installing via
 * FileProvider. The banner reflects download progress exactly like Telegram's side-menu update bar.
 */
public class SvipeUpdater {

    // We check once per cold start (checkedThisProcess), then throttle further foreground resumes;
    // how long that throttle lasts depends on whether the last check actually got an answer, see
    // SvipeUpdateThrottle.
    private static volatile boolean checkedThisProcess = false;
    private static final String PREFS = "svipe_updater";
    private static final String KEY_LAST_CHECK = "last_check";
    // Outcome of the check KEY_LAST_CHECK stamps. A check that never produced a valid response must not
    // buy the full success interval — see SvipeUpdateThrottle for why that matters so much here.
    private static final String KEY_LAST_CHECK_OK = "last_check_ok";
    private static final String KEY_PENDING_PATH = "pending_path";
    private static final String KEY_PENDING_VC = "pending_vc";
    private static final String KEY_PROMPTED_VC = "prompted_vc";
    // The rest of the offer, persisted alongside the APK so a downloaded-but-not-installed update can be
    // fully rehydrated on the next cold start — the banner needs the size, and a re-download after the
    // file is lost needs the url + checksum.
    private static final String KEY_PENDING_NAME = "pending_name";
    private static final String KEY_PENDING_SIZE = "pending_size";
    private static final String KEY_PENDING_SHA = "pending_sha";
    private static final String KEY_PENDING_URL = "pending_url";
    private static final String KEY_PENDING_CHANGELOG = "pending_changelog";
    private static final String KEY_PENDING_CANNOTSKIP = "pending_cannotskip";

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
    private static volatile int readyVersionCode;  // version code readyFile holds (0 when none)
    private static boolean awaitingInstallPermission; // true only between the unknown-sources settings trip and the return
    private static volatile boolean cancelRequested;

    private static boolean checking;
    /**
     * Whether the in-flight check has already said "Checking for updates…" to the user.
     *
     * <p>UI-thread confined, cleared the moment {@code checking} clears. It exists so that the
     * acknowledgement is emitted exactly once per in-flight request: the press that starts the request
     * normally owns it, but a manual press that lands while the automatic cold-start check is still
     * running has been told nothing yet and must own it instead. See {@link SvipeUpdateAck}.
     */
    private static boolean checkAnnounced;
    /**
     * Non-null while a manual "check for updates" is waiting on a request that was already in flight.
     *
     * <p>UI-thread confined (both {@link #check} and the SvipeApi callback run there), so no locking.
     * Weak on purpose: this is a long-lived static and must never pin an Activity.
     */
    private static WeakReference<Activity> forceWaiterRef;
    private static boolean promptOpen;
    private static WeakReference<SvipeUpdateLayout> bannerRef;
    private static AlertDialog modalProgress; // fallback progress UI when no drawer banner is present

    /**
     * Only the direct-download builds self-update; the Play build ({@code uz.svipe.app}) uses Google Play.
     * <p>
     * The {@code BuildConfig.SELF_UPDATE} check comes first on purpose: it is a compile-time constant, so
     * for the {@code release} build type javac folds it away and R8 can prune the whole updater. The
     * package-name check stays as a second, runtime guard — the two agree by construction, since the
     * {@code .beta} and {@code .web} suffixes are applied by exactly the {@code debug} and
     * {@code standalone} build types that set SELF_UPDATE to true.
     */
    public static boolean isSelfUpdateBuild() {
        if (!BuildConfig.SELF_UPDATE) return false;
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return false;
        String pkg = ctx.getPackageName();
        return pkg != null && (pkg.endsWith(".beta") || pkg.endsWith(".web"));
    }

    /**
     * "Update the app" — sent to the right updater for the build the user is actually holding.
     *
     * <p>Upstream has six of these buttons (an unsupported message or block, an out-of-date request,
     * a story this version cannot render, a blocking update screen, an admin-log entry) and every one
     * of them opened {@link org.telegram.messenger.BuildVars#PLAYSTORE_APP_URL} directly. For the Play
     * build that is already right — the constant was repointed at uz.svipe.app long ago, so the button
     * offers Svipe rather than Telegram. For the {@code .web} and {@code .beta} builds it is wrong in a
     * way that cannot be recovered from: those are a DIFFERENT package (uz.svipe.app.web), installed
     * from svipe.uz, so sending their owner to a Play listing offers them a second app beside the one
     * they are holding, and the message they could not read stays unreadable.
     *
     * <p>They have their own updater and it is the one that works for them, so it is the one they get.
     * Everyone else keeps the store, Huawei included.
     */
    public static void openAppUpdate(Context context) {
        if (isSelfUpdateBuild()) {
            Activity activity = AndroidUtilities.findActivity(context);
            if (activity == null) {
                activity = AndroidUtilities.findActivity(ApplicationLoader.applicationContext);
            }
            if (activity != null) {
                checkNow(activity);
                return;
            }
            // No activity to host the sheet: fall through rather than swallow the press.
        }
        if (context == null) return;
        Browser.openUrl(context, BuildVars.isHuaweiStoreApp()
                ? BuildVars.HUAWEI_STORE_URL : BuildVars.PLAYSTORE_APP_URL);
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
        cleanupOldApksOnce(); // reclaim abandoned downloads (see FIX 2) — no network needed
        // Restores 'pending' + the ready banner from disk. It only short-circuits when it actually
        // launched the installer (the unknown-sources round-trip); a merely-waiting APK must NOT stop
        // the server check, or a version newer than the downloaded one could never be discovered.
        if (resumePendingInstall(activity)) return;
        // Always check once per cold start so a fresh release is picked up on the next app open;
        // only subsequent foreground resumes within the same process are time-throttled.
        if (!checkedThisProcess) {
            checkedThisProcess = true;
            check(activity, false);
            return;
        }
        SharedPreferences p = prefs();
        // A failed check buys only a short backoff, so the next resume retries instead of going quiet
        // for half an hour on a network that resolves the backend only some of the time.
        if (!SvipeUpdateThrottle.shouldCheck(
                p.getLong(KEY_LAST_CHECK, 0),
                System.currentTimeMillis(),
                p.getBoolean(KEY_LAST_CHECK_OK, true))) {
            return;
        }
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
        // Acknowledge a manual press BEFORE anything can block on the network. SvipeApi allows 15s to
        // connect and 25s to read, so on the kind of half-broken resolver this feature exists to survive
        // a failing check takes ~40s; anything that leaves the screen unchanged for that long is the dead
        // button we are fixing. Automatic checks never reach a non-NONE ack (force=false) — they run on
        // every cold start and nobody asked them a question.
        final SvipeUpdateAck.Ack ack = SvipeUpdateAck.forCheck(force, checking, downloading, checkAnnounced);
        if (ack == SvipeUpdateAck.Ack.CHECKING) {
            toast(activity, getString(R.string.SvipeUpdateChecking));
            checkAnnounced = true; // either a check is already running, or the one below starts now
        } else if (ack == SvipeUpdateAck.Ack.DOWNLOADING) {
            toast(activity, getString(R.string.SvipeUpdateDownloading));
        }
        if (checking || downloading) {
            // A manual request must never be swallowed: an in-flight automatic check can hold this for
            // the whole 25s read timeout, and silence there is the same dead button we are fixing.
            // Coalesce onto the request that is already running. Its callback closed over force=false, so
            // without this latch it would complete silently and the user would get the acknowledgement
            // above and then nothing at all. Two rapid presses only overwrite the same single latch, so
            // one response still yields exactly one answer. Nothing is latched for the download-only
            // case: "Downloading…" already answers the user, and no callback would ever arrive to
            // consume it.
            if (force && checking) forceWaiterRef = new WeakReference<>(activity);
            return;
        }
        checking = true;
        final int currentVc = currentVersionCode(activity);
        SvipeApi.get("/api/app/update?version_code=" + currentVc, null, (res, code, err) -> {
            checking = false;
            checkAnnounced = false; // this request is over; the next press owns its own acknowledgement
            // Consume the latch up front, before any branch: that is what makes "cleared exactly once,
            // on every terminal path including failure" true by construction rather than by review.
            final Activity waiter = consumeForceWaiter();
            // A manual check must always answer, or it is indistinguishable from a dead button — whether
            // it started this request or merely coalesced onto it. An automatic one with no waiter stays
            // silent: it runs on every cold start and nobody asked it a question.
            final boolean answer = force || waiter != null;
            final Activity target = waiter != null ? waiter : activity;
            // Status first: an error response can still carry a JSON body, and reading "available" off
            // that would report a 500 as "you are up to date".
            final boolean transportOk = res != null && code >= 200 && code < 300;
            // A 2xx JSON body is NOT automatically an answer. SvipeApi parses any body starting with '{',
            // so a deploy-window '{}' or a '{"detail":...}' envelope reaches us looking valid, and
            // optBoolean's false default used to turn that into an authoritative "up to date" that
            // retired the offer and deleted a verified 60 MB APK. Demand positive evidence instead; see
            // SvipeUpdateResponse.
            final int newVc = transportOk ? res.optInt("version_code", 0) : 0;
            final SvipeUpdateResponse.Outcome outcome = transportOk
                    ? SvipeUpdateResponse.classify(
                            res.has("available") && !res.isNull("available"),
                            res.optBoolean("available", false),
                            newVc, currentVc)
                    : SvipeUpdateResponse.Outcome.NOT_AN_ANSWER;
            // An unparseable answer is a failed check for throttling too: it must buy the short backoff,
            // not the full 30-minute silence.
            recordCheckOutcome(outcome != SvipeUpdateResponse.Outcome.NOT_AN_ANSWER);
            if (outcome == SvipeUpdateResponse.Outcome.NOT_AN_ANSWER) {
                // Deliberately does NOT touch pending/readyFile, and deliberately never calls
                // retireOffer(): neither one flaky DNS lookup nor one ambiguous body may destroy a
                // completed download on the very network where re-fetching it costs the most.
                if (answer) toast(target, getString(R.string.SvipeUpdateCheckFailed));
                return;
            }
            if (outcome == SvipeUpdateResponse.Outcome.NO_UPDATE) {
                retireOffer();
                if (answer) toast(target, getString(R.string.SvipeUpdateUpToDate));
                return;
            }
            String url = res.optString("url", null);
            String sha = res.optString("sha256", "");
            // Fail closed: a side-loaded APK is only offered when we have both a URL and a checksum to
            // verify it. The offer is NOT retired here: the server does claim a newer build exists, it
            // just described it unusably, so an already-downloaded good APK stays installable.
            if (TextUtils.isEmpty(url) || TextUtils.isEmpty(sha)) {
                if (answer) toast(target, getString(R.string.SvipeUpdateCheckFailed));
                return;
            }
            // Now that maybeCheck no longer stops at a waiting download, the APK on disk can be a
            // different build from the one being offered — newer (installed 549, downloaded 559,
            // released 569) OR older, when a bad release is withdrawn and the server goes back to
            // offering 559 after 569 was already downloaded. Either way it is not the file this offer
            // describes, so drop it: otherwise isReady() stays true, the banner advertises one version
            // and installs another, and startDownload() early-returns instead of fetching the right one.
            if (SvipeUpdateFiles.readyIsStaleFor(readyVersionCode, newVc)) {
                clearPending();
            }
            pending = new Pending(
                    res.optString("version_name", ""), newVc, res.optLong("size", 0),
                    sha, url, res.optString("changelog", ""),
                    res.optBoolean("can_not_skip", false));
            notifyState(); // surface the drawer banner
            // Like native Telegram (LaunchActivity:6018), the bottom-sheet prompt is shown only the first
            // time a given version is detected; every later check for the same version just refreshes the
            // banner. A manual "check for updates" (force) still re-opens the sheet.
            if (answer || newVc != prefs().getInt(KEY_PROMPTED_VC, 0)) {
                // Record it as prompted ONLY once the sheet is really on screen. promptUpdate bails when
                // the activity is finishing or another sheet is open, and marking it up front would burn
                // this version's one modal chance on a prompt the user never saw — leaving the banner as
                // the only surface, and nothing at all if the banner has not been created yet.
                if (promptUpdate(target)) {
                    prefs().edit().putInt(KEY_PROMPTED_VC, newVc).apply();
                }
            }
        });
    }

    /** Take the pending manual-check latch (if any) and clear it. UI thread only. */
    private static Activity consumeForceWaiter() {
        WeakReference<Activity> ref = forceWaiterRef;
        forceWaiterRef = null;
        if (ref == null) return null;
        Activity a = ref.get();
        return a != null && !a.isFinishing() ? a : null;
    }

    /** Stamp when the last check ran and whether it actually got an answer (drives the throttle). */
    private static void recordCheckOutcome(boolean valid) {
        prefs().edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .putBoolean(KEY_LAST_CHECK_OK, valid)
                .apply();
    }

    /**
     * A valid "no update" response is authoritative: the offer we are showing no longer exists.
     *
     * <p>Without this the "Svipe is up to date" toast could land on top of a banner still advertising a
     * version the server has withdrawn (a pulled release, or a downgrade of the served build), and
     * tapping that banner would download or install it. Retiring clears the in-memory offer, the ready
     * state and the persisted {@code pending_*} keys, and lets the existing cleanup delete the APK.
     *
     * <p>Only ever called on a validated 2xx response — a failed request must leave a good offer alone.
     * It also stands down while a download is running: that download is something the user explicitly
     * started, its worker would re-persist the offer on completion anyway, and tearing state out from
     * under it buys nothing that the next check will not do a moment later.
     */
    private static void retireOffer() {
        if (downloading) return;
        if (pending == null && readyFile == null && !prefs().contains(KEY_PENDING_PATH)) return;
        pending = null;
        clearPending(); // readyFile + persisted keys + delete the retired APK + sweep
        notifyState();  // drop the banner
    }

    /** @return true only when the sheet actually reached the screen — the caller keys persistence off this. */
    private static boolean promptUpdate(Activity activity) {
        Pending u = pending;
        if (u == null || promptOpen || activity.isFinishing()) return false;
        promptOpen = true;
        // Native-style bottom sheet (mirrors Telegram's UpdateAppAlertDialog), driven by our HTTP data.
        SvipeUpdateSheet sheet = new SvipeUpdateSheet(activity, u.version, u.size, u.changelog, u.canNotSkip,
                () -> startDownload(activity));
        sheet.setOnDismissListener(d -> promptOpen = false);
        try {
            sheet.show();
        } catch (Throwable t) {
            // show() throws if the window went away between the isFinishing() check and here. Release the
            // flag or every later prompt in this process would be swallowed by promptOpen.
            promptOpen = false;
            FileLog.e(t);
            return false;
        }
        return true;
    }

    private static void toast(Activity activity, String text) {
        if (activity == null || activity.isFinishing()) return;
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }

    /** Begin (or resume) downloading the pending update. Triggered by the sheet or the drawer banner. */
    public static void startDownload(Activity activity) {
        final Pending u = pending;
        if (u == null) return;
        // Defence in depth behind readyIsStaleFor(): take the install shortcut only when the file on disk
        // IS the build this offer describes. The failure mode being guarded is "the user installs a build
        // we withdrew", so a second, local identity check is worth its two lines — if they ever disagree
        // we fall through and download the offered build instead.
        if (isReadyForCurrentOffer()) { installApk(activity, readyFile); return; }
        if (downloading) return;
        if (TextUtils.isEmpty(u.url) || TextUtils.isEmpty(u.sha256)) {
            // Only reachable for an offer rehydrated from a build that persisted no url/checksum and
            // whose APK has since vanished. Nothing to fetch and nothing to verify against: say so and
            // let the next check re-populate the offer, rather than failing deep inside the thread.
            toast(activity, getString(R.string.SvipeUpdateCheckFailed));
            return;
        }
        downloading = true;
        cancelRequested = false;
        progress = 0f;
        // Claim the updates directory BEFORE the worker thread exists, so the file this download will
        // create cannot possibly appear while a sweep believes nothing is in flight. See beginDownload.
        beginDownload(SvipeUpdateFiles.fileName(u.versionCode));
        notifyState();

        // Only show the modal progress dialog when there's no drawer banner to reflect progress
        // (e.g. on the pre-login intro screen). Otherwise the banner is the Telegram-style progress UI.
        if (!hasBanner()) {
            try {
                modalProgress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_LOADING);
                modalProgress.setCanCancel(false);
                modalProgress.setMessage(getString(R.string.SvipeUpdateDownloading));
                modalProgress.show();
            } catch (Throwable t) {
                // show() throws if the window went away. Progress UI is cosmetic — never let it stop the
                // download from starting, which would strand 'downloading' and the sweep claim forever.
                FileLog.e(t);
                modalProgress = null;
            }
        }

        // Don't capture the Activity in the long-running download (would leak it); resolve it weakly
        // at completion and skip UI if it's gone. The file path uses the app context, not the Activity.
        final WeakReference<Activity> actRef = new WeakReference<>(activity);
        Thread worker = new Thread(() -> {
            File out = null;
            String error = null;
            HttpURLConnection conn = null;
            try {
                File dir = updatesDir();
                if (dir == null) throw new Exception("no storage for updates");
                if (!dir.exists()) dir.mkdirs();
                out = new File(dir, SvipeUpdateFiles.fileName(u.versionCode)); // same name beginDownload published
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
                                        modalProgress.setMessage(LocaleController.formatString(R.string.SvipeUpdateDownloadingProgress, pct));
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
                    readyVersionCode = u.versionCode;
                    persistPending(u, apk);
                }
                // Release the directory only now, AFTER readyFile/persistPending have committed: the
                // finished APK hands off from the in-flight name to the keep-set with no gap in between.
                endDownload();
                notifyState(); // ready/failed transition -> banner + listeners
                Activity act = actRef.get();
                if (act == null || act.isFinishing()) return; // gone: pending-install resumes on next launch
                if (apk == null) {
                    if (!cancelled) {
                        new AlertDialog.Builder(act)
                                .setTitle(getString(R.string.SvipeUpdateFailedTitle))
                                .setMessage(LocaleController.formatString(R.string.SvipeUpdateFailedMessage, ferr != null ? " (" + ferr + ")" : ""))
                                .setPositiveButton(getString(R.string.OK), null)
                                .show();
                    }
                    return;
                }
                // Downloaded + verified. Do NOT auto-launch the installer — let the user tap (like
                // Telegram). The banner already shows the "tap to install" state via notifyState() above;
                // when there is no banner to tap, ask with a dialog instead of installing silently.
                if (!hasBanner()) {
                    new AlertDialog.Builder(act)
                            .setTitle(getString(R.string.SvipeUpdateReadyTitle))
                            .setMessage(getString(R.string.SvipeUpdateReadyMessage))
                            .setPositiveButton(getString(R.string.SvipeUpdateInstall), (d, w) -> installApk(act, apk))
                            .setNegativeButton(getString(R.string.SvipeUpdateLater), null)
                            .show();
                }
            });
        }, "svipe-apk-download");
        try {
            worker.start();
        } catch (Throwable t) {
            // Thread creation failed (OOM). Unwind everything the guards above set, or 'downloading'
            // would stay true forever (no download, no retry) and the sweep claim would suppress every
            // future cleanup in this process.
            FileLog.e(t);
            downloading = false;
            progress = 0f;
            dismissModal();
            endDownload();
            notifyState();
        }
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

    /**
     * Is the verified APK on disk the exact build the current offer describes?
     *
     * <p>{@link #isReady()} only says a file exists. The banner and the sheet advertise
     * {@code pending.versionCode}, so anything that installs from disk must confirm the identity too —
     * otherwise a withdrawn build that is still lying in the updates directory gets installed under the
     * label of the build that replaced it.
     */
    public static boolean isReadyForCurrentOffer() {
        Pending u = pending;
        return isReady() && u != null && readyVersionCode > 0 && readyVersionCode == u.versionCode;
    }

    /** Drawer-banner tap: install if downloaded, cancel if downloading, else start the download. */
    public static void onBannerClick(Activity activity) {
        if (isReadyForCurrentOffer()) {
            installApk(activity, readyFile);
        } else if (downloading) {
            cancelDownload();
        } else {
            startDownload(activity);
        }
    }

    /**
     * Rehydrate a downloaded-but-not-yet-installed APK into live state, and finish the unknown-sources
     * round-trip if that is what brought us back.
     *
     * <p>Critically this restores {@code pending}, not just {@code readyFile}: {@link SvipeUpdateLayout}
     * gates entirely on {@link #hasPending()}, so setting only {@code readyFile} left a verified APK on
     * disk with NO banner — and, when this also short-circuited {@link #maybeCheck}, no server check
     * either, on every launch, forever. The offer's metadata is persisted with the file precisely so
     * this can be reconstructed with no network at all, which is the point: the download already
     * succeeded, the banner must work offline.
     *
     * @return true ONLY when the installer was launched here (the caller then skips its check, because
     *         the install flow owns the screen). A merely-waiting APK returns false so the caller still
     *         checks the server: with 549 installed and 559 downloaded, a newly released 569 must still
     *         be discoverable instead of the user sitting on 559 forever.
     */
    private static boolean resumePendingInstall(Activity activity) {
        SharedPreferences p = prefs();
        String path = p.getString(KEY_PENDING_PATH, null);
        int vc = p.getInt(KEY_PENDING_VC, 0);
        if (path == null) return false;
        if (currentVersionCode(activity) >= vc) {
            // Already running it (or newer): the file is pure garbage now. On a fresh process readyFile
            // is null, so clearPending() has nothing to delete — name the file explicitly. This is the
            // path a self-update takes on its very first launch into the new version.
            File installed = new File(path);
            clearPending();
            deleteQuietlyAsync(installed);
            return false;
        }
        File apk = new File(path);
        if (!apk.exists()) { clearPending(); return false; }
        readyFile = apk;
        readyVersionCode = vc;
        if (pending == null || pending.versionCode != vc) {
            // Fall back to the version code as a display name for state written by an older build that
            // only persisted path+vc; every other field degrades harmlessly (size 0 hides the size
            // label, and the ready banner shows "Update now" without it anyway).
            String name = p.getString(KEY_PENDING_NAME, "");
            if (TextUtils.isEmpty(name)) name = String.valueOf(vc);
            pending = new Pending(
                    name, vc,
                    p.getLong(KEY_PENDING_SIZE, 0),
                    p.getString(KEY_PENDING_SHA, ""),
                    p.getString(KEY_PENDING_URL, ""),
                    p.getString(KEY_PENDING_CHANGELOG, ""),
                    p.getBoolean(KEY_PENDING_CANNOTSKIP, false));
        }
        notifyState(); // "ready — tap to install" banner; do NOT auto-launch the installer
        if (awaitingInstallPermission) {
            // Returned from the unknown-sources settings round-trip — resume the user's own install.
            awaitingInstallPermission = false;
            installApk(activity, apk);
            return true;
        }
        return false;
    }

    /** Persist everything needed to rebuild {@code pending} after a restart, next to the APK path. */
    private static void persistPending(Pending u, File apk) {
        prefs().edit()
                .putString(KEY_PENDING_PATH, apk.getAbsolutePath())
                .putInt(KEY_PENDING_VC, u.versionCode)
                .putString(KEY_PENDING_NAME, u.version)
                .putLong(KEY_PENDING_SIZE, u.size)
                .putString(KEY_PENDING_SHA, u.sha256)
                .putString(KEY_PENDING_URL, u.url)
                .putString(KEY_PENDING_CHANGELOG, u.changelog)
                .putBoolean(KEY_PENDING_CANNOTSKIP, u.canNotSkip)
                .apply();
    }

    /** Forget the downloaded APK (state + file). Never touches {@code pending} — the offer may still stand. */
    private static void clearPending() {
        File stale = readyFile;
        readyFile = null;
        readyVersionCode = 0;
        prefs().edit()
                .remove(KEY_PENDING_PATH).remove(KEY_PENDING_VC)
                .remove(KEY_PENDING_NAME).remove(KEY_PENDING_SIZE).remove(KEY_PENDING_SHA)
                .remove(KEY_PENDING_URL).remove(KEY_PENDING_CHANGELOG).remove(KEY_PENDING_CANNOTSKIP)
                .apply();
        if (stale != null) deleteQuietlyAsync(stale);
        cleanupOldApksAsync();
    }

    // ---- FIX 2: reclaim abandoned update APKs ----------------------------------------------------
    // startDownload() writes every update to <externalFilesDir>/updates/svipe-<vc>.apk and nothing ever
    // removed them: a real device held 29 files / ~2.25 GB going back to versionCode 259.
    //
    // Triggers, deliberately: (1) once per process from maybeCheck() — so merely LAUNCHING this build
    // drains an existing backlog, with no network and without waiting for a check to succeed, which
    // matters because the device that surfaced this could not reach the server at all; and (2) whenever
    // the pending APK is retired in clearPending() — after a successful install, after the first launch
    // into the new version, or when a newer release supersedes it — which is the moment a file becomes
    // garbage and the only way to keep the directory bounded within a long-running process.
    // Everything here is best-effort: storage may be unreadable and a cleanup failure must never affect
    // updating.

    private static volatile boolean cleanupInFlight = false;
    private static volatile boolean cleanedThisProcess = false;

    // ---- FIX 4: the sweep must never race a download --------------------------------------------
    // The sweep used to snapshot its keep-set and then delete; startDownload creates
    // svipe-<vc>.apk on another thread and fills it incrementally, so a download that began after the
    // snapshot but before the delete loop reached that name had its file unlinked underneath it.
    //
    // The window is closed by mutual exclusion, not by re-checking a flag (a flag can always flip
    // between the check and the delete). Everything that mutates or deletes files in the updates
    // directory takes SWEEP_LOCK:
    //   * beginDownload() raises activeDownloads and publishes the target name while holding it, and it
    //     runs on the UI thread BEFORE the worker thread is started — so the file cannot exist yet.
    //   * cleanupOldApks() holds it across the whole keep-set + delete-loop section and bails out when
    //     activeDownloads > 0.
    // Therefore for any download and any sweep, one of two things is true: beginDownload got the lock
    // first, and the sweep then observes activeDownloads > 0 and deletes nothing; or the sweep got the
    // lock first, and it has finished every delete before beginDownload returns — at which point the
    // download's file still does not exist, so there was nothing of it to delete. There is no ordering
    // in which a delete and a live download's file overlap.
    // The lock is only held for the delete loop (listing and the PackageManager lookup happen outside),
    // so the UI thread's beginDownload waits on a few File.delete() calls at most.
    private static final Object SWEEP_LOCK = new Object();
    private static int activeDownloads;         // guarded by SWEEP_LOCK
    private static String inFlightDownloadName; // guarded by SWEEP_LOCK

    /** Claim the updates directory for a download. Call on the UI thread before the worker starts. */
    private static void beginDownload(String targetName) {
        synchronized (SWEEP_LOCK) {
            activeDownloads++;
            inFlightDownloadName = targetName;
        }
    }

    /** Release the claim. Call once the finished file is protected by readyFile/persistPending. */
    private static void endDownload() {
        synchronized (SWEEP_LOCK) {
            if (--activeDownloads <= 0) {
                activeDownloads = 0;
                inFlightDownloadName = null;
            }
        }
    }

    /** Launch-time sweep: the backlog only needs draining once per process. */
    private static void cleanupOldApksOnce() {
        if (cleanedThisProcess) return;
        cleanedThisProcess = true;
        cleanupOldApksAsync();
    }

    /** Request a sweep. Coalesced: one at a time, so the launch sweep and a clearPending sweep can't race. */
    private static synchronized void cleanupOldApksAsync() {
        if (cleanupInFlight) return;
        cleanupInFlight = true;
        boolean started = runOffThread(() -> {
            try {
                cleanupOldApks();
            } finally {
                cleanupInFlight = false;
            }
        }, "svipe-apk-cleanup");
        if (!started) cleanupInFlight = false; // thread creation failed: don't wedge the flag forever
    }

    /** Delete the just-retired APK immediately; the sweep may not run again this process. */
    private static void deleteQuietlyAsync(File file) {
        runOffThread(() -> {
            try {
                // Same lock as the sweep, for the same reason: this is a targeted delete in the same
                // directory and must not be able to hit a file a download is filling right now.
                synchronized (SWEEP_LOCK) {
                    if (activeDownloads > 0 && file.getName().equals(inFlightDownloadName)) return;
                    if (file.exists()) file.delete();
                }
            } catch (Throwable ignore) {}
        }, "svipe-apk-delete");
    }

    private static boolean runOffThread(Runnable r, String name) {
        try {
            new Thread(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    FileLog.e(t); // never let housekeeping take down an update
                }
            }, name).start();
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    /**
     * Where downloaded APKs live. One resolution, used by the download, the sweep and the Storage
     * Usage screen — three copies of this fallback is how a "cleared" directory keeps its files.
     */
    public static File updatesDir() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return null;
        try {
            File base = ctx.getExternalFilesDir(null);
            if (base == null) base = ctx.getFilesDir(); // external storage unavailable -> internal
            return base == null ? null : new File(base, "updates");
        } catch (SecurityException e) {
            FileLog.e(e); // storage revoked/unavailable: nothing we can do, and nothing breaks
            return null;
        }
    }

    /**
     * Empty the update cache on the user's word — Storage Usage's Clear All (see SvipeStorage).
     *
     * <p>Retiring the pending APK first is what makes this safe AND complete: clearPending() forgets
     * the persisted file (so the app never offers to install something that is no longer there) and
     * deletes it, and the sweep it triggers drains everything else the directory still holds. A
     * download in flight is protected by that sweep's own lock, not by anything here.
     */
    public static void clearDownloadedApks() {
        try {
            clearPending();
            cleanupOldApks();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Blocking sweep of the updates directory. Call off the UI thread. */
    private static void cleanupOldApks() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return;
        File dir = updatesDir();
        if (dir == null || !dir.isDirectory()) return;

        String[] names;
        try {
            names = dir.list();
        } catch (SecurityException e) {
            FileLog.e(e);
            return;
        }
        if (names == null || names.length == 0) return;

        // Everything expensive (the listing above, the PackageManager lookup below) is done outside the
        // lock so the UI thread's beginDownload() never waits on it.
        int installedVc = currentVersionCode(ctx);

        // The keep-set is built INSIDE the lock together with the deletes it guards: built outside, it
        // would be the stale snapshot that made this racy in the first place.
        synchronized (SWEEP_LOCK) {
            // A download owns the directory. Its file is created early and written incrementally, so
            // there is no safe subset to delete while it runs; the next sweep (clearPending, or the
            // next launch) picks the backlog up.
            if (activeDownloads > 0) return;

            // Protect, in this order: the persisted pending APK, the live offer's file (an in-flight
            // download writes svipe-<vc>.apk long before it is persisted), the file a download is
            // writing right now, and the in-memory ready file. These can legitimately differ mid-flight,
            // and deleting any of them would break a running download or an install the system installer
            // is reading through our FileProvider.
            SharedPreferences p = prefs();
            String pendingPath = p.getString(KEY_PENDING_PATH, null);
            Pending u = pending;
            List<String> keep = SvipeUpdateFiles.keepNamesFor(
                    pendingPath == null ? null : new File(pendingPath).getName(),
                    u != null ? u.versionCode : p.getInt(KEY_PENDING_VC, 0),
                    inFlightDownloadName);
            File ready = readyFile;
            if (ready != null && !keep.contains(ready.getName())) keep.add(ready.getName());

            for (String name : SvipeUpdateFiles.selectDeletableForSweep(names, installedVc, keep, activeDownloads > 0)) {
                try {
                    File f = new File(dir, name);
                    if (f.isFile() && !f.delete()) FileLog.e("Svipe: could not delete stale update " + name);
                } catch (Throwable t) {
                    FileLog.e(t); // keep sweeping the rest
                }
            }
        }
    }

    /**
     * The Play build must not merely avoid installing — it must not <em>contain</em> installer code.
     * Play's automated policy scanning flags {@link android.content.pm.PackageInstaller} together with
     * USER_ACTION_NOT_REQUIRED as a silent self-update pattern, and it reads the shipped bundle, not the
     * control flow: an unreachable path still trips it and still has to be argued away at every review.
     * <p>
     * {@code BuildConfig.SELF_UPDATE} is a compile-time constant (false for the {@code release} build
     * type, true for {@code standalone}/.web and {@code debug}/.beta), so javac drops this call
     * entirely and R8 then prunes {@link #installApkImpl} and everything it reaches. The Play bundle
     * ends up with no installer code at all.
     */
    private static void installApk(Activity activity, File apk) {
        if (BuildConfig.SELF_UPDATE) {
            installApkImpl(activity, apk);
        }
    }

    private static void installApkImpl(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle(getString(R.string.SvipeUpdatePermissionTitle))
                    .setMessage(getString(R.string.SvipeUpdatePermissionMessage))
                    .setPositiveButton(getString(R.string.Settings), (d, w) -> {
                        awaitingInstallPermission = true; // resume this install when we return from settings
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    })
                    .setNegativeButton(getString(R.string.Cancel), null)
                    .show();
            return;
        }
        // Prefer the modern PackageInstaller session API. The legacy ACTION_VIEW hand-off below is what
        // Telegram itself uses, but it fails on some OEM ROMs (notably MIUI/Xiaomi) with a generic
        // "App not installed" and gives us no reason. The session API is more reliable there, can skip
        // the confirm dialog entirely when the OS allows a same-package self-update, and reports the
        // real failure code via a status callback. Fall back to the classic intent if the session
        // can't even be created.
        try {
            installViaSession(activity.getApplicationContext(), apk);
        } catch (Exception e) {
            FileLog.e(e);
            installViaView(activity, apk);
        }
    }

    /** Classic hand-off to the system installer (Telegram's own approach); used as a fallback. */
    private static void installViaView(Activity activity, File apk) {
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

    private static final String INSTALL_STATUS_ACTION = "org.telegram.svipe.SVIPE_INSTALL_STATUS";
    private static volatile boolean installReceiverRegistered = false;

    /** Stream the APK into a PackageInstaller session and commit it; status arrives on our receiver. */
    private static void installViaSession(Context context, File apk) throws IOException {
        Context appCtx = context.getApplicationContext();
        registerInstallReceiver(appCtx);
        PackageInstaller installer = appCtx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(appCtx.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Allow a silent self-update when the OS permits it (we're the same, identically-signed
            // package). When it isn't allowed the system just falls back to STATUS_PENDING_USER_ACTION,
            // which our receiver turns into the normal confirm dialog.
            try {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            } catch (Throwable ignore) {}
        }
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try (OutputStream out = session.openWrite("svipe_update.apk", 0, apk.length());
             InputStream in = new FileInputStream(apk)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            session.fsync(out);
        }
        Intent statusIntent = new Intent(INSTALL_STATUS_ACTION).setPackage(appCtx.getPackageName());
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            piFlags |= PendingIntent.FLAG_MUTABLE; // the OS fills in the confirm Intent
        }
        PendingIntent pi = PendingIntent.getBroadcast(appCtx, sessionId, statusIntent, piFlags);
        session.commit(pi.getIntentSender());
        session.close();
    }

    /** One process-wide receiver for PackageInstaller session callbacks (confirm UI / success / error). */
    private static void registerInstallReceiver(Context appCtx) {
        if (installReceiverRegistered) return;
        installReceiverRegistered = true;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            ctx.startActivity(confirm);
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                    return;
                }
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    clearPending(); // done — the system restarts us into the new build
                    return;
                }
                if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    return; // user dismissed the confirm dialog — stay quiet
                }
                final String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                FileLog.e("Svipe self-update install failed: status=" + status + " msg=" + msg);
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        Toast.makeText(ApplicationLoader.applicationContext,
                                LocaleController.formatString(R.string.SvipeUpdateInstallFailed, msg != null ? ": " + msg : ""),
                                Toast.LENGTH_LONG).show();
                    } catch (Exception ignore) {}
                });
            }
        };
        IntentFilter filter = new IntentFilter(INSTALL_STATUS_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appCtx.registerReceiver(receiver, filter);
            }
        } catch (Exception e) {
            FileLog.e(e);
            installReceiverRegistered = false;
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
