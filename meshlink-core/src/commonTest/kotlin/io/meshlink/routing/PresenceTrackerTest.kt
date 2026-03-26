package io.meshlink.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PresenceTrackerTest {

    @Test
    fun threeStateLifecycleWithTwoMissEviction() {
        val tracker = PresenceTracker()

        // Peer seen → Connected
        tracker.peerSeen("A")
        assertEquals(PresenceState.CONNECTED, tracker.state("A"))

        // First sweep miss → Disconnected
        tracker.sweep(seenPeers = emptySet())
        assertEquals(PresenceState.DISCONNECTED, tracker.state("A"))

        // Second consecutive miss → Gone (evicted)
        val evicted = tracker.sweep(seenPeers = emptySet())
        assertEquals(setOf("A"), evicted, "Peer A should be evicted after 2 misses")
        assertNull(tracker.state("A"), "Evicted peer should be gone")

        // If peer is seen again during Disconnected, goes back to Connected
        tracker.peerSeen("B")
        assertEquals(PresenceState.CONNECTED, tracker.state("B"))
        tracker.sweep(seenPeers = emptySet()) // B → Disconnected
        assertEquals(PresenceState.DISCONNECTED, tracker.state("B"))
        tracker.peerSeen("B") // B seen again → back to Connected
        assertEquals(PresenceState.CONNECTED, tracker.state("B"))
    }

    // --- Batch 11 Cycle 3: state(unknown) returns null ---

    @Test
    fun stateReturnsNullForUnknownPeer() {
        val tracker = PresenceTracker()
        assertNull(tracker.state("never-seen"), "Unknown peer should return null")

        tracker.peerSeen("A")
        assertNull(tracker.state("B"), "Untracked peer should return null even when others exist")
    }

    // --- Batch 11 Cycle 4: connectedPeerIds vs allPeerIds after disconnect ---

    @Test
    fun connectedVsAllPeerIdsAfterSweep() {
        val tracker = PresenceTracker()
        tracker.peerSeen("A")
        tracker.peerSeen("B")

        assertEquals(setOf("A", "B"), tracker.connectedPeerIds())
        assertEquals(setOf("A", "B"), tracker.allPeerIds())

        // Sweep with only A seen → B becomes DISCONNECTED
        tracker.sweep(setOf("A"))
        assertEquals(setOf("A"), tracker.connectedPeerIds(), "Only A is connected after sweep")
        assertEquals(setOf("A", "B"), tracker.allPeerIds(), "B still in allPeerIds (disconnected, not evicted)")

        // After clear
        tracker.clear()
        assertEquals(emptySet(), tracker.connectedPeerIds())
        assertEquals(emptySet(), tracker.allPeerIds())
    }

    // --- Batch 12 Cycle 7: sweep with all peers seen evicts none ---

    @Test
    fun sweepWithAllPeersSeenEvictsNone() {
        val tracker = PresenceTracker()
        tracker.peerSeen("A")
        tracker.peerSeen("B")
        tracker.peerSeen("C")

        // Sweep with all seen → all stay CONNECTED, no evictions
        val evicted1 = tracker.sweep(setOf("A", "B", "C"))
        assertEquals(emptySet(), evicted1)
        assertEquals(setOf("A", "B", "C"), tracker.connectedPeerIds())

        // Sweep again with all seen → still no evictions
        val evicted2 = tracker.sweep(setOf("A", "B", "C"))
        assertEquals(emptySet(), evicted2)

        // Now miss B twice → only B evicted
        tracker.sweep(setOf("A", "C")) // B → DISCONNECTED
        val evicted3 = tracker.sweep(setOf("A", "C")) // B → evicted
        assertEquals(setOf("B"), evicted3)
        assertEquals(setOf("A", "C"), tracker.connectedPeerIds())
    }
}
