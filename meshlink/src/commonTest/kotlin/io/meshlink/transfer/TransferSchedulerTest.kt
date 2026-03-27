package io.meshlink.transfer

import io.meshlink.transfer.TransferScheduler.PowerMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferSchedulerTest {

    // ── Basic scheduling ────────────────────────────────────────────────

    @Test
    fun singleTransferGetsFullWindow() {
        val scheduler = TransferScheduler()
        scheduler.addTransfer("t1")

        val result = scheduler.nextTransfer(totalWindow = 10)
        assertEquals("t1", result?.transferKey)
        assertEquals(10, result?.chunksToSend)
    }

    @Test
    fun twoTransfersSplitWindowEvenly() {
        val scheduler = TransferScheduler()
        scheduler.addTransfer("t1")
        scheduler.addTransfer("t2")

        val first = scheduler.nextTransfer(totalWindow = 10)
        val second = scheduler.nextTransfer(totalWindow = 10)

        // Round-robin: should alternate between t1 and t2
        assertEquals("t1", first?.transferKey)
        assertEquals("t2", second?.transferKey)
        // Each gets half the window
        assertEquals(5, first?.chunksToSend)
        assertEquals(5, second?.chunksToSend)
    }

    @Test
    fun threeTransfersRoundRobin() {
        val scheduler = TransferScheduler()
        scheduler.addTransfer("a")
        scheduler.addTransfer("b")
        scheduler.addTransfer("c")

        val keys = (1..6).map { scheduler.nextTransfer(totalWindow = 9)?.transferKey }
        assertEquals(listOf("a", "b", "c", "a", "b", "c"), keys)
    }

    @Test
    fun chunkAllocationFairSplitWithRemainder() {
        val scheduler = TransferScheduler()
        scheduler.addTransfer("t1")
        scheduler.addTransfer("t2")
        scheduler.addTransfer("t3")

        // window=10, 3 active → 10/3 = 3 per transfer (integer division, min 1)
        val chunks = (1..3).map { scheduler.nextTransfer(totalWindow = 10)?.chunksToSend }
        assertEquals(listOf(3, 3, 3), chunks)
    }

    @Test
    fun chunkAllocationMinimumIsOne() {
        val scheduler = TransferScheduler()
        scheduler.addTransfer("t1")
        scheduler.addTransfer("t2")
        scheduler.addTransfer("t3")

        // window=0 → each transfer still gets at least 1
        val result = scheduler.nextTransfer(totalWindow = 0)
        assertEquals(1, result?.chunksToSend)
    }

    // ── Empty / edge cases ──────────────────────────────────────────────

    @Test
    fun emptySchedulerReturnsNull() {
        val scheduler = TransferScheduler()
        assertNull(scheduler.nextTransfer(totalWindow = 10))
    }

    @Test
    fun duplicateAddIsIdempotent() {
        val scheduler = TransferScheduler()
        assertTrue(scheduler.addTransfer("t1"))
        assertTrue(scheduler.addTransfer("t1")) // already active — still returns true

        assertEquals(listOf("t1"), scheduler.activeTransferKeys())
        assertEquals(emptyList(), scheduler.waitingTransferKeys())
    }

    @Test
    fun removeNonExistentKeyIsNoOp() {
        val scheduler = TransferScheduler()
        scheduler.addTransfer("t1")
        assertNull(scheduler.removeTransfer("unknown"))
        assertEquals(listOf("t1"), scheduler.activeTransferKeys())
    }

    // ── Concurrency limits per power mode ───────────────────────────────

    @Test
    fun maxConcurrencyPerformanceMode() {
        val scheduler = TransferScheduler()
        scheduler.setPowerMode(PowerMode.PERFORMANCE)
        assertEquals(8, scheduler.maxConcurrent())

        repeat(8) { scheduler.addTransfer("t$it") }
        assertEquals(8, scheduler.activeTransferKeys().size)

        // 9th should be queued
        assertFalse(scheduler.addTransfer("t8"))
        assertEquals(1, scheduler.waitingTransferKeys().size)
    }

    @Test
    fun maxConcurrencyBalancedMode() {
        val scheduler = TransferScheduler()
        scheduler.setPowerMode(PowerMode.BALANCED)
        assertEquals(4, scheduler.maxConcurrent())

        repeat(4) { scheduler.addTransfer("t$it") }
        assertEquals(4, scheduler.activeTransferKeys().size)

        assertFalse(scheduler.addTransfer("t4"))
        assertEquals(1, scheduler.waitingTransferKeys().size)
    }

    @Test
    fun maxConcurrencyPowerSaverMode() {
        val scheduler = TransferScheduler()
        scheduler.setPowerMode(PowerMode.POWER_SAVER)
        assertEquals(1, scheduler.maxConcurrent())

        assertTrue(scheduler.addTransfer("t0"))
        assertFalse(scheduler.addTransfer("t1"))
        assertEquals(listOf("t0"), scheduler.activeTransferKeys())
        assertEquals(listOf("t1"), scheduler.waitingTransferKeys())
    }

    // ── Queuing and promotion ───────────────────────────────────────────

    @Test
    fun transferQueuedWhenAtMaxConcurrency() {
        val scheduler = TransferScheduler()
        scheduler.setPowerMode(PowerMode.POWER_SAVER) // max 1

        assertTrue(scheduler.addTransfer("first"))
        assertFalse(scheduler.addTransfer("second"))
        assertFalse(scheduler.addTransfer("third"))

        assertTrue(scheduler.isActive("first"))
        assertFalse(scheduler.isActive("second"))
        assertFalse(scheduler.isActive("third"))
        assertEquals(listOf("second", "third"), scheduler.waitingTransferKeys())
    }

    @Test
    fun removingTransferPromotesWaitingOne() {
        val scheduler = TransferScheduler()
        scheduler.setPowerMode(PowerMode.POWER_SAVER)

        scheduler.addTransfer("first")
        scheduler.addTransfer("second")
        scheduler.addTransfer("third")

        val promoted = scheduler.removeTransfer("first")
        assertEquals("second", promoted)
        assertTrue(scheduler.isActive("second"))
        assertEquals(listOf("third"), scheduler.waitingTransferKeys())
    }

    // ── Round-robin wrap-around ─────────────────────────────────────────

    @Test
    fun roundRobinWrapsAroundCorrectly() {
        val scheduler = TransferScheduler()
        scheduler.addTransfer("a")
        scheduler.addTransfer("b")

        // Go through two full cycles
        val keys = (1..4).map { scheduler.nextTransfer(totalWindow = 4)?.transferKey }
        assertEquals(listOf("a", "b", "a", "b"), keys)
    }

    // ── FIFO ordering ───────────────────────────────────────────────────

    @Test
    fun fifoOrderingPreserved() {
        val scheduler = TransferScheduler()
        scheduler.setPowerMode(PowerMode.BALANCED) // max 4

        val order = listOf("delta", "alpha", "charlie", "bravo")
        order.forEach { scheduler.addTransfer(it) }

        // Active list preserves insertion (FIFO) order
        assertEquals(order, scheduler.activeTransferKeys())

        // Round-robin follows FIFO order
        val scheduled = (1..4).map { scheduler.nextTransfer(totalWindow = 4)?.transferKey }
        assertEquals(order, scheduled)
    }

    // ── Power mode change mid-flight ────────────────────────────────────

    @Test
    fun powerModeDowngradeQueuesExcessTransfers() {
        val scheduler = TransferScheduler()
        scheduler.setPowerMode(PowerMode.PERFORMANCE)
        repeat(4) { scheduler.addTransfer("t$it") }
        assertEquals(4, scheduler.activeTransferKeys().size)
        assertEquals(0, scheduler.waitingTransferKeys().size)

        // Downgrade to PowerSaver (max 1) — 3 newest move to waiting
        scheduler.setPowerMode(PowerMode.POWER_SAVER)
        assertEquals(1, scheduler.activeTransferKeys().size)
        assertEquals(3, scheduler.waitingTransferKeys().size)

        // Oldest transfer stays active (FIFO)
        assertEquals("t0", scheduler.activeTransferKeys().first())
    }

    @Test
    fun powerModeUpgradePromotesWaitingTransfers() {
        val scheduler = TransferScheduler()
        scheduler.setPowerMode(PowerMode.POWER_SAVER)

        scheduler.addTransfer("t0")
        scheduler.addTransfer("t1")
        scheduler.addTransfer("t2")
        assertEquals(1, scheduler.activeTransferKeys().size)
        assertEquals(2, scheduler.waitingTransferKeys().size)

        // Upgrade to Balanced (max 4) — all waiting get promoted
        scheduler.setPowerMode(PowerMode.BALANCED)
        assertEquals(3, scheduler.activeTransferKeys().size)
        assertEquals(0, scheduler.waitingTransferKeys().size)
        assertEquals(listOf("t0", "t1", "t2"), scheduler.activeTransferKeys())
    }

    // ── Clear ───────────────────────────────────────────────────────────

    @Test
    fun clearResetsAllState() {
        val scheduler = TransferScheduler()
        scheduler.setPowerMode(PowerMode.POWER_SAVER)
        scheduler.addTransfer("t0")
        scheduler.addTransfer("t1")

        scheduler.clear()

        assertEquals(emptyList(), scheduler.activeTransferKeys())
        assertEquals(emptyList(), scheduler.waitingTransferKeys())
        assertNull(scheduler.nextTransfer(totalWindow = 10))
        // Power mode also resets to default (PERFORMANCE)
        assertEquals(8, scheduler.maxConcurrent())
    }

    // ── Round-robin stability after removal ─────────────────────────────

    @Test
    fun roundRobinStableAfterMiddleRemoval() {
        val scheduler = TransferScheduler()
        scheduler.addTransfer("a")
        scheduler.addTransfer("b")
        scheduler.addTransfer("c")

        // Advance to "b" (current index now at 1 after returning "a")
        assertEquals("a", scheduler.nextTransfer(totalWindow = 6)?.transferKey)

        // Remove "b" (the one about to be scheduled next)
        scheduler.removeTransfer("b")

        // Should continue with "c", not skip it
        assertEquals("c", scheduler.nextTransfer(totalWindow = 4)?.transferKey)
        assertEquals("a", scheduler.nextTransfer(totalWindow = 4)?.transferKey)
    }

    // ── Duplicate add when queued ───────────────────────────────────────

    @Test
    fun duplicateAddWhenQueuedIsIdempotent() {
        val scheduler = TransferScheduler()
        scheduler.setPowerMode(PowerMode.POWER_SAVER)
        scheduler.addTransfer("t0")
        scheduler.addTransfer("t1") // queued

        assertFalse(scheduler.addTransfer("t1")) // already queued — still returns false
        assertEquals(listOf("t1"), scheduler.waitingTransferKeys())
    }
}
