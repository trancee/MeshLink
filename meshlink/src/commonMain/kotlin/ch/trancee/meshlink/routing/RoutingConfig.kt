package ch.trancee.meshlink.routing

internal data class RoutingConfig(
    val helloIntervalMillis: Long = 5_000L,
    val fullDumpMultiplier: Int = 10,
    val routeExpiryMillis: Long = 210_000L,
    val dedupCapacity: Int = 25_000,
    val dedupTtlMillis: Long = 2_700_000L,
    val routeDiscoveryTimeoutMillis: Long = 5_000L,
    val defaultLinkCost: Double = 1.0,
    /**
     * Maximum jitter added to hello/full-dump timers (ms). Set >0 in production to desynchronize.
     */
    val timerJitterMaxMillis: Long = 0L,
)
