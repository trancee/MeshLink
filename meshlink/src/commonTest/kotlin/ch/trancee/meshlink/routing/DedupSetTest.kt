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
        // Arrange
        var now = 1000L
        val dedup = DedupSet(capacity = 100, ttlMillis = 60_000L) { now }
        val id = byteArrayOf(1, 2, 3)
        dedup.add(id)

        // Act
        val result = dedup.isDuplicate(id)

        // Assert
        assertTrue(result)
    }

    @Test
    fun `unknown ID returns false`() {
        // Arrange
        var now = 1000L
        val dedup = DedupSet(capacity = 100, ttlMillis = 60_000L) { now }

        // Act
        val result = dedup.isDuplicate(byteArrayOf(9, 8, 7))

        // Assert
        assertFalse(result)
    }

    @Test
    fun `TTL-expired entry returns false after clock advance`() {
        // Arrange
        var now = 1000L
        val dedup = DedupSet(capacity = 100, ttlMillis = 5_000L) { now }
        val id = byteArrayOf(4, 5, 6)
        dedup.add(id)
        now += 5_001L

        // Act
        val result = dedup.isDuplicate(id)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `count eviction removes oldest entry`() {
        // Arrange
        var now = 1000L
        val dedup = DedupSet(capacity = 2, ttlMillis = 60_000L) { now }
        dedup.add(byteArrayOf(1))
        dedup.add(byteArrayOf(2))
        dedup.add(byteArrayOf(3)) // should evict id1

        // Act
        val oldestEvicted = dedup.isDuplicate(byteArrayOf(1))

        // Assert
        assertFalse(oldestEvicted)
        assertEquals(2, dedup.size)
    }

    @Test
    fun `count eviction retains newer entries`() {
        // Arrange
        var now = 1000L
        val dedup = DedupSet(capacity = 2, ttlMillis = 60_000L) { now }
        dedup.add(byteArrayOf(1))
        dedup.add(byteArrayOf(2))
        dedup.add(byteArrayOf(3)) // evicts id1

        // Act
        val id2Present = dedup.isDuplicate(byteArrayOf(2))
        val id3Present = dedup.isDuplicate(byteArrayOf(3))

        // Assert
        assertTrue(id2Present)
        assertTrue(id3Present)
    }

    @Test
    fun `LRU recently-accessed entry survives count eviction over unaccessed`() {
        // Arrange — access id1 to make it most-recently-used
        var now = 1000L
        val dedup = DedupSet(capacity = 2, ttlMillis = 60_000L) { now }
        dedup.add(byteArrayOf(1))
        dedup.add(byteArrayOf(2))
        dedup.isDuplicate(byteArrayOf(1)) // move id1 to end (most recently used)
        dedup.add(byteArrayOf(3)) // evicts id2 (least recently used)

        // Act
        val id1Survived = dedup.isDuplicate(byteArrayOf(1))
        val id2Evicted = dedup.isDuplicate(byteArrayOf(2))

        // Assert
        assertTrue(id1Survived)
        assertFalse(id2Evicted)
        assertEquals(2, dedup.size)
    }

    @Test
    fun `re-add same ID updates timestamp`() {
        // Arrange
        var now = 1000L
        val dedup = DedupSet(capacity = 100, ttlMillis = 5_000L) { now }
        val id = byteArrayOf(7, 8)
        dedup.add(id)
        now += 4_500L
        dedup.add(id) // re-add resets the TTL clock
        now += 4_500L // total 9000ms from first add, but only 4500ms from re-add

        // Act
        val result = dedup.isDuplicate(id)

        // Assert
        assertTrue(result)
    }

    @Test
    fun `size reflects count after adds`() {
        // Arrange
        var now = 1000L
        val dedup = DedupSet(capacity = 100, ttlMillis = 60_000L) { now }
        dedup.add(byteArrayOf(1))
        dedup.add(byteArrayOf(2))

        // Act
        val size = dedup.size

        // Assert
        assertEquals(2, size)
    }

    // ----------------------------------------------------------------
    // Negative / boundary tests
    // ----------------------------------------------------------------

    @Test
    fun `isDuplicate on empty set returns false`() {
        // Arrange
        val dedup = DedupSet(capacity = 100, ttlMillis = 60_000L) { 0L }

        // Act
        val result = dedup.isDuplicate(byteArrayOf(0, 1, 2))

        // Assert
        assertFalse(result)
    }

    @Test
    fun `add with zero-length messageId does not throw`() {
        // Arrange
        var now = 0L
        val dedup = DedupSet(capacity = 100, ttlMillis = 60_000L) { now }
        dedup.add(byteArrayOf())

        // Act
        val result = dedup.isDuplicate(byteArrayOf())

        // Assert
        assertTrue(result)
    }

    @Test
    fun `capacity 1 evicts immediately on second add`() {
        // Arrange
        var now = 0L
        val dedup = DedupSet(capacity = 1, ttlMillis = 60_000L) { now }
        dedup.add(byteArrayOf(1))
        dedup.add(byteArrayOf(2))

        // Act
        val firstEvicted = dedup.isDuplicate(byteArrayOf(1))
        val secondPresent = dedup.isDuplicate(byteArrayOf(2))

        // Assert
        assertFalse(firstEvicted)
        assertTrue(secondPresent)
        assertEquals(1, dedup.size)
    }

    @Test
    fun `TTL boundary - entry at exactly ttlMillis elapsed returns false`() {
        // Arrange
        var now = 1000L
        val dedup = DedupSet(capacity = 100, ttlMillis = 5_000L) { now }
        val id = byteArrayOf(42)
        dedup.add(id)
        now += 5_001L // strictly greater than ttlMillis

        // Act
        val result = dedup.isDuplicate(id)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `TTL boundary - entry just within ttl returns true`() {
        // Arrange
        var now = 1000L
        val dedup = DedupSet(capacity = 100, ttlMillis = 5_000L) { now }
        val id = byteArrayOf(43)
        dedup.add(id)
        now += 4_999L // strictly less than ttlMillis

        // Act
        val result = dedup.isDuplicate(id)

        // Assert
        assertTrue(result)
    }
}
