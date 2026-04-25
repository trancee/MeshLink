package ch.trancee.meshlink.messaging

/**
 * All tuning parameters for the messaging and delivery subsystem.
 *
 * [appIdHash] is required and scopes broadcast messages to a specific application. Custom
 * [equals]/[hashCode] use [ByteArray.contentEquals]/[ByteArray.contentHashCode] for [appIdHash] and
 * structural comparison for all other fields.
 */
data class MessagingConfig(
    /** TTL for HIGH-priority messages in the store-and-forward buffer (ms). Default: 45 min. */
    val highPriorityTtlMs: Long = 2_700_000,
    /** TTL for NORMAL-priority messages in the store-and-forward buffer (ms). Default: 15 min. */
    val normalPriorityTtlMs: Long = 900_000,
    /** TTL for LOW-priority messages in the store-and-forward buffer (ms). Default: 5 min. */
    val lowPriorityTtlMs: Long = 300_000,
    /** Maximum number of messages held in the store-and-forward buffer. */
    val maxBufferedMessages: Int = 100,
    /** Initial hop count for flood-fill broadcasts (decremented per relay). */
    val broadcastTtl: UByte = 2u,
    /** Maximum payload size for a broadcast message in bytes. */
    val maxBroadcastSize: Int = 10_000,
    /** Maximum payload size for any message in bytes. */
    val maxMessageSize: Int = 102_400,
    /** Application-ID hash used to scope broadcasts to this application. Required — no default. */
    val appIdHash: ByteArray,
    /** If true, the pipeline requires a valid signature on every broadcast. */
    val requireBroadcastSignatures: Boolean = true,
    /** If true, unsigned broadcasts are accepted even when [requireBroadcastSignatures] is set. */
    val allowUnsignedBroadcasts: Boolean = false,
    // ── Rate limit parameters ────────────────────────────────────────────────
    /** Max outbound unicast messages per [outboundUnicastWindowMs]. */
    val outboundUnicastLimit: Int = 60,
    val outboundUnicastWindowMs: Long = 60_000,
    /** Max outbound broadcasts per [broadcastWindowMs]. */
    val broadcastLimit: Int = 10,
    val broadcastWindowMs: Long = 60_000,
    /** Max relays per (sender, neighbor) pair per [relayPerSenderPerNeighborWindowMs]. */
    val relayPerSenderPerNeighborLimit: Int = 20,
    val relayPerSenderPerNeighborWindowMs: Long = 60_000,
    /** Max aggregate relays to a single neighbor per [perNeighborAggregateWindowMs]. */
    val perNeighborAggregateLimit: Int = 100,
    val perNeighborAggregateWindowMs: Long = 60_000,
    /** Max inbound messages from a single sender per [perSenderInboundWindowMs]. */
    val perSenderInboundLimit: Int = 30,
    val perSenderInboundWindowMs: Long = 60_000,
    /** Max handshake initiations from a single peer per [handshakeWindowMs]. */
    val handshakeLimit: Int = 1,
    val handshakeWindowMs: Long = 1_000,
    /** Max NACKs from a single peer per [nackWindowMs]. */
    val nackLimit: Int = 10,
    val nackWindowMs: Long = 1_000,
    val circuitBreaker: CircuitBreakerConfig = CircuitBreakerConfig(),
) {
    /** Circuit-breaker parameters for the outbound send path. */
    data class CircuitBreakerConfig(
        /** Rolling window over which failures are counted (ms). */
        val windowMs: Long = 60_000,
        /** Number of failures within [windowMs] that trips the circuit breaker. */
        val maxFailures: Int = 3,
        /** How long the circuit stays open before transitioning to half-open (ms). */
        val cooldownMs: Long = 30_000,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessagingConfig) return false
        return highPriorityTtlMs == other.highPriorityTtlMs &&
            normalPriorityTtlMs == other.normalPriorityTtlMs &&
            lowPriorityTtlMs == other.lowPriorityTtlMs &&
            maxBufferedMessages == other.maxBufferedMessages &&
            broadcastTtl == other.broadcastTtl &&
            maxBroadcastSize == other.maxBroadcastSize &&
            maxMessageSize == other.maxMessageSize &&
            appIdHash.contentEquals(other.appIdHash) &&
            requireBroadcastSignatures == other.requireBroadcastSignatures &&
            allowUnsignedBroadcasts == other.allowUnsignedBroadcasts &&
            outboundUnicastLimit == other.outboundUnicastLimit &&
            outboundUnicastWindowMs == other.outboundUnicastWindowMs &&
            broadcastLimit == other.broadcastLimit &&
            broadcastWindowMs == other.broadcastWindowMs &&
            relayPerSenderPerNeighborLimit == other.relayPerSenderPerNeighborLimit &&
            relayPerSenderPerNeighborWindowMs == other.relayPerSenderPerNeighborWindowMs &&
            perNeighborAggregateLimit == other.perNeighborAggregateLimit &&
            perNeighborAggregateWindowMs == other.perNeighborAggregateWindowMs &&
            perSenderInboundLimit == other.perSenderInboundLimit &&
            perSenderInboundWindowMs == other.perSenderInboundWindowMs &&
            handshakeLimit == other.handshakeLimit &&
            handshakeWindowMs == other.handshakeWindowMs &&
            nackLimit == other.nackLimit &&
            nackWindowMs == other.nackWindowMs &&
            circuitBreaker == other.circuitBreaker
    }

    override fun hashCode(): Int {
        var h = appIdHash.contentHashCode()
        h = 31 * h + highPriorityTtlMs.hashCode()
        h = 31 * h + normalPriorityTtlMs.hashCode()
        h = 31 * h + lowPriorityTtlMs.hashCode()
        h = 31 * h + maxBufferedMessages.hashCode()
        h = 31 * h + broadcastTtl.hashCode()
        h = 31 * h + maxBroadcastSize.hashCode()
        h = 31 * h + maxMessageSize.hashCode()
        h = 31 * h + requireBroadcastSignatures.hashCode()
        h = 31 * h + allowUnsignedBroadcasts.hashCode()
        h = 31 * h + outboundUnicastLimit.hashCode()
        h = 31 * h + outboundUnicastWindowMs.hashCode()
        h = 31 * h + broadcastLimit.hashCode()
        h = 31 * h + broadcastWindowMs.hashCode()
        h = 31 * h + relayPerSenderPerNeighborLimit.hashCode()
        h = 31 * h + relayPerSenderPerNeighborWindowMs.hashCode()
        h = 31 * h + perNeighborAggregateLimit.hashCode()
        h = 31 * h + perNeighborAggregateWindowMs.hashCode()
        h = 31 * h + perSenderInboundLimit.hashCode()
        h = 31 * h + perSenderInboundWindowMs.hashCode()
        h = 31 * h + handshakeLimit.hashCode()
        h = 31 * h + handshakeWindowMs.hashCode()
        h = 31 * h + nackLimit.hashCode()
        h = 31 * h + nackWindowMs.hashCode()
        h = 31 * h + circuitBreaker.hashCode()
        return h
    }
}
