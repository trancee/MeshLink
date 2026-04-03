package io.meshlink.diagnostics

import io.meshlink.config.MeshLinkConfig
import io.meshlink.power.PowerCoordinator
import io.meshlink.routing.RoutingEngine
import io.meshlink.transfer.TransferEngine
import io.meshlink.util.PauseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Produces and throttles [MeshHealthSnapshot] emissions, extracted from MeshLink.
 */
internal class MeshHealthReporter(
    private val transferEngine: TransferEngine,
    private val routingEngine: RoutingEngine,
    private val powerCoordinator: PowerCoordinator,
    private val pauseManager: PauseManager,
    private val config: MeshLinkConfig,
    private val diagnosticSink: DiagnosticSink,
    private val clock: () -> Long,
    private val healthThrottleMillis: Long = 500L,
) {

    private val _meshHealthFlow = MutableStateFlow(
        MeshHealthSnapshot(0, 0, 0, 0, powerCoordinator.currentMode, 0.0)
    )
    val meshHealthFlow: StateFlow<MeshHealthSnapshot> = _meshHealthFlow.asStateFlow()

    private var lastHealthUpdateMillis: Long = 0L

    fun emitHealthUpdate() {
        val now = clock()
        if (now - lastHealthUpdateMillis < healthThrottleMillis) return
        lastHealthUpdateMillis = now
        _meshHealthFlow.value = snapshot()
    }

    fun snapshot(): MeshHealthSnapshot {
        val outboundBytes = transferEngine.outboundBufferBytes()
        val inboundBytes = transferEngine.inboundBufferBytes()
        val usedBytes = outboundBytes + inboundBytes
        val utilPercent = if (config.bufferCapacity > 0) {
            (usedBytes * 100 / config.bufferCapacity).coerceIn(0, 100)
        } else {
            0
        }
        val cap = config.bufferCapacity.toFloat().coerceAtLeast(1f)
        return MeshHealthSnapshot(
            connectedPeers = routingEngine.peerCount,
            reachablePeers = routingEngine.connectedPeerCount,
            bufferUtilizationPercent = utilPercent,
            activeTransfers = transferEngine.outboundCount,
            powerMode = powerCoordinator.currentMode,
            avgRouteCost = routingEngine.avgCost(),
            relayBufferUtilization = (inboundBytes.toFloat() / cap).coerceIn(0f, 1f),
            ownBufferUtilization = (outboundBytes.toFloat() / cap).coerceIn(0f, 1f),
            relayQueueSize = pauseManager.relayQueueSize,
        )
    }

    fun checkBufferPressure() {
        val usedBytes = (transferEngine.outboundBufferBytes() + transferEngine.inboundBufferBytes()).toLong()
        if (powerCoordinator.shouldWarnBufferPressure(usedBytes, config.bufferCapacity.toLong())) {
            val utilPercent = usedBytes * 100 / config.bufferCapacity
            diagnosticSink.emit(
                DiagnosticCode.BUFFER_PRESSURE,
                Severity.WARN,
                "utilization=$utilPercent%, used=$usedBytes, capacity=${config.bufferCapacity}"
            )
        }
    }

    fun resetThrottle() {
        lastHealthUpdateMillis = 0L
    }
}
