package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.engine.handshake.CreatedInitiatorHandshake
import ch.trancee.meshlink.engine.handshake.InitiatorHandshakeReservation
import ch.trancee.meshlink.engine.handshake.MeshEngineSessionRegistry
import ch.trancee.meshlink.engine.internal.HopSession
import ch.trancee.meshlink.engine.internal.PendingInitiatorHandshake
import ch.trancee.meshlink.engine.internal.PendingResponderHandshake
import ch.trancee.meshlink.identity.LocalIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeshEngineSessionRegistryTest {
    @Test
    fun `rebind pending initiator handshake moves it to the canonical peer id`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("session-registry-initiator-rebind-test")
            val sessionRegistry = MeshEngineSessionRegistry()
            val temporaryPeerId = PeerId("bt-aabbccddeeff")
            val canonicalPeerId = PeerId("00112233445566778899aabb")
            val initiatorManager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val pendingHandshake =
                PendingInitiatorHandshake(
                    manager = initiatorManager,
                    sessionDeferred = kotlinx.coroutines.CompletableDeferred(),
                )
            val reservation =
                sessionRegistry.initiatorHandshakeReservation(temporaryPeerId) {
                    CreatedInitiatorHandshake(
                        pendingHandshake = pendingHandshake,
                        message1 = initiatorManager.createMessage1(),
                    )
                }
            assertTrue(reservation is InitiatorHandshakeReservation.Created)

            // Act
            val rebound =
                sessionRegistry.rebindPendingInitiatorHandshake(
                    fromPeerId = temporaryPeerId,
                    toPeerId = canonicalPeerId,
                    pendingHandshake = pendingHandshake,
                )

            // Assert
            assertTrue(rebound)
            val reboundReservation = sessionRegistry.initiatorHandshakeReservation(canonicalPeerId)
            assertTrue(reboundReservation is InitiatorHandshakeReservation.Pending)
            assertSame(pendingHandshake, reboundReservation.pendingHandshake)
            assertNull(sessionRegistry.initiatorHandshakeReservation(temporaryPeerId))
        }

    @Test
    fun `rebind pending responder handshake moves it to the canonical peer id`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("session-registry-rebind-test")
            val sessionRegistry = MeshEngineSessionRegistry()
            val temporaryPeerId = PeerId("bt-aabbccddeeff")
            val canonicalPeerId = PeerId("00112233445566778899aabb")
            val pendingHandshake =
                PendingResponderHandshake(NoiseXXHandshakeManager(localIdentity.cryptoProvider))
            sessionRegistry.storePendingResponderHandshake(
                temporaryPeerId,
                pendingHandshake,
                byteArrayOf(),
            )

            // Act
            val rebound =
                sessionRegistry.rebindPendingResponderHandshake(
                    fromPeerId = temporaryPeerId,
                    toPeerId = canonicalPeerId,
                    pendingHandshake = pendingHandshake,
                )

            // Assert
            assertTrue(rebound)
            assertNull(sessionRegistry.pendingResponderHandshake(temporaryPeerId))
            assertSame(pendingHandshake, sessionRegistry.pendingResponderHandshake(canonicalPeerId))
        }

    @Test
    fun `hop session lookup follows temporary peer aliases after responder rebind`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("session-registry-alias-test")
            val sessionRegistry = MeshEngineSessionRegistry()
            val temporaryPeerId = PeerId("bt-aabbccddeeff")
            val canonicalPeerId = PeerId("00112233445566778899aabb")
            val pendingHandshake =
                PendingResponderHandshake(NoiseXXHandshakeManager(localIdentity.cryptoProvider))
            val establishedSession = HopSession(ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 })
            sessionRegistry.storePendingResponderHandshake(
                temporaryPeerId,
                pendingHandshake,
                byteArrayOf(),
            )
            sessionRegistry.rebindPendingResponderHandshake(
                fromPeerId = temporaryPeerId,
                toPeerId = canonicalPeerId,
                pendingHandshake = pendingHandshake,
            )
            sessionRegistry.completeResponderHandshake(
                peerId = canonicalPeerId,
                pendingHandshake = pendingHandshake,
                session = establishedSession,
            )

            // Act
            val resolvedTemporaryPeerId = sessionRegistry.resolvePeerId(temporaryPeerId)
            val resolvedCanonicalPeerId = sessionRegistry.resolvePeerId(canonicalPeerId)
            val temporarySession = sessionRegistry.hopSession(temporaryPeerId)
            val canonicalSession = sessionRegistry.hopSession(canonicalPeerId)

            // Assert
            assertEquals(canonicalPeerId.value, resolvedTemporaryPeerId.value)
            assertEquals(canonicalPeerId.value, resolvedCanonicalPeerId.value)
            assertSame(establishedSession, temporarySession)
            assertSame(establishedSession, canonicalSession)
        }

    @Test
    fun `clearing a canonical peer removes temporary peer aliases`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("session-registry-clear-alias-test")
            val sessionRegistry = MeshEngineSessionRegistry()
            val temporaryPeerId = PeerId("bt-aabbccddeeff")
            val canonicalPeerId = PeerId("00112233445566778899aabb")
            val pendingHandshake =
                PendingResponderHandshake(NoiseXXHandshakeManager(localIdentity.cryptoProvider))
            val establishedSession = HopSession(ByteArray(32) { 0x03 }, ByteArray(32) { 0x04 })
            sessionRegistry.storePendingResponderHandshake(
                temporaryPeerId,
                pendingHandshake,
                byteArrayOf(),
            )
            sessionRegistry.rebindPendingResponderHandshake(
                fromPeerId = temporaryPeerId,
                toPeerId = canonicalPeerId,
                pendingHandshake = pendingHandshake,
            )
            sessionRegistry.completeResponderHandshake(
                peerId = canonicalPeerId,
                pendingHandshake = pendingHandshake,
                session = establishedSession,
            )

            // Act
            sessionRegistry.clearPeer(canonicalPeerId)
            val resolvedTemporaryPeerId = sessionRegistry.resolvePeerId(temporaryPeerId)
            val clearedTemporarySession = sessionRegistry.hopSession(temporaryPeerId)
            val clearedCanonicalSession = sessionRegistry.hopSession(canonicalPeerId)

            // Assert
            assertEquals(temporaryPeerId.value, resolvedTemporaryPeerId.value)
            assertNull(clearedTemporarySession)
            assertNull(clearedCanonicalSession)
        }

    @Test
    fun `superseded initiator handshakes are retained bounded to the most recent attempts`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("session-registry-superseded-bound-test")
            val sessionRegistry = MeshEngineSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val attemptCount = 6
            val pendingHandshakes = mutableListOf<PendingInitiatorHandshake>()

            // Act
            // Create and immediately supersede each attempt but the last (still current) one, as
            // a real timeout/pause-resume-interrupted attempt would be -- a new reservation can
            // only be Created once the previous attempt for the peer has been failed/removed.
            repeat(attemptCount) { index ->
                lateinit var pendingHandshake: PendingInitiatorHandshake
                val reservation =
                    sessionRegistry.initiatorHandshakeReservation(peerId) { attemptId ->
                        val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
                        pendingHandshake =
                            PendingInitiatorHandshake(
                                manager = manager,
                                sessionDeferred = kotlinx.coroutines.CompletableDeferred(),
                                attemptId = attemptId,
                            )
                        CreatedInitiatorHandshake(
                            pendingHandshake = pendingHandshake,
                            message1 = manager.createMessage1(),
                        )
                    }
                assertTrue(reservation is InitiatorHandshakeReservation.Created)
                pendingHandshakes.add(pendingHandshake)
                if (index < attemptCount - 1) {
                    sessionRegistry.failInitiatorHandshake(peerId, pendingHandshake)
                }
            }
            val claimed = sessionRegistry.tryClaimSupersededAttemptsForMessage2(peerId)

            // Assert
            assertEquals(
                4,
                claimed.size,
                "only the most recent MAX_SUPERSEDED_INITIATOR_HANDSHAKES_PER_PEER attempts " +
                    "should be retained",
            )
            assertEquals(
                pendingHandshakes.dropLast(1).takeLast(4),
                claimed,
                "the oldest superseded attempt(s) beyond the bound should have been evicted",
            )
        }

    @Test
    fun `superseded initiator handshake attempts cannot be claimed twice concurrently`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("session-registry-superseded-claim-test")
            val sessionRegistry = MeshEngineSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val pendingHandshake =
                PendingInitiatorHandshake(
                    manager = manager,
                    sessionDeferred = kotlinx.coroutines.CompletableDeferred(),
                )
            sessionRegistry.initiatorHandshakeReservation(peerId) {
                CreatedInitiatorHandshake(
                    pendingHandshake = pendingHandshake,
                    message1 = manager.createMessage1(),
                )
            }
            sessionRegistry.failInitiatorHandshake(peerId, pendingHandshake)

            // Act
            val firstClaim = sessionRegistry.tryClaimSupersededAttemptsForMessage2(peerId)
            val secondClaim = sessionRegistry.tryClaimSupersededAttemptsForMessage2(peerId)

            // Assert
            assertEquals(listOf(pendingHandshake), firstClaim)
            assertTrue(
                secondClaim.isEmpty(),
                "an already-claimed superseded attempt must not be handed out again while still " +
                    "claimed, since a concurrent trial decrypt could otherwise race the same " +
                    "manager instance",
            )
        }

    @Test
    fun `releasing a superseded attempt claim after a failed trial allows it to be reclaimed`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("session-registry-superseded-release-test")
            val sessionRegistry = MeshEngineSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val pendingHandshake =
                PendingInitiatorHandshake(
                    manager = manager,
                    sessionDeferred = kotlinx.coroutines.CompletableDeferred(),
                )
            sessionRegistry.initiatorHandshakeReservation(peerId) {
                CreatedInitiatorHandshake(
                    pendingHandshake = pendingHandshake,
                    message1 = manager.createMessage1(),
                )
            }
            sessionRegistry.failInitiatorHandshake(peerId, pendingHandshake)
            sessionRegistry.tryClaimSupersededAttemptsForMessage2(peerId)

            // Act
            // Simulate an unrelated message2 delivery that was trial-decrypted against this
            // attempt and did not match -- the caller releases the claim instead of recording a
            // match, since NoiseXXHandshakeManager.tryProcessMessage2AndCreateMessage3 leaves the
            // manager unmutated on a failed trial.
            sessionRegistry.releaseSupersededAttemptClaim(peerId, pendingHandshake.attemptId)
            val reclaimed = sessionRegistry.tryClaimSupersededAttemptsForMessage2(peerId)

            // Assert
            assertEquals(
                listOf(pendingHandshake),
                reclaimed,
                "a superseded attempt released after a non-matching trial must remain claimable " +
                    "later, e.g. against its own genuine stale reply arriving after an unrelated " +
                    "frame was tried against it first",
            )
        }

    @Test
    fun `a recorded superseded attempt match is recognized by payload without reclaiming it`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("session-registry-superseded-match-test")
            val sessionRegistry = MeshEngineSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val pendingHandshake =
                PendingInitiatorHandshake(
                    manager = manager,
                    sessionDeferred = kotlinx.coroutines.CompletableDeferred(),
                )
            sessionRegistry.initiatorHandshakeReservation(peerId) {
                CreatedInitiatorHandshake(
                    pendingHandshake = pendingHandshake,
                    message1 = manager.createMessage1(),
                )
            }
            sessionRegistry.failInitiatorHandshake(peerId, pendingHandshake)
            sessionRegistry.tryClaimSupersededAttemptsForMessage2(peerId)
            val stalePayload = byteArrayOf(1, 2, 3)

            // Act
            sessionRegistry.recordSupersededAttemptMatch(
                peerId,
                pendingHandshake.attemptId,
                stalePayload,
            )
            val matchedAttemptId =
                sessionRegistry.previouslyMatchedSupersededAttemptId(peerId, stalePayload)
            val unrelatedPayloadMatch =
                sessionRegistry.previouslyMatchedSupersededAttemptId(peerId, byteArrayOf(9, 9, 9))

            // Assert
            assertEquals(pendingHandshake.attemptId, matchedAttemptId)
            assertNull(unrelatedPayloadMatch)
        }

    @Test
    fun `ending message2 processing releases the claim for a still-pending initiator handshake`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("session-registry-end-processing-test")
            val sessionRegistry = MeshEngineSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val pendingHandshake =
                PendingInitiatorHandshake(
                    manager = manager,
                    sessionDeferred = kotlinx.coroutines.CompletableDeferred(),
                )
            sessionRegistry.initiatorHandshakeReservation(peerId) {
                CreatedInitiatorHandshake(
                    pendingHandshake = pendingHandshake,
                    message1 = manager.createMessage1(),
                )
            }
            assertTrue(sessionRegistry.tryBeginProcessingMessage2(pendingHandshake))
            assertTrue(
                !sessionRegistry.tryBeginProcessingMessage2(pendingHandshake),
                "a second concurrent claim should be rejected while still processing",
            )

            // Act
            sessionRegistry.endProcessingMessage2(pendingHandshake)

            // Assert
            assertTrue(
                sessionRegistry.tryBeginProcessingMessage2(pendingHandshake),
                "a later, genuinely new message2 delivery must be able to claim the still-pending" +
                    " attempt again once the previous claim is released",
            )
        }
}
