package io.meshlink.power

/**
 * Cross-platform battery monitoring for power-aware mesh operation.
 */
interface BatteryMonitor {
    /** Current battery level as percentage (0-100), or -1 if unknown. */
    fun batteryLevel(): Int

    /** Whether the device is currently charging. */
    fun isCharging(): Boolean

    /** Whether battery monitoring is available on this platform. */
    fun isAvailable(): Boolean
}

expect fun BatteryMonitor(): BatteryMonitor
