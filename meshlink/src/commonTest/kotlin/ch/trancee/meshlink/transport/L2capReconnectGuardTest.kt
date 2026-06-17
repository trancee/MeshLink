package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class L2capReconnectGuardTest {
    @Test
    fun `transient l2cap disconnect is retried only once per peer`() {
        // Arrange
        val guard = L2capReconnectGuard()

        // Act & Assert
        assertTrue(guard.shouldRetry(hintPeerIdValue = "peer-123", reason = "socket closed"))
        assertFalse(guard.shouldRetry(hintPeerIdValue = "peer-123", reason = "socket closed"))
        assertFalse(guard.shouldRetry(hintPeerIdValue = "peer-456", reason = "transport stopped"))
    }
}
