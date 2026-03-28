package io.meshlink.power

/**
 * Single source of truth for all power-mode-dependent constants.
 *
 * Replaces scattered `when(mode)` lookups in [AdvertisingPolicy],
 * [ScanDutyCycleController][io.meshlink.transport.ScanDutyCycleController],
 * and [ConnectionLimiter] with a data-driven profile per mode.
 */
data class PowerProfile(
    val advertisingIntervalMs: Long,
    val scanOnMs: Long,
    val scanOffMs: Long,
    val maxConnections: Int,
    val gossipMultiplier: Double,
) {
    /** Computed scan duty cycle percentage. */
    val scanDutyPercent: Int
        get() {
            val total = scanOnMs + scanOffMs
            return if (total <= 0) 0 else ((scanOnMs * 100) / total).toInt()
        }

    /** Whether this profile uses aggressive discovery (short interval, high duty). */
    val isAggressiveDiscovery: Boolean get() = scanDutyPercent >= 70

    companion object {
        val PERFORMANCE = PowerProfile(
            advertisingIntervalMs = 250L,
            scanOnMs = 4_000L,
            scanOffMs = 1_000L,
            maxConnections = 8,
            gossipMultiplier = 1.0,
        )

        val BALANCED = PowerProfile(
            advertisingIntervalMs = 500L,
            scanOnMs = 3_000L,
            scanOffMs = 3_000L,
            maxConnections = 4,
            gossipMultiplier = 1.5,
        )

        val POWER_SAVER = PowerProfile(
            advertisingIntervalMs = 1_000L,
            scanOnMs = 1_000L,
            scanOffMs = 5_000L,
            maxConnections = 1,
            gossipMultiplier = 3.0,
        )

        fun forMode(mode: PowerMode): PowerProfile = when (mode) {
            PowerMode.PERFORMANCE -> PERFORMANCE
            PowerMode.BALANCED -> BALANCED
            PowerMode.POWER_SAVER -> POWER_SAVER
        }
    }
}
