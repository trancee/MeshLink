package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DensityClass
import ch.trancee.meshlink.DiagnosticEvent.RelayRateLimited.Frame
import ch.trancee.meshlink.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The per-link token buckets a relay checks before it relays a flood frame (spec "Relay rate limits").
class NeighborLimitsTest {
    private val a = nodeId(1)
    private val b = nodeId(2)
    private var now = 0L
    private var density = DensityClass.Low
    private val dropped = mutableListOf<Pair<NodeId, Frame>>()
    private val clock =
        object : Clock {
            override fun monotonicMillis(): Long = now

            override fun wallClockMillis(): Long = now
        }

    private fun limits(
        secured: Boolean = false,
        originsMax: Int = NeighborLimits.ORIGINS_PER_LINK,
    ) = NeighborLimits(clock, { density }, secured, originsMax) { peer, frame -> dropped += peer to frame }

    // How many of [count] frames are admitted in a row.
    private fun NeighborLimits.admitted(
        count: Int,
        frame: Frame,
        peer: NodeId = a,
        origin: NodeId? = null,
    ): Int = (1..count).count { admits(peer, frame, origin) }

    @Test
    fun aLinkRelaysItsBroadcastBurstThenOnePerRefill() {
        // Arrange
        val limits = limits()

        // Act
        val burst = limits.admitted(21, Frame.Broadcast)
        now += 250
        val refilled = limits.admitted(2, Frame.Broadcast)

        // Assert
        assertEquals(20, burst)
        assertEquals(1, refilled)
        assertEquals(List(2) { a to Frame.Broadcast }, dropped)
    }

    @Test
    fun eachLinkAndFrameTypeHasItsOwnBudget() {
        // Arrange
        val limits = limits()
        limits.admitted(20, Frame.Broadcast)

        // Act
        val other = limits.admitted(21, Frame.Broadcast, peer = b)
        val requests = limits.admitted(21, Frame.RouteRequest)
        val lookups = limits.admitted(21, Frame.LookupRequest)

        // Assert
        assertEquals(listOf(20, 20, 20), listOf(other, requests, lookups))
    }

    @Test
    fun floodedUnicastFollowsTheCurrentDensityClass() {
        // Arrange
        val limits = limits()

        // Act
        val low = limits.admitted(21, Frame.Unicast)
        density = DensityClass.Medium
        now += 1_000
        val medium = limits.admitted(11, Frame.Unicast)
        density = DensityClass.High
        now += 10_000
        val high = limits.admitted(41, Frame.Unicast)

        // Assert
        assertEquals(listOf(20, 10, 40), listOf(low, medium, high))
    }

    @Test
    fun aSecuredRelayGivesEachOriginItsShareOfTheLinkTotal() {
        // Arrange
        val limits = limits(secured = true)
        val origins = (10..14).map(::nodeId)

        // Act
        val first = limits.admitted(15, Frame.Broadcast, origin = origins[0])
        val others = origins.drop(1).map { limits.admitted(6, Frame.Broadcast, origin = it) }

        // Assert
        assertEquals(5, first)
        assertEquals(listOf(5, 5, 5, 0), others)
    }

    @Test
    fun anUnsecuredRelayIgnoresTheClaimedOrigin() {
        // Arrange
        val limits = limits()

        // Act
        val admitted = limits.admitted(21, Frame.Broadcast, origin = nodeId(10))

        // Assert
        assertEquals(20, admitted)
    }

    @Test
    fun aNewOriginPastTheBoundEvictsTheLeastRecentlyUsed() {
        // Arrange
        val limits = limits(secured = true, originsMax = 2)
        val (o1, o2, o3) = (10..12).map(::nodeId)
        limits.admitted(5, Frame.Broadcast, origin = o1)
        limits.admitted(1, Frame.Broadcast, origin = o2)
        limits.admitted(1, Frame.Broadcast, origin = o1)

        // Act
        limits.admitted(1, Frame.Broadcast, origin = o3)
        val o1Kept = limits.admitted(1, Frame.Broadcast, origin = o1)
        val o2Evicted = limits.admitted(6, Frame.Broadcast, origin = o2)

        // Assert
        assertEquals(0, o1Kept)
        assertEquals(5, o2Evicted)
    }

    @Test
    fun aClosedLinkForgetsItsBudgets() {
        // Arrange
        val limits = limits()
        limits.admitted(20, Frame.Broadcast)

        // Act
        limits.unlinked(a)

        // Assert
        assertTrue(limits.admits(a, Frame.Broadcast, null))
    }

    @Test
    fun admittingEverythingRelaysEveryFrame() {
        // Arrange
        val all = Admission.ALL

        // Act
        val admitted = (1..100).count { all.admits(a, Frame.Broadcast, null) }
        all.unlinked(a)

        // Assert
        assertEquals(100, admitted)
    }
}
