package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.transport.BlePowerMode

internal class IosPowerProfile internal constructor(internal val discoveryPowerMode: BlePowerMode)

internal object IosPowerMonitor {
    internal fun defaultProfile(): IosPowerProfile {
        return profileForTier(PowerTier.BALANCED)
    }

    internal fun profileFor(policy: PowerPolicy): IosPowerProfile {
        return profileForTier(policy.tier)
    }

    private fun profileForTier(tier: PowerTier): IosPowerProfile {
        return when (tier) {
            PowerTier.PERFORMANCE -> IosPowerProfile(BlePowerMode.PERFORMANCE)
            PowerTier.BALANCED -> IosPowerProfile(BlePowerMode.BALANCED)
            PowerTier.POWER_SAVER -> IosPowerProfile(BlePowerMode.POWER_SAVER)
        }
    }
}
