package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.power.PowerPolicyController
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.transport.BlePowerMode
import kotlin.test.Test
import kotlin.test.assertEquals

class PowerMonitorTest {
    @Test
    fun `defaultProfile returns the balanced platform profile`() {
        // Arrange / Act
        val profile = PowerMonitor.defaultProfile()

        // Assert
        assertEquals(BlePowerMode.BALANCED, profile.discoveryPowerMode)
    }

    @Test
    fun `profileFor maps the performance tier to the performance discovery mode`() {
        // Arrange
        val controller =
            PowerPolicyController(
                configuredMode = PowerMode.Performance,
                region = RegulatoryRegion.DEFAULT,
                bootstrapDurationMillis = 0L,
            )
        val policy = controller.currentPolicy(nowMillis = 0L)

        // Act
        val profile = PowerMonitor.profileFor(policy)

        // Assert
        assertEquals(PowerTier.PERFORMANCE, policy.tier)
        assertEquals(BlePowerMode.PERFORMANCE, profile.discoveryPowerMode)
    }

    @Test
    fun `profileFor maps the power saver tier to the power saver discovery mode`() {
        // Arrange
        val controller =
            PowerPolicyController(
                configuredMode = PowerMode.PowerSaver,
                region = RegulatoryRegion.DEFAULT,
                bootstrapDurationMillis = 0L,
            )
        val policy = controller.currentPolicy(nowMillis = 0L)

        // Act
        val profile = PowerMonitor.profileFor(policy)

        // Assert
        assertEquals(PowerTier.POWER_SAVER, policy.tier)
        assertEquals(BlePowerMode.POWER_SAVER, profile.discoveryPowerMode)
    }
}
