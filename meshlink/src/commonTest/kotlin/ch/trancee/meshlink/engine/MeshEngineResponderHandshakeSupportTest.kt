package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeSurface
import ch.trancee.meshlink.engine.handshake.MeshEngineHandshakeCallbacks
import ch.trancee.meshlink.engine.handshake.MeshEngineHandshakeRoutingContext
import ch.trancee.meshlink.engine.handshake.MeshEngineHandshakeState
import ch.trancee.meshlink.engine.handshake.MeshEngineResponderHandshakeSupport
import ch.trancee.meshlink.engine.handshake.MeshEngineSessionRegistry
import ch.trancee.meshlink.engine.handshake.canonicalPeerIdForTemporaryTransportPeer
import ch.trancee.meshlink.engine.internal.UNEXPECTED_FRAME_HEX_SNIPPET_BYTES
import ch.trancee.meshlink.engine.routing.MeshEngineRoutingSupport
import ch.trancee.meshlink.engine.transport.DirectWireFrame
import ch.trancee.meshlink.engine.trust.MeshEngineTrustSupport
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.identity.fromAppId
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class MeshEngineResponderHandshakeSupportTest {
    @Test
    fun `handleHandshakeMessage1 sends message2 and stores the pending responder handshake`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("responder-handshake-local")
            val temporaryPeerId = PeerId("cb-aabbccddeeff")
            val initiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val message1 = initiatorManager.createMessage1()
            val fixture = responderHandshakeFixture(localIdentity = localIdentity)

            // Act
            fixture.support.handleHandshakeMessage1(peerId = temporaryPeerId, payload = message1)

            // Assert
            val sentMessage = fixture.sentFrames.single()
            assertEquals(temporaryPeerId.value, sentMessage.peerIdValue)
            assertEquals("handshake.message2", sentMessage.action)
            assertIs<DirectWireFrame.HandshakeMessage2>(sentMessage.frame)
            assertNotNull(fixture.sessionRegistry.pendingResponderHandshake(temporaryPeerId))
            assertTrue(fixture.failures.isEmpty())
            assertTrue(fixture.promotions.isEmpty())
        }

    @Test
    fun `handleHandshakeMessage3 promotes temporary peers to canonical ids and completes the handshake`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("responder-handshake-local")
            val initiatorIdentity = LocalIdentity.fromAppId("responder-handshake-initiator")
            val temporaryPeerId = PeerId("cb-aabbccddeeff")
            val expectedCanonicalPeerId =
                canonicalPeerIdForTemporaryTransportPeer(
                    peerId = temporaryPeerId,
                    remoteEd25519PublicKey = initiatorIdentity.ed25519PublicKey,
                    remoteX25519PublicKey = initiatorIdentity.x25519PublicKey,
                    cryptoProvider = localIdentity.cryptoProvider,
                )
            val initiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val message1 = initiatorManager.createMessage1()
            val fixture = responderHandshakeFixture(localIdentity = localIdentity)
            fixture.support.handleHandshakeMessage1(peerId = temporaryPeerId, payload = message1)
            val message2 =
                assertIs<DirectWireFrame.HandshakeMessage2>(fixture.sentFrames.single().frame)
            val message3 =
                initiatorManager.processMessage2AndCreateMessage3(
                    initiatorIdentity.noiseIdentity,
                    message2.payload,
                )

            // Act
            fixture.support.handleHandshakeMessage3(
                peerId = temporaryPeerId,
                payload = message3.message3,
            )

            // Assert
            assertEquals(
                listOf(temporaryPeerId.value to expectedCanonicalPeerId.value),
                fixture.promotions,
            )
            assertNull(fixture.sessionRegistry.pendingResponderHandshake(temporaryPeerId))
            assertNull(fixture.sessionRegistry.pendingResponderHandshake(expectedCanonicalPeerId))
            val aliasedSession = fixture.sessionRegistry.hopSession(temporaryPeerId)
            val canonicalSession = fixture.sessionRegistry.hopSession(expectedCanonicalPeerId)
            assertNotNull(aliasedSession)
            assertSame(aliasedSession, canonicalSession)
            val selectedRoute = fixture.routeCoordinator.routeFor(expectedCanonicalPeerId)
            assertNotNull(selectedRoute)
            assertEquals(expectedCanonicalPeerId.value, selectedRoute.destinationPeerId.value)
            assertEquals(expectedCanonicalPeerId.value, selectedRoute.nextHopPeerId.value)
            assertTrue(selectedRoute.isDirect)
            assertEquals(
                listOf(
                    RecordedResponderHandshakeEstablished(
                        peerIdValue = expectedCanonicalPeerId.value,
                        stage = "transport.handshake.message3.complete",
                    )
                ),
                fixture.established,
            )
            assertNotNull(fixture.trustStore.read(expectedCanonicalPeerId.value))
            assertTrue(fixture.failures.isEmpty())
        }

    @Test
    fun `handleHandshakeMessage1 ignores a byte-identical redundant message1 while a responder handshake is already pending`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("responder-handshake-local")
            val temporaryPeerId = PeerId("cb-aabbccddeeff")
            val message1 = NoiseXXHandshakeManager(localIdentity.cryptoProvider).createMessage1()
            val redundantMessage1 = message1.copyOf()
            val fixture = responderHandshakeFixture(localIdentity = localIdentity)
            fixture.support.handleHandshakeMessage1(peerId = temporaryPeerId, payload = message1)
            val pendingBeforeRedundantDelivery =
                fixture.sessionRegistry.pendingResponderHandshake(temporaryPeerId)

            // Act
            fixture.support.handleHandshakeMessage1(
                peerId = temporaryPeerId,
                payload = redundantMessage1,
            )

            // Assert
            assertEquals(1, fixture.sentFrames.size)
            assertSame(
                pendingBeforeRedundantDelivery,
                fixture.sessionRegistry.pendingResponderHandshake(temporaryPeerId),
            )
            assertEquals(
                listOf(
                    RecordedResponderHandshakeFailure(
                        peerIdValue = temporaryPeerId.value,
                        stage = "transport.handshake.message1.duplicateIgnored",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata =
                            mapOf(
                                "hasEstablishedSession" to "false",
                                "hasPendingResponderHandshake" to "true",
                                "payloadBytes" to redundantMessage1.size.toString(),
                                "payloadPrefixHex" to
                                    redundantMessage1
                                        .copyOf(
                                            minOf(
                                                redundantMessage1.size,
                                                UNEXPECTED_FRAME_HEX_SNIPPET_BYTES,
                                            )
                                        )
                                        .toHexString(),
                            ),
                    )
                ),
                fixture.failures,
            )
        }

    @Test
    fun `handleHandshakeMessage1 ignores a byte-identical redundant message1 once a session is already established`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("responder-handshake-local")
            val initiatorIdentity = LocalIdentity.fromAppId("responder-handshake-initiator")
            val temporaryPeerId = PeerId("cb-aabbccddeeff")
            val initiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val message1 = initiatorManager.createMessage1()
            val fixture = responderHandshakeFixture(localIdentity = localIdentity)
            fixture.support.handleHandshakeMessage1(peerId = temporaryPeerId, payload = message1)
            val message2 =
                assertIs<DirectWireFrame.HandshakeMessage2>(fixture.sentFrames.single().frame)
            val message3 =
                initiatorManager.processMessage2AndCreateMessage3(
                    initiatorIdentity.noiseIdentity,
                    message2.payload,
                )
            fixture.support.handleHandshakeMessage3(
                peerId = temporaryPeerId,
                payload = message3.message3,
            )
            val establishedSession = fixture.sessionRegistry.hopSession(temporaryPeerId)
            assertNotNull(establishedSession)
            val redundantMessage1 = message1.copyOf()

            // Act
            fixture.support.handleHandshakeMessage1(
                peerId = temporaryPeerId,
                payload = redundantMessage1,
            )

            // Assert
            assertEquals(1, fixture.sentFrames.size)
            assertSame(establishedSession, fixture.sessionRegistry.hopSession(temporaryPeerId))
            assertEquals(
                listOf(
                    RecordedResponderHandshakeFailure(
                        peerIdValue = temporaryPeerId.value,
                        stage = "transport.handshake.message1.duplicateIgnored",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata =
                            mapOf(
                                "hasEstablishedSession" to "true",
                                "hasPendingResponderHandshake" to "false",
                                "payloadBytes" to redundantMessage1.size.toString(),
                                "payloadPrefixHex" to
                                    redundantMessage1
                                        .copyOf(
                                            minOf(
                                                redundantMessage1.size,
                                                UNEXPECTED_FRAME_HEX_SNIPPET_BYTES,
                                            )
                                        )
                                        .toHexString(),
                            ),
                    )
                ),
                fixture.failures,
            )
        }

    @Test
    fun `handleHandshakeMessage1 still processes a distinct message1 for a peer with an established session`() =
        runBlocking<Unit> {
            // Arrange -- simulates a peer reconnecting under the same transport peerId with a
            // rotated identity: the new message1's bytes differ from the one that established
            // the current session, so it must be processed as a fresh handshake attempt rather
            // than ignored as a stale duplicate.
            val localIdentity = LocalIdentity.fromAppId("responder-handshake-local")
            val initiatorIdentity = LocalIdentity.fromAppId("responder-handshake-initiator")
            val temporaryPeerId = PeerId("cb-aabbccddeeff")
            val initiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val message1 = initiatorManager.createMessage1()
            val fixture = responderHandshakeFixture(localIdentity = localIdentity)
            fixture.support.handleHandshakeMessage1(peerId = temporaryPeerId, payload = message1)
            val message2 =
                assertIs<DirectWireFrame.HandshakeMessage2>(fixture.sentFrames.single().frame)
            val message3 =
                initiatorManager.processMessage2AndCreateMessage3(
                    initiatorIdentity.noiseIdentity,
                    message2.payload,
                )
            fixture.support.handleHandshakeMessage3(
                peerId = temporaryPeerId,
                payload = message3.message3,
            )
            assertNotNull(fixture.sessionRegistry.hopSession(temporaryPeerId))
            val rotatedInitiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val distinctMessage1 = rotatedInitiatorManager.createMessage1()

            // Act
            fixture.support.handleHandshakeMessage1(
                peerId = temporaryPeerId,
                payload = distinctMessage1,
            )

            // Assert
            assertEquals(2, fixture.sentFrames.size)
            assertIs<DirectWireFrame.HandshakeMessage2>(fixture.sentFrames.last().frame)
            assertNotNull(fixture.sessionRegistry.pendingResponderHandshake(temporaryPeerId))
            assertTrue(fixture.failures.isEmpty())
        }

    @Test
    fun `handleHandshakeMessage1 clears the pending responder handshake when message2 delivery fails`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("responder-handshake-local")
            val temporaryPeerId = PeerId("cb-aabbccddeeff")
            val initiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val message1 = initiatorManager.createMessage1()
            val fixture =
                responderHandshakeFixture(localIdentity = localIdentity) { _, _, _, _ ->
                    TransportSendResult.Dropped("link not ready")
                }

            // Act
            fixture.support.handleHandshakeMessage1(peerId = temporaryPeerId, payload = message1)

            // Assert
            assertNull(fixture.sessionRegistry.pendingResponderHandshake(temporaryPeerId))
            assertEquals(
                listOf(
                    RecordedResponderHandshakeFailure(
                        peerIdValue = temporaryPeerId.value,
                        stage = "transport.handshake.message2.send",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata = emptyMap(),
                    )
                ),
                fixture.failures,
            )
        }

    @Test
    fun `handleHandshakeMessage1 retries message2 delivery after a transient link-not-ready drop`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("responder-handshake-local")
            val temporaryPeerId = PeerId("cb-aabbccddeeff")
            val initiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val message1 = initiatorManager.createMessage1()
            var attempts = 0
            val fixture =
                responderHandshakeFixture(localIdentity = localIdentity) { _, _, _, _ ->
                    attempts += 1
                    if (attempts == 1) {
                        TransportSendResult.Dropped("L2CAP connection is not ready")
                    } else {
                        TransportSendResult.Delivered
                    }
                }

            // Act
            fixture.support.handleHandshakeMessage1(peerId = temporaryPeerId, payload = message1)

            // Assert
            assertEquals(2, attempts)
            assertNotNull(fixture.sessionRegistry.pendingResponderHandshake(temporaryPeerId))
            assertTrue(fixture.failures.isEmpty())
        }

    @Test
    fun `handleHandshakeMessage3 reports unexpected payloads when no pending responder handshake exists`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("responder-handshake-local")
            val temporaryPeerId = PeerId("cb-aabbccddeeff")
            val fixture = responderHandshakeFixture(localIdentity = localIdentity)

            // Act
            fixture.support.handleHandshakeMessage3(
                peerId = temporaryPeerId,
                payload = byteArrayOf(),
            )

            // Assert
            assertEquals(
                listOf(
                    RecordedResponderHandshakeFailure(
                        peerIdValue = temporaryPeerId.value,
                        stage = "transport.handshake.message3.unexpected",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata = mapOf("payloadBytes" to "0", "payloadPrefixHex" to ""),
                    )
                ),
                fixture.failures,
            )
        }
}

private data class ResponderHandshakeFixture(
    val support: MeshEngineResponderHandshakeSupport,
    val sessionRegistry: MeshEngineSessionRegistry,
    val routeCoordinator: RouteCoordinator,
    val trustStore: TofuTrustStore,
    val sentFrames: MutableList<RecordedResponderHandshakeSend>,
    val established: MutableList<RecordedResponderHandshakeEstablished>,
    val failures: MutableList<RecordedResponderHandshakeFailure>,
    val promotions: MutableList<Pair<String, String>>,
    val diagnostics: MutableList<RecordedResponderHandshakeDiagnostic>,
)

private fun responderHandshakeFixture(
    localIdentity: LocalIdentity,
    sendDirectWireFrame:
        suspend (PeerId, DirectWireFrame, String, TransportMode?) -> TransportSendResult =
        { _, _, _, _ ->
            TransportSendResult.Delivered
        },
): ResponderHandshakeFixture {
    val sessionRegistry = MeshEngineSessionRegistry()
    val routeCoordinator = RouteCoordinator(localIdentity.peerId)
    val trustStore = TofuTrustStore(InMemorySecureStorage())
    val sentFrames = mutableListOf<RecordedResponderHandshakeSend>()
    val established = mutableListOf<RecordedResponderHandshakeEstablished>()
    val failures = mutableListOf<RecordedResponderHandshakeFailure>()
    val promotions = mutableListOf<Pair<String, String>>()
    val diagnostics = mutableListOf<RecordedResponderHandshakeDiagnostic>()
    val routingSupport =
        MeshEngineRoutingSupport(
            routeCoordinator = routeCoordinator,
            runtimeGate = MeshEngineRuntimeSurface().runtimeGate,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                diagnostics +=
                    RecordedResponderHandshakeDiagnostic(
                        code = code,
                        severity = severity,
                        stage = stage,
                        peerSuffix = peerSuffix,
                        reason = reason,
                        metadata = metadata,
                    )
            },
            sendEncryptedWireFrame = { _, _, _, _ -> true },
        )
    val trustSupport =
        MeshEngineTrustSupport(
            localIdentity = localIdentity,
            trustStore = trustStore,
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                diagnostics +=
                    RecordedResponderHandshakeDiagnostic(
                        code = code,
                        severity = severity,
                        stage = stage,
                        peerSuffix = peerSuffix,
                        reason = reason,
                        metadata = metadata,
                    )
            },
        )
    val support =
        MeshEngineResponderHandshakeSupport(
            localIdentity = localIdentity,
            trustSupport = trustSupport,
            state = MeshEngineHandshakeState(sessionRegistry = sessionRegistry),
            routingContext =
                MeshEngineHandshakeRoutingContext(
                    routeCoordinator = routeCoordinator,
                    routingSupport = routingSupport,
                    localSelfRouteSeqNo = 1L,
                ),
            callbacks =
                MeshEngineHandshakeCallbacks(
                    sendDirectWireFrame = { peerId, frame, action, preferredMode ->
                        sentFrames +=
                            RecordedResponderHandshakeSend(
                                peerIdValue = peerId.value,
                                frame = frame,
                                action = action,
                            )
                        sendDirectWireFrame(peerId, frame, action, preferredMode)
                    },
                    emitHopSessionEstablished = { peerId, stage ->
                        established +=
                            RecordedResponderHandshakeEstablished(
                                peerIdValue = peerId.value,
                                stage = stage,
                            )
                    },
                    emitHopSessionFailed = { peerId, stage, reason, metadata ->
                        failures +=
                            RecordedResponderHandshakeFailure(
                                peerIdValue = peerId.value,
                                stage = stage,
                                reason = reason,
                                metadata = metadata,
                            )
                    },
                    promoteTemporaryPeer = { temporaryPeerId, canonicalPeerId ->
                        promotions += temporaryPeerId.value to canonicalPeerId.value
                    },
                ),
        )
    return ResponderHandshakeFixture(
        support = support,
        sessionRegistry = sessionRegistry,
        routeCoordinator = routeCoordinator,
        trustStore = trustStore,
        sentFrames = sentFrames,
        established = established,
        failures = failures,
        promotions = promotions,
        diagnostics = diagnostics,
    )
}

private data class RecordedResponderHandshakeSend(
    val peerIdValue: String,
    val frame: DirectWireFrame,
    val action: String,
)

private data class RecordedResponderHandshakeEstablished(val peerIdValue: String, val stage: String)

private data class RecordedResponderHandshakeFailure(
    val peerIdValue: String,
    val stage: String,
    val reason: DiagnosticReason,
    val metadata: Map<String, String>,
)

private data class RecordedResponderHandshakeDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)
