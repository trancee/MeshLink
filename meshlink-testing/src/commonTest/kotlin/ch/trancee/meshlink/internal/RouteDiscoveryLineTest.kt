package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Reachability
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Route discovery among `unsecured` nodes whose origin is in the medium class (spec "Route
 * discovery"): only an origin's neighbors are in its range, so a message reaches farther than the
 * 3-hop flood only on a route a route request found.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteDiscoveryLineTest {
    @Test
    fun aSendSixHopsAwayArrivesOnceOnADiscoveredRouteThatEachRelayLearnedFromTheReply() =
        runTest(timeout = 5.minutes) {
            // Arrange: A to G in a line, and S beside D alone, so D floods to S only if it has no route.
            val air = air()
            val line = line(air, LINE)
            val side = air.platform(StandardTestDispatcher(testScheduler))
            air.move(side.radio, D * SPACING, SPACING)
            val nodes = (line + side).map(::node).onEach { it.start() }
            medium(air, line[0])
            val inbox = inbox(nodes[G])
            val g = nodes[G - 1].idOf(line[G])
            val a = nodes[1].idOf(line[0])

            // Act
            nodes[0].send(g, PAYLOAD)
            advanceTimeBy(BEFORE_RETRY_MILLIS)
            val early = inbox.toList()
            val origin =
                nodes[G]
                    .peers.value
                    .single { it.nodeId == a }
                    .reachability
            advanceTimeBy(DELIVERED_MILLIS)

            // Assert: sent on the route found before the outbox's first retry, and once; G lists A while
            // the reverse route its request left holds.
            assertEquals(listOf(PAYLOAD.decodeToString()), early)
            assertEquals(early, inbox)
            // G answered with hop count 0, and each relay counted its hop as it learned the route.
            (1..G).forEach { from ->
                val counts = written(line[from], line[from - 1]).mapNotNull { reply(it)?.hopCount }
                assertEquals(setOf(G - from), counts.toSet(), "replies from ${'A' + from}")
            }
            (0 until G).forEach { from ->
                val limits = written(line[from], line[from + 1]).mapNotNull { message(it)?.header?.hopLimit }
                assertTrue(HOP_LIMIT_MAX - from in limits, "routed message from ${'A' + from}")
            }
            assertEquals(emptyList(), written(line[D], side).mapNotNull(::message))
            val peer = nodes[0].peers.value.single { it.nodeId == g }
            assertEquals(Reachability.Routed(G), peer.reachability)
            assertNull(peer.proximity)
            assertEquals(Reachability.Routed(G), origin)
        }

    @Test
    fun aRoutedPeerLeavesThePeersWhenTheLinkItsRouteTakesCloses() =
        runTest(timeout = 5.minutes) {
            // Arrange
            val air = air()
            val line = line(air, SHORT_LINE)
            val nodes = line.map(::node).onEach { it.start() }
            medium(air, line[0])
            val far = nodes[SHORT_LINE - 2].idOf(line.last())
            nodes[0].send(far, PAYLOAD)
            advanceTimeBy(DELIVERED_MILLIS)
            val before = nodes[0].peers.value.map { it.nodeId }

            // Act
            air.move(line[1].radio, 0.0, AWAY)
            advanceTimeBy(DELIVERED_MILLIS)

            // Assert
            assertTrue(far in before)
            assertEquals(emptyList(), nodes[0].peers.value)
        }

    @Test
    fun aRoutedPeerLeavesThePeersWhenItsRouteExpires() =
        runTest(timeout = 5.minutes) {
            // Arrange
            val air = air()
            val line = line(air, SHORT_LINE)
            val nodes = line.map(::node).onEach { it.start() }
            medium(air, line[0])
            val far = nodes[SHORT_LINE - 2].idOf(line.last())
            nodes[0].send(far, PAYLOAD)
            advanceTimeBy(DELIVERED_MILLIS)
            val routed =
                nodes[0]
                    .peers.value
                    .single { it.nodeId == far }
                    .reachability

            // Act: retries use the route until the message expires; the last route found then expires too.
            advanceTimeBy(DEADLINE_MILLIS + FORWARD_ROUTE_MILLIS)

            // Assert
            assertEquals(Reachability.Routed(SHORT_LINE - 1), routed)
            assertTrue(nodes[0].peers.value.none { it.nodeId == far })
        }

    @Test
    fun aNodeWithAFreshRouteAnswersARequestForItInsteadOfFloodingIt() =
        runTest(timeout = 5.minutes) {
            // Arrange: Z beside A alone; A found its route to E first.
            val air = air()
            val line = line(air, SHORT_LINE)
            val z = air.platform(StandardTestDispatcher(testScheduler)).also { air.move(it.radio, -SPACING, 0.0) }
            val nodes = (line + z).map(::node).onEach { it.start() }
            medium(air, line[0])
            val e = nodes[SHORT_LINE - 2].idOf(line.last())
            val inbox = inbox(nodes[SHORT_LINE - 1])
            nodes[0].send(e, PAYLOAD)
            advanceTimeBy(DELIVERED_MILLIS)
            val requests = written(line[0], line[1]).count { it[0].toInt() and 0xFF == RouteRequest.TYPE }

            // Act
            nodes.last().send(e, PAYLOAD)
            advanceTimeBy(DELIVERED_MILLIS)

            // Assert: A replied with its own hop count to E, and passed no request of Z's on.
            assertEquals(2, inbox.size)
            assertEquals(requests, written(line[0], line[1]).count { it[0].toInt() and 0xFF == RouteRequest.TYPE })
            assertEquals(setOf(SHORT_LINE - 1), written(line[0], z).mapNotNull { reply(it)?.hopCount }.toSet())
            assertEquals(
                Reachability.Routed(SHORT_LINE),
                nodes
                    .last()
                    .peers.value
                    .single { it.nodeId == e }
                    .reachability,
            )
        }

    @Test
    fun aMessageOnABrokenRouteArrivesByTheResendsNewSearchOverTheOtherPath() =
        runTest(timeout = 5.minutes) {
            // Arrange: A reaches T through B or D; the route A found goes through relay R.
            val air = air()
            val platforms = List(4) { air.platform(StandardTestDispatcher(testScheduler)) }
            val (a, b, d) = platforms
            val t = platforms.last()
            air.move(b.radio, SPACING, OFFSET)
            air.move(d.radio, SPACING, -OFFSET)
            air.move(t.radio, 2 * SPACING, 0.0)
            val nodes = listOf(a, b, d, t).map(::node).onEach { it.start() }
            medium(air, a)
            val inbox = inbox(nodes[3])
            val tId = nodes[1].idOf(t)
            nodes[0].send(tId, PAYLOAD)
            advanceTimeBy(DELIVERED_MILLIS)
            val routed = written(a, b).any { message(it)?.header?.hopLimit == HOP_LIMIT_MAX }
            val (relay, other) = if (routed) b to d else d to b

            // Act: R moves out of T's range but stays A's neighbor, and drops what it can't forward.
            air.move(relay.radio, SPACING - AWAY_X, if (relay == b) AWAY_Y else -AWAY_Y)
            nodes[0].send(tId, SECOND)
            advanceTimeBy(DELIVERED_MILLIS)

            // Assert
            assertEquals(listOf(PAYLOAD, SECOND).map { it.decodeToString() }, inbox)
            assertTrue(written(other, t).mapNotNull(::message).isNotEmpty())
        }

    @Test
    fun aMessageSentOnAFoundRouteIsResentByANewSearchAfterResendBasePlusResendPerHop() =
        runTest(timeout = 5.minutes) {
            // Arrange
            val air = air()
            val line = line(air, SHORT_LINE)
            val nodes = line.map(::node).onEach { it.start() }
            medium(air, line[0])
            val e = nodes[SHORT_LINE - 2].idOf(line.last())
            val requests = { written(line[0], line[1]).count { it[0].toInt() and 0xFF == RouteRequest.TYPE } }

            // Act
            nodes[0].send(e, PAYLOAD)
            advanceTimeBy(RESEND_MILLIS - MARGIN_MILLIS)
            val before = requests()
            advanceTimeBy(2 * MARGIN_MILLIS)

            // Assert: the send's search, then the resend's.
            assertEquals(1, before)
            assertEquals(2, requests())
        }

    @Test
    fun aLowClassOriginSendsNothingMoreWhenARequestGivesItARouteToItsMessagesDestination() =
        runTest(timeout = 5.minutes) {
            // Arrange: A in the low class floods its message to C; C, in the medium class, searches for A.
            val air = air()
            val line = line(air, 3)
            val nodes = line.map(::node).onEach { it.start() }
            medium(air, line.last(), FAKE_DISTANCE)
            val c = nodes[1].idOf(line.last())
            val a = nodes[1].idOf(line[0])
            nodes[0].send(c, PAYLOAD)
            advanceTimeBy(WRITTEN_MILLIS)
            val sent = written(line[0], line[1]).mapNotNull(::message).size

            // Act
            nodes.last().send(a, SECOND)
            advanceTimeBy(BEFORE_RETRY_MILLIS - WRITTEN_MILLIS)

            // Assert: C's request reached A, which sent its message no second time before its first retry.
            assertTrue(written(line[1], line[0]).any { it[0].toInt() and 0xFF == RouteRequest.TYPE })
            assertEquals(sent, written(line[0], line[1]).mapNotNull(::message).size)
        }

    @Test
    fun aFoundRouteSendsAgainOnlyTheMessagesWaitingForItsDestination() =
        runTest(timeout = 5.minutes) {
            // Arrange: A's message to its neighbor B waits for its next retry when A sends to E.
            val air = air()
            val line = line(air, SHORT_LINE)
            val nodes = line.map(::node).onEach { it.start() }
            medium(air, line[0])
            val b = nodes[0].idOf(line[1])
            val e = nodes[SHORT_LINE - 2].idOf(line.last())
            val inbox = inbox(nodes.last())
            nodes[0].send(b, PAYLOAD)
            advanceTimeBy(WRITTEN_MILLIS)
            val toB = message(written(line[0], line[1]).first(::isMessage))!!.header.destinationTag
            advanceTimeBy(SETTLED_MILLIS)
            val before = messagesTo(line[0], line[1], toB)

            // Act
            nodes[0].send(e, SECOND)
            advanceTimeBy(BEFORE_SECOND_RETRY_MILLIS)

            // Assert
            assertEquals(listOf(SECOND.decodeToString()), inbox)
            assertEquals(before, messagesTo(line[0], line[1], toB))
        }

    /** Thirteen advertisers beside [platform] alone move its node up to the medium class. */
    private fun TestScope.medium(
        air: VirtualAir,
        platform: VirtualPlatform,
        offset: Double = -FAKE_DISTANCE,
    ) {
        advanceTimeBy(SETTLE_MILLIS)
        repeat(MEDIUM_UP_HEARD) {
            air.move(advertiseFake(air, capability = null), platform.radio.x + offset, platform.radio.y)
        }
        advanceTimeBy(SWITCH_MILLIS)
    }

    /** [count] platforms in a line along x, [SPACING] apart from the origin. */
    private fun TestScope.line(
        air: VirtualAir,
        count: Int,
    ): List<VirtualPlatform> =
        List(count) { index ->
            air.platform(StandardTestDispatcher(testScheduler)).also { air.move(it.radio, index * SPACING, 0.0) }
        }

    /** The NodeId of this node's linked peer on [platform]. */
    private fun MeshLink.idOf(platform: VirtualPlatform): NodeId =
        peers.value.single { it.nodeId.toByteArray().contentEquals(nodeId(platform)) }.nodeId

    private fun node(platform: VirtualPlatform): MeshLink =
        MeshLink.create(
            meshLinkConfig(LINE_PRESET, SecurityProfile.Unsecured) { meshId = MESH }.build(),
            meshPlatform(platform),
        )

    @OptIn(InternalMeshLinkApi::class)
    private fun meshPlatform(platform: VirtualPlatform): MeshPlatform = MeshPlatform.of(platform)

    /** Every payload [node] receives from now on, as text. */
    private fun TestScope.inbox(node: MeshLink): List<String> {
        val received = mutableListOf<String>()
        backgroundScope.launch { node.messages.collect { received += it.payload.decodeToString() } }
        return received
    }

    /** The routed frames [from] wrote to [to], over GATT and any channel. */
    private fun written(
        from: VirtualPlatform,
        to: VirtualPlatform,
    ): List<ByteArray> =
        from.radio.ends
            .filter { it.remote === to.radio }
            .flatMap { end -> listOf(end.written) + end.channels.map { it.written } }
            .flatMap { assertIs<Decoded.Valid<List<ByteArray>>>(RecordReader().read(it)).value }
            .filter { it[0].toInt() and 0xFF >= MessageFrame.TYPE }

    /** How many `MESSAGE` frames to the routing tag [tag] [from] wrote to [to]. */
    private fun messagesTo(
        from: VirtualPlatform,
        to: VirtualPlatform,
        tag: ByteArray,
    ): Int = written(from, to).mapNotNull(::message).count { it.header.destinationTag.contentEquals(tag) }

    private fun reply(frame: ByteArray): RouteReply? = (RouteReply.decode(frame) as? Decoded.Valid)?.value

    private fun isMessage(frame: ByteArray): Boolean = message(frame) != null

    private fun message(frame: ByteArray): UnicastFrame? =
        (UnicastFrame.decode(frame) as? Decoded.Valid)?.value?.takeIf { it.type == MessageFrame.TYPE }

    private companion object {
        val PAYLOAD = "six hops".encodeToByteArray()
        val SECOND = "again".encodeToByteArray()
        const val LINE = 7
        const val SHORT_LINE = 5
        const val G = 6
        const val D = 3
        const val SPACING = 25.0
        const val OFFSET = 10.0
        const val AWAY_X = 15.0
        const val AWAY_Y = 25.0
        const val FAKE_DISTANCE = 20.0
        const val SETTLE_MILLIS = 60_000L
        const val SWITCH_MILLIS = 120_000L
        const val DELIVERED_MILLIS = 5_000L
        const val AWAY = 1_000.0

        /** Before the outbox's first retry, 2 s after the send. */
        const val BEFORE_RETRY_MILLIS = 1_900L
        const val MEDIUM_UP_HEARD = 13

        /** When a message sent on a 4-hop route found 1.2 s after its send is resent: 1.2 + 1.0 + 4 × 0.2 s. */
        const val RESEND_MILLIS = 3_000L

        /** Short of and past [RESEND_MILLIS]. */
        const val MARGIN_MILLIS = 100L

        /** Past the flush, resend and found flush that sending to a neighbor starts, 3.6 s after the send. */
        const val SETTLED_MILLIS = 3_600L

        /** Enough for a write to a neighbor. */
        const val WRITTEN_MILLIS = 100L

        /** Before the outbox's second retry, 6 s after the first send. */
        const val BEFORE_SECOND_RETRY_MILLIS = 2_200L

        /** The hop limit of a frame on a route. */
        const val HOP_LIMIT_MAX = 255

        /** A message's 120-second lifetime and the 30-second skew allowance. */
        const val DEADLINE_MILLIS = 150_000L

        /** `forward-route-timeout`. */
        const val FORWARD_ROUTE_MILLIS = 60_000L
    }
}
