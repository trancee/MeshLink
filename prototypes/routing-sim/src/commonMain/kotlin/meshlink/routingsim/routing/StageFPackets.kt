package meshlink.routingsim.routing

/**
 * Stage F A1 catch-up summary (ADR 0014 decision 7), unicast part: held unicasts as {message ID, destination tag}
 * and tombstones as {message ID, k}. Idle control. [tombExpiry] is simulation metadata (the holder knows each
 * message's expiry from the header it holds or relayed); it is not on the wire.
 */
class CatchUpSummary(
    val ids: IntArray,
    val dstTags: LongArray,
    val tombIds: IntArray,
    val tombExpiry: LongArray,
) : Packet() {
    override val isControl get() = true
    override val isIdleControl get() = true

    override fun sizeBytes(s: RoutingSizes) =
        s.headerBytes + 2 + ids.size * (s.messageIdBytes + s.tagBytes) + tombIds.size * (s.messageIdBytes + TOMBSTONE_KEY_BYTES)

    companion object {
        const val TOMBSTONE_KEY_BYTES = 16
    }
}

/** Stage F A1: the peer pulls held unicasts addressed to its own tags. Idle control. */
class Claim(
    val ids: IntArray,
) : Packet() {
    override val isControl get() = true
    override val isIdleControl get() = true

    override fun sizeBytes(s: RoutingSizes) = s.headerBytes + 1 + ids.size * s.messageIdBytes
}

/**
 * Stage F A3 (ADR 0018) salted certificate lookup, flooded under route-request rules: 8 B salt, 8 B lookup tag,
 * 32 B ephemeral key, hop count. [target] (the NodeId the tag hides) and [requester] are simulation metadata.
 */
class LookupRequest(
    val salt: Long,
    val target: Int,
    val requester: Int,
    val hops: Int,
) : Packet() {
    override val isControl get() = true

    override fun sizeBytes(s: RoutingSizes) = s.headerBytes + 8 + 8 + 32 + s.hopFieldBytes
}

/** Stage F A3 lookup reply, sealed to the request's ephemeral key: salt (reverse-path key), certificate, 32 B + 16 B. */
class LookupReply(
    val salt: Long,
    val target: Int,
    val certBytes: Int,
    /** Simulation metadata (not on the wire): hops from the requester to the answering node, and the requester. */
    val answerHops: Int = 0,
    val requester: Int = -1,
) : Packet() {
    override val isControl get() = true

    override fun sizeBytes(s: RoutingSizes) = s.headerBytes + 8 + certBytes + 32 + 16
}
