package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class MeshEngineInitiatorHandshakeSupportTest {
    @Test
    fun `handleHandshakeMessage2 sends message3 and completes the pending initiator handshake`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("initiator-handshake-local")
            val responderIdentity = LocalIdentity.fromAppId("initiator-handshake-responder")
            val peerId = responderIdentity.peerId
            val fixture = initiatorHandshakeFixture(localIdentity = localIdentity)
            val initiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val seededHandshake =
                seedPendingInitiatorHandshake(
                    sessionRegistry = fixture.sessionRegistry,
                    peerId = peerId,
                    initiatorManager = initiatorManager,
                )
            val responderManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val message2 =
                responderManager.processMessage1AndCreateMessage2(
                    responderIdentity.noiseIdentity,
                    seededHandshake.message1,
                )

            // Act
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = message2)

            // Assert
            val sentMessage = fixture.sentFrames.single()
            assertEquals(peerId.value, sentMessage.peerIdValue)
            assertEquals("handshake.message3", sentMessage.action)
            assertIs<DirectWireFrame.HandshakeMessage3>(sentMessage.frame)
            val outcome = seededHandshake.sessionDeferred.await()
            assertIs<SessionEstablishmentOutcome.Established>(outcome)
            assertNotNull(fixture.sessionRegistry.hopSession(peerId))
            val selectedRoute = fixture.routeCoordinator.routeFor(peerId)
            assertNotNull(selectedRoute)
            assertEquals(peerId.value, selectedRoute.destinationPeerId.value)
            assertEquals(peerId.value, selectedRoute.nextHopPeerId.value)
            assertTrue(selectedRoute.isDirect)
            assertEquals(
                listOf(
                    RecordedInitiatorHandshakeEstablished(
                        peerIdValue = peerId.value,
                        stage = "transport.handshake.message2.complete",
                    )
                ),
                fixture.established,
            )
            assertNotNull(fixture.trustStore.read(peerId.value))
            assertTrue(fixture.failures.isEmpty())
            Unit
        }

    @Test
    fun `handleHandshakeMessage2 fails the pending initiator handshake when message3 delivery fails`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("initiator-handshake-local")
            val responderIdentity = LocalIdentity.fromAppId("initiator-handshake-responder")
            val peerId = responderIdentity.peerId
            val fixture =
                initiatorHandshakeFixture(localIdentity = localIdentity) { _, _, _ ->
                    TransportSendResult.Dropped("link not ready")
                }
            val initiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val seededHandshake =
                seedPendingInitiatorHandshake(
                    sessionRegistry = fixture.sessionRegistry,
                    peerId = peerId,
                    initiatorManager = initiatorManager,
                )
            val responderManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val message2 =
                responderManager.processMessage1AndCreateMessage2(
                    responderIdentity.noiseIdentity,
                    seededHandshake.message1,
                )

            // Act
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = message2)

            // Assert
            assertEquals(
                SessionEstablishmentOutcome.Unreachable,
                seededHandshake.sessionDeferred.await(),
            )
            assertNull(fixture.sessionRegistry.initiatorHandshakeReservation(peerId))
            assertEquals(
                listOf(
                    RecordedInitiatorHandshakeFailure(
                        peerIdValue = peerId.value,
                        stage = "transport.handshake.message3.send",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata = emptyMap(),
                    )
                ),
                fixture.failures,
            )
        }

    @Test
    fun `handleHandshakeMessage2 fails trust verification when pinned keys do not match`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("initiator-handshake-local")
            val responderIdentity = LocalIdentity.fromAppId("initiator-handshake-responder")
            val conflictingIdentity = LocalIdentity.fromAppId("initiator-handshake-conflict")
            val peerId = responderIdentity.peerId
            val fixture = initiatorHandshakeFixture(localIdentity = localIdentity)
            fixture.trustStore.write(
                trustRecordFor(peerId = peerId, identity = conflictingIdentity)
            )
            val initiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val seededHandshake =
                seedPendingInitiatorHandshake(
                    sessionRegistry = fixture.sessionRegistry,
                    peerId = peerId,
                    initiatorManager = initiatorManager,
                )
            val responderManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val message2 =
                responderManager.processMessage1AndCreateMessage2(
                    responderIdentity.noiseIdentity,
                    seededHandshake.message1,
                )

            // Act
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = message2)

            // Assert
            assertEquals(
                SessionEstablishmentOutcome.TrustFailure,
                seededHandshake.sessionDeferred.await(),
            )
            assertNull(fixture.sessionRegistry.initiatorHandshakeReservation(peerId))
            assertTrue(fixture.sentFrames.isEmpty())
            assertEquals(
                listOf(
                    RecordedInitiatorHandshakeFailure(
                        peerIdValue = peerId.value,
                        stage = "transport.handshake.message2.trust",
                        reason = DiagnosticReason.TRUST_FAILURE,
                        metadata = emptyMap(),
                    )
                ),
                fixture.failures,
            )
        }

    @Test
    fun `handleHandshakeMessage2 reports unexpected payloads when no pending initiator handshake exists`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("initiator-handshake-local")
            val peerId = PeerId("unknown-peer")
            val fixture = initiatorHandshakeFixture(localIdentity = localIdentity)

            // Act
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = byteArrayOf())

            // Assert
            assertEquals(
                listOf(
                    RecordedInitiatorHandshakeFailure(
                        peerIdValue = peerId.value,
                        stage = "transport.handshake.message2.unexpected",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata = emptyMap(),
                    )
                ),
                fixture.failures,
            )
        }
}

private data class InitiatorHandshakeFixture(
    val support: MeshEngineInitiatorHandshakeSupport,
    val sessionRegistry: MeshEngineSessionRegistry,
    val routeCoordinator: RouteCoordinator,
    val trustStore: TofuTrustStore,
    val sentFrames: MutableList<RecordedInitiatorHandshakeSend>,
    val established: MutableList<RecordedInitiatorHandshakeEstablished>,
    val failures: MutableList<RecordedInitiatorHandshakeFailure>,
    val diagnostics: MutableList<RecordedInitiatorHandshakeDiagnostic>,
)

private data class SeededPendingInitiatorHandshake(
    val message1: ByteArray,
    val sessionDeferred: CompletableDeferred<SessionEstablishmentOutcome>,
)

private fun initiatorHandshakeFixture(
    localIdentity: LocalIdentity,
    sendDirectWireFrame: suspend (PeerId, DirectWireFrame, String) -> TransportSendResult =
        { _, _, _ ->
            TransportSendResult.Delivered
        },
): InitiatorHandshakeFixture {
    val sessionRegistry = MeshEngineSessionRegistry()
    val routeCoordinator = RouteCoordinator(localIdentity.peerId)
    val trustStore = TofuTrustStore(InMemorySecureStorage())
    val sentFrames = mutableListOf<RecordedInitiatorHandshakeSend>()
    val established = mutableListOf<RecordedInitiatorHandshakeEstablished>()
    val failures = mutableListOf<RecordedInitiatorHandshakeFailure>()
    val diagnostics = mutableListOf<RecordedInitiatorHandshakeDiagnostic>()
    val routingSupport =
        MeshEngineRoutingSupport(
            routeCoordinator = routeCoordinator,
            runtimeGate = MeshEngineRuntimeSurface().runtimeGate,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                diagnostics +=
                    RecordedInitiatorHandshakeDiagnostic(
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
                    RecordedInitiatorHandshakeDiagnostic(
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
        MeshEngineInitiatorHandshakeSupport(
            localIdentity = localIdentity,
            trustSupport = trustSupport,
            state = MeshEngineHandshakeState(sessionRegistry = sessionRegistry),
            routingContext =
                MeshEngineHandshakeRoutingContext(
                    routeCoordinator = routeCoordinator,
                    routingSupport = routingSupport,
                ),
            callbacks =
                MeshEngineHandshakeCallbacks(
                    sendDirectWireFrame = { peerId, frame, action ->
                        sentFrames +=
                            RecordedInitiatorHandshakeSend(
                                peerIdValue = peerId.value,
                                frame = frame,
                                action = action,
                            )
                        sendDirectWireFrame(peerId, frame, action)
                    },
                    emitHopSessionEstablished = { peerId, stage ->
                        established +=
                            RecordedInitiatorHandshakeEstablished(
                                peerIdValue = peerId.value,
                                stage = stage,
                            )
                    },
                    emitHopSessionFailed = { peerId, stage, reason, metadata ->
                        failures +=
                            RecordedInitiatorHandshakeFailure(
                                peerIdValue = peerId.value,
                                stage = stage,
                                reason = reason,
                                metadata = metadata,
                            )
                    },
                    promoteTemporaryPeer = { _, _ -> error("unexpected temporary peer promotion") },
                ),
        )
    return InitiatorHandshakeFixture(
        support = support,
        sessionRegistry = sessionRegistry,
        routeCoordinator = routeCoordinator,
        trustStore = trustStore,
        sentFrames = sentFrames,
        established = established,
        failures = failures,
        diagnostics = diagnostics,
    )
}

private suspend fun seedPendingInitiatorHandshake(
    sessionRegistry: MeshEngineSessionRegistry,
    peerId: PeerId,
    initiatorManager: NoiseXXHandshakeManager,
): SeededPendingInitiatorHandshake {
    val message1 = initiatorManager.createMessage1()
    val sessionDeferred = CompletableDeferred<SessionEstablishmentOutcome>()
    val pendingHandshake =
        PendingInitiatorHandshake(manager = initiatorManager, sessionDeferred = sessionDeferred)
    val reservation =
        sessionRegistry.initiatorHandshakeReservation(peerId) {
            CreatedInitiatorHandshake(pendingHandshake = pendingHandshake, message1 = message1)
        }
    assertIs<InitiatorHandshakeReservation.Created>(reservation)
    return SeededPendingInitiatorHandshake(message1 = message1, sessionDeferred = sessionDeferred)
}

private fun trustRecordFor(peerId: PeerId, identity: LocalIdentity): TrustRecord {
    return TrustRecord(
        peerIdValue = peerId.value,
        identityFingerprintBytes = identity.identityFingerprintBytes,
        firstSeenAtEpochMillis = 1L,
        lastVerifiedAtEpochMillis = 1L,
        publicKeys =
            TrustPublicKeys(
                ed25519PublicKey = identity.ed25519PublicKey,
                x25519PublicKey = identity.x25519PublicKey,
            ),
    )
}

private data class RecordedInitiatorHandshakeSend(
    val peerIdValue: String,
    val frame: DirectWireFrame,
    val action: String,
)

private data class RecordedInitiatorHandshakeEstablished(val peerIdValue: String, val stage: String)

private data class RecordedInitiatorHandshakeFailure(
    val peerIdValue: String,
    val stage: String,
    val reason: DiagnosticReason,
    val metadata: Map<String, String>,
)

private data class RecordedInitiatorHandshakeDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)
