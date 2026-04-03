package io.meshlink.config

import io.meshlink.protocol.ProtocolVersion

/**
 * Internal / advanced protocol parameters that rarely need changing by
 * consuming apps. Library developers and tests may tune these via
 * `MeshLinkConfig.advanced`.
 */
data class MeshLinkInternalConfig(
    val rateLimitMaxSends: Int = 60,
    val rateLimitWindowMillis: Long = 60_000L,
    val circuitBreakerMaxFailures: Int = 0,
    val circuitBreakerWindowMillis: Long = 60_000L,
    val circuitBreakerCooldownMillis: Long = 30_000L,
    val diagnosticBufferCapacity: Int = 256,
    val dedupCapacity: Int = 100_000,
    val protocolVersion: ProtocolVersion = ProtocolVersion(1, 0),
    val inboundRateLimitPerSenderPerMin: Int = 30,
    val pendingMessageTtlMillis: Long = 0L,
    val pendingMessageCapacity: Int = 100,
    val broadcastRateLimitPerMin: Int = 10,
    val relayQueueCapacity: Int = 100,
    val evictionGracePeriodMillis: Long = 30_000L,
    val l2capBackpressureWindowMillis: Long = 7_000L,
    val ackWindowMin: Int = 2,
    val ackWindowMax: Int = 16,
    val l2capRetryAttempts: Int = 3,
    val chunkInactivityTimeoutMillis: Long = 30_000L,
    val bufferTtlMillis: Long = 900_000L,
    val deliveryTimeoutMillis: Long = 30_000L,
    val keepaliveIntervalMillis: Long = 0L,
    val routeCacheTtlMillis: Long = 300_000L,
    val routeDiscoveryTimeoutMillis: Long = 5_000L,
    val tombstoneWindowMillis: Long = 120_000L,
    val handshakeRateLimitPerSec: Int = 1,
    val nackRateLimitPerSec: Int = 10,
    val neighborAggregateLimitPerMin: Int = 100,
    val senderNeighborLimitPerMin: Int = 20,
    val visitedListEnabled: Boolean = true,
    val paddingBlockSize: Int = 0,
    val maxConcurrentInboundSessions: Int = 100,
) {
    fun validate(): List<String> {
        val violations = mutableListOf<String>()
        if (diagnosticBufferCapacity < 0) violations.add("diagnosticBufferCapacity must be non-negative")
        if (dedupCapacity <= 0) violations.add("dedupCapacity must be positive")
        if (rateLimitMaxSends > 0 && rateLimitWindowMillis <= 0) {
            violations.add("rateLimitWindowMillis must be positive when rate limiting is enabled")
        }
        if (circuitBreakerMaxFailures > 0 && circuitBreakerCooldownMillis <= 0) {
            violations.add("circuitBreakerCooldownMillis must be positive when circuit breaker is enabled")
        }
        if (evictionGracePeriodMillis < 5_000L || evictionGracePeriodMillis > 60_000L) {
            violations.add("evictionGracePeriodMillis ($evictionGracePeriodMillis) must be in range 5000..60000")
        }
        if (l2capBackpressureWindowMillis < 3_000L || l2capBackpressureWindowMillis > 15_000L) {
            violations.add(
                "l2capBackpressureWindowMillis ($l2capBackpressureWindowMillis) must be in range 3000..15000"
            )
        }
        if (ackWindowMax < ackWindowMin) {
            violations.add("ackWindowMax ($ackWindowMax) must be >= ackWindowMin ($ackWindowMin)")
        }
        if (maxConcurrentInboundSessions <= 0) violations.add("maxConcurrentInboundSessions must be positive")
        if (deliveryTimeoutMillis < 0) violations.add("deliveryTimeoutMillis must be non-negative")
        if (deliveryTimeoutMillis > 0 && bufferTtlMillis > 0 && deliveryTimeoutMillis > bufferTtlMillis) {
            violations.add(
                "deliveryTimeoutMillis ($deliveryTimeoutMillis) must be <= bufferTtlMillis ($bufferTtlMillis)"
            )
        }
        if (bufferTtlMillis > 0 && chunkInactivityTimeoutMillis >= bufferTtlMillis) {
            violations.add(
                "chunkInactivityTimeoutMillis ($chunkInactivityTimeoutMillis) must be < bufferTtlMillis ($bufferTtlMillis)"
            )
        }
        return violations
    }
}

class MeshLinkInternalConfigBuilder(
    var rateLimitMaxSends: Int = 60,
    var rateLimitWindowMillis: Long = 60_000L,
    var circuitBreakerMaxFailures: Int = 0,
    var circuitBreakerWindowMillis: Long = 60_000L,
    var circuitBreakerCooldownMillis: Long = 30_000L,
    var diagnosticBufferCapacity: Int = 256,
    var dedupCapacity: Int = 100_000,
    var protocolVersion: ProtocolVersion = ProtocolVersion(1, 0),
    var inboundRateLimitPerSenderPerMin: Int = 30,
    var pendingMessageTtlMillis: Long = 0L,
    var pendingMessageCapacity: Int = 100,
    var broadcastRateLimitPerMin: Int = 10,
    var relayQueueCapacity: Int = 100,
    var evictionGracePeriodMillis: Long = 30_000L,
    var l2capBackpressureWindowMillis: Long = 7_000L,
    var ackWindowMin: Int = 2,
    var ackWindowMax: Int = 16,
    var l2capRetryAttempts: Int = 3,
    var chunkInactivityTimeoutMillis: Long = 30_000L,
    var bufferTtlMillis: Long = 900_000L,
    var deliveryTimeoutMillis: Long = 30_000L,
    var keepaliveIntervalMillis: Long = 0L,
    var routeCacheTtlMillis: Long = 300_000L,
    var routeDiscoveryTimeoutMillis: Long = 5_000L,
    var tombstoneWindowMillis: Long = 120_000L,
    var handshakeRateLimitPerSec: Int = 1,
    var nackRateLimitPerSec: Int = 10,
    var neighborAggregateLimitPerMin: Int = 100,
    var senderNeighborLimitPerMin: Int = 20,
    var visitedListEnabled: Boolean = true,
    var paddingBlockSize: Int = 0,
    var maxConcurrentInboundSessions: Int = 100,
) {
    fun build(): MeshLinkInternalConfig = MeshLinkInternalConfig(
        rateLimitMaxSends = rateLimitMaxSends,
        rateLimitWindowMillis = rateLimitWindowMillis,
        circuitBreakerMaxFailures = circuitBreakerMaxFailures,
        circuitBreakerWindowMillis = circuitBreakerWindowMillis,
        circuitBreakerCooldownMillis = circuitBreakerCooldownMillis,
        diagnosticBufferCapacity = diagnosticBufferCapacity,
        dedupCapacity = dedupCapacity,
        protocolVersion = protocolVersion,
        inboundRateLimitPerSenderPerMin = inboundRateLimitPerSenderPerMin,
        pendingMessageTtlMillis = pendingMessageTtlMillis,
        pendingMessageCapacity = pendingMessageCapacity,
        broadcastRateLimitPerMin = broadcastRateLimitPerMin,
        relayQueueCapacity = relayQueueCapacity,
        evictionGracePeriodMillis = evictionGracePeriodMillis,
        l2capBackpressureWindowMillis = l2capBackpressureWindowMillis,
        ackWindowMin = ackWindowMin,
        ackWindowMax = ackWindowMax,
        l2capRetryAttempts = l2capRetryAttempts,
        chunkInactivityTimeoutMillis = chunkInactivityTimeoutMillis,
        bufferTtlMillis = bufferTtlMillis,
        deliveryTimeoutMillis = deliveryTimeoutMillis,
        keepaliveIntervalMillis = keepaliveIntervalMillis,
        routeCacheTtlMillis = routeCacheTtlMillis,
        routeDiscoveryTimeoutMillis = routeDiscoveryTimeoutMillis,
        tombstoneWindowMillis = tombstoneWindowMillis,
        handshakeRateLimitPerSec = handshakeRateLimitPerSec,
        nackRateLimitPerSec = nackRateLimitPerSec,
        neighborAggregateLimitPerMin = neighborAggregateLimitPerMin,
        senderNeighborLimitPerMin = senderNeighborLimitPerMin,
        visitedListEnabled = visitedListEnabled,
        paddingBlockSize = paddingBlockSize,
        maxConcurrentInboundSessions = maxConcurrentInboundSessions,
    )
}
