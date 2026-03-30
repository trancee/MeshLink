package io.meshlink.peer

import io.meshlink.peer.PeerConnectionCoordinator.Companion.POWER_MODE_UNKNOWN
import io.meshlink.peer.PeerConnectionCoordinator.Companion.compareUnsignedBytes
import io.meshlink.protocol.ProtocolVersion
import io.meshlink.routing.PresenceState
import io.meshlink.routing.RoutingEngine
import io.meshlink.util.RateLimitResult
import io.meshlink.util.toHex
import io.meshlink.wire.AdvertisementCodec
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
        gossipIntervalMillis = 5_000L,
    )

    /** Build a minimal advertisement payload: [majorVersion, minorVersion, ...x25519Key] */
    private fun advPayload(major: Int = 1, minor: Int = 0, x25519Key: ByteArray? = null): ByteArray {
        val version = byteArrayOf(major.toByte(), minor.toByte())
        return if (x25519Key != null) version + x25519Key else version
    }

    /** Build a full 17-byte AdvertisementCodec payload with power mode and key hash. */
    private fun fullAdvPayload(
        major: Int = 1,
        minor: Int = 0,
        powerMode: Int = 0,
        keyHash: ByteArray = ByteArray(AdvertisementCodec.KEY_HASH_SIZE),
    ): ByteArray = AdvertisementCodec.encode(major, minor, powerMode, keyHash)

    private fun coordinator(
        routingEngine: RoutingEngine,
        isPaused: () -> Boolean = { false },
        rateLimitPolicy: (String) -> RateLimitResult = { RateLimitResult.Allowed },
        protocolVersion: ProtocolVersion = ProtocolVersion(1, 0),
        localPowerMode: () -> Int = { 0 },
        localKeyHash: () -> ByteArray = { ByteArray(0) },
    ) = PeerConnectionCoordinator(
        routingEngine = routingEngine,
        securityEngine = null, // No crypto for most unit tests
        rateLimitPolicy = rateLimitPolicy,
        trustStore = null,
        localPeerId = localPeerId,
        protocolVersion = protocolVersion,
        isPaused = isPaused,
        localPowerMode = localPowerMode,
        localKeyHash = localKeyHash,
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

    @Test
    fun fullPayload_incompatibleVersion_rejected() {
        val re = routingEngine()
        val c = coordinator(re, protocolVersion = ProtocolVersion(1, 0))
        val result = c.onAdvertisementReceived(peerA, fullAdvPayload(major = 5, minor = 0))
        assertIs<PeerConnectionAction.Rejected>(result)
    }

    @Test
    fun fullPayload_compatibleVersion_proceeds() {
        val re = routingEngine()
        val c = coordinator(re, protocolVersion = ProtocolVersion(1, 0))
        val result = c.onAdvertisementReceived(peerA, fullAdvPayload(major = 1, minor = 0))
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

    // ── Power-mode-aware tie-breaking ───────────────────────────

    @Test
    fun shouldInitiate_higherPower_initiates() {
        val re = routingEngine()
        // Local is PERFORMANCE (0), remote is POWER_SAVER (2)
        val c = coordinator(re, localPowerMode = { 0 })
        assertTrue(c.shouldInitiate(peerAHex, remotePowerMode = 2, remoteKeyHash = null))
    }

    @Test
    fun shouldInitiate_lowerPower_waits() {
        val re = routingEngine()
        // Local is POWER_SAVER (2), remote is PERFORMANCE (0)
        val c = coordinator(re, localPowerMode = { 2 })
        assertTrue(!c.shouldInitiate(peerAHex, remotePowerMode = 0, remoteKeyHash = null))
    }

    @Test
    fun shouldInitiate_samePower_higherKeyHashInitiates() {
        val re = routingEngine()
        val localHash = ByteArray(15) { 0xFF.toByte() }
        val remoteHash = ByteArray(15) { 0x01 }
        val c = coordinator(re, localPowerMode = { 1 }, localKeyHash = { localHash })
        // Local hash FF... > remote hash 01... → local initiates
        assertTrue(c.shouldInitiate(peerAHex, remotePowerMode = 1, remoteKeyHash = remoteHash))
    }

    @Test
    fun shouldInitiate_samePower_lowerKeyHashWaits() {
        val re = routingEngine()
        val localHash = ByteArray(15) { 0x01 }
        val remoteHash = ByteArray(15) { 0xFF.toByte() }
        val c = coordinator(re, localPowerMode = { 1 }, localKeyHash = { localHash })
        // Local hash 01... < remote hash FF... → local waits
        assertTrue(!c.shouldInitiate(peerAHex, remotePowerMode = 1, remoteKeyHash = remoteHash))
    }

    @Test
    fun shouldInitiate_unknownPowerMode_fallsToPeerId() {
        val re = routingEngine()
        val c = coordinator(re)
        // localPeerId (0x01...) < peerA (0x10...) → local initiates per legacy rule
        assertTrue(c.shouldInitiate(peerAHex, POWER_MODE_UNKNOWN, remoteKeyHash = null))
    }

    @Test
    fun shouldInitiate_samePower_noKeyHash_fallsToPeerId() {
        val re = routingEngine()
        val c = coordinator(re, localPowerMode = { 1 })
        // Same power mode but no key hashes → fall back to peer ID
        assertTrue(c.shouldInitiate(peerAHex, remotePowerMode = 1, remoteKeyHash = null))
    }

    @Test
    fun shouldInitiate_samePower_allZerosKeyHash_fallsToPeerId() {
        val re = routingEngine()
        // Even with local key hash, all-zeros remote hash is treated as placeholder
        val localHash = ByteArray(15) { 0xFF.toByte() }
        val allZeros = ByteArray(15)
        val c = coordinator(re, localPowerMode = { 1 }, localKeyHash = { localHash })
        // Falls back to peer ID comparison (local 0101... < remote 1010...)
        assertTrue(c.shouldInitiate(peerAHex, remotePowerMode = 1, remoteKeyHash = allZeros))
    }

    @Test
    fun shouldInitiate_symmetry_onlyOneInitiates() {
        // Verify that given the same inputs, exactly one side initiates
        val hashA = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val hashB = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x71.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

        val re1 = routingEngine()
        val c1 = coordinator(re1, localPowerMode = { 1 }, localKeyHash = { hashA })
        val sideA = c1.shouldInitiate(peerAHex, remotePowerMode = 1, remoteKeyHash = hashB)

        val re2 = routingEngine()
        val c2 = coordinator(re2, localPowerMode = { 1 }, localKeyHash = { hashB })
        val sideB = c2.shouldInitiate(localPeerId.toHex(), remotePowerMode = 1, remoteKeyHash = hashA)

        // Exactly one should initiate
        assertTrue(sideA != sideB, "Tie-breaking must be deterministic: exactly one side initiates")
    }

    // ── Unsigned byte comparison ────────────────────────────────

    @Test
    fun compareUnsignedBytes_greaterThan() {
        val a = byteArrayOf(0xFF.toByte(), 0x00)
        val b = byteArrayOf(0x01, 0x00)
        assertTrue(compareUnsignedBytes(a, b) > 0)
    }

    @Test
    fun compareUnsignedBytes_lessThan() {
        val a = byteArrayOf(0x01, 0x00)
        val b = byteArrayOf(0xFF.toByte(), 0x00)
        assertTrue(compareUnsignedBytes(a, b) < 0)
    }

    @Test
    fun compareUnsignedBytes_equal() {
        val a = byteArrayOf(0x42, 0x43)
        val b = byteArrayOf(0x42, 0x43)
        assertEquals(0, compareUnsignedBytes(a, b))
    }

    @Test
    fun compareUnsignedBytes_longerArrayGreater() {
        val a = byteArrayOf(0x42, 0x43, 0x01)
        val b = byteArrayOf(0x42, 0x43)
        assertTrue(compareUnsignedBytes(a, b) > 0)
    }

    @Test
    fun compareUnsignedBytes_highBitUnsigned() {
        // 0x80 as unsigned (128) should be > 0x7F (127)
        val a = byteArrayOf(0x80.toByte())
        val b = byteArrayOf(0x7F)
        assertTrue(compareUnsignedBytes(a, b) > 0)
    }
}
