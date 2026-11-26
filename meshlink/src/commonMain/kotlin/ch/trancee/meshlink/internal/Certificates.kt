package ch.trancee.meshlink.internal

/**
 * The certificates of other nodes this node holds (ADR 0018 decision 7, spec "Certificate lookup"):
 * at most [CAPACITY], the least recently used evicted first, one per NodeId with the latest
 * `not_after`, each dropped once the wall clock reaches its `not_after`. The caller validates each
 * certificate it puts. Runs on the node's serial dispatcher.
 */
internal class Certificates {
    private val byNode = LinkedHashMap<String, Held>()

    /** Holds [certificate] of [nodeId] at Unix second [nowSeconds], unless one held for it lasts longer. */
    fun put(
        nodeId: ByteArray,
        certificate: IdentityCertificate,
        nowSeconds: Long,
    ) {
        prune(nowSeconds)
        if (nowSeconds >= certificate.notAfter.toLong()) return
        val key = nodeId.toHexString()
        val held = byNode.remove(key)
        val later = held != null && held.certificate.notAfter > certificate.notAfter
        byNode[key] = if (later) held else Held(nodeId, certificate)
        if (byNode.size > CAPACITY) byNode -= byNode.keys.first()
    }

    /** The certificate held for [nodeId] at Unix second [nowSeconds], or `null`; using it makes it the most recent. */
    fun get(
        nodeId: ByteArray,
        nowSeconds: Long,
    ): IdentityCertificate? = find(nowSeconds) { it.contentEquals(nodeId) }

    /**
     * The first certificate held at Unix second [nowSeconds] whose NodeId [matches], or `null`;
     * using it makes it the most recent.
     */
    fun find(
        nowSeconds: Long,
        matches: (nodeId: ByteArray) -> Boolean,
    ): IdentityCertificate? {
        prune(nowSeconds)
        val (key, held) = byNode.entries.firstOrNull { matches(it.value.nodeId) } ?: return null
        byNode.remove(key)
        byNode[key] = held
        return held.certificate
    }

    /** How many certificates are held at Unix second [nowSeconds]. */
    fun count(nowSeconds: Long): Int {
        prune(nowSeconds)
        return byNode.size
    }

    private fun prune(nowSeconds: Long) {
        byNode.values.removeAll { nowSeconds >= it.certificate.notAfter.toLong() }
    }

    private class Held(
        val nodeId: ByteArray,
        val certificate: IdentityCertificate,
    )

    companion object {
        /** `certificate-cache`. */
        const val CAPACITY: Int = 64
    }
}
