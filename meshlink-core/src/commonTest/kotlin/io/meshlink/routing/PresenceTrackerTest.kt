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
}
