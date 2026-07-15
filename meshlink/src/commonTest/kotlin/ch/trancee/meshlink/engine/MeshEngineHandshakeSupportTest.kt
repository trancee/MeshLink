package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeSurface
import ch.trancee.meshlink.engine.handshake.MeshEngineHandshakeRoutingContext
import ch.trancee.meshlink.engine.handshake.MeshEngineHandshakeState
import ch.trancee.meshlink.engine.handshake.MeshEngineSessionRegistry
import ch.trancee.meshlink.engine.handshake.buildMeshEngineRuntimeHandshakeCallbacks
import ch.trancee.meshlink.engine.routing.MeshEngineRoutingSupport
import ch.trancee.meshlink.engine.transport.DirectWireFrame
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

class MeshEngineHandshakeSupportTest {
    @Test
    fun `handshake callback builder preserves all supplied callbacks`() =
        runBlocking<Unit> {
            // Arrange
            var sendCall: Triple<String, String, String>? = null
            var establishedCall: Pair<String, String>? = null
            var failedCall: HandshakeFailureCall? = null
            var promotedCall: Pair<String, String>? = null
            val callbacks =
                buildMeshEngineRuntimeHandshakeCallbacks(
                    sendDirectWireFrame = { peerId, _, action, _ ->
                        sendCall = Triple(peerId.value, "HandshakeMessage1", action)
                        TransportSendResult.Delivered
                    },
                    emitHopSessionEstablished = { peerId, stage ->
                        establishedCall = peerId.value to stage
                    },
                    emitHopSessionFailed = { peerId, stage, reason, metadata ->
                        failedCall =
                            HandshakeFailureCall(
                                peerIdValue = peerId.value,
                                stage = stage,
                                reason = reason,
                                metadata = metadata,
                            )
                    },
                    promoteTemporaryPeer = { temporaryPeerId, canonicalPeerId ->
                        promotedCall = temporaryPeerId.value to canonicalPeerId.value
                    },
                )
            val peerId = PeerId("peer-abcdef")
            val temporaryPeerId = PeerId("temporary")
            val canonicalPeerId = PeerId("canonical")
            val frame = DirectWireFrame.HandshakeMessage1(byteArrayOf(0x01))

            // Act
            val sendResult =
                callbacks.sendDirectWireFrame(peerId, frame, "handshake.message1", null)
            callbacks.emitHopSessionEstablished(peerId, "handshake.established")
            callbacks.emitHopSessionFailed(
                peerId,
                "handshake.failed",
                DiagnosticReason.TRUST_FAILURE,
                mapOf("cause" to "test"),
            )
            callbacks.promoteTemporaryPeer(temporaryPeerId, canonicalPeerId)

            // Assert
            assertSame(TransportSendResult.Delivered, sendResult)
            assertEquals(Triple("peer-abcdef", "HandshakeMessage1", "handshake.message1"), sendCall)
            assertEquals("peer-abcdef" to "handshake.established", establishedCall)
            assertEquals(
                HandshakeFailureCall(
                    peerIdValue = "peer-abcdef",
                    stage = "handshake.failed",
                    reason = DiagnosticReason.TRUST_FAILURE,
                    metadata = mapOf("cause" to "test"),
                ),
                failedCall,
            )
            assertEquals("temporary" to "canonical", promotedCall)
        }

    @Test
    fun `handshake state and routing context preserve their collaborators`() {
        // Arrange
        val sessionRegistry = MeshEngineSessionRegistry()
        val routeCoordinator = RouteCoordinator(PeerId("local-peer"))
        val routingSupport =
            MeshEngineRoutingSupport(
                routeCoordinator = routeCoordinator,
                runtimeGate = MeshEngineRuntimeSurface().runtimeGate,
                coroutineScope =
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
                emitDiagnostic = { _, _, _, _, _, _ -> },
                sendEncryptedWireFrame = { _, _, _, _ -> true },
            )

        // Act
        val state = MeshEngineHandshakeState(sessionRegistry)
        val routingContext =
            MeshEngineHandshakeRoutingContext(
                routeCoordinator = routeCoordinator,
                routingSupport = routingSupport,
                localSelfRouteSeqNo = 1L,
            )

        // Assert
        assertSame(sessionRegistry, state.sessionRegistry)
        assertSame(routeCoordinator, routingContext.routeCoordinator)
        assertSame(routingSupport, routingContext.routingSupport)
    }
}

private data class HandshakeFailureCall(
    val peerIdValue: String,
    val stage: String,
    val reason: DiagnosticReason,
    val metadata: Map<String, String>,
)
