package ch.trancee.meshlink.transport

import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OemL2capProbeCacheTest {

    // ── get: unknown key returns null ─────────────────────────────────────────

    @Test
    fun getUnknownKeyReturnsNull() {
        val cache = OemL2capProbeCache()
        assertNull(cache.get("Acme|DeviceX|30"))
    }

    // ── put + get round-trip ──────────────────────────────────────────────────

    @Test
    fun putAndGetTrueRoundTrip() {
        val cache = OemL2capProbeCache()
        cache.put("Samsung|Galaxy S22|33", true)
        assertTrue(cache.get("Samsung|Galaxy S22|33")!!)
    }

    @Test
    fun putAndGetFalseRoundTrip() {
        val cache = OemL2capProbeCache()
        cache.put("Xiaomi|Redmi Note 11|31", false)
        assertFalse(cache.get("Xiaomi|Redmi Note 11|31")!!)
    }

    @Test
    fun iosKeyRoundTrip() {
        val cache = OemL2capProbeCache()
        cache.put("Apple|iPhone15,2|17.0", true)
        assertTrue(cache.get("Apple|iPhone15,2|17.0")!!)
    }

    // ── put: overwrite existing entry ─────────────────────────────────────────

    @Test
    fun overwriteExistingEntryRetainsLastValue() {
        val cache = OemL2capProbeCache()
        cache.put("OnePlus|9|31", true)
        cache.put("OnePlus|9|31", false)
        assertFalse(cache.get("OnePlus|9|31")!!)
    }

    @Test
    fun overwriteFalseWithTrue() {
        val cache = OemL2capProbeCache()
        cache.put("Pixel|6|32", false)
        cache.put("Pixel|6|32", true)
        assertTrue(cache.get("Pixel|6|32")!!)
    }

    // ── size tracking ─────────────────────────────────────────────────────────

    @Test
    fun initialSizeIsZero() {
        val cache = OemL2capProbeCache()
        assertEquals(0, cache.size)
    }

    @Test
    fun sizeIncreasesWithEachDistinctPut() {
        val cache = OemL2capProbeCache()
        cache.put("Samsung|A53|33", true)
        assertEquals(1, cache.size)
        cache.put("Huawei|P40|29", false)
        assertEquals(2, cache.size)
    }

    @Test
    fun sizeDoesNotIncreaseOnOverwrite() {
        val cache = OemL2capProbeCache()
        cache.put("Motorola|Edge|30", true)
        cache.put("Motorola|Edge|30", false)
        assertEquals(1, cache.size)
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    fun clearResetsToEmpty() {
        val cache = OemL2capProbeCache()
        cache.put("Samsung|Galaxy S22|33", true)
        cache.put("Pixel|6|32", false)
        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun getAfterClearReturnsNull() {
        val cache = OemL2capProbeCache()
        cache.put("Samsung|Galaxy S22|33", true)
        cache.clear()
        assertNull(cache.get("Samsung|Galaxy S22|33"))
    }

    @Test
    fun clearOnEmptyCacheIsIdempotent() {
        val cache = OemL2capProbeCache()
        cache.clear()
        assertEquals(0, cache.size)
    }

    // ── isolation: each instance is independent ───────────────────────────────

    @Test
    fun twoInstancesAreIndependent() {
        val cacheA = OemL2capProbeCache()
        val cacheB = OemL2capProbeCache()
        cacheA.put("Sony|Xperia 1|31", true)
        assertNull(cacheB.get("Sony|Xperia 1|31"))
        assertEquals(0, cacheB.size)
    }

    // ── persist: empty cache stores empty ByteArray ───────────────────────────

    @Test
    fun persistEmptyCacheStoresEmptyBytes() {
        val cache = OemL2capProbeCache()
        val storage = InMemorySecureStorage()
        cache.persist(storage)
        val bytes = storage.get("oemL2capProbeCache")
        assertNotNull(bytes)
        assertEquals(0, bytes.size)
    }

    // ── restore: missing key is a no-op ───────────────────────────────────────

    @Test
    fun restoreFromMissingKeyIsNoOp() {
        val cache = OemL2capProbeCache()
        val storage = InMemorySecureStorage()
        cache.restore(storage)
        assertEquals(0, cache.size)
    }

    // ── restore: empty ByteArray is a no-op ───────────────────────────────────

    @Test
    fun restoreFromEmptyBytesIsNoOp() {
        val cache = OemL2capProbeCache()
        val storage = InMemorySecureStorage()
        storage.put("oemL2capProbeCache", ByteArray(0))
        cache.restore(storage)
        assertEquals(0, cache.size)
    }

    // ── persist + restore: round-trip with true and false entries ─────────────

    @Test
    fun persistAndRestoreRoundTrip() {
        val storage = InMemorySecureStorage()
        val cacheA = OemL2capProbeCache()
        cacheA.put("Samsung|Galaxy S22|33", true)
        cacheA.put("Xiaomi|Redmi Note 11|31", false)
        cacheA.persist(storage)

        val cacheB = OemL2capProbeCache()
        cacheB.restore(storage)
        assertTrue(cacheB.get("Samsung|Galaxy S22|33")!!)
        assertFalse(cacheB.get("Xiaomi|Redmi Note 11|31")!!)
        assertEquals(2, cacheB.size)
    }

    // ── restore: malformed entry with no colon is silently skipped ────────────

    @Test
    fun restoreSkipsMalformedEntryWithNoColon() {
        val cache = OemL2capProbeCache()
        val storage = InMemorySecureStorage()
        storage.put("oemL2capProbeCache", "nocolon".encodeToByteArray())
        cache.restore(storage)
        assertEquals(0, cache.size)
    }

    // ── restore: entry with unrecognised value is silently skipped ────────────

    @Test
    fun restoreSkipsEntryWithUnrecognisedValue() {
        val cache = OemL2capProbeCache()
        val storage = InMemorySecureStorage()
        storage.put("oemL2capProbeCache", "Samsung|Galaxy S22|33:garbage".encodeToByteArray())
        cache.restore(storage)
        assertNull(cache.get("Samsung|Galaxy S22|33"))
        assertEquals(0, cache.size)
    }

    // ── overwrite then persist retains the latest value ───────────────────────

    @Test
    fun overwriteThenPersistRetainsLatestValue() {
        val storage = InMemorySecureStorage()
        val cacheA = OemL2capProbeCache()
        cacheA.put("OnePlus|9|31", true)
        cacheA.put("OnePlus|9|31", false)
        cacheA.persist(storage)

        val cacheB = OemL2capProbeCache()
        cacheB.restore(storage)
        assertFalse(cacheB.get("OnePlus|9|31")!!)
        assertEquals(1, cacheB.size)
    }
}
