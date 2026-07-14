package ch.trancee.meshlink.platform.android

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.ScanCallback
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.platform.android.scan.BleTransportDiscoveryHardware
import ch.trancee.meshlink.platform.android.scan.BleTransportDiscoveryLifecycle
import ch.trancee.meshlink.platform.android.scan.MAX_WEDGED_SCAN_RESTARTS_BEFORE_ESCALATION
import ch.trancee.meshlink.platform.android.scan.SCAN_WATCHDOG_IDLE_THRESHOLD_MILLIS
import ch.trancee.meshlink.platform.android.scan.advertiseErrorCodeName
import ch.trancee.meshlink.platform.android.scan.scanErrorCodeName
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerPolicyProfile
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.transport.BlePowerMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleTransportDiscoveryLifecycleTest {
    private companion object {
        const val RATE_LIMIT_RETRY_DELAY_1_MILLIS = 2_000L
        const val RATE_LIMIT_RETRY_DELAY_2_MILLIS = 4_000L
        const val RATE_LIMIT_RETRY_DELAY_3_MILLIS = 8_000L
        const val RATE_LIMIT_RETRY_DELAY_4_MILLIS = 16_000L
        const val RATE_LIMIT_RETRY_DELAY_CAPPED_MILLIS = 30_000L
        const val CONCURRENCY_STRESS_ITERATIONS_PER_THREAD = 500
    }

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
    fun updatePowerPolicySkipsRestartWhenTheResolvedTierIsUnchanged(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)
        fixture.lifecycle.updatePowerPolicy(
            policy = powerPolicy(tier = PowerTier.POWER_SAVER),
            started = true,
            hardware = fixture.hardware,
        )
        val startScanCallsAfterFirstUpdate = fixture.startScanCalls
        val startAdvertisingCallsAfterFirstUpdate = fixture.startAdvertisingCalls

        // Act: a second update resolving to the same tier (e.g. a repeated battery-changed
        // broadcast that doesn't cross a tier threshold) must not restart scan/advertise.
        fixture.lifecycle.updatePowerPolicy(
            policy = powerPolicy(tier = PowerTier.POWER_SAVER),
            started = true,
            hardware = fixture.hardware,
        )

        // Assert
        assertEquals(startScanCallsAfterFirstUpdate, fixture.startScanCalls)
        assertEquals(startAdvertisingCallsAfterFirstUpdate, fixture.startAdvertisingCalls)
    }

    @Test
    fun updatePowerPolicyRestartsWhenTheResolvedTierChanges(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)
        fixture.lifecycle.updatePowerPolicy(
            policy = powerPolicy(tier = PowerTier.POWER_SAVER),
            started = true,
            hardware = fixture.hardware,
        )
        val startScanCallsAfterFirstUpdate = fixture.startScanCalls

        // Act
        fixture.lifecycle.updatePowerPolicy(
            policy = powerPolicy(tier = PowerTier.PERFORMANCE),
            started = true,
            hardware = fixture.hardware,
        )

        // Assert
        assertEquals(startScanCallsAfterFirstUpdate + 1, fixture.startScanCalls)
        assertEquals(BlePowerMode.PERFORMANCE, fixture.startedAdvertisingPayloads.last().powerMode)
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

    @Test
    fun scanFailureWithRetryableErrorSchedulesARetryThatRestartsScanning(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act
        fixture.lifecycle.scanCallback.onScanFailed(
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES
        )

        // Assert
        assertEquals(1, fixture.scheduledScanRetryDelaysMillis.size)
        fixture.runScheduledScanRetries()
        assertEquals(2, fixture.startScanCalls)
    }

    @Test
    fun scanFailureWithNonRetryableErrorDoesNotScheduleARetry(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act
        fixture.lifecycle.scanCallback.onScanFailed(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)

        // Assert
        assertTrue(fixture.scheduledScanRetryDelaysMillis.isEmpty())
    }

    @Test
    fun scanFailureWithRateLimitErrorSchedulesADedicatedBackoffRetry(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act
        fixture.lifecycle.scanCallback.onScanFailed(
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
        )

        // Assert
        assertEquals(1, fixture.scheduledScanRetryDelaysMillis.size)
        assertEquals(
            RATE_LIMIT_RETRY_DELAY_1_MILLIS,
            fixture.scheduledScanRetryDelaysMillis.single(),
        )
        val call = fixture.scanFailedCalls.single()
        assertTrue(call.willRetry)
        assertEquals(1, call.attempt)
        fixture.runScheduledScanRetries()
        assertEquals(2, fixture.startScanCalls)
    }

    @Test
    fun scanRateLimitRetryBacksOffExponentiallyUpToACapAndDoesNotShareTheFastRetryBudget(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act -- exhaust the fast-retry family's attempt budget first ...
        repeat(4) {
            fixture.lifecycle.scanCallback.onScanFailed(
                ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES
            )
            fixture.runScheduledScanRetries()
        }
        assertFalse(fixture.scanFailedCalls.last().willRetry)
        fixture.scheduledScanRetryDelaysMillis.clear()

        // ... then trigger the rate-limit family, which must still retry using its own budget.
        repeat(7) {
            fixture.lifecycle.scanCallback.onScanFailed(
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
            )
            fixture.runScheduledScanRetries()
        }

        // Assert: 2s, 4s, 8s, 16s, 30s (capped), 30s (capped) -- 6 attempts, last one exhausted.
        assertEquals(
            listOf(
                RATE_LIMIT_RETRY_DELAY_1_MILLIS,
                RATE_LIMIT_RETRY_DELAY_2_MILLIS,
                RATE_LIMIT_RETRY_DELAY_3_MILLIS,
                RATE_LIMIT_RETRY_DELAY_4_MILLIS,
                RATE_LIMIT_RETRY_DELAY_CAPPED_MILLIS,
                RATE_LIMIT_RETRY_DELAY_CAPPED_MILLIS,
            ),
            fixture.scheduledScanRetryDelaysMillis,
        )
        assertFalse(fixture.scanFailedCalls.last().willRetry)
    }

    @Test
    fun scanRateLimitRetryAttemptCounterResetsIndependentlyOnASuccessfulScanResult(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)
        fixture.lifecycle.scanCallback.onScanFailed(
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
        )
        fixture.runScheduledScanRetries()

        // Act -- a real scan result arrives, which should reset the rate-limit attempt counter.
        fixture.lifecycle.scanCallback.onBatchScanResults(mutableListOf())
        fixture.lifecycle.scanCallback.onScanFailed(
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
        )

        // Assert: attempt is back at 1, not 2, because the intervening scan result reset it.
        assertEquals(1, fixture.scanFailedCalls.last().attempt)
    }

    @Test
    fun scanRetryStopsAfterTheMaximumAttemptCount(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act
        repeat(5) {
            fixture.lifecycle.scanCallback.onScanFailed(
                ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES
            )
            fixture.runScheduledScanRetries()
        }

        // Assert
        assertEquals(3, fixture.scheduledScanRetryDelaysMillis.size)
    }

    @Test
    fun stopCancelsPendingScanRetries(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)
        fixture.lifecycle.scanCallback.onScanFailed(
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES
        )

        // Act
        fixture.lifecycle.stop(fixture.hardware)
        fixture.runScheduledScanRetries()

        // Assert
        assertEquals(1, fixture.startScanCalls)
    }

    @Test
    fun scanRetryStopsScanningBeforeStartingAgainToAvoidAlreadyStartedFailures(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)
        fixture.lifecycle.scanCallback.onScanFailed(
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES
        )

        // Act
        fixture.runScheduledScanRetries()

        // Assert
        assertEquals(2, fixture.stopScanCalls)
        assertEquals(2, fixture.startScanCalls)
    }

    @Test
    fun scanErrorCodeNameMapsKnownAndUnknownErrorCodes(): Unit {
        // Assert
        assertEquals(
            "SCAN_FAILED_ALREADY_STARTED",
            scanErrorCodeName(ScanCallback.SCAN_FAILED_ALREADY_STARTED),
        )
        assertEquals(
            "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED",
            scanErrorCodeName(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED),
        )
        assertEquals(
            "SCAN_FAILED_INTERNAL_ERROR",
            scanErrorCodeName(ScanCallback.SCAN_FAILED_INTERNAL_ERROR),
        )
        assertEquals(
            "SCAN_FAILED_FEATURE_UNSUPPORTED",
            scanErrorCodeName(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED),
        )
        assertEquals(
            "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES",
            scanErrorCodeName(ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES),
        )
        assertEquals(
            "SCAN_FAILED_SCANNING_TOO_FREQUENTLY",
            scanErrorCodeName(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY),
        )
        assertEquals("SCAN_FAILED_UNKNOWN", scanErrorCodeName(-1))
    }

    @Test
    fun scanFailureNotifiesTheDiagnosticsCallbackWithErrorDetails(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act
        fixture.lifecycle.scanCallback.onScanFailed(
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES
        )

        // Assert
        val call = fixture.scanFailedCalls.single()
        assertEquals(ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES, call.errorCode)
        assertEquals("SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES", call.errorName)
        assertTrue(call.willRetry)
        assertEquals(1, call.attempt)
    }

    @Test
    fun scanFailureNotifiesNoRetryOnceMaxAttemptsAreExhausted(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act
        repeat(4) {
            fixture.lifecycle.scanCallback.onScanFailed(
                ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES
            )
            fixture.runScheduledScanRetries()
        }

        // Assert
        assertFalse(fixture.scanFailedCalls.last().willRetry)
    }

    @Test
    fun scanWatchdogRestartsScanningWhenNoScanResultArrivesWithinTheIdleThreshold(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.fakeNowMillis = 0L
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)
        val startScanCallsAfterRefresh = fixture.startScanCalls

        // Act: advance time past the idle threshold without ever delivering a scan result, then
        // run the scheduled watchdog check.
        fixture.fakeNowMillis = SCAN_WATCHDOG_IDLE_THRESHOLD_MILLIS + 1
        fixture.runScheduledWatchdogChecks()

        // Assert
        assertEquals(startScanCallsAfterRefresh + 1, fixture.startScanCalls)
    }

    @Test
    fun scanWatchdogDoesNotRestartScanningWhenResultsKeepArriving(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.fakeNowMillis = 0L
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)
        val startScanCallsAfterRefresh = fixture.startScanCalls

        // Act: a scan result keeps arriving right before each check, resetting the idle clock.
        fixture.fakeNowMillis = SCAN_WATCHDOG_IDLE_THRESHOLD_MILLIS - 1
        fixture.lifecycle.scanCallback.onBatchScanResults(mutableListOf())
        fixture.runScheduledWatchdogChecks()

        // Assert
        assertEquals(startScanCallsAfterRefresh, fixture.startScanCalls)
    }

    @Test
    fun scanWatchdogStopsReschedulingAfterDiscoveryIsStopped(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.fakeNowMillis = 0L
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)
        fixture.lifecycle.stop(fixture.hardware)
        val startScanCallsAfterStop = fixture.startScanCalls

        // Act
        fixture.fakeNowMillis = SCAN_WATCHDOG_IDLE_THRESHOLD_MILLIS + 1
        fixture.runScheduledWatchdogChecks()

        // Assert: the stale watchdog loop from before stop() must not restart scanning.
        assertEquals(startScanCallsAfterStop, fixture.startScanCalls)
    }

    @Test
    fun scanWatchdogEscalatesToAdapterPowerCycleAfterRepeatedWedgedRestarts(): Unit {
        // Arrange: a plain scan restart never brings in a result, so the idle clock never
        // resets between checks -- simulating a wedge a scan-only restart cannot clear.
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.fakeNowMillis = 0L
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act: advance past the idle threshold and run the watchdog check
        // MAX_WEDGED_SCAN_RESTARTS_BEFORE_ESCALATION times in a row.
        repeat(MAX_WEDGED_SCAN_RESTARTS_BEFORE_ESCALATION) {
            fixture.fakeNowMillis += SCAN_WATCHDOG_IDLE_THRESHOLD_MILLIS + 1
            fixture.runScheduledWatchdogChecks()
        }

        // Assert: escalation fires exactly once, after the configured number of consecutive
        // wedged restarts.
        assertEquals(1, fixture.powerCycleAttempts)
        assertEquals(0, fixture.manualRecoveryNeededCalls)
    }

    @Test
    fun scanWatchdogFallsBackToManualRecoveryWhenAdapterPowerCycleIsUnavailable(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.powerCycleResult = false
        fixture.fakeNowMillis = 0L
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act
        repeat(MAX_WEDGED_SCAN_RESTARTS_BEFORE_ESCALATION) {
            fixture.fakeNowMillis += SCAN_WATCHDOG_IDLE_THRESHOLD_MILLIS + 1
            fixture.runScheduledWatchdogChecks()
        }

        // Assert
        assertEquals(1, fixture.powerCycleAttempts)
        assertEquals(1, fixture.manualRecoveryNeededCalls)
    }

    @Test
    fun scanWatchdogResetsTheWedgeStreakWhenAScanResultArrives(): Unit {
        // Arrange
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.fakeNowMillis = 0L
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)

        // Act: one wedged restart, then a real result arrives before the next check, then
        // another wedged restart -- this should not accumulate into an escalation because the
        // streak was broken by the intervening result.
        fixture.fakeNowMillis = SCAN_WATCHDOG_IDLE_THRESHOLD_MILLIS + 1
        fixture.runScheduledWatchdogChecks()
        fixture.lifecycle.scanCallback.onBatchScanResults(mutableListOf())
        fixture.fakeNowMillis += SCAN_WATCHDOG_IDLE_THRESHOLD_MILLIS + 1
        fixture.runScheduledWatchdogChecks()

        // Assert
        assertEquals(0, fixture.powerCycleAttempts)
        assertEquals(0, fixture.manualRecoveryNeededCalls)
    }

    @Test
    fun concurrentScanCallbacksAndWatchdogChecksDoNotCorruptTheWedgeStreakCounter(): Unit {
        // Arrange -- this is the correctness property that must survive making
        // consecutiveWedgedScanRestarts (and the other BleTransportDiscoveryLifecycle fields)
        // @Volatile rather than lock-guarded: real concurrent writers from separate threads (one
        // simulating the BLE stack's own callback thread delivering scan results and resetting
        // the streak, one simulating the coroutineScope's IO-dispatcher thread running watchdog
        // checks and incrementing the streak) must never leave consecutiveWedgedScanRestarts (or
        // the escalation bookkeeping derived from it) in an invalid state, even though @Volatile
        // alone does not make a read-increment-write step atomic. Per this class's own doc
        // comment, consecutiveWedgedScanRestarts is only ever mutated from a sequential call
        // chain in production (never concurrently incremented from two threads at once); this
        // test's job is to confirm that claim holds by actually racing two threads against it
        // repeatedly and checking the escalation invariant never breaks, rather than to assert a
        // specific final count (which would be inherently racy to predict).
        val fixture = BleTransportDiscoveryLifecycleFixture()
        fixture.fakeNowMillis = 0L
        fixture.lifecycle.refresh(started = true, hardware = fixture.hardware)
        val iterationsPerThread = CONCURRENCY_STRESS_ITERATIONS_PER_THREAD
        val scanResultThread = Thread {
            repeat(iterationsPerThread) {
                fixture.lifecycle.scanCallback.onBatchScanResults(mutableListOf())
            }
        }
        val watchdogThread = Thread {
            repeat(iterationsPerThread) {
                fixture.fakeNowMillis += SCAN_WATCHDOG_IDLE_THRESHOLD_MILLIS + 1
                fixture.runScheduledWatchdogChecks()
            }
        }

        // Act
        scanResultThread.start()
        watchdogThread.start()
        scanResultThread.join()
        watchdogThread.join()

        // Assert -- the escalation callback must never fire more times than the number of
        // watchdog-triggered restarts could possibly justify, and must never go negative or
        // silently corrupt into a state that escalates on every single check. A lost update (or a
        // torn, non-atomic read-modify-write) manifesting as runaway escalation would show up here
        // as manualRecoveryNeededCalls/powerCycleAttempts exceeding what MAX_WEDGED_SCAN_RESTARTS_
        // BEFORE_ESCALATION divided into iterationsPerThread watchdog runs could produce.
        val maxPossibleEscalations =
            iterationsPerThread / MAX_WEDGED_SCAN_RESTARTS_BEFORE_ESCALATION + 1
        assertTrue(
            fixture.powerCycleAttempts + fixture.manualRecoveryNeededCalls <=
                maxPossibleEscalations,
            "Expected at most $maxPossibleEscalations escalations, got " +
                "${fixture.powerCycleAttempts + fixture.manualRecoveryNeededCalls} " +
                "(powerCycleAttempts=${fixture.powerCycleAttempts}, " +
                "manualRecoveryNeededCalls=${fixture.manualRecoveryNeededCalls})",
        )
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
    val scheduledScanRetryDelaysMillis = mutableListOf<Long>()
    private val pendingScanRetries = mutableListOf<() -> Unit>()
    val scanFailedCalls = mutableListOf<ScanFailedCall>()
    var fakeNowMillis: Long = 0L
    val scheduledWatchdogDelaysMillis = mutableListOf<Long>()
    private val pendingWatchdogChecks = mutableListOf<() -> Unit>()
    var powerCycleAttempts: Int = 0
    var powerCycleResult: Boolean = true
    var manualRecoveryNeededCalls: Int = 0

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
            scheduleScanRetry = { delayMillis, retry ->
                scheduledScanRetryDelaysMillis += delayMillis
                pendingScanRetries += retry
            },
            onScanFailed = { errorCode, errorName, willRetry, attempt ->
                scanFailedCalls +=
                    ScanFailedCall(
                        errorCode = errorCode,
                        errorName = errorName,
                        willRetry = willRetry,
                        attempt = attempt,
                    )
            },
            scheduleScanWatchdogCheck = { delayMillis, check ->
                scheduledWatchdogDelaysMillis += delayMillis
                pendingWatchdogChecks += check
            },
            nowMillis = { fakeNowMillis },
            attemptBluetoothAdapterPowerCycle = {
                powerCycleAttempts += 1
                powerCycleResult
            },
            onManualBluetoothRecoveryNeeded = { manualRecoveryNeededCalls += 1 },
        )

    fun runScheduledRetries(): Unit {
        val retries = pendingRetries.toList()
        pendingRetries.clear()
        retries.forEach { it() }
    }

    fun runScheduledScanRetries(): Unit {
        val retries = pendingScanRetries.toList()
        pendingScanRetries.clear()
        retries.forEach { it() }
    }

    fun runScheduledWatchdogChecks(): Unit {
        val checks = pendingWatchdogChecks.toList()
        pendingWatchdogChecks.clear()
        checks.forEach { it() }
    }
}

private data class AdvertiseFailedCall(
    val errorCode: Int,
    val errorName: String,
    val willRetry: Boolean,
    val attempt: Int,
)

private data class ScanFailedCall(
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
