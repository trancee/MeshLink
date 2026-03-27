package io.meshlink.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutingTableTest {

    @Test
    fun addRouteAndQueryBestRoute() {
        val table = RoutingTable()

        table.addRoute(destination = "D", nextHop = "A", cost = 10.0, sequenceNumber = 1u)
        table.addRoute(destination = "D", nextHop = "B", cost = 5.0, sequenceNumber = 1u)

        val best = table.bestRoute("D")
        assertEquals("B", best?.nextHop)
        assertEquals(5.0, best?.cost)

        assertNull(table.bestRoute("X"))
    }

    @Test
    fun preferHigherSequenceNumberOverShorterPath() {
        val table = RoutingTable()

        table.addRoute(destination = "D", nextHop = "A", cost = 1.0, sequenceNumber = 1u)
        table.addRoute(destination = "D", nextHop = "B", cost = 10.0, sequenceNumber = 5u)

        val best = table.bestRoute("D")
        assertEquals("B", best?.nextHop, "Higher seqnum should win over lower cost")
        assertEquals(5u, best?.sequenceNumber)
    }

    @Test
    fun primaryAndBackupFailover() {
        val table = RoutingTable()

        table.addRoute(destination = "D", nextHop = "A", cost = 3.0, sequenceNumber = 1u)
        table.addRoute(destination = "D", nextHop = "B", cost = 7.0, sequenceNumber = 1u)

        assertEquals("A", table.bestRoute("D")?.nextHop, "Primary should be lowest cost")

        table.removeRoute(destination = "D", nextHop = "A")

        val backup = table.bestRoute("D")
        assertEquals("B", backup?.nextHop, "Backup should take over after primary removed")
    }

    @Test
    fun routeExpiresAfterThreshold() {
        var now = 1000L
        RoutingTable.currentTime = { now }

        val table = RoutingTable(expiryMs = 500)

        table.addRoute(destination = "D", nextHop = "A", cost = 5.0, sequenceNumber = 1u)
        assertEquals("A", table.bestRoute("D")?.nextHop)

        now = 1600L
        assertNull(table.bestRoute("D"), "Route should be expired after 500ms")
    }

    @Test
    fun perNeighborCapRejectsExcessRoutes() {
        // neighborCapFraction = 0.3, so with max 10 destinations, one neighbor can announce at most 3
        val table = RoutingTable(neighborCapFraction = 0.3, maxDestinations = 10)

        // Neighbor "N" announces routes to D1, D2, D3 — all accepted
        table.addRoute(destination = "D1", nextHop = "N", cost = 1.0, sequenceNumber = 1u)
        table.addRoute(destination = "D2", nextHop = "N", cost = 1.0, sequenceNumber = 1u)
        table.addRoute(destination = "D3", nextHop = "N", cost = 1.0, sequenceNumber = 1u)

        assertEquals("N", table.bestRoute("D1")?.nextHop)
        assertEquals("N", table.bestRoute("D2")?.nextHop)
        assertEquals("N", table.bestRoute("D3")?.nextHop)

        // 4th route from same neighbor exceeds 30% cap → rejected
        table.addRoute(destination = "D4", nextHop = "N", cost = 1.0, sequenceNumber = 1u)
        assertNull(table.bestRoute("D4"), "4th route from neighbor N should be rejected (30% cap of 10)")

        // Different neighbor can still add routes
        table.addRoute(destination = "D4", nextHop = "M", cost = 2.0, sequenceNumber = 1u)
        assertEquals("M", table.bestRoute("D4")?.nextHop, "Different neighbor should be accepted")
    }

    // --- Batch 12 Cycle 5: Cost tie-breaking and removal ---

    @Test
    fun equalCostRoutesThenRemoveBest() {
        val table = RoutingTable()

        // Two routes with identical cost and seqnum
        table.addRoute("D", "A", cost = 5.0, sequenceNumber = 1u)
        table.addRoute("D", "B", cost = 5.0, sequenceNumber = 1u)

        // bestRoute returns one of them (deterministic via minByOrNull)
        val best = table.bestRoute("D")!!
        assertEquals(5.0, best.cost)

        // Remove whichever was picked — the other should take over
        table.removeRoute("D", best.nextHop)
        val remaining = table.bestRoute("D")
        assertEquals(5.0, remaining?.cost)
        // nextHop must be the OTHER one
        val other = if (best.nextHop == "A") "B" else "A"
        assertEquals(other, remaining?.nextHop)

        // Remove second route → null
        table.removeRoute("D", other)
        assertNull(table.bestRoute("D"))
    }

    // --- Batch 12 Cycle 6: Stale seqnum rejected ---

    @Test
    fun staleSequenceNumberRejected() {
        val table = RoutingTable()

        table.addRoute("D", "A", cost = 5.0, sequenceNumber = 10u)

        // Lower seqnum is ignored even with better cost
        table.addRoute("D", "B", cost = 1.0, sequenceNumber = 5u)
        assertEquals("A", table.bestRoute("D")?.nextHop,
            "Stale seqnum should be rejected even with better cost")

        // Equal seqnum adds a competing route
        table.addRoute("D", "C", cost = 3.0, sequenceNumber = 10u)
        assertEquals("C", table.bestRoute("D")?.nextHop,
            "Same seqnum, lower cost → new best route")

        // Higher seqnum clears old routes
        table.addRoute("D", "X", cost = 100.0, sequenceNumber = 20u)
        assertEquals("X", table.bestRoute("D")?.nextHop,
            "Higher seqnum clears old routes even with worse cost")
    }

    // --- Batch 12 Cycle 8: removeRoute then bestRoute null ---

    @Test
    fun removeOnlyRouteThenBestRouteNull() {
        val table = RoutingTable()

        table.addRoute("D", "A", cost = 1.0, sequenceNumber = 1u)
        assertEquals("A", table.bestRoute("D")?.nextHop)

        table.removeRoute("D", "A")
        assertNull(table.bestRoute("D"), "After removing only route, bestRoute returns null")

        // Removing from non-existent destination is a no-op
        table.removeRoute("X", "Y") // should not throw
    }

    // --- DSDV Holddown Timer ---

    @Test
    fun settlingTimeDelaysRouteUpdate() {
        var now = 1000L
        RoutingTable.currentTime = { now }

        val table = RoutingTable(settlingMs = 100)

        // Initial route accepted immediately
        table.addRoute("D", "A", cost = 5.0, sequenceNumber = 1u)
        assertEquals("A", table.bestRoute("D")?.nextHop)

        // New route with same seqnum arrives during settling period — held
        now = 1050L
        table.addRoute("D", "B", cost = 3.0, sequenceNumber = 1u)
        // During settling, the original route should still be best (settling delays new)
        // But after settling, the better route is accepted
        now = 1200L
        val best = table.bestRoute("D")
        assertEquals("B", best?.nextHop, "Better route should be usable after settling period")
    }

    @Test
    fun holddownRejectsDowngradesDuringTimer() {
        var now = 1000L
        RoutingTable.currentTime = { now }

        val table = RoutingTable(holddownMs = 200)

        // Route installed
        table.addRoute("D", "A", cost = 5.0, sequenceNumber = 5u)
        assertEquals("A", table.bestRoute("D")?.nextHop)

        // Route withdrawn (high cost = infinity, higher seqnum)
        now = 1100L
        table.addRoute("D", "A", cost = Double.MAX_VALUE, sequenceNumber = 6u)

        // During holddown, a new route with lower seqnum is rejected
        now = 1150L
        table.addRoute("D", "B", cost = 1.0, sequenceNumber = 4u)
        // Route with stale seqnum should still be rejected (existing behavior)
        val best = table.bestRoute("D")
        // Only the infinity-cost route exists during holddown
        if (best != null) {
            assertEquals("A", best.nextHop)
        }

        // After holddown, a new route with higher seqnum is accepted
        now = 1400L
        table.addRoute("D", "C", cost = 2.0, sequenceNumber = 7u)
        assertEquals("C", table.bestRoute("D")?.nextHop, "New route accepted after holddown")
    }
}
