package ch.trancee.meshlink.routing

import ch.trancee.meshlink.wire.Keepalive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class PresenceTrackerTest {

    // ----------------------------------------------------------------
    // PresenceTracker — flow emission tests
    // ----------------------------------------------------------------

    @Test
    fun `onPeerConnected emits Connected event`() = runTest {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01, 0x02)
        val events = mutableListOf<PeerEvent>()

        val job = launch { tracker.peerEvents.collect { events.add(it) } }
        testScheduler.runCurrent()

        tracker.onPeerConnected(peerId)
        testScheduler.runCurrent()

        assertEquals(1, events.size)
        assertEquals(PeerEvent.Connected(peerId), events[0])
        job.cancel()
    }

    @Test
    fun `onPeerDisconnected emits Disconnected event`() = runTest {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x03, 0x04)
        val events = mutableListOf<PeerEvent>()

        val job = launch { tracker.peerEvents.collect { events.add(it) } }
        testScheduler.runCurrent()

        tracker.onPeerDisconnected(peerId)
        testScheduler.runCurrent()

        assertEquals(1, events.size)
        assertEquals(PeerEvent.Disconnected(peerId), events[0])
        job.cancel()
    }

    @Test
    fun `multiple rapid events do not drop with extraBufferCapacity 64`() = runTest {
        val tracker = PresenceTracker()
        val events = mutableListOf<PeerEvent>()

        val job = launch { tracker.peerEvents.collect { events.add(it) } }
        testScheduler.runCurrent()

        // Burst 20 events without yielding — all should be buffered
        repeat(10) { tracker.onPeerConnected(byteArrayOf(it.toByte())) }
        repeat(10) { tracker.onPeerDisconnected(byteArrayOf(it.toByte())) }
        testScheduler.runCurrent()

        assertEquals(20, events.size)
        job.cancel()
    }

    @Test
    fun `onPeerConnected copies peerId so mutation does not affect emitted event`() = runTest {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01)
        val events = mutableListOf<PeerEvent>()

        val job = launch { tracker.peerEvents.collect { events.add(it) } }
        testScheduler.runCurrent()

        tracker.onPeerConnected(peerId)
        testScheduler.runCurrent()
        peerId[0] = 0xFF.toByte() // mutate original

        assertEquals(PeerEvent.Connected(byteArrayOf(0x01)), events[0])
        job.cancel()
    }

    // ----------------------------------------------------------------
    // PeerEvent.Connected — equals/hashCode branch coverage
    // ----------------------------------------------------------------

    @Test
    fun `PeerEvent Connected equals identity`() {
        val e = PeerEvent.Connected(byteArrayOf(1, 2))
        @Suppress("ReplaceCallWithBinaryOperator")
        assertTrue(e.equals(e))
    }

    @Test
    fun `PeerEvent Connected equals same peerId`() {
        val e1 = PeerEvent.Connected(byteArrayOf(1, 2))
        val e2 = PeerEvent.Connected(byteArrayOf(1, 2))
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    fun `PeerEvent Connected equals different peerId returns false`() {
        val e1 = PeerEvent.Connected(byteArrayOf(1, 2))
        val e2 = PeerEvent.Connected(byteArrayOf(9, 9))
        assertNotEquals(e1, e2)
    }

    @Test
    fun `PeerEvent Connected equals different subclass returns false`() {
        val e1 = PeerEvent.Connected(byteArrayOf(1, 2))
        val e2 = PeerEvent.Disconnected(byteArrayOf(1, 2))
        assertFalse(e1 == e2)
    }

    @Test
    fun `PeerEvent Connected equals null returns false`() {
        val e = PeerEvent.Connected(byteArrayOf(1, 2))
        @Suppress("ReplaceCallWithBinaryOperator")
        assertFalse(e.equals(null))
    }

    // ----------------------------------------------------------------
    // PeerEvent.Disconnected — equals/hashCode branch coverage
    // ----------------------------------------------------------------

    @Test
    fun `PeerEvent Disconnected equals identity`() {
        val e = PeerEvent.Disconnected(byteArrayOf(3, 4))
        @Suppress("ReplaceCallWithBinaryOperator")
        assertTrue(e.equals(e))
    }

    @Test
    fun `PeerEvent Disconnected equals same peerId`() {
        val e1 = PeerEvent.Disconnected(byteArrayOf(3, 4))
        val e2 = PeerEvent.Disconnected(byteArrayOf(3, 4))
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    fun `PeerEvent Disconnected equals different peerId returns false`() {
        val e1 = PeerEvent.Disconnected(byteArrayOf(3, 4))
        val e2 = PeerEvent.Disconnected(byteArrayOf(0, 0))
        assertNotEquals(e1, e2)
    }

    @Test
    fun `PeerEvent Disconnected equals different subclass returns false`() {
        val e1 = PeerEvent.Disconnected(byteArrayOf(3, 4))
        val e2 = PeerEvent.Connected(byteArrayOf(3, 4))
        assertFalse(e1 == e2)
    }

    @Test
    fun `PeerEvent Disconnected equals null returns false`() {
        val e = PeerEvent.Disconnected(byteArrayOf(3, 4))
        @Suppress("ReplaceCallWithBinaryOperator")
        assertFalse(e.equals(null))
    }

    // ----------------------------------------------------------------
    // PeerInfo — equals/hashCode branch coverage (N=4 conditions → 5 value tests + identity + type)
    // ----------------------------------------------------------------

    @Test
    fun `PeerInfo equals identity`() {
        val p = PeerInfo(byteArrayOf(1), 0x01, -70, 0.1)
        @Suppress("ReplaceCallWithBinaryOperator")
        assertTrue(p.equals(p))
    }

    @Test
    fun `PeerInfo equals non PeerInfo type returns false`() {
        val p = PeerInfo(byteArrayOf(1), 0x01, -70, 0.1)
        assertFalse(p.equals("not a PeerInfo"))
    }

    @Test
    fun `PeerInfo equals all fields same returns true`() {
        val p1 = PeerInfo(byteArrayOf(1, 2, 3), 0x02, -65, 0.05)
        val p2 = PeerInfo(byteArrayOf(1, 2, 3), 0x02, -65, 0.05)
        assertEquals(p1, p2)
        assertEquals(p1.hashCode(), p2.hashCode())
    }

    @Test
    fun `PeerInfo equals different peerId returns false`() {
        val p1 = PeerInfo(byteArrayOf(1, 2, 3), 0x02, -65, 0.05)
        val p2 = PeerInfo(byteArrayOf(9, 9, 9), 0x02, -65, 0.05)
        assertNotEquals(p1, p2)
    }

    @Test
    fun `PeerInfo equals different powerMode returns false`() {
        val p1 = PeerInfo(byteArrayOf(1, 2, 3), 0x02, -65, 0.05)
        val p2 = PeerInfo(byteArrayOf(1, 2, 3), 0x03, -65, 0.05)
        assertNotEquals(p1, p2)
    }

    @Test
    fun `PeerInfo equals different rssi returns false`() {
        val p1 = PeerInfo(byteArrayOf(1, 2, 3), 0x02, -65, 0.05)
        val p2 = PeerInfo(byteArrayOf(1, 2, 3), 0x02, -70, 0.05)
        assertNotEquals(p1, p2)
    }

    @Test
    fun `PeerInfo equals different lossRate returns false`() {
        val p1 = PeerInfo(byteArrayOf(1, 2, 3), 0x02, -65, 0.05)
        val p2 = PeerInfo(byteArrayOf(1, 2, 3), 0x02, -65, 0.99)
        assertNotEquals(p1, p2)
    }

    // ----------------------------------------------------------------
    // OutboundFrame — equals/hashCode branch coverage
    // ----------------------------------------------------------------

    private val msg1 = Keepalive(0u, 1000uL, 512u)
    private val msg2 = Keepalive(1u, 2000uL, 256u)

    @Test
    fun `OutboundFrame equals identity`() {
        val f = OutboundFrame(byteArrayOf(1), msg1)
        @Suppress("ReplaceCallWithBinaryOperator")
        assertTrue(f.equals(f))
    }

    @Test
    fun `OutboundFrame equals non OutboundFrame type returns false`() {
        val f = OutboundFrame(byteArrayOf(1), msg1)
        assertFalse(f.equals("not a frame"))
    }

    @Test
    fun `OutboundFrame equals null peerId both null same message returns true`() {
        val f1 = OutboundFrame(null, msg1)
        val f2 = OutboundFrame(null, msg1)
        assertEquals(f1, f2)
        assertEquals(f1.hashCode(), f2.hashCode())
    }

    @Test
    fun `OutboundFrame equals this null peerId other non-null returns false`() {
        val f1 = OutboundFrame(null, msg1)
        val f2 = OutboundFrame(byteArrayOf(1), msg1)
        assertNotEquals(f1, f2)
    }

    @Test
    fun `OutboundFrame equals this non-null peerId other null returns false`() {
        val f1 = OutboundFrame(byteArrayOf(1), msg1)
        val f2 = OutboundFrame(null, msg1)
        assertNotEquals(f1, f2)
    }

    @Test
    fun `OutboundFrame equals same non-null peerId same message returns true`() {
        val f1 = OutboundFrame(byteArrayOf(1, 2), msg1)
        val f2 = OutboundFrame(byteArrayOf(1, 2), msg1)
        assertEquals(f1, f2)
        assertEquals(f1.hashCode(), f2.hashCode())
    }

    @Test
    fun `OutboundFrame equals different peerId returns false`() {
        val f1 = OutboundFrame(byteArrayOf(1, 2), msg1)
        val f2 = OutboundFrame(byteArrayOf(9, 9), msg1)
        assertNotEquals(f1, f2)
    }

    @Test
    fun `OutboundFrame equals same peerId different message returns false`() {
        val f1 = OutboundFrame(byteArrayOf(1, 2), msg1)
        val f2 = OutboundFrame(byteArrayOf(1, 2), msg2)
        assertNotEquals(f1, f2)
    }

    @Test
    fun `OutboundFrame hashCode null peerId gives zero peerHash`() {
        val f1 = OutboundFrame(null, msg1)
        val f2 = OutboundFrame(null, msg1)
        assertEquals(f1.hashCode(), f2.hashCode())
    }
}
