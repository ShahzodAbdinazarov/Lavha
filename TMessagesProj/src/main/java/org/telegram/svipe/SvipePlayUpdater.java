package org.telegram.svipe;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.voip.VoIPService;

/**
 * Updates for the Play build ({@code uz.svipe.app}), shaped like the {@code .web} self-update
 * ({@link SvipeUpdater}) but carried by Google Play's in-app updates, "flexible" flow:
 *
 * <ul>
 *   <li>When Play has a newer version, Google's own dialog is shown once per version; the user can
 *       update or go on with what they were doing.</li>
 *   <li>The same banner as {@code .web} ({@link SvipeUpdateLayout}) stays at the bottom: "Update
 *       Svipe" before, the download progress while Play downloads (Play also shows its own
 *       notification), "Update now" when it is downloaded. Tapping it then installs at once: Play shows
 *       its brief restart screen and reopens the app.</li>
 *   <li>A downloaded update the user doesn't tap is installed once the app has been in the background
 *       for a while, silently, so the next open is the new version — but never while something is
 *       playing or a call is on: installing stops the app. "A while", because the app also stops for a
 *       moment behind the camera, a file picker or a browser tab, and the user comes back to it.</li>
 * </ul>
 *
 * The Play library lives only in the Play flavor of the app module, which hands an {@link Engine} to
 * {@link #setEngine} at start; every other build has none and this class does nothing.
 */
public final class SvipePlayUpdater {

    /** What the Play flavor provides. Calls come and go on the main thread. */
    public interface Engine {
        /** Calls back with the available version code and its download size, or 0 when there is none. */
        void check(Activity activity, Callback onResult);

        /** Google's flexible-update dialog. False if it could not be shown. */
        boolean start(Activity activity, int versionCode);

        /** Install a downloaded update (Play restarts the app if it is in the foreground). */
        void complete();
    }

    public interface Callback {
        void run(int versionCode, long size, boolean downloaded);
    }

    private static final String PREFS = "svipe_play_update";
    private static final String KEY_PROMPTED_VC = "prompted_vc";
    // A check per cold start, then at most hourly while the app keeps coming back to the foreground.
    private static final long RECHECK_MS = 60 * 60 * 1000L;
    private static final long BACKGROUND_INSTALL_MS = 2 * 60 * 1000L;

    private static Engine engine;
    private static long lastCheck;

    private static int availableVc;
    private static long size;
    private static boolean downloading;
    private static float progress;
    private static boolean downloaded;

    public static void setEngine(Engine e) {
        engine = e;
    }

    public static boolean isActive() {
        return engine != null;
    }

    public static boolean hasPending() { return availableVc > 0; }
    public static boolean isDownloading() { return downloading; }
    public static boolean isReady() { return downloaded; }
    public static float getProgress() { return progress; }
    public static long getPendingSize() { return size; }

    /** From LaunchActivity.onResume. */
    public static void maybeCheck(Activity activity) {
        if (engine == null) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(installInBackground); // back in the foreground
        long now = System.currentTimeMillis();
        if (lastCheck != 0 && now - lastCheck < RECHECK_MS) {
            return;
        }
        lastCheck = now;
        try {
            engine.check(activity, (versionCode, bytes, isDownloaded) -> {
                if (versionCode <= 0) {
                    availableVc = 0;
                    notifyState();
                    return;
                }
                availableVc = versionCode;
                size = bytes;
                downloaded = isDownloaded;
                notifyState();
                // Google's dialog once per version, like .web's sheet; afterwards the banner is the way in.
                if (!isDownloaded && !downloading && prefs().getInt(KEY_PROMPTED_VC, 0) != versionCode
                        && !activity.isFinishing() && engine.start(activity, versionCode)) {
                    prefs().edit().putInt(KEY_PROMPTED_VC, versionCode).apply();
                }
            });
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Play's download state, from the Play flavor's listener. */
    public static void onDownloading(long bytes, long total) {
        downloading = true;
        downloaded = false;
        progress = total > 0 ? Math.min(1f, bytes / (float) total) : 0f;
        if (total > 0) {
            size = total;
        }
        notifyState();
    }

    public static void onDownloaded() {
        downloading = false;
        downloaded = true;
        progress = 1f;
        notifyState();
    }

    /** Failed, cancelled or installed: back to the plain offer (or nothing, once installed). */
    public static void onStopped(boolean installed) {
        downloading = false;
        downloaded = false;
        progress = 0f;
        if (installed) {
            availableVc = 0;
        }
        notifyState();
    }

    /** Banner tap: install if downloaded, else Google's dialog again. Play's download can't be cancelled. */
    public static void onBannerClick(Activity activity) {
        if (engine == null) {
            return;
        }
        if (downloaded) {
            engine.complete();
        } else if (!downloading && availableVc > 0) {
            engine.start(activity, availableVc);
        }
    }

    /** From LaunchActivity.onStop: a downloaded update goes in while nobody is looking. */
    public static void onAppBackground() {
        if (engine == null || !downloaded) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(installInBackground);
        AndroidUtilities.runOnUIThread(installInBackground, BACKGROUND_INSTALL_MS);
    }

    private static final Runnable installInBackground = () -> {
        if (engine == null || !downloaded || !ApplicationLoader.mainInterfaceStopped) {
            return;
        }
        if (!quiet()) {
            AndroidUtilities.runOnUIThread(SvipePlayUpdater.installInBackground, BACKGROUND_INSTALL_MS);
            return;
        }
        try {
            engine.complete();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    };

    /** Installing stops the app: not while music or a video plays, or a call is on. */
    private static boolean quiet() {
        try {
            MediaController media = MediaController.getInstance();
            if (media.getPlayingMessageObject() != null && !media.isMessagePaused()) {
                return false;
            }
        } catch (Throwable ignore) {
        }
        return VoIPService.getSharedInstance() == null;
    }

    private static void notifyState() {
        SvipeUpdater.notifyExternalState();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
