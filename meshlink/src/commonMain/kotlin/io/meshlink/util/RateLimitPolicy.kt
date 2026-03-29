package io.meshlink.util

import io.meshlink.config.MeshLinkConfig

sealed interface RateLimitResult {
    data object Allowed : RateLimitResult
    data class Limited(val scope: String, val key: String) : RateLimitResult
}

/**
 * Facade consolidating all rate limiters and the circuit breaker behind
 * a unified [RateLimitResult] sealed type. MeshLink delegates all rate
 * checks here and pattern-matches on [Allowed] vs [Limited] to decide
 * whether to proceed or emit diagnostics.
 */
class RateLimitPolicy(
    config: MeshLinkConfig,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    private val sendLimiter: RateLimiter? =
        if (config.rateLimitMaxSends > 0) RateLimiter(config.rateLimitMaxSends, config.rateLimitWindowMs, clock) else null

    private val circuitBreaker: CircuitBreaker? =
        if (config.circuitBreakerMaxFailures > 0) {
            CircuitBreaker(
                config.circuitBreakerMaxFailures,
                config.circuitBreakerWindowMs,
                config.circuitBreakerCooldownMs,
                clock,
            )
        } else {
            null
        }

    private val broadcastLimiter: RateLimiter? =
        if (config.broadcastRateLimitPerMinute > 0) RateLimiter(config.broadcastRateLimitPerMinute, 60_000L, clock) else null

    private val handshakeLimiter: RateLimiter? =
        if (config.handshakeRateLimitPerSec > 0) RateLimiter(config.handshakeRateLimitPerSec, 1_000L, clock) else null

    private val nackLimiter: RateLimiter? =
        if (config.nackRateLimitPerSec > 0) RateLimiter(config.nackRateLimitPerSec, 1_000L, clock) else null

    private val neighborAggregateLimiter: RateLimiter? =
        if (config.neighborAggregateLimitPerMin > 0) RateLimiter(config.neighborAggregateLimitPerMin, 60_000L, clock) else null

    private val senderNeighborLimiter: RateLimiter? =
        if (config.senderNeighborLimitPerMin > 0) RateLimiter(config.senderNeighborLimitPerMin, 60_000L, clock) else null

    // ── Per-scope checks ──────────────────────────────────────────

    fun checkSend(recipientHex: String): RateLimitResult =
        check(sendLimiter, "send", recipientHex)

    fun checkCircuitBreaker(): RateLimitResult {
        val cb = circuitBreaker ?: return RateLimitResult.Allowed
        return if (cb.allowAttempt()) RateLimitResult.Allowed
        else RateLimitResult.Limited("circuit_breaker", "transport")
    }

    fun checkBroadcast(): RateLimitResult =
        check(broadcastLimiter, "broadcast", "broadcast")

    fun checkHandshake(peerHex: String): RateLimitResult =
        check(handshakeLimiter, "handshake", peerHex)

    fun checkNack(peerHex: String): RateLimitResult =
        check(nackLimiter, "nack", peerHex)

    fun checkNeighborAggregate(peerHex: String): RateLimitResult =
        check(neighborAggregateLimiter, "neighbor_aggregate", peerHex)

    fun checkSenderNeighborRelay(originHex: String, neighborHex: String): RateLimitResult =
        check(senderNeighborLimiter, "sender_neighbor", "$originHex->$neighborHex")

    // ── Circuit breaker failure recording ─────────────────────────

    fun recordTransportFailure() {
        circuitBreaker?.recordFailure()
    }

    // ── Internal ──────────────────────────────────────────────────

    private fun check(limiter: RateLimiter?, scope: String, key: String): RateLimitResult {
        val l = limiter ?: return RateLimitResult.Allowed
        return if (l.tryAcquire(key)) RateLimitResult.Allowed
        else RateLimitResult.Limited(scope, key)
    }
}
