package io.meshlink.routing

import io.meshlink.util.ByteArrayKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoutingEngineTest {

    private fun key(s: String) = ByteArrayKey(s.encodeToByteArray())

    private val localId = key("aabb")
    private fun engine(
        gossipIntervalMillis: Long = 1000L,
        dedupCapacity: Int = 100,
        triggeredUpdateThreshold: Double = 0.3,
        clock: () -> Long = { 0L },
    ) = RoutingEngine(
        localPeerId = localId,
        dedupCapacity = dedupCapacity,
        triggeredUpdateThreshold = triggeredUpdateThreshold,
        gossipIntervalMillis = gossipIntervalMillis,
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

    // ── 2. Route learning ─────────────────────────────────────────

    @Test
    fun learnNewRouteIsSignificantChange() {
        val re = engine()
        val result = re.learnRoutes(key("relay1"), listOf(LearnedRoute(key("dest1"), 1.0, 1u)))
        assertIs<RouteLearnResult.SignificantChange>(result)
        // Route should now be reachable with cost + 1
        val hop = re.resolveNextHop(key("dest1"))
        assertIs<NextHopResult.ViaRoute>(hop)
        assertEquals(key("relay1"), hop.nextHop)
    }

    @Test
    fun learnRouteToSelfIsIgnored() {
        val re = engine()
        val result = re.learnRoutes(key("relay1"), listOf(LearnedRoute(localId, 1.0, 1u)))
        assertIs<RouteLearnResult.NoSignificantChange>(result)
        assertIs<NextHopResult.Unreachable>(re.resolveNextHop(localId))
    }

    @Test
    fun smallCostChangeIsNotSignificant() {
        val re = engine(triggeredUpdateThreshold = 0.3)
        re.addRoute(key("dest1"), key("relay1"), 10.0, 1u)
        // Learned cost 8.5 + 1.0 = 9.5, change from 10.0 is 5% — below 30% threshold
        val result = re.learnRoutes(key("relay1"), listOf(LearnedRoute(key("dest1"), 8.5, 2u)))
        assertIs<RouteLearnResult.NoSignificantChange>(result)
    }

    @Test
    fun learnRouteDetectsNextHopChange() {
        val re = engine()
        re.addRoute(key("dest1"), key("relay1"), 5.0, 1u)
        // Better route via relay2 with same sequence
        val result = re.learnRoutes(key("relay2"), listOf(LearnedRoute(key("dest1"), 1.0, 2u)))
        assertIs<RouteLearnResult.SignificantChange>(result)
        assertEquals(1, result.routeChanges.size)
        assertEquals(key("relay1"), result.routeChanges[0].oldNextHop)
        assertEquals(key("relay2"), result.routeChanges[0].newNextHop)
    }

    // ── 3. Split horizon & poison reverse ─────────────────────────

    @Test
    fun splitHorizonOmitsRouteLearnedFromPeer() {
        val re = engine()
        re.addRoute(key("dest1"), key("relay1"), 2.0, 1u)
        // Gossip entries for relay1 should NOT include dest1 (split horizon)
        val entries = re.prepareGossipEntries(key("relay1"))
        assertTrue(entries.isEmpty())
    }

    @Test
    fun poisonReverseSendsWithdrawalToOldNextHop() {
        val re = engine()
        re.addRoute(key("dest1"), key("relay1"), 5.0, 1u)
        // Learn better route via relay2 → triggers next-hop change
        re.learnRoutes(key("relay2"), listOf(LearnedRoute(key("dest1"), 1.0, 2u)))
        // Gossip for relay1 should contain poison reverse (MAX_VALUE cost)
        val entries = re.prepareGossipEntries(key("relay1"))
        assertEquals(1, entries.size)
        assertEquals(Double.MAX_VALUE, entries[0].cost)
        assertEquals(key("dest1"), entries[0].destination)
    }

    @Test
    fun normalGossipEntriesIncludeReachableRoutes() {
        val re = engine()
        re.addRoute(key("dest1"), key("relay1"), 2.0, 1u)
        re.addRoute(key("dest2"), key("relay1"), 3.0, 1u)
        // Gossip for relay2 (not the next-hop) should include both routes
        val entries = re.prepareGossipEntries(key("relay2"))
        assertEquals(2, entries.size)
        val costs = entries.map { it.cost }.toSet()
        assertTrue(2.0 in costs)
        assertTrue(3.0 in costs)
    }

    // ── 4. Deduplication ──────────────────────────────────────────

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

    // ── 5. Gossip timing ──────────────────────────────────────────

    @Test
    fun effectiveGossipIntervalScalesWithPowerMode() {
        val re = engine(gossipIntervalMillis = 1000L)
        assertEquals(1000L, re.effectiveGossipInterval("PERFORMANCE"))
        assertEquals(2000L, re.effectiveGossipInterval("BALANCED"))
        assertEquals(3000L, re.effectiveGossipInterval("POWER_SAVER"))
    }

    @Test
    fun effectiveGossipIntervalScalesWithRouteCount() {
        val re = engine(gossipIntervalMillis = 1000L)
        // Add >100 routes to trigger 1.5x multiplier
        for (i in 0..100) {
            re.addRoute(key("dest$i"), key("relay"), 1.0, 1u)
        }
        assertEquals(1500L, re.effectiveGossipInterval("PERFORMANCE"))
    }

    @Test
    fun triggeredUpdateRateLimited() {
        var now = 0L
        val re = engine(gossipIntervalMillis = 1000L, clock = { now })
        // First triggered update always allowed
        assertTrue(re.shouldSendTriggeredUpdate(key("peer1"), "PERFORMANCE"))
        re.recordTriggeredUpdate(key("peer1"))
        // Immediately after: rate limited
        assertFalse(re.shouldSendTriggeredUpdate(key("peer1"), "PERFORMANCE"))
        // After interval: allowed again
        now = 1000L
        assertTrue(re.shouldSendTriggeredUpdate(key("peer1"), "PERFORMANCE"))
    }

    @Test
    fun timeSinceLastGossipTracked() {
        var now = 0L
        val re = engine(clock = { now })
        re.recordGossipSent()
        now = 500L
        assertEquals(500L, re.timeSinceLastGossip())
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
        var now = 0L
        val re = engine(clock = { now })
        re.peerSeen(key("peer1"))
        re.addRoute(key("dest1"), key("relay"), 2.0, 1u)
        re.isDuplicate(key("msg1"))
        re.recordTriggeredUpdate(key("peer1"))
        re.recordGossipSent()
        now = 100L

        re.clear()

        assertEquals(0, re.peerCount)
        assertEquals(0, re.routeCount)
        assertFalse(re.isDuplicate(key("msg1"))) // dedup cleared, msg1 is "new" again
        assertTrue(re.shouldSendTriggeredUpdate(key("peer1"), "PERFORMANCE"))
        assertEquals(now, re.timeSinceLastGossip()) // lastGossipSentMillis reset to 0
    }

    // ── Next-hop reliability tracking ──────────────────────────────

    @Test
    fun nextHopFailureRateTracksSuccessesAndFailures() {
        val re = RoutingEngine(key("local"))
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
        val re = RoutingEngine(key("local"))
        re.recordNextHopFailure(key("relay01"))
        re.recordNextHopSuccess(key("relay01"))
        re.clear()
        assertEquals(0, re.nextHopFailureCount(key("relay01")))
        assertEquals(0.0, re.nextHopFailureRate(key("relay01")))
    }
}
