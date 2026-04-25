package ch.trancee.meshlink.power

import ch.trancee.meshlink.transfer.Priority
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

// ─────────────────────────────────────────────────────────────────────────────
// PowerManager integration tests
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class PowerManagerTest {

    private val fastConfig =
        PowerConfig(
            batteryPollIntervalMs = 100L,
            hysteresisDelayMs = 1_000L,
            bootstrapDurationMs = 500L,
            evictionGracePeriodMs = 200L,
            maxEvictionGracePeriodMs = 1_000L,
            minThroughputBytesPerSec = 1_024f,
            performanceMaxConnections = 6,
            balancedMaxConnections = 4,
            powerSaverMaxConnections = 2,
            performanceThreshold = 0.80f,
            powerSaverThreshold = 0.30f,
            hysteresisPercent = 0.02f,
        )

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    @Test
    fun `bootstrap keeps PERFORMANCE regardless of low battery`() = runTest {
        val stub = StubBatteryMonitor(level = 0.20f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        assertEquals(PowerTier.PERFORMANCE, pm.currentTier)
    }

    @Test
    fun `onFirstConnectionEstablished ends bootstrap and re-evaluates`() = runTest {
        val stub = StubBatteryMonitor(level = 0.20f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        assertEquals(PowerTier.PERFORMANCE, pm.currentTier)

        // End bootstrap — battery 20% is below powerSaverThreshold, but downgrade delay applies.
        // Timer starts immediately; since now - startedAt = 0 < 1000, no transition yet.
        pm.onFirstConnectionEstablished()
        testScheduler.runCurrent()
        // Still PERFORMANCE — hysteresis delay not elapsed
        assertEquals(PowerTier.PERFORMANCE, pm.currentTier)
    }

    @Test
    fun `bootstrap timeout auto-ends bootstrap`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        assertEquals(PowerTier.PERFORMANCE, pm.currentTier)

        // Bootstrap fires after 500ms
        testScheduler.advanceTimeBy(fastConfig.bootstrapDurationMs + fastConfig.batteryPollIntervalMs)
        // Battery at 90% → stays PERFORMANCE
        assertEquals(PowerTier.PERFORMANCE, pm.currentTier)
    }

    @Test
    fun `onFirstConnectionEstablished when bootstrap already ended via timeout covers null job branch`() =
        runTest {
            val stub = StubBatteryMonitor(level = 0.90f)
            val pm =
                PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
            testScheduler.runCurrent()

            // Let bootstrap timeout fire → bootstrapJob becomes null
            testScheduler.advanceTimeBy(fastConfig.bootstrapDurationMs + fastConfig.batteryPollIntervalMs)
            testScheduler.runCurrent()

            // Call with bootstrapJob == null → covers the if(job != null) false branch
            pm.onFirstConnectionEstablished()
            testScheduler.runCurrent()
            assertEquals(PowerTier.PERFORMANCE, pm.currentTier)
        }

    // ── Tier transitions ──────────────────────────────────────────────────────

    @Test
    fun `PERFORMANCE to BALANCED after hysteresis delay`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        stub.level = 0.78f // Below performanceThreshold − hysteresis (0.80 − 0.02 = 0.78)
        // Timer starts at first poll. Advance past hysteresis delay + one extra poll to guarantee fire.
        testScheduler.advanceTimeBy(fastConfig.batteryPollIntervalMs + fastConfig.hysteresisDelayMs + fastConfig.batteryPollIntervalMs)
        testScheduler.runCurrent()
        assertEquals(PowerTier.BALANCED, pm.currentTier)
    }

    @Test
    fun `hysteresis dead-zone 79 percent stays PERFORMANCE`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        stub.level = 0.79f // Above 0.78 → dead band, no downgrade timer started
        testScheduler.advanceTimeBy(fastConfig.batteryPollIntervalMs + fastConfig.hysteresisDelayMs + fastConfig.batteryPollIntervalMs)
        testScheduler.runCurrent()
        assertEquals(PowerTier.PERFORMANCE, pm.currentTier)
    }

    @Test
    fun `cascading hysteresis BALANCED timer then POWER_SAVER restarts timer`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        // Start a downgrade timer for BALANCED (battery=0.78, first poll starts timer)
        stub.level = 0.78f
        testScheduler.advanceTimeBy(fastConfig.batteryPollIntervalMs) // timer starts
        assertEquals(PowerTier.PERFORMANCE, pm.currentTier)

        // Battery drops further — cascading: timer restarts for POWER_SAVER
        stub.level = 0.20f
        testScheduler.advanceTimeBy(fastConfig.batteryPollIntervalMs) // cascading restart
        assertEquals(PowerTier.PERFORMANCE, pm.currentTier)

        // Advance full hysteresis delay from restart point → POWER_SAVER
        testScheduler.advanceTimeBy(fastConfig.hysteresisDelayMs + fastConfig.batteryPollIntervalMs)
        testScheduler.runCurrent()
        assertEquals(PowerTier.POWER_SAVER, pm.currentTier)
    }

    @Test
    fun `PERFORMANCE to POWER_SAVER direct downgrade after delay`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        stub.level = 0.20f // Below powerSaverThreshold − hysteresis (0.30 − 0.02 = 0.28)
        testScheduler.advanceTimeBy(fastConfig.batteryPollIntervalMs + fastConfig.hysteresisDelayMs + fastConfig.batteryPollIntervalMs)
        testScheduler.runCurrent()
        assertEquals(PowerTier.POWER_SAVER, pm.currentTier)
    }

    @Test
    fun `BALANCED to POWER_SAVER downgrade after delay`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        // Force to BALANCED via custom mode
        pm.setCustomMode(PowerTier.BALANCED)
        testScheduler.runCurrent()
        assertEquals(PowerTier.BALANCED, pm.currentTier)

        // Now drop to POWER_SAVER range from BALANCED (removes custom mode, evaluates with new battery)
        stub.level = 0.20f
        pm.setCustomMode(null) // evaluateBattery: currentTier=BALANCED, battery=0.20 → POWER_SAVER downgrade starts
        testScheduler.advanceTimeBy(fastConfig.hysteresisDelayMs + fastConfig.batteryPollIntervalMs)
        testScheduler.runCurrent()
        assertEquals(PowerTier.POWER_SAVER, pm.currentTier)
    }

    @Test
    fun `BALANCED to PERFORMANCE immediate upgrade`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        // Force BALANCED via custom mode
        pm.setCustomMode(PowerTier.BALANCED)
        testScheduler.runCurrent()
        assertEquals(PowerTier.BALANCED, pm.currentTier)
        pm.setCustomMode(null)

        // Battery 82% → above performanceThreshold + hysteresis (0.80 + 0.02 = 0.82)
        stub.level = 0.82f
        testScheduler.advanceTimeBy(fastConfig.batteryPollIntervalMs)
        assertEquals(PowerTier.PERFORMANCE, pm.currentTier)
    }

    @Test
    fun `upward false branch BALANCED to PERFORMANCE battery just above threshold but below hysteresis`() =
        runTest {
            val stub = StubBatteryMonitor(level = 0.90f)
            val pm =
                PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
            testScheduler.runCurrent()
            pm.onFirstConnectionEstablished()

            // Set battery to 81% FIRST, then remove custom mode to trigger upward-false-branch
            stub.level = 0.81f
            pm.setCustomMode(PowerTier.BALANCED)
            testScheduler.runCurrent()
            pm.setCustomMode(null)
            // evaluateBattery: BALANCED→PERFORMANCE upward, battery(0.81) >= 0.82? NO → stays BALANCED
            testScheduler.runCurrent()
            assertEquals(PowerTier.BALANCED, pm.currentTier)
        }

    @Test
    fun `POWER_SAVER to BALANCED upgrade threshold exact`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        // Set battery FIRST, then force to POWER_SAVER and remove custom mode so evaluateBattery
        // runs with the right battery level. Use 0.33f (not 0.32f) to avoid float-rounding where
        // 0.30f + 0.02f > 0.32f in IEEE 754, making the >= check fail at the exact boundary.
        stub.level = 0.33f // safely above powerSaverThreshold + hysteresis → immediate upgrade
        pm.setCustomMode(PowerTier.POWER_SAVER)
        testScheduler.runCurrent()
        pm.setCustomMode(null) // evaluateBattery: POWER_SAVER→BALANCED upward, 0.33 >= 0.32 → YES
        testScheduler.runCurrent()
        assertEquals(PowerTier.BALANCED, pm.currentTier)
    }

    @Test
    fun `POWER_SAVER to BALANCED upward false branch battery just below threshold`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        // Set battery to 31% (above 0.30 but below 0.32 → upward path but no transition)
        stub.level = 0.31f
        pm.setCustomMode(PowerTier.POWER_SAVER)
        testScheduler.runCurrent()
        pm.setCustomMode(null) // evaluateBattery: POWER_SAVER, battery=0.31, upward but 0.31 < 0.32
        testScheduler.runCurrent()
        assertEquals(PowerTier.POWER_SAVER, pm.currentTier)
    }

    @Test
    fun `downgrade timer cancelled on deadband re-entry`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        stub.level = 0.78f // starts downgrade timer
        testScheduler.advanceTimeBy(fastConfig.batteryPollIntervalMs)
        assertEquals(PowerTier.PERFORMANCE, pm.currentTier)

        // Battery rises into deadband (0.79 > 0.78) → timer cancelled
        stub.level = 0.79f
        testScheduler.advanceTimeBy(fastConfig.hysteresisDelayMs + fastConfig.batteryPollIntervalMs)
        testScheduler.runCurrent()
        assertEquals(PowerTier.PERFORMANCE, pm.currentTier)
    }

    // ── Charging ──────────────────────────────────────────────────────────────

    @Test
    fun `charging at low battery forces PERFORMANCE`() = runTest {
        val stub = StubBatteryMonitor(level = 0.50f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        // Force to BALANCED: setCustomMode(BALANCED) sets currentTier=BALANCED
        pm.setCustomMode(PowerTier.BALANCED)
        testScheduler.runCurrent()
        assertEquals(PowerTier.BALANCED, pm.currentTier)

        // Now set isCharging BEFORE removing custom mode, then remove custom mode
        // so evaluateBattery runs with custom=null, isCharging=true, currentTier=BALANCED
        stub.isCharging = true
        pm.setCustomMode(null) // evaluateBattery: custom=null, isCharging=true, tier=BALANCED
        // → if (currentTier != PERFORMANCE) TRUE → currentTier=PERFORMANCE (lines 241-242 covered)
        testScheduler.runCurrent()
        assertEquals(PowerTier.PERFORMANCE, pm.currentTier)
    }

    @Test
    fun `charging when already PERFORMANCE no tier change emitted`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        val emitted = mutableListOf<PowerTier>()
        val job = launch { pm.tierChanges.collect { emitted.add(it) } }
        testScheduler.runCurrent()
        val countBefore = emitted.size

        stub.isCharging = true // already PERFORMANCE → no new emission
        testScheduler.advanceTimeBy(fastConfig.batteryPollIntervalMs)
        testScheduler.runCurrent()

        assertEquals(countBefore, emitted.size)
        job.cancel()
    }

    @Test
    fun `charging removed re-evaluates to battery-based tier`() = runTest {
        val stub = StubBatteryMonitor(level = 0.50f, isCharging = true)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        testScheduler.advanceTimeBy(fastConfig.batteryPollIntervalMs)
        assertEquals(PowerTier.PERFORMANCE, pm.currentTier) // charging → PERFORMANCE

        stub.isCharging = false
        testScheduler.advanceTimeBy(fastConfig.batteryPollIntervalMs + fastConfig.hysteresisDelayMs)
        assertEquals(PowerTier.BALANCED, pm.currentTier)
    }

    // ── Custom mode ───────────────────────────────────────────────────────────

    @Test
    fun `setCustomMode overrides battery-based tier`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        pm.setCustomMode(PowerTier.POWER_SAVER)
        testScheduler.runCurrent()
        assertEquals(PowerTier.POWER_SAVER, pm.currentTier)
    }

    @Test
    fun `setCustomMode same tier does not emit`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        pm.setCustomMode(PowerTier.POWER_SAVER)
        testScheduler.runCurrent()

        val emitted = mutableListOf<PowerTier>()
        val job = launch { pm.tierChanges.collect { emitted.add(it) } }
        testScheduler.runCurrent()
        val countBefore = emitted.size

        pm.setCustomMode(PowerTier.POWER_SAVER) // same → no emit
        testScheduler.runCurrent()
        assertEquals(countBefore, emitted.size)
        job.cancel()
    }

    @Test
    fun `setCustomMode null restores battery-based tier`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        pm.setCustomMode(PowerTier.POWER_SAVER)
        testScheduler.runCurrent()
        assertEquals(PowerTier.POWER_SAVER, pm.currentTier)

        pm.setCustomMode(null)
        testScheduler.runCurrent()
        assertEquals(PowerTier.PERFORMANCE, pm.currentTier) // battery=90% → back to PERFORMANCE
    }

    // ── tierChanges flow ──────────────────────────────────────────────────────

    @Test
    fun `tierChanges emits on tier transition`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        val emitted = mutableListOf<PowerTier>()
        val job = launch { pm.tierChanges.collect { emitted.add(it) } }
        testScheduler.runCurrent()

        stub.level = 0.78f
        testScheduler.advanceTimeBy(fastConfig.batteryPollIntervalMs + fastConfig.hysteresisDelayMs)
        testScheduler.runCurrent()

        assertTrue(emitted.contains(PowerTier.BALANCED))
        job.cancel()
    }

    @Test
    fun `profile returns correct parameters for current tier`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()
        assertEquals(fastConfig.performanceMaxConnections, pm.profile().maxConnections)
    }

    // ── Connection acquisition ────────────────────────────────────────────────

    @Test
    fun `tryAcquireConnection Accept path returns true`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        assertTrue(pm.tryAcquireConnection(byteArrayOf(1), Priority.NORMAL))
    }

    @Test
    fun `tryAcquireConnection Reject path returns false when all higher priority`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        // Force to POWER_SAVER (max=2)
        pm.setCustomMode(PowerTier.POWER_SAVER)
        testScheduler.runCurrent()

        // Fill to max with HIGH priority
        pm.tryAcquireConnection(byteArrayOf(1), Priority.HIGH)
        pm.tryAcquireConnection(byteArrayOf(2), Priority.HIGH)

        // LOW priority cannot evict HIGH → Reject
        assertFalse(pm.tryAcquireConnection(byteArrayOf(3), Priority.LOW))
    }

    @Test
    fun `tryAcquireConnection EvictAndAccept path evicts lower priority and emits`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        pm.setCustomMode(PowerTier.POWER_SAVER)
        testScheduler.runCurrent()

        // Fill to max with LOW priority
        pm.tryAcquireConnection(byteArrayOf(1), Priority.LOW)
        pm.tryAcquireConnection(byteArrayOf(2), Priority.LOW)

        val evictions = mutableListOf<ByteArray>()
        val job = launch { pm.evictionRequests.collect { evictions.add(it) } }
        testScheduler.runCurrent()

        // HIGH priority → EvictAndAccept
        assertTrue(pm.tryAcquireConnection(byteArrayOf(3), Priority.HIGH))
        testScheduler.runCurrent()

        assertEquals(1, evictions.size)
        job.cancel()
    }

    @Test
    fun `releaseConnection frees slot for next acquire`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        pm.setCustomMode(PowerTier.POWER_SAVER)
        testScheduler.runCurrent()

        // Fill to max(2) with HIGH priority; NORMAL peer cannot evict HIGH → Reject
        pm.tryAcquireConnection(byteArrayOf(1), Priority.HIGH)
        pm.tryAcquireConnection(byteArrayOf(2), Priority.HIGH)
        assertFalse(pm.tryAcquireConnection(byteArrayOf(3), Priority.NORMAL))

        // Release one slot; NORMAL peer can now acquire via Accept
        pm.releaseConnection(byteArrayOf(1))
        assertTrue(pm.tryAcquireConnection(byteArrayOf(3), Priority.NORMAL))
    }

    @Test
    fun `updateConnectionStatus persists transfer status`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()

        pm.tryAcquireConnection(byteArrayOf(1), Priority.NORMAL)
        // Update status — no crash, stored internally
        pm.updateConnectionStatus(byteArrayOf(1), TransferStatus(bytesPerSecond = 2000f, remainingBytes = 10_000L))
        // Unknown peer — no crash (no-op)
        pm.updateConnectionStatus(byteArrayOf(99), TransferStatus(bytesPerSecond = 100f, remainingBytes = 1_000L))
    }

    @Test
    fun `cancelAllGrace with no grace entries is a no-op`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()
        pm.cancelAllGrace() // empty map — should not crash
    }

    // ── Tier-downgrade drain ──────────────────────────────────────────────────

    @Test
    fun `tier downgrade drains excess idle connections immediately`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()
        testScheduler.runCurrent()

        // Acquire 5 connections at PERFORMANCE (max=6)
        repeat(5) { pm.tryAcquireConnection(byteArrayOf(it.toByte()), Priority.NORMAL) }

        val evictions = mutableListOf<ByteArray>()
        val job = launch { pm.evictionRequests.collect { evictions.add(it) } }
        testScheduler.runCurrent()

        // Downgrade to BALANCED (max=4) → excess=1 → drain idle → onEvict lambda executes
        pm.setCustomMode(PowerTier.BALANCED)
        testScheduler.runCurrent()

        assertEquals(1, evictions.size)
        job.cancel()
    }

    @Test
    fun `tier downgrade drains excess active connections via grace period`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()
        testScheduler.runCurrent()

        // Acquire 5 connections, all with active transfers
        repeat(5) { i ->
            val peer = byteArrayOf(i.toByte())
            pm.tryAcquireConnection(peer, Priority.NORMAL)
            pm.updateConnectionStatus(
                peer,
                TransferStatus(bytesPerSecond = 5_000f, remainingBytes = 100_000L),
            )
        }

        val evictions = mutableListOf<ByteArray>()
        val job = launch { pm.evictionRequests.collect { evictions.add(it) } }
        testScheduler.runCurrent()

        // Downgrade → excess=1 → drain active → grace period starts
        pm.setCustomMode(PowerTier.BALANCED)
        testScheduler.runCurrent()
        assertEquals(0, evictions.size) // not evicted yet

        // Advance past grace period → onEvict lambda fires
        testScheduler.advanceTimeBy(fastConfig.maxEvictionGracePeriodMs + 100L)
        testScheduler.runCurrent()
        assertEquals(1, evictions.size)
        job.cancel()
    }

    @Test
    fun `tier downgrade no excess does not drain`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()
        testScheduler.runCurrent()

        // Only 2 connections at PERFORMANCE; BALANCED allows 4 → no excess
        pm.tryAcquireConnection(byteArrayOf(1), Priority.NORMAL)
        pm.tryAcquireConnection(byteArrayOf(2), Priority.NORMAL)

        val evictions = mutableListOf<ByteArray>()
        val job = launch { pm.evictionRequests.collect { evictions.add(it) } }
        testScheduler.runCurrent()

        pm.setCustomMode(PowerTier.BALANCED)
        testScheduler.runCurrent()
        assertEquals(0, evictions.size)
        job.cancel()
    }

    @Test
    fun `cancelAllGrace with active grace entries evicts all immediately`() = runTest {
        val stub = StubBatteryMonitor(level = 0.90f)
        val pm = PowerManager(backgroundScope, stub, { testScheduler.currentTime }, fastConfig)
        testScheduler.runCurrent()
        pm.onFirstConnectionEstablished()
        testScheduler.runCurrent()

        // 5 connections with active transfers
        repeat(5) { i ->
            val peer = byteArrayOf(i.toByte())
            pm.tryAcquireConnection(peer, Priority.NORMAL)
            pm.updateConnectionStatus(
                peer,
                TransferStatus(bytesPerSecond = 5_000f, remainingBytes = 100_000L),
            )
        }

        val evictions = mutableListOf<ByteArray>()
        val job = launch { pm.evictionRequests.collect { evictions.add(it) } }
        testScheduler.runCurrent()

        pm.setCustomMode(PowerTier.BALANCED)
        testScheduler.runCurrent()
        assertEquals(0, evictions.size) // grace started

        pm.cancelAllGrace() // should evict immediately
        testScheduler.runCurrent()
        assertEquals(1, evictions.size)
        job.cancel()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ConnectionLimiter unit tests
// ─────────────────────────────────────────────────────────────────────────────

class ConnectionLimiterTest {

    @Test
    fun `tryAcquire returns true for already registered peer without re-adding`() {
        val limiter = ConnectionLimiter(maxConnections = 3)
        assertTrue(limiter.tryAcquire(byteArrayOf(1), Priority.NORMAL))
        assertTrue(limiter.tryAcquire(byteArrayOf(1), Priority.NORMAL)) // duplicate → true
        assertEquals(1, limiter.connectionCount())
    }

    @Test
    fun `tryAcquire returns false when at max connections`() {
        val limiter = ConnectionLimiter(maxConnections = 2)
        limiter.tryAcquire(byteArrayOf(1), Priority.NORMAL)
        limiter.tryAcquire(byteArrayOf(2), Priority.NORMAL)
        assertFalse(limiter.tryAcquire(byteArrayOf(3), Priority.HIGH))
    }

    @Test
    fun `updateTransferStatus no-op for unknown peer`() {
        val limiter = ConnectionLimiter(maxConnections = 3)
        // should not crash
        limiter.updateTransferStatus(byteArrayOf(99), TransferStatus(1000f, 500L))
    }

    @Test
    fun `updateTransferStatus updates existing connection`() {
        val limiter = ConnectionLimiter(maxConnections = 3)
        limiter.tryAcquire(byteArrayOf(1), Priority.NORMAL)
        limiter.updateTransferStatus(byteArrayOf(1), TransferStatus(2000f, 5000L))
        val conn = limiter.currentConnections().first { it.peerId.contentEquals(byteArrayOf(1)) }
        assertNotNull(conn.transferStatus)
        assertEquals(2000f, conn.transferStatus!!.bytesPerSecond)
    }

    @Test
    fun `release removes connection`() {
        val limiter = ConnectionLimiter(maxConnections = 3)
        limiter.tryAcquire(byteArrayOf(1), Priority.NORMAL)
        assertEquals(1, limiter.connectionCount())
        limiter.release(byteArrayOf(1))
        assertEquals(0, limiter.connectionCount())
    }

    @Test
    fun `updateMaxConnections changes limit`() {
        val limiter = ConnectionLimiter(maxConnections = 2)
        limiter.tryAcquire(byteArrayOf(1), Priority.NORMAL)
        limiter.tryAcquire(byteArrayOf(2), Priority.NORMAL)
        assertFalse(limiter.tryAcquire(byteArrayOf(3), Priority.NORMAL))

        limiter.updateMaxConnections(3)
        assertTrue(limiter.tryAcquire(byteArrayOf(3), Priority.NORMAL))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TieredShedder unit tests
// ─────────────────────────────────────────────────────────────────────────────

class TieredShedderTest {

    private val shedder = TieredShedder()
    private val minThroughput = 1_024f

    @Test
    fun `evaluate returns Accept when below max connections`() {
        val result = shedder.evaluate(byteArrayOf(1), Priority.NORMAL, emptyList(), 4, minThroughput)
        assertEquals(EvictionDecision.Accept, result)
    }

    @Test
    fun `evaluate returns Reject when at max and no lower-priority candidates`() {
        val conns =
            listOf(
                ManagedConnection(byteArrayOf(1), Priority.HIGH),
                ManagedConnection(byteArrayOf(2), Priority.HIGH),
            )
        val result = shedder.evaluate(byteArrayOf(3), Priority.NORMAL, conns, 2, minThroughput)
        assertEquals(EvictionDecision.Reject, result)
    }

    @Test
    fun `evaluate returns EvictAndAccept choosing idle over active`() {
        // Two LOW peers: one idle, one active
        val conns =
            listOf(
                ManagedConnection(
                    byteArrayOf(1),
                    Priority.LOW,
                    TransferStatus(5000f, 100_000L),
                ), // active
                ManagedConnection(byteArrayOf(2), Priority.LOW, null), // idle
            )
        val result = shedder.evaluate(byteArrayOf(3), Priority.HIGH, conns, 2, minThroughput)
        assertTrue(result is EvictionDecision.EvictAndAccept)
        assertContentEquals(byteArrayOf(2), (result as EvictionDecision.EvictAndAccept).evictPeerId)
    }

    @Test
    fun `evaluate isIdleOrStalled covers stalled path`() {
        // Peer with stalled transfer (bps < minThroughput)
        val conns =
            listOf(
                ManagedConnection(byteArrayOf(1), Priority.LOW, TransferStatus(100f, 10_000L))
            )
        val result = shedder.evaluate(byteArrayOf(2), Priority.HIGH, conns, 1, minThroughput)
        assertTrue(result is EvictionDecision.EvictAndAccept)
    }

    @Test
    fun `evaluate isIdleOrStalled covers active path`() {
        // Peer with active transfer (bps >= minThroughput) — still evictable if only candidate
        val conns =
            listOf(
                ManagedConnection(
                    byteArrayOf(1),
                    Priority.LOW,
                    TransferStatus(5_000f, 10_000L),
                )
            )
        val result = shedder.evaluate(byteArrayOf(2), Priority.HIGH, conns, 1, minThroughput)
        assertTrue(result is EvictionDecision.EvictAndAccept)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GracefulDrainManager unit tests
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class GracefulDrainManagerTest {

    private val testConfig =
        PowerConfig(
            evictionGracePeriodMs = 200L,
            maxEvictionGracePeriodMs = 1_000L,
            minThroughputBytesPerSec = 1_024f,
        )

    @Test
    fun `drain immediately evicts idle connection with null transfer status`() = runTest {
        val mgr = GracefulDrainManager({ testScheduler.currentTime }, testConfig, backgroundScope)
        val evicted = mutableListOf<ByteArray>()
        val conn = ManagedConnection(byteArrayOf(1), Priority.NORMAL, null)

        mgr.drain(listOf(conn)) { evicted.add(it) }
        assertEquals(1, evicted.size)
    }

    @Test
    fun `drain immediately evicts stalled connection`() = runTest {
        val mgr = GracefulDrainManager({ testScheduler.currentTime }, testConfig, backgroundScope)
        val evicted = mutableListOf<ByteArray>()
        val conn =
            ManagedConnection(byteArrayOf(1), Priority.NORMAL, TransferStatus(100f, 1_000L))

        mgr.drain(listOf(conn)) { evicted.add(it) }
        assertEquals(1, evicted.size)
    }

    @Test
    fun `drain gives active connection a grace period then evicts`() = runTest {
        val mgr = GracefulDrainManager({ testScheduler.currentTime }, testConfig, backgroundScope)
        val evicted = mutableListOf<ByteArray>()
        val conn =
            ManagedConnection(byteArrayOf(1), Priority.NORMAL, TransferStatus(5_000f, 50_000L))

        mgr.drain(listOf(conn)) { evicted.add(it) }
        assertEquals(0, evicted.size) // grace period active

        testScheduler.advanceTimeBy(testConfig.maxEvictionGracePeriodMs + 100L)
        testScheduler.runCurrent()
        assertEquals(1, evicted.size)
        assertEquals(1, mgr.activeGraceCount() + evicted.size) // combined is 1
    }

    @Test
    fun `drain replaces existing timer for same peer`() = runTest {
        val mgr = GracefulDrainManager({ testScheduler.currentTime }, testConfig, backgroundScope)
        val evicted = mutableListOf<ByteArray>()
        val conn =
            ManagedConnection(byteArrayOf(1), Priority.NORMAL, TransferStatus(5_000f, 50_000L))

        mgr.drain(listOf(conn)) { evicted.add(it) }
        assertEquals(1, mgr.activeGraceCount())

        // Drain same peer again — old timer cancelled, new one starts
        mgr.drain(listOf(conn)) { evicted.add(it) }
        assertEquals(1, mgr.activeGraceCount()) // still just one timer

        testScheduler.advanceTimeBy(testConfig.maxEvictionGracePeriodMs + 100L)
        testScheduler.runCurrent()
        assertEquals(1, evicted.size) // only one eviction
    }

    @Test
    fun `cancelAllGrace with active entries evicts all immediately`() = runTest {
        val mgr = GracefulDrainManager({ testScheduler.currentTime }, testConfig, backgroundScope)
        val evicted = mutableListOf<ByteArray>()
        val conn1 =
            ManagedConnection(byteArrayOf(1), Priority.NORMAL, TransferStatus(5_000f, 50_000L))
        val conn2 =
            ManagedConnection(byteArrayOf(2), Priority.NORMAL, TransferStatus(5_000f, 50_000L))

        mgr.drain(listOf(conn1, conn2)) { evicted.add(it) }
        assertEquals(2, mgr.activeGraceCount())
        assertEquals(0, evicted.size)

        mgr.cancelAllGrace()
        assertEquals(0, mgr.activeGraceCount())
        assertEquals(2, evicted.size)
    }

    @Test
    fun `drain idle connection with existing grace timer cancels old timer`() = runTest {
        val mgr = GracefulDrainManager({ testScheduler.currentTime }, testConfig, backgroundScope)
        val evicted = mutableListOf<ByteArray>()

        // Start grace period
        val activeConn =
            ManagedConnection(byteArrayOf(1), Priority.NORMAL, TransferStatus(5_000f, 50_000L))
        mgr.drain(listOf(activeConn)) { evicted.add(it) }
        assertEquals(1, mgr.activeGraceCount())

        // Now drain same peer as idle → cancels grace, evicts immediately
        val idleConn = ManagedConnection(byteArrayOf(1), Priority.NORMAL, null)
        mgr.drain(listOf(idleConn)) { evicted.add(it) }

        assertEquals(0, mgr.activeGraceCount())
        assertEquals(1, evicted.size) // immediate eviction
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PowerProfile tests
// ─────────────────────────────────────────────────────────────────────────────

class PowerProfileTest {

    private val config = PowerConfig()

    @Test
    fun `forTier PERFORMANCE returns correct spec values`() {
        val profile = PowerProfile.forTier(PowerTier.PERFORMANCE, config)
        assertEquals(80, profile.scanDutyPercent)
        assertEquals(250L, profile.adIntervalMs)
        assertEquals(5_000L, profile.keepaliveMs)
        assertEquals(1_500L, profile.presenceTimeoutMs)
        assertEquals(1_250L, profile.sweepIntervalMs)
        assertEquals(config.performanceMaxConnections, profile.maxConnections)
    }

    @Test
    fun `forTier BALANCED returns correct spec values`() {
        val profile = PowerProfile.forTier(PowerTier.BALANCED, config)
        assertEquals(50, profile.scanDutyPercent)
        assertEquals(500L, profile.adIntervalMs)
        assertEquals(15_000L, profile.keepaliveMs)
        assertEquals(4_000L, profile.presenceTimeoutMs)
        assertEquals(2_500L, profile.sweepIntervalMs)
        assertEquals(config.balancedMaxConnections, profile.maxConnections)
    }

    @Test
    fun `forTier POWER_SAVER returns correct spec values`() {
        val profile = PowerProfile.forTier(PowerTier.POWER_SAVER, config)
        assertEquals(17, profile.scanDutyPercent)
        assertEquals(1_000L, profile.adIntervalMs)
        assertEquals(30_000L, profile.keepaliveMs)
        assertEquals(10_000L, profile.presenceTimeoutMs)
        assertEquals(5_000L, profile.sweepIntervalMs)
        assertEquals(config.powerSaverMaxConnections, profile.maxConnections)
    }

    @Test
    fun `PowerProfile equals same reference`() {
        val p = PowerProfile.forTier(PowerTier.PERFORMANCE, config)
        assertEquals(p, p) // same reference → true
    }

    @Test
    fun `PowerProfile equals different instances same values`() {
        val p1 = PowerProfile.forTier(PowerTier.PERFORMANCE, config)
        val p2 = PowerProfile.forTier(PowerTier.PERFORMANCE, config)
        assertEquals(p1, p2)
    }

    @Test
    fun `PowerProfile not equals different values`() {
        val p1 = PowerProfile.forTier(PowerTier.PERFORMANCE, config)
        val p2 = PowerProfile.forTier(PowerTier.BALANCED, config)
        assertNotEquals(p1, p2)
    }

    @Test
    fun `PowerProfile hashCode consistent`() {
        val p = PowerProfile.forTier(PowerTier.PERFORMANCE, config)
        assertEquals(p.hashCode(), p.hashCode())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BleConnectionParameterPolicy tests
// ─────────────────────────────────────────────────────────────────────────────

class BleConnectionParameterPolicyTest {

    @Test
    fun `BULK constant has correct parameters`() {
        assertEquals(7.5f, BleConnectionParameterPolicy.BULK.intervalMs)
        assertEquals(0, BleConnectionParameterPolicy.BULK.slaveLatency)
    }

    @Test
    fun `ACTIVE constant has correct parameters`() {
        assertEquals(15f, BleConnectionParameterPolicy.ACTIVE.intervalMs)
        assertEquals(0, BleConnectionParameterPolicy.ACTIVE.slaveLatency)
    }

    @Test
    fun `IDLE constant has correct parameters`() {
        assertEquals(100f, BleConnectionParameterPolicy.IDLE.intervalMs)
        assertEquals(4, BleConnectionParameterPolicy.IDLE.slaveLatency)
    }

    @Test
    fun `ConnectionState enum has three values`() {
        assertEquals(3, BleConnectionParameterPolicy.ConnectionState.entries.size)
    }

    @Test
    fun `ConnectionParameters equals same reference`() {
        val p = BleConnectionParameterPolicy.BULK
        assertEquals(p, p)
    }

    @Test
    fun `ConnectionParameters equals different instances same values`() {
        val p1 = BleConnectionParameterPolicy.ConnectionParameters(7.5f, 0)
        val p2 = BleConnectionParameterPolicy.ConnectionParameters(7.5f, 0)
        assertEquals(p1, p2)
    }

    @Test
    fun `ConnectionParameters not equals different values`() {
        assertNotEquals(BleConnectionParameterPolicy.BULK, BleConnectionParameterPolicy.ACTIVE)
    }

    @Test
    fun `ConnectionParameters hashCode consistent`() {
        val p = BleConnectionParameterPolicy.BULK
        assertEquals(p.hashCode(), p.hashCode())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PeerKey equals/hashCode branch coverage
// ─────────────────────────────────────────────────────────────────────────────

class PeerKeyTest {

    @Test
    fun `equals same reference returns true`() {
        val k = PeerKey(byteArrayOf(1, 2, 3))
        assertEquals(k, k)
    }

    @Test
    fun `equals null returns false`() {
        val k = PeerKey(byteArrayOf(1, 2, 3))
        assertFalse(k.equals(null))
    }

    @Test
    fun `equals wrong type returns false`() {
        val k = PeerKey(byteArrayOf(1, 2, 3))
        assertFalse(k.equals("not a PeerKey"))
    }

    @Test
    fun `equals same bytes returns true`() {
        val k1 = PeerKey(byteArrayOf(1, 2, 3))
        val k2 = PeerKey(byteArrayOf(1, 2, 3))
        assertEquals(k1, k2)
    }

    @Test
    fun `equals different bytes returns false`() {
        val k1 = PeerKey(byteArrayOf(1, 2, 3))
        val k2 = PeerKey(byteArrayOf(4, 5, 6))
        assertNotEquals(k1, k2)
    }

    @Test
    fun `hashCode consistent with equals`() {
        val k1 = PeerKey(byteArrayOf(1, 2, 3))
        val k2 = PeerKey(byteArrayOf(1, 2, 3))
        assertEquals(k1.hashCode(), k2.hashCode())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ManagedConnection equals/hashCode branch coverage
// ─────────────────────────────────────────────────────────────────────────────

class ManagedConnectionTest {

    private val base =
        ManagedConnection(
            peerId = byteArrayOf(1, 2),
            priority = Priority.NORMAL,
            transferStatus = TransferStatus(100f, 500L),
        )

    @Test
    fun `equals same reference returns true`() {
        assertEquals(base, base)
    }

    @Test
    fun `equals null returns false`() {
        assertFalse(base.equals(null))
    }

    @Test
    fun `equals wrong type returns false`() {
        assertFalse(base.equals("not a connection"))
    }

    @Test
    fun `equals all fields equal returns true`() {
        val other =
            ManagedConnection(
                peerId = byteArrayOf(1, 2),
                priority = Priority.NORMAL,
                transferStatus = TransferStatus(100f, 500L),
            )
        assertEquals(base, other)
    }

    @Test
    fun `equals different peerId returns false`() {
        val other = base.copy(peerId = byteArrayOf(9, 9))
        assertNotEquals(base, other)
    }

    @Test
    fun `equals different priority returns false`() {
        val other = base.copy(priority = Priority.HIGH)
        assertNotEquals(base, other)
    }

    @Test
    fun `equals different transferStatus returns false`() {
        val other = base.copy(transferStatus = TransferStatus(999f, 999L))
        assertNotEquals(base, other)
    }

    @Test
    fun `hashCode consistent`() {
        val a = ManagedConnection(byteArrayOf(1, 2), Priority.NORMAL, TransferStatus(100f, 500L))
        val b = ManagedConnection(byteArrayOf(1, 2), Priority.NORMAL, TransferStatus(100f, 500L))
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `hashCode differs for different peerId`() {
        val a = ManagedConnection(byteArrayOf(1), Priority.NORMAL, null)
        val b = ManagedConnection(byteArrayOf(2), Priority.NORMAL, null)
        assertNotEquals(a.hashCode(), b.hashCode())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// EvictionDecision.EvictAndAccept equals/hashCode branch coverage
// ─────────────────────────────────────────────────────────────────────────────

class EvictAndAcceptTest {

    @Test
    fun `equals same reference returns true`() {
        val e = EvictionDecision.EvictAndAccept(byteArrayOf(1, 2))
        assertEquals(e, e)
    }

    @Test
    fun `equals null returns false`() {
        val e = EvictionDecision.EvictAndAccept(byteArrayOf(1, 2))
        assertFalse(e.equals(null))
    }

    @Test
    fun `equals wrong type returns false`() {
        val e = EvictionDecision.EvictAndAccept(byteArrayOf(1, 2))
        assertFalse(e.equals(EvictionDecision.Accept))
    }

    @Test
    fun `equals same bytes returns true`() {
        val e1 = EvictionDecision.EvictAndAccept(byteArrayOf(1, 2))
        val e2 = EvictionDecision.EvictAndAccept(byteArrayOf(1, 2))
        assertEquals(e1, e2)
    }

    @Test
    fun `equals different bytes returns false`() {
        val e1 = EvictionDecision.EvictAndAccept(byteArrayOf(1, 2))
        val e2 = EvictionDecision.EvictAndAccept(byteArrayOf(3, 4))
        assertNotEquals(e1, e2)
    }

    @Test
    fun `hashCode consistent with equals`() {
        val e1 = EvictionDecision.EvictAndAccept(byteArrayOf(1, 2))
        val e2 = EvictionDecision.EvictAndAccept(byteArrayOf(1, 2))
        assertEquals(e1.hashCode(), e2.hashCode())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TransferStatus data class coverage
// ─────────────────────────────────────────────────────────────────────────────

class TransferStatusTest {

    @Test
    fun `TransferStatus equals same reference`() {
        val ts = TransferStatus(100f, 500L)
        assertEquals(ts, ts)
    }

    @Test
    fun `TransferStatus equals same values`() {
        assertEquals(TransferStatus(100f, 500L), TransferStatus(100f, 500L))
    }

    @Test
    fun `TransferStatus not equals different bytesPerSecond`() {
        assertNotEquals(TransferStatus(100f, 500L), TransferStatus(200f, 500L))
    }

    @Test
    fun `TransferStatus not equals different remainingBytes`() {
        assertNotEquals(TransferStatus(100f, 500L), TransferStatus(100f, 999L))
    }

    @Test
    fun `TransferStatus hashCode consistent`() {
        val ts = TransferStatus(100f, 500L)
        assertEquals(ts.hashCode(), ts.hashCode())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PowerModeEngine direct unit tests (for branches unreachable through PM wrapper)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class PowerModeEngineDirectTest {

    /** Direct evaluateBattery call covering cascading timer-restart else-if branch. */
    @Test
    fun `cascading downgrade timer restart covers else-if branch`() = runTest {
        val config = PowerConfig(hysteresisDelayMs = 100L, batteryPollIntervalMs = 5_000L)
        val stub = StubBatteryMonitor(level = 0.90f)
        var t = 0L
        val engine = PowerModeEngine(backgroundScope, stub, { t }, config)
        engine.onBootstrapEnd() // bootstrapping=false, evaluateBattery at T=0 (same tier)

        // Start downgrade timer for BALANCED
        stub.level = 0.78f
        t = 50L
        engine.evaluateBattery() // existing=null → start timer for BALANCED at startedAt=50

        // Cascade: battery drops further to POWER_SAVER range before delay expires
        stub.level = 0.20f
        t = 80L
        engine.evaluateBattery() // POWER_SAVER.ordinal(2) > BALANCED.ordinal(1) → restart timer

        // Advance past hysteresis delay from restart (80+100=180)
        t = 200L
        engine.evaluateBattery() // now(200)-startedAt(80)=120 >= 100 → POWER_SAVER
        assertEquals(PowerTier.POWER_SAVER, engine.currentTier)
    }

    /** Direct evaluateBattery: keep-timer else branch (target same, timer not expired). */
    @Test
    fun `keep timer else branch when target unchanged`() = runTest {
        val config = PowerConfig(hysteresisDelayMs = 1_000L, batteryPollIntervalMs = 5_000L)
        val stub = StubBatteryMonitor(level = 0.90f)
        var t = 0L
        val engine = PowerModeEngine(backgroundScope, stub, { t }, config)
        engine.onBootstrapEnd()

        stub.level = 0.78f
        t = 0L; engine.evaluateBattery() // start timer for BALANCED at startedAt=0
        t = 100L; engine.evaluateBattery() // same target, keep timer, 100 < 1000
        assertEquals(PowerTier.PERFORMANCE, engine.currentTier) // no transition yet
        t = 1000L; engine.evaluateBattery() // 1000 >= 1000 → BALANCED
        assertEquals(PowerTier.BALANCED, engine.currentTier)
    }
}
