package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DiagnosticCode
import ch.trancee.meshlink.api.DiagnosticPayload
import ch.trancee.meshlink.api.DiagnosticSinkApi
import ch.trancee.meshlink.api.NoOpDiagnosticSink
import ch.trancee.meshlink.api.toPeerIdHex
import ch.trancee.meshlink.power.PowerManager
import ch.trancee.meshlink.routing.InternalPeerState
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
 * Emits diagnostic events per S02 pattern:
 * - LOG on sweep tick (peer count, disconnected count)
 * - THRESHOLD on Gone eviction (peerId)
 */
internal class MeshStateManager(
    private val presenceTracker: PresenceTracker,
    private val routeCoordinator: RouteCoordinator,
    private val powerManager: PowerManager,
    private val diagnosticSink: DiagnosticSinkApi = NoOpDiagnosticSink,
    private val sweepIntervalMillis: Long = 30_000L,
) {
    private var sweepJob: Job? = null

    /**
     * Start the periodic sweep timer in the given [scope].
     *
     * Each tick calls [PresenceTracker.sweep] and for every newly-Gone peer runs cross-subsystem
     * cleanup: route retraction + neighbor removal + power release.
     */
    fun start(scope: CoroutineScope) {
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
                        destination = "sweep:peers=$connectedCount,disconnected=$disconnectedCount",
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

                    // Release the power connection slot.
                    powerManager.releaseConnection(peerId)

                    // Emit THRESHOLD diagnostic on Gone eviction.
                    diagnosticSink.emit(DiagnosticCode.PEER_PRESENCE_EVICTED) {
                        DiagnosticPayload.PeerPresenceEvicted(peerId = peerIdHex)
                    }
                }
            }
        }
    }

    /** Cancel the sweep timer. Safe to call multiple times. */
    fun stop() {
        sweepJob?.cancel()
        sweepJob = null
    }
}
