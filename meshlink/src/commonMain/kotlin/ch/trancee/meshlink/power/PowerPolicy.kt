package ch.trancee.meshlink.power

import ch.trancee.meshlink.concurrent.compareAndSetLoop
import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal enum class PowerTier {
    PERFORMANCE,
    BALANCED,
    POWER_SAVER,
}

internal class PowerPolicyProfile
internal constructor(
    internal val advertisementIntervalMillis: Long,
    internal val connectionIntervalMillis: Long,
    internal val scanDutyCyclePercent: Int,
    internal val maxConnections: Int,
    internal val chunkBudgetBytes: Int,
)

internal class PowerPolicy
internal constructor(
    internal val tier: PowerTier,
    profile: PowerPolicyProfile,
    internal val region: RegulatoryRegion,
    clampWarnings: List<String> = emptyList(),
) {
    internal val advertisementIntervalMillis: Long = profile.advertisementIntervalMillis
    internal val connectionIntervalMillis: Long = profile.connectionIntervalMillis
    internal val scanDutyCyclePercent: Int = profile.scanDutyCyclePercent
    internal val maxConnections: Int = profile.maxConnections
    internal val chunkBudgetBytes: Int = profile.chunkBudgetBytes
    internal val clampWarnings: List<String> = clampWarnings.toList()
}

private data class AutomaticTierConfig(
    val performanceThreshold: Float,
    val powerSaverThreshold: Float,
    val hysteresisBand: Float,
    val bootstrapDurationMillis: Long,
)

private data class AutomaticTierState(
    val batteryLevel: Float,
    val charging: Boolean,
    val bootstrapStartedAtMillis: Long?,
    val lastTier: PowerTier?,
)

/**
 * Mutable fields of [PowerPolicyController] captured as a single immutable snapshot so that every
 * observation (battery update, mesh start, policy read) is applied atomically via
 * [ch.trancee.meshlink.concurrent.compareAndSetLoop] instead of touching several unsynchronized
 * fields independently. This keeps battery observation callbacks non-suspend (they can arrive from
 * arbitrary platform callback threads, e.g. a system battery broadcast receiver) while still being
 * safe under concurrent access from the engine's multi-threaded
 * [kotlinx.coroutines.Dispatchers.Default] scope.
 */
private data class PowerPolicyControllerState(
    val batteryLevel: Float = 1.0f,
    val charging: Boolean = false,
    val bootstrapStartedAtMillis: Long? = null,
    val lastTier: PowerTier? = null,
)

@OptIn(ExperimentalAtomicApi::class)
internal class PowerPolicyController
internal constructor(
    private val configuredMode: PowerMode,
    private val region: RegulatoryRegion,
    performanceThreshold: Float = DEFAULT_PERFORMANCE_THRESHOLD,
    powerSaverThreshold: Float = DEFAULT_POWER_SAVER_THRESHOLD,
    hysteresisBand: Float = DEFAULT_HYSTERESIS_BAND,
    bootstrapDurationMillis: Long = DEFAULT_BOOTSTRAP_DURATION_MILLIS,
) {
    private val automaticTierConfig =
        AutomaticTierConfig(
            performanceThreshold = performanceThreshold,
            powerSaverThreshold = powerSaverThreshold,
            hysteresisBand = hysteresisBand,
            bootstrapDurationMillis = bootstrapDurationMillis,
        )
    private val state = AtomicReference(PowerPolicyControllerState())

    internal fun onMeshStarted(nowMillis: Long): PowerPolicy {
        state.compareAndSetLoop { current ->
            if (current.bootstrapStartedAtMillis == null) {
                current.copy(bootstrapStartedAtMillis = nowMillis)
            } else {
                current
            }
        }
        return currentPolicy(nowMillis)
    }

    internal fun onBatterySnapshot(
        level: Float,
        isCharging: Boolean,
        nowMillis: Long,
    ): PowerPolicy {
        state.compareAndSetLoop { current ->
            current.copy(batteryLevel = level.coerceIn(0.0f, 1.0f), charging = isCharging)
        }
        return currentPolicy(nowMillis)
    }

    internal fun currentPolicy(nowMillis: Long): PowerPolicy {
        val tier = resolveTier(nowMillis)
        state.compareAndSetLoop { current -> current.copy(lastTier = tier) }
        return clampForRegion(basePolicyFor(tier))
    }

    private fun resolveTier(nowMillis: Long): PowerTier {
        return when (configuredMode) {
            PowerMode.Performance -> PowerTier.PERFORMANCE
            PowerMode.Balanced -> PowerTier.BALANCED
            PowerMode.PowerSaver -> PowerTier.POWER_SAVER
            PowerMode.Automatic -> resolveAutomaticTier(nowMillis)
        }
    }

    private fun resolveAutomaticTier(nowMillis: Long): PowerTier {
        val snapshot = state.load()
        return resolveAutomaticTier(
            nowMillis = nowMillis,
            state =
                AutomaticTierState(
                    batteryLevel = snapshot.batteryLevel,
                    charging = snapshot.charging,
                    bootstrapStartedAtMillis = snapshot.bootstrapStartedAtMillis,
                    lastTier = snapshot.lastTier,
                ),
            config = automaticTierConfig,
        )
    }

    private fun basePolicyFor(tier: PowerTier): PowerPolicy {
        return when (tier) {
            PowerTier.PERFORMANCE ->
                PowerPolicy(
                    tier = tier,
                    profile =
                        PowerPolicyProfile(
                            advertisementIntervalMillis = 250L,
                            connectionIntervalMillis = 100L,
                            scanDutyCyclePercent = 100,
                            maxConnections = 7,
                            chunkBudgetBytes = 4 * 1024,
                        ),
                    region = region,
                )

            PowerTier.BALANCED ->
                PowerPolicy(
                    tier = tier,
                    profile =
                        PowerPolicyProfile(
                            advertisementIntervalMillis = 500L,
                            connectionIntervalMillis = 250L,
                            scanDutyCyclePercent = 50,
                            maxConnections = 5,
                            chunkBudgetBytes = 2 * 1024,
                        ),
                    region = region,
                )

            PowerTier.POWER_SAVER ->
                PowerPolicy(
                    tier = tier,
                    profile =
                        PowerPolicyProfile(
                            advertisementIntervalMillis = 1_000L,
                            connectionIntervalMillis = 500L,
                            scanDutyCyclePercent = 5,
                            maxConnections = 3,
                            chunkBudgetBytes = 512,
                        ),
                    region = region,
                )
        }
    }

    private fun clampForRegion(policy: PowerPolicy): PowerPolicy {
        if (policy.region != RegulatoryRegion.EU) {
            return policy
        }
        var advertisementIntervalMillis = policy.advertisementIntervalMillis
        var scanDutyCyclePercent = policy.scanDutyCyclePercent
        val clampWarnings = mutableListOf<String>()
        if (advertisementIntervalMillis < EU_MIN_ADVERTISEMENT_INTERVAL_MILLIS) {
            advertisementIntervalMillis = EU_MIN_ADVERTISEMENT_INTERVAL_MILLIS
            clampWarnings +=
                "advertisementIntervalMillis clamped to $EU_MIN_ADVERTISEMENT_INTERVAL_MILLIS for EU compliance"
        }
        if (scanDutyCyclePercent > EU_MAX_SCAN_DUTY_CYCLE_PERCENT) {
            scanDutyCyclePercent = EU_MAX_SCAN_DUTY_CYCLE_PERCENT
            clampWarnings +=
                "scanDutyCyclePercent clamped to $EU_MAX_SCAN_DUTY_CYCLE_PERCENT for EU compliance"
        }
        return PowerPolicy(
            tier = policy.tier,
            profile =
                PowerPolicyProfile(
                    advertisementIntervalMillis = advertisementIntervalMillis,
                    connectionIntervalMillis = policy.connectionIntervalMillis,
                    scanDutyCyclePercent = scanDutyCyclePercent,
                    maxConnections = policy.maxConnections,
                    chunkBudgetBytes = policy.chunkBudgetBytes,
                ),
            region = policy.region,
            clampWarnings = clampWarnings,
        )
    }

    private companion object {
        private const val DEFAULT_PERFORMANCE_THRESHOLD: Float = 0.80f
        private const val DEFAULT_POWER_SAVER_THRESHOLD: Float = 0.30f
        private const val DEFAULT_HYSTERESIS_BAND: Float = 0.02f
        private const val DEFAULT_BOOTSTRAP_DURATION_MILLIS: Long = 30_000L
        private const val EU_MIN_ADVERTISEMENT_INTERVAL_MILLIS: Long = 300L
        private const val EU_MAX_SCAN_DUTY_CYCLE_PERCENT: Int = 70
    }
}

private fun resolveAutomaticTier(
    nowMillis: Long,
    state: AutomaticTierState,
    config: AutomaticTierConfig,
): PowerTier {
    if (state.isBootstrapActive(nowMillis = nowMillis, config = config) || state.charging) {
        return PowerTier.PERFORMANCE
    }
    return when (state.lastTier) {
        PowerTier.PERFORMANCE -> resolveTierFromPerformance(state = state, config = config)
        PowerTier.BALANCED -> resolveTierFromBalanced(state = state, config = config)
        PowerTier.POWER_SAVER -> resolveTierFromPowerSaver(state = state, config = config)
        null -> resolveTierWithoutHistory(state = state, config = config)
    }
}

private fun resolveTierFromPerformance(
    state: AutomaticTierState,
    config: AutomaticTierConfig,
): PowerTier {
    if (!state.isBelowPerformanceDowngradeThreshold(config)) {
        return PowerTier.PERFORMANCE
    }
    return if (state.isBelowPowerSaverDowngradeThreshold(config)) {
        PowerTier.POWER_SAVER
    } else {
        PowerTier.BALANCED
    }
}

private fun resolveTierFromBalanced(
    state: AutomaticTierState,
    config: AutomaticTierConfig,
): PowerTier {
    return when {
        state.isAbovePerformanceRecoveryThreshold(config) -> PowerTier.PERFORMANCE
        state.isBelowPowerSaverDowngradeThreshold(config) -> PowerTier.POWER_SAVER
        else -> PowerTier.BALANCED
    }
}

private fun resolveTierFromPowerSaver(
    state: AutomaticTierState,
    config: AutomaticTierConfig,
): PowerTier {
    if (!state.isAbovePowerSaverRecoveryThreshold(config)) {
        return PowerTier.POWER_SAVER
    }
    return if (state.isAbovePerformanceRecoveryThreshold(config)) {
        PowerTier.PERFORMANCE
    } else {
        PowerTier.BALANCED
    }
}

private fun resolveTierWithoutHistory(
    state: AutomaticTierState,
    config: AutomaticTierConfig,
): PowerTier {
    return when {
        state.batteryLevel > config.performanceThreshold -> PowerTier.PERFORMANCE
        state.batteryLevel < config.powerSaverThreshold -> PowerTier.POWER_SAVER
        else -> PowerTier.BALANCED
    }
}

private fun AutomaticTierState.isBelowPerformanceDowngradeThreshold(
    config: AutomaticTierConfig
): Boolean {
    return batteryLevel < config.performanceThreshold - config.hysteresisBand
}

private fun AutomaticTierState.isBelowPowerSaverDowngradeThreshold(
    config: AutomaticTierConfig
): Boolean {
    return batteryLevel < config.powerSaverThreshold - config.hysteresisBand
}

private fun AutomaticTierState.isAbovePerformanceRecoveryThreshold(
    config: AutomaticTierConfig
): Boolean {
    return batteryLevel > config.performanceThreshold + config.hysteresisBand
}

private fun AutomaticTierState.isAbovePowerSaverRecoveryThreshold(
    config: AutomaticTierConfig
): Boolean {
    return batteryLevel > config.powerSaverThreshold + config.hysteresisBand
}

private fun AutomaticTierState.isBootstrapActive(
    nowMillis: Long,
    config: AutomaticTierConfig,
): Boolean {
    val startedAtMillis = bootstrapStartedAtMillis ?: return false
    return nowMillis - startedAtMillis < config.bootstrapDurationMillis
}

/**
 * Admission-control check for the connection budget a [PowerPolicy] tier expresses via
 * [PowerPolicy.maxConnections] (7/5/3 for PERFORMANCE/BALANCED/POWER_SAVER).
 *
 * Historically [PowerPolicy.maxConnections] was computed and surfaced only in diagnostics (the
 * `POWER_MODE_CHANGED` payload) with nothing actually admission-controlling new connections against
 * it -- see docs/explanation/ble-connection-robustness.md's "PowerPolicy.maxConnections is
 * currently a diagnostics-only value" note. This function is the shared, platform-agnostic decision
 * a transport adapter calls before spending a new connection slot (opening a GATT side-link or an
 * L2CAP socket) on a newly discovered peer: an already-connected peer is always allowed to
 * keep/refresh its existing connection (it isn't spending a *new* slot), but a peer with no
 * connection yet is only admitted while [activeConnectionCount] is still under [maxConnections].
 *
 * Deliberately takes plain `Int`s rather than a full [PowerPolicy] so either platform's transport
 * adapter can call it with whatever it already has on hand (Android currently reads
 * `PowerProfile.maxConnections`, itself sourced from `PowerPolicy.maxConnections` -- see
 * `platform/android/PowerMonitor.kt`) without needing a full [PowerPolicy] object at the call site.
 */
internal fun hasConnectionBudget(
    peerAlreadyConnected: Boolean,
    activeConnectionCount: Int,
    maxConnections: Int,
): Boolean {
    return peerAlreadyConnected || activeConnectionCount < maxConnections
}
