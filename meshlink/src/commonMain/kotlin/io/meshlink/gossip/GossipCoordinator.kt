package io.meshlink.gossip

import io.meshlink.crypto.SecurityEngine
import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.diagnostics.Severity
import io.meshlink.routing.RoutingEngine
import io.meshlink.util.hexToBytes
import io.meshlink.wire.RouteUpdateEntry
import io.meshlink.wire.WireCodec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordinates routing protocol maintenance: gossip broadcasting,
 * keepalive pings, and triggered route updates.
 *
 * Owns the triggered-update channel and gossip/keepalive loop logic,
 * previously scattered across MeshLink.kt. Transport sends are
 * injected as a function type for testability.
 */
class GossipCoordinator(
    private val routingEngine: RoutingEngine,
    private val securityEngine: SecurityEngine?,
    private val diagnosticSink: DiagnosticSink,
    private val localPeerId: ByteArray,
    private val gossipIntervalMs: Long,
    private val triggeredUpdateBatchMs: Long,
    private val keepaliveIntervalMs: Long,
    private val currentPowerMode: () -> String,
    private val sendFrame: suspend (peerId: ByteArray, frame: ByteArray) -> Unit,
    private val clock: () -> Long,
) {
    private val triggeredUpdateChannel = Channel<Unit>(Channel.CONFLATED)

    /** Signal that a significant route change occurred. */
    fun triggerUpdate() {
        triggeredUpdateChannel.trySend(Unit)
    }

    /**
     * Blocking gossip loop — launch in a coroutine scope.
     * Waits for either the gossip interval or a triggered update signal,
     * batches rapid triggers, then broadcasts route updates.
     */
    suspend fun runGossipLoop(isActive: () -> Boolean) {
        while (isActive()) {
            val triggered = withTimeoutOrNull(
                routingEngine.effectiveGossipInterval(currentPowerMode())
            ) {
                triggeredUpdateChannel.receive()
            }
            if (!isActive()) break
            if (triggered != null) {
                delay(triggeredUpdateBatchMs)
                if (!isActive()) break
            }
            broadcastRouteUpdate(isTriggered = triggered != null)
        }
    }

    /**
     * Blocking keepalive loop — launch in a coroutine scope.
     * Sends keepalive heartbeats when gossip has been idle.
     */
    suspend fun runKeepaliveLoop(isActive: () -> Boolean) {
        while (isActive()) {
            delay(keepaliveIntervalMs)
            if (!isActive()) break
            val sinceLastGossip = routingEngine.timeSinceLastGossip()
            if (gossipIntervalMs <= 0 || sinceLastGossip >= keepaliveIntervalMs) {
                broadcastKeepalive()
            }
        }
    }

    /**
     * Broadcasts route updates to all connected peers.
     * Applies split-horizon and poison-reverse via [RoutingEngine.prepareGossipEntries].
     */
    suspend fun broadcastRouteUpdate(isTriggered: Boolean = false) {
        val connectedPeers = routingEngine.connectedPeerIds()
        if (connectedPeers.isEmpty()) return

        for (peerHex in connectedPeers) {
            if (isTriggered && !routingEngine.shouldSendTriggeredUpdate(peerHex, currentPowerMode())) {
                continue
            }

            val gossipEntries = routingEngine.prepareGossipEntries(peerHex)
            val entries = gossipEntries.map { ge ->
                RouteUpdateEntry(
                    destination = hexToBytes(ge.destination),
                    cost = ge.cost,
                    sequenceNumber = ge.sequenceNumber,
                    hopCount = ge.hopCount,
                )
            }
            val updateData = if (securityEngine != null) {
                val unsigned = WireCodec.encodeRouteUpdate(localPeerId, entries)
                val signed = securityEngine.sign(unsigned + securityEngine.localBroadcastPublicKey)
                WireCodec.encodeSignedRouteUpdate(localPeerId, entries, signed.signerPublicKey, signed.signature)
            } else {
                WireCodec.encodeRouteUpdate(localPeerId, entries)
            }
            sendFrame(hexToBytes(peerHex), updateData)

            if (isTriggered) {
                routingEngine.recordTriggeredUpdate(peerHex)
            }
        }

        diagnosticSink.emit(
            DiagnosticCode.GOSSIP_TRAFFIC_REPORT, Severity.INFO,
            "peers=${connectedPeers.size}, routes=${routingEngine.routeCount}"
        )
        routingEngine.recordGossipSent()
    }

    /** Broadcasts keepalive heartbeat to all connected peers. */
    suspend fun broadcastKeepalive() {
        val connectedPeers = routingEngine.connectedPeerIds()
        if (connectedPeers.isEmpty()) return
        val nowSeconds = (clock() / 1000).toUInt()
        val frame = WireCodec.encodeKeepalive(nowSeconds)
        for (peerHex in connectedPeers) {
            sendFrame(hexToBytes(peerHex), frame)
        }
    }
}
