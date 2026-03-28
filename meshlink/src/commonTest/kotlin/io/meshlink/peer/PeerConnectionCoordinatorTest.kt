package io.meshlink.peer

import io.meshlink.protocol.ProtocolVersion
import io.meshlink.routing.PresenceState
import io.meshlink.routing.RoutingEngine
import io.meshlink.util.RateLimitResult
import io.meshlink.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerConnectionCoordinatorTest {

    private val localPeerId = ByteArray(16) { 0x01 }
    // Higher peerId so localPeerId < peerA (tie-breaking: local initiates)
    private val peerA = ByteArray(16) { 0x10 }
    private val peerAHex = peerA.toHex()

    private fun routingEngine() = RoutingEngine(
        localPeerId = localPeerId.toHex(),
        dedupCapacity = 100,
        triggeredUpdateThreshold = 0.3,
        gossipIntervalMs = 5_000L,
    )

    /** Build a minimal advertisement payload: [majorVersion, minorVersion, ...x25519Key] */
    private fun advPayload(major: Int = 1, minor: Int = 0, x25519Key: ByteArray? = null): ByteArray {
        val version = byteArrayOf(major.toByte(), minor.toByte())
        return if (x25519Key != null) version + x25519Key else version
    }

    private fun coordinator(
        routingEngine: RoutingEngine,
        isPaused: () -> Boolean = { false },
        rateLimitPolicy: (String) -> RateLimitResult = { RateLimitResult.Allowed },
        protocolVersion: ProtocolVersion = ProtocolVersion(1, 0),
    ) = PeerConnectionCoordinator(
        routingEngine = routingEngine,
        securityEngine = null, // No crypto for most unit tests
        rateLimitPolicy = rateLimitPolicy,
        trustStore = null,
        localPeerId = localPeerId,
        protocolVersion = protocolVersion,
        isPaused = isPaused,
    )

    // ── Paused state ────────────────────────────────────────────

    @Test
    fun paused_skipsProcessing() {
        val re = routingEngine()
        val c = coordinator(re, isPaused = { true })
        val result = c.onAdvertisementReceived(peerA, advPayload())
        assertIs<PeerConnectionAction.Skipped>(result)
    }

    // ── Version negotiation ─────────────────────────────────────

    @Test
    fun incompatibleVersion_rejected() {
        val re = routingEngine()
        val c = coordinator(re, protocolVersion = ProtocolVersion(1, 0))
        // Major version 5 is incompatible with major version 1
        val result = c.onAdvertisementReceived(peerA, advPayload(major = 5, minor = 0))
        assertIs<PeerConnectionAction.Rejected>(result)
    }

    @Test
    fun compatibleVersion_proceeds() {
        val re = routingEngine()
        val c = coordinator(re, protocolVersion = ProtocolVersion(1, 0))
        val result = c.onAdvertisementReceived(peerA, advPayload(major = 1, minor = 0))
        assertIs<PeerConnectionAction.PeerUpdate>(result)
    }

    @Test
    fun emptyPayload_skipsVersionCheck_proceeds() {
        val re = routingEngine()
        val c = coordinator(re)
        val result = c.onAdvertisementReceived(peerA, ByteArray(0))
        assertIs<PeerConnectionAction.PeerUpdate>(result)
    }

    // ── New vs existing peer ────────────────────────────────────

    @Test
    fun firstSighting_isNewPeer() {
        val re = routingEngine()
        val c = coordinator(re)
        val result = c.onAdvertisementReceived(peerA, advPayload())
        assertIs<PeerConnectionAction.PeerUpdate>(result)
        assertTrue(result.isNewPeer)
    }

    @Test
    fun secondSighting_isNotNewPeer() {
        val re = routingEngine()
        val c = coordinator(re)
        c.onAdvertisementReceived(peerA, advPayload())
        val result = c.onAdvertisementReceived(peerA, advPayload())
        assertIs<PeerConnectionAction.PeerUpdate>(result)
        assertTrue(!result.isNewPeer)
    }

    @Test
    fun newPeer_updatesRoutingPresence() {
        val re = routingEngine()
        val c = coordinator(re)
        assertNull(re.presenceState(peerAHex))
        c.onAdvertisementReceived(peerA, advPayload())
        assertEquals(PresenceState.CONNECTED, re.presenceState(peerAHex))
    }

    // ── No crypto: no handshake ─────────────────────────────────

    @Test
    fun noCrypto_noHandshakeMessage() {
        val re = routingEngine()
        val c = coordinator(re)
        val result = c.onAdvertisementReceived(peerA, advPayload()) as PeerConnectionAction.PeerUpdate
        assertNull(result.handshakeMessage)
        assertTrue(!result.handshakeRateLimited)
    }

    @Test
    fun noCrypto_noKeyChangeEvent() {
        val re = routingEngine()
        val c = coordinator(re)
        val key = ByteArray(32) { 0x55 }
        val result = c.onAdvertisementReceived(peerA, advPayload(x25519Key = key)) as PeerConnectionAction.PeerUpdate
        assertNull(result.keyChangeEvent)
    }

    // ── Peer lost ───────────────────────────────────────────────

    @Test
    fun peerLost_updatesRouting() {
        val re = routingEngine()
        val c = coordinator(re)
        // First discover the peer
        c.onAdvertisementReceived(peerA, advPayload())
        assertEquals(PresenceState.CONNECTED, re.presenceState(peerAHex))

        // Then lose it
        val result = c.onPeerLost(peerA)
        assertIs<PeerConnectionAction.Lost>(result)
        assertTrue(result.peerId.contentEquals(peerA))
        assertEquals(PresenceState.DISCONNECTED, re.presenceState(peerAHex))
    }

    // ── Rate limiting (without crypto, just verifying plumbing) ─

    @Test
    fun peerUpdate_returnsCorrectPeerId() {
        val re = routingEngine()
        val c = coordinator(re)
        val result = c.onAdvertisementReceived(peerA, advPayload()) as PeerConnectionAction.PeerUpdate
        assertTrue(result.peerId.contentEquals(peerA))
    }

    // ── Version edge cases ──────────────────────────────────────

    @Test
    fun adjacentMajorVersion_compatible() {
        val re = routingEngine()
        val c = coordinator(re, protocolVersion = ProtocolVersion(2, 0))
        // Major 1 is adjacent to major 2 (gap = 1)
        val result = c.onAdvertisementReceived(peerA, advPayload(major = 1, minor = 0))
        assertIs<PeerConnectionAction.PeerUpdate>(result)
    }

    @Test
    fun twoMajorVersionsApart_rejected() {
        val re = routingEngine()
        val c = coordinator(re, protocolVersion = ProtocolVersion(3, 0))
        // Major 1 vs major 3 (gap = 2 > 1)
        val result = c.onAdvertisementReceived(peerA, advPayload(major = 1, minor = 0))
        assertIs<PeerConnectionAction.Rejected>(result)
    }
}
