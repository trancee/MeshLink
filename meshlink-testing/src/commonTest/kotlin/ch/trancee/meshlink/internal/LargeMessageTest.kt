package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.SendResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Large messages at their origin (spec "Size classes"): sent only on a direct link or on a route
 * that route discovery found, never flooded, in every density class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LargeMessageTest {
    @Test
    fun aLargeMessageToALinkedPeerGoesOnlyOnItsLink() =
        runTest(timeout = 5.minutes) {
            // Arrange: A, B and C side by side and linked.
            val (air, platforms, nodes) = trio()
            val c = nodes[0].peerOn(platforms[2])
            val inbox = inbox(nodes[2])

            // Act
            val status = statuses(nodes[0].send(c, LARGEST))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(LARGEST.toList()), inbox.map { it.toList() })
            assertEquals(listOf(DeliveryStatus.Queued, DeliveryStatus.Sent, DeliveryStatus.Delivered), status)
            assertEquals(emptyList(), messages(platforms[0], platforms[1]))
            assertEquals(listOf(1), messages(platforms[0], platforms[2]).map { it[1].toInt() and 0xFF })
        }

    @Test
    fun inTheLowClassALargeMessageCrossesALineOnARouteItsOriginFound() =
        runTest(timeout = 5.minutes) {
            // Arrange: A, B and C linked side by side, then in a line where only neighbors are in range.
            val (air, platforms, nodes) = trio()
            val c = nodes[0].peerOn(platforms[2])
            platforms.forEachIndexed { index, platform -> air.move(platform.radio, index * SPACING, 0.0) }
            advanceTimeBy(SETTLE_MILLIS)
            val inbox = inbox(nodes[2])

            // Act
            val statuses = listOf(SMALLEST_LARGE, LARGEST).map { statuses(nodes[0].send(c, it)) }
            advanceTimeBy(LINE_MILLIS)

            // Assert: no resend or retry copies the largest message while a copy is being written, so
            // only the retry due at 6 s, which finds A's link free while B still relays the first copy,
            // sends it again.
            assertEquals(listOf(SMALLEST_LARGE.size, LARGEST.size), inbox.map { it.size }.sorted())
            val delivered = listOf(DeliveryStatus.Queued, DeliveryStatus.Sent, DeliveryStatus.Delivered)
            assertEquals(listOf(delivered, delivered), statuses)
            val copies = listOf(1, 2)
            assertEquals(copies, frameCounts(messages(platforms[0], platforms[1])))
            assertEquals(copies, frameCounts(messages(platforms[1], platforms[2])))
        }

    @Test
    fun aLargeMessageWithNoRouteFoundByItsResendTimeIsHeldAndNeverFlooded() =
        runTest(timeout = 5.minutes) {
            // Arrange: C, away, answers no route request; A is still linked to B.
            val (air, platforms, nodes) = trio()
            val c = nodes[0].peerOn(platforms[2])
            air.move(platforms[2].radio, AWAY, 0.0)
            advanceTimeBy(SETTLE_MILLIS)
            val status = statuses(nodes[0].send(c, SMALLEST_LARGE))
            advanceTimeBy(SEARCH_MILLIS)
            val searching = status.toList()

            // Act: the check due at SEARCH_MILLIS runs once time passes it.
            advanceTimeBy(1)

            // Assert
            assertEquals(listOf(DeliveryStatus.Queued), searching)
            assertEquals(searching + DeliveryStatus.Held, status)
            assertEquals(emptyList(), messages(platforms[0], platforms[1]))
        }

    @Test
    fun aRouteBrokenAheadOfItsOriginDropsTheLargeFrameAndTheOriginsRetryDeliversItOnANewRoute() =
        runTest(timeout = 5.minutes) {
            // Arrange: A reaches C only through relay X; relay Y is linked to A but out of C's range.
            val (air, platforms, nodes) = trio(count = 4)
            val a = platforms[0]
            val x = platforms[1]
            val c = platforms[2]
            val y = platforms[3]
            val to = nodes[0].peerOn(c)
            air.move(a.radio, 0.0, 0.0)
            air.move(x.radio, RELAY_X, RELAY_OFFSET)
            air.move(y.radio, RELAY_X, -RELAY_OFFSET)
            air.move(c.radio, END_X, END_OFFSET)
            advanceTimeBy(SETTLE_MILLIS)
            val inbox = inbox(nodes[2])
            val found = statuses(nodes[0].send(to, SMALLEST_LARGE))
            advanceTimeBy(SETTLE_MILLIS)
            val viaX = messages(x, c).size

            // Act: C moves out of X's range into Y's while A's route through X is still fresh.
            air.move(c.radio, END_X, -END_OFFSET)
            advanceTimeBy(SETTLE_MILLIS)
            val status = statuses(nodes[0].send(to, LARGEST))
            advanceTimeBy(LINE_MILLIS)
            val viaY = messages(y, c).size

            // Assert: X dropped its copy, and holds none for C once C is back in its range.
            air.move(c.radio, END_X, END_OFFSET)
            advanceTimeBy(LINE_MILLIS)
            assertEquals(listOf(SMALLEST_LARGE.size, LARGEST.size), inbox.map { it.size })
            val delivered = listOf(DeliveryStatus.Queued, DeliveryStatus.Sent, DeliveryStatus.Delivered)
            assertEquals(listOf(delivered, delivered), listOf(found, status))
            assertEquals(listOf(1, 1), listOf(viaX, viaY))
            assertTrue(messages(a, x).any { it.size > LARGEST.size }, "A sent the largest message to X")
            assertEquals(viaX, messages(x, c).size)
            assertTrue(messages(x, a).isEmpty() && messages(x, y).isEmpty())
        }

    @Test
    fun aHubWhoseReassemblyBudgetIsFullAnswersBusyAndEveryLargeMessageStillArrives() =
        runTest(timeout = 5.minutes) {
            // Arrange: five senders all linked to the hub, on links whose streams take seconds.
            val (_, platforms, nodes) = trio(count = HUB_SENDERS + 1, airtimeBytesPerSecond = AIRTIME_BYTES_PER_SECOND)
            val hub = platforms[0]
            val inbox = inbox(nodes[0])

            // Act: their largest messages reach the hub at once, more than its budget holds.
            val statuses = nodes.drop(1).map { statuses(it.send(it.peerOn(hub), LARGEST)) }
            advanceTimeBy(HUB_MILLIS)

            // Assert
            val busies = hub.radio.ends.flatMap { end -> frames(end).filterIsInstance<LinkFrame.Busy>() }
            assertTrue(busies.isNotEmpty() && busies.all { it.code == BusyReason.LARGE_BUDGET.code }, "$busies")
            assertEquals(List(HUB_SENDERS) { LARGEST.size }, inbox.map { it.size })
            val delivered = listOf(DeliveryStatus.Queued, DeliveryStatus.Sent, DeliveryStatus.Delivered)
            assertEquals(List(HUB_SENDERS) { delivered }, statuses)
        }

    /** [count] started nodes, three by default, side by side and linked, on [airtimeBytesPerSecond] air. */
    private suspend fun TestScope.trio(
        count: Int = 3,
        airtimeBytesPerSecond: Int? = null,
    ): Triple<VirtualAir, List<VirtualPlatform>, List<MeshLink>> {
        val air = air(airtimeBytesPerSecond = airtimeBytesPerSecond)
        val platforms = List(count) { air.platform(StandardTestDispatcher(testScheduler)) }
        val nodes = platforms.map { node(it) }.onEach { it.start() }
        advanceTimeBy(SETTLE_MILLIS)
        return Triple(air, platforms, nodes)
    }

    /** The NodeId of this node's linked peer on [platform]. */
    private fun MeshLink.peerOn(platform: VirtualPlatform): NodeId =
        peers.value.single { it.nodeId.toByteArray().contentEquals(nodeId(platform)) }.nodeId

    /** Every payload [node] receives from now on. */
    private fun TestScope.inbox(node: MeshLink): List<ByteArray> {
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { node.messages.collect { received += it.payload } }
        return received
    }

    /** Every status of the message [result] accepted from now on. */
    private fun TestScope.statuses(result: SendResult): List<DeliveryStatus> {
        val seen = mutableListOf<DeliveryStatus>()
        backgroundScope.launch { assertIs<SendResult.Accepted>(result).message.status.toList(seen) }
        return seen
    }

    /** The `MESSAGE` frames [from] wrote on its links to [to], reassembled. */
    private fun messages(
        from: VirtualPlatform,
        to: VirtualPlatform,
    ): List<ByteArray> =
        from.radio.ends
            .filter { it.remote == to.radio }
            .flatMap { written(it).first }
            .filter { it[0] == MessageFrame.TYPE.toByte() }

    /** How many frames of each size, smallest first, are among [frames]. */
    private fun frameCounts(frames: List<ByteArray>): List<Int> =
        frames
            .groupingBy { it.size }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .map { it.value }

    private companion object {
        val SMALLEST_LARGE = ByteArray(4_097) { it.toByte() }
        val LARGEST = ByteArray(131_072) { it.toByte() }
        const val SPACING = 25.0
        const val AWAY = 1_000.0

        /** Senders enough that their largest messages pass `large-reassembly-budget` together. */
        const val HUB_SENDERS = 5

        /** A link rate at which each sender's largest message takes about 2.6 s to cross. */
        const val AIRTIME_BYTES_PER_SECOND = 50_000

        /** Long enough for each refused sender's retry to cross its link again. */
        const val HUB_MILLIS = 30_000L

        // A diamond: A at the origin, relays X and Y either side of A's line to C, C either side too.
        const val RELAY_X = 20.0
        const val RELAY_OFFSET = 10.0
        const val END_X = 40.0
        const val END_OFFSET = 15.0

        /** `resend-base` + `resend-per-hop` × `u6-flood-radius` (D-55). */
        const val SEARCH_MILLIS = 1_600L

        /** Long enough for every copy of the largest message to cross a 2-hop line. */
        const val LINE_MILLIS = 15_000L
    }
}
