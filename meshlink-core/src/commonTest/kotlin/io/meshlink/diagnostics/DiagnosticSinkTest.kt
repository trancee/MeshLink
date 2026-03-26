package io.meshlink.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticSinkTest {

    @Test
    fun emitsEventsWithSeverityAndTracksDrops() {
        var monotonicMs = 0L
        val sink = DiagnosticSink(bufferCapacity = 3, clock = { monotonicMs })

        val collected = mutableListOf<DiagnosticEvent>()

        // Emit events
        monotonicMs = 100L
        sink.emit(DiagnosticCode.ROUTE_CHANGED, Severity.INFO, "route to A updated")
        monotonicMs = 200L
        sink.emit(DiagnosticCode.BUFFER_PRESSURE, Severity.WARN, "buffer at 80%")
        monotonicMs = 300L
        sink.emit(DiagnosticCode.BLE_STACK_UNRESPONSIVE, Severity.ERROR, "no response 60s")

        // Drain events
        sink.drainTo(collected)
        assertEquals(3, collected.size)

        // Check first event
        assertEquals(DiagnosticCode.ROUTE_CHANGED, collected[0].code)
        assertEquals(Severity.INFO, collected[0].severity)
        assertEquals("route to A updated", collected[0].payload)
        assertEquals(100L, collected[0].monotonicMs)
        assertEquals(0, collected[0].droppedCount)

        // Overflow: emit 4 events with buffer=3 → oldest dropped
        collected.clear()
        for (i in 1..4) {
            monotonicMs = (400 + i).toLong()
            sink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "event $i")
        }
        sink.drainTo(collected)
        assertEquals(3, collected.size)
        // Last event carries the drop count (it was emitted right after overflow)
        assertTrue(collected.last().droppedCount >= 1, "Last event should report drops: ${collected.last().droppedCount}")
    }

    // --- Batch 10 Cycle 7: droppedCount tracks consecutive overflows ---

    @Test
    fun droppedCountTracksConsecutiveOverflowsCorrectly() {
        var ms = 0L
        val sink = DiagnosticSink(bufferCapacity = 2, clock = { ms })

        // Fill buffer: emit 2 events (at capacity)
        ms = 1L; sink.emit(DiagnosticCode.ROUTE_CHANGED, Severity.INFO, "e1")
        ms = 2L; sink.emit(DiagnosticCode.ROUTE_CHANGED, Severity.INFO, "e2")

        // Emit 3 more — each one drops the oldest, incrementing droppedSinceLastEmit
        // But droppedSinceLastEmit resets to 0 after each emit that records it
        ms = 3L; sink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "e3") // drops e1, droppedCount=1 on e3, then resets
        ms = 4L; sink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "e4") // drops e2, droppedCount=1 on e4, then resets
        ms = 5L; sink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "e5") // drops e3, droppedCount=1 on e5, then resets

        val events = mutableListOf<DiagnosticEvent>()
        sink.drainTo(events)
        assertEquals(2, events.size, "Buffer holds exactly 2")

        // Each event should report droppedCount=1 (one drop before each emit)
        assertEquals(1, events[0].droppedCount, "e4 should report 1 drop")
        assertEquals(1, events[1].droppedCount, "e5 should report 1 drop")
        assertEquals("e4", events[0].payload)
        assertEquals("e5", events[1].payload)
    }
}
