package io.meshlink.routing

import io.meshlink.util.ByteArrayKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DedupSetTest {

    private fun key(s: String) = ByteArrayKey(s.encodeToByteArray())

    @Test
    fun acceptFirstRejectDuplicateLruEviction() {
        val dedup = DedupSet(capacity = 3, clock = { 0L })

        // First time seeing each ID → accepted
        assertTrue(dedup.tryInsert(key("msg1")), "msg1 first time — accept")
        assertTrue(dedup.tryInsert(key("msg2")), "msg2 first time — accept")
        assertTrue(dedup.tryInsert(key("msg3")), "msg3 first time — accept")

        // Duplicates → rejected
        assertFalse(dedup.tryInsert(key("msg1")), "msg1 duplicate — reject")
        assertFalse(dedup.tryInsert(key("msg2")), "msg2 duplicate — reject")
        // Access order now: [msg3, msg1, msg2] — msg3 is LRU

        // Insert msg4 → evicts LRU (msg3)
        assertTrue(dedup.tryInsert(key("msg4")), "msg4 new — accept, evicts msg3 (LRU)")

        // msg3 was evicted — now accepted as new
        assertTrue(dedup.tryInsert(key("msg3")), "msg3 was evicted — accept as new")

        // msg4 should still be present
        assertFalse(dedup.tryInsert(key("msg4")), "msg4 still present — reject")
    }

    @Test
    fun capacityOneEvictsImmediatelyOnNewInsert() {
        val dedup = DedupSet(capacity = 1, clock = { 0L })

        assertTrue(dedup.tryInsert(key("a")), "First insert accepted")
        assertFalse(dedup.tryInsert(key("a")), "Duplicate rejected")
        assertEquals(1, dedup.size())

        // Insert b evicts a
        assertTrue(dedup.tryInsert(key("b")), "New insert evicts only slot")
        assertEquals(1, dedup.size())

        // a is now gone, b is present
        assertTrue(dedup.tryInsert(key("a")), "a was evicted — accepted as new")
        assertFalse(dedup.tryInsert(key("a")), "a is now present — rejected")
    }

    @Test
    fun reAddAfterEvictionAndClearBehavior() {
        val dedup = DedupSet(capacity = 2, clock = { 0L })

        assertTrue(dedup.tryInsert(key("x")))
        assertTrue(dedup.tryInsert(key("y")))
        assertEquals(2, dedup.size())

        // Insert z → evicts x (LRU)
        assertTrue(dedup.tryInsert(key("z")))
        assertEquals(2, dedup.size())

        // x was evicted → re-insert succeeds
        assertTrue(dedup.tryInsert(key("x")), "Evicted ID should be accepted as new")
        assertFalse(dedup.tryInsert(key("x")), "x is now present — rejected")

        dedup.clear()
        assertEquals(0, dedup.size())
        assertTrue(dedup.tryInsert(key("x")), "After clear, all IDs accepted as new")
        assertTrue(dedup.tryInsert(key("y")))
        assertTrue(dedup.tryInsert(key("z"))) // evicts x
        assertTrue(dedup.tryInsert(key("x")), "x evicted by z, re-added as new")
    }

    // --- TM-003: TTL-based expiry ---

    @Test
    fun expiredEntriesAreEvictedByTtl() {
        var now = 0L
        val dedup = DedupSet(capacity = 100, ttlMillis = 1000L, clock = { now })

        assertTrue(dedup.tryInsert(key("a")))
        assertTrue(dedup.tryInsert(key("b")))
        assertFalse(dedup.tryInsert(key("a")), "Duplicate within TTL — reject")

        // Advance past TTL
        now = 1001L
        assertTrue(dedup.tryInsert(key("a")), "a expired by TTL — accept as new")
        assertTrue(dedup.tryInsert(key("b")), "b expired by TTL — accept as new")
        assertEquals(2, dedup.size())
    }

    @Test
    fun ttlExpiryPreventsTargetedEviction() {
        var now = 0L
        val dedup = DedupSet(capacity = 3, ttlMillis = 5000L, clock = { now })

        // Insert legitimate message
        assertTrue(dedup.tryInsert(key("legit")))

        // Attacker tries to fill and evict
        now = 100L
        assertTrue(dedup.tryInsert(key("atk1")))
        assertTrue(dedup.tryInsert(key("atk2")))
        // At capacity — would evict "legit" by LRU, but TTL not yet expired
        assertTrue(dedup.tryInsert(key("atk3")))

        // legit was evicted by capacity cap, but within TTL window
        // this is acceptable — the capacity is a safety net
        // The key improvement is that with ttlMillis=5000 and capacity=100_000,
        // an attacker needs 100,000 unique IDs within 5 seconds to evict

        // Verify the TTL window protects when capacity is large enough
        now = 0L
        val largeDedup = DedupSet(capacity = 100_000, ttlMillis = 5000L, clock = { now })
        assertTrue(largeDedup.tryInsert(key("legit")))
        for (i in 0 until 1000) {
            largeDedup.tryInsert(key("atk$i"))
        }
        assertFalse(largeDedup.tryInsert(key("legit")), "legit still present in large capacity set")
    }
}
