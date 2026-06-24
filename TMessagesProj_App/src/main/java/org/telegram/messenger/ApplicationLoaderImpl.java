package org.telegram.messenger;

import android.app.Activity;
import android.view.ViewGroup;

import org.telegram.messenger.regular.BuildConfig;
import org.telegram.svipe.SvipeUpdateLayout;
import org.telegram.svipe.SvipeUpdater;
import org.telegram.ui.IUpdateLayout;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        // Only the direct-download builds (.web/.beta) show the native-style self-update banner;
        // the Play build updates via Google Play.
        if (!SvipeUpdater.isSelfUpdateBuild()) {
            return null;
        }
        return new SvipeUpdateLayout(activity, sideMenuContainer);
    }
}
