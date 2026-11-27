package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.ReceivedMessage
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The relay rate limits (spec "Relay rate limits") on running nodes: A, B and C in a line, only B
 * reaching both. The limiter itself is tested alone; this shows the node wires it to its relay path
 * and its diagnostics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayRateLimitTest {
    @Test
    fun aRelayDeliversEveryBroadcastOfAFloodingNeighborButRelaysOnlyItsBudgetAndReportsTheRest() =
        runTest(timeout = 5.minutes) {
            // Arrange
            val (a, b, c) = line()
            val atB = inbox(b)
            val atC = inbox(c)
            val limited = diagnostics(b, "ML5005")

            // Act
            repeat(SENT) {
                assertIs<SendResult.Accepted>(a.broadcast(byteArrayOf(it.toByte())))
                advanceTimeBy(GAP_MILLIS)
            }
            advanceTimeBy(SETTLE_LINE_MILLIS)

            // Assert: B relays the burst, then one broadcast per 250 ms, give or take the frames in flight.
            assertEquals(SENT, atB.size)
            assertTrue(atC.size in RELAYED - SLACK..RELAYED + SLACK, "C got ${atC.size}")
            val first = assertIs<DiagnosticEvent.RelayRateLimited>(limited.first())
            assertEquals(DiagnosticEvent.RelayRateLimited.Frame.Broadcast, first.frame)
        }

    // Started nodes A, B and C, linked side by side, then SPACING apart in a line so only B reaches both.
    private suspend fun TestScope.line(): List<MeshLink> {
        val air = air()
        val platforms = List(3) { air.platform(StandardTestDispatcher(testScheduler)) }
        val nodes = platforms.map { node(it) }
        nodes.forEach { it.start() }
        advanceTimeBy(SETTLE_LINE_MILLIS)
        platforms.forEachIndexed { index, platform -> air.move(platform.radio, index * SPACING, 0.0) }
        advanceTimeBy(SETTLE_LINE_MILLIS)
        assertEquals(emptyList(), platforms[0].radio.ends.filter { it.open && it.remote == platforms[2].radio })
        return nodes
    }

    // Every message [node] receives from now on.
    private fun TestScope.inbox(node: MeshLink): List<ReceivedMessage> {
        val received = mutableListOf<ReceivedMessage>()
        backgroundScope.launch { node.messages.collect { received += it } }
        return received
    }

    private companion object {
        // 20 broadcasts a second for 5 s.
        const val SENT = 100
        const val GAP_MILLIS = 50L

        // `rate-broadcast-burst`, plus 5 s at one per `rate-broadcast-gap`.
        const val RELAYED = 20 + 5_000 / 250
        const val SLACK = 2
        const val SPACING = 25.0
        const val SETTLE_LINE_MILLIS = 60_000L
    }
}
