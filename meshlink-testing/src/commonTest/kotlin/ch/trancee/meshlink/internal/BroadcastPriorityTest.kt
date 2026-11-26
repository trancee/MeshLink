package ch.trancee.meshlink.internal

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkConfigBuilder
import ch.trancee.meshlink.SendResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

/** The `broadcastPriority` toggle on a link whose airtime makes frames wait in its lane. */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastPriorityTest {
    @Test
    fun withBroadcastPriorityABroadcastOvertakesWaitingMessages() =
        runTest(timeout = 5.minutes) {
            // Act
            val order = arrivals { broadcastPriority = true }

            // Assert
            assertEquals(listOf(BROADCAST) + List(MESSAGES) { MESSAGE }, order)
        }

    @Test
    fun withoutBroadcastPriorityABroadcastWaitsBehindMessages() =
        runTest(timeout = 5.minutes) {
            // Act
            val order = arrivals { broadcastPriority = false }

            // Assert
            assertEquals(List(MESSAGES) { MESSAGE } + BROADCAST, order)
        }

    /**
     * What a sender configured by [configure] delivers to its linked peer, in arrival order, when it
     * sends [MESSAGES] messages and then a broadcast at once.
     */
    private suspend fun TestScope.arrivals(configure: MeshLinkConfigBuilder.() -> Unit): List<String> {
        val air = air(airtimeBytesPerSecond = AIRTIME_BYTES_PER_SECOND)
        val sender = node(air.platform(StandardTestDispatcher(testScheduler)), configure)
        val receiver = node(air.platform(StandardTestDispatcher(testScheduler)))
        listOf(sender, receiver).forEach { it.start() }
        advanceTimeBy(SETTLE_MILLIS)
        val order = inbox(receiver)
        val peer =
            sender.peers.value
                .single()
                .nodeId
        repeat(MESSAGES) { assertIs<SendResult.Accepted>(sender.send(peer, ByteArray(MESSAGE_BYTES))) }
        assertIs<SendResult.Accepted>(sender.broadcast(ByteArray(BROADCAST_BYTES)))
        advanceTimeBy(DRAIN_MILLIS)
        return order
    }

    /** What [node] receives from now on: [MESSAGE] or [BROADCAST] each. */
    private fun TestScope.inbox(node: MeshLink): List<String> {
        val received = mutableListOf<String>()
        backgroundScope.launch { node.messages.collect { received += if (it.broadcast) BROADCAST else MESSAGE } }
        return received
    }

    private companion object {
        const val AIRTIME_BYTES_PER_SECOND = 1_000
        const val MESSAGES = 6
        const val MESSAGE_BYTES = 1_000
        const val BROADCAST_BYTES = 100
        const val DRAIN_MILLIS = 60_000L
        const val MESSAGE = "message"
        const val BROADCAST = "broadcast"
    }
}
