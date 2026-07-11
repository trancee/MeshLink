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
        // No PowerPolicy is available before one is ever supplied (this is the bootstrap default),
        // so there's nothing to compare against except BALANCED's documented literal budget -- see
        // PowerPolicy.kt's basePolicyFor(BALANCED).maxConnections, which this mirrors.
        @Suppress("MagicNumber") val expectedBootstrapMaxConnections = 5
        assertEquals(expectedBootstrapMaxConnections, profile.maxConnections)
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
        // Confirms the live PowerPolicy.maxConnections budget actually reaches the platform
        // profile, rather than re-asserting the PERFORMANCE tier's literal (already covered by
        // PowerPolicyTest) as a second, redundant magic number here.
        assertEquals(policy.maxConnections, profile.maxConnections)
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
        // See the PERFORMANCE-tier test above for why this doesn't also re-assert the literal.
        assertEquals(policy.maxConnections, profile.maxConnections)
    }
}
