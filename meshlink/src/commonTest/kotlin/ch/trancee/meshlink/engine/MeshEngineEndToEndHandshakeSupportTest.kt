package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class MeshEngineEndToEndHandshakeSupportTest {
    @Test
    fun `a full relayed handshake establishes matching sessions and pins trust on both ends`() =
        runBlocking<Unit> {
            // Arrange
            val initiatorIdentity = LocalIdentity.fromAppId("e2e-handshake-initiator")
            val responderIdentity = LocalIdentity.fromAppId("e2e-handshake-responder")
            val initiatorFixture = endToEndHandshakeFixture(initiatorIdentity)
            val responderFixture = endToEndHandshakeFixture(responderIdentity)
            wireFixturesTogether(initiatorFixture, responderFixture)

            // Act
            val outcome = initiatorFixture.support.ensureEndToEndSession(responderIdentity.peerId)

            // Assert
            assertIs<EndToEndSessionEstablishmentOutcome.Established>(outcome)
            val initiatorSession = initiatorFixture.registry.session(responderIdentity.peerId)
            val responderSession = responderFixture.registry.session(initiatorIdentity.peerId)
            assertNotNull(initiatorSession)
            assertNotNull(responderSession)
            assertContentEquals(initiatorSession.sendKey, responderSession.receiveKey)
            assertContentEquals(initiatorSession.receiveKey, responderSession.sendKey)
            assertNotNull(initiatorFixture.trustStore.read(responderIdentity.peerId.value))
            assertNotNull(responderFixture.trustStore.read(initiatorIdentity.peerId.value))
            assertTrue(
                initiatorFixture.diagnostics.any { it.code == DiagnosticCode.TRUST_ESTABLISHED }
            )
            assertTrue(
                responderFixture.diagnostics.any { it.code == DiagnosticCode.TRUST_ESTABLISHED }
            )
        }

    @Test
    fun `ensureEndToEndSession returns the established session immediately on repeat calls`() =
        runBlocking<Unit> {
            // Arrange
            val initiatorIdentity = LocalIdentity.fromAppId("e2e-handshake-repeat-initiator")
            val responderIdentity = LocalIdentity.fromAppId("e2e-handshake-repeat-responder")
            val initiatorFixture = endToEndHandshakeFixture(initiatorIdentity)
            val responderFixture = endToEndHandshakeFixture(responderIdentity)
            wireFixturesTogether(initiatorFixture, responderFixture)
            val firstOutcome =
                initiatorFixture.support.ensureEndToEndSession(responderIdentity.peerId)
            assertIs<EndToEndSessionEstablishmentOutcome.Established>(firstOutcome)

            // Act
            val secondOutcome =
                initiatorFixture.support.ensureEndToEndSession(responderIdentity.peerId)

            // Assert
            assertIs<EndToEndSessionEstablishmentOutcome.Established>(secondOutcome)
            assertEquals(
                1,
                initiatorFixture.sentFrames.count { it.action == "e2eHandshake.message1" },
            )
        }

    @Test
    fun `ensureEndToEndSession reports unreachable when message1 delivery fails`() =
        runBlocking<Unit> {
            // Arrange
            val initiatorIdentity = LocalIdentity.fromAppId("e2e-handshake-unreachable-initiator")
            val destinationPeerId = PeerId("00112233445566778899aabb")
            val initiatorFixture =
                endToEndHandshakeFixture(
                    initiatorIdentity,
                    sendFrameTowardsPeer = { _, _, _ -> false },
                )

            // Act
            val outcome = initiatorFixture.support.ensureEndToEndSession(destinationPeerId)

            // Assert
            assertEquals(EndToEndSessionEstablishmentOutcome.Unreachable, outcome)
            assertNull(initiatorFixture.registry.session(destinationPeerId))
        }

    @Test
    fun `handleLocalEndToEndHandshakeFrame reports message1 processing failures`() =
        runBlocking<Unit> {
            // Arrange
            val responderIdentity = LocalIdentity.fromAppId("e2e-handshake-bad-message1")
            val originPeerId = PeerId("00112233445566778899aabb")
            val responderFixture = endToEndHandshakeFixture(responderIdentity)
            val invalidMessage1Frame =
                WireFrame.EndToEndHandshakeMessage1(
                    route =
                        WireFrame.EndToEndHandshakeRoute(
                            handshakeId = "handshake-1",
                            originPeerId = originPeerId,
                            destinationPeerId = responderIdentity.peerId,
                        ),
                    payload = ByteArray(0),
                )

            // Act
            responderFixture.support.handleLocalEndToEndHandshakeFrame(invalidMessage1Frame)

            // Assert
            assertTrue(responderFixture.sentFrames.isEmpty())
            assertEquals(
                listOf("e2eHandshake.message1.process"),
                responderFixture.diagnostics.map { it.stage },
            )
        }

    @Test
    fun `handleLocalEndToEndHandshakeFrame reports message2 arriving with no pending initiator handshake`() =
        runBlocking<Unit> {
            // Arrange
            val initiatorIdentity = LocalIdentity.fromAppId("e2e-handshake-unexpected-message2")
            val originPeerId = PeerId("00112233445566778899aabb")
            val initiatorFixture = endToEndHandshakeFixture(initiatorIdentity)
            val unexpectedMessage2Frame =
                WireFrame.EndToEndHandshakeMessage2(
                    route =
                        WireFrame.EndToEndHandshakeRoute(
                            handshakeId = "handshake-1",
                            originPeerId = originPeerId,
                            destinationPeerId = initiatorIdentity.peerId,
                        ),
                    payload = ByteArray(0),
                )

            // Act
            initiatorFixture.support.handleLocalEndToEndHandshakeFrame(unexpectedMessage2Frame)

            // Assert
            assertEquals(
                listOf("e2eHandshake.message2.unexpected"),
                initiatorFixture.diagnostics.map { it.stage },
            )
        }

    @Test
    fun `handleLocalEndToEndHandshakeFrame reports message2 processing failures`() =
        runBlocking<Unit> {
            // Arrange
            val initiatorIdentity = LocalIdentity.fromAppId("e2e-handshake-bad-message2")
            val responderIdentity = LocalIdentity.fromAppId("e2e-handshake-bad-message2-peer")
            val initiatorFixture = endToEndHandshakeFixture(initiatorIdentity)
            val ensureDeferred =
                async(kotlinx.coroutines.Dispatchers.Unconfined) {
                    initiatorFixture.support.ensureEndToEndSession(responderIdentity.peerId)
                }
            val invalidMessage2Frame =
                WireFrame.EndToEndHandshakeMessage2(
                    route =
                        WireFrame.EndToEndHandshakeRoute(
                            handshakeId = "handshake-1",
                            originPeerId = responderIdentity.peerId,
                            destinationPeerId = initiatorIdentity.peerId,
                        ),
                    payload = ByteArray(0),
                )

            // Act
            initiatorFixture.support.handleLocalEndToEndHandshakeFrame(invalidMessage2Frame)

            // Assert
            assertEquals(EndToEndSessionEstablishmentOutcome.Unreachable, ensureDeferred.await())
            assertTrue(
                initiatorFixture.diagnostics.any { it.stage == "e2eHandshake.message2.process" }
            )
            assertNull(initiatorFixture.registry.session(responderIdentity.peerId))
        }

    @Test
    fun `handleLocalEndToEndHandshakeFrame fails trust verification when pinned keys do not match`() =
        runBlocking<Unit> {
            // Arrange
            val initiatorIdentity =
                LocalIdentity.fromAppId("e2e-handshake-trust-mismatch-initiator")
            val responderIdentity =
                LocalIdentity.fromAppId("e2e-handshake-trust-mismatch-responder")
            val conflictingIdentity =
                LocalIdentity.fromAppId("e2e-handshake-trust-mismatch-conflict")
            val initiatorFixture = endToEndHandshakeFixture(initiatorIdentity)
            val responderFixture = endToEndHandshakeFixture(responderIdentity)
            wireFixturesTogether(initiatorFixture, responderFixture)
            initiatorFixture.trustStore.write(
                trustRecordFor(peerId = responderIdentity.peerId, identity = conflictingIdentity)
            )

            // Act
            val outcome = initiatorFixture.support.ensureEndToEndSession(responderIdentity.peerId)

            // Assert
            assertEquals(EndToEndSessionEstablishmentOutcome.TrustFailure, outcome)
            assertNull(initiatorFixture.registry.session(responderIdentity.peerId))
            assertTrue(
                initiatorFixture.diagnostics.any { it.stage == "e2eHandshake.message2.trust" }
            )
        }

    @Test
    fun `ensureEndToEndSession reports unreachable when message3 delivery fails`() =
        runBlocking<Unit> {
            // Arrange
            val initiatorIdentity = LocalIdentity.fromAppId("e2e-handshake-message3-fail-initiator")
            val responderIdentity = LocalIdentity.fromAppId("e2e-handshake-message3-fail-responder")
            val responderFixture = endToEndHandshakeFixture(responderIdentity)
            var messageCount = 0
            val initiatorFixture =
                endToEndHandshakeFixture(initiatorIdentity) { _, frame, _ ->
                    messageCount += 1
                    if (frame is WireFrame.EndToEndHandshakeMessage3) {
                        false
                    } else {
                        responderFixture.support.handleLocalEndToEndHandshakeFrame(
                            frame as WireFrame.EndToEndHandshakeFrame
                        )
                        true
                    }
                }
            responderFixture.deliveryDelegateHolder.delegate = { _, frame, _ ->
                initiatorFixture.support.handleLocalEndToEndHandshakeFrame(
                    frame as WireFrame.EndToEndHandshakeFrame
                )
                true
            }

            // Act
            val outcome = initiatorFixture.support.ensureEndToEndSession(responderIdentity.peerId)

            // Assert
            assertEquals(EndToEndSessionEstablishmentOutcome.Unreachable, outcome)
            assertTrue(messageCount >= 2)
            assertNull(initiatorFixture.registry.session(responderIdentity.peerId))
        }

    @Test
    fun `handleLocalEndToEndHandshakeFrame reports message3 arriving with no pending responder handshake`() =
        runBlocking<Unit> {
            // Arrange
            val responderIdentity = LocalIdentity.fromAppId("e2e-handshake-unexpected-message3")
            val originPeerId = PeerId("00112233445566778899aabb")
            val responderFixture = endToEndHandshakeFixture(responderIdentity)
            val unexpectedMessage3Frame =
                WireFrame.EndToEndHandshakeMessage3(
                    route =
                        WireFrame.EndToEndHandshakeRoute(
                            handshakeId = "handshake-1",
                            originPeerId = originPeerId,
                            destinationPeerId = responderIdentity.peerId,
                        ),
                    payload = ByteArray(0),
                )

            // Act
            responderFixture.support.handleLocalEndToEndHandshakeFrame(unexpectedMessage3Frame)

            // Assert
            assertEquals(
                listOf("e2eHandshake.message3.unexpected"),
                responderFixture.diagnostics.map { it.stage },
            )
        }

    @Test
    fun `handleLocalEndToEndHandshakeFrame reports message3 processing failures`() =
        runBlocking<Unit> {
            // Arrange
            val initiatorIdentity = LocalIdentity.fromAppId("e2e-handshake-bad-message3-initiator")
            val responderIdentity = LocalIdentity.fromAppId("e2e-handshake-bad-message3-responder")
            val responderFixture = endToEndHandshakeFixture(responderIdentity)
            val initiatorManager = NoiseXXHandshakeManager(initiatorIdentity.cryptoProvider)
            val message1Frame =
                WireFrame.EndToEndHandshakeMessage1(
                    route =
                        WireFrame.EndToEndHandshakeRoute(
                            handshakeId = "handshake-1",
                            originPeerId = initiatorIdentity.peerId,
                            destinationPeerId = responderIdentity.peerId,
                        ),
                    payload = initiatorManager.createMessage1(),
                )
            responderFixture.support.handleLocalEndToEndHandshakeFrame(message1Frame)
            val corruptedMessage3Frame =
                WireFrame.EndToEndHandshakeMessage3(
                    route =
                        WireFrame.EndToEndHandshakeRoute(
                            handshakeId = "handshake-1",
                            originPeerId = initiatorIdentity.peerId,
                            destinationPeerId = responderIdentity.peerId,
                        ),
                    payload = ByteArray(0),
                )

            // Act
            responderFixture.support.handleLocalEndToEndHandshakeFrame(corruptedMessage3Frame)

            // Assert
            assertTrue(
                responderFixture.diagnostics.any { it.stage == "e2eHandshake.message3.process" }
            )
            assertNull(
                responderFixture.registry.pendingResponderHandshake(initiatorIdentity.peerId)
            )
        }
}

private data class RecordedEndToEndHandshakeSend(
    val peerIdValue: String,
    val frame: WireFrame,
    val action: String,
)

private data class RecordedEndToEndHandshakeDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)

private data class EndToEndHandshakeFixture(
    val support: MeshEngineEndToEndHandshakeSupport,
    val registry: MeshEngineEndToEndSessionRegistry,
    val trustStore: TofuTrustStore,
    val sentFrames: MutableList<RecordedEndToEndHandshakeSend>,
    val diagnostics: MutableList<RecordedEndToEndHandshakeDiagnostic>,
    val deliveryDelegateHolder: DeliveryDelegateHolder,
)

private class DeliveryDelegateHolder {
    var delegate: suspend (PeerId, WireFrame, String) -> Boolean = { _, _, _ -> true }
}

private fun endToEndHandshakeFixture(
    localIdentity: LocalIdentity,
    sendFrameTowardsPeer: suspend (PeerId, WireFrame, String) -> Boolean = { _, _, _ -> true },
): EndToEndHandshakeFixture {
    val registry = MeshEngineEndToEndSessionRegistry()
    val trustStore = TofuTrustStore(InMemorySecureStorage())
    val sentFrames = mutableListOf<RecordedEndToEndHandshakeSend>()
    val diagnostics = mutableListOf<RecordedEndToEndHandshakeDiagnostic>()
    val deliveryDelegateHolder = DeliveryDelegateHolder()
    deliveryDelegateHolder.delegate = sendFrameTowardsPeer
    var handshakeCounter = 0
    val trustSupport =
        MeshEngineTrustSupport(
            localIdentity = localIdentity,
            trustStore = trustStore,
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                diagnostics +=
                    RecordedEndToEndHandshakeDiagnostic(
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
        MeshEngineEndToEndHandshakeSupport(
            localIdentity = localIdentity,
            trustSupport = trustSupport,
            registry = registry,
            callbacks =
                MeshEngineEndToEndHandshakeCallbacks(
                    sendFrameTowardsPeer = { peerId, frame, action ->
                        sentFrames +=
                            RecordedEndToEndHandshakeSend(
                                peerIdValue = peerId.value,
                                frame = frame,
                                action = action,
                            )
                        deliveryDelegateHolder.delegate(peerId, frame, action)
                    },
                    createHandshakeId = {
                        handshakeCounter += 1
                        "handshake-$handshakeCounter"
                    },
                    emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                        diagnostics +=
                            RecordedEndToEndHandshakeDiagnostic(
                                code = code,
                                severity = severity,
                                stage = stage,
                                peerSuffix = peerSuffix,
                                reason = reason,
                                metadata = metadata,
                            )
                    },
                ),
        )
    return EndToEndHandshakeFixture(
        support = support,
        registry = registry,
        trustStore = trustStore,
        sentFrames = sentFrames,
        diagnostics = diagnostics,
        deliveryDelegateHolder = deliveryDelegateHolder,
    )
}

/**
 * Connects two fixtures' send callbacks so that frames sent by one are delivered directly to the
 * other's [MeshEngineEndToEndHandshakeSupport.handleLocalEndToEndHandshakeFrame], simulating a
 * relay transparently forwarding opaque handshake frames between two mesh peers (with any number of
 * hops in between collapsed away, since relaying itself is covered separately by
 * `MeshEnginePeerFlowSupportTest` and `MeshEngineInboundSupportTest`).
 */
private fun wireFixturesTogether(
    first: EndToEndHandshakeFixture,
    second: EndToEndHandshakeFixture,
): Unit {
    first.deliveryDelegateHolder.delegate = { _, frame, _ ->
        second.support.handleLocalEndToEndHandshakeFrame(frame as WireFrame.EndToEndHandshakeFrame)
        true
    }
    second.deliveryDelegateHolder.delegate = { _, frame, _ ->
        first.support.handleLocalEndToEndHandshakeFrame(frame as WireFrame.EndToEndHandshakeFrame)
        true
    }
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
