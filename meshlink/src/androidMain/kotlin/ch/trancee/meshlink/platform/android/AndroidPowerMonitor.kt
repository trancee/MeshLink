package ch.trancee.meshlink.platform.android

import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.transport.BlePowerMode

internal class AndroidPowerProfile
internal constructor(
    internal val discoveryPowerMode: BlePowerMode,
    internal val advertiseMode: Int,
    internal val txPowerLevel: Int,
    internal val scanMode: Int,
)

internal object AndroidPowerMonitor {
    internal fun defaultProfile(): AndroidPowerProfile {
        return profileForTier(PowerTier.BALANCED)
    }

    internal fun profileFor(policy: PowerPolicy): AndroidPowerProfile {
        return profileForTier(policy.tier)
    }

    private fun profileForTier(tier: PowerTier): AndroidPowerProfile {
        return when (tier) {
            PowerTier.PERFORMANCE ->
                AndroidPowerProfile(
                    discoveryPowerMode = BlePowerMode.PERFORMANCE,
                    advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
                    txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
                    scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
                )

            PowerTier.BALANCED ->
                AndroidPowerProfile(
                    discoveryPowerMode = BlePowerMode.BALANCED,
                    advertiseMode = AdvertiseSettings.ADVERTISE_MODE_BALANCED,
                    txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
                    scanMode = ScanSettings.SCAN_MODE_BALANCED,
                )

            PowerTier.POWER_SAVER ->
                AndroidPowerProfile(
                    discoveryPowerMode = BlePowerMode.POWER_SAVER,
                    advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
                    txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
                    scanMode = ScanSettings.SCAN_MODE_LOW_POWER,
                )
        }
    }
}
