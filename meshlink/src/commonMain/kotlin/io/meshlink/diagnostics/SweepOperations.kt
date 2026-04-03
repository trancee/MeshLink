package io.meshlink.diagnostics

import io.meshlink.config.MeshLinkConfig
import io.meshlink.delivery.DeliveryPipeline
import io.meshlink.dispatch.OutboundTracker
import io.meshlink.model.MessageId
import io.meshlink.model.TransferFailure
import io.meshlink.power.PowerCoordinator
import io.meshlink.power.ShedAction
import io.meshlink.routing.RoutingEngine
import io.meshlink.transfer.TransferEngine
import io.meshlink.util.ByteArrayKey
import io.meshlink.util.DeliveryOutcome
import io.meshlink.util.PauseManager
import io.meshlink.util.hexToBytes
import kotlinx.coroutines.flow.MutableSharedFlow

private const val NEXTHOP_UNRELIABLE_THRESHOLD = 0.5
private const val NEXTHOP_MIN_SAMPLES = 3

/**
 * Sweep and memory-pressure operations, extracted from MeshLink for clarity.
 */
internal class SweepOperations(
    private val routingEngine: RoutingEngine,
    private val transferEngine: TransferEngine,
    private val outboundTracker: OutboundTracker,
    private val deliveryPipeline: DeliveryPipeline,
    private val diagnosticSink: DiagnosticSink,
    private val powerCoordinator: PowerCoordinator,
    private val pauseManager: PauseManager,
    private val config: MeshLinkConfig,
    private val transferFailures: MutableSharedFlow<TransferFailure>,
) {

    fun sweep(seenPeers: Set<String>): Set<String> {
        val seenKeys = seenPeers.map { ByteArrayKey(hexToBytes(it)) }.toSet()
        val presenceEvicted = routingEngine.sweepPresence(seenKeys)
        for (peerId in presenceEvicted) {
            diagnosticSink.emit(DiagnosticCode.PEER_PRESENCE_EVICTED, Severity.INFO, "peerId=$peerId")
        }
        return presenceEvicted.map { it.toString() }.toSet()
    }

    fun sweepStaleTransfers(maxAgeMillis: Long): Int {
        val staleKeys = transferEngine.sweepStaleOutbound(maxAgeMillis)
        for (key in staleKeys) {
            outboundTracker.removeRecipient(key)
            outboundTracker.removeNextHop(key)?.let { nextHop ->
                routingEngine.recordNextHopFailure(nextHop)
                if (routingEngine.nextHopFailureRate(nextHop) > NEXTHOP_UNRELIABLE_THRESHOLD &&
                    routingEngine.nextHopFailureCount(nextHop) >= NEXTHOP_MIN_SAMPLES
                ) {
                    diagnosticSink.emit(
                        DiagnosticCode.NEXTHOP_UNRELIABLE,
                        Severity.WARN,
                        "nextHop=$nextHop, failureRate=${((routingEngine.nextHopFailureRate(nextHop) * 100).toInt())}%"
                    )
                }
            }
            if (deliveryPipeline.recordFailure(key, DeliveryOutcome.FAILED_ACK_TIMEOUT)) {
                transferFailures.tryEmit(
                    TransferFailure(MessageId.fromBytes(key.bytes), DeliveryOutcome.FAILED_ACK_TIMEOUT)
                )
            }
        }
        return staleKeys.size
    }

    fun sweepStaleReassemblies(maxAgeMillis: Long): Int {
        val staleKeys = transferEngine.sweepStaleInbound(maxAgeMillis)
        return staleKeys.size
    }

    fun sweepExpiredPendingMessages(): Int {
        val expired = deliveryPipeline.sweepExpiredPending(config.pendingMessageTtlMillis)
        repeat(expired) {
            transferFailures.tryEmit(
                TransferFailure(MessageId.random(), DeliveryOutcome.FAILED_DELIVERY_TIMEOUT)
            )
        }
        return expired
    }

    fun shedMemoryPressure(): List<String> {
        val health = meshHealthSnapshot()
        val level = powerCoordinator.evaluatePressure(health.bufferUtilizationPercent)
            ?: return emptyList()
        val results = powerCoordinator.computeShedActions(
            level,
            transferEngine.inboundCount,
            routingEngine.dedupSize,
            routingEngine.peerCount,
        )
        val actions = mutableListOf<String>()
        for (result in results) {
            when (result.action) {
                ShedAction.RELAY_BUFFERS_CLEARED -> {
                    transferEngine.clearAll()
                    val relayCount = pauseManager.relayQueueSize
                    pauseManager.clear()
                    actions.add("Cleared ${result.count} relay buffers, $relayCount queued relays")
                }
                ShedAction.DEDUP_TRIMMED -> {
                    routingEngine.clearDedup()
                    actions.add("Trimmed ${result.count} dedup entries")
                }
                ShedAction.CONNECTIONS_DROPPED -> {
                    actions.add("Would drop ${result.count} connections")
                }
            }
        }
        if (actions.isNotEmpty()) {
            diagnosticSink.emit(DiagnosticCode.MEMORY_PRESSURE, Severity.WARN, "level=$level, actions=$actions")
        }
        return actions
    }

    private fun meshHealthSnapshot(): MeshHealthSnapshot {
        val outboundBytes = transferEngine.outboundBufferBytes()
        val inboundBytes = transferEngine.inboundBufferBytes()
        val usedBytes = outboundBytes + inboundBytes
        val utilPercent = if (config.bufferCapacity > 0) {
            (usedBytes * 100 / config.bufferCapacity).coerceIn(0, 100)
        } else {
            0
        }
        return MeshHealthSnapshot(
            connectedPeers = routingEngine.peerCount,
            reachablePeers = routingEngine.connectedPeerCount,
            bufferUtilizationPercent = utilPercent,
            activeTransfers = transferEngine.outboundCount,
            powerMode = powerCoordinator.currentMode,
            avgRouteCost = routingEngine.avgCost(),
        )
    }
}
