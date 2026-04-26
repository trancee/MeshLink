package ch.trancee.meshlink.power

internal interface BatteryMonitor {
    fun readBatteryLevel(): Float

    val isCharging: Boolean
}
