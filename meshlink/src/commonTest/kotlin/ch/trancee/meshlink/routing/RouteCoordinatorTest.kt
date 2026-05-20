package ch.trancee.meshlink.routing

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteCoordinatorTest {
    @Test
    fun `onPeerDisconnected emits one digest per remaining peer even when multiple routes retract`() {
        // Arrange
        val coordinator = RouteCoordinator(PeerId("local"))
        val relayPeerId = PeerId("relay")
        val observerPeerId = PeerId("observer")

        coordinator.onPeerConnected(peerId = relayPeerId, trustRecord = trustRecord(relayPeerId, 1))
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
            ed25519PublicKey = repeatedByteArray(seed),
            x25519PublicKey = repeatedByteArray(seed + 50),
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
            metric = 1,
            seqNo = seqNo,
            feasibilityMetric = 1,
            destinationEd25519PublicKey = repeatedByteArray(seed),
            destinationX25519PublicKey = repeatedByteArray(seed + 25),
        )
    }

    private fun repeatedByteArray(seed: Int): ByteArray {
        return ByteArray(32) { index -> ((seed + index) and 0xFF).toByte() }
    }
}
