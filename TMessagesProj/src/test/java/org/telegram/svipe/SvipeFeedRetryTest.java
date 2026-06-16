package org.telegram.svipe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.tgnet.ConnectionsManager;

public class SvipeFeedRetryTest {

    @Test
    public void retriesWhenConnectedAfterFailure() {
        assertTrue(SvipeFeedRetry.shouldRetry(ConnectionsManager.ConnectionStateConnected, true, false));
    }

    @Test
    public void retriesWhenUpdatingAfterFailure() {
        assertTrue(SvipeFeedRetry.shouldRetry(ConnectionsManager.ConnectionStateUpdating, true, false));
    }

    @Test
    public void doesNotRetryWithoutFailure() {
        assertFalse(SvipeFeedRetry.shouldRetry(ConnectionsManager.ConnectionStateConnected, false, false));
    }

    @Test
    public void doesNotRetryWhileAlreadyLoading() {
        assertFalse(SvipeFeedRetry.shouldRetry(ConnectionsManager.ConnectionStateConnected, true, true));
    }

    @Test
    public void doesNotRetryWhileStillOfflineOrConnecting() {
        assertFalse(SvipeFeedRetry.shouldRetry(ConnectionsManager.ConnectionStateWaitingForNetwork, true, false));
        assertFalse(SvipeFeedRetry.shouldRetry(ConnectionsManager.ConnectionStateConnecting, true, false));
    }
}
