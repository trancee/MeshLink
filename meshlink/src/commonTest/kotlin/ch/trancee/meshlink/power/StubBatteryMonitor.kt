package ch.trancee.meshlink.power

class StubBatteryMonitor(var level: Float = 1.0f, override var isCharging: Boolean = false) :
    BatteryMonitor {
    override fun readBatteryLevel(): Float = level

    override fun reportBattery(percent: Float, isCharging: Boolean) {
        level = percent
        this.isCharging = isCharging
    }
}
