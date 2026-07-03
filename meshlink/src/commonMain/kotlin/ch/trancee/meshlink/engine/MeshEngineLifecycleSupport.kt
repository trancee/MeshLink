package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.wire.TransferAbortReasonCode

internal data class MeshEngineLifecycleState(
    val runtimeSurface: MeshEngineCompatibilityRuntimeSurface,
    val transferRegistry: MeshEngineTransferRegistry,
) {
    constructor(
        runtimeSurface: MeshEngineCompatibilityRuntimeSurface,
        inboundTransfers: MutableMap<String, ch.trancee.meshlink.transfer.InboundTransferSession>,
        relayTransfers: MutableMap<String, ch.trancee.meshlink.transfer.RelayTransferSession>,
    ) : this(
        runtimeSurface = runtimeSurface,
        transferRegistry =
            MeshEngineTransferRegistry(
                inboundTransfers = inboundTransfers,
                relayTransfers = relayTransfers,
            ),
    )
}

internal data class MeshEngineLifecycleCallbacks(
    val ensureTransportCollector: () -> Unit,
    val stopTransportCollector: suspend () -> Unit,
    val startTransport: suspend () -> Unit,
    val pauseTransport: suspend () -> Unit,
    val resumeTransport: suspend () -> Unit,
    val stopTransport: suspend () -> Unit,
    val clearVolatileRuntimeView: suspend (String, DiagnosticCode, Map<String, String>) -> Unit,
    val abortCommittedTransfers: suspend (TransferAbortReasonCode) -> Unit,
    val clearOutboundTransfers: suspend () -> Unit,
)

internal data class MeshEngineLifecycleDiagnostics(
    val emitLifecycleEvent: (DiagnosticCode, String) -> Unit
)

internal class MeshEngineLifecycleSupport(
    private val state: MeshEngineLifecycleState,
    private val callbacks: MeshEngineLifecycleCallbacks,
    private val diagnostics: MeshEngineLifecycleDiagnostics,
    private val powerPolicySupport: MeshEnginePowerPolicySupport,
) {
    internal suspend fun start(): StartResult {
        return when (val currentState = state.runtimeSurface.currentState()) {
            MeshLinkState.Running -> StartResult.AlreadyRunning
            MeshLinkState.Paused -> StartResult.InvalidState(currentState)
            MeshLinkState.Uninitialized,
            MeshLinkState.Configured,
            MeshLinkState.Stopped -> {
                callbacks.ensureTransportCollector()
                try {
                    powerPolicySupport.onMeshStarted()
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
            MeshLinkState.Configured,
            MeshLinkState.Stopped -> PauseResult.InvalidState(currentState)
        }
    }

    internal suspend fun resume(): ResumeResult {
        return when (val currentState = state.runtimeSurface.currentState()) {
            MeshLinkState.Running -> ResumeResult.AlreadyRunning
            MeshLinkState.Paused -> {
                callbacks.ensureTransportCollector()
                try {
                    powerPolicySupport.refreshCurrentPolicy()
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
            MeshLinkState.Configured,
            MeshLinkState.Stopped -> ResumeResult.InvalidState(currentState)
        }
    }

    internal suspend fun stop(): StopResult {
        return when (state.runtimeSurface.currentState()) {
            MeshLinkState.Stopped -> StopResult.AlreadyStopped
            MeshLinkState.Uninitialized,
            MeshLinkState.Configured -> {
                callbacks.clearOutboundTransfers()
                state.transferRegistry.clearInboundSessions()
                state.transferRegistry.clearRelaySessions()
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
                state.transferRegistry.clearInboundSessions()
                state.transferRegistry.clearRelaySessions()
                state.runtimeSurface.setLifecycleState(MeshLinkState.Stopped)
                diagnostics.emitLifecycleEvent(DiagnosticCode.MESH_STOPPED, "lifecycle.stop")
                StopResult.Stopped
            }
        }
    }
}

internal fun buildMeshEngineRuntimeLifecycleSupport(
    runtimeSurface: MeshEngineCompatibilityRuntimeSurface,
    transferRegistry: MeshEngineTransferRegistry,
    ensureTransportCollector: () -> Unit,
    stopTransportCollector: suspend () -> Unit,
    startTransport: suspend () -> Unit,
    pauseTransport: suspend () -> Unit,
    resumeTransport: suspend () -> Unit,
    stopTransport: suspend () -> Unit,
    clearVolatileRuntimeView: suspend (String, DiagnosticCode, Map<String, String>) -> Unit,
    abortCommittedTransfers: suspend (TransferAbortReasonCode) -> Unit,
    clearOutboundTransfers: suspend () -> Unit,
    emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
    powerPolicySupport: MeshEnginePowerPolicySupport,
): MeshEngineLifecycleSupport {
    val lifecycleState =
        MeshEngineLifecycleState(
            runtimeSurface = runtimeSurface,
            transferRegistry = transferRegistry,
        )
    return MeshEngineLifecycleSupport(
        state = lifecycleState,
        callbacks =
            MeshEngineLifecycleCallbacks(
                ensureTransportCollector = ensureTransportCollector,
                stopTransportCollector = stopTransportCollector,
                startTransport = startTransport,
                pauseTransport = pauseTransport,
                resumeTransport = resumeTransport,
                stopTransport = stopTransport,
                clearVolatileRuntimeView = clearVolatileRuntimeView,
                abortCommittedTransfers = abortCommittedTransfers,
                clearOutboundTransfers = clearOutboundTransfers,
            ),
        diagnostics =
            MeshEngineLifecycleDiagnostics(
                emitLifecycleEvent = { code, stage ->
                    emitDiagnostic(
                        code,
                        DiagnosticSeverity.INFO,
                        stage,
                        null,
                        DiagnosticReason.STATE_CHANGE,
                        emptyMap(),
                    )
                }
            ),
        powerPolicySupport = powerPolicySupport,
    )
}
