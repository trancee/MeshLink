package ch.trancee.meshlink.power

import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion

internal enum class PowerTier {
    PERFORMANCE,
    BALANCED,
    POWER_SAVER,
}

internal class PowerPolicy
internal constructor(
    internal val tier: PowerTier,
    internal val advertisementIntervalMillis: Long,
    internal val connectionIntervalMillis: Long,
    internal val scanDutyCyclePercent: Int,
    internal val maxConnections: Int,
    internal val chunkBudgetBytes: Int,
    internal val region: RegulatoryRegion,
    clampWarnings: List<String> = emptyList(),
) {
    internal val clampWarnings: List<String> = clampWarnings.toList()
}

internal class PowerPolicyController
internal constructor(
    private val configuredMode: PowerMode,
    private val region: RegulatoryRegion,
    private val performanceThreshold: Float = DEFAULT_PERFORMANCE_THRESHOLD,
    private val powerSaverThreshold: Float = DEFAULT_POWER_SAVER_THRESHOLD,
    private val hysteresisBand: Float = DEFAULT_HYSTERESIS_BAND,
    private val bootstrapDurationMillis: Long = DEFAULT_BOOTSTRAP_DURATION_MILLIS,
) {
    private var batteryLevel: Float = 1.0f
    private var charging: Boolean = false
    private var bootstrapStartedAtMillis: Long? = null
    private var lastTier: PowerTier? = null

    internal fun onMeshStarted(nowMillis: Long): PowerPolicy {
        if (bootstrapStartedAtMillis == null) {
            bootstrapStartedAtMillis = nowMillis
        }
        return currentPolicy(nowMillis)
    }

    internal fun onBatterySnapshot(
        level: Float,
        isCharging: Boolean,
        nowMillis: Long,
    ): PowerPolicy {
        batteryLevel = level.coerceIn(0.0f, 1.0f)
        charging = isCharging
        return currentPolicy(nowMillis)
    }

    internal fun currentPolicy(nowMillis: Long): PowerPolicy {
        val tier = resolveTier(nowMillis)
        lastTier = tier
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
        if (isBootstrapActive(nowMillis) || charging) {
            return PowerTier.PERFORMANCE
        }
        return when (lastTier) {
            PowerTier.PERFORMANCE -> resolveTierFromPerformance()
            PowerTier.BALANCED -> resolveTierFromBalanced()
            PowerTier.POWER_SAVER -> resolveTierFromPowerSaver()
            null -> resolveTierWithoutHistory()
        }
    }

    private fun resolveTierFromPerformance(): PowerTier {
        if (!isBelowPerformanceDowngradeThreshold()) {
            return PowerTier.PERFORMANCE
        }
        return if (isBelowPowerSaverDowngradeThreshold()) {
            PowerTier.POWER_SAVER
        } else {
            PowerTier.BALANCED
        }
    }

    private fun resolveTierFromBalanced(): PowerTier {
        return when {
            isAbovePerformanceRecoveryThreshold() -> PowerTier.PERFORMANCE
            isBelowPowerSaverDowngradeThreshold() -> PowerTier.POWER_SAVER
            else -> PowerTier.BALANCED
        }
    }

    private fun resolveTierFromPowerSaver(): PowerTier {
        if (!isAbovePowerSaverRecoveryThreshold()) {
            return PowerTier.POWER_SAVER
        }
        return if (isAbovePerformanceRecoveryThreshold()) {
            PowerTier.PERFORMANCE
        } else {
            PowerTier.BALANCED
        }
    }

    private fun resolveTierWithoutHistory(): PowerTier {
        return when {
            batteryLevel > performanceThreshold -> PowerTier.PERFORMANCE
            batteryLevel < powerSaverThreshold -> PowerTier.POWER_SAVER
            else -> PowerTier.BALANCED
        }
    }

    private fun isBelowPerformanceDowngradeThreshold(): Boolean {
        return batteryLevel < performanceThreshold - hysteresisBand
    }

    private fun isBelowPowerSaverDowngradeThreshold(): Boolean {
        return batteryLevel < powerSaverThreshold - hysteresisBand
    }

    private fun isAbovePerformanceRecoveryThreshold(): Boolean {
        return batteryLevel > performanceThreshold + hysteresisBand
    }

    private fun isAbovePowerSaverRecoveryThreshold(): Boolean {
        return batteryLevel > powerSaverThreshold + hysteresisBand
    }

    private fun isBootstrapActive(nowMillis: Long): Boolean {
        val startedAtMillis = bootstrapStartedAtMillis ?: return false
        return nowMillis - startedAtMillis < bootstrapDurationMillis
    }

    private fun basePolicyFor(tier: PowerTier): PowerPolicy {
        return when (tier) {
            PowerTier.PERFORMANCE ->
                PowerPolicy(
                    tier = tier,
                    advertisementIntervalMillis = 250L,
                    connectionIntervalMillis = 100L,
                    scanDutyCyclePercent = 100,
                    maxConnections = 7,
                    chunkBudgetBytes = 4 * 1024,
                    region = region,
                )

            PowerTier.BALANCED ->
                PowerPolicy(
                    tier = tier,
                    advertisementIntervalMillis = 500L,
                    connectionIntervalMillis = 250L,
                    scanDutyCyclePercent = 50,
                    maxConnections = 5,
                    chunkBudgetBytes = 2 * 1024,
                    region = region,
                )

            PowerTier.POWER_SAVER ->
                PowerPolicy(
                    tier = tier,
                    advertisementIntervalMillis = 1_000L,
                    connectionIntervalMillis = 500L,
                    scanDutyCyclePercent = 5,
                    maxConnections = 3,
                    chunkBudgetBytes = 512,
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
            advertisementIntervalMillis = advertisementIntervalMillis,
            connectionIntervalMillis = policy.connectionIntervalMillis,
            scanDutyCyclePercent = scanDutyCyclePercent,
            maxConnections = policy.maxConnections,
            chunkBudgetBytes = policy.chunkBudgetBytes,
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
