package io.meshlink.config

import io.meshlink.protocol.ProtocolVersion

data class MeshLinkConfig(
    val maxMessageSize: Int = 100_000,
    val bufferCapacity: Int = 1_048_576,
    val mtu: Int = 185,
    val rateLimitMaxSends: Int = 0,
    val rateLimitWindowMs: Long = 60_000L,
    val circuitBreakerMaxFailures: Int = 0,
    val circuitBreakerWindowMs: Long = 60_000L,
    val circuitBreakerCooldownMs: Long = 30_000L,
    val diagnosticBufferCapacity: Int = 256,
    val dedupCapacity: Int = 10_000,
    val protocolVersion: ProtocolVersion = ProtocolVersion(1, 0),
    val appId: String? = null,
    val inboundRateLimitPerSenderPerMinute: Int = 0,
    val gossipIntervalMs: Long = 0L,
    val pendingMessageTtlMs: Long = 0L,
    val pendingMessageCapacity: Int = 100,
    val broadcastRateLimitPerMinute: Int = 0,
    val relayQueueCapacity: Int = 100,
    val maxHops: UByte = UByte.MAX_VALUE,
    val ackWindowMin: Int = 2,
    val ackWindowMax: Int = 16,
    val powerModeThresholds: List<Int> = listOf(80, 30),
    val l2capEnabled: Boolean = true,
    val l2capRetryAttempts: Int = 3,
    val chunkInactivityTimeoutMs: Long = 30_000L,
    val bufferTtlMs: Long = 300_000L,
    val triggeredUpdateThreshold: Double = 0.3,
    val triggeredUpdateBatchMs: Long = 100L,
    val keepaliveIntervalMs: Long = 0L,
    val tombstoneWindowMs: Long = 120_000L,
    val handshakeRateLimitPerSec: Int = 1,
    val nackRateLimitPerSec: Int = 10,
    val neighborAggregateLimitPerMin: Int = 100,
    val senderNeighborLimitPerMin: Int = 20,
) {
    fun validate(): List<String> {
        val violations = mutableListOf<String>()
        if (mtu <= 21) violations.add("mtu must be > 21 (chunk header size)")
        if (maxMessageSize > bufferCapacity) violations.add("maxMessageSize ($maxMessageSize) exceeds bufferCapacity ($bufferCapacity)")
        if (maxMessageSize <= 0) violations.add("maxMessageSize must be positive")
        if (bufferCapacity <= 0) violations.add("bufferCapacity must be positive")
        if (mtu > maxMessageSize) violations.add("mtu ($mtu) exceeds maxMessageSize ($maxMessageSize)")
        if (diagnosticBufferCapacity < 0) violations.add("diagnosticBufferCapacity must be non-negative")
        if (dedupCapacity <= 0) violations.add("dedupCapacity must be positive")
        if (rateLimitMaxSends > 0 && rateLimitWindowMs <= 0) violations.add("rateLimitWindowMs must be positive when rate limiting is enabled")
        if (circuitBreakerMaxFailures > 0 && circuitBreakerCooldownMs <= 0) violations.add("circuitBreakerCooldownMs must be positive when circuit breaker is enabled")
        // Cross-field validation rules from design doc §14
        if (ackWindowMax < ackWindowMin) violations.add("ackWindowMax ($ackWindowMax) must be >= ackWindowMin ($ackWindowMin)")
        if (powerModeThresholds.size >= 2 && powerModeThresholds[0] <= powerModeThresholds[1]) {
            violations.add("powerModeThresholds must be strictly descending: [${powerModeThresholds.joinToString()}]")
        }
        if (l2capEnabled && l2capRetryAttempts < 0) violations.add("l2capRetryAttempts ($l2capRetryAttempts) must be >= 0 when l2capEnabled is true")
        if (chunkInactivityTimeoutMs >= bufferTtlMs) violations.add("chunkInactivityTimeoutMs ($chunkInactivityTimeoutMs) must be < bufferTtlMs ($bufferTtlMs)")
        return violations
    }

    companion object {
        fun chatOptimized(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(maxMessageSize = 10_000, bufferCapacity = 524_288)
                .apply(overrides).build()

        fun fileTransferOptimized(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(maxMessageSize = 100_000, bufferCapacity = 2_097_152)
                .apply(overrides).build()

        fun powerOptimized(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(maxMessageSize = 10_000, bufferCapacity = 262_144)
                .apply(overrides).build()
    }
}

fun meshLinkConfig(block: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
    MeshLinkConfigBuilder().apply(block).build()

class MeshLinkConfigBuilder(
    var maxMessageSize: Int = 100_000,
    var bufferCapacity: Int = 1_048_576,
    var mtu: Int = 185,
    var rateLimitMaxSends: Int = 0,
    var rateLimitWindowMs: Long = 60_000L,
    var circuitBreakerMaxFailures: Int = 0,
    var circuitBreakerWindowMs: Long = 60_000L,
    var circuitBreakerCooldownMs: Long = 30_000L,
    var diagnosticBufferCapacity: Int = 256,
    var dedupCapacity: Int = 10_000,
    var protocolVersion: ProtocolVersion = ProtocolVersion(1, 0),
    var appId: String? = null,
    var inboundRateLimitPerSenderPerMinute: Int = 0,
    var gossipIntervalMs: Long = 0L,
    var pendingMessageTtlMs: Long = 0L,
    var pendingMessageCapacity: Int = 100,
    var broadcastRateLimitPerMinute: Int = 0,
    var relayQueueCapacity: Int = 100,
    var maxHops: UByte = UByte.MAX_VALUE,
    var ackWindowMin: Int = 2,
    var ackWindowMax: Int = 16,
    var powerModeThresholds: List<Int> = listOf(80, 30),
    var l2capEnabled: Boolean = true,
    var l2capRetryAttempts: Int = 3,
    var chunkInactivityTimeoutMs: Long = 30_000L,
    var bufferTtlMs: Long = 300_000L,
    var triggeredUpdateThreshold: Double = 0.3,
    var triggeredUpdateBatchMs: Long = 100L,
    var keepaliveIntervalMs: Long = 0L,
    var tombstoneWindowMs: Long = 120_000L,
    var handshakeRateLimitPerSec: Int = 1,
    var nackRateLimitPerSec: Int = 10,
    var neighborAggregateLimitPerMin: Int = 100,
    var senderNeighborLimitPerMin: Int = 20,
) {

    fun build(): MeshLinkConfig = MeshLinkConfig(
        maxMessageSize = maxMessageSize,
        bufferCapacity = bufferCapacity,
        mtu = mtu,
        rateLimitMaxSends = rateLimitMaxSends,
        rateLimitWindowMs = rateLimitWindowMs,
        circuitBreakerMaxFailures = circuitBreakerMaxFailures,
        circuitBreakerWindowMs = circuitBreakerWindowMs,
        circuitBreakerCooldownMs = circuitBreakerCooldownMs,
        diagnosticBufferCapacity = diagnosticBufferCapacity,
        dedupCapacity = dedupCapacity,
        protocolVersion = protocolVersion,
        appId = appId,
        inboundRateLimitPerSenderPerMinute = inboundRateLimitPerSenderPerMinute,
        gossipIntervalMs = gossipIntervalMs,
        pendingMessageTtlMs = pendingMessageTtlMs,
        pendingMessageCapacity = pendingMessageCapacity,
        broadcastRateLimitPerMinute = broadcastRateLimitPerMinute,
        relayQueueCapacity = relayQueueCapacity,
        maxHops = maxHops,
        ackWindowMin = ackWindowMin,
        ackWindowMax = ackWindowMax,
        powerModeThresholds = powerModeThresholds,
        l2capEnabled = l2capEnabled,
        l2capRetryAttempts = l2capRetryAttempts,
        chunkInactivityTimeoutMs = chunkInactivityTimeoutMs,
        bufferTtlMs = bufferTtlMs,
        triggeredUpdateThreshold = triggeredUpdateThreshold,
        triggeredUpdateBatchMs = triggeredUpdateBatchMs,
        keepaliveIntervalMs = keepaliveIntervalMs,
        tombstoneWindowMs = tombstoneWindowMs,
        handshakeRateLimitPerSec = handshakeRateLimitPerSec,
        nackRateLimitPerSec = nackRateLimitPerSec,
        neighborAggregateLimitPerMin = neighborAggregateLimitPerMin,
        senderNeighborLimitPerMin = senderNeighborLimitPerMin,
    )
}
