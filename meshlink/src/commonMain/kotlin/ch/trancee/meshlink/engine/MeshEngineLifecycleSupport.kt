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
import ch.trancee.meshlink.transfer.RelayTransferSession
import ch.trancee.meshlink.wire.TransferAbortReasonCode

internal data class MeshEngineLifecycleState(
    val runtimeSurface: MeshEngineCompatibilityRuntimeSurface,
    val inboundTransfers: MutableMap<String, InboundTransferSession>,
    val relayTransfers: MutableMap<String, RelayTransferSession>,
    var currentPowerPolicy: PowerPolicy,
)

internal data class MeshEngineLifecycleCallbacks(
    val ensureTransportCollector: () -> Unit,
    val stopTransportCollector: suspend () -> Unit,
    val updateTransportPowerPolicy: suspend (PowerPolicy) -> Unit,
    val startTransport: suspend () -> Unit,
    val pauseTransport: suspend () -> Unit,
    val resumeTransport: suspend () -> Unit,
    val stopTransport: suspend () -> Unit,
    val launchTransportPowerPolicyUpdate: (PowerPolicy) -> Unit,
    val clearVolatileRuntimeView: suspend (String, DiagnosticCode, Map<String, String>) -> Unit,
    val abortCommittedTransfers: suspend (TransferAbortReasonCode) -> Unit,
    val clearOutboundTransfers: () -> Unit,
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
        return when (val currentState = state.runtimeSurface.currentState()) {
            MeshLinkState.Running -> StartResult.AlreadyRunning
            MeshLinkState.Paused -> StartResult.InvalidState(currentState)
            MeshLinkState.Uninitialized,
            MeshLinkState.Stopped -> {
                callbacks.ensureTransportCollector()
                try {
                    state.currentPowerPolicy =
                        powerPolicyController.onMeshStarted(powerPolicyNowMillis())
                    callbacks.updateTransportPowerPolicy(state.currentPowerPolicy)
                    callbacks.startTransport()
                    state.runtimeSurface.beginHardRun()
                } catch (exception: Throwable) {
                    callbacks.stopTransportCollector()
                    throw exception
                }
                diagnostics.emitLifecycleEvent(DiagnosticCode.MESH_STARTED, "lifecycle.start")
                StartResult.Started
            }
        }
    }

    internal suspend fun pause(): PauseResult {
        return when (val currentState = state.runtimeSurface.currentState()) {
            MeshLinkState.Paused -> PauseResult.AlreadyPaused
            MeshLinkState.Running -> {
                callbacks.pauseTransport()
                callbacks.stopTransportCollector()
                state.runtimeSurface.setLifecycleState(MeshLinkState.Paused)
                callbacks.clearVolatileRuntimeView(
                    "lifecycle.pause",
                    DiagnosticCode.ROUTE_RETRACTED,
                    mapOf("runtimeBoundary" to "pause"),
                )
                diagnostics.emitLifecycleEvent(DiagnosticCode.MESH_PAUSED, "lifecycle.pause")
                PauseResult.Paused
            }
            MeshLinkState.Uninitialized,
            MeshLinkState.Stopped -> PauseResult.InvalidState(currentState)
        }
    }

    internal suspend fun resume(): ResumeResult {
        return when (val currentState = state.runtimeSurface.currentState()) {
            MeshLinkState.Running -> ResumeResult.AlreadyRunning
            MeshLinkState.Paused -> {
                callbacks.ensureTransportCollector()
                try {
                    state.currentPowerPolicy =
                        powerPolicyController.currentPolicy(powerPolicyNowMillis())
                    callbacks.updateTransportPowerPolicy(state.currentPowerPolicy)
                    callbacks.resumeTransport()
                } catch (exception: Throwable) {
                    callbacks.stopTransportCollector()
                    throw exception
                }
                state.runtimeSurface.setLifecycleState(MeshLinkState.Running)
                diagnostics.emitLifecycleEvent(DiagnosticCode.MESH_RESUMED, "lifecycle.resume")
                ResumeResult.Resumed
            }
            MeshLinkState.Uninitialized,
            MeshLinkState.Stopped -> ResumeResult.InvalidState(currentState)
        }
    }

    internal suspend fun stop(): StopResult {
        return when (state.runtimeSurface.currentState()) {
            MeshLinkState.Stopped -> StopResult.AlreadyStopped
            MeshLinkState.Uninitialized -> {
                callbacks.clearOutboundTransfers()
                state.inboundTransfers.clear()
                state.relayTransfers.clear()
                state.runtimeSurface.setLifecycleState(MeshLinkState.Stopped)
                diagnostics.emitLifecycleEvent(DiagnosticCode.MESH_STOPPED, "lifecycle.stop")
                StopResult.Stopped
            }
            MeshLinkState.Running,
            MeshLinkState.Paused -> {
                callbacks.abortCommittedTransfers(TransferAbortReasonCode.RUNTIME_STOPPED)
                callbacks.stopTransport()
                callbacks.stopTransportCollector()
                callbacks.clearVolatileRuntimeView(
                    "lifecycle.stop",
                    DiagnosticCode.ROUTE_RETRACTED,
                    mapOf("runtimeBoundary" to "stop"),
                )
                callbacks.clearOutboundTransfers()
                state.inboundTransfers.clear()
                state.relayTransfers.clear()
                state.runtimeSurface.setLifecycleState(MeshLinkState.Stopped)
                diagnostics.emitLifecycleEvent(DiagnosticCode.MESH_STOPPED, "lifecycle.stop")
                StopResult.Stopped
            }
        }
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
