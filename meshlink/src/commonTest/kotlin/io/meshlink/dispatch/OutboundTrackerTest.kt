package io.meshlink.dispatch

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboundTrackerTest {

    @Test
    fun registerAndLookupRecipient() {
        val tracker = OutboundTracker()
        val peer = byteArrayOf(1, 2, 3)
        tracker.registerRecipient("key1", peer)
        assertContentEquals(peer, tracker.recipient("key1"))
    }

    @Test
    fun recipientReturnsNullForUnknownKey() {
        val tracker = OutboundTracker()
        assertNull(tracker.recipient("unknown"))
    }

    @Test
    fun removeRecipientReturnsAndRemoves() {
        val tracker = OutboundTracker()
        tracker.registerRecipient("key1", byteArrayOf(1))
        assertNotNull(tracker.removeRecipient("key1"))
        assertNull(tracker.recipient("key1"))
    }

    @Test
    fun registerAndRemoveNextHop() {
        val tracker = OutboundTracker()
        tracker.registerNextHop("key1", "0a0b0c")
        assertEquals("0a0b0c", tracker.removeNextHop("key1"))
        assertNull(tracker.removeNextHop("key1"))
    }

    @Test
    fun advanceReplayCounterIsMonotonic() {
        val tracker = OutboundTracker()
        val first = tracker.advanceReplayCounter()
        val second = tracker.advanceReplayCounter()
        assertTrue(second > first)
    }

    @Test
    fun allRecipientKeysReturnsSnapshot() {
        val tracker = OutboundTracker()
        tracker.registerRecipient("a", byteArrayOf(1))
        tracker.registerRecipient("b", byteArrayOf(2))
        val keys = tracker.allRecipientKeys()
        assertEquals(setOf("a", "b"), keys)
    }

    @Test
    fun clearRemovesAllState() {
        val tracker = OutboundTracker()
        tracker.registerRecipient("key1", byteArrayOf(1))
        tracker.registerNextHop("key1", "hop1")
        tracker.clear()
        assertNull(tracker.recipient("key1"))
        assertNull(tracker.removeNextHop("key1"))
        assertTrue(tracker.allRecipientKeys().isEmpty())
    }
}
