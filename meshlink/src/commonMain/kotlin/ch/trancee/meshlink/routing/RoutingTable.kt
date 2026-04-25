package ch.trancee.meshlink.routing

val METRIC_RETRACTION: UShort = 0xFFFFu.toUShort()

/**
 * Returns true when sequence number [a] is strictly newer than [b] using RFC 8966 §2.1 modular
 * arithmetic. Handles 16-bit wraparound: seqno 0 is correctly identified as newer than 65535.
 */
internal fun isSeqNoNewer(a: UShort, b: UShort): Boolean =
    a != b && ((a.toInt() - b.toInt()) and 0xFFFF) < 0x8000

/**
 * FNV-1a 32-bit hash. Offset basis = 2166136261, prime = 16777619. Bytes are treated as unsigned
 * 8-bit values via masking.
 */
internal fun fnv1a32(data: ByteArray): UInt {
    var hash = 2166136261u
    for (byte in data) {
        hash = hash xor (byte.toInt() and 0xFF).toUInt()
        hash *= 16777619u
    }
    return hash
}

/**
 * A single best-path route entry. Four ByteArray fields use [contentEquals]/[contentHashCode] for
 * value-based equality — never use the data class default (identity-based for arrays).
 */
internal class RouteEntry(
    val destination: ByteArray, // 12 bytes: peer ID
    val nextHop: ByteArray, // 12 bytes: next-hop peer ID
    val metric: Double,
    val seqNo: UShort,
    val feasibilityDistance:
        Double, // FD at time of install (authoritative copy lives in RoutingEngine)
    val expiresAt: Long, // epoch-ms; compared against getCurrentTimeMs()
    val ed25519PublicKey: ByteArray, // 32 bytes
    val x25519PublicKey: ByteArray, // 32 bytes
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RouteEntry) return false
        return destination.contentEquals(other.destination) &&
            nextHop.contentEquals(other.nextHop) &&
            metric == other.metric &&
            seqNo == other.seqNo &&
            feasibilityDistance == other.feasibilityDistance &&
            expiresAt == other.expiresAt &&
            ed25519PublicKey.contentEquals(other.ed25519PublicKey) &&
            x25519PublicKey.contentEquals(other.x25519PublicKey)
    }

    override fun hashCode(): Int {
        var result = destination.contentHashCode()
        result = 31 * result + nextHop.contentHashCode()
        result = 31 * result + metric.hashCode()
        result = 31 * result + seqNo.hashCode()
        result = 31 * result + feasibilityDistance.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + ed25519PublicKey.contentHashCode()
        result = 31 * result + x25519PublicKey.contentHashCode()
        return result
    }
}

/**
 * Single-best-path route store keyed by destination (as `List<Byte>` for Kover-safe content
 * equality — see MEM047). Digest is maintained incrementally via XOR-fold of per-entry FNV-1a
 * hashes so callers can detect lost updates in O(1).
 *
 * All expiry is lazy: stale entries are evicted on first access after their [RouteEntry.expiresAt]
 * timestamp passes.
 */
internal class RoutingTable(private val getCurrentTimeMs: () -> Long) {

    private val routes: HashMap<List<Byte>, RouteEntry> = HashMap()
    private var digest: UInt = 0u

    /** Compute the XOR component for one table entry (dest + seqNo-LE). */
    private fun entryHash(destination: ByteArray, seqNo: UShort): UInt {
        val seqBytes =
            byteArrayOf(
                (seqNo.toInt() and 0xFF).toByte(),
                ((seqNo.toInt() shr 8) and 0xFF).toByte(),
            )
        return fnv1a32(destination + seqBytes)
    }

    /**
     * Install [entry] into the table. If a route for [entry.destination] already exists it is
     * replaced and its hash is XORed out of the digest before the new hash is XORed in.
     *
     * @return the previous entry for this destination, or null if none.
     */
    fun install(entry: RouteEntry): RouteEntry? {
        val key = entry.destination.asList()
        val existing = routes[key]
        if (existing != null) {
            digest = digest xor entryHash(existing.destination, existing.seqNo)
        }
        routes[key] = entry
        digest = digest xor entryHash(entry.destination, entry.seqNo)
        return existing
    }

    /**
     * Remove the route for [destination] and XOR its hash out of the digest.
     *
     * @return the removed entry, or null if no route was present.
     */
    fun retract(destination: ByteArray): RouteEntry? {
        val key = destination.asList()
        val existing = routes.remove(key) ?: return null
        digest = digest xor entryHash(existing.destination, existing.seqNo)
        return existing
    }

    /**
     * Look up the next-hop for [destination], applying lazy expiry.
     *
     * @return the next-hop bytes, or null if unknown or expired.
     */
    fun lookupNextHop(destination: ByteArray): ByteArray? {
        val key = destination.asList()
        val entry = routes[key] ?: return null
        if (getCurrentTimeMs() > entry.expiresAt) {
            routes.remove(key)
            digest = digest xor entryHash(entry.destination, entry.seqNo)
            return null
        }
        return entry.nextHop
    }

    /**
     * Look up the full [RouteEntry] for [destination], applying lazy expiry.
     *
     * @return the full entry, or null if unknown or expired.
     */
    fun lookupRoute(destination: ByteArray): RouteEntry? {
        val key = destination.asList()
        val entry = routes[key] ?: return null
        if (getCurrentTimeMs() > entry.expiresAt) {
            routes.remove(key)
            digest = digest xor entryHash(entry.destination, entry.seqNo)
            return null
        }
        return entry
    }

    /** XOR-fold route digest for lost-update detection. */
    fun routeDigest(): UInt = digest

    /** Return all non-expired routes, evicting stale entries lazily as a side-effect. */
    fun allRoutes(): List<RouteEntry> {
        val now = getCurrentTimeMs()
        val keysToRemove = mutableListOf<List<Byte>>()
        val entriesToRemove = mutableListOf<RouteEntry>()
        for ((key, entry) in routes) {
            if (now > entry.expiresAt) {
                keysToRemove.add(key)
                entriesToRemove.add(entry)
            }
        }
        for (i in keysToRemove.indices) {
            routes.remove(keysToRemove[i])
            digest = digest xor entryHash(entriesToRemove[i].destination, entriesToRemove[i].seqNo)
        }
        return routes.values.toList()
    }

    /** Current number of stored routes (may include not-yet-lazily-expired entries). */
    fun routeCount(): Int = routes.size
}
