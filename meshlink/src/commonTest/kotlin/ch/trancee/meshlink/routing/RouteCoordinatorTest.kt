package ch.trancee.meshlink.routing

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RouteCoordinatorTest {
    @Test
    fun `clearConnectedPeers returns empty when the coordinator is already empty`() =
        runBlocking<Unit> {
            // Arrange
            val coordinator = RouteCoordinator(PeerId("local"))

            // Act
            val mutation = coordinator.clearConnectedPeers()

            // Assert
            assertEquals(0, mutation.advertisements.size)
            assertEquals(0, mutation.routeChanges.size)
            assertEquals(0L, coordinator.topologyVersion.value)
        }

    @Test
    fun `clearConnectedPeers removes direct and relayed routes and advances topology`() =
        runBlocking<Unit> {
            // Arrange
            val coordinator = RouteCoordinator(PeerId("local"))
            val relayPeerId = PeerId("relay")
            val observerPeerId = PeerId("observer")
            val destinationPeerId = PeerId("remote-1")
            coordinator.onPeerConnected(
                peerId = relayPeerId,
                trustRecord = trustRecord(relayPeerId, 1),
            )
            coordinator.onPeerConnected(
                peerId = observerPeerId,
                trustRecord = trustRecord(observerPeerId, 2),
            )
            coordinator.onRouteUpdate(
                fromPeerId = relayPeerId,
                update =
                    routeUpdate(
                        destinationPeerId = destinationPeerId,
                        relayPeerId = relayPeerId,
                        seqNo = 5L,
                    ),
            )
            val topologyBeforeClear = coordinator.topologyVersion.value

            // Act
            val mutation = coordinator.clearConnectedPeers()

            // Assert
            assertEquals(0, mutation.advertisements.size)
            assertEquals(3, mutation.routeChanges.size)
            assertEquals(null, coordinator.routeFor(relayPeerId))
            assertEquals(null, coordinator.routeFor(observerPeerId))
            assertEquals(null, coordinator.routeFor(destinationPeerId))
            assertEquals(topologyBeforeClear + 1L, coordinator.topologyVersion.value)
        }

    @Test
    fun `onRouteUpdate ignores routes that target the local peer`() =
        runBlocking<Unit> {
            // Arrange
            val localPeerId = PeerId("local")
            val coordinator = RouteCoordinator(localPeerId)
            val relayPeerId = PeerId("relay")
            coordinator.onPeerConnected(
                peerId = relayPeerId,
                trustRecord = trustRecord(relayPeerId, 1),
            )

            // Act
            val mutation =
                coordinator.onRouteUpdate(
                    fromPeerId = relayPeerId,
                    update =
                        routeUpdate(
                            destinationPeerId = localPeerId,
                            relayPeerId = relayPeerId,
                            seqNo = 2L,
                        ),
                )

            // Assert
            assertEquals(0, mutation.advertisements.size)
            assertEquals(0, mutation.routeChanges.size)
            assertEquals(null, coordinator.routeFor(localPeerId))
            assertEquals(relayPeerId.value, coordinator.routeFor(relayPeerId)?.nextHopPeerId?.value)
        }

    @Test
    fun `onRouteUpdate keeps a direct route when a relay only offers an equal cost stale path`() =
        runBlocking<Unit> {
            // Arrange
            val coordinator = RouteCoordinator(PeerId("local"))
            val directPeerId = PeerId("direct-peer")
            val relayPeerId = PeerId("relay")
            coordinator.onPeerConnected(
                peerId = directPeerId,
                trustRecord = trustRecord(directPeerId, 1),
            )
            coordinator.onPeerConnected(
                peerId = relayPeerId,
                trustRecord = trustRecord(relayPeerId, 2),
            )

            // Act
            val mutation =
                coordinator.onRouteUpdate(
                    fromPeerId = relayPeerId,
                    update =
                        WireFrame.RouteUpdate(
                            destinationPeerId = directPeerId,
                            nextHopPeerId = relayPeerId,
                            metrics =
                                WireFrame.RouteUpdateMetrics(
                                    metric = 0,
                                    seqNo = 1L,
                                    feasibilityMetric = 0,
                                ),
                            publicKeys =
                                WireFrame.RouteUpdatePublicKeys(
                                    destinationEd25519PublicKey = repeatedByteArray(7),
                                    destinationX25519PublicKey = repeatedByteArray(8),
                                ),
                        ),
                )

            // Assert
            assertEquals(0, mutation.advertisements.size)
            assertEquals(0, mutation.routeChanges.size)
            val route = coordinator.routeFor(directPeerId)
            assertEquals(directPeerId.value, route?.nextHopPeerId?.value)
            assertTrue(route?.isDirect == true)
        }

    @Test
    fun `onPeerConnected advertises the new direct route to observers and existing routes to the newcomer`() =
        runBlocking<Unit> {
            // Arrange
            val coordinator = RouteCoordinator(PeerId("local"))
            val relayPeerId = PeerId("relay")
            val observerPeerId = PeerId("observer")
            val newcomerPeerId = PeerId("newcomer")
            val destinationPeerId = PeerId("remote-1")
            coordinator.onPeerConnected(
                peerId = relayPeerId,
                trustRecord = trustRecord(relayPeerId, 1),
            )
            coordinator.onPeerConnected(
                peerId = observerPeerId,
                trustRecord = trustRecord(observerPeerId, 2),
            )
            coordinator.onRouteUpdate(
                fromPeerId = relayPeerId,
                update =
                    routeUpdate(
                        destinationPeerId = destinationPeerId,
                        relayPeerId = relayPeerId,
                        seqNo = 5L,
                    ),
            )

            // Act
            val mutation =
                coordinator.onPeerConnected(newcomerPeerId, trustRecord(newcomerPeerId, 3))

            // Assert
            val newcomerAdvertisements =
                mutation.advertisements.filter { advertisement ->
                    advertisement.targetPeerId.value == newcomerPeerId.value
                }
            val observerAdvertisements =
                mutation.advertisements.filter { advertisement ->
                    advertisement.targetPeerId.value == observerPeerId.value
                }
            assertEquals(4, newcomerAdvertisements.size)
            assertEquals(
                1,
                newcomerAdvertisements.count { advertisement ->
                    advertisement.frame is WireFrame.RouteDigest
                },
            )
            assertEquals(
                3,
                newcomerAdvertisements.count { advertisement ->
                    advertisement.frame is WireFrame.RouteUpdate
                },
            )
            assertEquals(2, observerAdvertisements.size)
            assertEquals(
                1,
                observerAdvertisements.count { advertisement ->
                    advertisement.frame is WireFrame.RouteDigest
                },
            )
            assertEquals(
                1,
                observerAdvertisements.count { advertisement ->
                    advertisement.frame is WireFrame.RouteUpdate
                },
            )
            assertEquals(1, mutation.routeChanges.size)
            assertTrue(mutation.routeChanges.single() is RouteSelectionChange.Available)
        }

    @Test
    fun `onRouteDigest mismatch pushes full table to advertising peer`() =
        runBlocking<Unit> {
            // Arrange
            val coordinator = RouteCoordinator(PeerId("local"))
            val relayPeerId = PeerId("relay")
            val observerPeerId = PeerId("observer")
            val remotePeerId = PeerId("remote-1")
            coordinator.onPeerConnected(
                peerId = relayPeerId,
                trustRecord = trustRecord(relayPeerId, 1),
            )
            coordinator.onPeerConnected(
                peerId = observerPeerId,
                trustRecord = trustRecord(observerPeerId, 2),
            )
            coordinator.onRouteUpdate(
                fromPeerId = relayPeerId,
                update = routeUpdate(remotePeerId, relayPeerId, seqNo = 5L),
            )

            // Act
            val mutation =
                coordinator.onRouteDigest(
                    fromPeerId = observerPeerId,
                    frame = WireFrame.RouteDigest(observerPeerId, byteArrayOf(9, 9, 9, 9)),
                )

            // Assert
            assertEquals(0, mutation.routeChanges.size)
            assertEquals(
                2,
                mutation.advertisements.count { advertisement ->
                    advertisement.targetPeerId.value == observerPeerId.value &&
                        advertisement.frame is WireFrame.RouteUpdate
                },
            )
            assertEquals(
                1,
                mutation.advertisements.count { advertisement ->
                    advertisement.targetPeerId.value == observerPeerId.value &&
                        advertisement.frame is WireFrame.RouteDigest
                },
            )
        }

    @Test
    fun `onRouteUpdate keeps direct route when relay reports a higher seqno`() =
        runBlocking<Unit> {
            // Arrange
            val coordinator = RouteCoordinator(PeerId("local"))
            val destination = PeerId("destination")
            val relay = PeerId("relay")
            coordinator.onPeerConnected(
                peerId = destination,
                trustRecord = trustRecord(destination, 1),
            )
            coordinator.onPeerConnected(peerId = relay, trustRecord = trustRecord(relay, 2))
            coordinator.onRouteUpdate(
                fromPeerId = destination,
                update = routeUpdate(destination, destination, seqNo = 3L),
            )

            // Act
            val mutation =
                coordinator.onRouteUpdate(
                    fromPeerId = relay,
                    update = routeUpdate(destination, relay, seqNo = 9L),
                )

            // Assert
            assertEquals(0, mutation.advertisements.size)
            assertEquals(0, mutation.routeChanges.size)
            val route = checkNotNull(coordinator.routeFor(destination))
            assertEquals(destination.value, route.nextHopPeerId.value)
            assertTrue(route.isDirect)
            assertEquals(3L, route.seqNo)
        }

    @Test
    fun `onRouteUpdate ignores infeasible updates from a different relay`() =
        runBlocking<Unit> {
            // Arrange
            val coordinator = RouteCoordinator(PeerId("local"))
            val relayA = PeerId("relay-a")
            val relayB = PeerId("relay-b")
            val destination = PeerId("remote-1")
            coordinator.onPeerConnected(peerId = relayA, trustRecord = trustRecord(relayA, 1))
            coordinator.onPeerConnected(peerId = relayB, trustRecord = trustRecord(relayB, 2))
            coordinator.onRouteUpdate(
                fromPeerId = relayA,
                update =
                    routeUpdate(destinationPeerId = destination, relayPeerId = relayA, seqNo = 5L),
            )

            // Act
            val mutation =
                coordinator.onRouteUpdate(
                    fromPeerId = relayB,
                    update =
                        routeUpdate(
                            destinationPeerId = destination,
                            relayPeerId = relayB,
                            seqNo = 4L,
                        ),
                )

            // Assert
            assertEquals(0, mutation.advertisements.size)
            assertEquals(0, mutation.routeChanges.size)
            assertEquals(relayA.value, coordinator.routeFor(destination)?.nextHopPeerId?.value)
        }

    @Test
    fun `onRouteRetraction ignores a relay that is not the selected next hop`() =
        runBlocking<Unit> {
            // Arrange
            val coordinator = RouteCoordinator(PeerId("local"))
            val relayA = PeerId("relay-a")
            val relayB = PeerId("relay-b")
            val destination = PeerId("remote-2")
            coordinator.onPeerConnected(peerId = relayA, trustRecord = trustRecord(relayA, 1))
            coordinator.onPeerConnected(peerId = relayB, trustRecord = trustRecord(relayB, 2))
            coordinator.onRouteUpdate(
                fromPeerId = relayA,
                update =
                    routeUpdate(destinationPeerId = destination, relayPeerId = relayA, seqNo = 5L),
            )

            // Act
            val mutation =
                coordinator.onRouteRetraction(
                    fromPeerId = relayB,
                    retraction =
                        WireFrame.RouteRetraction(destinationPeerId = destination, seqNo = 5L),
                )

            // Assert
            assertEquals(0, mutation.advertisements.size)
            assertEquals(0, mutation.routeChanges.size)
            assertEquals(relayA.value, coordinator.routeFor(destination)?.nextHopPeerId?.value)
        }

    @Test
    fun `onPeerDisconnected emits one digest per remaining peer even when multiple routes retract`() =
        runBlocking<Unit> {
            // Arrange
            val coordinator = RouteCoordinator(PeerId("local"))
            val relayPeerId = PeerId("relay")
            val observerPeerId = PeerId("observer")

            coordinator.onPeerConnected(
                peerId = relayPeerId,
                trustRecord = trustRecord(relayPeerId, 1),
            )
            coordinator.onPeerConnected(
                peerId = observerPeerId,
                trustRecord = trustRecord(observerPeerId, 2),
            )
            coordinator.onRouteUpdate(
                fromPeerId = relayPeerId,
                update =
                    routeUpdate(
                        destinationPeerId = PeerId("remote-1"),
                        relayPeerId = relayPeerId,
                        seqNo = 1L,
                    ),
            )
            coordinator.onRouteUpdate(
                fromPeerId = relayPeerId,
                update =
                    routeUpdate(
                        destinationPeerId = PeerId("remote-2"),
                        relayPeerId = relayPeerId,
                        seqNo = 2L,
                    ),
            )

            // Act
            val mutation = coordinator.onPeerDisconnected(relayPeerId)
            val observerAdvertisements =
                mutation.advertisements.filter { advertisement ->
                    advertisement.targetPeerId.value == observerPeerId.value
                }

            // Assert
            assertEquals(4, observerAdvertisements.size)
            assertEquals(
                1,
                observerAdvertisements.count { advertisement ->
                    advertisement.frame is WireFrame.RouteDigest
                },
            )
            assertEquals(
                3,
                observerAdvertisements.count { advertisement ->
                    advertisement.frame is WireFrame.RouteRetraction
                },
            )
            assertEquals(3, mutation.routeChanges.size)
        }

    private fun trustRecord(peerId: PeerId, seed: Int): TrustRecord {
        return TrustRecord(
            peerIdValue = peerId.value,
            identityFingerprintBytes = repeatedByteArray(seed + 100),
            firstSeenAtEpochMillis = seed.toLong(),
            lastVerifiedAtEpochMillis = seed.toLong(),
            publicKeys =
                TrustPublicKeys(
                    ed25519PublicKey = repeatedByteArray(seed),
                    x25519PublicKey = repeatedByteArray(seed + 50),
                ),
        )
    }

    private fun routeUpdate(
        destinationPeerId: PeerId,
        relayPeerId: PeerId,
        seqNo: Long,
    ): WireFrame.RouteUpdate {
        val seed = destinationPeerId.value.last().code
        return WireFrame.RouteUpdate(
            destinationPeerId = destinationPeerId,
            nextHopPeerId = relayPeerId,
            metrics =
                WireFrame.RouteUpdateMetrics(metric = 1, seqNo = seqNo, feasibilityMetric = 1),
            publicKeys =
                WireFrame.RouteUpdatePublicKeys(
                    destinationEd25519PublicKey = repeatedByteArray(seed),
                    destinationX25519PublicKey = repeatedByteArray(seed + 25),
                ),
        )
    }

    private fun repeatedByteArray(seed: Int): ByteArray {
        return ByteArray(32) { index -> ((seed + index) and 0xFF).toByte() }
    }
}
