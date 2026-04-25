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
    ) = RouteEntry(destination, nextHop, metric, seqNo, feasibilityDistance, expiresAt, ed25519PublicKey, x25519PublicKey)

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
        var time = 1000L
        val table = RoutingTable { time }
        val entry = makeEntry(expiresAt = 2000L)
        table.install(entry)
        assertNotNull(table.lookupNextHop(entry.destination))
        time = 2001L
        assertNull(table.lookupNextHop(entry.destination))
        assertEquals(0, table.routeCount())
    }

    @Test
    fun `lookupRoute returns null after expiry and removes entry`() {
        var time = 1000L
        val table = RoutingTable { time }
        val entry = makeEntry(expiresAt = 2000L)
        table.install(entry)
        assertNotNull(table.lookupRoute(entry.destination))
        time = 2001L
        assertNull(table.lookupRoute(entry.destination))
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
        assertEquals(2166136261u, fnv1a32(ByteArray(0)))
    }

    @Test
    fun `fnv1a32 of ASCII a returns known value 0xe40c292c`() {
        // FNV-1a 32-bit hash of single byte 0x61 ('a') = 0xe40c292c = 3826002220
        assertEquals(3826002220u, fnv1a32(byteArrayOf(0x61.toByte())))
    }

    @Test
    fun `fnv1a32 is deterministic`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte())
        assertEquals(fnv1a32(data), fnv1a32(data))
    }

    @Test
    fun `fnv1a32 different inputs produce different hashes`() {
        assertNotEquals(fnv1a32(byteArrayOf(0x01)), fnv1a32(byteArrayOf(0x02)))
    }

    // ----------------------------------------------------------------
    // (i) isSeqNoNewer — all five sub-cases
    // ----------------------------------------------------------------

    @Test
    fun `isSeqNoNewer a greater than b returns true`() {
        assertTrue(isSeqNoNewer(100u, 50u))
    }

    @Test
    fun `isSeqNoNewer a less than b returns false`() {
        assertFalse(isSeqNoNewer(50u, 100u))
    }

    @Test
    fun `isSeqNoNewer equal values returns false`() {
        assertFalse(isSeqNoNewer(50u, 50u))
    }

    @Test
    fun `isSeqNoNewer wraparound zero is newer than 65535`() {
        // RFC 8966 §2.1: seqno 0 follows 65535
        assertTrue(isSeqNoNewer(0u, 65535u))
    }

    @Test
    fun `isSeqNoNewer wraparound 65535 is not newer than zero`() {
        assertFalse(isSeqNoNewer(65535u, 0u))
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
        val b = RouteEntry(dest.copyOf(), nextHop.copyOf(), 1.0, 10u, 0.5, 5000L, ed.copyOf(), x25.copyOf())
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
        var time = 1000L
        val table = RoutingTable { time }
        val valid = makeEntry(destination = ByteArray(12) { 1 }, expiresAt = Long.MAX_VALUE)
        val expired = makeEntry(destination = ByteArray(12) { 2 }, expiresAt = 1500L)
        table.install(valid)
        table.install(expired)

        time = 2000L
        val routes = table.allRoutes()
        assertEquals(1, routes.size)
        assertContentEquals(valid.destination, routes[0].destination)
        // Expired entry was removed from the table
        assertEquals(1, table.routeCount())
    }

    @Test
    fun `allRoutes updates digest when evicting expired entries`() {
        var time = 1000L
        val table = RoutingTable { time }
        val entry = makeEntry(expiresAt = 1500L)
        table.install(entry)
        val digestAfterInstall = table.routeDigest()
        assertNotEquals(0u, digestAfterInstall)
        time = 2000L
        table.allRoutes()
        // Digest should be back to 0u (XOR-fold with the same entry hash)
        assertEquals(0u, table.routeDigest())
    }

    // ----------------------------------------------------------------
    // Negative tests
    // ----------------------------------------------------------------

    @Test
    fun `allRoutes on empty table returns empty list`() {
        val table = RoutingTable { 1000L }
        assertEquals(emptyList(), table.allRoutes())
    }

    @Test
    fun `retract non-existent destination returns null`() {
        val table = RoutingTable { 1000L }
        assertNull(table.retract(ByteArray(12)))
    }

    @Test
    fun `isSeqNoNewer with equal values returns false (negative)`() {
        assertFalse(isSeqNoNewer(0u, 0u))
        assertFalse(isSeqNoNewer(65535u, 65535u))
    }

    // ----------------------------------------------------------------
    // Digest consistency after lazy eviction via lookupNextHop / lookupRoute
    // ----------------------------------------------------------------

    @Test
    fun `routeDigest reverts to 0 after lazy eviction via lookupNextHop`() {
        var time = 1000L
        val table = RoutingTable { time }
        val entry = makeEntry(expiresAt = 1500L)
        table.install(entry)
        assertNotEquals(0u, table.routeDigest())
        time = 2000L
        table.lookupNextHop(entry.destination)  // triggers lazy eviction
        assertEquals(0u, table.routeDigest())
    }

    @Test
    fun `routeDigest reverts to 0 after lazy eviction via lookupRoute`() {
        var time = 1000L
        val table = RoutingTable { time }
        val entry = makeEntry(expiresAt = 1500L)
        table.install(entry)
        time = 2000L
        table.lookupRoute(entry.destination)  // triggers lazy eviction
        assertEquals(0u, table.routeDigest())
    }

    // ----------------------------------------------------------------
    // routeCount reflects table state
    // ----------------------------------------------------------------

    @Test
    fun `routeCount reflects installed and retracted routes`() {
        val table = RoutingTable { 1000L }
        assertEquals(0, table.routeCount())
        table.install(makeEntry(destination = ByteArray(12) { 1 }))
        assertEquals(1, table.routeCount())
        table.install(makeEntry(destination = ByteArray(12) { 2 }))
        assertEquals(2, table.routeCount())
        table.retract(ByteArray(12) { 1 })
        assertEquals(1, table.routeCount())
    }

    // ----------------------------------------------------------------
    // allRoutes with only valid routes (second-loop not-entered branch)
    // ----------------------------------------------------------------

    @Test
    fun `allRoutes with all valid routes returns all and does not modify digest`() {
        val table = RoutingTable { 1000L }
        val e1 = makeEntry(destination = ByteArray(12) { 1 })
        val e2 = makeEntry(destination = ByteArray(12) { 2 })
        table.install(e1)
        table.install(e2)
        val digestBefore = table.routeDigest()
        val routes = table.allRoutes()
        assertEquals(2, routes.size)
        assertEquals(digestBefore, table.routeDigest())
    }
}
