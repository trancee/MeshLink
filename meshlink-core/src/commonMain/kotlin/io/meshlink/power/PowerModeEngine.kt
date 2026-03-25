package io.meshlink.power

enum class PowerMode { PERFORMANCE, BALANCED, POWER_SAVER }

class PowerModeEngine(
    private val hysteresisMs: Long = 30_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
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

        val targetMode = modeForBattery(batteryPercent)

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
        if (now - downgradeRequestedAt >= hysteresisMs) {
            currentMode = targetMode
            pendingDowngrade = null
        }
        return currentMode
    }

    private fun modeForBattery(percent: Int): PowerMode = when {
        percent > 80 -> PowerMode.PERFORMANCE
        percent >= 30 -> PowerMode.BALANCED
        else -> PowerMode.POWER_SAVER
    }
}
