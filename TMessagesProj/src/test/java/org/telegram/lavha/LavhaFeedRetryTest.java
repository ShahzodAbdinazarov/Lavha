package org.telegram.lavha;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.tgnet.ConnectionsManager;

public class LavhaFeedRetryTest {

    @Test
    public void retriesWhenConnectedAfterFailure() {
        assertTrue(LavhaFeedRetry.shouldRetry(ConnectionsManager.ConnectionStateConnected, true, false));
    }

    @Test
    public void retriesWhenUpdatingAfterFailure() {
        assertTrue(LavhaFeedRetry.shouldRetry(ConnectionsManager.ConnectionStateUpdating, true, false));
    }

    @Test
    public void doesNotRetryWithoutFailure() {
        assertFalse(LavhaFeedRetry.shouldRetry(ConnectionsManager.ConnectionStateConnected, false, false));
    }

    @Test
    public void doesNotRetryWhileAlreadyLoading() {
        assertFalse(LavhaFeedRetry.shouldRetry(ConnectionsManager.ConnectionStateConnected, true, true));
    }

    @Test
    public void doesNotRetryWhileStillOfflineOrConnecting() {
        assertFalse(LavhaFeedRetry.shouldRetry(ConnectionsManager.ConnectionStateWaitingForNetwork, true, false));
        assertFalse(LavhaFeedRetry.shouldRetry(ConnectionsManager.ConnectionStateConnecting, true, false));
    }
}
