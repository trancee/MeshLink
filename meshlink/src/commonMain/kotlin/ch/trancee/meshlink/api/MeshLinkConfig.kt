package ch.trancee.meshlink.api

import ch.trancee.meshlink.engine.HandshakeConfig
import ch.trancee.meshlink.engine.MeshEngineConfig
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.transfer.ChunkSizePolicy
import ch.trancee.meshlink.transport.BleTransportConfig
import ch.trancee.meshlink.transport.MeshHashFilter

// ── Public configuration hierarchy ──────────────────────────────────────────

/**
 * Configuration for the messaging and delivery subsystem.
 *
 * @param maxMessageSize Maximum payload size in bytes for any message (unicast or transfer).
 * @param bufferCapacity Total store-and-forward buffer capacity in bytes. Must be ≥ 64 KB and ≥
 *   [maxMessageSize] (throws if violated).
 * @param broadcastTtl Initial hop count for flood-fill broadcasts. Clamped to
 *   [RoutingConfig.maxHops] if greater.
 * @param maxBroadcastSize Maximum payload size in bytes for a broadcast message.
 */
public data class MessagingConfig(
    val maxMessageSize: Int = 102_400,
    val bufferCapacity: Int = 1_048_576,
    val broadcastTtl: Int = 2,
    val maxBroadcastSize: Int = 10_000,
)

/**
 * Configuration for the BLE transport layer.
 *
 * @param mtu BLE ATT MTU in bytes; determines chunk framing. Use 517 for L2CAP, 244 for GATT.
 * @param l2capEnabled Whether to advertise and use L2CAP CoC channels. Falls back to GATT if peer
 *   does not support L2CAP.
 * @param forceL2cap Skip GATT fallback and always open L2CAP even if PSM is advertised as 0x00.
 * @param forceGatt Skip L2CAP and always use GATT even if PSM is advertised.
 * @param bootstrapDurationMs Duration in milliseconds to stay in PERFORMANCE power tier on startup
 *   regardless of battery level.
 * @param l2capRetryAttempts Number of L2CAP connection retries before falling back to GATT.
 */
public data class TransportConfig(
    val mtu: Int = 517,
    val l2capEnabled: Boolean = true,
    val forceL2cap: Boolean = false,
    val forceGatt: Boolean = false,
    val bootstrapDurationMs: Long = 30_000L,
    val l2capRetryAttempts: Int = 3,
)

/**
 * Configuration for security and trust policies.
 *
 * @param requireEncryption When true (default), all messages must be encrypted via Noise
 *   ChaCha20-Poly1305. Plaintext fallback is rejected.
 * @param trustMode Governs reaction to peer key changes. Defaults to [TrustMode.STRICT].
 * @param keyChangeTimeoutMillis How long (ms) to wait for app resolution of a [TrustMode.PROMPT]
 *   key-change before auto-rejecting.
 * @param requireBroadcastSignatures When true (default), unsigned broadcasts are rejected.
 * @param ackJitterMaxMillis Maximum random jitter (ms) added to ACK timers to reduce
 *   synchronisation storms.
 */
public data class SecurityConfig(
    val requireEncryption: Boolean = true,
    val trustMode: TrustMode = TrustMode.STRICT,
    val keyChangeTimeoutMillis: Long = 30_000L,
    val requireBroadcastSignatures: Boolean = true,
    val ackJitterMaxMillis: Long = 500L,
)

/**
 * Battery-level thresholds that drive the automatic power tier selection.
 *
 * @param performanceThreshold Battery fraction (0–1) above which the engine stays in
 *   [PowerTier.PERFORMANCE]. Default: 0.80 (80 %).
 * @param powerSaverThreshold Battery fraction (0–1) below which the engine enters
 *   [PowerTier.POWER_SAVER]. Default: 0.30 (30 %).
 */
public data class PowerModeThresholds(
    val performanceThreshold: Float = 0.80f,
    val powerSaverThreshold: Float = 0.30f,
)

/**
 * Power-management configuration.
 *
 * @param powerModeThresholds Battery thresholds for automatic tier selection.
 * @param customPowerMode When non-null, overrides automatic tier selection with a fixed
 *   [PowerTier]. Set to null to restore automatic mode.
 * @param evictionGracePeriodMillis How long (ms) the engine waits before evicting a peer after the
 *   power tier drops below its connection limit.
 */
public data class PowerConfig(
    val powerModeThresholds: PowerModeThresholds = PowerModeThresholds(),
    val customPowerMode: PowerTier? = null,
    val evictionGracePeriodMillis: Long = 30_000L,
)

/**
 * Routing and deduplication configuration.
 *
 * @param routeCacheTtlMillis Route expiry in milliseconds. Routes older than this are evicted.
 * @param maxHops Maximum hop count applied to all broadcasts. Clamped to [1, 20].
 * @param dedupCapacity Maximum entries in the broadcast deduplication set. Clamped to
 *   [1000, 50000].
 * @param maxMessageAgeMillis Maximum age in milliseconds of a message before it is discarded by the
 *   dedup set (TTL for dedup entries).
 */
public data class RoutingConfig(
    val routeCacheTtlMillis: Long = 210_000L,
    val maxHops: Int = 10,
    val dedupCapacity: Int = 25_000,
    val maxMessageAgeMillis: Long = 2_700_000L,
)

/**
 * Diagnostic and observability configuration.
 *
 * @param enabled When false, the diagnostic event pipeline is disabled entirely.
 * @param bufferCapacity Maximum number of diagnostic events buffered in the event ring.
 * @param redactPeerIds When true, peer identifiers are replaced with truncated hashes in all
 *   diagnostic output (GDPR mode).
 * @param healthSnapshotIntervalMs Interval in milliseconds between [MeshHealthSnapshot] emissions
 *   on [MeshLinkApi.meshHealthFlow]. Default: 5 000 ms.
 */
public data class DiagnosticsConfig(
    val enabled: Boolean = true,
    val bufferCapacity: Int = 1_000,
    val redactPeerIds: Boolean = false,
    val healthSnapshotIntervalMs: Long = 5_000L,
)

/**
 * Rate-limiting configuration for outbound traffic.
 *
 * @param maxSends Maximum outbound unicast messages per minute.
 * @param broadcastLimit Maximum outbound broadcasts per minute.
 * @param handshakeLimit Maximum handshake initiations per peer per second.
 */
public data class RateLimitConfig(
    val maxSends: Int = 60,
    val broadcastLimit: Int = 10,
    val handshakeLimit: Int = 1,
)

/**
 * Chunked-transfer tuning configuration.
 *
 * @param chunkInactivityTimeout Base inactivity timeout in milliseconds for a transfer session.
 *   Scaled by hop count (up to 4×) to account for multi-hop latency.
 * @param maxConcurrentTransfers Maximum simultaneously active transfer sessions (inbound +
 *   outbound). Capped by the global hard limit (100) enforced by the TransferEngine.
 * @param ackTimeoutMultiplier Multiplier applied to the base ACK timeout when the link degrades.
 *   Reserved for future internal wiring; stored in config for forward compatibility.
 * @param degradationThreshold Fraction of ACK timeouts within a sliding window that triggers link
 *   degradation detection. Reserved for future internal wiring.
 */
public data class TransferConfig(
    val chunkInactivityTimeout: Long = 30_000L,
    val maxConcurrentTransfers: Int = 4,
    val ackTimeoutMultiplier: Float = 1.0f,
    val degradationThreshold: Float = 0.5f,
)

// ── Root config ──────────────────────────────────────────────────────────────

/**
 * Root configuration for a [MeshLink] instance.
 *
 * Create via the [meshLinkConfig] DSL builder or one of the preset factories in [Companion].
 * Validation is enforced at construction time via [MeshLinkConfigBuilder.build]:
 * - **Safety-critical violations** (e.g.
 *   [MessagingConfig.maxMessageSize] > [MessagingConfig.bufferCapacity]) throw
 *   [IllegalArgumentException].
 * - **Best-effort violations** (e.g. out-of-range [RoutingConfig.maxHops]) clamp the value and
 *   record a warning in [clampWarnings]. MeshLink emits these as `CONFIG_CLAMPED` diagnostic events
 *   on [MeshLinkApi.start].
 *
 * @param appId Required application identifier. Peers with different [appId] values will not
 *   connect to each other (mesh isolation). Hashed via FNV-1a to a 16-bit mesh hash embedded in
 *   every BLE advertisement.
 * @param messaging Messaging and delivery subsystem tuning.
 * @param transport BLE transport tuning.
 * @param security Security and trust policies.
 * @param power Power management configuration.
 * @param routing Routing and deduplication tuning.
 * @param diagnostics Diagnostic event pipeline configuration.
 * @param rateLimiting Outbound rate-limit configuration.
 * @param transfer Chunked-transfer tuning.
 * @param clampWarnings Populated by [MeshLinkConfigBuilder.build] when a best-effort field is
 *   clamped. MeshLink emits a `CONFIG_CLAMPED` diagnostic for each entry on start.
 */
public data class MeshLinkConfig(
    val appId: String,
    val messaging: MessagingConfig = MessagingConfig(),
    val transport: TransportConfig = TransportConfig(),
    val security: SecurityConfig = SecurityConfig(),
    val power: PowerConfig = PowerConfig(),
    val routing: RoutingConfig = RoutingConfig(),
    val diagnostics: DiagnosticsConfig = DiagnosticsConfig(),
    val rateLimiting: RateLimitConfig = RateLimitConfig(),
    val transfer: TransferConfig = TransferConfig(),
    val clampWarnings: List<String> = emptyList(),
) {

    /**
     * Converts this public config into an internal [MeshEngineConfig].
     *
     * The [appId] is hashed via FNV-1a to a 2-byte [ByteArray] used to scope broadcast messages.
     */
    internal fun toMeshEngineConfig(): MeshEngineConfig {
        val appIdHash = deriveAppIdHash()
        val bufferedMessages =
            (messaging.bufferCapacity / messaging.maxMessageSize).coerceAtLeast(1)
        return MeshEngineConfig(
            routing =
                ch.trancee.meshlink.routing.RoutingConfig(
                    routeExpiryMillis = routing.routeCacheTtlMillis,
                    dedupCapacity = routing.dedupCapacity,
                    dedupTtlMillis = routing.maxMessageAgeMillis,
                ),
            messaging =
                ch.trancee.meshlink.messaging.MessagingConfig(
                    maxMessageSize = messaging.maxMessageSize,
                    maxBufferedMessages = bufferedMessages,
                    broadcastTtl = messaging.broadcastTtl.toUByte(),
                    maxBroadcastSize = messaging.maxBroadcastSize,
                    appIdHash = appIdHash,
                    requireBroadcastSignatures = security.requireBroadcastSignatures,
                    outboundUnicastLimit = rateLimiting.maxSends,
                    broadcastLimit = rateLimiting.broadcastLimit,
                    handshakeLimit = rateLimiting.handshakeLimit,
                ),
            transfer =
                ch.trancee.meshlink.transfer.TransferConfig(
                    inactivityBaseTimeoutMillis = transfer.chunkInactivityTimeout
                ),
            power =
                ch.trancee.meshlink.power.PowerConfig(
                    performanceThreshold = power.powerModeThresholds.performanceThreshold,
                    powerSaverThreshold = power.powerModeThresholds.powerSaverThreshold,
                    evictionGracePeriodMillis = power.evictionGracePeriodMillis,
                    bootstrapDurationMillis = transport.bootstrapDurationMs,
                ),
            handshake = HandshakeConfig(),
            chunkSize = if (transport.l2capEnabled) ChunkSizePolicy.L2CAP else ChunkSizePolicy.GATT,
        )
    }

    /**
     * Converts this public config into an internal [BleTransportConfig].
     *
     * The [appId] is passed directly; the transport layer derives the 16-bit mesh hash internally
     * via [MeshHashFilter.computeMeshHash].
     */
    internal fun toBleTransportConfig(): BleTransportConfig =
        BleTransportConfig(
            appId = appId,
            forceL2cap = transport.forceL2cap,
            forceGatt = transport.forceGatt,
        )

    /**
     * Derives a 2-byte [ByteArray] from [appId] via FNV-1a XOR-fold.
     *
     * Stored little-endian (low byte first) to match the wire format in [Broadcast.appIdHash].
     */
    private fun deriveAppIdHash(): ByteArray {
        val meshHash = MeshHashFilter.computeMeshHash(appId)
        val v = meshHash.toInt()
        return byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }

    public companion object {

        /**
         * Preset for text-chat applications: small messages, low latency.
         *
         * maxMessageSize=10 KB, bufferCapacity=512 KB, dedupCapacity=25 000
         */
        public fun smallPayloadLowLatency(appId: String): MeshLinkConfig =
            MeshLinkConfigBuilder(appId)
                .apply {
                    messaging {
                        maxMessageSize = 10_000
                        bufferCapacity = 524_288
                        maxBroadcastSize = 5_000
                        broadcastTtl = 2
                    }
                    routing { dedupCapacity = 25_000 }
                }
                .build()

        /**
         * Preset for image/file transfers: large messages, high throughput.
         *
         * maxMessageSize=100 KB, bufferCapacity=2 MB, dedupCapacity=25 000
         */
        public fun largePayloadHighThroughput(appId: String): MeshLinkConfig =
            MeshLinkConfigBuilder(appId)
                .apply {
                    messaging {
                        maxMessageSize = 100_000
                        bufferCapacity = 2_097_152
                        maxBroadcastSize = 20_000
                        broadcastTtl = 2
                    }
                    routing { dedupCapacity = 25_000 }
                    transport { l2capEnabled = true }
                }
                .build()

        /**
         * Preset for IoT/wearable devices: minimal memory and radio usage.
         *
         * maxMessageSize=10 KB, bufferCapacity=256 KB, dedupCapacity=10 000
         */
        public fun minimalResourceUsage(appId: String): MeshLinkConfig =
            MeshLinkConfigBuilder(appId)
                .apply {
                    messaging {
                        maxMessageSize = 10_000
                        bufferCapacity = 262_144
                        maxBroadcastSize = 5_000
                        broadcastTtl = 2
                    }
                    routing { dedupCapacity = 10_000 }
                    transport { l2capEnabled = false }
                }
                .build()

        /**
         * Preset for sensor telemetry: tiny payloads, minimal buffer.
         *
         * maxMessageSize=1 KB, bufferCapacity=64 KB, dedupCapacity=5 000
         */
        public fun sensorTelemetry(appId: String): MeshLinkConfig =
            MeshLinkConfigBuilder(appId)
                .apply {
                    messaging {
                        maxMessageSize = 1_000
                        bufferCapacity = 65_536
                        maxBroadcastSize = 500
                        broadcastTtl = 1
                    }
                    routing {
                        dedupCapacity = 5_000
                        maxHops = 3
                    }
                    transport { l2capEnabled = false }
                }
                .build()
    }
}

// ── DSL entry point ───────────────────────────────────────────────────────────

/**
 * Creates a [MeshLinkConfig] via the nested DSL builder.
 *
 * Example:
 * ```kotlin
 * val config = meshLinkConfig("com.example.myapp") {
 *     security { trustMode = TrustMode.PROMPT }
 *     transport { l2capEnabled = false }
 *     routing { maxHops = 5 }
 * }
 * ```
 *
 * Validation rules are enforced by [MeshLinkConfigBuilder.build]:
 * - `maxMessageSize > bufferCapacity` → throws [IllegalArgumentException]
 * - `broadcastTtl > maxHops`, `maxHops` outside [1, 20], `bufferCapacity < 64 KB`, `dedupCapacity`
 *   outside [1000, 50000] → clamped; warning added to [MeshLinkConfig.clampWarnings].
 *
 * @param appId Required application identifier for mesh isolation.
 * @param block Configuration DSL lambda.
 */
public fun meshLinkConfig(
    appId: String,
    block: MeshLinkConfigBuilder.() -> Unit = {},
): MeshLinkConfig = MeshLinkConfigBuilder(appId).apply(block).build()

// ── Builder ───────────────────────────────────────────────────────────────────

/** Mutable builder for [MessagingConfig] used inside the [meshLinkConfig] DSL. */
public class MessagingConfigBuilder {
    public var maxMessageSize: Int = 102_400
    public var bufferCapacity: Int = 1_048_576
    public var broadcastTtl: Int = 2
    public var maxBroadcastSize: Int = 10_000

    internal fun build(): MessagingConfig =
        MessagingConfig(
            maxMessageSize = maxMessageSize,
            bufferCapacity = bufferCapacity,
            broadcastTtl = broadcastTtl,
            maxBroadcastSize = maxBroadcastSize,
        )
}

/** Mutable builder for [TransportConfig] used inside the [meshLinkConfig] DSL. */
public class TransportConfigBuilder {
    public var mtu: Int = 517
    public var l2capEnabled: Boolean = true
    public var forceL2cap: Boolean = false
    public var forceGatt: Boolean = false
    public var bootstrapDurationMs: Long = 30_000L
    public var l2capRetryAttempts: Int = 3

    internal fun build(): TransportConfig =
        TransportConfig(
            mtu = mtu,
            l2capEnabled = l2capEnabled,
            forceL2cap = forceL2cap,
            forceGatt = forceGatt,
            bootstrapDurationMs = bootstrapDurationMs,
            l2capRetryAttempts = l2capRetryAttempts,
        )
}

/** Mutable builder for [SecurityConfig] used inside the [meshLinkConfig] DSL. */
public class SecurityConfigBuilder {
    public var requireEncryption: Boolean = true
    public var trustMode: TrustMode = TrustMode.STRICT
    public var keyChangeTimeoutMillis: Long = 30_000L
    public var requireBroadcastSignatures: Boolean = true
    public var ackJitterMaxMillis: Long = 500L

    internal fun build(): SecurityConfig =
        SecurityConfig(
            requireEncryption = requireEncryption,
            trustMode = trustMode,
            keyChangeTimeoutMillis = keyChangeTimeoutMillis,
            requireBroadcastSignatures = requireBroadcastSignatures,
            ackJitterMaxMillis = ackJitterMaxMillis,
        )
}

/** Mutable builder for [PowerModeThresholds] used inside the [meshLinkConfig] DSL. */
public class PowerModeThresholdsBuilder {
    public var performanceThreshold: Float = 0.80f
    public var powerSaverThreshold: Float = 0.30f

    internal fun build(): PowerModeThresholds =
        PowerModeThresholds(
            performanceThreshold = performanceThreshold,
            powerSaverThreshold = powerSaverThreshold,
        )
}

/** Mutable builder for [PowerConfig] used inside the [meshLinkConfig] DSL. */
public class PowerConfigBuilder {
    public var powerModeThresholds: PowerModeThresholdsBuilder = PowerModeThresholdsBuilder()
    public var customPowerMode: PowerTier? = null
    public var evictionGracePeriodMillis: Long = 30_000L

    /** Nested DSL for [PowerModeThresholds]. */
    public fun powerModeThresholds(block: PowerModeThresholdsBuilder.() -> Unit) {
        powerModeThresholds.block()
    }

    internal fun build(): PowerConfig =
        PowerConfig(
            powerModeThresholds = powerModeThresholds.build(),
            customPowerMode = customPowerMode,
            evictionGracePeriodMillis = evictionGracePeriodMillis,
        )
}

/** Mutable builder for [RoutingConfig] used inside the [meshLinkConfig] DSL. */
public class RoutingConfigBuilder {
    public var routeCacheTtlMillis: Long = 210_000L
    public var maxHops: Int = 10
    public var dedupCapacity: Int = 25_000
    public var maxMessageAgeMillis: Long = 2_700_000L

    internal fun build(): RoutingConfig =
        RoutingConfig(
            routeCacheTtlMillis = routeCacheTtlMillis,
            maxHops = maxHops,
            dedupCapacity = dedupCapacity,
            maxMessageAgeMillis = maxMessageAgeMillis,
        )
}

/** Mutable builder for [DiagnosticsConfig] used inside the [meshLinkConfig] DSL. */
public class DiagnosticsConfigBuilder {
    public var enabled: Boolean = true
    public var bufferCapacity: Int = 1_000
    public var redactPeerIds: Boolean = false
    public var healthSnapshotIntervalMs: Long = 5_000L

    internal fun build(): DiagnosticsConfig =
        DiagnosticsConfig(
            enabled = enabled,
            bufferCapacity = bufferCapacity,
            redactPeerIds = redactPeerIds,
            healthSnapshotIntervalMs = healthSnapshotIntervalMs,
        )
}

/** Mutable builder for [RateLimitConfig] used inside the [meshLinkConfig] DSL. */
public class RateLimitConfigBuilder {
    public var maxSends: Int = 60
    public var broadcastLimit: Int = 10
    public var handshakeLimit: Int = 1

    internal fun build(): RateLimitConfig =
        RateLimitConfig(
            maxSends = maxSends,
            broadcastLimit = broadcastLimit,
            handshakeLimit = handshakeLimit,
        )
}

/** Mutable builder for [TransferConfig] used inside the [meshLinkConfig] DSL. */
public class TransferConfigBuilder {
    public var chunkInactivityTimeout: Long = 30_000L
    public var maxConcurrentTransfers: Int = 4
    public var ackTimeoutMultiplier: Float = 1.0f
    public var degradationThreshold: Float = 0.5f

    internal fun build(): TransferConfig =
        TransferConfig(
            chunkInactivityTimeout = chunkInactivityTimeout,
            maxConcurrentTransfers = maxConcurrentTransfers,
            ackTimeoutMultiplier = ackTimeoutMultiplier,
            degradationThreshold = degradationThreshold,
        )
}

/**
 * DSL builder for [MeshLinkConfig].
 *
 * Use [meshLinkConfig] to create an instance. Each sub-section has a DSL method:
 * ```kotlin
 * meshLinkConfig("com.example.app") {
 *     messaging { maxMessageSize = 50_000 }
 *     security { trustMode = TrustMode.PROMPT }
 *     transport { l2capEnabled = false }
 *     power { evictionGracePeriodMillis = 60_000 }
 *     routing { maxHops = 5 }
 *     diagnostics { redactPeerIds = true }
 *     rateLimiting { maxSends = 30 }
 *     transfer { chunkInactivityTimeout = 60_000 }
 * }
 * ```
 */
public class MeshLinkConfigBuilder(private val appId: String) {

    private val messagingBuilder = MessagingConfigBuilder()
    private val transportBuilder = TransportConfigBuilder()
    private val securityBuilder = SecurityConfigBuilder()
    private val powerBuilder = PowerConfigBuilder()
    private val routingBuilder = RoutingConfigBuilder()
    private val diagnosticsBuilder = DiagnosticsConfigBuilder()
    private val rateLimitingBuilder = RateLimitConfigBuilder()
    private val transferBuilder = TransferConfigBuilder()

    /** Configure the messaging subsystem. */
    public fun messaging(block: MessagingConfigBuilder.() -> Unit) {
        messagingBuilder.block()
    }

    /** Configure the BLE transport. */
    public fun transport(block: TransportConfigBuilder.() -> Unit) {
        transportBuilder.block()
    }

    /** Configure security and trust policies. */
    public fun security(block: SecurityConfigBuilder.() -> Unit) {
        securityBuilder.block()
    }

    /** Configure power management. */
    public fun power(block: PowerConfigBuilder.() -> Unit) {
        powerBuilder.block()
    }

    /** Configure routing and deduplication. */
    public fun routing(block: RoutingConfigBuilder.() -> Unit) {
        routingBuilder.block()
    }

    /** Configure the diagnostic event pipeline. */
    public fun diagnostics(block: DiagnosticsConfigBuilder.() -> Unit) {
        diagnosticsBuilder.block()
    }

    /** Configure outbound rate limits. */
    public fun rateLimiting(block: RateLimitConfigBuilder.() -> Unit) {
        rateLimitingBuilder.block()
    }

    /** Configure chunked-transfer tuning. */
    public fun transfer(block: TransferConfigBuilder.() -> Unit) {
        transferBuilder.block()
    }

    /**
     * Validates field constraints and builds the [MeshLinkConfig].
     *
     * **Safety-critical (throws):**
     * - `maxMessageSize > bufferCapacity`
     *
     * **Best-effort (clamp + CONFIG_CLAMPED warning):**
     * - `bufferCapacity < 64 KB` → clamped to 65536
     * - `maxHops` outside [1, 20] → clamped to 1 or 20
     * - `broadcastTtl > maxHops` → clamped to maxHops
     * - `dedupCapacity` outside [1000, 50000] → clamped to 1000 or 50000
     */
    public fun build(): MeshLinkConfig {
        val clampWarnings = mutableListOf<String>()

        // Materialise raw builder values for mutation / cross-field checks.
        var bufferCapacity = messagingBuilder.bufferCapacity
        var broadcastTtl = messagingBuilder.broadcastTtl
        var maxHops = routingBuilder.maxHops
        var dedupCapacity = routingBuilder.dedupCapacity
        val maxMessageSize = messagingBuilder.maxMessageSize

        // ── Best-effort clamps ────────────────────────────────────────────────
        // Message format: "<fieldName>: <reason>" so callers can match on startsWith(fieldName).

        // 1. bufferCapacity ≥ 64 KB
        if (bufferCapacity < 65_536) {
            clampWarnings.add("bufferCapacity: clamped $bufferCapacity → 65536 (minimum 64 KB)")
            bufferCapacity = 65_536
        }

        // 2. maxHops ∈ [1, 20]
        if (maxHops < 1) {
            clampWarnings.add("maxHops: clamped $maxHops → 1 (minimum 1)")
            maxHops = 1
        }
        if (maxHops > 20) {
            clampWarnings.add("maxHops: clamped $maxHops → 20 (maximum 20)")
            maxHops = 20
        }

        // 3. broadcastTtl ≤ maxHops  (evaluated after maxHops may have been clamped)
        if (broadcastTtl > maxHops) {
            clampWarnings.add(
                "broadcastTtl: clamped $broadcastTtl → $maxHops (exceeds routing.maxHops)"
            )
            broadcastTtl = maxHops
        }

        // 4. dedupCapacity ∈ [1000, 50000]
        if (dedupCapacity < 1_000) {
            clampWarnings.add("dedupCapacity: clamped $dedupCapacity → 1000 (minimum 1000)")
            dedupCapacity = 1_000
        }
        if (dedupCapacity > 50_000) {
            clampWarnings.add("dedupCapacity: clamped $dedupCapacity → 50000 (maximum 50000)")
            dedupCapacity = 50_000
        }

        // ── Safety-critical validation (throws) ───────────────────────────────
        // Evaluated after clamps so bufferCapacity has already been raised to ≥ 64 KB.
        if (maxMessageSize > bufferCapacity) {
            throw IllegalArgumentException(
                "maxMessageSize ($maxMessageSize) must be <= bufferCapacity ($bufferCapacity)"
            )
        }

        val messaging =
            MessagingConfig(
                maxMessageSize = maxMessageSize,
                bufferCapacity = bufferCapacity,
                broadcastTtl = broadcastTtl,
                maxBroadcastSize = messagingBuilder.maxBroadcastSize,
            )
        val routing =
            RoutingConfig(
                routeCacheTtlMillis = routingBuilder.routeCacheTtlMillis,
                maxHops = maxHops,
                dedupCapacity = dedupCapacity,
                maxMessageAgeMillis = routingBuilder.maxMessageAgeMillis,
            )

        return MeshLinkConfig(
            appId = appId,
            messaging = messaging,
            transport = transportBuilder.build(),
            security = securityBuilder.build(),
            power = powerBuilder.build(),
            routing = routing,
            diagnostics = diagnosticsBuilder.build(),
            rateLimiting = rateLimitingBuilder.build(),
            transfer = transferBuilder.build(),
            clampWarnings = clampWarnings,
        )
    }
}
