package ch.trancee.meshlink.transport

/**
 * FNV-1a-based mesh hash computation and advertisement filtering.
 *
 * Computes a 16-bit mesh hash from an application ID string and matches incoming advertisement
 * payloads against a local hash. The zero value (0x0000) is reserved for universal-broadcast
 * advertisements that match any local hash.
 */
object MeshHashFilter {

    private const val FNV_OFFSET_BASIS = 2166136261u // 0x811c9dc5
    private const val FNV_PRIME = 16777619u // 0x01000193

    /**
     * Computes the 16-bit mesh hash for [appId] using FNV-1a 32-bit with XOR-fold.
     *
     * Algorithm:
     * 1. Initialize hash = FNV_OFFSET_BASIS (32-bit).
     * 2. For each UTF-8 byte b in [appId]: hash = (hash XOR b) * FNV_PRIME (mod 2^32).
     * 3. XOR-fold to 16 bits: (hash >>> 16) XOR (hash AND 0xFFFF).
     * 4. Substitute 0x0000 → 0x0001 to reserve the zero value for universal broadcast.
     *
     * @return A non-zero UShort in [0x0001, 0xFFFF].
     */
    fun computeMeshHash(appId: String): UShort {
        var hash = FNV_OFFSET_BASIS
        for (b in appId.encodeToByteArray()) {
            hash = hash xor b.toUByte().toUInt()
            hash = hash * FNV_PRIME
        }
        val result = ((hash shr 16) xor (hash and 0xFFFFu)).toUShort()
        if (result == 0.toUShort()) return 1.toUShort()
        return result
    }

    /**
     * Returns `true` if [payloadMeshHash] should be delivered to a peer with [localMeshHash].
     *
     * - `payloadMeshHash == 0x0000` is a universal advertisement and matches any local hash.
     * - Otherwise, an exact match is required.
     */
    fun matches(payloadMeshHash: UShort, localMeshHash: UShort): Boolean =
        payloadMeshHash == 0.toUShort() || payloadMeshHash == localMeshHash
}
