package ch.trancee.meshlink.power

internal interface BatteryMonitor {
    fun readBatteryLevel(): Float

    val isCharging: Boolean

    /**
     * Reports an externally-observed battery state. Implementations that support mutation (e.g.
     * [StubBatteryMonitor][ch.trancee.meshlink.power.StubBatteryMonitor] in tests, or headless
     * platforms) override this to store the values for the next [readBatteryLevel]/[isCharging]
     * poll. Default no-op for fixed/hardware-backed monitors.
     */
    fun reportBattery(percent: Float, isCharging: Boolean) {
        // Default no-op — hardware-backed monitors ignore external reports.
    }
}
