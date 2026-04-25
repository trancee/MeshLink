package ch.trancee.meshlink.crypto.noise

import ch.trancee.meshlink.crypto.createCryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class DhCacheTest {

    private val crypto = createCryptoProvider()

    // ── Cache miss: computes, stores, and returns the secret ─────────────────

    @Test
    fun cacheMissComputesAndStores() {
        val localKey = crypto.generateX25519KeyPair()
        val remoteKey = crypto.generateX25519KeyPair()
        val cache = DhCache()

        assertEquals(0, cache.size)

        val result = cache.getOrCompute(crypto, localKey.privateKey, remoteKey.publicKey)

        assertEquals(1, cache.size)
        assertEquals(32, result.size)
    }

    // ── Cache hit: returns the same ByteArray instance without recomputing ────

    @Test
    fun cacheHitReturnsSameInstance() {
        val localKey = crypto.generateX25519KeyPair()
        val remoteKey = crypto.generateX25519KeyPair()
        val cache = DhCache()

        val first = cache.getOrCompute(crypto, localKey.privateKey, remoteKey.publicKey)
        val second = cache.getOrCompute(crypto, localKey.privateKey, remoteKey.publicKey)

        // Must be the same ByteArray reference — proves the cache was hit, not recomputed
        assertSame(first, second)
        assertEquals(1, cache.size)
    }

    // ── Cache content matches a direct X25519 computation ────────────────────

    @Test
    fun cacheContentMatchesManualDh() {
        val localKey = crypto.generateX25519KeyPair()
        val remoteKey = crypto.generateX25519KeyPair()
        val cache = DhCache()

        val cached = cache.getOrCompute(crypto, localKey.privateKey, remoteKey.publicKey)
        val manual = crypto.x25519SharedSecret(localKey.privateKey, remoteKey.publicKey)

        assertContentEquals(manual, cached)
    }

    // ── Multiple different remote keys produce separate cache entries ─────────

    @Test
    fun multipleRemoteKeysProduceSeparateEntries() {
        val localKey = crypto.generateX25519KeyPair()
        val remoteKeyA = crypto.generateX25519KeyPair()
        val remoteKeyB = crypto.generateX25519KeyPair()
        val cache = DhCache()

        val resultA = cache.getOrCompute(crypto, localKey.privateKey, remoteKeyA.publicKey)
        val resultB = cache.getOrCompute(crypto, localKey.privateKey, remoteKeyB.publicKey)

        assertEquals(2, cache.size)
        // Different remote keys must produce different secrets (overwhelmingly likely)
        assertFalse(
            resultA.contentEquals(resultB),
            "Different remote keys should yield different secrets",
        )
    }

    // ── Clear zeroizes cached secrets and empties the map ────────────────────

    @Test
    fun clearZeroizesAndEmptiesCache() {
        val localKey = crypto.generateX25519KeyPair()
        val remoteKeyA = crypto.generateX25519KeyPair()
        val remoteKeyB = crypto.generateX25519KeyPair()
        val cache = DhCache()

        // Populate cache with two entries
        cache.getOrCompute(crypto, localKey.privateKey, remoteKeyA.publicKey)
        cache.getOrCompute(crypto, localKey.privateKey, remoteKeyB.publicKey)
        assertEquals(2, cache.size)

        cache.clear()

        assertEquals(0, cache.size)
    }

    // ── Clear on empty cache: no-op, does not throw ───────────────────────────

    @Test
    fun clearOnEmptyCacheIsNoOp() {
        val cache = DhCache()
        assertEquals(0, cache.size)
        // Must not throw; for-loop body is not entered (no entries to zeroize)
        cache.clear()
        assertEquals(0, cache.size)
    }

    // ── Different local keys produce different cached secrets ─────────────────

    @Test
    fun differentLocalKeysProduceDifferentSecrets() {
        val localKeyA = crypto.generateX25519KeyPair()
        val localKeyB = crypto.generateX25519KeyPair()
        val remoteKey = crypto.generateX25519KeyPair()
        val cacheA = DhCache()
        val cacheB = DhCache()

        val secretA = cacheA.getOrCompute(crypto, localKeyA.privateKey, remoteKey.publicKey)
        val secretB = cacheB.getOrCompute(crypto, localKeyB.privateKey, remoteKey.publicKey)

        assertFalse(
            secretA.contentEquals(secretB),
            "Different local keys must yield different DH secrets",
        )
    }

    // ── After clear, getOrCompute recomputes the secret (cache miss) ──────────

    @Test
    fun getOrComputeAfterClearRecomputes() {
        val localKey = crypto.generateX25519KeyPair()
        val remoteKey = crypto.generateX25519KeyPair()
        val cache = DhCache()

        val before = cache.getOrCompute(crypto, localKey.privateKey, remoteKey.publicKey)
        // Save a copy before clear() zeroizes the cached ByteArray in-place
        val beforeCopy = before.copyOf()
        cache.clear()
        assertEquals(0, cache.size)

        // clear() must have filled the cached ByteArray with zeros
        assertContentEquals(
            ByteArray(32),
            before,
            "clear() must zeroize the cached secret in-place",
        )

        // After clear, next getOrCompute is a cache miss — must recompute (not throw)
        val after = cache.getOrCompute(crypto, localKey.privateKey, remoteKey.publicKey)
        assertEquals(1, cache.size)

        // DH is deterministic: recomputed value must equal the original pre-clear secret
        assertContentEquals(beforeCopy, after)
    }
}
