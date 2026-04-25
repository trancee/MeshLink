package ch.trancee.meshlink.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TransferFoundationTest {

    // ──────────────────────────────────────────────────────────────────────
    // TransferConfig
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `TransferConfig default values`() {
        val cfg = TransferConfig()
        assertEquals(244, cfg.chunkSize)
        assertEquals(4, cfg.acksBeforeDouble)
        assertEquals(8, cfg.acksBeforeQuad)
        assertEquals(30_000L, cfg.inactivityBaseTimeoutMs)
        assertEquals(3, cfg.maxResumeAttempts)
        assertEquals(5, cfg.maxNackRetries)
        assertEquals(500L, cfg.nackBaseBackoffMs)
    }

    @Test
    fun `TransferConfig custom values override defaults`() {
        val cfg =
            TransferConfig(
                chunkSize = 512,
                acksBeforeDouble = 2,
                acksBeforeQuad = 4,
                inactivityBaseTimeoutMs = 10_000L,
                maxResumeAttempts = 5,
                maxNackRetries = 3,
                nackBaseBackoffMs = 250L,
            )
        assertEquals(512, cfg.chunkSize)
        assertEquals(2, cfg.acksBeforeDouble)
        assertEquals(4, cfg.acksBeforeQuad)
        assertEquals(10_000L, cfg.inactivityBaseTimeoutMs)
        assertEquals(5, cfg.maxResumeAttempts)
        assertEquals(3, cfg.maxNackRetries)
        assertEquals(250L, cfg.nackBaseBackoffMs)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Priority
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `Priority fromWire LOW`() {
        val p = Priority.fromWire((-1).toByte())
        assertEquals(Priority.LOW, p)
        assertEquals((-1).toByte(), Priority.LOW.wire)
    }

    @Test
    fun `Priority fromWire NORMAL`() {
        val p = Priority.fromWire(0.toByte())
        assertEquals(Priority.NORMAL, p)
        assertEquals(0.toByte(), Priority.NORMAL.wire)
    }

    @Test
    fun `Priority fromWire HIGH`() {
        val p = Priority.fromWire(1.toByte())
        assertEquals(Priority.HIGH, p)
        assertEquals(1.toByte(), Priority.HIGH.wire)
    }

    @Test
    fun `Priority fromWire unknown falls back to NORMAL`() {
        val p = Priority.fromWire(99.toByte())
        assertEquals(Priority.NORMAL, p)
    }

    // ──────────────────────────────────────────────────────────────────────
    // FailureReason
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `FailureReason all five values exist`() {
        val values = FailureReason.entries
        assertTrue(FailureReason.INACTIVITY_TIMEOUT in values)
        assertTrue(FailureReason.DEGRADATION_PROBE_FAILED in values)
        assertTrue(FailureReason.BUFFER_FULL_RETRY_EXHAUSTED in values)
        assertTrue(FailureReason.MEMORY_PRESSURE in values)
        assertTrue(FailureReason.RESUME_FAILED in values)
        assertEquals(5, values.size)
    }

    // ──────────────────────────────────────────────────────────────────────
    // TransferEvent — AssemblyComplete
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `AssemblyComplete equals same object`() {
        val e = TransferEvent.AssemblyComplete(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        assertTrue(e.equals(e))
    }

    @Test
    fun `AssemblyComplete equals equal content`() {
        val e1 = TransferEvent.AssemblyComplete(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        val e2 = TransferEvent.AssemblyComplete(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    fun `AssemblyComplete equals different type returns false`() {
        val ac = TransferEvent.AssemblyComplete(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        val tf = TransferEvent.TransferFailed(byteArrayOf(1), FailureReason.INACTIVITY_TIMEOUT)
        assertFalse(ac.equals(tf))
        assertFalse(ac.equals("not-a-transfer-event"))
    }

    @Test
    fun `AssemblyComplete equals different messageId`() {
        val e1 = TransferEvent.AssemblyComplete(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        val e2 = TransferEvent.AssemblyComplete(byteArrayOf(9), byteArrayOf(2), byteArrayOf(3))
        assertNotEquals(e1, e2)
    }

    @Test
    fun `AssemblyComplete equals different payload`() {
        val e1 = TransferEvent.AssemblyComplete(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        val e2 = TransferEvent.AssemblyComplete(byteArrayOf(1), byteArrayOf(9), byteArrayOf(3))
        assertNotEquals(e1, e2)
    }

    @Test
    fun `AssemblyComplete equals different fromPeerId`() {
        val e1 = TransferEvent.AssemblyComplete(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        val e2 = TransferEvent.AssemblyComplete(byteArrayOf(1), byteArrayOf(2), byteArrayOf(9))
        assertNotEquals(e1, e2)
    }

    // ──────────────────────────────────────────────────────────────────────
    // TransferEvent — TransferFailed
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `TransferFailed equals same object`() {
        val e = TransferEvent.TransferFailed(byteArrayOf(1), FailureReason.MEMORY_PRESSURE)
        assertTrue(e.equals(e))
    }

    @Test
    fun `TransferFailed equals equal content`() {
        val e1 = TransferEvent.TransferFailed(byteArrayOf(1), FailureReason.MEMORY_PRESSURE)
        val e2 = TransferEvent.TransferFailed(byteArrayOf(1), FailureReason.MEMORY_PRESSURE)
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    fun `TransferFailed equals different type returns false`() {
        val tf = TransferEvent.TransferFailed(byteArrayOf(1), FailureReason.MEMORY_PRESSURE)
        val ac = TransferEvent.AssemblyComplete(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        assertFalse(tf.equals(ac))
    }

    @Test
    fun `TransferFailed equals different reason`() {
        val e1 = TransferEvent.TransferFailed(byteArrayOf(1), FailureReason.MEMORY_PRESSURE)
        val e2 = TransferEvent.TransferFailed(byteArrayOf(1), FailureReason.RESUME_FAILED)
        assertNotEquals(e1, e2)
    }

    @Test
    fun `TransferFailed equals different messageId`() {
        val e1 = TransferEvent.TransferFailed(byteArrayOf(1), FailureReason.MEMORY_PRESSURE)
        val e2 = TransferEvent.TransferFailed(byteArrayOf(9), FailureReason.MEMORY_PRESSURE)
        assertNotEquals(e1, e2)
    }

    // ──────────────────────────────────────────────────────────────────────
    // TransferEvent — ChunkProgress
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `ChunkProgress equals same object`() {
        val e = TransferEvent.ChunkProgress(byteArrayOf(1), 3, 10)
        assertTrue(e.equals(e))
    }

    @Test
    fun `ChunkProgress equals equal content`() {
        val e1 = TransferEvent.ChunkProgress(byteArrayOf(1), 3, 10)
        val e2 = TransferEvent.ChunkProgress(byteArrayOf(1), 3, 10)
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    fun `ChunkProgress equals different type returns false`() {
        val cp = TransferEvent.ChunkProgress(byteArrayOf(1), 3, 10)
        val tf = TransferEvent.TransferFailed(byteArrayOf(1), FailureReason.MEMORY_PRESSURE)
        assertFalse(cp.equals(tf))
    }

    @Test
    fun `ChunkProgress equals different chunksReceived`() {
        val e1 = TransferEvent.ChunkProgress(byteArrayOf(1), 3, 10)
        val e2 = TransferEvent.ChunkProgress(byteArrayOf(1), 5, 10)
        assertNotEquals(e1, e2)
    }

    @Test
    fun `ChunkProgress equals different totalChunks`() {
        val e1 = TransferEvent.ChunkProgress(byteArrayOf(1), 3, 10)
        val e2 = TransferEvent.ChunkProgress(byteArrayOf(1), 3, 20)
        assertNotEquals(e1, e2)
    }

    @Test
    fun `ChunkProgress equals different messageId`() {
        val e1 = TransferEvent.ChunkProgress(byteArrayOf(1), 3, 10)
        val e2 = TransferEvent.ChunkProgress(byteArrayOf(9), 3, 10)
        assertNotEquals(e1, e2)
    }

    // ──────────────────────────────────────────────────────────────────────
    // ChunkSizePolicy
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `ChunkSizePolicy GATT default is 244`() {
        assertEquals(244, ChunkSizePolicy.GATT.size)
    }

    @Test
    fun `ChunkSizePolicy L2CAP default is 4096`() {
        assertEquals(4096, ChunkSizePolicy.L2CAP.size)
    }

    @Test
    fun `ChunkSizePolicy fixed returns specified size`() {
        assertEquals(64, ChunkSizePolicy.fixed(64).size)
        assertEquals(1, ChunkSizePolicy.fixed(1).size)
    }

    // ──────────────────────────────────────────────────────────────────────
    // ResumeCalculator
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `ResumeCalculator alignedOffset rounds down to chunk boundary`() {
        assertEquals(64L, ResumeCalculator.alignedOffset(100L, 64))
        assertEquals(0L, ResumeCalculator.alignedOffset(63L, 64))
        assertEquals(128L, ResumeCalculator.alignedOffset(191L, 64))
    }

    @Test
    fun `ResumeCalculator alignedOffset zero bytes received`() {
        assertEquals(0L, ResumeCalculator.alignedOffset(0L, 64))
    }

    @Test
    fun `ResumeCalculator alignedOffset exactly on boundary`() {
        assertEquals(128L, ResumeCalculator.alignedOffset(128L, 64))
    }

    @Test
    fun `ResumeCalculator alignedOffset chunkSize one`() {
        assertEquals(42L, ResumeCalculator.alignedOffset(42L, 1))
    }

    // ──────────────────────────────────────────────────────────────────────
    // SackTracker
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `SackTracker initial state canSend within window`() {
        val tracker = SackTracker()
        assertTrue(tracker.canSend(0u))
        assertTrue(tracker.canSend(63u))
    }

    @Test
    fun `SackTracker initial state canSend at window edge`() {
        val tracker = SackTracker()
        assertFalse(tracker.canSend(64u))
        assertFalse(tracker.canSend(100u))
    }

    @Test
    fun `SackTracker initial state isMissing within window`() {
        val tracker = SackTracker()
        assertTrue(tracker.isMissing(0u))
        assertTrue(tracker.isMissing(63u))
    }

    @Test
    fun `SackTracker initial state isMissing outside window`() {
        val tracker = SackTracker()
        assertFalse(tracker.isMissing(64u))
        assertFalse(tracker.isMissing(100u))
    }

    @Test
    fun `SackTracker initial buildAck returns sentinel state`() {
        val tracker = SackTracker()
        val (ackSeq, bitmap) = tracker.buildAck()
        assertEquals(UShort.MAX_VALUE, ackSeq)
        assertEquals(0uL, bitmap)
    }

    @Test
    fun `SackTracker receive in order advances ackSequence`() {
        val tracker = SackTracker()
        tracker.markReceived(0u)
        tracker.markReceived(1u)
        tracker.markReceived(2u)
        val (ackSeq, bitmap) = tracker.buildAck()
        assertEquals(2u.toUShort(), ackSeq)
        assertEquals(0uL, bitmap)
        assertFalse(tracker.isMissing(0u)) // already acked — outside window
        assertFalse(tracker.isMissing(2u)) // acked — off=65534 >= 64 → false
    }

    @Test
    fun `SackTracker receive out of order sets bitmask`() {
        val tracker = SackTracker()
        tracker.markReceived(2u) // chunk 0 and 1 still missing

        val (ackSeq, bitmap) = tracker.buildAck()
        assertEquals(UShort.MAX_VALUE, ackSeq) // no consecutive advance
        // bit 2 set: offset(2) = (2+65536-65535-1)&0xFFFF = 2
        assertEquals(4uL, bitmap)

        assertTrue(tracker.isMissing(0u))  // bit 0 clear
        assertTrue(tracker.isMissing(1u))  // bit 1 clear
        assertFalse(tracker.isMissing(2u)) // bit 2 set
    }

    @Test
    fun `SackTracker out-of-order receive then gap filled advances ackSequence`() {
        val tracker = SackTracker()
        tracker.markReceived(2u)  // bitmap bit 2 set
        tracker.markReceived(0u)  // fills bit 0, advances once; bitmap has bit for chunk 2 remaining
        tracker.markReceived(1u)  // fills the gap; advance through chunk 1 and chunk 2
        val (ackSeq, bitmap) = tracker.buildAck()
        assertEquals(2u.toUShort(), ackSeq)
        assertEquals(0uL, bitmap)
    }

    @Test
    fun `SackTracker duplicate receive after ack is no-op`() {
        val tracker = SackTracker()
        tracker.markReceived(0u) // advances ackSeq to 0
        tracker.markReceived(0u) // chunk 0 offset is now 65535 (>= 64) — no-op
        val (ackSeq, bitmap) = tracker.buildAck()
        assertEquals(0u.toUShort(), ackSeq)
        assertEquals(0uL, bitmap)
    }

    @Test
    fun `SackTracker 64-chunk window boundary`() {
        val tracker = SackTracker()
        // chunk 63 is the last in the window; chunk 64 is outside
        tracker.markReceived(63u) // offset 63 — last slot
        val (ackSeq, bitmap) = tracker.buildAck()
        assertEquals(UShort.MAX_VALUE, ackSeq) // no consecutive advance from front
        assertEquals(1uL shl 63, bitmap) // bit 63 set
        assertFalse(tracker.isMissing(63u)) // bit 63 set → not missing
        assertFalse(tracker.isMissing(64u)) // outside window → false
        assertTrue(tracker.canSend(63u))
        assertFalse(tracker.canSend(64u))
    }

    @Test
    fun `SackTracker window slides after consecutive advance`() {
        val tracker = SackTracker()
        // receive 0..2, then window is [3..66]
        tracker.markReceived(0u)
        tracker.markReceived(1u)
        tracker.markReceived(2u)
        // chunk 66 is at offset (66+65536-2-1)&0xFFFF = 63 → canSend
        assertTrue(tracker.canSend(66u))
        // chunk 67 is at offset 64 → canSend = false
        assertFalse(tracker.canSend(67u))
        assertTrue(tracker.isMissing(3u))   // in window, not received
        assertTrue(tracker.isMissing(66u))  // in window, not received (off=63, bit clear)
    }

    @Test
    fun `SackTracker isMissing returns false for received chunk inside window`() {
        val tracker = SackTracker()
        tracker.markReceived(5u) // offset 5 from initial ackSeq=65535
        assertFalse(tracker.isMissing(5u)) // bit 5 set
        assertTrue(tracker.isMissing(4u))  // bit 4 clear
    }

    // ──────────────────────────────────────────────────────────────────────
    // ObservationRateController
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `ObservationRateController starts at rate 1`() {
        val ctrl = ObservationRateController(TransferConfig())
        assertEquals(1, ctrl.currentRate())
    }

    @Test
    fun `ObservationRateController stays at 1 below threshold`() {
        val ctrl = ObservationRateController(TransferConfig(acksBeforeDouble = 4))
        repeat(3) { ctrl.onAck() } // one less than threshold
        assertEquals(1, ctrl.currentRate())
    }

    @Test
    fun `ObservationRateController ramps to 2 after acksBeforeDouble ACKs`() {
        val ctrl = ObservationRateController(TransferConfig(acksBeforeDouble = 4))
        repeat(4) { ctrl.onAck() }
        assertEquals(2, ctrl.currentRate())
    }

    @Test
    fun `ObservationRateController stays at 2 below quad threshold`() {
        val ctrl = ObservationRateController(TransferConfig(acksBeforeDouble = 4, acksBeforeQuad = 8))
        repeat(4) { ctrl.onAck() } // advance to rate 2 (resets counter to 0)
        repeat(7) { ctrl.onAck() } // one less than quad threshold
        assertEquals(2, ctrl.currentRate())
    }

    @Test
    fun `ObservationRateController ramps to 4 after acksBeforeQuad more ACKs`() {
        val ctrl = ObservationRateController(TransferConfig(acksBeforeDouble = 4, acksBeforeQuad = 8))
        repeat(4) { ctrl.onAck() } // → rate 2
        repeat(8) { ctrl.onAck() } // → rate 4
        assertEquals(4, ctrl.currentRate())
    }

    @Test
    fun `ObservationRateController stays at 4 after additional ACKs`() {
        val ctrl = ObservationRateController(TransferConfig(acksBeforeDouble = 4, acksBeforeQuad = 8))
        repeat(4) { ctrl.onAck() } // → rate 2
        repeat(8) { ctrl.onAck() } // → rate 4
        repeat(10) { ctrl.onAck() } // still 4
        assertEquals(4, ctrl.currentRate())
    }

    @Test
    fun `ObservationRateController timeout at rate 1 keeps at 1`() {
        val ctrl = ObservationRateController(TransferConfig())
        ctrl.onTimeout()
        assertEquals(1, ctrl.currentRate())
    }

    @Test
    fun `ObservationRateController timeout at rate 2 resets to 1`() {
        val ctrl = ObservationRateController(TransferConfig(acksBeforeDouble = 4))
        repeat(4) { ctrl.onAck() } // → rate 2
        ctrl.onTimeout()
        assertEquals(1, ctrl.currentRate())
    }

    @Test
    fun `ObservationRateController timeout at rate 4 resets to 1`() {
        val ctrl = ObservationRateController(TransferConfig(acksBeforeDouble = 4, acksBeforeQuad = 8))
        repeat(4) { ctrl.onAck() } // → rate 2
        repeat(8) { ctrl.onAck() } // → rate 4
        ctrl.onTimeout()
        assertEquals(1, ctrl.currentRate())
        // After reset, ramp works again
        repeat(4) { ctrl.onAck() }
        assertEquals(2, ctrl.currentRate())
    }

    @Test
    fun `ObservationRateController partial progress then timeout resets counter`() {
        val ctrl = ObservationRateController(TransferConfig(acksBeforeDouble = 4))
        repeat(3) { ctrl.onAck() } // 3/4 towards doubling
        ctrl.onTimeout() // reset
        repeat(3) { ctrl.onAck() } // restart: 3/4 again
        assertEquals(1, ctrl.currentRate()) // not yet at threshold
        ctrl.onAck() // 4th ACK
        assertEquals(2, ctrl.currentRate())
    }

    // ──────────────────────────────────────────────────────────────────────
    // TransferScheduler
    // ──────────────────────────────────────────────────────────────────────

    private val hId = byteArrayOf(0x01)
    private val nId = byteArrayOf(0x02)
    private val lId = byteArrayOf(0x03)
    private val h2Id = byteArrayOf(0x04)

    @Test
    fun `TransferScheduler empty returns empty batch`() {
        val scheduler = TransferScheduler(maxConcurrent = 4)
        assertTrue(scheduler.nextBatch().isEmpty())
        assertEquals(4, scheduler.maxConcurrent)
    }

    @Test
    fun `TransferScheduler single HIGH session always scheduled`() {
        val scheduler = TransferScheduler(maxConcurrent = 4)
        scheduler.register(hId, Priority.HIGH)
        // Two rounds (odd and even)
        val b1 = scheduler.nextBatch()
        val b2 = scheduler.nextBatch()
        assertTrue(b1.any { it.contentEquals(hId) })
        assertTrue(b2.any { it.contentEquals(hId) })
    }

    @Test
    fun `TransferScheduler LOW included on odd round excluded on even`() {
        val scheduler = TransferScheduler(maxConcurrent = 4)
        scheduler.register(hId, Priority.HIGH)
        scheduler.register(nId, Priority.NORMAL)
        scheduler.register(lId, Priority.LOW)

        val b1 = scheduler.nextBatch() // call 1 — odd — LOW included
        val b2 = scheduler.nextBatch() // call 2 — even — LOW excluded

        assertTrue(b1.any { it.contentEquals(lId) })
        assertFalse(b2.any { it.contentEquals(lId) })
        // HIGH and NORMAL always present
        assertTrue(b1.any { it.contentEquals(hId) })
        assertTrue(b1.any { it.contentEquals(nId) })
        assertTrue(b2.any { it.contentEquals(hId) })
        assertTrue(b2.any { it.contentEquals(nId) })
    }

    @Test
    fun `TransferScheduler maxConcurrent limits batch size`() {
        val scheduler = TransferScheduler(maxConcurrent = 1)
        scheduler.register(hId, Priority.HIGH)
        scheduler.register(nId, Priority.NORMAL)
        scheduler.register(lId, Priority.LOW)

        val batch = scheduler.nextBatch()
        assertEquals(1, batch.size)
        // HIGH has highest weight — should be the one returned
        assertTrue(batch.first().contentEquals(hId))
    }

    @Test
    fun `TransferScheduler HIGH fills batch before NORMAL when maxConcurrent limited`() {
        val scheduler = TransferScheduler(maxConcurrent = 2)
        scheduler.register(hId, Priority.HIGH)
        scheduler.register(h2Id, Priority.HIGH)
        scheduler.register(nId, Priority.NORMAL)

        val batch = scheduler.nextBatch()
        // Two HIGH sessions fill maxConcurrent=2; NORMAL not included
        assertEquals(2, batch.size)
        assertTrue(batch.any { it.contentEquals(hId) })
        assertTrue(batch.any { it.contentEquals(h2Id) })
        assertFalse(batch.any { it.contentEquals(nId) })
    }

    @Test
    fun `TransferScheduler updateMaxConcurrent changes limit`() {
        val scheduler = TransferScheduler(maxConcurrent = 1)
        scheduler.register(hId, Priority.HIGH)
        scheduler.register(nId, Priority.NORMAL)

        assertEquals(1, scheduler.maxConcurrent)
        scheduler.updateMaxConcurrent(4)
        assertEquals(4, scheduler.maxConcurrent)

        val batch = scheduler.nextBatch()
        assertTrue(batch.size == 2)
    }

    @Test
    fun `TransferScheduler deregister removes session from batches`() {
        val scheduler = TransferScheduler(maxConcurrent = 4)
        scheduler.register(hId, Priority.HIGH)
        scheduler.register(nId, Priority.NORMAL)

        scheduler.deregister(hId)
        val batch = scheduler.nextBatch()
        assertFalse(batch.any { it.contentEquals(hId) })
        assertTrue(batch.any { it.contentEquals(nId) })
    }

    @Test
    fun `TransferScheduler deregister non-existent id is safe`() {
        val scheduler = TransferScheduler(maxConcurrent = 4)
        scheduler.register(hId, Priority.HIGH)
        scheduler.deregister(byteArrayOf(0xFF.toByte())) // not registered
        val batch = scheduler.nextBatch()
        assertTrue(batch.any { it.contentEquals(hId) })
    }

    @Test
    fun `TransferScheduler register replaces existing session priority`() {
        val scheduler = TransferScheduler(maxConcurrent = 4)
        scheduler.register(lId, Priority.LOW)
        scheduler.register(lId, Priority.HIGH) // upgrade to HIGH

        // On even round, LOW would be excluded — but this session is now HIGH
        scheduler.nextBatch() // round 1
        val b2 = scheduler.nextBatch() // round 2 (even — LOW excluded)
        // Session is now HIGH, should always appear
        assertTrue(b2.any { it.contentEquals(lId) })
    }

    @Test
    fun `TransferScheduler WRR HIGH appears before NORMAL with maxConcurrent 1`() {
        val scheduler = TransferScheduler(maxConcurrent = 1)
        scheduler.register(nId, Priority.NORMAL)
        scheduler.register(hId, Priority.HIGH)

        // Over multiple calls, HIGH always wins maxConcurrent=1 slot
        val results = (1..4).map { scheduler.nextBatch() }
        assertTrue(results.all { batch -> batch.size == 1 && batch.first().contentEquals(hId) })
    }

    @Test
    fun `TransferScheduler only LOW sessions skipped on even round`() {
        val scheduler = TransferScheduler(maxConcurrent = 4)
        scheduler.register(lId, Priority.LOW)

        val b1 = scheduler.nextBatch() // odd — LOW included
        val b2 = scheduler.nextBatch() // even — LOW excluded
        val b3 = scheduler.nextBatch() // odd — LOW included again

        assertTrue(b1.any { it.contentEquals(lId) })
        assertTrue(b2.isEmpty())
        assertTrue(b3.any { it.contentEquals(lId) })
    }
}
