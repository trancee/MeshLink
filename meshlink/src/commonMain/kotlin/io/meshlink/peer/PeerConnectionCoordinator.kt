package io.meshlink.peer

import io.meshlink.crypto.KeyRegistrationResult
import io.meshlink.crypto.SecurityEngine
import io.meshlink.crypto.TrustStore
import io.meshlink.crypto.VerifyResult
import io.meshlink.model.KeyChangeEvent
import io.meshlink.protocol.ProtocolVersion
import io.meshlink.routing.PresenceState
import io.meshlink.routing.RoutingEngine
import io.meshlink.util.ByteArrayKey
import io.meshlink.util.RateLimitResult
import io.meshlink.util.toKey
import io.meshlink.wire.AdvertisementCodec

/**
 * Result of processing a BLE advertisement or peer-lost event.
 * MeshLink pattern-matches on this sealed type to execute transport
 * sends, flow emissions, and transfer resumptions.
 */
sealed interface PeerConnectionAction {
    /** Incompatible protocol version — peer rejected. */
    data object Rejected : PeerConnectionAction

    /** Mesh is paused — skip processing. */
    data object Skipped : PeerConnectionAction

    /** Peer update with all data MeshLink needs to execute effects. */
    data class PeerUpdate(
        val peerId: ByteArray,
        val isNewPeer: Boolean,
        val keyChangeEvent: KeyChangeEvent?,
        val handshakeMessage: ByteArray?,
        val handshakeRateLimited: Boolean,
    ) : PeerConnectionAction

    /** Peer lost — MeshLink should emit Lost event and update health. */
    data class Lost(val peerId: ByteArray) : PeerConnectionAction
}

/**
 * Coordinates BLE peer discovery: version negotiation, routing presence,
 * security key registration, and handshake initiation.
 *
 * Previously the 58-line advertisement handler in MeshLink.start().
 * Returns sealed [PeerConnectionAction] so MeshLink can execute
 * transport sends and flow emissions.
 *
 * **Connection role tie-breaking (design § Connection Role Tie-Breaking):**
 * When a full [AdvertisementCodec] payload is available, the higher-power
 * device acts as central (initiator) so the lower-power device can use
 * BLE peripheral slave latency. When power modes are equal, the peer
 * with the lexicographically higher key hash initiates. Falls back to
 * peer-ID comparison when the advertisement payload is too short.
 */
class PeerConnectionCoordinator(
    private val routingEngine: RoutingEngine,
    private val securityEngine: SecurityEngine?,
    private val rateLimitPolicy: (ByteArrayKey) -> RateLimitResult,
    private val trustStore: TrustStore?,
    private val localPeerId: ByteArray,
    private val protocolVersion: ProtocolVersion,
    private val isPaused: () -> Boolean,
    private val localPowerMode: () -> Int = { 0 },
    private val localKeyHash: () -> ByteArray = { ByteArray(0) },
) {
    /**
     * Process a BLE advertisement event.
     * Performs version negotiation, updates routing presence, registers
     * peer keys, and determines whether a handshake should be initiated.
     */
    fun onAdvertisementReceived(peerId: ByteArray, advertisementPayload: ByteArray): PeerConnectionAction {
        if (isPaused()) return PeerConnectionAction.Skipped

        // Protocol version negotiation and power mode extraction.
        // Exactly 17 bytes → AdvertisementCodec format: byte 0 = [major:4][power:4].
        // Other sizes ≥ 2 bytes → legacy format: byte 0 = major, byte 1 = minor.
        var remotePowerMode = POWER_MODE_UNKNOWN
        var remoteKeyHash: ByteArray? = null

        if (advertisementPayload.size == AdvertisementCodec.SIZE) {
            val adv = AdvertisementCodec.decode(advertisementPayload)
            val remoteVersion = ProtocolVersion(adv.versionMajor, adv.versionMinor)
            if (protocolVersion.negotiate(remoteVersion) == null) {
                return PeerConnectionAction.Rejected
            }
            remotePowerMode = adv.powerMode
            remoteKeyHash = adv.keyHash
        } else if (advertisementPayload.size >= 2) {
            val remoteMajor = advertisementPayload[0].toInt() and 0xFF
            val remoteMinor = advertisementPayload[1].toInt() and 0xFF
            val remoteVersion = ProtocolVersion(remoteMajor, remoteMinor)
            if (protocolVersion.negotiate(remoteVersion) == null) {
                return PeerConnectionAction.Rejected
            }
        }

        val peerKey = peerId.toKey()
        val isNewPeer = routingEngine.presenceState(peerKey) != PresenceState.CONNECTED
        routingEngine.peerSeen(peerKey)

        // Key registration (requires full 32-byte X25519 key in extended payload)
        var keyChangeEvent: KeyChangeEvent? = null
        if (advertisementPayload.size >= 34 && securityEngine != null) {
            val newKey = advertisementPayload.copyOfRange(2, 34)
            val regResult = securityEngine.registerPeerKey(peerKey, newKey)
            if (regResult is KeyRegistrationResult.Changed) {
                keyChangeEvent = KeyChangeEvent(
                    peerId = peerId.copyOf(),
                    previousKey = regResult.previousKey.copyOf(),
                    newKey = newKey.copyOf(),
                )
            }
        }

        // Handshake initiation (power-mode-aware tie-breaking)
        var handshakeMessage: ByteArray? = null
        var handshakeRateLimited = false
        if (securityEngine != null && !securityEngine.isHandshakeComplete(peerId)) {
            if (shouldInitiate(peerId, remotePowerMode, remoteKeyHash)) {
                val isPinned = trustStore?.let { ts ->
                    val key = securityEngine.peerPublicKey(peerKey)
                    key != null && ts.verify(peerKey.toString(), key) is VerifyResult.Trusted
                } ?: false

                if (!isPinned && rateLimitPolicy(peerKey) is RateLimitResult.Limited) {
                    handshakeRateLimited = true
                } else {
                    handshakeMessage = securityEngine.initiateHandshake(peerId)
                }
            }
        }

        return PeerConnectionAction.PeerUpdate(
            peerId = peerId,
            isNewPeer = isNewPeer,
            keyChangeEvent = keyChangeEvent,
            handshakeMessage = handshakeMessage,
            handshakeRateLimited = handshakeRateLimited,
        )
    }

    /** Process a peer-lost event. Updates routing engine. */
    fun onPeerLost(peerId: ByteArray): PeerConnectionAction.Lost {
        routingEngine.markDisconnected(peerId.toKey())
        return PeerConnectionAction.Lost(peerId)
    }

    /**
     * Power-mode-aware tie-breaking per design § Connection Role Tie-Breaking.
     *
     * - Higher-power device (lower [PowerMode] ordinal) acts as central.
     * - Same power → lexicographically higher key hash acts as central.
     * - Fallback (no power/key info): lower peer ID initiates (legacy).
     */
    internal fun shouldInitiate(
        peerId: ByteArray,
        remotePowerMode: Int,
        remoteKeyHash: ByteArray?,
    ): Boolean {
        val localPower = localPowerMode()

        if (remotePowerMode != POWER_MODE_UNKNOWN) {
            // Lower ordinal = higher power (PERFORMANCE=0, BALANCED=1, POWER_SAVER=2)
            if (localPower < remotePowerMode) return true
            if (localPower > remotePowerMode) return false

            // Same power mode — higher key hash initiates.
            // Only if both hashes are non-trivial (not placeholder all-zeros).
            val localHash = localKeyHash()
            if (remoteKeyHash != null && localHash.isNotEmpty() && !isAllZeros(remoteKeyHash)) {
                return compareUnsignedBytes(localHash, remoteKeyHash) > 0
            }
        }

        // Fallback: lower peer ID initiates (legacy behavior)
        return compareUnsignedBytes(localPeerId, peerId) < 0
    }

    companion object {
        /** Sentinel indicating no power mode info available in the advertisement. */
        const val POWER_MODE_UNKNOWN = -1

        /**
         * Unsigned lexicographic byte-array comparison.
         * Returns positive if [a] > [b], negative if [a] < [b], 0 if equal.
         */
        internal fun compareUnsignedBytes(a: ByteArray, b: ByteArray): Int {
            val len = minOf(a.size, b.size)
            for (i in 0 until len) {
                val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (diff != 0) return diff
            }
            return a.size - b.size
        }

        private fun isAllZeros(data: ByteArray): Boolean = data.all { it == 0.toByte() }
    }
}
