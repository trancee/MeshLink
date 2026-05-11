package ch.trancee.meshlink.power

import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PowerPolicyTest {
    @Test
    fun `automatic mode stays in performance during bootstrap even on low battery`() {
        // Arrange
        val controller = PowerPolicyController(
            configuredMode = PowerMode.Automatic,
            region = RegulatoryRegion.DEFAULT,
            bootstrapDurationMillis = 30_000L,
        )
        controller.onBatterySnapshot(level = 0.10f, isCharging = false, nowMillis = 0L)
        controller.onMeshStarted(nowMillis = 0L)

        // Act
        val policy = controller.currentPolicy(nowMillis = 5_000L)

        // Assert
        assertEquals(PowerTier.PERFORMANCE, policy.tier)
        assertEquals(250L, policy.advertisementIntervalMillis)
        assertEquals(100, policy.scanDutyCyclePercent)
    }

    @Test
    fun `automatic mode drops to power saver after bootstrap expires`() {
        // Arrange
        val controller = PowerPolicyController(
            configuredMode = PowerMode.Automatic,
            region = RegulatoryRegion.DEFAULT,
            bootstrapDurationMillis = 30_000L,
        )
        controller.onBatterySnapshot(level = 0.10f, isCharging = false, nowMillis = 0L)
        controller.onMeshStarted(nowMillis = 0L)

        // Act
        val policy = controller.currentPolicy(nowMillis = 31_000L)

        // Assert
        assertEquals(PowerTier.POWER_SAVER, policy.tier)
        assertEquals(1_000L, policy.advertisementIntervalMillis)
        assertEquals(5, policy.scanDutyCyclePercent)
    }

    @Test
    fun `hysteresis keeps power saver active until the recovery threshold is crossed`() {
        // Arrange
        val controller = PowerPolicyController(
            configuredMode = PowerMode.Automatic,
            region = RegulatoryRegion.DEFAULT,
            bootstrapDurationMillis = 0L,
        )
        controller.onBatterySnapshot(level = 0.27f, isCharging = false, nowMillis = 0L)
        controller.onMeshStarted(nowMillis = 0L)
        controller.currentPolicy(nowMillis = 0L)

        // Act
        controller.onBatterySnapshot(level = 0.31f, isCharging = false, nowMillis = 1_000L)
        val withinDeadZone = controller.currentPolicy(nowMillis = 1_000L)
        controller.onBatterySnapshot(level = 0.33f, isCharging = false, nowMillis = 2_000L)
        val recovered = controller.currentPolicy(nowMillis = 2_000L)

        // Assert
        assertEquals(PowerTier.POWER_SAVER, withinDeadZone.tier)
        assertEquals(PowerTier.BALANCED, recovered.tier)
    }

    @Test
    fun `eu region clamps the performance tier to compliant advertise and scan limits`() {
        // Arrange
        val controller = PowerPolicyController(
            configuredMode = PowerMode.Performance,
            region = RegulatoryRegion.EU,
            bootstrapDurationMillis = 0L,
        )

        // Act
        val policy = controller.currentPolicy(nowMillis = 0L)

        // Assert
        assertEquals(PowerTier.PERFORMANCE, policy.tier)
        assertEquals(300L, policy.advertisementIntervalMillis)
        assertEquals(70, policy.scanDutyCyclePercent)
        assertTrue(policy.clampWarnings.any { warning -> warning.contains("advertisementIntervalMillis") })
        assertTrue(policy.clampWarnings.any { warning -> warning.contains("scanDutyCyclePercent") })
    }
}
