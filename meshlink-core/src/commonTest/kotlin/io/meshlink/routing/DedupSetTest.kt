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

    // --- Batch 13 Cycle 7: Re-add after eviction detected as new ---

    @Test
    fun reAddAfterEvictionAndClearBehavior() {
        val dedup = DedupSet(capacity = 2)

        assertTrue(dedup.tryInsert("x"))
        assertTrue(dedup.tryInsert("y"))
        // x is LRU (y was inserted last)
        assertEquals(2, dedup.size())

        // Insert z → evicts x (LRU)
        assertTrue(dedup.tryInsert("z"))
        assertEquals(2, dedup.size())

        // x was evicted → re-insert succeeds (fresh detection)
        assertTrue(dedup.tryInsert("x"), "Evicted ID should be accepted as new")
        // y was evicted by x (y was now LRU since z was more recent)
        assertFalse(dedup.tryInsert("x"), "x is now present — rejected")

        // Clear empties everything
        dedup.clear()
        assertEquals(0, dedup.size())
        assertTrue(dedup.tryInsert("x"), "After clear, all IDs accepted as new")
        assertTrue(dedup.tryInsert("y"))
        assertTrue(dedup.tryInsert("z")) // evicts x
        assertTrue(dedup.tryInsert("x"), "x evicted by z, re-added as new")
    }
}
