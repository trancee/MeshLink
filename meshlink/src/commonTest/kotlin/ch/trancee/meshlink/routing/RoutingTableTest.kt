package ch.trancee.meshlink.routing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingTableTest {

    // ----------------------------------------------------------------
    // Test helpers
    // ----------------------------------------------------------------

    private fun makeEntry(
        destination: ByteArray = ByteArray(12) { it.toByte() },
        nextHop: ByteArray = ByteArray(12) { (it + 1).toByte() },
        metric: Double = 1.0,
        seqNo: UShort = 100u,
        feasibilityDistance: Double = 0.0,
        expiresAt: Long = Long.MAX_VALUE,
        ed25519PublicKey: ByteArray = ByteArray(32) { it.toByte() },
        x25519PublicKey: ByteArray = ByteArray(32) { (it + 32).toByte() },
    ) =
        RouteEntry(
            destination,
            nextHop,
            metric,
            seqNo,
            feasibilityDistance,
            expiresAt,
            ed25519PublicKey,
            x25519PublicKey,
        )

    // ----------------------------------------------------------------
    // (a) install + lookupNextHop round-trip
    // ----------------------------------------------------------------

    @Test
    fun `install then lookupNextHop returns next-hop`() {
        val table = RoutingTable { 1000L }
        val entry = makeEntry()
        assertNull(table.install(entry))
        assertContentEquals(entry.nextHop, table.lookupNextHop(entry.destination))
    }

    @Test
    fun `install then lookupRoute returns full entry`() {
        val table = RoutingTable { 1000L }
        val entry = makeEntry()
        table.install(entry)
        assertEquals(entry, table.lookupRoute(entry.destination))
    }

    @Test
    fun `install returns replaced entry on second install for same destination`() {
        val dest = ByteArray(12) { it.toByte() }
        val entryA = makeEntry(destination = dest, seqNo = 1u)
        val entryB = makeEntry(destination = dest, seqNo = 2u)
        val table = RoutingTable { 1000L }
        assertNull(table.install(entryA))
        val replaced = table.install(entryB)
        assertEquals(entryA, replaced)
        assertEquals(entryB, table.lookupRoute(dest))
    }

    // ----------------------------------------------------------------
    // (b) retract removes route
    // ----------------------------------------------------------------

    @Test
    fun `retract removes installed route and returns it`() {
        val table = RoutingTable { 1000L }
        val entry = makeEntry()
        table.install(entry)
        val removed = table.retract(entry.destination)
        assertEquals(entry, removed)
        assertNull(table.lookupNextHop(entry.destination))
    }

    // ----------------------------------------------------------------
    // (c) lookupNextHop returns null for unknown destination
    // ----------------------------------------------------------------

    @Test
    fun `lookupNextHop for unknown destination returns null`() {
        val table = RoutingTable { 1000L }
        assertNull(table.lookupNextHop(ByteArray(12)))
    }

    @Test
    fun `lookupRoute for unknown destination returns null`() {
        val table = RoutingTable { 1000L }
        assertNull(table.lookupRoute(ByteArray(12)))
    }

    // ----------------------------------------------------------------
    // (d) lazy expiry: lookupNextHop returns null after clock advances
    // ----------------------------------------------------------------

    @Test
    fun `lookupNextHop returns null after expiry and removes entry`() {
        // Arrange
        var time = 1000L
        val table = RoutingTable { time }
        val entry = makeEntry(expiresAt = 2000L)
        table.install(entry)
        assertNotNull(table.lookupNextHop(entry.destination)) // precondition: route exists

        // Act
        time = 2001L
        val result = table.lookupNextHop(entry.destination)

        // Assert
        assertNull(result)
        assertEquals(0, table.routeCount())
    }

    @Test
    fun `lookupRoute returns null after expiry and removes entry`() {
        // Arrange
        var time = 1000L
        val table = RoutingTable { time }
        val entry = makeEntry(expiresAt = 2000L)
        table.install(entry)
        assertNotNull(table.lookupRoute(entry.destination)) // precondition: route exists

        // Act
        time = 2001L
        val result = table.lookupRoute(entry.destination)

        // Assert
        assertNull(result)
        assertEquals(0, table.routeCount())
    }

    // ----------------------------------------------------------------
    // (e) route digest changes on install
    // ----------------------------------------------------------------

    @Test
    fun `routeDigest changes after install`() {
        val table = RoutingTable { 1000L }
        val before = table.routeDigest()
        table.install(makeEntry())
        assertNotEquals(before, table.routeDigest())
    }

    // ----------------------------------------------------------------
    // (f) route digest reverts on retract
    // ----------------------------------------------------------------

    @Test
    fun `routeDigest reverts to initial value after retract`() {
        val table = RoutingTable { 1000L }
        val initial = table.routeDigest()
        val entry = makeEntry()
        table.install(entry)
        table.retract(entry.destination)
        assertEquals(initial, table.routeDigest())
    }

    // ----------------------------------------------------------------
    // (g) install replacing existing route updates digest correctly
    // ----------------------------------------------------------------

    @Test
    fun `routeDigest after replace equals fresh install of replacement entry`() {
        val dest = ByteArray(12) { it.toByte() }
        val entryA = makeEntry(destination = dest, seqNo = 1u)
        val entryB = makeEntry(destination = dest, seqNo = 2u)

        // Expected digest: fresh install of B into an empty table
        val freshTable = RoutingTable { 1000L }
        freshTable.install(entryB)
        val expectedDigest = freshTable.routeDigest()

        // Actual: install A then replace with B
        val table = RoutingTable { 1000L }
        table.install(entryA)
        table.install(entryB)
        assertEquals(expectedDigest, table.routeDigest())
    }

    // ----------------------------------------------------------------
    // (h) FNV-1a known-value tests
    // ----------------------------------------------------------------

    @Test
    fun `fnv1a32 empty input returns FNV offset basis`() {
        // Act
        val result = fnv1a32(ByteArray(0))

        // Assert
        assertEquals(2166136261u, result)
    }

    @Test
    fun `fnv1a32 of ASCII a returns known value 0xe40c292c`() {
        // Act — FNV-1a 32-bit hash of single byte 0x61 ('a') = 0xe40c292c = 3826002220
        val result = fnv1a32(byteArrayOf(0x61.toByte()))

        // Assert
        assertEquals(3826002220u, result)
    }

    @Test
    fun `fnv1a32 is deterministic`() {
        // Arrange
        val data = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte())

        // Act
        val first = fnv1a32(data)
        val second = fnv1a32(data)

        // Assert
        assertEquals(first, second)
    }

    @Test
    fun `fnv1a32 different inputs produce different hashes`() {
        // Act
        val hashA = fnv1a32(byteArrayOf(0x01))
        val hashB = fnv1a32(byteArrayOf(0x02))

        // Assert
        assertNotEquals(hashA, hashB)
    }

    // ----------------------------------------------------------------
    // (i) isSeqNoNewer — all five sub-cases
    // ----------------------------------------------------------------

    @Test
    fun `isSeqNoNewer a greater than b returns true`() {
        // Act
        val result = isSeqNoNewer(100u, 50u)

        // Assert
        assertTrue(result)
    }

    @Test
    fun `isSeqNoNewer a less than b returns false`() {
        // Act
        val result = isSeqNoNewer(50u, 100u)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `isSeqNoNewer equal values returns false`() {
        // Act
        val result = isSeqNoNewer(50u, 50u)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `isSeqNoNewer wraparound zero is newer than 65535`() {
        // Act — RFC 8966 §2.1: seqno 0 follows 65535
        val result = isSeqNoNewer(0u, 65535u)

        // Assert
        assertTrue(result)
    }

    @Test
    fun `isSeqNoNewer wraparound 65535 is not newer than zero`() {
        // Act
        val result = isSeqNoNewer(65535u, 0u)

        // Assert
        assertFalse(result)
    }

    // ----------------------------------------------------------------
    // (j) RouteEntry equals / hashCode
    // ----------------------------------------------------------------

    @Test
    fun `RouteEntry equals identity returns true`() {
        val entry = makeEntry()
        assertTrue(entry.equals(entry))
    }

    @Test
    fun `RouteEntry equals all-same fields returns true and hashCode agrees`() {
        val dest = ByteArray(12) { it.toByte() }
        val nextHop = ByteArray(12) { (it + 1).toByte() }
        val ed = ByteArray(32) { it.toByte() }
        val x25 = ByteArray(32) { (it + 32).toByte() }
        val a = RouteEntry(dest, nextHop, 1.0, 10u, 0.5, 5000L, ed, x25)
        val b =
            RouteEntry(
                dest.copyOf(),
                nextHop.copyOf(),
                1.0,
                10u,
                0.5,
                5000L,
                ed.copyOf(),
                x25.copyOf(),
            )
        assertTrue(a.equals(b))
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `RouteEntry equals different destination returns false`() {
        val a = makeEntry(destination = ByteArray(12) { 1 })
        val b = makeEntry(destination = ByteArray(12) { 2 })
        assertFalse(a.equals(b))
    }

    @Test
    fun `RouteEntry equals different nextHop returns false`() {
        val a = makeEntry(nextHop = ByteArray(12) { 1 })
        val b = makeEntry(nextHop = ByteArray(12) { 2 })
        assertFalse(a.equals(b))
    }

    @Test
    fun `RouteEntry equals different metric returns false`() {
        val a = makeEntry(metric = 1.0)
        val b = makeEntry(metric = 2.0)
        assertFalse(a.equals(b))
    }

    @Test
    fun `RouteEntry equals different seqNo returns false`() {
        val a = makeEntry(seqNo = 1u)
        val b = makeEntry(seqNo = 2u)
        assertFalse(a.equals(b))
    }

    @Test
    fun `RouteEntry equals different feasibilityDistance returns false`() {
        val a = makeEntry(feasibilityDistance = 0.0)
        val b = makeEntry(feasibilityDistance = 1.0)
        assertFalse(a.equals(b))
    }

    @Test
    fun `RouteEntry equals different expiresAt returns false`() {
        val a = makeEntry(expiresAt = 1000L)
        val b = makeEntry(expiresAt = 2000L)
        assertFalse(a.equals(b))
    }

    @Test
    fun `RouteEntry equals different ed25519PublicKey returns false`() {
        val a = makeEntry(ed25519PublicKey = ByteArray(32) { 1 })
        val b = makeEntry(ed25519PublicKey = ByteArray(32) { 2 })
        assertFalse(a.equals(b))
    }

    @Test
    fun `RouteEntry equals different x25519PublicKey returns false`() {
        val a = makeEntry(x25519PublicKey = ByteArray(32) { 1 })
        val b = makeEntry(x25519PublicKey = ByteArray(32) { 2 })
        assertFalse(a.equals(b))
    }

    @Test
    fun `RouteEntry equals non-RouteEntry returns false`() {
        val entry = makeEntry()
        assertFalse(entry.equals("not a RouteEntry"))
    }

    // ----------------------------------------------------------------
    // (k) allRoutes filters expired entries
    // ----------------------------------------------------------------

    @Test
    fun `allRoutes returns only non-expired entries and evicts expired`() {
        // Arrange
        var time = 1000L
        val table = RoutingTable { time }
        val valid = makeEntry(destination = ByteArray(12) { 1 }, expiresAt = Long.MAX_VALUE)
        val expired = makeEntry(destination = ByteArray(12) { 2 }, expiresAt = 1500L)
        table.install(valid)
        table.install(expired)

        // Act
        time = 2000L
        val routes = table.allRoutes()

        // Assert
        assertEquals(1, routes.size)
        assertContentEquals(valid.destination, routes[0].destination)
        assertEquals(1, table.routeCount())
    }

    @Test
    fun `allRoutes updates digest when evicting expired entries`() {
        // Arrange
        var time = 1000L
        val table = RoutingTable { time }
        val entry = makeEntry(expiresAt = 1500L)
        table.install(entry)
        assertNotEquals(0u, table.routeDigest()) // precondition: non-zero after install

        // Act
        time = 2000L
        table.allRoutes()

        // Assert — digest should be back to 0u (XOR-fold with the same entry hash)
        assertEquals(0u, table.routeDigest())
    }

    // ----------------------------------------------------------------
    // Negative tests
    // ----------------------------------------------------------------

    @Test
    fun `allRoutes on empty table returns empty list`() {
        // Arrange
        val table = RoutingTable { 1000L }

        // Act
        val routes = table.allRoutes()

        // Assert
        assertEquals(emptyList(), routes)
    }

    @Test
    fun `retract non-existent destination returns null`() {
        // Arrange
        val table = RoutingTable { 1000L }

        // Act
        val result = table.retract(ByteArray(12))

        // Assert
        assertNull(result)
    }

    @Test
    fun `isSeqNoNewer with equal values returns false - negative case`() {
        // Act & Assert
        assertFalse(isSeqNoNewer(0u, 0u))
        assertFalse(isSeqNoNewer(65535u, 65535u))
    }

    // ----------------------------------------------------------------
    // Digest consistency after lazy eviction via lookupNextHop / lookupRoute
    // ----------------------------------------------------------------

    @Test
    fun `routeDigest reverts to 0 after lazy eviction via lookupNextHop`() {
        // Arrange
        var time = 1000L
        val table = RoutingTable { time }
        val entry = makeEntry(expiresAt = 1500L)
        table.install(entry)
        assertNotEquals(0u, table.routeDigest()) // precondition: digest is non-zero

        // Act
        time = 2000L
        table.lookupNextHop(entry.destination) // triggers lazy eviction

        // Assert
        assertEquals(0u, table.routeDigest())
    }

    @Test
    fun `routeDigest reverts to 0 after lazy eviction via lookupRoute`() {
        // Arrange
        var time = 1000L
        val table = RoutingTable { time }
        val entry = makeEntry(expiresAt = 1500L)
        table.install(entry)
        assertNotEquals(0u, table.routeDigest()) // precondition: digest is non-zero

        // Act
        time = 2000L
        table.lookupRoute(entry.destination) // triggers lazy eviction

        // Assert
        assertEquals(0u, table.routeDigest())
    }

    // ----------------------------------------------------------------
    // routeCount reflects table state
    // ----------------------------------------------------------------

    @Test
    fun `routeCount starts at zero`() {
        // Arrange
        val table = RoutingTable { 1000L }

        // Act
        val count = table.routeCount()

        // Assert
        assertEquals(0, count)
    }

    @Test
    fun `routeCount increments after install`() {
        // Arrange
        val table = RoutingTable { 1000L }
        table.install(makeEntry(destination = ByteArray(12) { 1 }))

        // Act
        val count = table.routeCount()

        // Assert
        assertEquals(1, count)
    }

    @Test
    fun `routeCount reflects multiple installs`() {
        // Arrange
        val table = RoutingTable { 1000L }
        table.install(makeEntry(destination = ByteArray(12) { 1 }))
        table.install(makeEntry(destination = ByteArray(12) { 2 }))

        // Act
        val count = table.routeCount()

        // Assert
        assertEquals(2, count)
    }

    @Test
    fun `routeCount decrements after retract`() {
        // Arrange
        val table = RoutingTable { 1000L }
        table.install(makeEntry(destination = ByteArray(12) { 1 }))
        table.install(makeEntry(destination = ByteArray(12) { 2 }))
        table.retract(ByteArray(12) { 1 })

        // Act
        val count = table.routeCount()

        // Assert
        assertEquals(1, count)
    }

    // ----------------------------------------------------------------
    // allRoutes with only valid routes (second-loop not-entered branch)
    // ----------------------------------------------------------------

    @Test
    fun `allRoutes with all valid routes returns all and does not modify digest`() {
        // Arrange
        val table = RoutingTable { 1000L }
        val e1 = makeEntry(destination = ByteArray(12) { 1 })
        val e2 = makeEntry(destination = ByteArray(12) { 2 })
        table.install(e1)
        table.install(e2)
        val digestBefore = table.routeDigest()

        // Act
        val routes = table.allRoutes()

        // Assert
        assertEquals(2, routes.size)
        assertEquals(digestBefore, table.routeDigest())
    }
}
