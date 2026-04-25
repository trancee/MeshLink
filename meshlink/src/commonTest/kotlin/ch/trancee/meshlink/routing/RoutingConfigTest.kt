package ch.trancee.meshlink.routing

import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingConfigTest {

    @Test
    fun `RoutingConfig default values match spec`() {
        val config = RoutingConfig()
        assertEquals(5_000L, config.helloIntervalMs)
        assertEquals(10, config.fullDumpMultiplier)
        assertEquals(210_000L, config.routeExpiryMs)
        assertEquals(25_000, config.dedupCapacity)
        assertEquals(2_700_000L, config.dedupTtlMs)
        assertEquals(5_000L, config.routeDiscoveryTimeoutMs)
        assertEquals(1.0, config.defaultLinkCost)
    }

    @Test
    fun `RoutingConfig custom values override defaults`() {
        val config =
            RoutingConfig(
                helloIntervalMs = 1_000L,
                fullDumpMultiplier = 5,
                routeExpiryMs = 60_000L,
                dedupCapacity = 10_000,
                dedupTtlMs = 1_000_000L,
                routeDiscoveryTimeoutMs = 2_000L,
                defaultLinkCost = 2.0,
            )
        assertEquals(1_000L, config.helloIntervalMs)
        assertEquals(5, config.fullDumpMultiplier)
        assertEquals(60_000L, config.routeExpiryMs)
        assertEquals(10_000, config.dedupCapacity)
        assertEquals(1_000_000L, config.dedupTtlMs)
        assertEquals(2_000L, config.routeDiscoveryTimeoutMs)
        assertEquals(2.0, config.defaultLinkCost)
    }
}
