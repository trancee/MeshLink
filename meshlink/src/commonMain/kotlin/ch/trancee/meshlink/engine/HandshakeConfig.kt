package ch.trancee.meshlink.engine

/**
 * Configuration for the Noise handshake rate-limiter and concurrency gate.
 *
 * @param maxConcurrentHandshakes Maximum number of in-flight handshakes allowed at once.
 * @param rateLimitWindowMillis Rolling window (ms) over which per-peer handshake initiations are
 *   counted.
 */
internal data class HandshakeConfig(
    val maxConcurrentHandshakes: Int = 10,
    val rateLimitWindowMillis: Long = 1_000L,
)
