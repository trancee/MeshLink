package io.meshlink.power

/**
 * Single source of truth for all power-mode-dependent constants.
 *
 * Used by [ScanDutyCycleController][io.meshlink.transport.ScanDutyCycleController]
 * and [ConnectionLimiter] for mode-specific timing and limits.
 */
data class PowerProfile(
    val advertisingIntervalMillis: Long,
    val scanOnMillis: Long,
    val scanOffMillis: Long,
    val maxConnections: Int,
    val keepaliveIntervalMillis: Long,
    val presenceTimeoutMillis: Long,
    val sweepIntervalMillis: Long,
) {
    /** Computed scan duty cycle percentage. */
    val scanDutyPercent: Int
        get() {
            val total = scanOnMillis + scanOffMillis
            return if (total <= 0) 0 else ((scanOnMillis * 100) / total).toInt()
        }

    /** Whether this profile uses aggressive discovery (short interval, high duty). */
    val isAggressiveDiscovery: Boolean get() = scanDutyPercent >= 70

    companion object {
        val PERFORMANCE = PowerProfile(
            advertisingIntervalMillis = 250L,
            scanOnMillis = 4_000L,
            scanOffMillis = 1_000L,
            maxConnections = 8,
            keepaliveIntervalMillis = 5_000L,
            presenceTimeoutMillis = 2_500L,
            sweepIntervalMillis = 1_250L,
        )

        val BALANCED = PowerProfile(
            advertisingIntervalMillis = 500L,
            scanOnMillis = 3_000L,
            scanOffMillis = 3_000L,
            maxConnections = 4,
            keepaliveIntervalMillis = 15_000L,
            presenceTimeoutMillis = 5_000L,
            sweepIntervalMillis = 2_500L,
        )

        val POWER_SAVER = PowerProfile(
            advertisingIntervalMillis = 1_000L,
            scanOnMillis = 1_000L,
            scanOffMillis = 5_000L,
            maxConnections = 2,
            keepaliveIntervalMillis = 30_000L,
            presenceTimeoutMillis = 10_000L,
            sweepIntervalMillis = 5_000L,
        )

        fun forMode(mode: PowerMode): PowerProfile = when (mode) {
            PowerMode.PERFORMANCE -> PERFORMANCE
            PowerMode.BALANCED -> BALANCED
            PowerMode.POWER_SAVER -> POWER_SAVER
        }
    }
}
