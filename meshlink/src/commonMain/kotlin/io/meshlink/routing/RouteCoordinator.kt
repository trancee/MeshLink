package io.meshlink.routing

import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.diagnostics.Severity
import io.meshlink.wire.WireCodec
import kotlinx.coroutines.delay

/**
 * Coordinates Babel routing operations and keepalive heartbeats.
 *
 * Sends periodic Hello messages (which double as keepalives) and
 * triggered route Updates on topology changes. Also retains legacy
 * RREQ flooding for backward compatibility with AODV route discovery.
 */
class RouteCoordinator(
    private val routingEngine: RoutingEngine,
    private val diagnosticSink: DiagnosticSink,
    private val keepaliveIntervalMillis: Long,
    private val sendFrame: suspend (peerId: ByteArray, frame: ByteArray) -> Unit,
    private val clock: () -> Long,
) {
    /**
     * Blocking keepalive + Hello loop — launch in a coroutine scope.
     * Sends Babel Hello (which doubles as keepalive) to all connected neighbors.
     * Periodically bumps local seqno for route freshness.
     */
    suspend fun runKeepaliveLoop(isActive: () -> Boolean) {
        var updateCounter = 0
        while (isActive()) {
            delay(keepaliveIntervalMillis)
            if (!isActive()) break

            broadcastKeepalive()

            // Send periodic full routing table update every 4× Hello interval
            updateCounter++
            if (updateCounter >= 4) {
                updateCounter = 0
                routingEngine.bumpSeqNo()
                broadcastFullUpdate()
            }
        }
    }

    /** Sends Babel Hello (+ keepalive) to all connected peers. */
    suspend fun broadcastKeepalive() {
        val connectedPeers = routingEngine.connectedPeerIds()
        if (connectedPeers.isEmpty()) return

        // Send standard keepalive
        val nowMillis = clock().toULong()
        val keepaliveFrame = WireCodec.encodeKeepalive(nowMillis)
        for (peerId in connectedPeers) {
            sendFrame(peerId.bytes, keepaliveFrame)
        }

        // Send Babel Hello
        val helloFrame = routingEngine.buildHello()
        for (peerId in connectedPeers) {
            sendFrame(peerId.bytes, helloFrame)
        }
    }

    /** Sends full routing table as Babel Updates to all connected peers. */
    suspend fun broadcastFullUpdate() {
        val connectedPeers = routingEngine.connectedPeerIds()
        if (connectedPeers.isEmpty()) return

        val updates = routingEngine.buildFullUpdateSet()
        for (peerId in connectedPeers) {
            for (update in updates) {
                sendFrame(peerId.bytes, update)
            }
        }

        diagnosticSink.emit(
            DiagnosticCode.GOSSIP_TRAFFIC_REPORT,
            Severity.INFO,
            "Babel full update: ${updates.size} routes to ${connectedPeers.size} peers",
        )
    }

    /**
     * Flood a route request to all connected neighbours.
     * Legacy AODV support — called by MeshLink when send() finds no route.
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
