package org.telegram.messenger;

import android.app.Activity;

import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;

import org.telegram.svipe.SvipePlayUpdater;

/**
 * The Play build's updates: Google Play's in-app updates, flexible flow, handed to
 * {@link SvipePlayUpdater}, which shows them with the same banner as the direct-download builds.
 * Only this flavor carries the Play library; the direct-download builds have a no-op twin.
 */
final class PlayUpdates {

    private static final int REQUEST_CODE = 0x5e1f;

    static void install() {
        final AppUpdateManager manager = AppUpdateManagerFactory.create(ApplicationLoader.applicationContext);
        manager.registerListener(state -> AndroidUtilities.runOnUIThread(() -> {
            switch (state.installStatus()) {
                case InstallStatus.PENDING:
                case InstallStatus.DOWNLOADING:
                    SvipePlayUpdater.onDownloading(state.bytesDownloaded(), state.totalBytesToDownload());
                    break;
                case InstallStatus.DOWNLOADED:
                    SvipePlayUpdater.onDownloaded();
                    break;
                case InstallStatus.INSTALLED:
                    SvipePlayUpdater.onStopped(true);
                    break;
                case InstallStatus.FAILED:
                case InstallStatus.CANCELED:
                    SvipePlayUpdater.onStopped(false);
                    break;
            }
        }));
        SvipePlayUpdater.setEngine(new SvipePlayUpdater.Engine() {
            @Override
            public void check(Activity activity, SvipePlayUpdater.Callback onResult) {
                manager.getAppUpdateInfo().addOnSuccessListener(info -> {
                    final boolean downloaded = info.installStatus() == InstallStatus.DOWNLOADED;
                    final boolean available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                            && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE);
                    if (info.installStatus() == InstallStatus.DOWNLOADING) {
                        SvipePlayUpdater.onDownloading(info.bytesDownloaded(), info.totalBytesToDownload());
                    }
                    onResult.run(available || downloaded ? info.availableVersionCode() : 0, info.totalBytesToDownload(), downloaded);
                }).addOnFailureListener(e -> onResult.run(0, 0, false));
            }

            @Override
            public boolean start(Activity activity, int versionCode) {
                // An AppUpdateInfo starts a flow only once, so each start asks Play afresh.
                manager.getAppUpdateInfo().addOnSuccessListener(info -> {
                    if (info.availableVersionCode() != versionCode
                            || info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE
                            || !info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                            || activity.isFinishing()) {
                        return;
                    }
                    try {
                        manager.startUpdateFlowForResult(info, activity, AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE), REQUEST_CODE);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                });
                return true;
            }

            @Override
            public void complete() {
                // Play can report a download that is gone (a newer version was installed from the store
                // meanwhile): it then refuses, and the banner goes back to offering the update.
                manager.completeUpdate().addOnFailureListener(e -> AndroidUtilities.runOnUIThread(() -> SvipePlayUpdater.onStopped(false)));
            }

            @Override
            public void completeIfDownloaded() {
                // Not getAppUpdateInfo first: Play answers it only to an app in the foreground ("not
                // important enough"). completeUpdate itself just fails when nothing is downloaded.
                manager.completeUpdate();
            }
        });
    }
}
