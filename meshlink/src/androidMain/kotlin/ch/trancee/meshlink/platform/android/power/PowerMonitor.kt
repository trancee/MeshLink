package ch.trancee.meshlink.platform.android.power

import android.bluetooth.BluetoothGatt
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.transport.BlePowerMode

internal class PowerProfile
internal constructor(
    internal val discoveryPowerMode: BlePowerMode,
    internal val advertiseMode: Int,
    internal val txPowerLevel: Int,
    internal val scanMode: Int,
    internal val connectionPriority: Int,
    // Mirrors the live PowerPolicy.maxConnections budget (see power/PowerPolicy.kt) so platform
    // code that only has a PowerProfile in hand (e.g. BleTransportAdapterScanSupport's connection
    // admission gate) can read the same per-tier connection budget without needing its own copy of
    // the PowerPolicy. defaultProfile()'s bootstrap value (used before any PowerPolicy has been
    // supplied) matches PowerPolicy's own BALANCED-tier default.
    internal val maxConnections: Int,
)

internal object PowerMonitor {
    internal fun defaultProfile(): PowerProfile {
        return profileForTier(PowerTier.BALANCED, maxConnections = 5)
    }

    internal fun profileFor(policy: PowerPolicy): PowerProfile {
        return profileForTier(policy.tier, maxConnections = policy.maxConnections)
    }

    private fun profileForTier(tier: PowerTier, maxConnections: Int): PowerProfile {
        return when (tier) {
            PowerTier.PERFORMANCE ->
                PowerProfile(
                    discoveryPowerMode = BlePowerMode.PERFORMANCE,
                    advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
                    txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
                    scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
                    // Short connection interval (7.5-15ms) with a ~2s supervision timeout. Only
                    // appropriate while the radio is otherwise idle (bootstrap/foreground),
                    // since a busy/dense radio environment can exceed 2s between successful
                    // connection events and trigger a spurious disconnect.
                    connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_HIGH,
                    maxConnections = maxConnections,
                )

            PowerTier.BALANCED ->
                PowerProfile(
                    discoveryPowerMode = BlePowerMode.BALANCED,
                    advertiseMode = AdvertiseSettings.ADVERTISE_MODE_BALANCED,
                    txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
                    scanMode = ScanSettings.SCAN_MODE_BALANCED,
                    // ~30-50ms interval, ~5s supervision timeout: tolerates ordinary radio
                    // contention in a dense environment without sacrificing much latency.
                    connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
                    maxConnections = maxConnections,
                )

            PowerTier.POWER_SAVER ->
                PowerProfile(
                    discoveryPowerMode = BlePowerMode.POWER_SAVER,
                    advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
                    txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
                    scanMode = ScanSettings.SCAN_MODE_LOW_POWER,
                    // ~100-250ms interval, ~20s supervision timeout: maximizes tolerance for
                    // radio congestion at the cost of latency, matching the power tier's intent.
                    connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER,
                    maxConnections = maxConnections,
                )
        }
    }
}
