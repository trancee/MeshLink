package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

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

    @Test
    fun `concurrent callers across different peers never corrupt or drop each other's retry budget`() {
        runBlocking<Unit> {
            // Arrange -- this is the correctness property that must survive the
            // linkedMapOf-to-AtomicReference-compare-and-swap change in L2capReconnectGuard:
            // concurrent connect/disconnect paths for many different peers, racing on
            // Dispatchers.Default, must each observe exactly maxAttempts approved retries for
            // their own hintPeerIdValue -- no peer's budget may be corrupted, lost, or leaked into
            // another peer's count by a lost update under concurrent map access.
            val guard = L2capReconnectGuard()
            val peerCount = 64
            val attemptsPerPeer = 3

            // Act: each peer's attempts run concurrently with every other peer's attempts (and,
            // within a peer, sequentially -- shouldRetry's own return value is the retry-budget
            // signal, so this models the guard being invoked concurrently from unrelated peers'
            // connect-failure paths, which is the actual production concurrency shape).
            val approvedCountsByPeer =
                (1..peerCount)
                    .map { peerIndex ->
                        async(Dispatchers.Default) {
                            val hintPeerIdValue = "peer-$peerIndex"
                            val approvedCount =
                                (1..attemptsPerPeer + 1).count { attempt ->
                                    guard.shouldRetry(
                                        hintPeerIdValue = hintPeerIdValue,
                                        reason = "socket closed",
                                    )
                                }
                            hintPeerIdValue to approvedCount
                        }
                    }
                    .awaitAll()
                    .toMap()

            // Assert: every peer independently observed exactly attemptsPerPeer approved retries
            // (the (attemptsPerPeer + 1)-th call is always denied), regardless of how many other
            // peers were racing against the same guard instance at the same time.
            for (peerIndex in 1..peerCount) {
                assertEquals(
                    attemptsPerPeer,
                    approvedCountsByPeer.getValue("peer-$peerIndex"),
                    "peer-$peerIndex should have exactly $attemptsPerPeer approved retries",
                )
            }
        }
    }
}
