package io.meshlink.peer

import io.meshlink.crypto.KeyRegistrationResult
import io.meshlink.crypto.SecurityEngine
import io.meshlink.crypto.TrustStore
import io.meshlink.crypto.VerifyResult
import io.meshlink.model.KeyChangeEvent
import io.meshlink.protocol.ProtocolVersion
import io.meshlink.routing.PresenceState
import io.meshlink.routing.RoutingEngine
import io.meshlink.util.RateLimitResult
import io.meshlink.util.toHex

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
 */
class PeerConnectionCoordinator(
    private val routingEngine: RoutingEngine,
    private val securityEngine: SecurityEngine?,
    private val rateLimitPolicy: (String) -> RateLimitResult,
    private val trustStore: TrustStore?,
    private val localPeerId: ByteArray,
    private val protocolVersion: ProtocolVersion,
    private val isPaused: () -> Boolean,
) {
    /**
     * Process a BLE advertisement event.
     * Performs version negotiation, updates routing presence, registers
     * peer keys, and determines whether a handshake should be initiated.
     */
    fun onAdvertisementReceived(peerId: ByteArray, advertisementPayload: ByteArray): PeerConnectionAction {
        if (isPaused()) return PeerConnectionAction.Skipped

        // Protocol version negotiation
        if (advertisementPayload.size >= 2) {
            val remoteMajor = advertisementPayload[0].toInt() and 0xFF
            val remoteMinor = advertisementPayload[1].toInt() and 0xFF
            val remoteVersion = ProtocolVersion(remoteMajor, remoteMinor)
            if (protocolVersion.negotiate(remoteVersion) == null) {
                return PeerConnectionAction.Rejected
            }
        }

        val peerHex = peerId.toHex()
        val isNewPeer = routingEngine.presenceState(peerHex) != PresenceState.CONNECTED
        routingEngine.peerSeen(peerHex)

        // Key registration
        var keyChangeEvent: KeyChangeEvent? = null
        if (advertisementPayload.size >= 34 && securityEngine != null) {
            val newKey = advertisementPayload.copyOfRange(2, 34)
            val regResult = securityEngine.registerPeerKey(peerHex, newKey)
            if (regResult is KeyRegistrationResult.Changed) {
                keyChangeEvent = KeyChangeEvent(
                    peerId = peerId.copyOf(),
                    previousKey = regResult.previousKey.copyOf(),
                    newKey = newKey.copyOf(),
                )
            }
        }

        // Handshake initiation (deterministic: lower peerId initiates)
        var handshakeMessage: ByteArray? = null
        var handshakeRateLimited = false
        if (securityEngine != null && !securityEngine.isHandshakeComplete(peerId)) {
            if (localPeerId.toHex() < peerHex) {
                val isPinned = trustStore?.let { ts ->
                    val key = securityEngine.peerPublicKey(peerHex)
                    key != null && ts.verify(peerHex, key) is VerifyResult.Trusted
                } ?: false

                if (!isPinned && rateLimitPolicy(peerHex) is RateLimitResult.Limited) {
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
        routingEngine.markDisconnected(peerId.toHex())
        return PeerConnectionAction.Lost(peerId)
    }
}
