package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.transport.BlePowerMode

internal class PowerProfile
internal constructor(
    internal val discoveryPowerMode: BlePowerMode,
    // Mirrors the live PowerPolicy.maxConnections budget (see power/PowerPolicy.kt) so platform
    // code that only has a PowerProfile in hand (e.g. the discovery admission gate in
    // BleTransportAdapterDiscoverySupport) can read the same per-tier connection budget without
    // needing its own copy of the PowerPolicy. defaultProfile()'s bootstrap value (used before any
    // PowerPolicy has been supplied) matches PowerPolicy's own BALANCED-tier default.
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
                PowerProfile(BlePowerMode.PERFORMANCE, maxConnections = maxConnections)
            PowerTier.BALANCED ->
                PowerProfile(BlePowerMode.BALANCED, maxConnections = maxConnections)
            PowerTier.POWER_SAVER ->
                PowerProfile(BlePowerMode.POWER_SAVER, maxConnections = maxConnections)
        }
    }
}
