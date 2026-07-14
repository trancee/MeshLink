package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.engine.handshake.CreatedEndToEndInitiatorHandshake
import ch.trancee.meshlink.engine.handshake.EndToEndInitiatorHandshakeReservation
import ch.trancee.meshlink.engine.handshake.EndToEndSession
import ch.trancee.meshlink.engine.handshake.MeshEngineEndToEndSessionRegistry
import ch.trancee.meshlink.engine.handshake.PendingEndToEndInitiatorHandshake
import ch.trancee.meshlink.engine.handshake.PendingEndToEndResponderHandshake
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.identity.fromAppId
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

class MeshEngineEndToEndSessionRegistryTest {
    private fun pendingInitiatorHandshake(
        localIdentity: LocalIdentity,
        handshakeId: String = "handshake-1",
    ) =
        PendingEndToEndInitiatorHandshake(
            handshakeId = handshakeId,
            manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider),
            sessionDeferred = CompletableDeferred(),
        )

    private fun pendingResponderHandshake(
        localIdentity: LocalIdentity,
        handshakeId: String = "handshake-1",
    ) =
        PendingEndToEndResponderHandshake(
            handshakeId = handshakeId,
            manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider),
        )

    @Test
    fun `initiator handshake reservation returns null when no factory is supplied`() =
        runBlocking<Unit> {
            // Arrange
            val registry = MeshEngineEndToEndSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")

            // Act
            val reservation = registry.initiatorHandshakeReservation(peerId)

            // Assert
            assertNull(reservation)
        }

    @Test
    fun `initiator handshake reservation creates and stores a pending handshake`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("e2e-registry-created-test")
            val registry = MeshEngineEndToEndSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val pendingHandshake =
                PendingEndToEndInitiatorHandshake(
                    handshakeId = "handshake-1",
                    manager = manager,
                    sessionDeferred = CompletableDeferred(),
                )

            // Act
            val reservation =
                registry.initiatorHandshakeReservation(peerId) {
                    CreatedEndToEndInitiatorHandshake(
                        pendingHandshake = pendingHandshake,
                        message1Frame =
                            WireFrame.EndToEndHandshakeMessage1(
                                route =
                                    WireFrame.EndToEndHandshakeRoute(
                                        handshakeId = "handshake-1",
                                        originPeerId = localIdentity.peerId,
                                        destinationPeerId = peerId,
                                    ),
                                payload = manager.createMessage1(),
                            ),
                    )
                }

            // Assert
            assertTrue(reservation is EndToEndInitiatorHandshakeReservation.Created)
            assertSame(pendingHandshake, reservation.pendingHandshake)
            val pendingReservation = registry.initiatorHandshakeReservation(peerId)
            assertTrue(pendingReservation is EndToEndInitiatorHandshakeReservation.Pending)
            assertSame(pendingHandshake, pendingReservation.pendingHandshake)
        }

    @Test
    fun `completing an initiator handshake establishes the session and clears pending state`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("e2e-registry-complete-initiator-test")
            val registry = MeshEngineEndToEndSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val pendingHandshake = pendingInitiatorHandshake(localIdentity)
            val session = EndToEndSession(ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 })
            registry.initiatorHandshakeReservation(peerId) {
                CreatedEndToEndInitiatorHandshake(
                    pendingHandshake = pendingHandshake,
                    message1Frame =
                        WireFrame.EndToEndHandshakeMessage1(
                            route =
                                WireFrame.EndToEndHandshakeRoute(
                                    handshakeId = "handshake-1",
                                    originPeerId = localIdentity.peerId,
                                    destinationPeerId = peerId,
                                ),
                            payload = ByteArray(0),
                        ),
                )
            }

            // Act
            val completed = registry.completeInitiatorHandshake(peerId, pendingHandshake, session)
            val reservation = registry.initiatorHandshakeReservation(peerId)

            // Assert
            assertTrue(completed)
            assertTrue(reservation is EndToEndInitiatorHandshakeReservation.Established)
            assertSame(session, reservation.session)
            assertSame(session, registry.session(peerId))
        }

    @Test
    fun `completing an initiator handshake with a stale pending handshake fails`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("e2e-registry-stale-complete-test")
            val registry = MeshEngineEndToEndSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val stalePendingHandshake = pendingInitiatorHandshake(localIdentity)
            val session = EndToEndSession(ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 })

            // Act
            val completed =
                registry.completeInitiatorHandshake(peerId, stalePendingHandshake, session)

            // Assert
            assertFalse(completed)
            assertNull(registry.session(peerId))
        }

    @Test
    fun `failing an initiator handshake removes the pending state`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("e2e-registry-fail-initiator-test")
            val registry = MeshEngineEndToEndSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val pendingHandshake = pendingInitiatorHandshake(localIdentity)
            registry.initiatorHandshakeReservation(peerId) {
                CreatedEndToEndInitiatorHandshake(
                    pendingHandshake = pendingHandshake,
                    message1Frame =
                        WireFrame.EndToEndHandshakeMessage1(
                            route =
                                WireFrame.EndToEndHandshakeRoute(
                                    handshakeId = "handshake-1",
                                    originPeerId = localIdentity.peerId,
                                    destinationPeerId = peerId,
                                ),
                            payload = ByteArray(0),
                        ),
                )
            }

            // Act
            val failed = registry.failInitiatorHandshake(peerId, pendingHandshake)

            // Assert
            assertTrue(failed)
            assertNull(registry.initiatorHandshakeReservation(peerId))
        }

    @Test
    fun `failing an initiator handshake with a stale pending handshake is a no-op`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("e2e-registry-stale-fail-test")
            val registry = MeshEngineEndToEndSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val stalePendingHandshake = pendingInitiatorHandshake(localIdentity)

            // Act
            val failed = registry.failInitiatorHandshake(peerId, stalePendingHandshake)

            // Assert
            assertFalse(failed)
        }

    @Test
    fun `storing and completing a pending responder handshake establishes the session`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("e2e-registry-responder-test")
            val registry = MeshEngineEndToEndSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val pendingHandshake = pendingResponderHandshake(localIdentity)
            val session = EndToEndSession(ByteArray(32) { 0x05 }, ByteArray(32) { 0x06 })

            // Act
            registry.storePendingResponderHandshake(peerId, pendingHandshake)
            val storedPendingHandshake = registry.pendingResponderHandshake(peerId)
            val completed = registry.completeResponderHandshake(peerId, pendingHandshake, session)

            // Assert
            assertSame(pendingHandshake, storedPendingHandshake)
            assertTrue(completed)
            assertSame(session, registry.session(peerId))
            assertNull(registry.pendingResponderHandshake(peerId))
        }

    @Test
    fun `completing a responder handshake with a stale pending handshake fails`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("e2e-registry-stale-responder-test")
            val registry = MeshEngineEndToEndSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val stalePendingHandshake = pendingResponderHandshake(localIdentity)
            val session = EndToEndSession(ByteArray(32) { 0x07 }, ByteArray(32) { 0x08 })

            // Act
            val completed =
                registry.completeResponderHandshake(peerId, stalePendingHandshake, session)

            // Assert
            assertFalse(completed)
            assertNull(registry.session(peerId))
        }

    @Test
    fun `removing a pending responder handshake without a current handshake returns null`() =
        runBlocking<Unit> {
            // Arrange
            val registry = MeshEngineEndToEndSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")

            // Act
            val removed = registry.removePendingResponderHandshake(peerId)

            // Assert
            assertNull(removed)
        }

    @Test
    fun `removing a pending responder handshake with a mismatched instance returns null`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("e2e-registry-mismatch-remove-test")
            val registry = MeshEngineEndToEndSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val storedPendingHandshake = pendingResponderHandshake(localIdentity)
            val otherPendingHandshake = pendingResponderHandshake(localIdentity)
            registry.storePendingResponderHandshake(peerId, storedPendingHandshake)

            // Act
            val removed = registry.removePendingResponderHandshake(peerId, otherPendingHandshake)

            // Assert
            assertNull(removed)
            assertSame(storedPendingHandshake, registry.pendingResponderHandshake(peerId))
        }

    @Test
    fun `removing a pending responder handshake without specifying an instance always removes it`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("e2e-registry-unconditional-remove-test")
            val registry = MeshEngineEndToEndSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val storedPendingHandshake = pendingResponderHandshake(localIdentity)
            registry.storePendingResponderHandshake(peerId, storedPendingHandshake)

            // Act
            val removed = registry.removePendingResponderHandshake(peerId)

            // Assert
            assertSame(storedPendingHandshake, removed)
            assertNull(registry.pendingResponderHandshake(peerId))
        }

    @Test
    fun `clearing a peer removes its session and pending handshakes`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("e2e-registry-clear-peer-test")
            val registry = MeshEngineEndToEndSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val pendingInitiator = pendingInitiatorHandshake(localIdentity)
            val pendingResponder = pendingResponderHandshake(localIdentity)
            registry.initiatorHandshakeReservation(peerId) {
                CreatedEndToEndInitiatorHandshake(
                    pendingHandshake = pendingInitiator,
                    message1Frame =
                        WireFrame.EndToEndHandshakeMessage1(
                            route =
                                WireFrame.EndToEndHandshakeRoute(
                                    handshakeId = "handshake-1",
                                    originPeerId = localIdentity.peerId,
                                    destinationPeerId = peerId,
                                ),
                            payload = ByteArray(0),
                        ),
                )
            }
            registry.storePendingResponderHandshake(peerId, pendingResponder)

            // Act
            registry.clearPeer(peerId)

            // Assert
            assertNull(registry.session(peerId))
            assertNull(registry.pendingResponderHandshake(peerId))
            assertNull(registry.initiatorHandshakeReservation(peerId))
        }

    @Test
    fun `clearing the whole registry returns the outstanding pending initiator handshakes`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("e2e-registry-clear-all-test")
            val registry = MeshEngineEndToEndSessionRegistry()
            val peerId = PeerId("00112233445566778899aabb")
            val pendingInitiator = pendingInitiatorHandshake(localIdentity)
            registry.initiatorHandshakeReservation(peerId) {
                CreatedEndToEndInitiatorHandshake(
                    pendingHandshake = pendingInitiator,
                    message1Frame =
                        WireFrame.EndToEndHandshakeMessage1(
                            route =
                                WireFrame.EndToEndHandshakeRoute(
                                    handshakeId = "handshake-1",
                                    originPeerId = localIdentity.peerId,
                                    destinationPeerId = peerId,
                                ),
                            payload = ByteArray(0),
                        ),
                )
            }

            // Act
            val clearedPendingHandshakes = registry.clear()

            // Assert
            assertEquals(1, clearedPendingHandshakes.size)
            assertSame(pendingInitiator, clearedPendingHandshakes.single())
            assertNull(registry.initiatorHandshakeReservation(peerId))
        }
}
