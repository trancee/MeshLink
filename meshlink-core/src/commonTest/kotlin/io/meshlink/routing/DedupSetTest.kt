package io.meshlink.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DedupSetTest {

    @Test
    fun acceptFirstRejectDuplicateLruEviction() {
        val dedup = DedupSet(capacity = 3)

        // First time seeing each ID → accepted
        assertTrue(dedup.tryInsert("msg1"), "msg1 first time — accept")
        assertTrue(dedup.tryInsert("msg2"), "msg2 first time — accept")
        assertTrue(dedup.tryInsert("msg3"), "msg3 first time — accept")

        // Duplicates → rejected
        assertFalse(dedup.tryInsert("msg1"), "msg1 duplicate — reject")
        assertFalse(dedup.tryInsert("msg2"), "msg2 duplicate — reject")
        // Access order now: [msg3, msg1, msg2] — msg3 is LRU

        // Insert msg4 → evicts LRU (msg3)
        assertTrue(dedup.tryInsert("msg4"), "msg4 new — accept, evicts msg3 (LRU)")

        // msg3 was evicted — now accepted as new
        assertTrue(dedup.tryInsert("msg3"), "msg3 was evicted — accept as new")

        // msg4 should still be present
        assertFalse(dedup.tryInsert("msg4"), "msg4 still present — reject")
    }

    // --- Batch 10 Cycle 6: capacity=1 edge case ---

    @Test
    fun capacityOneEvictsImmediatelyOnNewInsert() {
        val dedup = DedupSet(capacity = 1)

        assertTrue(dedup.tryInsert("a"), "First insert accepted")
        assertFalse(dedup.tryInsert("a"), "Duplicate rejected")
        assertEquals(1, dedup.size())

        // Insert b evicts a
        assertTrue(dedup.tryInsert("b"), "New insert evicts only slot")
        assertEquals(1, dedup.size())

        // a is now gone, b is present
        assertTrue(dedup.tryInsert("a"), "a was evicted — accepted as new")
        assertFalse(dedup.tryInsert("a"), "a is now present — rejected")
    }
}
