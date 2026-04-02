package io.meshlink.routing

import io.meshlink.util.ByteArrayKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RoutingTableTest {

    private fun key(s: String) = ByteArrayKey(s.encodeToByteArray())

    @Test
    fun addRouteAndQueryBestRoute() {
        val table = RoutingTable()

        table.addRoute(destination = key("D"), nextHop = key("A"), cost = 10.0, sequenceNumber = 1u)
        table.addRoute(destination = key("D"), nextHop = key("B"), cost = 5.0, sequenceNumber = 1u)

        val best = table.bestRoute(key("D"))
        assertEquals(key("B"), best?.nextHop)
        assertEquals(5.0, best?.cost)

        assertNull(table.bestRoute(key("X")))
    }

    @Test
    fun preferHigherSequenceNumberOverShorterPath() {
        val table = RoutingTable()

        table.addRoute(destination = key("D"), nextHop = key("A"), cost = 1.0, sequenceNumber = 1u)
        table.addRoute(destination = key("D"), nextHop = key("B"), cost = 10.0, sequenceNumber = 5u)

        val best = table.bestRoute(key("D"))
        assertEquals(key("B"), best?.nextHop, "Higher seqnum should win over lower cost")
        assertEquals(5u, best?.sequenceNumber)
    }

    @Test
    fun primaryAndBackupFailover() {
        val table = RoutingTable()

        table.addRoute(destination = key("D"), nextHop = key("A"), cost = 3.0, sequenceNumber = 1u)
        table.addRoute(destination = key("D"), nextHop = key("B"), cost = 7.0, sequenceNumber = 1u)

        assertEquals(key("A"), table.bestRoute(key("D"))?.nextHop, "Primary should be lowest cost")

        table.removeRoute(destination = key("D"), nextHop = key("A"))

        val backup = table.bestRoute(key("D"))
        assertEquals(key("B"), backup?.nextHop, "Backup should take over after primary removed")
    }

    @Test
    fun routeExpiresAfterThreshold() {
        var now = 1000L
        RoutingTable.currentTime = { now }

        val table = RoutingTable(expiryMillis = 500)

        table.addRoute(destination = key("D"), nextHop = key("A"), cost = 5.0, sequenceNumber = 1u)
        assertEquals(key("A"), table.bestRoute(key("D"))?.nextHop)

        now = 1600L
        assertNull(table.bestRoute(key("D")), "Route should be expired after 500ms")
    }

    @Test
    fun perNeighborCapRejectsExcessRoutes() {
        // neighborCapFraction = 0.3, so with max 10 destinations, one neighbor can announce at most 3
        val table = RoutingTable(neighborCapFraction = 0.3, maxDestinations = 10)

        // Neighbor "N" announces routes to D1, D2, D3 — all accepted
        table.addRoute(destination = key("D1"), nextHop = key("N"), cost = 1.0, sequenceNumber = 1u)
        table.addRoute(destination = key("D2"), nextHop = key("N"), cost = 1.0, sequenceNumber = 1u)
        table.addRoute(destination = key("D3"), nextHop = key("N"), cost = 1.0, sequenceNumber = 1u)

        assertEquals(key("N"), table.bestRoute(key("D1"))?.nextHop)
        assertEquals(key("N"), table.bestRoute(key("D2"))?.nextHop)
        assertEquals(key("N"), table.bestRoute(key("D3"))?.nextHop)

        // 4th route from same neighbor exceeds 30% cap → rejected
        table.addRoute(destination = key("D4"), nextHop = key("N"), cost = 1.0, sequenceNumber = 1u)
        assertNull(table.bestRoute(key("D4")), "4th route from neighbor N should be rejected (30% cap of 10)")

        // Different neighbor can still add routes
        table.addRoute(destination = key("D4"), nextHop = key("M"), cost = 2.0, sequenceNumber = 1u)
        assertEquals(key("M"), table.bestRoute(key("D4"))?.nextHop, "Different neighbor should be accepted")
    }

    // --- Batch 12 Cycle 5: Cost tie-breaking and removal ---

    @Test
    fun equalCostRoutesThenRemoveBest() {
        val table = RoutingTable()

        // Two routes with identical cost and seqnum
        table.addRoute(key("D"), key("A"), cost = 5.0, sequenceNumber = 1u)
        table.addRoute(key("D"), key("B"), cost = 5.0, sequenceNumber = 1u)

        // bestRoute returns one of them (deterministic via minByOrNull)
        val best = table.bestRoute(key("D"))!!
        assertEquals(5.0, best.cost)

        // Remove whichever was picked — the other should take over
        table.removeRoute(key("D"), best.nextHop)
        val remaining = table.bestRoute(key("D"))
        assertEquals(5.0, remaining?.cost)
        // nextHop must be the OTHER one
        val other = if (best.nextHop == key("A")) key("B") else key("A")
        assertEquals(other, remaining?.nextHop)

        // Remove second route → null
        table.removeRoute(key("D"), other)
        assertNull(table.bestRoute(key("D")))
    }

    // --- Batch 12 Cycle 6: Stale seqnum rejected ---

    @Test
    fun staleSequenceNumberRejected() {
        val table = RoutingTable()

        table.addRoute(key("D"), key("A"), cost = 5.0, sequenceNumber = 10u)

        // Lower seqnum is ignored even with better cost
        table.addRoute(key("D"), key("B"), cost = 1.0, sequenceNumber = 5u)
        assertEquals(key("A"), table.bestRoute(key("D"))?.nextHop,
            "Stale seqnum should be rejected even with better cost")

        // Equal seqnum adds a competing route
        table.addRoute(key("D"), key("C"), cost = 3.0, sequenceNumber = 10u)
        assertEquals(key("C"), table.bestRoute(key("D"))?.nextHop,
            "Same seqnum, lower cost → new best route")

        // Higher seqnum clears old routes
        table.addRoute(key("D"), key("X"), cost = 100.0, sequenceNumber = 20u)
        assertEquals(key("X"), table.bestRoute(key("D"))?.nextHop,
            "Higher seqnum clears old routes even with worse cost")
    }

    // --- Batch 12 Cycle 8: removeRoute then bestRoute null ---

    @Test
    fun removeOnlyRouteThenBestRouteNull() {
        val table = RoutingTable()

        table.addRoute(key("D"), key("A"), cost = 1.0, sequenceNumber = 1u)
        assertEquals(key("A"), table.bestRoute(key("D"))?.nextHop)

        table.removeRoute(key("D"), key("A"))
        assertNull(table.bestRoute(key("D")), "After removing only route, bestRoute returns null")

        // Removing from non-existent destination is a no-op
        table.removeRoute(key("X"), key("Y")) // should not throw
    }

    // --- Route Cost Sanity Validation ---

    @Test
    fun negativeCostRejected() {
        val table = RoutingTable()
        table.addRoute(key("D"), key("A"), cost = -1.0, sequenceNumber = 1u)
        assertNull(table.bestRoute(key("D")), "Negative cost route should be rejected")
    }

    @Test
    fun nanCostRejected() {
        val table = RoutingTable()
        table.addRoute(key("D"), key("A"), cost = Double.NaN, sequenceNumber = 1u)
        assertNull(table.bestRoute(key("D")), "NaN cost route should be rejected")
    }

    @Test
    fun infiniteCostRejected() {
        val table = RoutingTable()
        table.addRoute(key("D"), key("A"), cost = Double.POSITIVE_INFINITY, sequenceNumber = 1u)
        assertNull(table.bestRoute(key("D")), "Positive infinity cost should be rejected")

        table.addRoute(key("D"), key("B"), cost = Double.NEGATIVE_INFINITY, sequenceNumber = 1u)
        assertNull(table.bestRoute(key("D")), "Negative infinity cost should be rejected")
    }

    @Test
    fun costExceedingMaxRejected() {
        val table = RoutingTable()
        table.addRoute(key("D"), key("A"), cost = RoutingTable.MAX_ROUTE_COST + 1.0, sequenceNumber = 1u)
        assertNull(table.bestRoute(key("D")), "Cost exceeding MAX_ROUTE_COST should be rejected")
    }

    @Test
    fun withdrawalCostAccepted() {
        val table = RoutingTable()
        table.addRoute(key("D"), key("A"), cost = Double.MAX_VALUE, sequenceNumber = 1u)
        val best = table.bestRoute(key("D"))
        assertNotNull(best, "Double.MAX_VALUE (withdrawal) should be accepted")
        assertEquals(Double.MAX_VALUE, best.cost)
    }

    @Test
    fun normalPositiveCostsAccepted() {
        val table = RoutingTable()
        table.addRoute(key("D"), key("A"), cost = 0.0, sequenceNumber = 1u)
        assertNotNull(table.bestRoute(key("D")), "Zero cost should be accepted")

        table.addRoute(key("E"), key("A"), cost = 1.0, sequenceNumber = 1u)
        assertNotNull(table.bestRoute(key("E")), "Normal positive cost should be accepted")

        table.addRoute(key("F"), key("A"), cost = RoutingTable.MAX_ROUTE_COST, sequenceNumber = 1u)
        assertNotNull(table.bestRoute(key("F")), "Cost exactly at MAX_ROUTE_COST should be accepted")
    }
}
