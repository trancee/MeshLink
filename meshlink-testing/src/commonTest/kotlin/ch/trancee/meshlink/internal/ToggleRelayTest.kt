package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkConfigBuilder
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReadReceiptRequest
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SendOptions
import ch.trancee.meshlink.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

/**
 * The toggle rule (ADR 0011 decision 8): a node with a feature off still relays other nodes' frames
 * of that feature. In each test A and C are out of range, and B between them has one toggle off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToggleRelayTest {
    @Test
    fun aRelayWithRoutedUnicastOffStillRelaysAMessage() =
        runTest(timeout = 5.minutes) {
            assertRelaysAMessage { routedUnicast = false }
        }

    @Test
    fun aRelayWithHoldingOffStillRelaysAMessage() =
        runTest(timeout = 5.minutes) {
            assertRelaysAMessage { holding = false }
        }

    @Test
    fun aRelayWithBroadcastOriginationOffStillRelaysABroadcast() =
        runTest(timeout = 5.minutes) {
            // Arrange
            val line = line { broadcastOrigination = false }
            val inbox = inbox(line.c)

            // Act
            assertIs<SendResult.Accepted>(line.a.broadcast(PAYLOAD))
            advanceTimeBy(LINE_SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(PAYLOAD.toList()), inbox.map { it.payload.toList() })
        }

    @Test
    fun aRelayWithReadReceiptsSendOffStillRelaysAReadReceipt() =
        runTest(timeout = 5.minutes) {
            // Arrange
            val line = line { readReceipts.send = false }
            val inbox = inbox(line.c)
            val sent = line.a.send(line.cId, PAYLOAD, SendOptions(readReceipt = ReadReceiptRequest.Request))
            val status = statuses(sent)
            advanceTimeBy(LINE_SETTLE_MILLIS)

            // Act
            inbox.single().markRead()
            advanceTimeBy(LINE_SETTLE_MILLIS)

            // Assert
            assertEquals(DeliveryStatus.Read, status.last())
        }

    // Asserts that B, set by [middle], relays A's message to C.
    private suspend fun TestScope.assertRelaysAMessage(middle: MeshLinkConfigBuilder.() -> Unit) {
        // Arrange
        val line = line(middle)
        val inbox = inbox(line.c)

        // Act
        val status = statuses(line.a.send(line.cId, PAYLOAD))
        advanceTimeBy(LINE_SETTLE_MILLIS)

        // Assert
        assertEquals(listOf(PAYLOAD.toList()), inbox.map { it.payload.toList() })
        assertEquals(DeliveryStatus.Delivered, status.last())
    }

    /**
     * Started nodes A, B and C, linked while side by side, then [SPACING] apart in a line so only B
     * reaches both; B with [middle].
     */
    private suspend fun TestScope.line(middle: MeshLinkConfigBuilder.() -> Unit): Line {
        val air = air()
        val platforms = List(3) { air.platform(StandardTestDispatcher(testScheduler)) }
        val nodes = platforms.mapIndexed { index, platform -> node(platform, if (index == 1) middle else ({})) }
        nodes.forEach { it.start() }
        advanceTimeBy(LINE_SETTLE_MILLIS)
        val cId = nodes[0].peers.value.single { it.nodeId.toByteArray().contentEquals(nodeId(platforms[2])) }.nodeId
        platforms.forEachIndexed { index, platform -> air.move(platform.radio, index * SPACING, 0.0) }
        advanceTimeBy(LINE_SETTLE_MILLIS)
        assertEquals(emptyList(), platforms[0].radio.ends.filter { it.open && it.remote == platforms[2].radio })
        return Line(nodes[0], nodes[2], cId)
    }

    /** Nodes [a] and [c] at the ends of a line, and C's NodeId [cId] as A knows it. */
    private class Line(
        val a: MeshLink,
        val c: MeshLink,
        val cId: NodeId,
    )

    /** Every message [node] receives from now on. */
    private fun TestScope.inbox(node: MeshLink): List<ReceivedMessage> {
        val received = mutableListOf<ReceivedMessage>()
        backgroundScope.launch { node.messages.collect { received += it } }
        return received
    }

    /** Every status of the message [result] accepted from now on. */
    private fun TestScope.statuses(result: SendResult): List<DeliveryStatus> {
        val seen = mutableListOf<DeliveryStatus>()
        backgroundScope.launch { assertIs<SendResult.Accepted>(result).message.status.toList(seen) }
        return seen
    }

    private companion object {
        val PAYLOAD = "relayed".encodeToByteArray()
        const val SPACING = 25.0
        const val LINE_SETTLE_MILLIS = 60_000L
    }
}
