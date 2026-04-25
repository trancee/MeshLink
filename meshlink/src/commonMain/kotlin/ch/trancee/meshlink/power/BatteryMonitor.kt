package ch.trancee.meshlink.power

interface BatteryMonitor {
    fun readBatteryLevel(): Float

    val isCharging: Boolean
}
