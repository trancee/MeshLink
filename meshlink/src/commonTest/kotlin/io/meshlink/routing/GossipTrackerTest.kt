package io.meshlink.routing

import io.meshlink.util.ByteArrayKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GossipTrackerTest {

    private fun key(s: String) = ByteArrayKey(s.encodeToByteArray())

    private fun route(
        destination: String,
        nextHop: String = "hop",
        cost: Double = 1.0,
        sequenceNumber: UInt = 1u,
    ) = RoutingTable.Route(key(destination), key(nextHop), cost, sequenceNumber)

    // 1. New peer gets full exchange
    @Test
    fun newPeerGetsFullExchange() {
        val tracker = GossipTracker()
        val routes = listOf(route("A"), route("B"), route("C"))

        val diff = tracker.computeDiff(key("peer1"), routes)

        assertEquals(routes, diff)
    }

    // 2. Same routes sent twice → empty diff
    @Test
    fun sameRoutesSentTwiceProducesEmptyDiff() {
        val tracker = GossipTracker()
        val routes = listOf(route("A"), route("B"))

        tracker.recordSent(key("peer1"), routes)
        val diff = tracker.computeDiff(key("peer1"), routes)

        assertTrue(diff.isEmpty())
    }

    // 3. Route cost change → only changed route in diff
    @Test
    fun costChangeIncludesOnlyChangedRoute() {
        val tracker = GossipTracker()
        val original = listOf(route("A", cost = 1.0), route("B", cost = 2.0))
        tracker.recordSent(key("peer1"), original)

        val updated = listOf(route("A", cost = 5.0), route("B", cost = 2.0))
        val diff = tracker.computeDiff(key("peer1"), updated)

        assertEquals(1, diff.size)
        assertEquals(key("A"), diff[0].destination)
        assertEquals(5.0, diff[0].cost)
    }

    // 4. Route sequence number bump → route included in diff
    @Test
    fun sequenceNumberBumpIncludesRoute() {
        val tracker = GossipTracker()
        val original = listOf(route("A", sequenceNumber = 1u))
        tracker.recordSent(key("peer1"), original)

        val updated = listOf(route("A", sequenceNumber = 2u))
        val diff = tracker.computeDiff(key("peer1"), updated)

        assertEquals(1, diff.size)
        assertEquals(2u, diff[0].sequenceNumber)
    }

    // 5. New route added → included in diff
    @Test
    fun newRouteAddedIncludedInDiff() {
        val tracker = GossipTracker()
        val original = listOf(route("A"))
        tracker.recordSent(key("peer1"), original)

        val updated = listOf(route("A"), route("B"))
        val diff = tracker.computeDiff(key("peer1"), updated)

        assertEquals(1, diff.size)
        assertEquals(key("B"), diff[0].destination)
    }

    // 6. Route removed from table → withdrawal computed
    @Test
    fun removedRouteProducesWithdrawal() {
        val tracker = GossipTracker()
        val original = listOf(route("A"), route("B"))
        tracker.recordSent(key("peer1"), original)

        val currentDestinations = setOf(key("A"))
        val withdrawals = tracker.computeWithdrawals(key("peer1"), currentDestinations)

        assertEquals(setOf(key("B")), withdrawals)
    }

    // 7. Multiple peers tracked independently
    @Test
    fun multiplePeersTrackedIndependently() {
        val tracker = GossipTracker()
        val routes = listOf(route("A"), route("B"))

        tracker.recordSent(key("peer1"), routes)
        // peer2 has no state yet
        val diff1 = tracker.computeDiff(key("peer1"), routes)
        val diff2 = tracker.computeDiff(key("peer2"), routes)

        assertTrue(diff1.isEmpty())
        assertEquals(routes, diff2)
    }

    // 8. removePeer clears only that peer's state
    @Test
    fun removePeerClearsOnlyThatPeer() {
        val tracker = GossipTracker()
        val routes = listOf(route("A"))

        tracker.recordSent(key("peer1"), routes)
        tracker.recordSent(key("peer2"), routes)
        tracker.removePeer(key("peer1"))

        // peer1 removed → full exchange
        val diff1 = tracker.computeDiff(key("peer1"), routes)
        assertEquals(routes, diff1)

        // peer2 unaffected → empty diff
        val diff2 = tracker.computeDiff(key("peer2"), routes)
        assertTrue(diff2.isEmpty())
    }

    // 9. resetPeer forces full exchange
    @Test
    fun resetPeerForcesFullExchange() {
        val tracker = GossipTracker()
        val routes = listOf(route("A"), route("B"))

        tracker.recordSent(key("peer1"), routes)
        assertTrue(tracker.computeDiff(key("peer1"), routes).isEmpty())

        tracker.resetPeer(key("peer1"))

        val diff = tracker.computeDiff(key("peer1"), routes)
        assertEquals(routes, diff)
    }

    // 10. clear() removes all state
    @Test
    fun clearRemovesAllState() {
        val tracker = GossipTracker()
        val routes = listOf(route("A"))

        tracker.recordSent(key("peer1"), routes)
        tracker.recordSent(key("peer2"), routes)
        tracker.clear()

        assertEquals(0, tracker.trackedPeerCount())
        assertEquals(routes, tracker.computeDiff(key("peer1"), routes))
        assertEquals(routes, tracker.computeDiff(key("peer2"), routes))
    }

    // 11. Mixed scenario: some routes changed, some not, some withdrawn
    @Test
    fun mixedScenarioChangedUnchangedWithdrawn() {
        val tracker = GossipTracker()
        val original = listOf(
            route("A", cost = 1.0, sequenceNumber = 1u),
            route("B", cost = 2.0, sequenceNumber = 1u),
            route("C", cost = 3.0, sequenceNumber = 1u),
        )
        tracker.recordSent(key("peer1"), original)

        // A: unchanged, B: cost changed, C: removed, D: new
        val updated = listOf(
            route("A", cost = 1.0, sequenceNumber = 1u),
            route("B", cost = 5.0, sequenceNumber = 1u),
            route("D", cost = 4.0, sequenceNumber = 1u),
        )

        val diff = tracker.computeDiff(key("peer1"), updated)
        val diffDests = diff.map { it.destination }.toSet()
        assertEquals(setOf(key("B"), key("D")), diffDests)

        val withdrawals = tracker.computeWithdrawals(key("peer1"), setOf(key("A"), key("B"), key("D")))
        assertEquals(setOf(key("C")), withdrawals)
    }

    // 12. Withdrawal not re-sent if peer was already notified
    @Test
    fun withdrawalNotResentIfAlreadyNotified() {
        val tracker = GossipTracker()
        val original = listOf(route("A"), route("B"))
        tracker.recordSent(key("peer1"), original)

        // B is withdrawn — record it as sent with MAX_VALUE cost
        val withdrawal = route("B", cost = Double.MAX_VALUE, sequenceNumber = 2u)
        tracker.recordSent(key("peer1"), listOf(withdrawal))

        // Now B is still absent from current destinations
        val withdrawals = tracker.computeWithdrawals(key("peer1"), setOf(key("A")))
        assertTrue(withdrawals.isEmpty(), "Should not re-withdraw already-notified route")
    }

    // 13. trackedPeerCount accuracy
    @Test
    fun trackedPeerCountAccuracy() {
        val tracker = GossipTracker()

        assertEquals(0, tracker.trackedPeerCount())

        tracker.recordSent(key("peer1"), listOf(route("A")))
        assertEquals(1, tracker.trackedPeerCount())

        tracker.recordSent(key("peer2"), listOf(route("A")))
        assertEquals(2, tracker.trackedPeerCount())

        tracker.removePeer(key("peer1"))
        assertEquals(1, tracker.trackedPeerCount())

        tracker.clear()
        assertEquals(0, tracker.trackedPeerCount())
    }

    // 14. Empty current routes → all previous routes are withdrawals
    @Test
    fun emptyCurrentRoutesProducesAllWithdrawals() {
        val tracker = GossipTracker()
        val original = listOf(route("A"), route("B"), route("C"))
        tracker.recordSent(key("peer1"), original)

        val withdrawals = tracker.computeWithdrawals(key("peer1"), emptySet())
        assertEquals(setOf(key("A"), key("B"), key("C")), withdrawals)
    }

    // 15. Cost change below threshold still included (any change counts)
    @Test
    fun tinyCostChangeStillIncluded() {
        val tracker = GossipTracker()
        val original = listOf(route("A", cost = 1.0))
        tracker.recordSent(key("peer1"), original)

        val updated = listOf(route("A", cost = 1.0000001))
        val diff = tracker.computeDiff(key("peer1"), updated)

        assertEquals(1, diff.size)
        assertEquals(key("A"), diff[0].destination)
    }

    // 16. computeWithdrawals with no prior state returns empty
    @Test
    fun computeWithdrawalsNoPriorStateReturnsEmpty() {
        val tracker = GossipTracker()
        val withdrawals = tracker.computeWithdrawals(key("unknown"), setOf(key("A")))
        assertTrue(withdrawals.isEmpty())
    }

    // 17. recordSent updates existing entries, not just appends
    @Test
    fun recordSentUpdatesExistingEntries() {
        val tracker = GossipTracker()
        tracker.recordSent(key("peer1"), listOf(route("A", cost = 1.0, sequenceNumber = 1u)))
        tracker.recordSent(key("peer1"), listOf(route("A", cost = 2.0, sequenceNumber = 2u)))

        // Route now matches the updated state → empty diff
        val diff = tracker.computeDiff(key("peer1"), listOf(route("A", cost = 2.0, sequenceNumber = 2u)))
        assertTrue(diff.isEmpty())
    }
}
