package io.meshlink.routing

import io.meshlink.util.ByteArrayKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PresenceTrackerTest {

    private fun key(s: String) = ByteArrayKey(s.encodeToByteArray())

    @Test
    fun threeStateLifecycleWithTwoMissEviction() {
        val tracker = PresenceTracker()

        // Peer seen → Connected
        tracker.peerSeen(key("A"))
        assertEquals(PresenceState.CONNECTED, tracker.state(key("A")))

        // First sweep miss → Disconnected
        tracker.sweep(seenPeers = emptySet())
        assertEquals(PresenceState.DISCONNECTED, tracker.state(key("A")))

        // Second consecutive miss → Gone (evicted)
        val evicted = tracker.sweep(seenPeers = emptySet())
        assertEquals(setOf(key("A")), evicted, "Peer A should be evicted after 2 misses")
        assertNull(tracker.state(key("A")), "Evicted peer should be gone")

        // If peer is seen again during Disconnected, goes back to Connected
        tracker.peerSeen(key("B"))
        assertEquals(PresenceState.CONNECTED, tracker.state(key("B")))
        tracker.sweep(seenPeers = emptySet()) // B → Disconnected
        assertEquals(PresenceState.DISCONNECTED, tracker.state(key("B")))
        tracker.peerSeen(key("B")) // B seen again → back to Connected
        assertEquals(PresenceState.CONNECTED, tracker.state(key("B")))
    }

    // --- Batch 11 Cycle 3: state(unknown) returns null ---

    @Test
    fun stateReturnsNullForUnknownPeer() {
        val tracker = PresenceTracker()
        assertNull(tracker.state(key("never-seen")), "Unknown peer should return null")

        tracker.peerSeen(key("A"))
        assertNull(tracker.state(key("B")), "Untracked peer should return null even when others exist")
    }

    // --- Batch 11 Cycle 4: connectedPeerIds vs allPeerIds after disconnect ---

    @Test
    fun connectedVsAllPeerIdsAfterSweep() {
        val tracker = PresenceTracker()
        tracker.peerSeen(key("A"))
        tracker.peerSeen(key("B"))

        assertEquals(setOf(key("A"), key("B")), tracker.connectedPeerIds())
        assertEquals(setOf(key("A"), key("B")), tracker.allPeerIds())

        // Sweep with only A seen → B becomes DISCONNECTED
        tracker.sweep(setOf(key("A")))
        assertEquals(setOf(key("A")), tracker.connectedPeerIds(), "Only A is connected after sweep")
        assertEquals(setOf(key("A"), key("B")), tracker.allPeerIds(), "B still in allPeerIds (disconnected, not evicted)")

        // After clear
        tracker.clear()
        assertEquals(emptySet<ByteArrayKey>(), tracker.connectedPeerIds())
        assertEquals(emptySet<ByteArrayKey>(), tracker.allPeerIds())
    }

    // --- Batch 12 Cycle 7: sweep with all peers seen evicts none ---

    @Test
    fun sweepWithAllPeersSeenEvictsNone() {
        val tracker = PresenceTracker()
        tracker.peerSeen(key("A"))
        tracker.peerSeen(key("B"))
        tracker.peerSeen(key("C"))

        // Sweep with all seen → all stay CONNECTED, no evictions
        val evicted1 = tracker.sweep(setOf(key("A"), key("B"), key("C")))
        assertEquals(emptySet<ByteArrayKey>(), evicted1)
        assertEquals(setOf(key("A"), key("B"), key("C")), tracker.connectedPeerIds())

        // Sweep again with all seen → still no evictions
        val evicted2 = tracker.sweep(setOf(key("A"), key("B"), key("C")))
        assertEquals(emptySet<ByteArrayKey>(), evicted2)

        // Now miss B twice → only B evicted
        tracker.sweep(setOf(key("A"), key("C"))) // B → DISCONNECTED
        val evicted3 = tracker.sweep(setOf(key("A"), key("C"))) // B → evicted
        assertEquals(setOf(key("B")), evicted3)
        assertEquals(setOf(key("A"), key("C")), tracker.connectedPeerIds())
    }

    // --- 3-State Presence Lifecycle: GONE state ---

    @Test
    fun goneStateExplicitLifecycle() {
        val tracker = PresenceTracker()

        tracker.peerSeen(key("X"))
        assertEquals(PresenceState.CONNECTED, tracker.state(key("X")))

        // First miss → DISCONNECTED
        tracker.sweep(emptySet())
        assertEquals(PresenceState.DISCONNECTED, tracker.state(key("X")))

        // Peer seen again after DISCONNECTED → back to CONNECTED
        tracker.peerSeen(key("X"))
        assertEquals(PresenceState.CONNECTED, tracker.state(key("X")))

        // Miss again → DISCONNECTED
        tracker.sweep(emptySet())
        assertEquals(PresenceState.DISCONNECTED, tracker.state(key("X")))

        // Second consecutive miss → GONE (evicted)
        val evicted = tracker.sweep(emptySet())
        assertEquals(setOf(key("X")), evicted)
        assertNull(tracker.state(key("X")), "Peer should be null (evicted/GONE)")
    }
}
