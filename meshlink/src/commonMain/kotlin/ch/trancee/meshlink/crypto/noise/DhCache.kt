package ch.trancee.meshlink.crypto.noise

import ch.trancee.meshlink.crypto.CryptoProvider

/**
 * Cache for DH static-static shared secrets used in Noise K seal/open.
 *
 * `DH(s, rs)` is deterministic for a given sender/recipient pair and does not change until key
 * rotation. Caching it eliminates one of the two `scalarMult` operations per seal, reducing cost to
 * ~0.05–0.25ms with hardware-accelerated X25519.
 *
 * Call [clear] on key rotation to zeroize and evict all cached secrets.
 */
internal class DhCache {

    private class ByteArrayKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (other !is ByteArrayKey) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private val cache = HashMap<ByteArrayKey, ByteArray>()

    /** Returns the number of cached DH secrets. */
    val size: Int
        get() = cache.size

    /**
     * Returns the DH shared secret for [remoteStaticPublic], computing and caching it if absent.
     *
     * @param crypto Platform crypto provider for X25519 scalar multiplication.
     * @param localStaticPrivate The local static X25519 private key (32 bytes).
     * @param remoteStaticPublic The remote static X25519 public key (32 bytes).
     * @return The 32-byte DH shared secret.
     */
    fun getOrCompute(
        crypto: CryptoProvider,
        localStaticPrivate: ByteArray,
        remoteStaticPublic: ByteArray,
    ): ByteArray {
        val key = ByteArrayKey(remoteStaticPublic)
        val cached = cache[key]
        if (cached != null) return cached
        val computed = crypto.x25519SharedSecret(localStaticPrivate, remoteStaticPublic)
        cache[key] = computed
        return computed
    }

    /**
     * Zeroizes all cached secrets and clears the map.
     *
     * Iterates entries and fills each cached [ByteArray] with zeros before removing, preventing
     * residual key material from lingering in the heap.
     */
    fun clear() {
        for (entry in cache.values) {
            entry.fill(0)
        }
        cache.clear()
    }
}
