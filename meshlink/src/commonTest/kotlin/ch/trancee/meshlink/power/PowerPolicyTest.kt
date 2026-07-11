package ch.trancee.meshlink.power

import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerPolicyTest {
    @Test
    fun `automatic mode selects balanced when battery starts between the thresholds`() {
        // Arrange
        val controller =
            PowerPolicyController(
                configuredMode = PowerMode.Automatic,
                region = RegulatoryRegion.DEFAULT,
                bootstrapDurationMillis = 0L,
            )
        controller.onBatterySnapshot(level = 0.50f, isCharging = false, nowMillis = 0L)

        // Act
        val policy = controller.currentPolicy(nowMillis = 0L)

        // Assert
        assertEquals(PowerTier.BALANCED, policy.tier)
        assertEquals(500L, policy.advertisementIntervalMillis)
        assertEquals(250L, policy.connectionIntervalMillis)
        assertEquals(50, policy.scanDutyCyclePercent)
    }

    @Test
    fun `automatic mode stays in performance during bootstrap even on low battery`() {
        // Arrange
        val controller =
            PowerPolicyController(
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
        assertEquals(100L, policy.connectionIntervalMillis)
        assertEquals(100, policy.scanDutyCyclePercent)
    }

    @Test
    fun `automatic mode does not restart the bootstrap window on repeated mesh starts`() {
        // Arrange
        val controller =
            PowerPolicyController(
                configuredMode = PowerMode.Automatic,
                region = RegulatoryRegion.DEFAULT,
                bootstrapDurationMillis = 30_000L,
            )
        controller.onBatterySnapshot(level = 0.10f, isCharging = false, nowMillis = 0L)
        controller.onMeshStarted(nowMillis = 0L)
        controller.currentPolicy(nowMillis = 31_000L)

        // Act
        val policy = controller.onMeshStarted(nowMillis = 40_000L)

        // Assert
        assertEquals(PowerTier.POWER_SAVER, policy.tier)
        assertEquals(1_000L, policy.advertisementIntervalMillis)
        assertEquals(500L, policy.connectionIntervalMillis)
    }

    @Test
    fun `automatic mode drops to power saver after bootstrap expires and keeps connection interval at or above 500 ms`() {
        // Arrange
        val controller =
            PowerPolicyController(
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
        assertEquals(500L, policy.connectionIntervalMillis)
        assertTrue(
            policy.connectionIntervalMillis >= 500L,
            "Expected LOW-tier connection interval to stay at or above 500 ms",
        )
        assertEquals(5, policy.scanDutyCyclePercent)
    }

    @Test
    fun `fixed power saver mode keeps maintained connection interval at or above 500 ms`() {
        // Arrange
        val controller =
            PowerPolicyController(
                configuredMode = PowerMode.PowerSaver,
                region = RegulatoryRegion.DEFAULT,
                bootstrapDurationMillis = 0L,
            )

        // Act
        val policy = controller.currentPolicy(nowMillis = 0L)

        // Assert
        assertEquals(PowerTier.POWER_SAVER, policy.tier)
        assertEquals(500L, policy.connectionIntervalMillis)
        assertTrue(
            policy.connectionIntervalMillis >= 500L,
            "Expected fixed LOW-tier connection interval to stay at or above 500 ms",
        )
    }

    @Test
    fun `hysteresis keeps power saver active until the recovery threshold is crossed`() {
        // Arrange
        val controller =
            PowerPolicyController(
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
    fun `hysteresis keeps performance active until the downgrade threshold is crossed`() {
        // Arrange
        val controller =
            PowerPolicyController(
                configuredMode = PowerMode.Automatic,
                region = RegulatoryRegion.DEFAULT,
                bootstrapDurationMillis = 0L,
            )
        controller.onBatterySnapshot(level = 0.90f, isCharging = false, nowMillis = 0L)
        controller.currentPolicy(nowMillis = 0L)

        // Act
        controller.onBatterySnapshot(level = 0.79f, isCharging = false, nowMillis = 1_000L)
        val withinDeadZone = controller.currentPolicy(nowMillis = 1_000L)
        controller.onBatterySnapshot(level = 0.77f, isCharging = false, nowMillis = 2_000L)
        val downgraded = controller.currentPolicy(nowMillis = 2_000L)

        // Assert
        assertEquals(PowerTier.PERFORMANCE, withinDeadZone.tier)
        assertEquals(PowerTier.BALANCED, downgraded.tier)
    }

    @Test
    fun `balanced tier recovers to performance once the recovery threshold is crossed`() {
        // Arrange
        val controller =
            PowerPolicyController(
                configuredMode = PowerMode.Automatic,
                region = RegulatoryRegion.DEFAULT,
                bootstrapDurationMillis = 0L,
            )
        controller.onBatterySnapshot(level = 0.50f, isCharging = false, nowMillis = 0L)
        controller.currentPolicy(nowMillis = 0L)

        // Act
        controller.onBatterySnapshot(level = 0.81f, isCharging = false, nowMillis = 1_000L)
        val withinDeadZone = controller.currentPolicy(nowMillis = 1_000L)
        controller.onBatterySnapshot(level = 0.83f, isCharging = false, nowMillis = 2_000L)
        val recovered = controller.currentPolicy(nowMillis = 2_000L)

        // Assert
        assertEquals(PowerTier.BALANCED, withinDeadZone.tier)
        assertEquals(PowerTier.PERFORMANCE, recovered.tier)
    }

    @Test
    fun `automatic mode immediately returns to performance while charging`() {
        // Arrange
        val controller =
            PowerPolicyController(
                configuredMode = PowerMode.Automatic,
                region = RegulatoryRegion.DEFAULT,
                bootstrapDurationMillis = 0L,
            )
        controller.onBatterySnapshot(level = 0.25f, isCharging = false, nowMillis = 0L)
        controller.onMeshStarted(nowMillis = 0L)
        controller.currentPolicy(nowMillis = 0L)

        // Act
        val policy =
            controller.onBatterySnapshot(level = 0.25f, isCharging = true, nowMillis = 1_000L)

        // Assert
        assertEquals(PowerTier.PERFORMANCE, policy.tier)
        assertEquals(250L, policy.advertisementIntervalMillis)
        assertEquals(100L, policy.connectionIntervalMillis)
    }

    @Test
    fun `eu region leaves already compliant balanced settings unchanged`() {
        // Arrange
        val controller =
            PowerPolicyController(
                configuredMode = PowerMode.Balanced,
                region = RegulatoryRegion.EU,
                bootstrapDurationMillis = 0L,
            )

        // Act
        val policy = controller.currentPolicy(nowMillis = 0L)

        // Assert
        assertEquals(PowerTier.BALANCED, policy.tier)
        assertEquals(500L, policy.advertisementIntervalMillis)
        assertEquals(250L, policy.connectionIntervalMillis)
        assertEquals(50, policy.scanDutyCyclePercent)
        assertTrue(policy.clampWarnings.isEmpty())
    }

    @Test
    fun `eu region clamps the performance tier to compliant advertise and scan limits`() {
        // Arrange
        val controller =
            PowerPolicyController(
                configuredMode = PowerMode.Performance,
                region = RegulatoryRegion.EU,
                bootstrapDurationMillis = 0L,
            )

        // Act
        val policy = controller.currentPolicy(nowMillis = 0L)

        // Assert
        assertEquals(PowerTier.PERFORMANCE, policy.tier)
        assertEquals(300L, policy.advertisementIntervalMillis)
        assertEquals(100L, policy.connectionIntervalMillis)
        assertEquals(70, policy.scanDutyCyclePercent)
        assertTrue(
            policy.clampWarnings.any { warning -> warning.contains("advertisementIntervalMillis") }
        )
        assertTrue(policy.clampWarnings.any { warning -> warning.contains("scanDutyCyclePercent") })
    }

    @Test
    fun `hasConnectionBudget admits an already-connected peer regardless of active count`() {
        // Arrange / Act / Assert: an already-connected peer isn't spending a *new* slot, so it's
        // always admitted even when the active count already meets or exceeds the budget.
        assertTrue(
            hasConnectionBudget(
                peerAlreadyConnected = true,
                activeConnectionCount = 7,
                maxConnections = 7,
            )
        )
        assertTrue(
            hasConnectionBudget(
                peerAlreadyConnected = true,
                activeConnectionCount = 99,
                maxConnections = 3,
            )
        )
    }

    @Test
    fun `hasConnectionBudget admits a new peer while active count is under the budget`() {
        // Arrange / Act / Assert
        assertTrue(
            hasConnectionBudget(
                peerAlreadyConnected = false,
                activeConnectionCount = 0,
                maxConnections = 7,
            )
        )
        assertTrue(
            hasConnectionBudget(
                peerAlreadyConnected = false,
                activeConnectionCount = 6,
                maxConnections = 7,
            )
        )
    }

    @Test
    fun `hasConnectionBudget refuses a new peer once active count reaches the budget`() {
        // Arrange / Act / Assert
        assertFalse(
            hasConnectionBudget(
                peerAlreadyConnected = false,
                activeConnectionCount = 7,
                maxConnections = 7,
            )
        )
        assertFalse(
            hasConnectionBudget(
                peerAlreadyConnected = false,
                activeConnectionCount = 10,
                maxConnections = 7,
            )
        )
    }
}
