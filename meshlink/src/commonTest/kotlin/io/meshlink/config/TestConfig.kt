package io.meshlink.config

/**
 * Test-friendly config builder that disables gossip, keepalive, and delivery
 * deadline timers by default. Gossip/keepalive infinite loops cause
 * `advanceUntilIdle()` to hang in `runTest` blocks. Delivery deadlines are
 * disabled so tests that manually control time aren't surprised by timer fires.
 * Tests that specifically exercise these features can override the values.
 */
fun testMeshLinkConfig(block: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
    meshLinkConfig {
        gossipIntervalMillis = 0L
        keepaliveIntervalMillis = 0L
        deliveryTimeoutMillis = 0L
        compressionEnabled = false
        block()
    }
