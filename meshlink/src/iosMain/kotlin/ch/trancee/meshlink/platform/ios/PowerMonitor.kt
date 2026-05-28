package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.transport.BlePowerMode

internal class PowerProfile internal constructor(internal val discoveryPowerMode: BlePowerMode)

internal object PowerMonitor {
    internal fun defaultProfile(): PowerProfile {
        return profileForTier(PowerTier.BALANCED)
    }

    internal fun profileFor(policy: PowerPolicy): PowerProfile {
        return profileForTier(policy.tier)
    }

    private fun profileForTier(tier: PowerTier): PowerProfile {
        return when (tier) {
            PowerTier.PERFORMANCE -> PowerProfile(BlePowerMode.PERFORMANCE)
            PowerTier.BALANCED -> PowerProfile(BlePowerMode.BALANCED)
            PowerTier.POWER_SAVER -> PowerProfile(BlePowerMode.POWER_SAVER)
        }
    }
}
