package io.meshlink.power

import io.meshlink.util.currentTimeMillis

enum class PowerMode { PERFORMANCE, BALANCED, POWER_SAVER }

class PowerModeEngine(
    private val hysteresisMillis: Long = 30_000,
    private val deadZonePercent: Int = 2,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    private var currentMode: PowerMode = PowerMode.PERFORMANCE
    private var pendingDowngrade: PowerMode? = null
    private var downgradeRequestedAt: Long = 0

    fun update(batteryPercent: Int, isCharging: Boolean): PowerMode {
        // Charging override
        if (isCharging) {
            pendingDowngrade = null
            currentMode = PowerMode.PERFORMANCE
            return currentMode
        }

        val targetMode = modeForBattery(batteryPercent, currentMode)

        // Upward transition (higher power) → immediate
        if (targetMode.ordinal < currentMode.ordinal) {
            pendingDowngrade = null
            currentMode = targetMode
            return currentMode
        }

        // Same mode → clear pending
        if (targetMode == currentMode) {
            pendingDowngrade = null
            return currentMode
        }

        // Downward transition → apply hysteresis
        val now = clock()
        if (pendingDowngrade != targetMode) {
            pendingDowngrade = targetMode
            downgradeRequestedAt = now
            return currentMode
        }

        // Check if hysteresis period has elapsed
        if (now - downgradeRequestedAt >= hysteresisMillis) {
            currentMode = targetMode
            pendingDowngrade = null
        }
        return currentMode
    }

    /**
     * Determine target power mode with a ±[deadZonePercent] band around thresholds.
     *
     * Downward crossings require the battery to drop [deadZonePercent] below the
     * threshold; upward crossings require it to rise [deadZonePercent] above.
     * This prevents flapping when battery oscillates near a boundary.
     */
    private fun modeForBattery(percent: Int, current: PowerMode): PowerMode {
        val dz = deadZonePercent
        return when (current) {
            PowerMode.PERFORMANCE -> when {
                percent > 80 - dz -> PowerMode.PERFORMANCE
                percent >= 30 - dz -> PowerMode.BALANCED
                else -> PowerMode.POWER_SAVER
            }
            PowerMode.BALANCED -> when {
                percent > 80 + dz -> PowerMode.PERFORMANCE
                percent >= 30 - dz -> PowerMode.BALANCED
                else -> PowerMode.POWER_SAVER
            }
            PowerMode.POWER_SAVER -> when {
                percent > 80 + dz -> PowerMode.PERFORMANCE
                percent >= 30 + dz -> PowerMode.BALANCED
                else -> PowerMode.POWER_SAVER
            }
        }
    }
}
