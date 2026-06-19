package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.reference.model.PeerTrustState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveReferenceSessionProjectorPeerEventsTest {
    @Test
    fun `recordPeerTrustReset marks the peer forgotten when trust is cleared`() {
        // Arrange
        val stateStore = referenceStateStore()
        val projector = LiveReferenceSessionProjector(stateStore = stateStore)

        // Act
        projector.recordPeerTrustReset(peerId = TEST_PEER_ID, result = ForgetPeerResult.Forgotten)

        // Assert
        val snapshot = stateStore.currentSnapshot
        assertEquals(PeerTrustState.FORGOTTEN, snapshot.peers.single().trustState)
        assertEquals("Peer trust reset", snapshot.timeline.last().title)
        assertEquals(
            "forgetPeer(${TEST_PEER_SUFFIX}) -> Forgotten",
            snapshot.timeline.last().detail,
        )
    }

    @Test
    fun `recordPeerTrustReset keeps the peer unknown when no trust state exists`() {
        // Arrange
        val stateStore = referenceStateStore(trustState = PeerTrustState.TRUSTED)
        val projector = LiveReferenceSessionProjector(stateStore = stateStore)

        // Act
        projector.recordPeerTrustReset(peerId = TEST_PEER_ID, result = ForgetPeerResult.NotFound)

        // Assert
        val snapshot = stateStore.currentSnapshot
        assertEquals(PeerTrustState.UNKNOWN, snapshot.peers.single().trustState)
        assertEquals("forgetPeer(${TEST_PEER_SUFFIX}) -> NotFound", snapshot.timeline.last().detail)
    }

    @Test
    fun `recordInboundMessage appends payload evidence and updates the peer outcome`() {
        // Arrange
        val stateStore = referenceStateStore()
        val runtimeLogs = mutableListOf<String>()
        val projector =
            LiveReferenceSessionProjector(stateStore = stateStore, runtimeLogger = runtimeLogs::add)
        val message =
            InboundMessage(
                originPeerId = PeerId(TEST_PEER_ID),
                payload = "hello from relay".encodeToByteArray(),
                receivedAtEpochMillis = 2_000L,
                priority = ch.trancee.meshlink.api.DeliveryPriority.NORMAL,
            )

        // Act
        projector.recordInboundMessage(message)

        // Assert
        val snapshot = stateStore.currentSnapshot
        val inboundTimelineEntry = snapshot.timeline.first { it.title == "Inbound message" }
        val trustTimelineEntry = snapshot.timeline.last()
        assertEquals("Inbound message", inboundTimelineEntry.title)
        assertEquals(TEST_PEER_SUFFIX, inboundTimelineEntry.peerSuffix)
        assertEquals("hello from relay", inboundTimelineEntry.fullPayload)
        assertEquals("Trust established", trustTimelineEntry.title)
        assertEquals(TEST_PEER_SUFFIX, trustTimelineEntry.peerSuffix)
        assertEquals("Inbound message received", snapshot.session.lastOutcomeSummary)
        assertEquals(TEST_PEER_ID, snapshot.session.selectedPeerId)
        assertEquals(PeerTrustState.TRUSTED, snapshot.peers.single().trustState)
        assertEquals("Inbound 16 bytes", snapshot.peers.single().lastDeliveryOutcome)
        assertEquals(2, runtimeLogs.size)
        assertTrue(runtimeLogs.any { it.contains("origin=$TEST_PEER_ID") })
        assertTrue(runtimeLogs.any { it.contains("inbound.selected peer=$TEST_PEER_ID") })
    }
}
