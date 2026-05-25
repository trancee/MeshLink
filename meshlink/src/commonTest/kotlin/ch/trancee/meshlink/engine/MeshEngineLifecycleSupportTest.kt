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
import ch.trancee.meshlink.transfer.RelayTransferSession
import ch.trancee.meshlink.wire.TransferAbortReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MeshEngineLifecycleSupportTest {
    @Test
    fun `start from uninitialized begins a new hard run and emits the started diagnostic`() =
        runBlocking {
            // Arrange
            val harness = lifecycleHarness(meshState = MeshLinkState.Uninitialized)
            val support = harness.lifecycleSupport

            // Act
            val result = support.start()

            // Assert
            assertEquals(StartResult.Started, result)
            assertEquals(MeshLinkState.Running, harness.runtimeSurface.state.value)
            assertEquals(1L, harness.runtimeSurface.runtimeGate.currentHardRunEpoch())
            assertEquals(1, harness.lifecycleCallbacks.ensureTransportCollectorCalls)
            assertEquals(1, harness.lifecycleCallbacks.startTransportCalls)
            assertEquals(1, harness.powerCallbacks.updatedTransportPolicies.size)
            assertEquals(
                harness.powerPolicyState.currentPowerPolicy.tier,
                harness.powerCallbacks.updatedTransportPolicies.single().tier,
            )
            assertEquals(
                listOf(DiagnosticCode.MESH_STARTED to "lifecycle.start"),
                harness.lifecycleDiagnostics.lifecycleEvents,
            )
        }

    @Test
    fun `start from paused returns invalid state without side effects`() = runBlocking {
        // Arrange
        val harness = lifecycleHarness(meshState = MeshLinkState.Paused)
        val support = harness.lifecycleSupport

        // Act
        val result = support.start()

        // Assert
        val invalidState = result as StartResult.InvalidState
        assertEquals(MeshLinkState.Paused, invalidState.currentState)
        assertEquals(0, harness.lifecycleCallbacks.ensureTransportCollectorCalls)
        assertTrue(harness.lifecycleDiagnostics.lifecycleEvents.isEmpty())
    }

    @Test
    fun `pause from running suspends collection clears the volatile view and emits the paused diagnostic`() =
        runBlocking {
            // Arrange
            val harness = lifecycleHarness(meshState = MeshLinkState.Running)
            val support = harness.lifecycleSupport

            // Act
            val result = support.pause()

            // Assert
            assertEquals(PauseResult.Paused, result)
            assertEquals(MeshLinkState.Paused, harness.runtimeSurface.state.value)
            assertEquals(1, harness.lifecycleCallbacks.pauseTransportCalls)
            assertEquals(1, harness.lifecycleCallbacks.stopTransportCollectorCalls)
            assertEquals(
                listOf(
                    RuntimeViewClear(
                        stage = "lifecycle.pause",
                        removalCode = DiagnosticCode.ROUTE_RETRACTED,
                        metadata = mapOf("runtimeBoundary" to "pause"),
                    )
                ),
                harness.lifecycleCallbacks.clearedRuntimeViews,
            )
            assertEquals(
                listOf(DiagnosticCode.MESH_PAUSED to "lifecycle.pause"),
                harness.lifecycleDiagnostics.lifecycleEvents,
            )
        }

    @Test
    fun `pause from stopped returns invalid state`() = runBlocking {
        // Arrange
        val harness = lifecycleHarness(meshState = MeshLinkState.Stopped)
        val support = harness.lifecycleSupport

        // Act
        val result = support.pause()

        // Assert
        val invalidState = result as PauseResult.InvalidState
        assertEquals(MeshLinkState.Stopped, invalidState.currentState)
        assertEquals(0, harness.lifecycleCallbacks.pauseTransportCalls)
        assertTrue(harness.lifecycleCallbacks.clearedRuntimeViews.isEmpty())
    }

    @Test
    fun `resume from paused restarts collection and emits the resumed diagnostic`() = runBlocking {
        // Arrange
        val harness = lifecycleHarness(meshState = MeshLinkState.Paused)
        val support = harness.lifecycleSupport

        // Act
        val result = support.resume()

        // Assert
        assertEquals(ResumeResult.Resumed, result)
        assertEquals(MeshLinkState.Running, harness.runtimeSurface.state.value)
        assertEquals(1, harness.lifecycleCallbacks.ensureTransportCollectorCalls)
        assertEquals(1, harness.lifecycleCallbacks.resumeTransportCalls)
        assertEquals(1, harness.powerCallbacks.updatedTransportPolicies.size)
        assertEquals(
            listOf(DiagnosticCode.MESH_RESUMED to "lifecycle.resume"),
            harness.lifecycleDiagnostics.lifecycleEvents,
        )
    }

    @Test
    fun `stop from uninitialized commits locally without transport teardown`() = runBlocking {
        // Arrange
        val harness = lifecycleHarness(meshState = MeshLinkState.Uninitialized)
        val support = harness.lifecycleSupport

        // Act
        val result = support.stop()

        // Assert
        assertEquals(StopResult.Stopped, result)
        assertEquals(MeshLinkState.Stopped, harness.runtimeSurface.state.value)
        assertEquals(0, harness.lifecycleCallbacks.stopTransportCalls)
        assertEquals(0, harness.lifecycleCallbacks.stopTransportCollectorCalls)
        assertEquals(1, harness.lifecycleCallbacks.clearOutboundTransfersCalls)
        assertTrue(harness.lifecycleCallbacks.clearedRuntimeViews.isEmpty())
        assertTrue(harness.lifecycleCallbacks.abortedTransferReasons.isEmpty())
    }

    @Test
    fun `stop from running aborts transfers clears the volatile view and emits the stopped diagnostic`() =
        runBlocking {
            // Arrange
            val harness = lifecycleHarness(meshState = MeshLinkState.Running)
            val support = harness.lifecycleSupport

            // Act
            val result = support.stop()

            // Assert
            assertEquals(StopResult.Stopped, result)
            assertEquals(MeshLinkState.Stopped, harness.runtimeSurface.state.value)
            assertEquals(
                listOf(TransferAbortReasonCode.RUNTIME_STOPPED),
                harness.lifecycleCallbacks.abortedTransferReasons,
            )
            assertEquals(1, harness.lifecycleCallbacks.clearOutboundTransfersCalls)
            assertEquals(1, harness.lifecycleCallbacks.stopTransportCalls)
            assertEquals(1, harness.lifecycleCallbacks.stopTransportCollectorCalls)
            assertEquals(
                listOf(
                    RuntimeViewClear(
                        stage = "lifecycle.stop",
                        removalCode = DiagnosticCode.ROUTE_RETRACTED,
                        metadata = mapOf("runtimeBoundary" to "stop"),
                    )
                ),
                harness.lifecycleCallbacks.clearedRuntimeViews,
            )
            assertEquals(
                listOf(DiagnosticCode.MESH_STOPPED to "lifecycle.stop"),
                harness.lifecycleDiagnostics.lifecycleEvents,
            )
        }

    @Test
    fun `stop waits for the transport collector to finish before clearing the volatile view`() =
        runBlocking {
            // Arrange
            val harness = lifecycleHarness(meshState = MeshLinkState.Running)
            val support = harness.lifecycleSupport
            val stopTransportCollectorGate = CompletableDeferred<Unit>()
            val stopTransportCollectorStarted = CompletableDeferred<Unit>()
            harness.lifecycleCallbacks.stopTransportCollectorGate = stopTransportCollectorGate
            harness.lifecycleCallbacks.stopTransportCollectorStarted = stopTransportCollectorStarted

            // Act
            val stopDeferred = async { support.stop() }
            withTimeout(1_000) { stopTransportCollectorStarted.await() }
            delay(10)

            // Assert
            assertTrue(harness.lifecycleCallbacks.clearedRuntimeViews.isEmpty())
            assertTrue(!stopDeferred.isCompleted)

            stopTransportCollectorGate.complete(Unit)
            assertEquals(StopResult.Stopped, withTimeout(1_000) { stopDeferred.await() })
            assertEquals(
                listOf(
                    RuntimeViewClear(
                        stage = "lifecycle.stop",
                        removalCode = DiagnosticCode.ROUTE_RETRACTED,
                        metadata = mapOf("runtimeBoundary" to "stop"),
                    )
                ),
                harness.lifecycleCallbacks.clearedRuntimeViews,
            )
        }

    @Test
    fun `start stops the transport collector again when transport startup throws`() = runBlocking {
        // Arrange
        val harness = lifecycleHarness(meshState = MeshLinkState.Uninitialized)
        val support = harness.lifecycleSupport
        harness.lifecycleCallbacks.startTransportFailure = IllegalStateException("boom")

        // Act
        val error = kotlin.test.assertFailsWith<IllegalStateException> { support.start() }

        // Assert
        assertEquals("boom", error.message)
        assertEquals(1, harness.lifecycleCallbacks.ensureTransportCollectorCalls)
        assertEquals(1, harness.lifecycleCallbacks.startTransportCalls)
        assertEquals(1, harness.lifecycleCallbacks.stopTransportCollectorCalls)
        assertEquals(MeshLinkState.Uninitialized, harness.runtimeSurface.state.value)
        assertTrue(harness.lifecycleDiagnostics.lifecycleEvents.isEmpty())
    }

    @Test
    fun `resume stops the transport collector again when transport resume throws`() = runBlocking {
        // Arrange
        val harness = lifecycleHarness(meshState = MeshLinkState.Paused)
        val support = harness.lifecycleSupport
        harness.lifecycleCallbacks.resumeTransportFailure = IllegalStateException("boom")

        // Act
        val error = kotlin.test.assertFailsWith<IllegalStateException> { support.resume() }

        // Assert
        assertEquals("boom", error.message)
        assertEquals(1, harness.lifecycleCallbacks.ensureTransportCollectorCalls)
        assertEquals(1, harness.lifecycleCallbacks.resumeTransportCalls)
        assertEquals(1, harness.lifecycleCallbacks.stopTransportCollectorCalls)
        assertEquals(MeshLinkState.Paused, harness.runtimeSurface.state.value)
        assertTrue(harness.lifecycleDiagnostics.lifecycleEvents.isEmpty())
    }
}

private data class LifecycleHarness(
    val runtimeSurface: MeshEngineRuntimeSurface,
    val powerPolicyState: MeshEnginePowerPolicyState,
    val lifecycleCallbacks: RecordingLifecycleCallbacks,
    val powerCallbacks: RecordingPowerPolicyCallbacks,
    val lifecycleDiagnostics: RecordingLifecycleDiagnostics,
    val lifecycleSupport: MeshEngineLifecycleSupport,
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
    val powerPolicyState =
        MeshEnginePowerPolicyState(
            currentPowerPolicy = powerPolicyController.currentPolicy(nowMillis = 0L)
        )
    val lifecycleState =
        MeshEngineLifecycleState(
            runtimeSurface = runtimeSurface,
            inboundTransfers = linkedMapOf<String, InboundTransferSession>(),
            relayTransfers = linkedMapOf<String, RelayTransferSession>(),
        )
    val lifecycleCallbacks = RecordingLifecycleCallbacks()
    val powerCallbacks = RecordingPowerPolicyCallbacks()
    val lifecycleDiagnostics = RecordingLifecycleDiagnostics()
    val powerPolicySupport =
        MeshEnginePowerPolicySupport(
            powerPolicyController = powerPolicyController,
            powerPolicyNowMillis = { 1_000L },
            state = powerPolicyState,
            callbacks = powerCallbacks.asCallbacks(),
            diagnostics = noOpPowerPolicyDiagnostics,
        )
    val lifecycleSupport =
        MeshEngineLifecycleSupport(
            state = lifecycleState,
            callbacks = lifecycleCallbacks.asCallbacks(),
            diagnostics = lifecycleDiagnostics.asDiagnostics(),
            powerPolicySupport = powerPolicySupport,
        )
    return LifecycleHarness(
        runtimeSurface = runtimeSurface,
        powerPolicyState = powerPolicyState,
        lifecycleCallbacks = lifecycleCallbacks,
        powerCallbacks = powerCallbacks,
        lifecycleDiagnostics = lifecycleDiagnostics,
        lifecycleSupport = lifecycleSupport,
    )
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
    var startTransportFailure: Throwable? = null
    var resumeTransportFailure: Throwable? = null
    var stopTransportCollectorCalls: Int = 0
    val clearedRuntimeViews: MutableList<RuntimeViewClear> = mutableListOf()
    val abortedTransferReasons: MutableList<TransferAbortReasonCode> = mutableListOf()
    var clearOutboundTransfersCalls: Int = 0
    var stopTransportCollectorStarted: CompletableDeferred<Unit>? = null
    var stopTransportCollectorGate: CompletableDeferred<Unit>? = null

    fun asCallbacks(): MeshEngineLifecycleCallbacks {
        return MeshEngineLifecycleCallbacks(
            ensureTransportCollector = { ensureTransportCollectorCalls += 1 },
            stopTransportCollector = {
                stopTransportCollectorCalls += 1
                stopTransportCollectorStarted?.complete(Unit)
                stopTransportCollectorGate?.await()
            },
            startTransport = {
                startTransportCalls += 1
                startTransportFailure?.let { throw it }
            },
            pauseTransport = { pauseTransportCalls += 1 },
            resumeTransport = {
                resumeTransportCalls += 1
                resumeTransportFailure?.let { throw it }
            },
            stopTransport = { stopTransportCalls += 1 },
            clearVolatileRuntimeView = { stage, removalCode, metadata ->
                clearedRuntimeViews += RuntimeViewClear(stage, removalCode, metadata)
            },
            abortCommittedTransfers = { reasonCode -> abortedTransferReasons += reasonCode },
            clearOutboundTransfers = { clearOutboundTransfersCalls += 1 },
        )
    }
}

private class RecordingPowerPolicyCallbacks {
    val updatedTransportPolicies: MutableList<PowerPolicy> = mutableListOf()

    fun asCallbacks(): MeshEnginePowerPolicyCallbacks {
        return MeshEnginePowerPolicyCallbacks(
            updateTransportPowerPolicy = { policy -> updatedTransportPolicies += policy },
            launchTransportPowerPolicyUpdate = { _ ->
                error("Lifecycle tests should not launch asynchronous battery policy updates")
            },
        )
    }
}

private class RecordingLifecycleDiagnostics {
    val lifecycleEvents: MutableList<Pair<DiagnosticCode, String>> = mutableListOf()

    fun asDiagnostics(): MeshEngineLifecycleDiagnostics {
        return MeshEngineLifecycleDiagnostics(
            emitLifecycleEvent = { code, stage -> lifecycleEvents += code to stage }
        )
    }
}

private val noOpPowerPolicyDiagnostics =
    MeshEnginePowerPolicyDiagnostics(emitPowerModeChanged = { _, _, _ -> Unit })
