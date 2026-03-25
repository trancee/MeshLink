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
}
