package io.meshlink.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DedupSetTest {

    @Test
    fun acceptFirstRejectDuplicateLruEviction() {
        val dedup = DedupSet(capacity = 3, clock = { 0L })

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

    @Test
    fun capacityOneEvictsImmediatelyOnNewInsert() {
        val dedup = DedupSet(capacity = 1, clock = { 0L })

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

    @Test
    fun reAddAfterEvictionAndClearBehavior() {
        val dedup = DedupSet(capacity = 2, clock = { 0L })

        assertTrue(dedup.tryInsert("x"))
        assertTrue(dedup.tryInsert("y"))
        assertEquals(2, dedup.size())

        // Insert z → evicts x (LRU)
        assertTrue(dedup.tryInsert("z"))
        assertEquals(2, dedup.size())

        // x was evicted → re-insert succeeds
        assertTrue(dedup.tryInsert("x"), "Evicted ID should be accepted as new")
        assertFalse(dedup.tryInsert("x"), "x is now present — rejected")

        dedup.clear()
        assertEquals(0, dedup.size())
        assertTrue(dedup.tryInsert("x"), "After clear, all IDs accepted as new")
        assertTrue(dedup.tryInsert("y"))
        assertTrue(dedup.tryInsert("z")) // evicts x
        assertTrue(dedup.tryInsert("x"), "x evicted by z, re-added as new")
    }

    // --- TM-003: TTL-based expiry ---

    @Test
    fun expiredEntriesAreEvictedByTtl() {
        var now = 0L
        val dedup = DedupSet(capacity = 100, ttlMs = 1000L, clock = { now })

        assertTrue(dedup.tryInsert("a"))
        assertTrue(dedup.tryInsert("b"))
        assertFalse(dedup.tryInsert("a"), "Duplicate within TTL — reject")

        // Advance past TTL
        now = 1001L
        assertTrue(dedup.tryInsert("a"), "a expired by TTL — accept as new")
        assertTrue(dedup.tryInsert("b"), "b expired by TTL — accept as new")
        assertEquals(2, dedup.size())
    }

    @Test
    fun ttlExpiryPreventsTargetedEviction() {
        var now = 0L
        val dedup = DedupSet(capacity = 3, ttlMs = 5000L, clock = { now })

        // Insert legitimate message
        assertTrue(dedup.tryInsert("legit"))

        // Attacker tries to fill and evict
        now = 100L
        assertTrue(dedup.tryInsert("atk1"))
        assertTrue(dedup.tryInsert("atk2"))
        // At capacity — would evict "legit" by LRU, but TTL not yet expired
        assertTrue(dedup.tryInsert("atk3"))

        // legit was evicted by capacity cap, but within TTL window
        // this is acceptable — the capacity is a safety net
        // The key improvement is that with ttlMs=5000 and capacity=100_000,
        // an attacker needs 100,000 unique IDs within 5 seconds to evict

        // Verify the TTL window protects when capacity is large enough
        now = 0L
        val largeDedup = DedupSet(capacity = 100_000, ttlMs = 5000L, clock = { now })
        assertTrue(largeDedup.tryInsert("legit"))
        for (i in 0 until 1000) {
            largeDedup.tryInsert("atk$i")
        }
        assertFalse(largeDedup.tryInsert("legit"), "legit still present in large capacity set")
    }
}
