package ch.trancee.meshlink.routing

data class RoutingConfig(
    val helloIntervalMs: Long = 5_000L,
    val fullDumpMultiplier: Int = 10,
    val routeExpiryMs: Long = 210_000L,
    val dedupCapacity: Int = 25_000,
    val dedupTtlMs: Long = 2_700_000L,
    val routeDiscoveryTimeoutMs: Long = 5_000L,
    val defaultLinkCost: Double = 1.0,
)
