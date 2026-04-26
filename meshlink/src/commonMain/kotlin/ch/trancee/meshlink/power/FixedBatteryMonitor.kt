package ch.trancee.meshlink.power

/**
 * [BatteryMonitor] implementation that always reports a fixed battery level.
 *
 * Used by [ch.trancee.meshlink.transport.MeshLinkService] during S04 integration testing so the
 * engine has a concrete monitor without requiring Android battery APIs. A real device-battery
 * integration is wired in M004.
 *
 * @param level Fixed battery level to report, in the range [0.0, 1.0]. Defaults to full (1.0f).
 */
internal class FixedBatteryMonitor(private val level: Float = 1.0f) : BatteryMonitor {
    override fun readBatteryLevel(): Float = level

    override val isCharging: Boolean = false
}
