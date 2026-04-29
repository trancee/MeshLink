package ch.trancee.meshlink.routing

import ch.trancee.meshlink.wire.Keepalive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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

        // Must connect first so disconnect is a valid transition (not idempotent no-op on unknown)
        tracker.onPeerConnected(peerId)
        testScheduler.runCurrent()

        tracker.onPeerDisconnected(peerId)
        testScheduler.runCurrent()

        assertEquals(2, events.size)
        assertEquals(PeerEvent.Disconnected(peerId), events[1])
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
        @Suppress("ReplaceCallWithBinaryOperator") assertTrue(e.equals(e))
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
        @Suppress("ReplaceCallWithBinaryOperator") assertFalse(e.equals(null))
    }

    // ----------------------------------------------------------------
    // PeerEvent.Disconnected — equals/hashCode branch coverage
    // ----------------------------------------------------------------

    @Test
    fun `PeerEvent Disconnected equals identity`() {
        val e = PeerEvent.Disconnected(byteArrayOf(3, 4))
        @Suppress("ReplaceCallWithBinaryOperator") assertTrue(e.equals(e))
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
        @Suppress("ReplaceCallWithBinaryOperator") assertFalse(e.equals(null))
    }

    // ----------------------------------------------------------------
    // PeerEvent.Gone — equals/hashCode branch coverage
    // ----------------------------------------------------------------

    @Test
    fun `PeerEvent Gone equals identity`() {
        val e = PeerEvent.Gone(byteArrayOf(5, 6))
        @Suppress("ReplaceCallWithBinaryOperator") assertTrue(e.equals(e))
    }

    @Test
    fun `PeerEvent Gone equals same peerId`() {
        val e1 = PeerEvent.Gone(byteArrayOf(5, 6))
        val e2 = PeerEvent.Gone(byteArrayOf(5, 6))
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    fun `PeerEvent Gone equals different peerId returns false`() {
        val e1 = PeerEvent.Gone(byteArrayOf(5, 6))
        val e2 = PeerEvent.Gone(byteArrayOf(0, 0))
        assertNotEquals(e1, e2)
    }

    @Test
    fun `PeerEvent Gone equals different subclass returns false`() {
        val e1 = PeerEvent.Gone(byteArrayOf(5, 6))
        val e2 = PeerEvent.Connected(byteArrayOf(5, 6))
        assertFalse(e1 == e2)
    }

    @Test
    fun `PeerEvent Gone equals null returns false`() {
        val e = PeerEvent.Gone(byteArrayOf(5, 6))
        @Suppress("ReplaceCallWithBinaryOperator") assertFalse(e.equals(null))
    }

    // ----------------------------------------------------------------
    // PeerInfo — equals/hashCode branch coverage (N=4 conditions → 5 value tests + identity + type)
    // ----------------------------------------------------------------

    @Test
    fun `PeerInfo equals identity`() {
        val p = PeerInfo(byteArrayOf(1), 0x01, -70, 0.1)
        @Suppress("ReplaceCallWithBinaryOperator") assertTrue(p.equals(p))
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
        @Suppress("ReplaceCallWithBinaryOperator") assertTrue(f.equals(f))
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

    // ----------------------------------------------------------------
    // PresenceTracker.connectedPeers() + updatePresenceTimeout()
    // ----------------------------------------------------------------

    @Test
    fun `connectedPeers is empty when no peers have connected`() {
        val tracker = PresenceTracker()
        assertEquals(0, tracker.connectedPeers().size)
    }

    @Test
    fun `connectedPeers returns peer after onPeerConnected`() {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01, 0x02)
        tracker.onPeerConnected(peerId)
        val peers = tracker.connectedPeers()
        assertEquals(1, peers.size)
        assertTrue(peers.any { it.contentEquals(peerId) })
    }

    @Test
    fun `connectedPeers removes peer after onPeerDisconnected`() {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x03, 0x04)
        tracker.onPeerConnected(peerId)
        assertEquals(1, tracker.connectedPeers().size)
        tracker.onPeerDisconnected(peerId)
        // Disconnected peers are still tracked but no longer in connectedPeers().
        assertEquals(0, tracker.connectedPeers().size)
    }

    @Test
    fun `connectedPeers tracks multiple peers independently`() {
        val tracker = PresenceTracker()
        val peerA = byteArrayOf(0x0A)
        val peerB = byteArrayOf(0x0B)
        tracker.onPeerConnected(peerA)
        tracker.onPeerConnected(peerB)
        assertEquals(2, tracker.connectedPeers().size)
        tracker.onPeerDisconnected(peerA)
        val remaining = tracker.connectedPeers()
        assertEquals(1, remaining.size)
        assertTrue(remaining.any { it.contentEquals(peerB) })
    }

    @Test
    fun `updatePresenceTimeout stores new timeout value`() {
        val tracker = PresenceTracker()
        assertEquals(30_000L, tracker.presenceTimeoutMillis)
        tracker.updatePresenceTimeout(5_000L)
        assertEquals(5_000L, tracker.presenceTimeoutMillis)
    }

    @Test
    fun `updatePresenceTimeout can be called multiple times`() {
        val tracker = PresenceTracker()
        tracker.updatePresenceTimeout(1_000L)
        tracker.updatePresenceTimeout(60_000L)
        assertEquals(60_000L, tracker.presenceTimeoutMillis)
    }

    // ----------------------------------------------------------------
    // Three-state FSM: Connected → Disconnected → Gone
    // ----------------------------------------------------------------

    @Test
    fun `onPeerConnected sets peer to CONNECTED state`() {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01)
        tracker.onPeerConnected(peerId)
        assertEquals(InternalPeerState.CONNECTED, tracker.peerState(peerId))
    }

    @Test
    fun `onPeerDisconnected transitions Connected to DISCONNECTED`() {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01)
        tracker.onPeerConnected(peerId)
        tracker.onPeerDisconnected(peerId)
        assertEquals(InternalPeerState.DISCONNECTED, tracker.peerState(peerId))
    }

    @Test
    fun `peerState returns null for unknown peer`() {
        val tracker = PresenceTracker()
        assertNull(tracker.peerState(byteArrayOf(0x09)))
    }

    @Test
    fun `idempotent disconnect does not emit second Disconnected event`() = runTest {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01)
        val events = mutableListOf<PeerEvent>()

        val job = launch { tracker.peerEvents.collect { events.add(it) } }
        testScheduler.runCurrent()

        tracker.onPeerConnected(peerId)
        tracker.onPeerDisconnected(peerId)
        tracker.onPeerDisconnected(peerId) // second disconnect — should be no-op
        testScheduler.runCurrent()

        // Should have Connected + exactly one Disconnected
        assertEquals(2, events.size)
        assertTrue(events[0] is PeerEvent.Connected)
        assertTrue(events[1] is PeerEvent.Disconnected)
        job.cancel()
    }

    @Test
    fun `rapid disconnect then reconnect resets to CONNECTED`() = runTest {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01)
        val events = mutableListOf<PeerEvent>()

        val job = launch { tracker.peerEvents.collect { events.add(it) } }
        testScheduler.runCurrent()

        tracker.onPeerConnected(peerId)
        tracker.onPeerDisconnected(peerId)
        tracker.onPeerConnected(peerId) // reconnect
        testScheduler.runCurrent()

        assertEquals(InternalPeerState.CONNECTED, tracker.peerState(peerId))
        assertEquals(3, events.size)
        assertTrue(events[2] is PeerEvent.Connected)
        job.cancel()
    }

    @Test
    fun `sweep increments sweepCount for Disconnected peers below threshold`() {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01)
        tracker.onPeerConnected(peerId)
        tracker.onPeerDisconnected(peerId)

        // First sweep: sweepCount 0 → 1
        val gone1 = tracker.sweep()
        assertTrue(gone1.isEmpty())
        assertEquals(InternalPeerState.DISCONNECTED, tracker.peerState(peerId))
    }

    @Test
    fun `sweep evicts Disconnected peer after sweepCount reaches 2`() = runTest {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01)
        val events = mutableListOf<PeerEvent>()

        val job = launch { tracker.peerEvents.collect { events.add(it) } }
        testScheduler.runCurrent()

        tracker.onPeerConnected(peerId)
        tracker.onPeerDisconnected(peerId)
        testScheduler.runCurrent()

        tracker.sweep() // sweepCount 0 → 1
        tracker.sweep() // sweepCount 1 → 2
        val gone = tracker.sweep() // sweepCount 2 ≥ 2 → evict
        testScheduler.runCurrent()

        assertEquals(1, gone.size)
        assertTrue(gone[0].contentEquals(peerId))
        assertNull(tracker.peerState(peerId)) // peer removed
        // Should have emitted Gone event
        assertTrue(events.any { it is PeerEvent.Gone && it.peerId.contentEquals(peerId) })
        job.cancel()
    }

    @Test
    fun `sweep does not touch Connected peers`() {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01)
        tracker.onPeerConnected(peerId)

        val gone = tracker.sweep()
        assertTrue(gone.isEmpty())
        assertEquals(InternalPeerState.CONNECTED, tracker.peerState(peerId))
    }

    @Test
    fun `sweep returns empty list when no peers are tracked`() {
        val tracker = PresenceTracker()
        assertTrue(tracker.sweep().isEmpty())
    }

    @Test
    fun `connect after Gone treats as fresh peer`() = runTest {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01)
        val events = mutableListOf<PeerEvent>()

        val job = launch { tracker.peerEvents.collect { events.add(it) } }
        testScheduler.runCurrent()

        // Full lifecycle: connect → disconnect → sweep to Gone
        tracker.onPeerConnected(peerId)
        tracker.onPeerDisconnected(peerId)
        tracker.sweep() // sweepCount 0 → 1
        tracker.sweep() // sweepCount 1 → 2
        tracker.sweep() // evict → Gone
        testScheduler.runCurrent()

        assertNull(tracker.peerState(peerId))

        // Reconnect after Gone — should be treated as fresh
        tracker.onPeerConnected(peerId)
        testScheduler.runCurrent()

        assertEquals(InternalPeerState.CONNECTED, tracker.peerState(peerId))
        val lastEvent = events.last()
        assertTrue(lastEvent is PeerEvent.Connected && lastEvent.peerId.contentEquals(peerId))
        job.cancel()
    }

    @Test
    fun `onPeerDisconnected on unknown peer adds as DISCONNECTED and emits event`() = runTest {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x09)
        val events = mutableListOf<PeerEvent>()

        val job = launch { tracker.peerEvents.collect { events.add(it) } }
        testScheduler.runCurrent()

        tracker.onPeerDisconnected(peerId) // peer never connected
        testScheduler.runCurrent()

        assertEquals(InternalPeerState.DISCONNECTED, tracker.peerState(peerId))
        assertEquals(1, events.size)
        assertTrue(events[0] is PeerEvent.Disconnected)
        job.cancel()
    }

    @Test
    fun `connectedPeers excludes Disconnected peers`() {
        val tracker = PresenceTracker()
        val peerA = byteArrayOf(0x0A)
        val peerB = byteArrayOf(0x0B)
        tracker.onPeerConnected(peerA)
        tracker.onPeerConnected(peerB)
        tracker.onPeerDisconnected(peerA)

        val connected = tracker.connectedPeers()
        assertEquals(1, connected.size)
        assertTrue(connected.any { it.contentEquals(peerB) })
        assertFalse(connected.any { it.contentEquals(peerA) })
    }

    // ----------------------------------------------------------------
    // onPeerActivity + clock injection
    // ----------------------------------------------------------------

    @Test
    fun `onPeerActivity updates lastSeenMillis`() {
        var now = 1000L
        val tracker = PresenceTracker(clock = { now })
        val peerId = byteArrayOf(0x01)

        tracker.onPeerConnected(peerId)
        val statesBefore = tracker.allPeerStates()
        assertEquals(1000L, statesBefore[peerId.toList()]!!.lastSeenMillis)

        now = 5000L
        tracker.onPeerActivity(peerId)
        val statesAfter = tracker.allPeerStates()
        assertEquals(5000L, statesAfter[peerId.toList()]!!.lastSeenMillis)
    }

    @Test
    fun `onPeerActivity is no-op for unknown peer`() {
        val tracker = PresenceTracker()
        // Should not throw
        tracker.onPeerActivity(byteArrayOf(0x09))
        assertNull(tracker.peerState(byteArrayOf(0x09)))
    }

    @Test
    fun `clock is used for lastSeenMillis on connect`() {
        var now = 42L
        val tracker = PresenceTracker(clock = { now })
        val peerId = byteArrayOf(0x01)

        tracker.onPeerConnected(peerId)
        assertEquals(42L, tracker.allPeerStates()[peerId.toList()]!!.lastSeenMillis)
    }

    // ----------------------------------------------------------------
    // allPeerStates()
    // ----------------------------------------------------------------

    @Test
    fun `allPeerStates returns snapshot of all tracked peers`() {
        val tracker = PresenceTracker()
        val peerA = byteArrayOf(0x0A)
        val peerB = byteArrayOf(0x0B)
        tracker.onPeerConnected(peerA)
        tracker.onPeerConnected(peerB)
        tracker.onPeerDisconnected(peerB)

        val states = tracker.allPeerStates()
        assertEquals(2, states.size)
        assertEquals(InternalPeerState.CONNECTED, states[peerA.toList()]!!.state)
        assertEquals(InternalPeerState.DISCONNECTED, states[peerB.toList()]!!.state)
    }

    @Test
    fun `allPeerStates is a copy and does not leak internal state`() {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01)
        tracker.onPeerConnected(peerId)

        val snapshot = tracker.allPeerStates() as HashMap
        snapshot.clear() // clear the copy

        // Internal state unaffected
        assertEquals(1, tracker.allPeerStates().size)
    }

    // ----------------------------------------------------------------
    // sweepCount tracking across sweeps
    // ----------------------------------------------------------------

    @Test
    fun `sweepCount resets on reconnect`() {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01)

        tracker.onPeerConnected(peerId)
        tracker.onPeerDisconnected(peerId)
        tracker.sweep() // sweepCount 0 → 1

        // Reconnect resets sweepCount to 0
        tracker.onPeerConnected(peerId)
        assertEquals(0, tracker.allPeerStates()[peerId.toList()]!!.sweepCount)
    }

    @Test
    fun `sweep handles mixed Connected and Disconnected peers`() = runTest {
        val tracker = PresenceTracker()
        val peerA = byteArrayOf(0x0A) // will be Connected
        val peerB = byteArrayOf(0x0B) // will be Disconnected, swept to Gone
        val peerC = byteArrayOf(0x0C) // will be Disconnected, not yet Gone

        tracker.onPeerConnected(peerA)
        tracker.onPeerConnected(peerB)
        tracker.onPeerConnected(peerC)
        tracker.onPeerDisconnected(peerB)
        tracker.onPeerDisconnected(peerC)

        // 3 sweeps: peerB and peerC both Disconnected
        tracker.sweep() // both: sweepCount 0 → 1
        tracker.sweep() // both: sweepCount 1 → 2

        // Reconnect peerC before eviction
        tracker.onPeerConnected(peerC)

        val gone = tracker.sweep() // peerB: sweepCount 2 → evict; peerC: Connected (skip)

        // Only peerB should be Gone
        assertEquals(1, gone.size)
        assertTrue(gone[0].contentEquals(peerB))

        // peerA still Connected, peerC reconnected as Connected
        assertEquals(InternalPeerState.CONNECTED, tracker.peerState(peerA))
        assertEquals(InternalPeerState.CONNECTED, tracker.peerState(peerC))
        assertNull(tracker.peerState(peerB))
    }

    @Test
    fun `onPeerDisconnected resets sweepCount to 0`() {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01)
        tracker.onPeerConnected(peerId)
        tracker.onPeerDisconnected(peerId)

        assertEquals(0, tracker.allPeerStates()[peerId.toList()]!!.sweepCount)
    }

    @Test
    fun `sweep progressive increments before eviction`() {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01)
        tracker.onPeerConnected(peerId)
        tracker.onPeerDisconnected(peerId)

        // Sweep 1: count 0 → 1
        tracker.sweep()
        assertEquals(1, tracker.allPeerStates()[peerId.toList()]!!.sweepCount)

        // Sweep 2: count 1 → 2
        tracker.sweep()
        assertEquals(2, tracker.allPeerStates()[peerId.toList()]!!.sweepCount)

        // Sweep 3: count 2 ≥ 2 → evict
        val gone = tracker.sweep()
        assertEquals(1, gone.size)
        assertNull(tracker.peerState(peerId))
    }

    // ----------------------------------------------------------------
    // Duplicate peerLostEvent (onPeerDisconnected called after Gone)
    // ----------------------------------------------------------------

    @Test
    fun `disconnect after Gone adds peer as Disconnected again`() = runTest {
        val tracker = PresenceTracker()
        val peerId = byteArrayOf(0x01)
        val events = mutableListOf<PeerEvent>()

        val job = launch { tracker.peerEvents.collect { events.add(it) } }
        testScheduler.runCurrent()

        // Full lifecycle to Gone
        tracker.onPeerConnected(peerId)
        tracker.onPeerDisconnected(peerId)
        tracker.sweep()
        tracker.sweep()
        tracker.sweep() // evict
        testScheduler.runCurrent()

        assertNull(tracker.peerState(peerId))
        events.clear()

        // Late disconnect event arrives for already-Gone peer
        tracker.onPeerDisconnected(peerId)
        testScheduler.runCurrent()

        // Should be added as DISCONNECTED (edge case: unknown peer)
        assertEquals(InternalPeerState.DISCONNECTED, tracker.peerState(peerId))
        assertEquals(1, events.size)
        assertTrue(events[0] is PeerEvent.Disconnected)
        job.cancel()
    }
}
