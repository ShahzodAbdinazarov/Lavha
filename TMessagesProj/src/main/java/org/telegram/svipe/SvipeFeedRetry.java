package org.telegram.svipe;

import org.telegram.tgnet.ConnectionsManager;

/**
 * Decides when the Reels feed should auto-retry after a failed load. Mirrors Telegram's own
 * pattern: observe NotificationCenter.didUpdateConnectionState and act on the connection state.
 * Kept free of Android dependencies so it can be unit-tested on the JVM.
 */
public class SvipeFeedRetry {

    public static boolean shouldRetry(int connectionState, boolean feedLoadFailed, boolean loadingFeed) {
        if (!feedLoadFailed || loadingFeed) {
            return false;
        }
        return connectionState == ConnectionsManager.ConnectionStateConnected
                || connectionState == ConnectionsManager.ConnectionStateUpdating;
    }
}
