package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerPolicyController
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.RelayTransferSession
import kotlinx.coroutines.flow.MutableStateFlow

internal data class MeshEngineLifecycleState(
    val mutableState: MutableStateFlow<MeshLinkState>,
    val sessionRegistry: MeshEngineSessionRegistry,
    val outboundTransfers: MutableMap<String, OutboundTransferSession>,
    val inboundTransfers: MutableMap<String, InboundTransferSession>,
    val relayTransfers: MutableMap<String, RelayTransferSession>,
    var currentPowerPolicy: PowerPolicy,
)

internal data class MeshEngineLifecycleCallbacks(
    val ensureTransportCollector: () -> Unit,
    val stopTransportCollector: () -> Unit,
    val updateTransportPowerPolicy: suspend (PowerPolicy) -> Unit,
    val startTransport: suspend () -> Unit,
    val pauseTransport: suspend () -> Unit,
    val resumeTransport: suspend () -> Unit,
    val stopTransport: suspend () -> Unit,
    val launchTransportPowerPolicyUpdate: (PowerPolicy) -> Unit,
)

internal data class MeshEngineLifecycleDiagnostics(
    val emitLifecycleEvent: (DiagnosticCode, String) -> Unit,
    val emitPowerModeChanged: (PowerPolicy, Float, Boolean) -> Unit,
)

internal class MeshEngineLifecycleSupport(
    private val powerPolicyController: PowerPolicyController,
    private val powerPolicyNowMillis: () -> Long,
    private val state: MeshEngineLifecycleState,
    private val callbacks: MeshEngineLifecycleCallbacks,
    private val diagnostics: MeshEngineLifecycleDiagnostics,
) {
    internal suspend fun start(): StartResult {
        if (state.mutableState.value === MeshLinkState.Running) {
            return StartResult.AlreadyRunning
        }
        callbacks.ensureTransportCollector()
        state.currentPowerPolicy = powerPolicyController.onMeshStarted(powerPolicyNowMillis())
        callbacks.updateTransportPowerPolicy(state.currentPowerPolicy)
        callbacks.startTransport()
        state.mutableState.value = MeshLinkState.Running
        diagnostics.emitLifecycleEvent(DiagnosticCode.MESH_STARTED, "lifecycle.start")
        return StartResult.Started
    }

    internal suspend fun pause(): PauseResult {
        if (state.mutableState.value === MeshLinkState.Paused) {
            return PauseResult.AlreadyPaused
        }
        callbacks.pauseTransport()
        state.mutableState.value = MeshLinkState.Paused
        diagnostics.emitLifecycleEvent(DiagnosticCode.MESH_PAUSED, "lifecycle.pause")
        return PauseResult.Paused
    }

    internal suspend fun resume(): ResumeResult {
        if (state.mutableState.value === MeshLinkState.Running) {
            return ResumeResult.AlreadyRunning
        }
        callbacks.ensureTransportCollector()
        state.currentPowerPolicy = powerPolicyController.currentPolicy(powerPolicyNowMillis())
        callbacks.updateTransportPowerPolicy(state.currentPowerPolicy)
        callbacks.resumeTransport()
        state.mutableState.value = MeshLinkState.Running
        diagnostics.emitLifecycleEvent(DiagnosticCode.MESH_RESUMED, "lifecycle.resume")
        return ResumeResult.Resumed
    }

    internal suspend fun stop(): StopResult {
        if (state.mutableState.value === MeshLinkState.Stopped) {
            return StopResult.AlreadyStopped
        }
        callbacks.stopTransport()
        callbacks.stopTransportCollector()
        state.sessionRegistry.clear().forEach { pendingHandshake ->
            pendingHandshake.sessionDeferred.complete(SessionEstablishmentOutcome.Unreachable)
        }
        state.outboundTransfers.clear()
        state.inboundTransfers.clear()
        state.relayTransfers.clear()
        state.mutableState.value = MeshLinkState.Stopped
        diagnostics.emitLifecycleEvent(DiagnosticCode.MESH_STOPPED, "lifecycle.stop")
        return StopResult.Stopped
    }

    internal fun updateBattery(level: Float, isCharging: Boolean): Unit {
        val clampedLevel = level.coerceIn(0f, 1f)
        val policy =
            powerPolicyController.onBatterySnapshot(
                level = clampedLevel,
                isCharging = isCharging,
                nowMillis = powerPolicyNowMillis(),
            )
        state.currentPowerPolicy = policy
        callbacks.launchTransportPowerPolicyUpdate(policy)
        diagnostics.emitPowerModeChanged(policy, clampedLevel, isCharging)
    }
}
