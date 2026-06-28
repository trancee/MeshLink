package ch.trancee.meshlink.engine

import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MeshEngineRuntimeSessionAssemblyTest {
    @Test
    fun `session assembly completes a real handshake between two assembled runtimes`() =
        runBlocking<Unit> {
            // Arrange
            val initiator =
                runtimeSessionAssemblyHarness(LocalIdentity.fromAppId("session-assembly-a"))
            val responder =
                runtimeSessionAssemblyHarness(LocalIdentity.fromAppId("session-assembly-b"))
            val initiatorHardRunToken = initiator.runtimeSurface.beginHardRun()
            val establishment =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) {
                        initiator.session.ensureHopSession(
                            responder.localIdentity.peerId,
                            initiatorHardRunToken,
                        )
                    }
                }
            val message1 =
                assertIs<DirectWireFrame.HandshakeMessage1>(
                    DirectWireFrame.decode(initiator.transport.sentFrames.single().payload)
                )

            // Act
            responder.session.handleHandshakeMessage1(
                initiator.localIdentity.peerId,
                message1.payload,
            )
            val message2 =
                assertIs<DirectWireFrame.HandshakeMessage2>(
                    DirectWireFrame.decode(responder.transport.sentFrames.single().payload)
                )
            initiator.session.handleHandshakeMessage2(
                responder.localIdentity.peerId,
                message2.payload,
            )
            val message3 =
                assertIs<DirectWireFrame.HandshakeMessage3>(
                    initiator.transport.sentFrames
                        .map { sentFrame -> DirectWireFrame.decode(sentFrame.payload) }
                        .first { frame -> frame is DirectWireFrame.HandshakeMessage3 }
                )
            responder.session.handleHandshakeMessage3(
                initiator.localIdentity.peerId,
                message3.payload,
            )
            val outcome = establishment.await()

            // Assert
            assertIs<SessionEstablishmentOutcome.Established>(outcome)
            assertNotNull(
                initiator.foundation.sharedState.sessionRegistry.hopSession(
                    responder.localIdentity.peerId
                )
            )
            assertNotNull(
                responder.foundation.sharedState.sessionRegistry.hopSession(
                    initiator.localIdentity.peerId
                )
            )
            assertTrue(
                initiator.transport.sentFrames
                    .map { frame -> frame.action }
                    .containsAll(listOf("handshake.message1", "handshake.message3"))
            )
            assertEquals(
                listOf("handshake.message2"),
                responder.transport.sentFrames.map { frame -> frame.action },
            )
        }
}

private data class RuntimeSessionAssemblyHarness(
    val localIdentity: LocalIdentity,
    val environment: MeshEngineRuntimeAssemblyEnvironment,
    val support: MeshEngineRuntimeAssemblySupport,
    val foundation: MeshEngineRuntimeFoundationAssembly,
    val session: MeshEngineRuntimeSessionAssembly,
    val runtimeSurface: MeshEngineRuntimeSurface,
    val transport: RecordingRuntimeSessionAssemblyBleTransport,
)

private fun runtimeSessionAssemblyHarness(
    localIdentity: LocalIdentity
): RuntimeSessionAssemblyHarness {
    val runtimeSurface = MeshEngineRuntimeSurface()
    val transport = RecordingRuntimeSessionAssemblyBleTransport()
    val environment =
        MeshEngineRuntimeAssemblyEnvironment(
            config = meshLinkConfig { appId = localIdentity.peerId.value },
            localIdentity = localIdentity,
            trustStore = TofuTrustStore(InMemorySecureStorage()),
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            platformBridge = MeshEnginePlatformBridge(transport),
            publishedSurface = runtimeSurface,
            compatibilitySurface = runtimeSurface,
        )
    val support =
        MeshEngineRuntimeAssemblySupport(
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                runtimeSurface.emitDiagnostic(code, severity, stage, peerSuffix, reason, metadata)
            },
            sendDirectWireFrame = { peerId, frame, action, preferredMode ->
                transport.sentFrames +=
                    RecordedRuntimeSessionAssemblyFrame(
                        peerIdValue = peerId.value,
                        action = action,
                        payload = frame.encode(),
                    )
                TransportSendResult.Delivered
            },
        )
    val lateBindingContext = MeshEngineRuntimeLateBindingContext()
    val foundation =
        buildMeshEngineRuntimeFoundationAssembly(
            environment = environment,
            support = support,
            lateBindingContext = lateBindingContext,
        )
    val session =
        buildMeshEngineRuntimeSessionAssembly(
            environment = environment,
            support = support,
            foundation = foundation,
            lateBindingContext = lateBindingContext,
        )
    return RuntimeSessionAssemblyHarness(
        localIdentity = localIdentity,
        environment = environment,
        support = support,
        foundation = foundation,
        session = session,
        runtimeSurface = runtimeSurface,
        transport = transport,
    )
}

private class RecordingRuntimeSessionAssemblyBleTransport : BleTransport {
    override val events: Flow<TransportEvent> = emptyFlow()
    val sentFrames: MutableList<RecordedRuntimeSessionAssemblyFrame> = mutableListOf()

    override suspend fun start(): Unit = Unit

    override suspend fun pause(): Unit = Unit

    override suspend fun resume(): Unit = Unit

    override suspend fun stop(): Unit = Unit

    override suspend fun send(frame: OutboundFrame): TransportSendResult =
        TransportSendResult.Delivered
}

private data class RecordedRuntimeSessionAssemblyFrame(
    val peerIdValue: String,
    val action: String,
    val payload: ByteArray,
)
