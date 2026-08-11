package org.telegram.svipe;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Where in a show the viewer got to — the episode, not the second.
 *
 * <p>Position INSIDE an episode is already remembered per message by the player (the shared
 * {@code media_saved_pos} store), so the only thing missing to make a show resumable is which episode
 * was last opened. That is one integer per show, and it is local by design: it costs no request, it
 * works offline, and a watch history is not something to ship to a server for a feature this small.
 */
public class SvipeSeriesProgress {

    private static final String PREFS = "svipe_series_progress";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The episode index to open a show at — 0 for a show never opened. */
    public static int lastEpisode(long seriesId) {
        return Math.max(0, prefs().getInt(String.valueOf(seriesId), 0));
    }

    public static void setLastEpisode(long seriesId, int index) {
        if (seriesId == 0 || index < 0) {
            return;
        }
        prefs().edit().putInt(String.valueOf(seriesId), index).apply();
    }
}
