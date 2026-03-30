package io.meshlink.dispatch

import io.meshlink.util.ByteArrayKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboundTrackerTest {

    private fun key(s: String) = ByteArrayKey(s.encodeToByteArray())

    @Test
    fun registerAndLookupRecipient() {
        val tracker = OutboundTracker()
        val peer = byteArrayOf(1, 2, 3)
        tracker.registerRecipient(key("key1"), peer)
        assertContentEquals(peer, tracker.recipient(key("key1")))
    }

    @Test
    fun recipientReturnsNullForUnknownKey() {
        val tracker = OutboundTracker()
        assertNull(tracker.recipient(key("unknown")))
    }

    @Test
    fun removeRecipientReturnsAndRemoves() {
        val tracker = OutboundTracker()
        tracker.registerRecipient(key("key1"), byteArrayOf(1))
        assertNotNull(tracker.removeRecipient(key("key1")))
        assertNull(tracker.recipient(key("key1")))
    }

    @Test
    fun registerAndRemoveNextHop() {
        val tracker = OutboundTracker()
        tracker.registerNextHop(key("key1"), key("0a0b0c"))
        assertEquals(key("0a0b0c"), tracker.removeNextHop(key("key1")))
        assertNull(tracker.removeNextHop(key("key1")))
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
        tracker.registerRecipient(key("a"), byteArrayOf(1))
        tracker.registerRecipient(key("b"), byteArrayOf(2))
        val keys = tracker.allRecipientKeys()
        assertEquals(setOf(key("a"), key("b")), keys)
    }

    @Test
    fun clearRemovesAllState() {
        val tracker = OutboundTracker()
        tracker.registerRecipient(key("key1"), byteArrayOf(1))
        tracker.registerNextHop(key("key1"), key("hop1"))
        tracker.clear()
        assertNull(tracker.recipient(key("key1")))
        assertNull(tracker.removeNextHop(key("key1")))
        assertTrue(tracker.allRecipientKeys().isEmpty())
    }
}
