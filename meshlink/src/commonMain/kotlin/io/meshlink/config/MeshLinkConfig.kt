package io.meshlink.config

import io.meshlink.protocol.ProtocolVersion

data class MeshLinkConfig(
    val maxMessageSize: Int = 100_000,
    val bufferCapacity: Int = 1_048_576,
    val mtu: Int = 185,
    val rateLimitMaxSends: Int = 0,
    val rateLimitWindowMillis: Long = 60_000L,
    val circuitBreakerMaxFailures: Int = 0,
    val circuitBreakerWindowMillis: Long = 60_000L,
    val circuitBreakerCooldownMillis: Long = 30_000L,
    val diagnosticBufferCapacity: Int = 256,
    val dedupCapacity: Int = 100_000,
    val protocolVersion: ProtocolVersion = ProtocolVersion(1, 0),
    val appId: String? = null,
    val inboundRateLimitPerSenderPerMinute: Int = 0,
    val gossipIntervalMillis: Long = 0L,
    val pendingMessageTtlMillis: Long = 0L,
    val pendingMessageCapacity: Int = 100,
    val broadcastRateLimitPerMinute: Int = 0,
    val relayQueueCapacity: Int = 100,
    val maxHops: UByte = 10u,
    val ackWindowMin: Int = 2,
    val ackWindowMax: Int = 16,
    val powerModeThresholds: List<Int> = listOf(80, 30),
    val l2capEnabled: Boolean = true,
    val l2capRetryAttempts: Int = 3,
    val chunkInactivityTimeoutMillis: Long = 30_000L,
    val bufferTtlMillis: Long = 300_000L,
    val triggeredUpdateThreshold: Double = 0.3,
    val triggeredUpdateBatchMillis: Long = 100L,
    val keepaliveIntervalMillis: Long = 0L,
    val tombstoneWindowMillis: Long = 120_000L,
    val handshakeRateLimitPerSec: Int = 1,
    val nackRateLimitPerSec: Int = 10,
    val neighborAggregateLimitPerMin: Int = 100,
    val senderNeighborLimitPerMin: Int = 20,
    val maxConcurrentInboundSessions: Int = 100,
    val requireEncryption: Boolean = true,
) {
    fun validate(): List<String> {
        val violations = mutableListOf<String>()
        if (mtu <= 21) violations.add("mtu must be > 21 (chunk header size)")
        if (maxMessageSize > bufferCapacity) {
            violations.add(
                "maxMessageSize ($maxMessageSize) exceeds bufferCapacity ($bufferCapacity)",
            )
        }
        if (maxMessageSize <= 0) violations.add("maxMessageSize must be positive")
        if (bufferCapacity <= 0) violations.add("bufferCapacity must be positive")
        if (mtu > maxMessageSize) violations.add("mtu ($mtu) exceeds maxMessageSize ($maxMessageSize)")
        if (diagnosticBufferCapacity < 0) violations.add("diagnosticBufferCapacity must be non-negative")
        if (dedupCapacity <= 0) violations.add("dedupCapacity must be positive")
        if (rateLimitMaxSends > 0 && rateLimitWindowMillis <= 0) {
            violations.add("rateLimitWindowMillis must be positive when rate limiting is enabled")
        }
        if (circuitBreakerMaxFailures > 0 && circuitBreakerCooldownMillis <= 0) {
            violations.add("circuitBreakerCooldownMillis must be positive when circuit breaker is enabled")
        }
        // Cross-field validation rules from design doc §14
        if (ackWindowMax < ackWindowMin) {
            violations.add(
                "ackWindowMax ($ackWindowMax) must be >= ackWindowMin ($ackWindowMin)",
            )
        }
        if (powerModeThresholds.size >= 2 && powerModeThresholds[0] <= powerModeThresholds[1]) {
            violations.add("powerModeThresholds must be strictly descending: [${powerModeThresholds.joinToString()}]")
        }
        if (l2capEnabled && l2capRetryAttempts < 0) {
            violations.add(
                "l2capRetryAttempts ($l2capRetryAttempts) must be >= 0 when l2capEnabled is true",
            )
        }
        if (bufferTtlMillis > 0 && chunkInactivityTimeoutMillis >= bufferTtlMillis) {
            violations.add(
                "chunkInactivityTimeoutMillis ($chunkInactivityTimeoutMillis) must be < bufferTtlMillis ($bufferTtlMillis)",
            )
        }
        if (maxConcurrentInboundSessions <= 0) violations.add("maxConcurrentInboundSessions must be positive")
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

        fun sensorOptimized(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(
                maxMessageSize = 1_000,
                bufferCapacity = 65_536,
                gossipIntervalMillis = 30_000L,
                keepaliveIntervalMillis = 60_000L,
            ).apply(overrides).build()
    }
}

fun meshLinkConfig(block: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
    MeshLinkConfigBuilder().apply(block).build()

class MeshLinkConfigBuilder(
    var maxMessageSize: Int = 100_000,
    var bufferCapacity: Int = 1_048_576,
    var mtu: Int = 185,
    var rateLimitMaxSends: Int = 0,
    var rateLimitWindowMillis: Long = 60_000L,
    var circuitBreakerMaxFailures: Int = 0,
    var circuitBreakerWindowMillis: Long = 60_000L,
    var circuitBreakerCooldownMillis: Long = 30_000L,
    var diagnosticBufferCapacity: Int = 256,
    var dedupCapacity: Int = 100_000,
    var protocolVersion: ProtocolVersion = ProtocolVersion(1, 0),
    var appId: String? = null,
    var inboundRateLimitPerSenderPerMinute: Int = 0,
    var gossipIntervalMillis: Long = 0L,
    var pendingMessageTtlMillis: Long = 0L,
    var pendingMessageCapacity: Int = 100,
    var broadcastRateLimitPerMinute: Int = 0,
    var relayQueueCapacity: Int = 100,
    var maxHops: UByte = 10u,
    var ackWindowMin: Int = 2,
    var ackWindowMax: Int = 16,
    var powerModeThresholds: List<Int> = listOf(80, 30),
    var l2capEnabled: Boolean = true,
    var l2capRetryAttempts: Int = 3,
    var chunkInactivityTimeoutMillis: Long = 30_000L,
    var bufferTtlMillis: Long = 300_000L,
    var triggeredUpdateThreshold: Double = 0.3,
    var triggeredUpdateBatchMillis: Long = 100L,
    var keepaliveIntervalMillis: Long = 0L,
    var tombstoneWindowMillis: Long = 120_000L,
    var handshakeRateLimitPerSec: Int = 1,
    var nackRateLimitPerSec: Int = 10,
    var neighborAggregateLimitPerMin: Int = 100,
    var senderNeighborLimitPerMin: Int = 20,
    var maxConcurrentInboundSessions: Int = 100,
    var requireEncryption: Boolean = true,
) {

    fun build(): MeshLinkConfig = MeshLinkConfig(
        maxMessageSize = maxMessageSize,
        bufferCapacity = bufferCapacity,
        mtu = mtu,
        rateLimitMaxSends = rateLimitMaxSends,
        rateLimitWindowMillis = rateLimitWindowMillis,
        circuitBreakerMaxFailures = circuitBreakerMaxFailures,
        circuitBreakerWindowMillis = circuitBreakerWindowMillis,
        circuitBreakerCooldownMillis = circuitBreakerCooldownMillis,
        diagnosticBufferCapacity = diagnosticBufferCapacity,
        dedupCapacity = dedupCapacity,
        protocolVersion = protocolVersion,
        appId = appId,
        inboundRateLimitPerSenderPerMinute = inboundRateLimitPerSenderPerMinute,
        gossipIntervalMillis = gossipIntervalMillis,
        pendingMessageTtlMillis = pendingMessageTtlMillis,
        pendingMessageCapacity = pendingMessageCapacity,
        broadcastRateLimitPerMinute = broadcastRateLimitPerMinute,
        relayQueueCapacity = relayQueueCapacity,
        maxHops = maxHops,
        ackWindowMin = ackWindowMin,
        ackWindowMax = ackWindowMax,
        powerModeThresholds = powerModeThresholds,
        l2capEnabled = l2capEnabled,
        l2capRetryAttempts = l2capRetryAttempts,
        chunkInactivityTimeoutMillis = chunkInactivityTimeoutMillis,
        bufferTtlMillis = bufferTtlMillis,
        triggeredUpdateThreshold = triggeredUpdateThreshold,
        triggeredUpdateBatchMillis = triggeredUpdateBatchMillis,
        keepaliveIntervalMillis = keepaliveIntervalMillis,
        tombstoneWindowMillis = tombstoneWindowMillis,
        handshakeRateLimitPerSec = handshakeRateLimitPerSec,
        nackRateLimitPerSec = nackRateLimitPerSec,
        neighborAggregateLimitPerMin = neighborAggregateLimitPerMin,
        senderNeighborLimitPerMin = senderNeighborLimitPerMin,
        maxConcurrentInboundSessions = maxConcurrentInboundSessions,
        requireEncryption = requireEncryption,
    )
}
