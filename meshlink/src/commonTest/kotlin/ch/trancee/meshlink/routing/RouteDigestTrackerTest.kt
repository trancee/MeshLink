package ch.trancee.meshlink.routing

import ch.trancee.meshlink.api.PeerId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class RouteDigestTrackerTest {
    @Test
    fun `routeDigestFrame stays stable when equivalent routes are inserted in different orders`() {
        // Arrange
        val localPeerId = PeerId("local")
        val routeA =
            routeEntry(
                destinationPeerIdValue = "peer-a",
                nextHopPeerIdValue = "relay-a",
                seqNo = 1L,
            )
        val routeB =
            routeEntry(
                destinationPeerIdValue = "peer-b",
                nextHopPeerIdValue = "relay-b",
                seqNo = 2L,
            )
        val firstTracker = RouteDigestTracker()
        val secondTracker = RouteDigestTracker()

        // Act
        firstTracker.upsert(routeB)
        firstTracker.upsert(routeA)
        secondTracker.upsert(routeA)
        secondTracker.upsert(routeB)
        val firstDigest = firstTracker.routeDigestFrame(localPeerId).digest
        val secondDigest = secondTracker.routeDigestFrame(localPeerId).digest

        // Assert
        assertContentEquals(firstDigest, secondDigest)
    }

    @Test
    fun `routeDigestFrame ignores next-hop and metric differences for the same destination and seqNo`() {
        // Arrange
        val localPeerId = PeerId("local")
        val firstView =
            routeEntry(
                destinationPeerIdValue = "peer-a",
                nextHopPeerIdValue = "relay-a",
                seqNo = 3L,
                metric = 1,
            )
        val secondView =
            routeEntry(
                destinationPeerIdValue = "peer-a",
                nextHopPeerIdValue = "relay-b",
                seqNo = 3L,
                metric = 9,
            )
        val firstTracker = RouteDigestTracker()
        val secondTracker = RouteDigestTracker()

        // Act
        firstTracker.upsert(firstView)
        secondTracker.upsert(secondView)
        val firstDigest = firstTracker.routeDigestFrame(localPeerId).digest
        val secondDigest = secondTracker.routeDigestFrame(localPeerId).digest

        // Assert
        assertContentEquals(firstDigest, secondDigest)
    }

    @Test
    fun `routeDigestFrame changes when a route for the same destination is replaced`() {
        // Arrange
        val localPeerId = PeerId("local")
        val originalRoute =
            routeEntry(
                destinationPeerIdValue = "peer-a",
                nextHopPeerIdValue = "relay-a",
                seqNo = 1L,
            )
        val replacementRoute =
            routeEntry(
                destinationPeerIdValue = "peer-a",
                nextHopPeerIdValue = "relay-b",
                seqNo = 3L,
            )
        val companionRoute =
            routeEntry(
                destinationPeerIdValue = "peer-b",
                nextHopPeerIdValue = "relay-c",
                seqNo = 2L,
            )
        val tracker = RouteDigestTracker()
        val expectedTracker = RouteDigestTracker()
        tracker.upsert(originalRoute)
        tracker.upsert(companionRoute)
        val originalDigest = tracker.routeDigestFrame(localPeerId).digest

        // Act
        tracker.upsert(replacementRoute)
        val updatedDigest = tracker.routeDigestFrame(localPeerId).digest
        expectedTracker.upsert(replacementRoute)
        expectedTracker.upsert(companionRoute)
        val expectedDigest = expectedTracker.routeDigestFrame(localPeerId).digest

        // Assert
        assertFalse(originalDigest.contentEquals(updatedDigest))
        assertContentEquals(expectedDigest, updatedDigest)
    }

    @Test
    fun `routeDigestFrame after removing a route matches a tracker built from the survivors`() {
        // Arrange
        val localPeerId = PeerId("local")
        val routeA =
            routeEntry(
                destinationPeerIdValue = "peer-a",
                nextHopPeerIdValue = "relay-a",
                seqNo = 1L,
            )
        val routeB =
            routeEntry(
                destinationPeerIdValue = "peer-b",
                nextHopPeerIdValue = "relay-b",
                seqNo = 2L,
            )
        val routeC =
            routeEntry(
                destinationPeerIdValue = "peer-c",
                nextHopPeerIdValue = "relay-c",
                seqNo = 3L,
            )
        val tracker = RouteDigestTracker()
        val expectedTracker = RouteDigestTracker()
        tracker.upsert(routeA)
        tracker.upsert(routeB)
        tracker.upsert(routeC)

        // Act
        tracker.remove(routeB.destinationPeerId.value)
        val digestAfterRemoval = tracker.routeDigestFrame(localPeerId).digest
        expectedTracker.upsert(routeA)
        expectedTracker.upsert(routeC)
        val expectedDigest = expectedTracker.routeDigestFrame(localPeerId).digest

        // Assert
        assertContentEquals(expectedDigest, digestAfterRemoval)
    }
}

private fun routeEntry(
    destinationPeerIdValue: String,
    nextHopPeerIdValue: String,
    seqNo: Long,
    metric: Int = 1,
): RouteEntry {
    val seed = destinationPeerIdValue.last().code
    return RouteEntry(
        destinationPeerId = PeerId(destinationPeerIdValue),
        nextHopPeerId = PeerId(nextHopPeerIdValue),
        metrics =
            RouteMetrics(
                metric = metric,
                seqNo = seqNo,
                feasibilityMetric = metric,
                isDirect = false,
            ),
        publicKeys =
            RoutePublicKeys(
                ed25519PublicKey = repeatedByteArray(seed),
                x25519PublicKey = repeatedByteArray(seed + 25),
            ),
    )
}

private fun repeatedByteArray(seed: Int): ByteArray {
    return ByteArray(32) { index -> ((seed + index) and 0xFF).toByte() }
}
