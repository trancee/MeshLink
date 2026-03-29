package io.meshlink.power

/**
 * Determines BLE advertising and scan intervals based on power mode.
 * Controls how aggressively the node advertises its presence and scans for peers.
 */
class AdvertisingPolicy(
    private val performanceAdvIntervalMillis: Long = 250L,
    private val balancedAdvIntervalMillis: Long = 500L,
    private val powerSaverAdvIntervalMillis: Long = 1000L,
    private val performanceScanDutyPercent: Int = 90,
    private val balancedScanDutyPercent: Int = 50,
    private val powerSaverScanDutyPercent: Int = 15,
) {
    enum class PowerMode { PERFORMANCE, BALANCED, POWER_SAVER }

    /**
     * Advertising interval for the given power mode.
     */
    fun advertisingIntervalMillis(mode: PowerMode): Long = when (mode) {
        PowerMode.PERFORMANCE -> performanceAdvIntervalMillis
        PowerMode.BALANCED -> balancedAdvIntervalMillis
        PowerMode.POWER_SAVER -> powerSaverAdvIntervalMillis
    }

    /**
     * Scan duty cycle percentage for the given power mode.
     * Represents the percentage of time the scanner is active.
     */
    fun scanDutyPercent(mode: PowerMode): Int = when (mode) {
        PowerMode.PERFORMANCE -> performanceScanDutyPercent
        PowerMode.BALANCED -> balancedScanDutyPercent
        PowerMode.POWER_SAVER -> powerSaverScanDutyPercent
    }

    /**
     * Scan window duration for a given scan interval.
     * scanWindow = scanInterval × (dutyPercent / 100)
     */
    fun scanWindowMillis(mode: PowerMode, scanIntervalMillis: Long): Long =
        scanIntervalMillis * scanDutyPercent(mode) / 100

    /**
     * Whether the current mode should use aggressive discovery
     * (short interval, high duty cycle).
     */
    fun isAggressiveDiscovery(mode: PowerMode): Boolean =
        mode == PowerMode.PERFORMANCE
}
