package ch.trancee.meshlink.power

import ch.trancee.meshlink.api.BatterySnapshot

internal interface BatteryMonitor {
    fun start(onSnapshot: (BatterySnapshot) -> Unit)

    fun stop()
}

internal object NoOpBatteryMonitor : BatteryMonitor {
    override fun start(onSnapshot: (BatterySnapshot) -> Unit) = Unit

    override fun stop() = Unit
}
