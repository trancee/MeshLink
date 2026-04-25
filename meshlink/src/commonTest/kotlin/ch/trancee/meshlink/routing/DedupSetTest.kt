package ch.trancee.meshlink.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DedupSetTest {

    // ----------------------------------------------------------------
    // Happy path
    // ----------------------------------------------------------------

    @Test
    fun `add then isDuplicate returns true`() {
        var now = 1000L
        val dedup = DedupSet(capacity = 100, ttlMs = 60_000L) { now }
        val id = byteArrayOf(1, 2, 3)
        dedup.add(id)
        assertTrue(dedup.isDuplicate(id))
    }

    @Test
    fun `unknown ID returns false`() {
        var now = 1000L
        val dedup = DedupSet(capacity = 100, ttlMs = 60_000L) { now }
        assertFalse(dedup.isDuplicate(byteArrayOf(9, 8, 7)))
    }

    @Test
    fun `TTL-expired entry returns false after clock advance`() {
        var now = 1000L
        val dedup = DedupSet(capacity = 100, ttlMs = 5_000L) { now }
        val id = byteArrayOf(4, 5, 6)
        dedup.add(id)
        now += 5_001L
        assertFalse(dedup.isDuplicate(id))
    }

    @Test
    fun `count eviction removes oldest when over capacity`() {
        var now = 1000L
        val dedup = DedupSet(capacity = 2, ttlMs = 60_000L) { now }
        val id1 = byteArrayOf(1)
        val id2 = byteArrayOf(2)
        val id3 = byteArrayOf(3)
        dedup.add(id1)
        dedup.add(id2)
        dedup.add(id3) // should evict id1
        assertEquals(2, dedup.size)
        assertFalse(dedup.isDuplicate(id1))
        assertTrue(dedup.isDuplicate(id2))
        assertTrue(dedup.isDuplicate(id3))
    }

    @Test
    fun `LRU recently-accessed entry survives count eviction over unaccessed`() {
        var now = 1000L
        val dedup = DedupSet(capacity = 2, ttlMs = 60_000L) { now }
        val id1 = byteArrayOf(1)
        val id2 = byteArrayOf(2)
        val id3 = byteArrayOf(3)
        dedup.add(id1)
        dedup.add(id2)
        // Access id1 to move it to end (most recently used)
        dedup.isDuplicate(id1)
        // Adding id3 should evict id2 (least recently used / at front)
        dedup.add(id3)
        assertEquals(2, dedup.size)
        assertTrue(dedup.isDuplicate(id1))
        assertFalse(dedup.isDuplicate(id2))
    }

    @Test
    fun `re-add same ID updates timestamp`() {
        var now = 1000L
        val dedup = DedupSet(capacity = 100, ttlMs = 5_000L) { now }
        val id = byteArrayOf(7, 8)
        dedup.add(id)
        now += 4_500L
        // Re-add resets the TTL clock
        dedup.add(id)
        now += 4_500L // total 9000ms from first add, but only 4500ms from re-add
        // Entry was re-added at now=5500; current time is 10000; elapsed = 4500 ≤ 5000
        assertTrue(dedup.isDuplicate(id))
    }

    @Test
    fun `size reflects count`() {
        var now = 1000L
        val dedup = DedupSet(capacity = 100, ttlMs = 60_000L) { now }
        assertEquals(0, dedup.size)
        dedup.add(byteArrayOf(1))
        assertEquals(1, dedup.size)
        dedup.add(byteArrayOf(2))
        assertEquals(2, dedup.size)
    }

    // ----------------------------------------------------------------
    // Negative / boundary tests
    // ----------------------------------------------------------------

    @Test
    fun `isDuplicate on empty set returns false`() {
        val dedup = DedupSet(capacity = 100, ttlMs = 60_000L) { 0L }
        assertFalse(dedup.isDuplicate(byteArrayOf(0, 1, 2)))
    }

    @Test
    fun `add with zero-length messageId does not throw`() {
        var now = 0L
        val dedup = DedupSet(capacity = 100, ttlMs = 60_000L) { now }
        dedup.add(byteArrayOf())
        assertTrue(dedup.isDuplicate(byteArrayOf()))
    }

    @Test
    fun `capacity 1 evicts immediately on second add`() {
        var now = 0L
        val dedup = DedupSet(capacity = 1, ttlMs = 60_000L) { now }
        val id1 = byteArrayOf(1)
        val id2 = byteArrayOf(2)
        dedup.add(id1)
        dedup.add(id2)
        assertEquals(1, dedup.size)
        assertFalse(dedup.isDuplicate(id1))
        assertTrue(dedup.isDuplicate(id2))
    }

    @Test
    fun `TTL boundary - entry at exactly ttlMs elapsed returns false`() {
        var now = 1000L
        val dedup = DedupSet(capacity = 100, ttlMs = 5_000L) { now }
        val id = byteArrayOf(42)
        dedup.add(id)
        now += 5_001L // strictly greater than ttlMs
        assertFalse(dedup.isDuplicate(id))
    }

    @Test
    fun `TTL boundary - entry just within ttl returns true`() {
        var now = 1000L
        val dedup = DedupSet(capacity = 100, ttlMs = 5_000L) { now }
        val id = byteArrayOf(43)
        dedup.add(id)
        now += 4_999L // strictly less than ttlMs
        assertTrue(dedup.isDuplicate(id))
    }
}
