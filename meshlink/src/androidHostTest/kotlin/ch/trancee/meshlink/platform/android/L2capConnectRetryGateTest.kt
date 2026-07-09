package ch.trancee.meshlink.platform.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class L2capConnectRetryGateTest {
    @Test
    fun retryIsAttemptedWhenFailureIsRetryableAndNoGattFallbackIsReady(): Unit {
        // Arrange & Act
        val shouldAttempt =
            shouldAttemptL2capConnectRetry(retryableFailure = true, gattFallbackReady = false)

        // Assert
        assertTrue(shouldAttempt)
    }

    @Test
    fun retryIsSkippedWhenAGattFallbackLinkIsAlreadyReady(): Unit {
        // Arrange & Act: mirrors closeLink()'s existing gate -- a peer already reachable over its
        // GATT side-link fallback doesn't need a redundant L2CAP retry scheduled.
        val shouldAttempt =
            shouldAttemptL2capConnectRetry(retryableFailure = true, gattFallbackReady = true)

        // Assert
        assertFalse(shouldAttempt)
    }

    @Test
    fun retryIsSkippedWhenTheFailureIsClassifiedTerminal(): Unit {
        // Arrange & Act
        val shouldAttempt =
            shouldAttemptL2capConnectRetry(retryableFailure = false, gattFallbackReady = false)

        // Assert
        assertFalse(shouldAttempt)
    }
}
