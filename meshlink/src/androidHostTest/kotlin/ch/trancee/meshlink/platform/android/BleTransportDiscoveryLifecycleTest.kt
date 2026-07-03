package ch.trancee.meshlink.platform.android

import android.bluetooth.le.AdvertiseCallback
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

    @Test
    fun advertiseFailureWithRetryableErrorSchedulesARetryThatRestartsAdvertising(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act
        fixture.lifecycle.advertiseCallback.onStartFailure(
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
        )

        // Assert
        assertEquals(1, fixture.scheduledRetryDelaysMillis.size)
        fixture.runScheduledRetries()
        assertEquals(2, fixture.startAdvertisingCalls)
    }

    @Test
    fun advertiseFailureWithNonRetryableErrorDoesNotScheduleARetry(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act
        fixture.lifecycle.advertiseCallback.onStartFailure(
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE
        )

        // Assert
        assertTrue(fixture.scheduledRetryDelaysMillis.isEmpty())
    }

    @Test
    fun advertiseRetryStopsAfterTheMaximumAttemptCount(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act
        repeat(5) {
            fixture.lifecycle.advertiseCallback.onStartFailure(
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
            )
            fixture.runScheduledRetries()
        }

        // Assert
        assertEquals(3, fixture.scheduledRetryDelaysMillis.size)
    }

    @Test
    fun stopCancelsPendingAdvertiseRetries(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)
        fixture.lifecycle.advertiseCallback.onStartFailure(
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
        )

        // Act
        fixture.lifecycle.stop(fixture.hardware)
        fixture.runScheduledRetries()

        // Assert
        assertEquals(1, fixture.startAdvertisingCalls)
    }

    @Test
    fun retryStopsAdvertisingBeforeStartingAgainToAvoidAlreadyStartedFailures(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)
        fixture.lifecycle.advertiseCallback.onStartFailure(
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
        )

        // Act
        fixture.runScheduledRetries()

        // Assert
        assertEquals(2, fixture.stopAdvertisingCalls)
        assertEquals(2, fixture.startAdvertisingCalls)
    }

    @Test
    fun advertiseErrorCodeNameMapsKnownAndUnknownErrorCodes(): Unit {
        // Assert
        assertEquals(
            "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS",
            advertiseErrorCodeName(AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS),
        )
        assertEquals(
            "ADVERTISE_FAILED_DATA_TOO_LARGE",
            advertiseErrorCodeName(AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE),
        )
        assertEquals(
            "ADVERTISE_FAILED_ALREADY_STARTED",
            advertiseErrorCodeName(AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED),
        )
        assertEquals(
            "ADVERTISE_FAILED_FEATURE_UNSUPPORTED",
            advertiseErrorCodeName(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED),
        )
        assertEquals(
            "ADVERTISE_FAILED_INTERNAL_ERROR",
            advertiseErrorCodeName(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR),
        )
        assertEquals("ADVERTISE_FAILED_UNKNOWN", advertiseErrorCodeName(-1))
    }

    @Test
    fun advertiseFailureNotifiesTheDiagnosticsCallbackWithErrorDetails(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act
        fixture.lifecycle.advertiseCallback.onStartFailure(
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
        )

        // Assert
        val call = fixture.advertiseFailedCalls.single()
        assertEquals(AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS, call.errorCode)
        assertEquals("ADVERTISE_FAILED_TOO_MANY_ADVERTISERS", call.errorName)
        assertTrue(call.willRetry)
        assertEquals(1, call.attempt)
    }

    @Test
    fun advertiseFailureNotifiesNoRetryOnceMaxAttemptsAreExhausted(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act
        repeat(4) {
            fixture.lifecycle.advertiseCallback.onStartFailure(
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
            )
            fixture.runScheduledRetries()
        }

        // Assert
        assertFalse(fixture.advertiseFailedCalls.last().willRetry)
    }
}

private class BleTransportDiscoveryLifecycleFixture {
    var stopScanCalls: Int = 0
    var startScanCalls: Int = 0
    var stopAdvertisingCalls: Int = 0
    var startAdvertisingCalls: Int = 0
    val startedAdvertisingPayloads =
        mutableListOf<ch.trancee.meshlink.transport.BleDiscoveryPayload>()
    val scheduledRetryDelaysMillis = mutableListOf<Long>()
    private val pendingRetries = mutableListOf<() -> Unit>()
    val advertiseFailedCalls = mutableListOf<AdvertiseFailedCall>()

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
            foreignScanIgnoredCount = { 0 },
            log = {},
            scheduleAdvertiseRetry = { delayMillis, retry ->
                scheduledRetryDelaysMillis += delayMillis
                pendingRetries += retry
            },
            onAdvertiseFailed = { errorCode, errorName, willRetry, attempt ->
                advertiseFailedCalls +=
                    AdvertiseFailedCall(
                        errorCode = errorCode,
                        errorName = errorName,
                        willRetry = willRetry,
                        attempt = attempt,
                    )
            },
        )

    fun runScheduledRetries(): Unit {
        val retries = pendingRetries.toList()
        pendingRetries.clear()
        retries.forEach { it() }
    }
}

private data class AdvertiseFailedCall(
    val errorCode: Int,
    val errorName: String,
    val willRetry: Boolean,
    val attempt: Int,
)

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
