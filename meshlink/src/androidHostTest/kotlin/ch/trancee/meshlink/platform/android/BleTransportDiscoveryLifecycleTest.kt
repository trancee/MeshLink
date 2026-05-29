package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerPolicyProfile
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.transport.BlePowerMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleTransportDiscoveryLifecycleTest {
    @Test
    fun refreshStartsScanningAndAdvertisingWhenTheTransportIsStarted(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.updateL2capPsm(192u)

        // Act
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Assert
        assertEquals(1, fixture.stopScanCalls)
        assertEquals(1, fixture.stopAdvertisingCalls)
        assertEquals(1, fixture.startScanCalls)
        assertEquals(1, fixture.startAdvertisingCalls)
        assertEquals(192u, fixture.startedAdvertisingPayloads.single().l2capPsm)
        assertEquals(BlePowerMode.BALANCED, fixture.startedAdvertisingPayloads.single().powerMode)
    }

    @Test
    fun setSuspendedStopsDiscoveryWithoutRestartingScanOrAdvertising(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()

        // Act
        fixture.lifecycle.setSuspended(
            suspended = true,
            started = true,
            hardware = fixture.hardware,
        )

        // Assert
        assertTrue(fixture.lifecycle.isDiscoverySuspended)
        assertEquals(1, fixture.stopScanCalls)
        assertEquals(1, fixture.stopAdvertisingCalls)
        assertEquals(0, fixture.startScanCalls)
        assertEquals(0, fixture.startAdvertisingCalls)
    }

    @Test
    fun updatePowerPolicyKeepsTheAdvertisedPsmWhileRestartingDiscovery(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.updateL2capPsm(193u)

        // Act
        fixture.lifecycle.updatePowerPolicy(
            policy = powerPolicy(tier = PowerTier.POWER_SAVER),
            started = true,
            hardware = fixture.hardware,
        )

        // Assert
        assertFalse(fixture.lifecycle.isDiscoverySuspended)
        assertEquals(BlePowerMode.POWER_SAVER, fixture.lifecycle.currentDiscoveryPayload.powerMode)
        assertEquals(193u, fixture.lifecycle.currentDiscoveryPayload.l2capPsm)
        assertEquals(
            BlePowerMode.POWER_SAVER,
            fixture.startedAdvertisingPayloads.single().powerMode,
        )
        assertEquals(193u, fixture.startedAdvertisingPayloads.single().l2capPsm)
    }
}

private class BleTransportDiscoveryLifecycleFixture {
    var stopScanCalls: Int = 0
    var startScanCalls: Int = 0
    var stopAdvertisingCalls: Int = 0
    var startAdvertisingCalls: Int = 0
    val startedAdvertisingPayloads =
        mutableListOf<ch.trancee.meshlink.transport.BleDiscoveryPayload>()

    val hardware =
        BleTransportDiscoveryHardware(
            hasScanner = true,
            hasAdvertiser = true,
            stopScan = { stopScanCalls += 1 },
            startScan = { _, _ -> startScanCalls += 1 },
            stopAdvertising = { stopAdvertisingCalls += 1 },
            startAdvertising = { _, payload, _ ->
                startAdvertisingCalls += 1
                startedAdvertisingPayloads += payload
            },
        )

    val lifecycle =
        BleTransportDiscoveryLifecycle(
            appId = "demo.meshlink.android.transport",
            localKeyHash = ByteArray(12) { index -> (index + 1).toByte() },
            handleScanResult = {},
            ensurePermissionsGranted = {},
            log = {},
        )
}

private fun powerPolicy(tier: PowerTier): PowerPolicy {
    return PowerPolicy(
        tier = tier,
        profile =
            PowerPolicyProfile(
                advertisementIntervalMillis = 1_000L,
                connectionIntervalMillis = 500L,
                scanDutyCyclePercent = 5,
                maxConnections = 3,
                chunkBudgetBytes = 512,
            ),
        region = RegulatoryRegion.EU,
    )
}
