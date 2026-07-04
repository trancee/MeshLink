package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
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
}
