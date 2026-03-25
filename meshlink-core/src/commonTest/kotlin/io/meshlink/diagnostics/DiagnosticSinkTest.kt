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
}
