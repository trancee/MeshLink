package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// What Router reports to diagnostics, on lanes to three linked peers.
class RouterDiagnosticsTest {
    private val a = nodeId(1)
    private val b = nodeId(2)
    private val c = nodeId(3)
    private val lanes = Lanes().apply { listOf(a, b, c).forEach { open(it) } }
    private val diagnosed = mutableListOf<DiagnosticEvent>()
    private val floods = Floods(lanes, broadcastCodec(), lowTree(lanes), keptTombstones()) {}

    private fun TestScope.router(): Router {
        val endpoint = FakeEndpoint(OWN).apply { kinds = setOf(Container.UNSECURED) }
        val clock = SchedulerClock(testScheduler)
        return Router(clock, lanes, Routes(), { ByteArray(it) }, endpoint, Handoff(floods, diagnose = diagnosed::add))
    }

    private fun TestScope.nowSeconds() = SchedulerClock(testScheduler).wallClockMillis() / 1_000

    private suspend fun relayedTo(peer: NodeId) = lanes.drain(peer).size

    @Test
    fun anUntimelyFrameReportsHowFarItsExpiryLiesFromTheLocalClock() =
        runTest {
            // Arrange
            val router = router()
            val now = nowSeconds()
            val flood = FloodHeader(5, messageId(3), (now - 100).toUInt())

            // Act
            router.received(a, unicast(UNKNOWN_TYPE, 5, messageId(1), now - 31, tag(9), tag(8)))
            router.received(a, unicast(UNKNOWN_TYPE, 5, messageId(2), now + 3_631, tag(9), tag(8)))
            router.received(a, BroadcastFrame(flood, Container.Unsecured(ByteArray(32), byteArrayOf(1))).encode())
            router.received(a, unicast(UNKNOWN_TYPE, 5, messageId(4), now - 30, tag(9), tag(8)))
            router.received(a, byteArrayOf(FLOOD_TYPE))

            // Assert
            val skews = diagnosed.filterIsInstance<DiagnosticEvent.ExpiryFarFromLocalTime>().map { it.skewSeconds }
            assertEquals(listOf(-31L, 31L, -100L), skews)
            assertEquals(1, relayedTo(b), "only the frame at the bound is relayed")
        }

    @Test
    fun aUnicastTypeThisVersionDoesNotKnowIsRelayedByItsHeaderAndReported() =
        runTest {
            // Arrange
            val router = router()
            val message = MessageFrame.TYPE
            val body = messageBody(Container.Unsecured(ByteArray(32), byteArrayOf()), ByteArray(16))

            // Act
            router.received(a, unicast(0x45, 5, messageId(1), nowSeconds(), tag(9), tag(8)))
            router.received(a, unicast(0x46, 5, messageId(2), nowSeconds(), OWN, tag(8)))
            router.received(a, unicast(0x47, 1, messageId(3), nowSeconds(), tag(9), tag(8)))
            router.received(a, unicast(message, 5, messageId(4), nowSeconds() + 60, tag(9), tag(8), body))

            // Assert
            assertEquals(2, relayedTo(b))
            assertEquals(listOf("ML5003 UnknownRoutedFrameRelayed(type=69) x1"), diagnosed.map { it.toString() })
        }

    private companion object {
        val OWN = tag(0x55)
        val FLOOD_TYPE: Byte = 0x80.toByte()
        const val UNKNOWN_TYPE = 0x4e
    }
}
