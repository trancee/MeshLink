package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerPolicyController
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.RelayTransferSession
import ch.trancee.meshlink.wire.TransferAbortReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeshEngineLifecycleSupportTest {
    @Test
    fun `start from uninitialized begins a new hard run and emits the started diagnostic`() =
        runBlocking {
            // Arrange
            val harness = lifecycleHarness(meshState = MeshLinkState.Uninitialized)
            val support = harness.support

            // Act
            val result = support.start()

            // Assert
            assertEquals(StartResult.Started, result)
            assertEquals(MeshLinkState.Running, harness.runtimeSurface.state.value)
            assertEquals(1L, harness.runtimeSurface.runtimeGate.currentHardRunEpoch())
            assertEquals(1, harness.callbacks.ensureTransportCollectorCalls)
            assertEquals(1, harness.callbacks.startTransportCalls)
            assertEquals(1, harness.callbacks.updatedTransportPolicies.size)
            assertEquals(
                harness.lifecycleState.currentPowerPolicy.tier,
                harness.callbacks.updatedTransportPolicies.single().tier,
            )
            assertEquals(
                listOf(DiagnosticCode.MESH_STARTED to "lifecycle.start"),
                harness.diagnostics.lifecycleEvents,
            )
        }

    @Test
    fun `start from paused returns invalid state without side effects`() = runBlocking {
        // Arrange
        val harness = lifecycleHarness(meshState = MeshLinkState.Paused)
        val support = harness.support

        // Act
        val result = support.start()

        // Assert
        val invalidState = result as StartResult.InvalidState
        assertEquals(MeshLinkState.Paused, invalidState.currentState)
        assertEquals(0, harness.callbacks.ensureTransportCollectorCalls)
        assertTrue(harness.diagnostics.lifecycleEvents.isEmpty())
    }

    @Test
    fun `pause from running suspends collection clears the volatile view and emits the paused diagnostic`() =
        runBlocking {
            // Arrange
            val harness = lifecycleHarness(meshState = MeshLinkState.Running)
            val support = harness.support

            // Act
            val result = support.pause()

            // Assert
            assertEquals(PauseResult.Paused, result)
            assertEquals(MeshLinkState.Paused, harness.runtimeSurface.state.value)
            assertEquals(1, harness.callbacks.pauseTransportCalls)
            assertEquals(1, harness.callbacks.stopTransportCollectorCalls)
            assertEquals(
                listOf(
                    RuntimeViewClear(
                        stage = "lifecycle.pause",
                        removalCode = DiagnosticCode.ROUTE_RETRACTED,
                        metadata = mapOf("runtimeBoundary" to "pause"),
                    )
                ),
                harness.callbacks.clearedRuntimeViews,
            )
            assertEquals(
                listOf(DiagnosticCode.MESH_PAUSED to "lifecycle.pause"),
                harness.diagnostics.lifecycleEvents,
            )
        }

    @Test
    fun `pause from stopped returns invalid state`() = runBlocking {
        // Arrange
        val harness = lifecycleHarness(meshState = MeshLinkState.Stopped)
        val support = harness.support

        // Act
        val result = support.pause()

        // Assert
        val invalidState = result as PauseResult.InvalidState
        assertEquals(MeshLinkState.Stopped, invalidState.currentState)
        assertEquals(0, harness.callbacks.pauseTransportCalls)
        assertTrue(harness.callbacks.clearedRuntimeViews.isEmpty())
    }

    @Test
    fun `resume from paused restarts collection and emits the resumed diagnostic`() = runBlocking {
        // Arrange
        val harness = lifecycleHarness(meshState = MeshLinkState.Paused)
        val support = harness.support

        // Act
        val result = support.resume()

        // Assert
        assertEquals(ResumeResult.Resumed, result)
        assertEquals(MeshLinkState.Running, harness.runtimeSurface.state.value)
        assertEquals(1, harness.callbacks.ensureTransportCollectorCalls)
        assertEquals(1, harness.callbacks.resumeTransportCalls)
        assertEquals(1, harness.callbacks.updatedTransportPolicies.size)
        assertEquals(
            listOf(DiagnosticCode.MESH_RESUMED to "lifecycle.resume"),
            harness.diagnostics.lifecycleEvents,
        )
    }

    @Test
    fun `stop from uninitialized commits locally without transport teardown`() = runBlocking {
        // Arrange
        val harness = lifecycleHarness(meshState = MeshLinkState.Uninitialized)
        val support = harness.support

        // Act
        val result = support.stop()

        // Assert
        assertEquals(StopResult.Stopped, result)
        assertEquals(MeshLinkState.Stopped, harness.runtimeSurface.state.value)
        assertEquals(0, harness.callbacks.stopTransportCalls)
        assertEquals(0, harness.callbacks.stopTransportCollectorCalls)
        assertTrue(harness.callbacks.clearedRuntimeViews.isEmpty())
        assertTrue(harness.callbacks.abortedTransferReasons.isEmpty())
    }

    @Test
    fun `stop from running aborts transfers clears the volatile view and emits the stopped diagnostic`() =
        runBlocking {
            // Arrange
            val harness = lifecycleHarness(meshState = MeshLinkState.Running)
            val support = harness.support

            // Act
            val result = support.stop()

            // Assert
            assertEquals(StopResult.Stopped, result)
            assertEquals(MeshLinkState.Stopped, harness.runtimeSurface.state.value)
            assertEquals(
                listOf(TransferAbortReasonCode.RUNTIME_STOPPED),
                harness.callbacks.abortedTransferReasons,
            )
            assertEquals(1, harness.callbacks.stopTransportCalls)
            assertEquals(1, harness.callbacks.stopTransportCollectorCalls)
            assertEquals(
                listOf(
                    RuntimeViewClear(
                        stage = "lifecycle.stop",
                        removalCode = DiagnosticCode.ROUTE_RETRACTED,
                        metadata = mapOf("runtimeBoundary" to "stop"),
                    )
                ),
                harness.callbacks.clearedRuntimeViews,
            )
            assertEquals(
                listOf(DiagnosticCode.MESH_STOPPED to "lifecycle.stop"),
                harness.diagnostics.lifecycleEvents,
            )
        }

    @Test
    fun `updateBattery stores the new power policy and emits a power-mode diagnostic`() {
        // Arrange
        val harness = lifecycleHarness(meshState = MeshLinkState.Running)
        val support = harness.support

        // Act
        support.updateBattery(level = 1.5f, isCharging = false)

        // Assert
        assertEquals(1, harness.callbacks.launchedTransportPowerPolicies.size)
        assertEquals(
            harness.lifecycleState.currentPowerPolicy.tier,
            harness.callbacks.launchedTransportPowerPolicies.single().tier,
        )
        assertEquals(1.0f, harness.diagnostics.powerModeLevels.single())
        assertFalse(harness.diagnostics.powerModeCharging.single())
        assertEquals(1, harness.diagnostics.powerModePolicies.size)
    }
}

private data class LifecycleHarness(
    val runtimeSurface: MeshEngineRuntimeSurface,
    val lifecycleState: MeshEngineLifecycleState,
    val callbacks: RecordingLifecycleCallbacks,
    val diagnostics: RecordingLifecycleDiagnostics,
    val support: MeshEngineLifecycleSupport,
)

private fun lifecycleHarness(meshState: MeshLinkState): LifecycleHarness {
    val runtimeSurface = MeshEngineRuntimeSurface()
    when (meshState) {
        MeshLinkState.Uninitialized -> Unit
        MeshLinkState.Running -> runtimeSurface.beginHardRun()
        MeshLinkState.Paused -> {
            runtimeSurface.beginHardRun()
            runtimeSurface.setLifecycleState(MeshLinkState.Paused)
        }
        MeshLinkState.Stopped -> {
            runtimeSurface.beginHardRun()
            runtimeSurface.setLifecycleState(MeshLinkState.Stopped)
        }
    }
    val powerPolicyController =
        PowerPolicyController(
            configuredMode = PowerMode.Automatic,
            region = RegulatoryRegion.DEFAULT,
        )
    val lifecycleState =
        MeshEngineLifecycleState(
            runtimeSurface = runtimeSurface,
            outboundTransfers = linkedMapOf<String, OutboundTransferSession>(),
            inboundTransfers = linkedMapOf<String, InboundTransferSession>(),
            relayTransfers = linkedMapOf<String, RelayTransferSession>(),
            currentPowerPolicy = powerPolicyController.currentPolicy(nowMillis = 0L),
        )
    val callbacks = RecordingLifecycleCallbacks()
    val diagnostics = RecordingLifecycleDiagnostics()
    val support =
        MeshEngineLifecycleSupport(
            powerPolicyController =
                PowerPolicyController(
                    configuredMode = PowerMode.Automatic,
                    region = RegulatoryRegion.DEFAULT,
                ),
            powerPolicyNowMillis = { 1_000L },
            state = lifecycleState,
            callbacks = callbacks.asCallbacks(),
            diagnostics = diagnostics.asDiagnostics(),
        )
    return LifecycleHarness(runtimeSurface, lifecycleState, callbacks, diagnostics, support)
}

private data class RuntimeViewClear(
    val stage: String,
    val removalCode: DiagnosticCode,
    val metadata: Map<String, String>,
)

private class RecordingLifecycleCallbacks {
    var ensureTransportCollectorCalls: Int = 0
    var startTransportCalls: Int = 0
    var pauseTransportCalls: Int = 0
    var resumeTransportCalls: Int = 0
    var stopTransportCalls: Int = 0
    var stopTransportCollectorCalls: Int = 0
    val updatedTransportPolicies: MutableList<PowerPolicy> = mutableListOf()
    val launchedTransportPowerPolicies: MutableList<PowerPolicy> = mutableListOf()
    val clearedRuntimeViews: MutableList<RuntimeViewClear> = mutableListOf()
    val abortedTransferReasons: MutableList<TransferAbortReasonCode> = mutableListOf()

    fun asCallbacks(): MeshEngineLifecycleCallbacks {
        return MeshEngineLifecycleCallbacks(
            ensureTransportCollector = { ensureTransportCollectorCalls += 1 },
            stopTransportCollector = { stopTransportCollectorCalls += 1 },
            updateTransportPowerPolicy = { policy -> updatedTransportPolicies += policy },
            startTransport = { startTransportCalls += 1 },
            pauseTransport = { pauseTransportCalls += 1 },
            resumeTransport = { resumeTransportCalls += 1 },
            stopTransport = { stopTransportCalls += 1 },
            launchTransportPowerPolicyUpdate = { policy ->
                launchedTransportPowerPolicies += policy
            },
            clearVolatileRuntimeView = { stage, removalCode, metadata ->
                clearedRuntimeViews += RuntimeViewClear(stage, removalCode, metadata)
            },
            abortCommittedTransfers = { reasonCode -> abortedTransferReasons += reasonCode },
        )
    }
}

private class RecordingLifecycleDiagnostics {
    val lifecycleEvents: MutableList<Pair<DiagnosticCode, String>> = mutableListOf()
    val powerModePolicies: MutableList<PowerPolicy> = mutableListOf()
    val powerModeLevels: MutableList<Float> = mutableListOf()
    val powerModeCharging: MutableList<Boolean> = mutableListOf()

    fun asDiagnostics(): MeshEngineLifecycleDiagnostics {
        return MeshEngineLifecycleDiagnostics(
            emitLifecycleEvent = { code, stage -> lifecycleEvents += code to stage },
            emitPowerModeChanged = { policy, level, isCharging ->
                powerModePolicies += policy
                powerModeLevels += level
                powerModeCharging += isCharging
            },
        )
    }
}
