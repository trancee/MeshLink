package ch.trancee.meshlink.presence

import ch.trancee.meshlink.api.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PeerPresenceTrackerTest {
    @Test
    fun `onPeerConnected returns found on first observation and state changed afterwards`() =
        runBlocking<Unit> {
            // Arrange
            val tracker = PeerPresenceTracker()
            val peerId = PeerId("peer-abcdef")

            // Act
            val firstTransition = tracker.onPeerConnected(peerId)
            val secondTransition = tracker.onPeerConnected(peerId)

            // Assert
            assertEquals(PresenceTransition.FOUND, firstTransition)
            assertEquals(PresenceTransition.STATE_CHANGED, secondTransition)
        }

    @Test
    fun `onPeerDisconnected reports whether the peer was present`() =
        runBlocking<Unit> {
            // Arrange
            val tracker = PeerPresenceTracker()
            val peerId = PeerId("peer-abcdef")
            tracker.onPeerConnected(peerId)

            // Act
            val removed = tracker.onPeerDisconnected(peerId)
            val removedAgain = tracker.onPeerDisconnected(peerId)

            // Assert
            assertTrue(removed)
            assertFalse(removedAgain)
        }

    @Test
    fun `clear returns peers in insertion order and empties the tracker`() =
        runBlocking<Unit> {
            // Arrange
            val tracker = PeerPresenceTracker()
            val firstPeerId = PeerId("peer-first")
            val secondPeerId = PeerId("peer-second")
            tracker.onPeerConnected(firstPeerId)
            tracker.onPeerConnected(secondPeerId)

            // Act
            val clearedPeers = tracker.clear()
            val nextTransition = tracker.onPeerConnected(firstPeerId)

            // Assert
            assertEquals(
                listOf(firstPeerId.value, secondPeerId.value),
                clearedPeers.map { it.value },
            )
            assertEquals(PresenceTransition.FOUND, nextTransition)
        }
}
