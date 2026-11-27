package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId

/**
 * PROTOTYPE (ticket 74), never merged: per-neighbor admission of relayed flood frames. Each relay keeps
 * token buckets; a relayed frame over budget is dropped (not relayed). Buckets: per (relay, incoming link,
 * type) for flooded unicast, route requests and lookups; broadcasts per [broadcastKey].
 */
public object RateProto {
    /** none | link | origin | originlink. */
    public var broadcastKey: String = "none"
    public var linkRate: Double = 2.0
    public var linkBurst: Double = 10.0
    public var originRate: Double = 1.0
    public var originBurst: Double = 5.0
    public var aggregateRate: Double = 4.0
    public var aggregateBurst: Double = 20.0
    public var unicastRate: Double = Double.POSITIVE_INFINITY
    public var unicastBurst: Double = 40.0
    public var requestRate: Double = Double.POSITIVE_INFINITY
    public var requestBurst: Double = 20.0

    /** The attacker's broadcasts (unsecured payload byte 0 = 2) look like fresh origins when [rotate]. */
    public var rotate: Boolean = false
    public var nowMillis: () -> Long = { 0L }
    public val drops: MutableMap<String, Long> = mutableMapOf()
    private val buckets = mutableMapOf<List<Any>, DoubleArray>()
    private var fresh = 0L

    public fun reset() {
        buckets.clear()
        drops.clear()
        fresh = 0
    }

    public fun nodeId(bytes: ByteArray): NodeId = NodeId(bytes)

    private fun take(
        key: List<Any>,
        rate: Double,
        burst: Double,
    ): Boolean {
        if (rate.isInfinite()) return true
        val now = nowMillis().toDouble()
        val b = buckets.getOrPut(key) { doubleArrayOf(burst, now) }
        b[0] = minOf(burst, b[0] + (now - b[1]) * rate / 1000.0)
        b[1] = now
        return if (b[0] >= 1.0) {
            b[0] -= 1.0
            true
        } else {
            false
        }
    }

    private fun count(
        kind: String,
        ok: Boolean,
    ): Boolean {
        if (!ok) drops[kind] = (drops[kind] ?: 0L) + 1
        return ok
    }

    internal fun broadcast(
        relay: Any,
        peer: NodeId,
        body: ByteArray,
    ): Boolean {
        val origin = body.copyOfRange(ORIGIN_AT, ORIGIN_AT + ORIGIN_BYTES).toList()
        val attacker = body.size > PAYLOAD_AT && body[PAYLOAD_AT] == ATTACK
        val key: Any = if (attacker && rotate) "fresh${fresh++}" else origin
        val ok =
            when (broadcastKey) {
                "link" -> take(listOf(relay, peer, "bc"), linkRate, linkBurst)
                "origin" -> take(listOf(relay, key), originRate, originBurst)
                "originlink" ->
                    take(listOf(relay, peer, "agg"), aggregateRate, aggregateBurst) &&
                        take(listOf(relay, key), originRate, originBurst)
                else -> true
            }
        return count(if (attacker) "bc-attack" else "bc-honest", ok)
    }

    internal fun unicast(
        relay: Any,
        peer: NodeId,
    ): Boolean = count("uni", take(listOf(relay, peer, "uni"), unicastRate, unicastBurst))

    internal fun request(
        relay: Any,
        peer: NodeId,
        type: String,
    ): Boolean = count(type, take(listOf(relay, peer, type), requestRate, requestBurst))

    private const val ORIGIN_AT = 1
    private const val PAYLOAD_AT = 37
    private const val ATTACK: Byte = 2
    private const val ORIGIN_BYTES = 8
}
