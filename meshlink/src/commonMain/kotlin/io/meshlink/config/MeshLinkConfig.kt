package io.meshlink.config

import io.meshlink.crypto.TrustMode
import io.meshlink.power.PowerMode
import io.meshlink.protocol.ProtocolVersion

data class MeshLinkConfig(
    val maxMessageSize: Int = 100_000,
    val bufferCapacity: Int = 1_048_576,
    val mtu: Int = 185,
    val rateLimitMaxSends: Int = 60,
    val rateLimitWindowMillis: Long = 60_000L,
    val circuitBreakerMaxFailures: Int = 0,
    val circuitBreakerWindowMillis: Long = 60_000L,
    val circuitBreakerCooldownMillis: Long = 30_000L,
    val diagnosticBufferCapacity: Int = 256,
    val dedupCapacity: Int = 100_000,
    val protocolVersion: ProtocolVersion = ProtocolVersion(1, 0),
    val appId: String? = null,
    val inboundRateLimitPerSenderPerMinute: Int = 30,
    val pendingMessageTtlMillis: Long = 0L,
    val pendingMessageCapacity: Int = 100,
    val broadcastRateLimitPerMinute: Int = 10,
    val relayQueueCapacity: Int = 100,
    val maxHops: UByte = 10u,
    val broadcastTTL: UByte = 2u,
    val trustMode: TrustMode = TrustMode.STRICT,
    val deliveryAckEnabled: Boolean = true,
    val diagnosticsEnabled: Boolean = false,
    val customPowerMode: PowerMode? = null,
    val evictionGracePeriodMillis: Long = 30_000L,
    val l2capBackpressureWindowMillis: Long = 7_000L,
    val ackWindowMin: Int = 2,
    val ackWindowMax: Int = 16,
    val powerModeThresholds: List<Int> = listOf(80, 30),
    val l2capEnabled: Boolean = true,
    val l2capRetryAttempts: Int = 3,
    val chunkInactivityTimeoutMillis: Long = 30_000L,
    val bufferTtlMillis: Long = 300_000L,
    val deliveryTimeoutMillis: Long = 30_000L,
    val keepaliveIntervalMillis: Long = 0L,
    val routeCacheTtlMillis: Long = 60_000L,
    val routeDiscoveryTimeoutMillis: Long = 5_000L,
    val tombstoneWindowMillis: Long = 120_000L,
    val handshakeRateLimitPerSec: Int = 1,
    val nackRateLimitPerSec: Int = 10,
    val neighborAggregateLimitPerMin: Int = 100,
    val senderNeighborLimitPerMin: Int = 20,
    val maxConcurrentInboundSessions: Int = 100,
    val requireEncryption: Boolean = true,
    val compressionEnabled: Boolean = true,
    val compressionMinBytes: Int = 128,
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
        if (broadcastTTL < 1u || broadcastTTL > maxHops) {
            violations.add("broadcastTTL ($broadcastTTL) must be in range 1..maxHops ($maxHops)")
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
        if (deliveryTimeoutMillis < 0) violations.add("deliveryTimeoutMillis must be non-negative")
        if (deliveryTimeoutMillis > 0 && bufferTtlMillis > 0 && deliveryTimeoutMillis > bufferTtlMillis) {
            violations.add(
                "deliveryTimeoutMillis ($deliveryTimeoutMillis) must be <= bufferTtlMillis ($bufferTtlMillis)",
            )
        }
        return violations
    }

    companion object {
        fun smallPayloadLowLatency(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(
                maxMessageSize = 10_000,
                bufferCapacity = 524_288,
                deliveryTimeoutMillis = 10_000L,
            ).apply(overrides).build()

        fun largePayloadHighThroughput(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(
                maxMessageSize = 100_000,
                bufferCapacity = 2_097_152,
                deliveryTimeoutMillis = 120_000L,
            ).apply(overrides).build()

        fun minimalResourceUsage(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(maxMessageSize = 10_000, bufferCapacity = 262_144)
                .apply(overrides).build()

        fun minimalOverhead(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(
                maxMessageSize = 1_000,
                bufferCapacity = 65_536,
                keepaliveIntervalMillis = 60_000L,
                routeCacheTtlMillis = 120_000L,
            ).apply(overrides).build()

        @Deprecated("Use smallPayloadLowLatency()", ReplaceWith("smallPayloadLowLatency(overrides)"))
        fun chatOptimized(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            smallPayloadLowLatency(overrides)

        @Deprecated("Use largePayloadHighThroughput()", ReplaceWith("largePayloadHighThroughput(overrides)"))
        fun fileTransferOptimized(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            largePayloadHighThroughput(overrides)

        @Deprecated("Use minimalResourceUsage()", ReplaceWith("minimalResourceUsage(overrides)"))
        fun powerOptimized(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            minimalResourceUsage(overrides)

        @Deprecated("Use minimalOverhead()", ReplaceWith("minimalOverhead(overrides)"))
        fun sensorOptimized(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            minimalOverhead(overrides)
    }
}

fun meshLinkConfig(block: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
    MeshLinkConfigBuilder().apply(block).build()

class MeshLinkConfigBuilder(
    var maxMessageSize: Int = 100_000,
    var bufferCapacity: Int = 1_048_576,
    var mtu: Int = 185,
    var rateLimitMaxSends: Int = 60,
    var rateLimitWindowMillis: Long = 60_000L,
    var circuitBreakerMaxFailures: Int = 0,
    var circuitBreakerWindowMillis: Long = 60_000L,
    var circuitBreakerCooldownMillis: Long = 30_000L,
    var diagnosticBufferCapacity: Int = 256,
    var dedupCapacity: Int = 100_000,
    var protocolVersion: ProtocolVersion = ProtocolVersion(1, 0),
    var appId: String? = null,
    var inboundRateLimitPerSenderPerMinute: Int = 30,
    var pendingMessageTtlMillis: Long = 0L,
    var pendingMessageCapacity: Int = 100,
    var broadcastRateLimitPerMinute: Int = 10,
    var relayQueueCapacity: Int = 100,
    var maxHops: UByte = 10u,
    var broadcastTTL: UByte = 2u,
    var trustMode: TrustMode = TrustMode.STRICT,
    var deliveryAckEnabled: Boolean = true,
    var diagnosticsEnabled: Boolean = false,
    var customPowerMode: PowerMode? = null,
    var evictionGracePeriodMillis: Long = 30_000L,
    var l2capBackpressureWindowMillis: Long = 7_000L,
    var ackWindowMin: Int = 2,
    var ackWindowMax: Int = 16,
    var powerModeThresholds: List<Int> = listOf(80, 30),
    var l2capEnabled: Boolean = true,
    var l2capRetryAttempts: Int = 3,
    var chunkInactivityTimeoutMillis: Long = 30_000L,
    var bufferTtlMillis: Long = 300_000L,
    var deliveryTimeoutMillis: Long = 30_000L,
    var keepaliveIntervalMillis: Long = 0L,
    var routeCacheTtlMillis: Long = 60_000L,
    var routeDiscoveryTimeoutMillis: Long = 5_000L,
    var tombstoneWindowMillis: Long = 120_000L,
    var handshakeRateLimitPerSec: Int = 1,
    var nackRateLimitPerSec: Int = 10,
    var neighborAggregateLimitPerMin: Int = 100,
    var senderNeighborLimitPerMin: Int = 20,
    var maxConcurrentInboundSessions: Int = 100,
    var requireEncryption: Boolean = true,
    var compressionEnabled: Boolean = true,
    var compressionMinBytes: Int = 128,
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
        pendingMessageTtlMillis = pendingMessageTtlMillis,
        pendingMessageCapacity = pendingMessageCapacity,
        broadcastRateLimitPerMinute = broadcastRateLimitPerMinute,
        relayQueueCapacity = relayQueueCapacity,
        maxHops = maxHops,
        broadcastTTL = broadcastTTL,
        trustMode = trustMode,
        deliveryAckEnabled = deliveryAckEnabled,
        diagnosticsEnabled = diagnosticsEnabled,
        customPowerMode = customPowerMode,
        evictionGracePeriodMillis = evictionGracePeriodMillis,
        l2capBackpressureWindowMillis = l2capBackpressureWindowMillis,
        ackWindowMin = ackWindowMin,
        ackWindowMax = ackWindowMax,
        powerModeThresholds = powerModeThresholds,
        l2capEnabled = l2capEnabled,
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
        maxConcurrentInboundSessions = maxConcurrentInboundSessions,
        requireEncryption = requireEncryption,
        compressionEnabled = compressionEnabled,
        compressionMinBytes = compressionMinBytes,
    )
}
