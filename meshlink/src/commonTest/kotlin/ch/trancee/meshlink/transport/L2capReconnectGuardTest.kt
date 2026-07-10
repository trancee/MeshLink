package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class L2capReconnectGuardTest {
    @Test
    fun `transient l2cap disconnect is retried up to the backoff schedule length per peer`() {
        // Arrange
        val guard = L2capReconnectGuard()

        // Act & Assert: three retries allowed (matching the 3-step backoff schedule), then denied.
        assertTrue(guard.shouldRetry(hintPeerIdValue = "peer-123", reason = "socket closed"))
        assertTrue(guard.shouldRetry(hintPeerIdValue = "peer-123", reason = "socket closed"))
        assertTrue(guard.shouldRetry(hintPeerIdValue = "peer-123", reason = "socket closed"))
        assertFalse(guard.shouldRetry(hintPeerIdValue = "peer-123", reason = "socket closed"))
        assertFalse(guard.shouldRetry(hintPeerIdValue = "peer-456", reason = "transport stopped"))
    }

    @Test
    fun `connect failed reason is treated as a transient l2cap disconnect`() {
        // Arrange
        val guard = L2capReconnectGuard()

        // Act & Assert
        assertTrue(
            guard.shouldRetry(hintPeerIdValue = "peer-789", reason = "connect failed: timed out")
        )
    }

    @Test
    fun `send failed reason is treated as a transient l2cap disconnect`() {
        // Arrange
        val guard = L2capReconnectGuard()

        // Act & Assert
        assertTrue(
            guard.shouldRetry(hintPeerIdValue = "peer-789", reason = "send failed: broken pipe")
        )
    }

    @Test
    fun `keepalive write failure is classified as a send failure and retried`() {
        // Arrange: the L2CAP keepalive loop closes a link with this exact reason string on a
        // failed keepalive write (see BleTransportAdapterL2capSupport.launchL2capKeepAliveLoop) --
        // it must be classified the same as any other transient send failure, not silently
        // excluded from automatic reconnect by not matching any known prefix.
        val guard = L2capReconnectGuard()

        // Act & Assert
        assertTrue(
            guard.shouldRetry(
                hintPeerIdValue = "peer-789",
                reason = "send failed: keepalive write failed",
            )
        )
    }

    @Test
    fun `backoff delay increases with each approved retry`() {
        // Arrange
        val guard = L2capReconnectGuard()

        // Act
        guard.shouldRetry(hintPeerIdValue = "peer-123", reason = "socket closed")
        val firstBackoff = guard.backoffMillisFor("peer-123")
        guard.shouldRetry(hintPeerIdValue = "peer-123", reason = "socket closed")
        val secondBackoff = guard.backoffMillisFor("peer-123")

        // Assert
        assertTrue(secondBackoff > firstBackoff)
    }

    @Test
    fun `resetSuccess restores the full retry budget`() {
        // Arrange
        val guard = L2capReconnectGuard()
        repeat(3) { guard.shouldRetry(hintPeerIdValue = "peer-123", reason = "socket closed") }
        assertFalse(guard.shouldRetry(hintPeerIdValue = "peer-123", reason = "socket closed"))

        // Act
        guard.resetSuccess("peer-123")

        // Assert
        assertTrue(guard.shouldRetry(hintPeerIdValue = "peer-123", reason = "socket closed"))
    }
}
