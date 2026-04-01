package io.meshlink.transport

import io.meshlink.power.PowerProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScanDutyCycleControllerTest {

    // -- Timing table tests ---------------------------------------------------

    @Test
    fun performanceTimingIs4sOn1sOff() {
        val p = PowerProfile.PERFORMANCE
        val t = ScanDutyCycleController.CycleTiming(p.scanOnMillis, p.scanOffMillis)
        assertEquals(4_000L, t.scanOnMillis)
        assertEquals(1_000L, t.scanOffMillis)
        assertEquals(80, t.dutyPercent)
    }

    @Test
    fun balancedTimingIs3sOn3sOff() {
        val p = PowerProfile.BALANCED
        val t = ScanDutyCycleController.CycleTiming(p.scanOnMillis, p.scanOffMillis)
        assertEquals(3_000L, t.scanOnMillis)
        assertEquals(3_000L, t.scanOffMillis)
        assertEquals(50, t.dutyPercent)
    }

    @Test
    fun powerSaverTimingIs1sOn5sOff() {
        val p = PowerProfile.POWER_SAVER
        val t = ScanDutyCycleController.CycleTiming(p.scanOnMillis, p.scanOffMillis)
        assertEquals(1_000L, t.scanOnMillis)
        assertEquals(5_000L, t.scanOffMillis)
        assertEquals(16, t.dutyPercent) // 1000/6000 = 16%
    }

    // -- Start / stop lifecycle -----------------------------------------------

    @Test
    fun startSetsRunningFlag() = runTest {
        val controller = ScanDutyCycleController()
        val transport = RecordingTransport()

        assertFalse(controller.isRunning)
        controller.start(this, transport)
        assertTrue(controller.isRunning)
        controller.stop()
    }

    @Test
    fun stopClearsRunningFlag() = runTest {
        val controller = ScanDutyCycleController()
        val transport = RecordingTransport()

        controller.start(this, transport)
        controller.stop()
        assertFalse(controller.isRunning)
    }

    // -- Duty-cycle behaviour -------------------------------------------------

    @Test
    fun performanceCycleCallsStartThenStop() = runTest {
        val transport = RecordingTransport()
        val controller = ScanDutyCycleController()

        controller.start(this, transport)

        // Scan-on phase: transport should be started immediately
        advanceTimeBy(100)
        assertTrue(transport.startCount > 0, "startAdvertisingAndScanning should be called")

        // Advance past the 4 s scan-on window
        advanceTimeBy(4_000)
        assertTrue(transport.stopCount > 0, "stopAll should be called after scan-on window")

        controller.stop()
    }

    @Test
    fun multipleCyclesAlternateStartAndStop() = runTest {
        val transport = RecordingTransport()
        val controller = ScanDutyCycleController()
        val balanced = PowerProfile.BALANCED
        controller.onTimingChanged(balanced.scanOnMillis, balanced.scanOffMillis) // 3s on, 3s off

        controller.start(this, transport)

        // First cycle: scan on
        advanceTimeBy(100)
        assertEquals(1, transport.startCount)

        // End of scan-on → stop
        advanceTimeBy(3_000)
        assertEquals(1, transport.stopCount)

        // Pause phase complete → second scan-on
        advanceTimeBy(3_000)
        assertEquals(2, transport.startCount)

        // Second scan-off
        advanceTimeBy(3_000)
        assertEquals(2, transport.stopCount)

        controller.stop()
    }

    // -- Power mode changes ---------------------------------------------------

    @Test
    fun onTimingChangedUpdatesTiming() {
        val controller = ScanDutyCycleController()
        assertEquals(4_000L, controller.timing.scanOnMillis) // default PERFORMANCE

        val ps = PowerProfile.POWER_SAVER
        controller.onTimingChanged(ps.scanOnMillis, ps.scanOffMillis)
        assertEquals(1_000L, controller.timing.scanOnMillis)
        assertEquals(5_000L, controller.timing.scanOffMillis)
    }

    @Test
    fun powerModeChangeAppliesOnNextCycle() = runTest {
        val transport = RecordingTransport()
        val controller = ScanDutyCycleController()

        controller.start(this, transport)
        advanceTimeBy(100) // first start called

        // Switch to PowerSaver mid-cycle (1s on, 5s off)
        val ps = PowerProfile.POWER_SAVER
        controller.onTimingChanged(ps.scanOnMillis, ps.scanOffMillis)

        // Complete the current PERFORMANCE on-window (4s) + off-window (1s)
        advanceTimeBy(4_900)

        // Next cycle should use PowerSaver timing
        val startsBefore = transport.startCount
        advanceTimeBy(6_100) // full PowerSaver cycle = 1s + 5s = 6s
        assertTrue(transport.startCount > startsBefore, "New cycle should start with updated timing")

        controller.stop()
    }

    // -- CycleTiming computed properties --------------------------------------

    @Test
    fun cycleTimingTotalMillis() {
        val t = ScanDutyCycleController.CycleTiming(scanOnMillis = 2_000L, scanOffMillis = 8_000L)
        assertEquals(10_000L, t.totalMillis)
    }

    @Test
    fun cycleTimingDutyPercent() {
        val t = ScanDutyCycleController.CycleTiming(scanOnMillis = 2_000L, scanOffMillis = 8_000L)
        assertEquals(20, t.dutyPercent)
    }

    // -- Edge cases -----------------------------------------------------------

    @Test
    fun doubleStartStopsPreviousCycle() = runTest {
        val transport = RecordingTransport()
        val controller = ScanDutyCycleController()

        controller.start(this, transport)
        advanceTimeBy(100)
        val firstStartCount = transport.startCount

        // Starting again should cancel the previous cycle
        controller.start(this, transport)
        advanceTimeBy(100)

        // Should not have double-accumulated starts beyond what's expected
        assertTrue(controller.isRunning)
        controller.stop()
    }

    @Test
    fun stopWithoutStartIsNoop() {
        val controller = ScanDutyCycleController()
        controller.stop() // should not throw
        assertFalse(controller.isRunning)
    }

    // =========================================================================
    // Test double – records calls to BleTransport
    // =========================================================================

    private class RecordingTransport : BleTransport {
        var startCount = 0
        var stopCount = 0

        override val localPeerId: ByteArray = ByteArray(8)
        override var advertisementServiceData: ByteArray = ByteArray(0)

        override suspend fun startAdvertisingAndScanning() {
            startCount++
        }

        override suspend fun stopAll() {
            stopCount++
        }

        override val advertisementEvents: Flow<AdvertisementEvent>
            get() = MutableSharedFlow()

        override val peerLostEvents: Flow<PeerLostEvent>
            get() = MutableSharedFlow()

        override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray) {}

        override val incomingData: Flow<IncomingData>
            get() = MutableSharedFlow()
    }
}
