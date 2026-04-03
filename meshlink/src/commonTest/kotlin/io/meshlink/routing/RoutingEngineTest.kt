package io.meshlink.routing

import io.meshlink.util.ByteArrayKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingEngineTest {

    private fun key(s: String) = ByteArrayKey(s.encodeToByteArray())

    /** 8-byte key required by WireCodec for peer IDs in AODV frames. */
    private fun peerId(index: Int) = ByteArrayKey(ByteArray(12) { ((index shl 4) + it).toByte() })

    private val localId = peerId(0)
    private fun engine(
        dedupCapacity: Int = 100,
        routeCacheTtlMillis: Long = 60_000L,
        maxHops: UByte = 10u,
        clock: () -> Long = { 0L },
    ) = RoutingEngine(
        localPeerId = localId,
        dedupCapacity = dedupCapacity,
        routeCacheTtlMillis = routeCacheTtlMillis,
        maxHops = maxHops,
        clock = clock,
    )

    // ── 1. Next-hop resolution ────────────────────────────────────

    @Test
    fun directPeerResolvedWithoutRouteTable() {
        val re = engine()
        re.peerSeen(key("peer1"))
        val result = re.resolveNextHop(key("peer1"))
        assertIs<NextHopResult.Direct>(result)
        assertEquals(key("peer1"), result.peerId)
    }

    @Test
    fun routedPeerResolvesViaRouteTable() {
        val re = engine()
        re.addRoute(key("remote"), key("relay"), 2.0, 1u)
        val result = re.resolveNextHop(key("remote"))
        assertIs<NextHopResult.ViaRoute>(result)
        assertEquals(key("relay"), result.nextHop)
    }

    @Test
    fun unknownPeerIsUnreachable() {
        val re = engine()
        assertIs<NextHopResult.Unreachable>(re.resolveNextHop(key("unknown")))
    }

    @Test
    fun directPeerPreferredOverRoute() {
        val re = engine()
        re.peerSeen(key("peer1"))
        re.addRoute(key("peer1"), key("relay"), 5.0, 1u)
        val result = re.resolveNextHop(key("peer1"))
        assertIs<NextHopResult.Direct>(result)
    }

    // ── 2. AODV Route Discovery (initiateRouteDiscovery) ──────────

    @Test
    fun initiateRouteDiscoveryReturnsRreqFrame() {
        val re = engine()
        val discovery = re.initiateRouteDiscovery(peerId(1))
        assertTrue(discovery.rreqFrame.isNotEmpty())
        assertEquals(0x03, discovery.rreqFrame[0].toInt(), "Frame should be TYPE_ROUTE_REQUEST")
    }

    @Test
    fun initiateRouteDiscoveryIncrementsRequestId() {
        val re = engine()
        val d1 = re.initiateRouteDiscovery(peerId(1))
        val d2 = re.initiateRouteDiscovery(peerId(2))
        assertTrue(d2.requestId > d1.requestId, "Request IDs should increment")
    }

    // ── 3. AODV handleRouteRequest ────────────────────────────────

    @Test
    fun handleRouteRequestForLocalPeerReplies() {
        val re = engine()
        val origin = peerId(1)
        val result = re.handleRouteRequest(
            fromPeerId = peerId(2),
            originPeerId = origin,
            destinationPeerId = localId,
            requestId = 1u,
            hopCount = 1u,
            hopLimit = 10u,
        )
        assertIs<RouteRequestResult.Reply>(result)
        assertEquals(peerId(2), result.replyTo)
        assertTrue(result.replyFrame.isNotEmpty())
    }

    @Test
    fun handleRouteRequestForUnknownDestinationFloods() {
        val re = engine()
        val result = re.handleRouteRequest(
            fromPeerId = peerId(2),
            originPeerId = peerId(1),
            destinationPeerId = peerId(9),
            requestId = 1u,
            hopCount = 0u,
            hopLimit = 10u,
        )
        assertIs<RouteRequestResult.Flood>(result)
        assertTrue(result.rreqFrame.isNotEmpty())
    }

    @Test
    fun handleRouteRequestWithCachedRouteReplies() {
        val re = engine()
        re.addRoute(peerId(3), peerId(4), 2.0, 1u)
        val result = re.handleRouteRequest(
            fromPeerId = peerId(2),
            originPeerId = peerId(1),
            destinationPeerId = peerId(3),
            requestId = 1u,
            hopCount = 0u,
            hopLimit = 10u,
        )
        assertIs<RouteRequestResult.Reply>(result)
    }

    @Test
    fun handleRouteRequestDuplicateIsDropped() {
        val re = engine()
        re.handleRouteRequest(
            fromPeerId = peerId(2),
            originPeerId = peerId(1),
            destinationPeerId = peerId(3),
            requestId = 1u,
            hopCount = 0u,
            hopLimit = 10u,
        )
        val result = re.handleRouteRequest(
            fromPeerId = peerId(4),
            originPeerId = peerId(1),
            destinationPeerId = peerId(3),
            requestId = 1u,
            hopCount = 0u,
            hopLimit = 10u,
        )
        assertIs<RouteRequestResult.Drop>(result)
    }

    @Test
    fun handleRouteRequestAtHopLimitIsDropped() {
        val re = engine(maxHops = 3u)
        val result = re.handleRouteRequest(
            fromPeerId = peerId(2),
            originPeerId = peerId(1),
            destinationPeerId = peerId(3),
            requestId = 1u,
            hopCount = 3u,
            hopLimit = 3u,
        )
        assertIs<RouteRequestResult.Drop>(result)
    }

    @Test
    fun handleRouteRequestInstallsReverseRoute() {
        val re = engine()
        re.handleRouteRequest(
            fromPeerId = peerId(2),
            originPeerId = peerId(1),
            destinationPeerId = peerId(3),
            requestId = 1u,
            hopCount = 2u,
            hopLimit = 10u,
        )
        val route = re.bestRoute(peerId(1))
        assertNotNull(route, "Reverse route to origin should be installed")
        assertEquals(peerId(2), route.nextHop)
    }

    // ── 4. AODV handleRouteReply ──────────────────────────────────

    @Test
    fun handleRouteReplyAsOriginatorResolves() {
        val re = engine()
        re.initiateRouteDiscovery(peerId(3))
        val result = re.handleRouteReply(
            fromPeerId = peerId(2),
            originPeerId = localId,
            destinationPeerId = peerId(3),
            requestId = 0u,
            hopCount = 1u,
        )
        assertIs<RouteReplyResult.Resolved>(result)
        assertEquals(peerId(3), result.destination)
    }

    @Test
    fun handleRouteReplyInstallsForwardRoute() {
        val re = engine()
        re.handleRouteReply(
            fromPeerId = peerId(2),
            originPeerId = localId,
            destinationPeerId = peerId(3),
            requestId = 0u,
            hopCount = 2u,
        )
        val route = re.bestRoute(peerId(3))
        assertNotNull(route, "Forward route to destination should be installed")
        assertEquals(peerId(2), route.nextHop)
    }

    @Test
    fun handleRouteReplyForwardsAlongReversePath() {
        val re = engine()
        re.handleRouteRequest(
            fromPeerId = peerId(2),
            originPeerId = peerId(1),
            destinationPeerId = peerId(3),
            requestId = 5u,
            hopCount = 0u,
            hopLimit = 10u,
        )
        val result = re.handleRouteReply(
            fromPeerId = peerId(4),
            originPeerId = peerId(1),
            destinationPeerId = peerId(3),
            requestId = 5u,
            hopCount = 1u,
        )
        assertIs<RouteReplyResult.Forward>(result)
        assertEquals(peerId(2), result.nextHop)
    }

    @Test
    fun handleRouteReplyWithNoReversePathDrops() {
        val re = engine()
        val result = re.handleRouteReply(
            fromPeerId = peerId(2),
            originPeerId = peerId(9),
            destinationPeerId = peerId(3),
            requestId = 99u,
            hopCount = 1u,
        )
        assertIs<RouteReplyResult.Drop>(result)
    }

    // ── 5. Deduplication ──────────────────────────────────────────

    @Test
    fun firstMessageIsNotDuplicate() {
        val re = engine()
        assertFalse(re.isDuplicate(key("msg1")))
    }

    @Test
    fun sameMessageIdIsDuplicate() {
        val re = engine()
        re.isDuplicate(key("msg1"))
        assertTrue(re.isDuplicate(key("msg1")))
    }

    @Test
    fun dedupCapacityEvictsOldest() {
        val re = engine(dedupCapacity = 2)
        re.isDuplicate(key("msg1"))
        re.isDuplicate(key("msg2"))
        re.isDuplicate(key("msg3")) // evicts msg1
        assertFalse(re.isDuplicate(key("msg1"))) // msg1 was evicted, so it's "new" (re-insert evicts msg2)
        assertTrue(re.isDuplicate(key("msg3")))  // msg3 still present
    }

    // ── 6. Presence ───────────────────────────────────────────────

    @Test
    fun sweepEvictsMissingPeers() {
        val re = engine()
        re.peerSeen(key("peer1"))
        re.peerSeen(key("peer2"))
        // First sweep with only peer1 seen: peer2 → DISCONNECTED
        re.sweepPresence(setOf(key("peer1")))
        assertEquals(PresenceState.DISCONNECTED, re.presenceState(key("peer2")))
        // Second sweep without peer2: peer2 → evicted
        val presenceEvicted = re.sweepPresence(setOf(key("peer1")))
        assertTrue(key("peer2") in presenceEvicted)
        assertEquals(1, re.peerCount)
    }

    @Test
    fun connectedVsAllPeerIds() {
        val re = engine()
        re.peerSeen(key("peer1"))
        re.peerSeen(key("peer2"))
        re.markDisconnected(key("peer2"))
        assertEquals(setOf(key("peer1")), re.connectedPeerIds())
        assertEquals(setOf(key("peer1"), key("peer2")), re.allPeerIds())
    }

    // ── 7. Health ─────────────────────────────────────────────────

    @Test
    fun healthMetricsReflectState() {
        val re = engine()
        re.peerSeen(key("peer1"))
        re.peerSeen(key("peer2"))
        re.addRoute(key("dest1"), key("relay"), 3.0, 1u)
        assertEquals(2, re.peerCount)
        assertEquals(2, re.connectedPeerCount)
        assertEquals(1, re.routeCount)
        assertEquals(3.0, re.avgCost())
    }

    // ── 8. Clear ──────────────────────────────────────────────────

    @Test
    fun clearResetsAllState() {
        val re = engine()
        re.peerSeen(key("peer1"))
        re.addRoute(key("dest1"), key("relay"), 2.0, 1u)
        re.isDuplicate(key("msg1"))

        re.clear()

        assertEquals(0, re.peerCount)
        assertEquals(0, re.routeCount)
        assertFalse(re.isDuplicate(key("msg1")))
    }

    // ── Next-hop reliability tracking ──────────────────────────────

    @Test
    fun nextHopFailureRateTracksSuccessesAndFailures() {
        val re = RoutingEngine(peerId(0xF0))
        assertEquals(0.0, re.nextHopFailureRate(key("relay01")))

        re.recordNextHopSuccess(key("relay01"))
        re.recordNextHopSuccess(key("relay01"))
        re.recordNextHopFailure(key("relay01"))
        // 1 failure out of 3 total
        assertEquals(1.0 / 3.0, re.nextHopFailureRate(key("relay01")), 0.001)
        assertEquals(1, re.nextHopFailureCount(key("relay01")))
    }

    @Test
    fun nextHopFailureCountersClearedOnClear() {
        val re = RoutingEngine(peerId(0xF0))
        re.recordNextHopFailure(key("relay01"))
        re.recordNextHopSuccess(key("relay01"))
        re.clear()
        assertEquals(0, re.nextHopFailureCount(key("relay01")))
        assertEquals(0.0, re.nextHopFailureRate(key("relay01")))
    }

    // ── RouteCostCalculator integration ─────────────────────────

    @Test
    fun linkMeasurementAffectsRouteInstallationCost() {
        val local = peerId(0xA0)
        val peer = peerId(0xB0)
        val dest = peerId(0xC0)
        val re = RoutingEngine(local, clock = { 1000L })

        // Record a weak signal measurement for the peer
        re.recordLinkMeasurement(peer, rssi = -80, lossRate = 0.1)

        // Simulate RREQ handling — route cost should incorporate link quality
        re.peerSeen(peer)
        re.handleRouteRequest(
            fromPeerId = peer,
            originPeerId = dest,
            destinationPeerId = local,
            requestId = 1u,
            hopCount = 0u,
            hopLimit = 10u,
        )

        // The reverse route to dest via peer should use the composite cost
        val route = re.bestRoute(dest)
        assertNotNull(route)
        // With RSSI=-80 (base=6), loss=0.1 (mult=2.0), fresh (1.0), new link (+3)
        // link_cost = 6*2.0*1.0 + 3 = 15.0; route_cost = 15.0 * (0+1) = 15.0
        assertTrue(route.cost > 1.0, "Cost should be higher than default hop-count 1.0, got ${route.cost}")
    }

    @Test
    fun stableIntervalReducesCost() {
        val local = peerId(0xA0)
        val peer = peerId(0xB0)
        val re = RoutingEngine(local, clock = { 1000L })

        re.recordLinkMeasurement(peer, rssi = -50, lossRate = 0.0)
        val costNew = re.computeLinkCost(peer)

        // After 3 stable intervals, stability penalty decays to 0
        repeat(3) { re.recordStableInterval(peer) }
        val costStable = re.computeLinkCost(peer)

        assertTrue(costStable < costNew, "Stable link should have lower cost: $costStable < $costNew")
    }

    @Test
    fun disconnectResetsCostStability() {
        val local = peerId(0xA0)
        val peer = peerId(0xB0)
        val re = RoutingEngine(local, clock = { 1000L })

        re.recordLinkMeasurement(peer, rssi = -50, lossRate = 0.0)
        repeat(3) { re.recordStableInterval(peer) }
        val costBefore = re.computeLinkCost(peer)

        re.recordLinkDisconnect(peer)
        val costAfter = re.computeLinkCost(peer)

        assertTrue(costAfter > costBefore, "Disconnect should increase cost: $costAfter > $costBefore")
    }

    @Test
    fun defaultCostWhenNoMeasurement() {
        val local = peerId(0xA0)
        val peer = peerId(0xB0)
        val re = RoutingEngine(local, clock = { 1000L })

        assertEquals(RouteCostCalculator.DEFAULT_COST, re.computeLinkCost(peer))
    }

    @Test
    fun sweepClearsLinkState() {
        val local = peerId(0xA0)
        val peer = peerId(0xB0)
        val re = RoutingEngine(local, clock = { 1000L })

        re.peerSeen(peer)
        re.recordLinkMeasurement(peer, rssi = -60, lossRate = 0.0)
        assertTrue(re.computeLinkCost(peer) > RouteCostCalculator.DEFAULT_COST)

        // Sweep with empty set — peer evicted, link state cleared
        re.sweepPresence(emptySet())
        re.sweepPresence(emptySet()) // 2nd sweep for 2-consecutive-miss eviction

        // After eviction, cost returns to default
        assertEquals(RouteCostCalculator.DEFAULT_COST, re.computeLinkCost(peer))
    }

    // ── Babel Hello/Update ──────────────────────────────────────

    @Test
    fun helloFromNewNeighborTriggersFullUpdate() {
        val local = peerId(1)
        val neighbor = peerId(2)
        val re = RoutingEngine(local, clock = { 1000L })

        // First hello from a new neighbor should return update frames
        val updates = re.handleHello(neighbor, senderSeqno = 1u)
        assertTrue(updates.isNotEmpty(), "Should send routing table to new neighbor")
    }

    @Test
    fun helloFromKnownNeighborReturnsEmpty() {
        val local = peerId(1)
        val neighbor = peerId(2)
        val re = RoutingEngine(local, clock = { 1000L })

        re.handleHello(neighbor, senderSeqno = 1u) // first hello
        val updates = re.handleHello(neighbor, senderSeqno = 2u) // second hello
        assertTrue(updates.isEmpty(), "Known neighbor should not trigger full update")
    }

    @Test
    fun updateInstallsRouteAndPropagatesKey() {
        val local = peerId(1)
        val neighbor = peerId(2)
        val dest = peerId(3)
        val destKey = ByteArray(32) { (0xDD + it).toByte() }
        val re = RoutingEngine(local, clock = { 1000L })

        re.peerSeen(neighbor)
        val propagated = re.handleUpdate(neighbor, dest, metric = 2u, seqno = 1u, publicKey = destKey)

        assertNotNull(propagated, "Should propagate public key")
        assertContentEquals(destKey, propagated)
        assertNotNull(re.bestRoute(dest), "Route should be installed")
        assertContentEquals(destKey, re.getPublicKey(dest))
    }

    @Test
    fun updateFeasibilityRejectsOlderSeqno() {
        val local = peerId(1)
        val neighbor = peerId(2)
        val dest = peerId(3)
        val key = ByteArray(32) { 0x11 }
        val re = RoutingEngine(local, clock = { 1000L })

        re.peerSeen(neighbor)
        re.handleUpdate(neighbor, dest, metric = 5u, seqno = 10u, publicKey = key)
        val route1 = re.bestRoute(dest)!!

        // Older seqno should be rejected
        re.handleUpdate(neighbor, dest, metric = 1u, seqno = 5u, publicKey = key)
        val route2 = re.bestRoute(dest)!!
        assertEquals(route1.sequenceNumber, route2.sequenceNumber, "Older seqno rejected")
    }

    @Test
    fun updateFeasibilityAcceptsNewerSeqno() {
        val local = peerId(1)
        val neighbor = peerId(2)
        val dest = peerId(3)
        val key = ByteArray(32) { 0x11 }
        val re = RoutingEngine(local, clock = { 1000L })

        re.peerSeen(neighbor)
        re.handleUpdate(neighbor, dest, metric = 5u, seqno = 1u, publicKey = key)

        // Newer seqno should be accepted even with worse metric
        re.handleUpdate(neighbor, dest, metric = 100u, seqno = 2u, publicKey = key)
        val route = re.bestRoute(dest)!!
        assertEquals(2u, route.sequenceNumber)
    }

    @Test
    fun buildHelloEncodesCorrectly() {
        val local = peerId(1)
        val re = RoutingEngine(local, clock = { 1000L })
        val hello = re.buildHello()
        val decoded = io.meshlink.wire.WireCodec.decodeHello(hello)
        assertContentEquals(local.bytes, decoded.sender)
        assertEquals(0u.toUShort(), decoded.seqno)

        re.bumpSeqno()
        val hello2 = re.buildHello()
        val decoded2 = io.meshlink.wire.WireCodec.decodeHello(hello2)
        assertEquals(1u.toUShort(), decoded2.seqno)
    }

    @Test
    fun registerAndGetPublicKey() {
        val local = peerId(1)
        val peer = peerId(2)
        val key = ByteArray(32) { 0xAA.toByte() }
        val re = RoutingEngine(local, clock = { 1000L })

        assertNull(re.getPublicKey(peer))
        re.registerPublicKey(peer, key)
        assertContentEquals(key, re.getPublicKey(peer))
    }
}
