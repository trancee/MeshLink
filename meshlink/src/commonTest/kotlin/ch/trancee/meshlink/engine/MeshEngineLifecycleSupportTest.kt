package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerPolicyController
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.RelayTransferSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class MeshEngineLifecycleSupportTest {
    @Test
    fun `start ensures transport collection updates transport power policy and emits the started diagnostic`() =
        runBlocking {
            // Arrange
            val lifecycleState = lifecycleState(meshState = MeshLinkState.Uninitialized)
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

            // Act
            val result = support.start()

            // Assert
            assertEquals(StartResult.Started, result)
            assertEquals(MeshLinkState.Running, lifecycleState.mutableState.value)
            assertEquals(1, callbacks.ensureTransportCollectorCalls)
            assertEquals(1, callbacks.startTransportCalls)
            assertEquals(1, callbacks.updatedTransportPolicies.size)
            assertEquals(
                lifecycleState.currentPowerPolicy.tier,
                callbacks.updatedTransportPolicies.single().tier,
            )
            assertEquals(
                listOf(DiagnosticCode.MESH_STARTED to "lifecycle.start"),
                diagnostics.lifecycleEvents,
            )
        }

    @Test
    fun `pause short circuits when the mesh is already paused`() = runBlocking {
        // Arrange
        val lifecycleState = lifecycleState(meshState = MeshLinkState.Paused)
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

        // Act
        val result = support.pause()

        // Assert
        assertEquals(PauseResult.AlreadyPaused, result)
        assertEquals(0, callbacks.pauseTransportCalls)
        assertTrue(diagnostics.lifecycleEvents.isEmpty())
    }

    @Test
    fun `stop clears pending initiator handshakes and emits the stopped diagnostic`() =
        runBlocking {
            // Arrange
            val lifecycleState = lifecycleState(meshState = MeshLinkState.Running)
            val callbacks = RecordingLifecycleCallbacks()
            val diagnostics = RecordingLifecycleDiagnostics()
            val localIdentity = ch.trancee.meshlink.identity.LocalIdentity.fromAppId("stop-test")
            val sessionDeferred = CompletableDeferred<SessionEstablishmentOutcome>()
            val pendingHandshake =
                PendingInitiatorHandshake(
                    manager =
                        ch.trancee.meshlink.crypto.NoiseXXHandshakeManager(
                            localIdentity.cryptoProvider
                        ),
                    sessionDeferred = sessionDeferred,
                )
            val reservation =
                lifecycleState.sessionRegistry.initiatorHandshakeReservation(
                    peerId = ch.trancee.meshlink.api.PeerId("peer-stop-abcdef"),
                    createHandshake = {
                        CreatedInitiatorHandshake(
                            pendingHandshake = pendingHandshake,
                            message1 = byteArrayOf(0x01),
                        )
                    },
                )
            assertIs<InitiatorHandshakeReservation.Created>(reservation)
            val support =
                MeshEngineLifecycleSupport(
                    powerPolicyController =
                        PowerPolicyController(
                            configuredMode = PowerMode.Automatic,
                            region = RegulatoryRegion.DEFAULT,
                        ),
                    powerPolicyNowMillis = { 2_000L },
                    state = lifecycleState,
                    callbacks = callbacks.asCallbacks(),
                    diagnostics = diagnostics.asDiagnostics(),
                )

            // Act
            val result = support.stop()

            // Assert
            assertEquals(ch.trancee.meshlink.api.StopResult.Stopped, result)
            assertEquals(MeshLinkState.Stopped, lifecycleState.mutableState.value)
            assertEquals(1, callbacks.stopTransportCalls)
            assertEquals(1, callbacks.stopTransportCollectorCalls)
            assertTrue(sessionDeferred.isCompleted)
            assertIs<SessionEstablishmentOutcome.Unreachable>(sessionDeferred.await())
            assertEquals(
                listOf(DiagnosticCode.MESH_STOPPED to "lifecycle.stop"),
                diagnostics.lifecycleEvents,
            )
        }

    @Test
    fun `updateBattery stores the new power policy and emits a power-mode diagnostic`() {
        // Arrange
        val lifecycleState = lifecycleState(meshState = MeshLinkState.Running)
        val callbacks = RecordingLifecycleCallbacks()
        val diagnostics = RecordingLifecycleDiagnostics()
        val support =
            MeshEngineLifecycleSupport(
                powerPolicyController =
                    PowerPolicyController(
                        configuredMode = PowerMode.Automatic,
                        region = RegulatoryRegion.EU,
                    ),
                powerPolicyNowMillis = { 3_000L },
                state = lifecycleState,
                callbacks = callbacks.asCallbacks(),
                diagnostics = diagnostics.asDiagnostics(),
            )

        // Act
        support.updateBattery(level = 1.5f, isCharging = false)

        // Assert
        assertEquals(1, callbacks.launchedTransportPowerPolicies.size)
        assertEquals(
            lifecycleState.currentPowerPolicy.tier,
            callbacks.launchedTransportPowerPolicies.single().tier,
        )
        assertEquals(1.0f, diagnostics.powerModeLevels.single())
        assertFalse(diagnostics.powerModeCharging.single())
        assertEquals(1, diagnostics.powerModePolicies.size)
    }
}

private fun lifecycleState(meshState: MeshLinkState): MeshEngineLifecycleState {
    val powerPolicyController =
        PowerPolicyController(
            configuredMode = PowerMode.Automatic,
            region = RegulatoryRegion.DEFAULT,
        )
    return MeshEngineLifecycleState(
        mutableState = MutableStateFlow(meshState),
        sessionRegistry = MeshEngineSessionRegistry(),
        outboundTransfers = linkedMapOf<String, OutboundTransferSession>(),
        inboundTransfers = linkedMapOf<String, InboundTransferSession>(),
        relayTransfers = linkedMapOf<String, RelayTransferSession>(),
        currentPowerPolicy = powerPolicyController.currentPolicy(nowMillis = 0L),
    )
}

private class RecordingLifecycleCallbacks {
    var ensureTransportCollectorCalls: Int = 0
    var startTransportCalls: Int = 0
    var pauseTransportCalls: Int = 0
    var resumeTransportCalls: Int = 0
    var stopTransportCalls: Int = 0
    var stopTransportCollectorCalls: Int = 0
    val updatedTransportPolicies: MutableList<PowerPolicy> = mutableListOf()
    val launchedTransportPowerPolicies: MutableList<PowerPolicy> = mutableListOf()

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
