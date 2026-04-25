package ch.trancee.meshlink.routing

import ch.trancee.meshlink.wire.Hello
import ch.trancee.meshlink.wire.Update
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RoutingEngineTest {

    // ── fixtures ───────────────────────────────────────────────────────────────

    private val localPeerId = ByteArray(12) { 0x0A }
    private val localEdKey = ByteArray(32) { it.toByte() }
    private val localDhKey = ByteArray(32) { (it + 32).toByte() }

    private val peerA = ByteArray(12) { 0x0B }
    private val peerAEdKey = ByteArray(32) { (it + 1).toByte() }
    private val peerADhKey = ByteArray(32) { (it + 33).toByte() }

    private val peerB = ByteArray(12) { 0x0C }
    private val peerBEdKey = ByteArray(32) { (it + 2).toByte() }
    private val peerBDhKey = ByteArray(32) { (it + 34).toByte() }

    private val destA = ByteArray(12) { 0xAA.toByte() }
    private val destB = ByteArray(12) { 0xBB.toByte() }
    private val destC = ByteArray(12) { 0xCC.toByte() }

    private fun makeUpdate(
        destination: ByteArray = destA,
        metric: UShort = 100u,
        seqNo: UShort = 100u,
        edKey: ByteArray = peerAEdKey,
        dhKey: ByteArray = peerADhKey,
    ) =
        Update(
            destination = destination,
            metric = metric,
            seqNo = seqNo,
            ed25519PublicKey = edKey,
            x25519PublicKey = dhKey,
        )

    private fun makeEngine(
        table: RoutingTable,
        clock: () -> Long,
        scope: kotlinx.coroutines.CoroutineScope,
        config: RoutingConfig = RoutingConfig(),
    ) =
        RoutingEngine(
            routingTable = table,
            localPeerId = localPeerId,
            localEdPublicKey = localEdKey,
            localDhPublicKey = localDhKey,
            scope = scope,
            clock = clock,
            config = config,
        )

    // ── processUpdate: feasibility condition ───────────────────────────────────

    // (a) newer seqNo accepted, FD reset to new totalCost
    @Test
    fun `processUpdate newer seqNo is accepted and FD is reset`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        // First install: seqNo=100, wire metric=200 → totalCost=2.0
        assertTrue(engine.processUpdate(peerA, makeUpdate(seqNo = 100u, metric = 200u), 0.0))

        // Newer seqNo=101 with higher cost → still accepted (seqNo wins)
        val accepted = engine.processUpdate(peerA, makeUpdate(seqNo = 101u, metric = 300u), 0.0)
        assertTrue(accepted)
        val route = table.lookupRoute(destA)
        assertNotNull(route)
        assertEquals(101u, route.seqNo)
        // FD reset to 3.0
        assertTrue(route.metric > 2.0)
    }

    // (b) same seqNo + lower metric accepted
    @Test
    fun `processUpdate same seqNo lower metric is accepted`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        // FD = 2.0
        assertTrue(engine.processUpdate(peerA, makeUpdate(seqNo = 100u, metric = 200u), 0.0))

        // same seqNo, lower cost 1.0 < 2.0 → accepted
        val accepted = engine.processUpdate(peerA, makeUpdate(seqNo = 100u, metric = 100u), 0.0)
        assertTrue(accepted)
        assertEquals(1.0, table.lookupRoute(destA)?.metric)
    }

    // (c) same seqNo + higher metric rejected
    @Test
    fun `processUpdate same seqNo higher metric is rejected`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        assertTrue(engine.processUpdate(peerA, makeUpdate(seqNo = 100u, metric = 100u), 0.0))
        val rejected = engine.processUpdate(peerA, makeUpdate(seqNo = 100u, metric = 200u), 0.0)
        assertFalse(rejected)
        // route still has original metric
        assertEquals(1.0, table.lookupRoute(destA)?.metric)
    }

    // (d) stale seqNo rejected
    @Test
    fun `processUpdate stale seqNo is rejected`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        // Install with seqNo=101
        assertTrue(engine.processUpdate(peerA, makeUpdate(seqNo = 101u, metric = 200u), 0.0))
        // seqNo=100 is stale — not newer, not equal → rejected
        val rejected = engine.processUpdate(peerA, makeUpdate(seqNo = 100u, metric = 100u), 0.0)
        assertFalse(rejected)
        assertEquals(101u, table.lookupRoute(destA)?.seqNo)
    }

    // (e) retraction with newer seqNo removes route and clears FD
    @Test
    fun `processUpdate retraction with newer seqNo removes route and clears FD`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        assertTrue(engine.processUpdate(peerA, makeUpdate(seqNo = 100u, metric = 100u), 0.0))
        assertNotNull(table.lookupRoute(destA))

        // Retraction with newer seqNo=101
        val accepted =
            engine.processUpdate(peerA, makeUpdate(seqNo = 101u, metric = METRIC_RETRACTION), 0.0)
        assertTrue(accepted)
        assertNull(table.lookupRoute(destA))
    }

    // (f) retraction with stale seqNo rejected
    @Test
    fun `processUpdate retraction with stale seqNo is rejected`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        assertTrue(engine.processUpdate(peerA, makeUpdate(seqNo = 100u, metric = 100u), 0.0))
        val rejected =
            engine.processUpdate(peerA, makeUpdate(seqNo = 99u, metric = METRIC_RETRACTION), 0.0)
        assertFalse(rejected)
        assertNotNull(table.lookupRoute(destA))
    }

    // (g) after FD cleared by retraction, any finite metric is accepted
    @Test
    fun `processUpdate after retraction clears FD any metric is accepted`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        assertTrue(engine.processUpdate(peerA, makeUpdate(seqNo = 100u, metric = 100u), 0.0))
        // Retract to clear FD
        assertTrue(
            engine.processUpdate(peerA, makeUpdate(seqNo = 101u, metric = METRIC_RETRACTION), 0.0)
        )
        assertNull(table.lookupRoute(destA))

        // Any finite metric now accepted (no FD)
        assertTrue(engine.processUpdate(peerA, makeUpdate(seqNo = 50u, metric = 500u), 0.0))
        assertNotNull(table.lookupRoute(destA))
    }

    // retraction for unknown destination returns false (existingRoute == null path)
    @Test
    fun `processUpdate retraction for unknown destination returns false`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        val result =
            engine.processUpdate(peerA, makeUpdate(seqNo = 1u, metric = METRIC_RETRACTION), 0.0)
        assertFalse(result)
    }

    // FD exists but route has expired — existingRoute == null in step 3/4
    @Test
    fun `processUpdate FD set but route expired returns false`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val config = RoutingConfig(routeExpiryMillis = 1000L)
        val engine = makeEngine(table, { now }, backgroundScope, config)

        // Install with very short expiry
        assertTrue(engine.processUpdate(peerA, makeUpdate(seqNo = 100u, metric = 100u), 0.0))
        assertNotNull(table.lookupRoute(destA))

        // Advance clock past expiry — lookupRoute will now return null
        now = 2000L
        assertNull(table.lookupRoute(destA))

        // FD is still set (not cleared by expiry), but existingRoute == null
        // → step 3 & 4 short-circuit to false
        val result = engine.processUpdate(peerA, makeUpdate(seqNo = 101u, metric = 50u), 0.0)
        assertFalse(result)
    }

    // negative: same seqNo, metric == FD (not strictly less) → rejected
    @Test
    fun `processUpdate same seqNo metric equal to FD is rejected`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        // FD = 1.0 (metric=100u, linkCost=0)
        assertTrue(engine.processUpdate(peerA, makeUpdate(seqNo = 100u, metric = 100u), 0.0))
        // Same seqNo, same totalCost 1.0 == FD 1.0 → NOT strictly less → rejected
        val rejected = engine.processUpdate(peerA, makeUpdate(seqNo = 100u, metric = 100u), 0.0)
        assertFalse(rejected)
    }

    // (l) seqNo wraparound: 0 is accepted after 65535
    @Test
    fun `processUpdate seqNo 0 after 65535 is accepted as newer`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        assertTrue(engine.processUpdate(peerA, makeUpdate(seqNo = 65535u, metric = 200u), 0.0))
        val accepted = engine.processUpdate(peerA, makeUpdate(seqNo = 0u, metric = 100u), 0.0)
        assertTrue(accepted)
        assertEquals(0u, table.lookupRoute(destA)?.seqNo)
    }

    // ── processHello ───────────────────────────────────────────────────────────

    private fun installRoute(
        table: RoutingTable,
        dest: ByteArray,
        nextHop: ByteArray,
        seqNo: UShort,
        metric: Double = 1.0,
        edKey: ByteArray = peerAEdKey,
        dhKey: ByteArray = peerADhKey,
    ) {
        table.install(
            RouteEntry(
                destination = dest,
                nextHop = nextHop,
                metric = metric,
                seqNo = seqNo,
                feasibilityDistance = metric,
                expiresAt = Long.MAX_VALUE,
                ed25519PublicKey = edKey,
                x25519PublicKey = dhKey,
            )
        )
    }

    // (h) new neighbour → full dump
    @Test
    fun `processHello new neighbor returns full dump`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        installRoute(table, destA, peerA, 100u)
        installRoute(table, destB, peerA, 200u)

        // peerB is new (not registered)
        val updates = engine.processHello(peerB, Hello(peerB, 1u, 0u))
        assertEquals(2, updates.size)
        assertTrue(updates.any { it.destination.contentEquals(destA) })
        assertTrue(updates.any { it.destination.contentEquals(destB) })
    }

    // (i) digest mismatch → full dump
    @Test
    fun `processHello digest mismatch triggers full dump`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        installRoute(table, destA, peerA, 100u)
        engine.registerNeighbor(peerB)

        // Force digest mismatch by passing wrong routeDigest
        val wrongDigest = (table.routeDigest() xor 0xDEADBEEFu)
        val updates = engine.processHello(peerB, Hello(peerB, 1u, wrongDigest))
        assertEquals(1, updates.size)
        assertTrue(updates[0].destination.contentEquals(destA))
    }

    // (j) digest match → differential updates only
    @Test
    fun `processHello digest match returns differential updates`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        // Install self-route, routeA (seqNo=10), routeB (seqNo=20)
        installRoute(table, localPeerId, localPeerId, 1u, 0.0, localEdKey, localDhKey)
        installRoute(table, destA, peerA, 10u)
        installRoute(table, destB, peerA, 20u)

        // First hello from peerB: full dump establishes tracking {local:1, A:10, B:20}
        engine.processHello(peerB, Hello(peerB, 1u, 0u))

        // Update routeB seqNo→21 (changed), install routeC (new, untracked)
        installRoute(table, destB, peerA, 21u)
        installRoute(table, destC, peerA, 30u)

        // routeA (seqNo=10) unchanged → should NOT appear
        // routeB (seqNo=21, was 20) changed → should appear
        // routeC (seqNo=30, untracked) → should appear
        // self-route (isSelf=true) → always appears
        val updates = engine.processHello(peerB, Hello(peerB, 2u, table.routeDigest()))
        val dests = updates.map { it.destination.toList() }.toSet()

        assertFalse(dests.contains(destA.toList())) // unchanged: excluded
        assertTrue(dests.contains(destB.toList())) // seqNo changed
        assertTrue(dests.contains(destC.toList())) // new/untracked
        assertTrue(dests.contains(localPeerId.toList())) // self-route always included
    }

    // negative: new neighbour, empty routing table → returns []
    @Test
    fun `processHello new neighbor with empty table returns empty list`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        val updates = engine.processHello(peerA, Hello(peerA, 1u, 0u))
        assertTrue(updates.isEmpty())
    }

    // negative: digest mismatch, empty table → returns []
    @Test
    fun `processHello digest mismatch with empty table returns empty list`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        engine.registerNeighbor(peerA)
        val updates = engine.processHello(peerA, Hello(peerA, 1u, 0xFFFFFFFFu))
        assertTrue(updates.isEmpty())
    }

    // negative: digest match, empty table → differential loop not entered, returns []
    @Test
    fun `processHello digest match with empty table returns empty list`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        engine.registerNeighbor(peerA)
        // Both table and hello routeDigest are 0 → match
        val updates = engine.processHello(peerA, Hello(peerA, 1u, 0u))
        assertTrue(updates.isEmpty())
    }

    // negative: new neighbour, table has only self-route → returns self-route only
    @Test
    fun `processHello new neighbor with only self-route returns self-route only`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        installRoute(table, localPeerId, localPeerId, 1u, 0.0, localEdKey, localDhKey)
        val updates = engine.processHello(peerA, Hello(peerA, 1u, 0u))
        assertEquals(1, updates.size)
        assertContentEquals(localPeerId, updates[0].destination)
    }

    // ── retractRoutesVia ───────────────────────────────────────────────────────

    // (m) retractRoutesVia: removes matching routes and returns retraction Updates
    @Test
    fun `retractRoutesVia removes routes via peer and leaves others`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        // Install route via peerA and route via peerB
        installRoute(table, destA, peerA, 100u)
        installRoute(table, destB, peerB, 200u)

        val retractions = engine.retractRoutesVia(peerA)

        // Only destA was via peerA
        assertEquals(1, retractions.size)
        assertContentEquals(destA, retractions[0].destination)
        assertEquals(METRIC_RETRACTION, retractions[0].metric)
        assertEquals(101u, retractions[0].seqNo)

        // destA removed; destB still present
        assertNull(table.lookupRoute(destA))
        assertNotNull(table.lookupRoute(destB))
    }

    // negative: retractRoutesVia for non-existent peer → empty list (empty loop)
    @Test
    fun `retractRoutesVia non-existent peer returns empty list`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        val result = engine.retractRoutesVia(peerA)
        assertTrue(result.isEmpty())
    }

    // ── registerNeighbor / unregisterNeighbor ─────────────────────────────────

    @Test
    fun `registerNeighbor then processHello uses digest check not new-neighbor path`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        installRoute(table, destA, peerA, 100u)
        engine.registerNeighbor(peerB)

        // Registered neighbor with matching digest → differential (empty because sentSeqNos
        // unknown)
        // sentSeqNo for destA is null → included (sentSeqNo == null branch)
        val updates = engine.processHello(peerB, Hello(peerB, 1u, table.routeDigest()))
        // destA has no sentSeqNo → included in differential
        assertEquals(1, updates.size)
        assertContentEquals(destA, updates[0].destination)
    }

    @Test
    fun `unregisterNeighbor removes tracking so next hello is treated as new neighbor`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        engine.registerNeighbor(peerA)
        engine.unregisterNeighbor(peerA)

        installRoute(table, destA, peerA, 100u)
        // After unregister, peerA is new again → full dump
        val updates = engine.processHello(peerA, Hello(peerA, 1u, table.routeDigest()))
        assertEquals(1, updates.size)
    }

    // ── timer tests ───────────────────────────────────────────────────────────

    // (k) self-route installed in table after hello timer fires
    @Test
    fun `self-route is installed after helloInterval elapses`() = runTest {
        val table = RoutingTable { testScheduler.currentTime }
        val engine =
            makeEngine(
                table,
                { testScheduler.currentTime },
                backgroundScope,
                RoutingConfig(helloIntervalMillis = 1000L),
            )

        engine.startTimers()
        testScheduler.runCurrent() // let timer coroutines reach first delay()
        testScheduler.advanceTimeBy(1000L)
        testScheduler.runCurrent() // process timer bodies

        assertNotNull(table.lookupRoute(localPeerId))
        assertContentEquals(localEdKey, table.lookupRoute(localPeerId)!!.ed25519PublicKey)
    }

    // (n) Hello broadcast emitted to outboundMessages after hello timer fires
    @Test
    fun `Hello is emitted on outboundMessages after helloInterval`() = runTest {
        val table = RoutingTable { testScheduler.currentTime }
        val engine =
            makeEngine(
                table,
                { testScheduler.currentTime },
                backgroundScope,
                RoutingConfig(helloIntervalMillis = 1000L),
            )

        val received = mutableListOf<OutboundFrame>()
        val job = launch { engine.outboundMessages.collect { received.add(it) } }
        testScheduler.runCurrent() // let collector reach suspension point

        engine.startTimers()
        testScheduler.runCurrent() // let timer coroutines reach first delay()
        testScheduler.advanceTimeBy(1000L)
        testScheduler.runCurrent() // process timer bodies + collector

        val hellos = received.filter { it.message is Hello && it.peerId == null }
        assertTrue(hellos.isNotEmpty())
        val hello = hellos[0].message as Hello
        assertContentEquals(localPeerId, hello.sender)
        assertEquals(1u, hello.seqNo)

        job.cancel()
    }

    // full dump: first tick with empty table (loop not entered), second tick with self-route
    @Test
    fun `full dump emits Updates for routes and handles empty table`() = runTest {
        val table = RoutingTable { testScheduler.currentTime }
        val engine =
            makeEngine(
                table,
                { testScheduler.currentTime },
                backgroundScope,
                // fullDumpMultiplier=1: full dump fires at same time as Hello each tick
                // full dump is launched first → fires before Hello on same tick
                RoutingConfig(helloIntervalMillis = 1000L, fullDumpMultiplier = 1),
            )

        val received = mutableListOf<OutboundFrame>()
        val job = launch { engine.outboundMessages.collect { received.add(it) } }
        testScheduler.runCurrent()

        engine.startTimers()
        testScheduler.runCurrent() // let timer coroutines reach first delay()

        // First tick (t=1000): full dump fires first (empty table → no Updates),
        // then Hello fires and installs self-route.
        testScheduler.advanceTimeBy(1000L)
        testScheduler.runCurrent()

        @Suppress("UNUSED_VARIABLE")
        val updatesAfterFirstTick = received.filter { it.message is Update && it.peerId == null }

        // Second tick (t=2000): full dump fires first again, this time self-route is in table.
        val countBefore = received.size
        testScheduler.advanceTimeBy(1000L)
        testScheduler.runCurrent()

        val newFrames = received.subList(countBefore, received.size)
        val fullDumpUpdates = newFrames.filter { it.message is Update && it.peerId == null }
        // Full dump should emit at least the self-route Update
        assertTrue(fullDumpUpdates.isNotEmpty())
        assertTrue(
            fullDumpUpdates.any { (it.message as Update).destination.contentEquals(localPeerId) }
        )

        job.cancel()
    }

    // seqNo wraparound in localSeqNo via Hello timer
    @Test
    fun `localSeqNo wraps around from 65535 to 0`() = runTest {
        val table = RoutingTable { testScheduler.currentTime }
        val engine =
            makeEngine(
                table,
                { testScheduler.currentTime },
                backgroundScope,
                RoutingConfig(helloIntervalMillis = 100L),
            )
        engine.localSeqNo = 65535u
        engine.startTimers()
        testScheduler.advanceTimeBy(100L)
        testScheduler.runCurrent()
        assertEquals(0u, engine.localSeqNo)
        assertEquals(0u, table.lookupRoute(localPeerId)?.seqNo)
    }

    // metric encoding in routeToUpdate: ensure wire metric round-trips through processHello
    @Test
    fun `routeToUpdate encodes metric correctly via processHello full dump`() = runTest {
        var now = 0L
        val table = RoutingTable { now }
        val engine = makeEngine(table, { now }, backgroundScope)

        // Install route with stored metric 2.0 (wire equivalent 200)
        installRoute(table, destA, peerA, 100u, 2.0)

        val updates = engine.processHello(peerB, Hello(peerB, 1u, 0u))
        assertEquals(1, updates.size)
        assertEquals(200u, updates[0].metric)
    }
}
