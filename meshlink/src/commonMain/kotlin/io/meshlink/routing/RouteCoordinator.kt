package io.meshlink.routing

import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.diagnostics.Severity
import io.meshlink.wire.WireCodec
import kotlinx.coroutines.delay

/**
 * Coordinates AODV reactive routing and keepalive heartbeats.
 *
 * Replaces GossipCoordinator. No periodic route table flooding — routes are
 * discovered on-demand via RREQ/RREP in [MessageDispatcher]. This coordinator
 * handles only keepalive heartbeats for neighbour liveness detection.
 */
class RouteCoordinator(
    private val routingEngine: RoutingEngine,
    private val diagnosticSink: DiagnosticSink,
    private val keepaliveIntervalMillis: Long,
    private val sendFrame: suspend (peerId: ByteArray, frame: ByteArray) -> Unit,
    private val clock: () -> Long,
) {
    /**
     * Blocking keepalive loop — launch in a coroutine scope.
     * Sends keepalive heartbeats to all connected neighbours.
     */
    suspend fun runKeepaliveLoop(isActive: () -> Boolean) {
        while (isActive()) {
            delay(keepaliveIntervalMillis)
            if (!isActive()) break
            broadcastKeepalive()
        }
    }

    /** Broadcasts keepalive heartbeat to all connected peers. */
    suspend fun broadcastKeepalive() {
        val connectedPeers = routingEngine.connectedPeerIds()
        if (connectedPeers.isEmpty()) return
        val nowMillis = clock().toULong()
        val frame = WireCodec.encodeKeepalive(nowMillis)
        for (peerId in connectedPeers) {
            sendFrame(peerId.bytes, frame)
        }
    }

    /**
     * Flood a route request to all connected neighbours.
     * Called by MeshLink when a send attempt returns Unreachable.
     */
    suspend fun floodRouteRequest(rreqFrame: ByteArray) {
        val connectedPeers = routingEngine.connectedPeerIds()
        if (connectedPeers.isEmpty()) return
        for (peerId in connectedPeers) {
            sendFrame(peerId.bytes, rreqFrame)
        }
        diagnosticSink.emit(
            DiagnosticCode.GOSSIP_TRAFFIC_REPORT,
            Severity.INFO,
            "RREQ flooded to ${connectedPeers.size} peers",
        )
    }
}
