package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeSurface
import ch.trancee.meshlink.engine.handshake.CreatedInitiatorHandshake
import ch.trancee.meshlink.engine.handshake.InitiatorHandshakeReservation
import ch.trancee.meshlink.engine.handshake.MeshEngineHandshakeCallbacks
import ch.trancee.meshlink.engine.handshake.MeshEngineHandshakeRoutingContext
import ch.trancee.meshlink.engine.handshake.MeshEngineHandshakeState
import ch.trancee.meshlink.engine.handshake.MeshEngineInitiatorHandshakeSupport
import ch.trancee.meshlink.engine.handshake.MeshEngineSessionRegistry
import ch.trancee.meshlink.engine.handshake.canonicalPeerIdForTemporaryTransportPeer
import ch.trancee.meshlink.engine.internal.PendingInitiatorHandshake
import ch.trancee.meshlink.engine.internal.SessionEstablishmentOutcome
import ch.trancee.meshlink.engine.internal.UNEXPECTED_FRAME_HEX_SNIPPET_BYTES
import ch.trancee.meshlink.engine.routing.MeshEngineRoutingSupport
import ch.trancee.meshlink.engine.transport.DirectWireFrame
import ch.trancee.meshlink.engine.trust.MeshEngineTrustSupport
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.transport.TransportMode
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
        runBlocking<Unit> {
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
        }

    @Test
    fun `handleHandshakeMessage2 ignores a byte-identical redundant message2 once the session is established`() =
        runBlocking<Unit> {
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
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = message2)
            assertIs<SessionEstablishmentOutcome.Established>(
                seededHandshake.sessionDeferred.await()
            )

            // Act
            // Simulate MeshLink's redundant GATT/L2CAP side-link transports delivering the exact
            // same message2 wire frame a second time after the handshake already completed.
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = message2)

            // Assert
            assertEquals(1, fixture.sentFrames.size, "no additional message3 should be sent")
            assertEquals(1, fixture.established.size, "session should not be re-established")
            assertEquals(
                listOf(
                    RecordedInitiatorHandshakeFailure(
                        peerIdValue = peerId.value,
                        stage = "transport.handshake.message2.duplicateIgnored",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata =
                            mapOf(
                                "payloadBytes" to message2.size.toString(),
                                "payloadPrefixHex" to
                                    message2
                                        .copyOf(
                                            minOf(message2.size, UNEXPECTED_FRAME_HEX_SNIPPET_BYTES)
                                        )
                                        .toHexString(),
                            ),
                    )
                ),
                fixture.failures,
            )
        }

    @Test
    fun `handleHandshakeMessage2 reports unexpected for a distinct message2 once the session is established`() =
        runBlocking<Unit> {
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
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = message2)
            assertIs<SessionEstablishmentOutcome.Established>(
                seededHandshake.sessionDeferred.await()
            )
            val distinctPayload = message2.copyOf().also { it[0] = (it[0] + 1).toByte() }

            // Act
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = distinctPayload)

            // Assert
            assertEquals(1, fixture.sentFrames.size, "no additional message3 should be sent")
            assertEquals(
                listOf(
                    RecordedInitiatorHandshakeFailure(
                        peerIdValue = peerId.value,
                        stage = "transport.handshake.message2.unexpected",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata =
                            mapOf(
                                "payloadBytes" to distinctPayload.size.toString(),
                                "payloadPrefixHex" to
                                    distinctPayload
                                        .copyOf(
                                            minOf(
                                                distinctPayload.size,
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
    fun `handleHandshakeMessage2 promotes temporary peers to their canonical ids`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("initiator-temporary-local")
            val responderIdentity = LocalIdentity.fromAppId("initiator-temporary-responder")
            val temporaryPeerId = PeerId("bt-aabbccddeeff")
            val canonicalPeerId =
                canonicalPeerIdForTemporaryTransportPeer(
                    peerId = temporaryPeerId,
                    remoteEd25519PublicKey = responderIdentity.ed25519PublicKey,
                    remoteX25519PublicKey = responderIdentity.x25519PublicKey,
                    cryptoProvider = localIdentity.cryptoProvider,
                )
            val fixture = initiatorHandshakeFixture(localIdentity = localIdentity)
            val initiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val seededHandshake =
                seedPendingInitiatorHandshake(
                    sessionRegistry = fixture.sessionRegistry,
                    peerId = temporaryPeerId,
                    initiatorManager = initiatorManager,
                )
            val responderManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val message2 =
                responderManager.processMessage1AndCreateMessage2(
                    responderIdentity.noiseIdentity,
                    seededHandshake.message1,
                )

            // Act
            fixture.support.handleHandshakeMessage2(peerId = temporaryPeerId, payload = message2)

            // Assert
            val sentMessage = fixture.sentFrames.single()
            assertEquals(canonicalPeerId.value, sentMessage.peerIdValue)
            assertEquals("handshake.message3", sentMessage.action)
            assertIs<DirectWireFrame.HandshakeMessage3>(sentMessage.frame)
            val outcome = seededHandshake.sessionDeferred.await()
            assertIs<SessionEstablishmentOutcome.Established>(outcome)
            assertEquals(listOf(temporaryPeerId.value to canonicalPeerId.value), fixture.promotions)
            assertEquals(
                canonicalPeerId.value,
                fixture.sessionRegistry.resolvePeerId(temporaryPeerId).value,
            )
            assertNotNull(fixture.sessionRegistry.hopSession(canonicalPeerId))
            val selectedRoute = fixture.routeCoordinator.routeFor(canonicalPeerId)
            assertNotNull(selectedRoute)
            assertEquals(canonicalPeerId.value, selectedRoute.destinationPeerId.value)
            assertNotNull(fixture.trustStore.read(canonicalPeerId.value))
            assertTrue(fixture.failures.isEmpty())
        }

    @Test
    fun `handleHandshakeMessage2 fails the pending initiator handshake when message3 delivery fails`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("initiator-handshake-local")
            val responderIdentity = LocalIdentity.fromAppId("initiator-handshake-responder")
            val peerId = responderIdentity.peerId
            val fixture =
                initiatorHandshakeFixture(localIdentity = localIdentity) { _, _, _, _ ->
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
        runBlocking<Unit> {
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
        runBlocking<Unit> {
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
                        metadata = mapOf("payloadBytes" to "0", "payloadPrefixHex" to ""),
                    )
                ),
                fixture.failures,
            )
        }

    @Test
    fun `handleHandshakeMessage2 drops a stale message2 from a superseded attempt and the current attempt still completes normally`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("initiator-handshake-local")
            val responderIdentity = LocalIdentity.fromAppId("initiator-handshake-responder")
            val peerId = responderIdentity.peerId
            val fixture = initiatorHandshakeFixture(localIdentity = localIdentity)
            val supersededInitiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val supersededHandshake =
                seedPendingInitiatorHandshake(
                    sessionRegistry = fixture.sessionRegistry,
                    peerId = peerId,
                    initiatorManager = supersededInitiatorManager,
                )
            val supersededResponderManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            // The real message2 reply the responder sent to the (about to be superseded) first
            // attempt -- physically delayed by the transport, delivered only after a newer attempt
            // has already taken its place, as observed in the field with pause/resume-interrupted
            // BLE handshakes.
            val staleMessage2 =
                supersededResponderManager.processMessage1AndCreateMessage2(
                    responderIdentity.noiseIdentity,
                    supersededHandshake.message1,
                )
            val supersededReservation =
                fixture.sessionRegistry.initiatorHandshakeReservation(peerId)
            assertIs<InitiatorHandshakeReservation.Pending>(supersededReservation)
            val supersededAttemptId = supersededReservation.pendingHandshake.attemptId
            fixture.sessionRegistry.failInitiatorHandshake(
                peerId,
                supersededReservation.pendingHandshake,
            )
            val currentInitiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val currentHandshake =
                seedPendingInitiatorHandshake(
                    sessionRegistry = fixture.sessionRegistry,
                    peerId = peerId,
                    initiatorManager = currentInitiatorManager,
                )

            // Act
            // The stale reply fails Noise transcript verification against the current attempt's
            // manager, but successfully decrypts against the retained superseded manager.
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = staleMessage2)

            // Assert
            assertTrue(
                fixture.sentFrames.isEmpty(),
                "no message3 should be sent for the stale reply",
            )
            assertTrue(
                !currentHandshake.sessionDeferred.isCompleted,
                "the current, still-legitimate attempt must not be failed by the stale reply",
            )
            assertEquals(
                listOf(
                    RecordedInitiatorHandshakeFailure(
                        peerIdValue = peerId.value,
                        stage = "transport.handshake.message2.staleAttemptIgnored",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata =
                            mapOf(
                                "payloadBytes" to staleMessage2.size.toString(),
                                "staleAttemptId" to supersededAttemptId.toString(),
                                // seedPendingInitiatorHandshake (like the rest of this test file)
                                // doesn't thread the registry-assigned attemptId through, so the
                                // current attempt's PendingInitiatorHandshake keeps its default.
                                "attemptId" to "0",
                            ),
                    )
                ),
                fixture.failures,
            )

            // Act (continued)
            // The genuine reply for the current attempt then arrives, and must still be able to
            // complete the handshake -- this specifically guards against the current attempt's
            // tryBeginProcessingMessage2 claim being left permanently taken by the stale-match
            // handling above, which would otherwise wedge this real delivery as a duplicate.
            val currentResponderManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val currentMessage2 =
                currentResponderManager.processMessage1AndCreateMessage2(
                    responderIdentity.noiseIdentity,
                    currentHandshake.message1,
                )
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = currentMessage2)

            // Assert
            assertIs<SessionEstablishmentOutcome.Established>(
                currentHandshake.sessionDeferred.await()
            )
            assertEquals(
                1,
                fixture.sentFrames.size,
                "message3 should be sent for the genuine reply",
            )
            assertNotNull(fixture.sessionRegistry.hopSession(peerId))
        }

    @Test
    fun `handleHandshakeMessage2 still recognizes a superseded attempt's genuine stale reply after an unrelated message2 was unsuccessfully trial-decrypted against it first`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("initiator-handshake-local")
            val responderIdentity = LocalIdentity.fromAppId("initiator-handshake-responder")
            val peerId = responderIdentity.peerId
            val fixture = initiatorHandshakeFixture(localIdentity = localIdentity)

            // A decoy superseded attempt, superseded *before* the target attempt below, so it is
            // trial-decrypted *after* the target attempt (candidates are tried most-recently
            // superseded first). Unlike seedPendingInitiatorHandshake (which does not thread the
            // registry-assigned attemptId through, see other tests in this file), this
            // constructs the PendingInitiatorHandshake with its real, unique attemptId so the
            // registry's attemptId-keyed bookkeeping (releaseSupersededAttemptClaim /
            // recordSupersededAttemptMatch) can tell the decoy and target attempts apart, exactly
            // as it does in production.
            val decoyInitiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val decoyMessage1 = decoyInitiatorManager.createMessage1()
            val decoyReservation =
                fixture.sessionRegistry.initiatorHandshakeReservation(peerId) { attemptId ->
                    CreatedInitiatorHandshake(
                        pendingHandshake =
                            PendingInitiatorHandshake(
                                manager = decoyInitiatorManager,
                                sessionDeferred = CompletableDeferred(),
                                attemptId = attemptId,
                            ),
                        message1 = decoyMessage1,
                    )
                }
            assertIs<InitiatorHandshakeReservation.Created>(decoyReservation)
            val decoyResponderManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val decoyMessage2 =
                decoyResponderManager.processMessage1AndCreateMessage2(
                    responderIdentity.noiseIdentity,
                    decoyMessage1,
                )
            fixture.sessionRegistry.failInitiatorHandshake(
                peerId,
                decoyReservation.pendingHandshake,
            )

            // The target superseded attempt, superseded *after* the decoy, so it is trial-
            // decrypted *first* -- its own genuine stale reply is delivered only afterwards.
            val targetInitiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val targetMessage1 = targetInitiatorManager.createMessage1()
            val targetReservation =
                fixture.sessionRegistry.initiatorHandshakeReservation(peerId) { attemptId ->
                    CreatedInitiatorHandshake(
                        pendingHandshake =
                            PendingInitiatorHandshake(
                                manager = targetInitiatorManager,
                                sessionDeferred = CompletableDeferred(),
                                attemptId = attemptId,
                            ),
                        message1 = targetMessage1,
                    )
                }
            assertIs<InitiatorHandshakeReservation.Created>(targetReservation)
            val targetAttemptId = targetReservation.pendingHandshake.attemptId
            val targetResponderManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val targetStaleMessage2 =
                targetResponderManager.processMessage1AndCreateMessage2(
                    responderIdentity.noiseIdentity,
                    targetMessage1,
                )
            fixture.sessionRegistry.failInitiatorHandshake(
                peerId,
                targetReservation.pendingHandshake,
            )

            val currentInitiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val currentHandshake =
                seedPendingInitiatorHandshake(
                    sessionRegistry = fixture.sessionRegistry,
                    peerId = peerId,
                    initiatorManager = currentInitiatorManager,
                )

            // Act
            // The decoy's own genuine stale reply arrives first. It is trial-decrypted against
            // the target attempt first (most recently superseded), which does *not* match --
            // under the old design this single failed trial would have permanently corrupted the
            // target's manager, since any invocation (successful or not) mutated its transcript
            // state irreversibly. The snapshot/rollback in
            // NoiseXXHandshakeManager.tryProcessMessage2AndCreateMessage3 leaves it genuinely
            // untouched instead, so trial-decrypting then continues on to the decoy attempt,
            // which matches.
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = decoyMessage2)
            assertTrue(
                !currentHandshake.sessionDeferred.isCompleted,
                "the current attempt must be unaffected by the decoy's stale reply",
            )

            // The target attempt's own genuine stale reply then arrives -- it must still be
            // recognized, since the earlier failed trial against it did not poison its manager.
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = targetStaleMessage2)

            // Assert
            assertTrue(fixture.sentFrames.isEmpty())
            assertTrue(
                !currentHandshake.sessionDeferred.isCompleted,
                "the current attempt must still be unaffected after the target's stale reply is" +
                    " correctly attributed to it",
            )
            assertEquals(
                "transport.handshake.message2.staleAttemptIgnored",
                fixture.failures.last().stage,
            )
            assertEquals(
                targetAttemptId.toString(),
                fixture.failures.last().metadata["staleAttemptId"],
                "the target attempt's genuine stale reply must be correctly attributed to it" +
                    " even though an unrelated (decoy) reply was tried against it first",
            )
        }

    @Test
    fun `handleHandshakeMessage2 recognizes a repeat delivery of the same stale message2 without reprocessing it`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("initiator-handshake-local")
            val responderIdentity = LocalIdentity.fromAppId("initiator-handshake-responder")
            val peerId = responderIdentity.peerId
            val fixture = initiatorHandshakeFixture(localIdentity = localIdentity)
            val supersededInitiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val supersededHandshake =
                seedPendingInitiatorHandshake(
                    sessionRegistry = fixture.sessionRegistry,
                    peerId = peerId,
                    initiatorManager = supersededInitiatorManager,
                )
            val supersededResponderManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val staleMessage2 =
                supersededResponderManager.processMessage1AndCreateMessage2(
                    responderIdentity.noiseIdentity,
                    supersededHandshake.message1,
                )
            val supersededReservation =
                fixture.sessionRegistry.initiatorHandshakeReservation(peerId)
            assertIs<InitiatorHandshakeReservation.Pending>(supersededReservation)
            val supersededAttemptId = supersededReservation.pendingHandshake.attemptId
            fixture.sessionRegistry.failInitiatorHandshake(
                peerId,
                supersededReservation.pendingHandshake,
            )
            val currentInitiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val currentHandshake =
                seedPendingInitiatorHandshake(
                    sessionRegistry = fixture.sessionRegistry,
                    peerId = peerId,
                    initiatorManager = currentInitiatorManager,
                )
            // First delivery: matched via a trial decrypt against the superseded manager, and the
            // match is cached.
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = staleMessage2)

            // Act
            // The same redundant GATT/L2CAP side-link transports that duplicated the original
            // message2 can redeliver this exact stale frame a second time. Re-invoking the
            // superseded manager here would fail, since its transcript state already advanced
            // irreversibly on the first (successful) trial decrypt -- this delivery must instead
            // be recognized via the cached match.
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = staleMessage2)

            // Assert
            assertTrue(fixture.sentFrames.isEmpty())
            assertTrue(
                !currentHandshake.sessionDeferred.isCompleted,
                "the current attempt must still be unaffected after the repeat stale delivery",
            )
            assertEquals(
                List(2) {
                    RecordedInitiatorHandshakeFailure(
                        peerIdValue = peerId.value,
                        stage = "transport.handshake.message2.staleAttemptIgnored",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata =
                            mapOf(
                                "payloadBytes" to staleMessage2.size.toString(),
                                "staleAttemptId" to supersededAttemptId.toString(),
                                "attemptId" to "0",
                            ),
                    )
                },
                fixture.failures,
            )
        }

    @Test
    fun `handleHandshakeMessage2 fails the pending initiator handshake for a genuinely unmatched message2`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("initiator-handshake-local")
            val responderIdentity = LocalIdentity.fromAppId("initiator-handshake-responder")
            val peerId = responderIdentity.peerId
            val fixture = initiatorHandshakeFixture(localIdentity = localIdentity)
            val currentInitiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val currentHandshake =
                seedPendingInitiatorHandshake(
                    sessionRegistry = fixture.sessionRegistry,
                    peerId = peerId,
                    initiatorManager = currentInitiatorManager,
                )
            // A message2 for a completely unrelated handshake (no superseded attempt exists for
            // this peer at all) -- genuinely corrupt/unexpected, not a stale reply.
            val unrelatedInitiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val unrelatedMessage1 = unrelatedInitiatorManager.createMessage1()
            val unrelatedResponderManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val unrelatedMessage2 =
                unrelatedResponderManager.processMessage1AndCreateMessage2(
                    responderIdentity.noiseIdentity,
                    unrelatedMessage1,
                )

            // Act
            fixture.support.handleHandshakeMessage2(peerId = peerId, payload = unrelatedMessage2)

            // Assert
            assertEquals(
                SessionEstablishmentOutcome.Unreachable,
                currentHandshake.sessionDeferred.await(),
            )
            assertTrue(fixture.sentFrames.isEmpty())
            assertNull(fixture.sessionRegistry.initiatorHandshakeReservation(peerId))
            assertEquals(1, fixture.failures.size)
            assertEquals("transport.handshake.message2.process", fixture.failures.single().stage)
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
    val promotions: MutableList<Pair<String, String>>,
)

private data class SeededPendingInitiatorHandshake(
    val message1: ByteArray,
    val sessionDeferred: CompletableDeferred<SessionEstablishmentOutcome>,
)

private fun initiatorHandshakeFixture(
    localIdentity: LocalIdentity,
    sendDirectWireFrame:
        suspend (PeerId, DirectWireFrame, String, TransportMode?) -> TransportSendResult =
        { _, _, _, _ ->
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
    val promotions = mutableListOf<Pair<String, String>>()
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
                    sendDirectWireFrame = { peerId, frame, action, preferredMode ->
                        sentFrames +=
                            RecordedInitiatorHandshakeSend(
                                peerIdValue = peerId.value,
                                frame = frame,
                                action = action,
                            )
                        sendDirectWireFrame(peerId, frame, action, preferredMode)
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
                    promoteTemporaryPeer = { temporaryPeerId, canonicalPeerId ->
                        promotions += temporaryPeerId.value to canonicalPeerId.value
                    },
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
        promotions = promotions,
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
