package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DiagnosticCode
import ch.trancee.meshlink.api.DiagnosticPayload
import ch.trancee.meshlink.api.DiagnosticSinkApi
import ch.trancee.meshlink.api.NoOpDiagnosticSink
import ch.trancee.meshlink.api.PeerIdHex
import ch.trancee.meshlink.api.toPeerIdHex
import ch.trancee.meshlink.power.PowerManager
import ch.trancee.meshlink.routing.InternalPeerState
import ch.trancee.meshlink.routing.PeerEvent
import ch.trancee.meshlink.routing.PresenceTracker
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transport.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Orchestrates periodic sweep of disconnected peers and coordinates cross-subsystem cleanup when
 * peers transition to Gone state.
 *
 * Owns a fixed-interval sweep timer (default 30 seconds). On each tick:
 * 1. Calls [PresenceTracker.sweep] to advance the FSM and collect newly-Gone peers.
 * 2. For each Gone peer: retracts routes, releases power slot, and removes neighbor state via
 *    [RouteCoordinator.onPeerDisconnected].
 *
 * Also subscribes to [PresenceTracker.peerEvents] to emit diagnostic events on state transitions:
 * - LOG on Connected→Disconnected transition
 * - LOG on Disconnected→Connected reconnect
 *
 * Emits 4 diagnostic events per S02 pattern:
 * - LOG on sweep tick (peer count, disconnected count)
 * - LOG on Connected→Disconnected transition
 * - THRESHOLD on Gone eviction (peerId)
 * - LOG on Disconnected→Connected reconnect
 */
internal class MeshStateManager(
    private val presenceTracker: PresenceTracker,
    private val routeCoordinator: RouteCoordinator,
    private val powerManager: PowerManager,
    private val deliveryPipeline: ch.trancee.meshlink.messaging.DeliveryPipeline? = null,
    private val diagnosticSink: DiagnosticSinkApi = NoOpDiagnosticSink,
    private val sweepIntervalMillis: Long = 30_000L,
) {
    private var sweepJob: Job? = null
    private var peerEventsJob: Job? = null

    /**
     * Start the periodic sweep timer and peer-event diagnostic subscription in the given [scope].
     *
     * Each sweep tick calls [PresenceTracker.sweep] and for every newly-Gone peer runs
     * cross-subsystem cleanup: route retraction + neighbor removal + power release.
     *
     * The peer-events subscription emits LOG diagnostics on Connected→Disconnected and
     * Disconnected→Connected transitions.
     */
    fun start(scope: CoroutineScope) {
        // ── Sweep timer ───────────────────────────────────────────────────────
        sweepJob = scope.launch {
            while (true) {
                delay(sweepIntervalMillis)

                // Snapshot peer counts before sweep for diagnostics.
                val allStates = presenceTracker.allPeerStates()
                val connectedCount =
                    allStates.values.count { it.state == InternalPeerState.CONNECTED }
                val disconnectedCount =
                    allStates.values.count { it.state == InternalPeerState.DISCONNECTED }

                // Emit LOG diagnostic on each sweep tick.
                Logger.d(
                    "MeshStateManager",
                    "sweep tick: connected=$connectedCount disconnected=$disconnectedCount",
                )
                diagnosticSink.emit(DiagnosticCode.ROUTE_CHANGED) {
                    DiagnosticPayload.RouteChanged(
                        destination =
                            PeerIdHex(
                                "sweep:peers=$connectedCount,disconnected=$disconnectedCount"
                            ),
                        cost = 0.0,
                    )
                }

                // Run the sweep — returns newly-Gone peer IDs.
                val gonePeers = presenceTracker.sweep()

                // Cross-subsystem cleanup for each Gone peer.
                for (peerId in gonePeers) {
                    val peerIdHex = peerId.toPeerIdHex()
                    Logger.d("MeshStateManager", "peer gone: $peerIdHex — cleaning up")

                    // Route retraction + neighbor removal.
                    routeCoordinator.onPeerDisconnected(peerId)

                    // Remove per-destination seqNo counter (D044).
                    routeCoordinator.removeSeqNo(peerId)

                    // Cancel pending messages for the gone peer.
                    deliveryPipeline?.cancelMessagesFor(peerId)

                    // Release the power connection slot.
                    powerManager.releaseConnection(peerId)

                    // Emit THRESHOLD diagnostic on Gone eviction.
                    diagnosticSink.emit(DiagnosticCode.PEER_PRESENCE_EVICTED) {
                        DiagnosticPayload.PeerPresenceEvicted(peerId = peerIdHex)
                    }
                }
            }
        }

        // ── Peer-event diagnostics ────────────────────────────────────────────
        peerEventsJob = scope.launch {
            presenceTracker.peerEvents.collect { event ->
                when (event) {
                    is PeerEvent.Disconnected -> {
                        val peerIdHex = event.peerId.toPeerIdHex()
                        Logger.d("MeshStateManager", "peer disconnected: $peerIdHex")
                        diagnosticSink.emit(DiagnosticCode.ROUTE_CHANGED) {
                            DiagnosticPayload.RouteChanged(
                                destination = PeerIdHex("transition:disconnected:${peerIdHex.hex}"),
                                cost = -1.0,
                            )
                        }
                    }
                    is PeerEvent.Connected -> {
                        val peerIdHex = event.peerId.toPeerIdHex()
                        Logger.d("MeshStateManager", "peer reconnected: $peerIdHex")
                        diagnosticSink.emit(DiagnosticCode.ROUTE_CHANGED) {
                            DiagnosticPayload.RouteChanged(
                                destination = PeerIdHex("transition:connected:${peerIdHex.hex}"),
                                cost = 0.0,
                            )
                        }
                    }
                    is PeerEvent.Gone -> {
                        // Gone cleanup is handled in the sweep loop above.
                    }
                }
            }
        }
    }

    /** Cancel the sweep timer and peer-event subscription. Safe to call multiple times. */
    fun stop() {
        sweepJob?.cancel()
        sweepJob = null
        peerEventsJob?.cancel()
        peerEventsJob = null
    }
}
