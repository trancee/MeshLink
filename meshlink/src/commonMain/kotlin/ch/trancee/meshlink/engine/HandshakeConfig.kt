package ch.trancee.meshlink.engine

/**
 * Configuration for the Noise handshake rate-limiter and concurrency gate.
 *
 * @param maxConcurrentHandshakes Maximum number of in-flight handshakes allowed at once.
 * @param rateLimitWindowMs Rolling window (ms) over which per-peer handshake initiations are
 *   counted.
 */
data class HandshakeConfig(
    val maxConcurrentHandshakes: Int = 10,
    val rateLimitWindowMs: Long = 1_000L,
)
