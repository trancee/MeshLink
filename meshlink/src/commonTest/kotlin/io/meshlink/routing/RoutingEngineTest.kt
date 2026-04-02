package io.meshlink.routing

import io.meshlink.util.ByteArrayKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoutingEngineTest {

    private fun key(s: String) = ByteArrayKey(s.encodeToByteArray())

    /** 8-byte key required by WireCodec for peer IDs in AODV frames. */
    private fun peerId(index: Int) = ByteArrayKey(ByteArray(8) { ((index shl 4) + it).toByte() })

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
}
