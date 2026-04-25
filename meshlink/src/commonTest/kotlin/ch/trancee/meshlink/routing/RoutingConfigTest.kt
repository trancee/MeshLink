package ch.trancee.meshlink.routing

import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingConfigTest {

    @Test
    fun `RoutingConfig default values match spec`() {
        val config = RoutingConfig()
        assertEquals(5_000L, config.helloIntervalMillis)
        assertEquals(10, config.fullDumpMultiplier)
        assertEquals(210_000L, config.routeExpiryMillis)
        assertEquals(25_000, config.dedupCapacity)
        assertEquals(2_700_000L, config.dedupTtlMillis)
        assertEquals(5_000L, config.routeDiscoveryTimeoutMillis)
        assertEquals(1.0, config.defaultLinkCost)
    }

    @Test
    fun `RoutingConfig custom values override defaults`() {
        val config =
            RoutingConfig(
                helloIntervalMillis = 1_000L,
                fullDumpMultiplier = 5,
                routeExpiryMillis = 60_000L,
                dedupCapacity = 10_000,
                dedupTtlMillis = 1_000_000L,
                routeDiscoveryTimeoutMillis = 2_000L,
                defaultLinkCost = 2.0,
            )
        assertEquals(1_000L, config.helloIntervalMillis)
        assertEquals(5, config.fullDumpMultiplier)
        assertEquals(60_000L, config.routeExpiryMillis)
        assertEquals(10_000, config.dedupCapacity)
        assertEquals(1_000_000L, config.dedupTtlMillis)
        assertEquals(2_000L, config.routeDiscoveryTimeoutMillis)
        assertEquals(2.0, config.defaultLinkCost)
    }
}
