package io.meshlink.config

/**
 * Test-friendly config builder that disables gossip and keepalive loops by default.
 * These infinite loops cause `advanceUntilIdle()` to hang in `runTest` blocks.
 * Tests that specifically exercise gossip/keepalive can override these values.
 */
fun testMeshLinkConfig(block: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
    meshLinkConfig {
        gossipIntervalMillis = 0L
        keepaliveIntervalMillis = 0L
        block()
    }
