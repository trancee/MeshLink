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
    )
}
