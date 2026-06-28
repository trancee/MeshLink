package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.wire.TransferAbortReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

class MeshEngineRuntimeFacadeAssemblyTest {
    @Test
    fun `facade assembly send delegates to transfer phase sendPayload`() =
        runBlocking<Unit> {
            // Arrange
            val harness = runtimeFacadeAssemblyHarness()
            harness.runtimeSurface.beginHardRun()
            val payload = ByteArray(2_048) { 0x01 }

            // Act
            val result =
                harness.facadeOperations.send(
                    peerId = PeerId("peer-abcdef"),
                    payload = payload,
                    priority = DeliveryPriority.HIGH,
                )

            // Assert
            assertEquals(SendResult.Sent, result)
            assertEquals(
                listOf(
                    RecordedFacadeSendPayload(
                        mode = MeshEngineOutboundDeliveryMode.LARGE_TRANSFER,
                        peerIdValue = "peer-abcdef",
                        payloadBytes = payload.size,
                        priority = DeliveryPriority.HIGH,
                        hardRunEpoch = 1L,
                    )
                ),
                harness.sendPayloadCalls,
            )
        }

    @Test
    fun `facade assembly stop delegates to transfer abort and clear operations`() =
        runBlocking<Unit> {
            // Arrange
            val harness = runtimeFacadeAssemblyHarness()
            harness.runtimeSurface.beginHardRun()

            // Act
            val result = harness.facadeOperations.stop()

            // Assert
            assertEquals(StopResult.Stopped, result)
            assertEquals(listOf(TransferAbortReasonCode.RUNTIME_STOPPED), harness.abortReasons)
            assertEquals(1, harness.clearOutboundTransfersCalls.size)
        }
}

private data class RuntimeFacadeAssemblyHarness(
    val facadeOperations: MeshEngineRuntimeFacadeOperations,
    val runtimeSurface: MeshEngineRuntimeSurface,
    val sendPayloadCalls: MutableList<RecordedFacadeSendPayload>,
    val abortReasons: MutableList<TransferAbortReasonCode>,
    val clearOutboundTransfersCalls: MutableList<Unit>,
)

private fun runtimeFacadeAssemblyHarness(): RuntimeFacadeAssemblyHarness {
    val runtimeSurface = MeshEngineRuntimeSurface()
    val environment =
        MeshEngineRuntimeAssemblyEnvironment(
            config = meshLinkConfig { appId = "runtime-facade-assembly" },
            localIdentity = LocalIdentity.fromAppId("runtime-facade-local"),
            trustStore = TofuTrustStore(InMemorySecureStorage()),
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            platformBridge = MeshEnginePlatformBridge(NoOpFacadeAssemblyBleTransport()),
            publishedSurface = runtimeSurface,
            compatibilitySurface = runtimeSurface,
        )
    val support =
        MeshEngineRuntimeAssemblySupport(
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                runtimeSurface.emitDiagnostic(code, severity, stage, peerSuffix, reason, metadata)
            },
            sendDirectWireFrame = { _, _, _, _ -> TransportSendResult.Delivered },
        )
    val lateBindingContext = MeshEngineRuntimeLateBindingContext()
    val foundation =
        buildMeshEngineRuntimeFoundationAssembly(
            environment = environment,
            support = support,
            lateBindingContext = lateBindingContext,
        )
    val sendPayloadCalls = mutableListOf<RecordedFacadeSendPayload>()
    val abortReasons = mutableListOf<TransferAbortReasonCode>()
    val clearOutboundTransfersCalls = mutableListOf<Unit>()
    val session =
        MeshEngineRuntimeSessionAssembly(
            ensureHopSession = { _, _ -> SessionEstablishmentOutcome.Unreachable },
            sendEncryptedWireFrame = { _, _, _, _ -> false },
            sendEncryptedDirectWireFrame = { _, _, _, _ -> TransportSendResult.Delivered },
            decryptHopPayload = { _, payload -> payload },
            emitHopSessionFailed = { _, _, _, _ -> },
            prewarmHopSession = { _ -> },
            forwardMessageToNextHop = { _, _ -> },
            shouldAttemptLargeInlineSend = { false },
            isLocalPeerId = { false },
            handleHandshakeMessage1 = { _, _ -> },
            handleHandshakeMessage2 = { _, _ -> },
            handleHandshakeMessage3 = { _, _ -> },
        )
    val transferAndInbound =
        MeshEngineRuntimeTransferAndInboundPhase(
            sendPayload = { mode, peerId, payload, priority, hardRunToken ->
                sendPayloadCalls +=
                    RecordedFacadeSendPayload(
                        mode = mode,
                        peerIdValue = peerId.value,
                        payloadBytes = payload.size,
                        priority = priority,
                        hardRunEpoch = hardRunToken.epoch,
                    )
                SendResult.Sent
            },
            handleEncryptedDataFrame = { _, _ -> },
            abortLocalTransfers = { reasonCode -> abortReasons += reasonCode },
            clearOutboundTransfers = { clearOutboundTransfersCalls += Unit },
        )
    val facadeOperations =
        buildMeshEngineRuntimeFacadeOperations(
            environment = environment,
            support = support,
            foundation = foundation,
            session = session,
            transferAndInbound = transferAndInbound,
        )
    return RuntimeFacadeAssemblyHarness(
        facadeOperations = facadeOperations,
        runtimeSurface = runtimeSurface,
        sendPayloadCalls = sendPayloadCalls,
        abortReasons = abortReasons,
        clearOutboundTransfersCalls = clearOutboundTransfersCalls,
    )
}

private data class RecordedFacadeSendPayload(
    val mode: MeshEngineOutboundDeliveryMode,
    val peerIdValue: String,
    val payloadBytes: Int,
    val priority: DeliveryPriority,
    val hardRunEpoch: Long,
)

private class NoOpFacadeAssemblyBleTransport : BleTransport {
    override val events: Flow<TransportEvent> = emptyFlow()

    override suspend fun start(): Unit = Unit

    override suspend fun pause(): Unit = Unit

    override suspend fun resume(): Unit = Unit

    override suspend fun stop(): Unit = Unit

    override suspend fun send(frame: OutboundFrame): TransportSendResult =
        TransportSendResult.Delivered
}
