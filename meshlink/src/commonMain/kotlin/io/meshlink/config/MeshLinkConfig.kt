package io.meshlink.config

import io.meshlink.crypto.TrustMode
import io.meshlink.power.PowerMode
import io.meshlink.protocol.ProtocolVersion

/**
 * Immutable mesh configuration.
 *
 * Contains **public API** parameters commonly tuned by consuming apps.
 * Internal / advanced protocol parameters live in [MeshLinkInternalConfig]
 * and are accessible via [advanced].
 */
data class MeshLinkConfig(
    // ── Public API parameters ─────────────────────────────────────
    val maxMessageSize: Int = 100_000,
    val bufferCapacity: Int = 1_048_576,
    val mtu: Int = 185,
    val maxHops: UByte = 10u,
    val broadcastTtl: UByte = 2u,
    val appId: String? = null,
    val trustMode: TrustMode = TrustMode.SOFT_REPIN,
    val deliveryAckEnabled: Boolean = true,
    val diagnosticsEnabled: Boolean = true,
    val customPowerMode: PowerMode? = null,
    val powerModeThresholds: List<Int> = listOf(80, 30),
    val l2capEnabled: Boolean = true,
    val compressionEnabled: Boolean = true,
    val compressionMinBytes: Int = 128,
    val requireEncryption: Boolean = true,
    // ── Advanced / internal parameters ────────────────────────────
    val advanced: MeshLinkInternalConfig = MeshLinkInternalConfig(),
) {

    // ── Convenience accessors (delegate to advanced) ──────────────
    // These keep existing call-sites compiling during migration and
    // will be removed once all callers switch to config.advanced.xxx.

    val rateLimitMaxSends: Int get() = advanced.rateLimitMaxSends
    val rateLimitWindowMillis: Long get() = advanced.rateLimitWindowMillis
    val circuitBreakerMaxFailures: Int get() = advanced.circuitBreakerMaxFailures
    val circuitBreakerWindowMillis: Long get() = advanced.circuitBreakerWindowMillis
    val circuitBreakerCooldownMillis: Long get() = advanced.circuitBreakerCooldownMillis
    val diagnosticBufferCapacity: Int get() = advanced.diagnosticBufferCapacity
    val dedupCapacity: Int get() = advanced.dedupCapacity
    val protocolVersion: ProtocolVersion get() = advanced.protocolVersion
    val inboundRateLimitPerSenderPerMin: Int get() = advanced.inboundRateLimitPerSenderPerMin
    val pendingMessageTtlMillis: Long get() = advanced.pendingMessageTtlMillis
    val pendingMessageCapacity: Int get() = advanced.pendingMessageCapacity
    val broadcastRateLimitPerMin: Int get() = advanced.broadcastRateLimitPerMin
    val relayQueueCapacity: Int get() = advanced.relayQueueCapacity
    val evictionGracePeriodMillis: Long get() = advanced.evictionGracePeriodMillis
    val l2capBackpressureWindowMillis: Long get() = advanced.l2capBackpressureWindowMillis
    val ackWindowMin: Int get() = advanced.ackWindowMin
    val ackWindowMax: Int get() = advanced.ackWindowMax
    val l2capRetryAttempts: Int get() = advanced.l2capRetryAttempts
    val chunkInactivityTimeoutMillis: Long get() = advanced.chunkInactivityTimeoutMillis
    val bufferTtlMillis: Long get() = advanced.bufferTtlMillis
    val deliveryTimeoutMillis: Long get() = advanced.deliveryTimeoutMillis
    val keepaliveIntervalMillis: Long get() = advanced.keepaliveIntervalMillis
    val routeCacheTtlMillis: Long get() = advanced.routeCacheTtlMillis
    val routeDiscoveryTimeoutMillis: Long get() = advanced.routeDiscoveryTimeoutMillis
    val tombstoneWindowMillis: Long get() = advanced.tombstoneWindowMillis
    val handshakeRateLimitPerSec: Int get() = advanced.handshakeRateLimitPerSec
    val nackRateLimitPerSec: Int get() = advanced.nackRateLimitPerSec
    val neighborAggregateLimitPerMin: Int get() = advanced.neighborAggregateLimitPerMin
    val senderNeighborLimitPerMin: Int get() = advanced.senderNeighborLimitPerMin
    val maxConcurrentInboundSessions: Int get() = advanced.maxConcurrentInboundSessions

    fun validate(): List<String> {
        val violations = mutableListOf<String>()
        // Public field validations
        if (mtu <= 21) violations.add("mtu must be > 21 (chunk header size)")
        if (maxMessageSize > bufferCapacity) {
            violations.add("maxMessageSize ($maxMessageSize) exceeds bufferCapacity ($bufferCapacity)")
        }
        if (maxMessageSize <= 0) violations.add("maxMessageSize must be positive")
        if (bufferCapacity <= 0) violations.add("bufferCapacity must be positive")
        if (mtu > maxMessageSize) violations.add("mtu ($mtu) exceeds maxMessageSize ($maxMessageSize)")
        if (broadcastTtl < 1u || broadcastTtl > maxHops) {
            violations.add("broadcastTtl ($broadcastTtl) must be in range 1..maxHops ($maxHops)")
        }
        if (powerModeThresholds.size >= 2 && powerModeThresholds[0] <= powerModeThresholds[1]) {
            violations.add("powerModeThresholds must be strictly descending: [${powerModeThresholds.joinToString()}]")
        }
        if (l2capEnabled && l2capRetryAttempts < 0) {
            violations.add("l2capRetryAttempts ($l2capRetryAttempts) must be >= 0 when l2capEnabled is true")
        }
        // Internal field validations
        violations.addAll(advanced.validate())
        return violations
    }

    companion object {
        fun smallPayloadLowLatency(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(
                maxMessageSize = 10_000,
                bufferCapacity = 524_288,
            ).apply {
                advanced { deliveryTimeoutMillis = 10_000L }
                overrides()
            }.build()

        fun largePayloadHighThroughput(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(
                maxMessageSize = 100_000,
                bufferCapacity = 2_097_152,
            ).apply {
                advanced { deliveryTimeoutMillis = 120_000L }
                overrides()
            }.build()

        fun minimalResourceUsage(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(maxMessageSize = 10_000, bufferCapacity = 262_144)
                .apply(overrides).build()

        fun minimalOverhead(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(
                maxMessageSize = 1_000,
                bufferCapacity = 65_536,
            ).apply {
                advanced {
                    keepaliveIntervalMillis = 60_000L
                    routeCacheTtlMillis = 120_000L
                }
                overrides()
            }.build()

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
    // ── Public API parameters ─────────────────────────────────────
    var maxMessageSize: Int = 100_000,
    var bufferCapacity: Int = 1_048_576,
    var mtu: Int = 185,
    var maxHops: UByte = 10u,
    var broadcastTtl: UByte = 2u,
    var appId: String? = null,
    var trustMode: TrustMode = TrustMode.SOFT_REPIN,
    var deliveryAckEnabled: Boolean = true,
    var diagnosticsEnabled: Boolean = true,
    var customPowerMode: PowerMode? = null,
    var powerModeThresholds: List<Int> = listOf(80, 30),
    var l2capEnabled: Boolean = true,
    var compressionEnabled: Boolean = true,
    var compressionMinBytes: Int = 128,
    var requireEncryption: Boolean = true,
) {
    // ── Advanced / internal parameters ────────────────────────────
    private var advancedBuilder = MeshLinkInternalConfigBuilder()

    /** Configure advanced / internal parameters. */
    fun advanced(block: MeshLinkInternalConfigBuilder.() -> Unit) {
        advancedBuilder.apply(block)
    }

    // ── Compatibility setters for existing call-sites ────────────
    // These forward to the advanced builder so that existing code like
    //   meshLinkConfig { keepaliveIntervalMillis = 0L }
    // keeps compiling. Will be removed once all callers migrate.

    var rateLimitMaxSends: Int
        get() = advancedBuilder.rateLimitMaxSends
        set(value) { advancedBuilder.rateLimitMaxSends = value }
    var rateLimitWindowMillis: Long
        get() = advancedBuilder.rateLimitWindowMillis
        set(value) { advancedBuilder.rateLimitWindowMillis = value }
    var circuitBreakerMaxFailures: Int
        get() = advancedBuilder.circuitBreakerMaxFailures
        set(value) { advancedBuilder.circuitBreakerMaxFailures = value }
    var circuitBreakerWindowMillis: Long
        get() = advancedBuilder.circuitBreakerWindowMillis
        set(value) { advancedBuilder.circuitBreakerWindowMillis = value }
    var circuitBreakerCooldownMillis: Long
        get() = advancedBuilder.circuitBreakerCooldownMillis
        set(value) { advancedBuilder.circuitBreakerCooldownMillis = value }
    var diagnosticBufferCapacity: Int
        get() = advancedBuilder.diagnosticBufferCapacity
        set(value) { advancedBuilder.diagnosticBufferCapacity = value }
    var dedupCapacity: Int
        get() = advancedBuilder.dedupCapacity
        set(value) { advancedBuilder.dedupCapacity = value }
    var protocolVersion: ProtocolVersion
        get() = advancedBuilder.protocolVersion
        set(value) { advancedBuilder.protocolVersion = value }
    var inboundRateLimitPerSenderPerMin: Int
        get() = advancedBuilder.inboundRateLimitPerSenderPerMin
        set(value) { advancedBuilder.inboundRateLimitPerSenderPerMin = value }
    var pendingMessageTtlMillis: Long
        get() = advancedBuilder.pendingMessageTtlMillis
        set(value) { advancedBuilder.pendingMessageTtlMillis = value }
    var pendingMessageCapacity: Int
        get() = advancedBuilder.pendingMessageCapacity
        set(value) { advancedBuilder.pendingMessageCapacity = value }
    var broadcastRateLimitPerMin: Int
        get() = advancedBuilder.broadcastRateLimitPerMin
        set(value) { advancedBuilder.broadcastRateLimitPerMin = value }
    var relayQueueCapacity: Int
        get() = advancedBuilder.relayQueueCapacity
        set(value) { advancedBuilder.relayQueueCapacity = value }
    var evictionGracePeriodMillis: Long
        get() = advancedBuilder.evictionGracePeriodMillis
        set(value) { advancedBuilder.evictionGracePeriodMillis = value }
    var l2capBackpressureWindowMillis: Long
        get() = advancedBuilder.l2capBackpressureWindowMillis
        set(value) { advancedBuilder.l2capBackpressureWindowMillis = value }
    var ackWindowMin: Int
        get() = advancedBuilder.ackWindowMin
        set(value) { advancedBuilder.ackWindowMin = value }
    var ackWindowMax: Int
        get() = advancedBuilder.ackWindowMax
        set(value) { advancedBuilder.ackWindowMax = value }
    var l2capRetryAttempts: Int
        get() = advancedBuilder.l2capRetryAttempts
        set(value) { advancedBuilder.l2capRetryAttempts = value }
    var chunkInactivityTimeoutMillis: Long
        get() = advancedBuilder.chunkInactivityTimeoutMillis
        set(value) { advancedBuilder.chunkInactivityTimeoutMillis = value }
    var bufferTtlMillis: Long
        get() = advancedBuilder.bufferTtlMillis
        set(value) { advancedBuilder.bufferTtlMillis = value }
    var deliveryTimeoutMillis: Long
        get() = advancedBuilder.deliveryTimeoutMillis
        set(value) { advancedBuilder.deliveryTimeoutMillis = value }
    var keepaliveIntervalMillis: Long
        get() = advancedBuilder.keepaliveIntervalMillis
        set(value) { advancedBuilder.keepaliveIntervalMillis = value }
    var routeCacheTtlMillis: Long
        get() = advancedBuilder.routeCacheTtlMillis
        set(value) { advancedBuilder.routeCacheTtlMillis = value }
    var routeDiscoveryTimeoutMillis: Long
        get() = advancedBuilder.routeDiscoveryTimeoutMillis
        set(value) { advancedBuilder.routeDiscoveryTimeoutMillis = value }
    var tombstoneWindowMillis: Long
        get() = advancedBuilder.tombstoneWindowMillis
        set(value) { advancedBuilder.tombstoneWindowMillis = value }
    var handshakeRateLimitPerSec: Int
        get() = advancedBuilder.handshakeRateLimitPerSec
        set(value) { advancedBuilder.handshakeRateLimitPerSec = value }
    var nackRateLimitPerSec: Int
        get() = advancedBuilder.nackRateLimitPerSec
        set(value) { advancedBuilder.nackRateLimitPerSec = value }
    var neighborAggregateLimitPerMin: Int
        get() = advancedBuilder.neighborAggregateLimitPerMin
        set(value) { advancedBuilder.neighborAggregateLimitPerMin = value }
    var senderNeighborLimitPerMin: Int
        get() = advancedBuilder.senderNeighborLimitPerMin
        set(value) { advancedBuilder.senderNeighborLimitPerMin = value }
    var maxConcurrentInboundSessions: Int
        get() = advancedBuilder.maxConcurrentInboundSessions
        set(value) { advancedBuilder.maxConcurrentInboundSessions = value }

    fun build(): MeshLinkConfig = MeshLinkConfig(
        maxMessageSize = maxMessageSize,
        bufferCapacity = bufferCapacity,
        mtu = mtu,
        maxHops = maxHops,
        broadcastTtl = broadcastTtl,
        appId = appId,
        trustMode = trustMode,
        deliveryAckEnabled = deliveryAckEnabled,
        diagnosticsEnabled = diagnosticsEnabled,
        customPowerMode = customPowerMode,
        powerModeThresholds = powerModeThresholds,
        l2capEnabled = l2capEnabled,
        compressionEnabled = compressionEnabled,
        compressionMinBytes = compressionMinBytes,
        requireEncryption = requireEncryption,
        advanced = advancedBuilder.build(),
    )
}
