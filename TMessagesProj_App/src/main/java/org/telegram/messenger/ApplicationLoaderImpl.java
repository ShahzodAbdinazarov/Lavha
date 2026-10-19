package org.telegram.messenger;

import android.app.Activity;
import android.view.ViewGroup;

import org.telegram.messenger.regular.BuildConfig;
import org.telegram.svipe.SvipePlayUpdater;
import org.telegram.svipe.SvipeUpdateLayout;
import org.telegram.svipe.SvipeUpdater;
import org.telegram.ui.IUpdateLayout;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PlayUpdates.install(); // the Play build only: Google Play's in-app updates
    }

    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        // The same banner for every build: the direct-download builds (.web/.beta) update over HTTP,
        // the Play build through Google Play's in-app updates.
        if (!SvipeUpdater.isSelfUpdateBuild() && !SvipePlayUpdater.isActive()) {
            return null;
        }
        return new SvipeUpdateLayout(activity, sideMenuContainer);
    }
}
