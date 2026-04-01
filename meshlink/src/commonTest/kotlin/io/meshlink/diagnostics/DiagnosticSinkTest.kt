package io.meshlink.diagnostics

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticSinkTest {

    @Test
    fun emitsEventsWithCorrectFieldsViaDrain() {
        var monotonicMillis = 0L
        val sink = DiagnosticSink(bufferCapacity = 3, clock = { monotonicMillis })

        val collected = mutableListOf<DiagnosticEvent>()

        monotonicMillis = 100L
        sink.emit(DiagnosticCode.ROUTE_CHANGED, Severity.INFO, "route to A updated")
        monotonicMillis = 200L
        sink.emit(DiagnosticCode.BUFFER_PRESSURE, Severity.WARN, "buffer at 80%")
        monotonicMillis = 300L
        sink.emit(DiagnosticCode.BLE_STACK_UNRESPONSIVE, Severity.ERROR, "no response 60s")

        @Suppress("DEPRECATION")
        sink.drainTo(collected)
        assertEquals(3, collected.size)

        assertEquals(DiagnosticCode.ROUTE_CHANGED, collected[0].code)
        assertEquals(Severity.INFO, collected[0].severity)
        assertEquals("route to A updated", collected[0].payload)
        assertEquals(100L, collected[0].monotonicMillis)
        assertEquals(0, collected[0].droppedCount)
    }

    @Test
    fun overflowDropsOldestAndRetainsNewest() {
        var monotonicMillis = 0L
        val sink = DiagnosticSink(bufferCapacity = 3, clock = { monotonicMillis })

        for (i in 1..5) {
            monotonicMillis = (100 * i).toLong()
            sink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "event $i")
        }

        val collected = mutableListOf<DiagnosticEvent>()
        @Suppress("DEPRECATION")
        sink.drainTo(collected)
        assertEquals(3, collected.size, "Buffer retains only bufferCapacity events")
        assertEquals("event 3", collected[0].payload)
        assertEquals("event 4", collected[1].payload)
        assertEquals("event 5", collected[2].payload)
    }

    @Test
    fun overflowKeepsNewestEventsInBuffer() {
        var ms = 0L
        val sink = DiagnosticSink(bufferCapacity = 2, clock = { ms })

        ms = 1L; sink.emit(DiagnosticCode.ROUTE_CHANGED, Severity.INFO, "e1")
        ms = 2L; sink.emit(DiagnosticCode.ROUTE_CHANGED, Severity.INFO, "e2")
        ms = 3L; sink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "e3")
        ms = 4L; sink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "e4")
        ms = 5L; sink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "e5")

        val events = mutableListOf<DiagnosticEvent>()
        @Suppress("DEPRECATION")
        sink.drainTo(events)
        assertEquals(2, events.size, "Buffer holds exactly 2")
        assertEquals("e4", events[0].payload)
        assertEquals("e5", events[1].payload)
    }

    @Test
    fun emitAfterDrainStartsFresh() {
        var ms = 0L
        val sink = DiagnosticSink(bufferCapacity = 10, clock = { ms })

        ms = 1L; sink.emit(DiagnosticCode.ROUTE_CHANGED, Severity.INFO, "first")
        val batch1 = mutableListOf<DiagnosticEvent>()
        @Suppress("DEPRECATION")
        sink.drainTo(batch1)
        assertEquals(1, batch1.size)

        val batch2 = mutableListOf<DiagnosticEvent>()
        @Suppress("DEPRECATION")
        sink.drainTo(batch2)
        assertEquals(0, batch2.size, "Second drain should be empty")

        ms = 2L; sink.emit(DiagnosticCode.BUFFER_PRESSURE, Severity.WARN, "second")
        val batch3 = mutableListOf<DiagnosticEvent>()
        @Suppress("DEPRECATION")
        sink.drainTo(batch3)
        assertEquals(1, batch3.size)
        assertEquals(0, batch3[0].droppedCount, "Fresh emit after drain should have 0 drops")
        assertEquals("second", batch3[0].payload)
    }

    // --- SharedFlow reactive API tests ---

    @Test
    fun eventsFlowReceivesEmittedEvents() = runTest {
        var ms = 0L
        val sink = DiagnosticSink(bufferCapacity = 16, clock = { ms })

        val received = mutableListOf<DiagnosticEvent>()
        val job = launch {
            sink.events.take(2).toList(received)
        }
        // Ensure subscriber is active before emitting
        testScheduler.advanceUntilIdle()

        ms = 10L; sink.emit(DiagnosticCode.ROUTE_CHANGED, Severity.INFO, "a")
        ms = 20L; sink.emit(DiagnosticCode.PEER_PRESENCE_EVICTED, Severity.WARN, "b")
        testScheduler.advanceUntilIdle()

        job.join()

        assertEquals(2, received.size)
        assertEquals(DiagnosticCode.ROUTE_CHANGED, received[0].code)
        assertEquals("a", received[0].payload)
        assertEquals(DiagnosticCode.PEER_PRESENCE_EVICTED, received[1].code)
        assertEquals("b", received[1].payload)
    }

    @Test
    fun sharedFlowDropsOldestOnOverflow() = runTest {
        val sink = DiagnosticSink(bufferCapacity = 2, clock = { 0L })

        // Emit 4 events with no active subscriber — buffer holds only the last 2
        repeat(4) { i ->
            sink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "ev$i")
        }

        // A subscriber collecting from now should not see the dropped events.
        // Verify via the legacy drain that buffer retained the newest 2.
        val drained = mutableListOf<DiagnosticEvent>()
        @Suppress("DEPRECATION")
        sink.drainTo(drained)
        assertEquals(2, drained.size)
        assertEquals("ev2", drained[0].payload)
        assertEquals("ev3", drained[1].payload)
    }

    @Test
    fun droppedCountIsAlwaysZeroWithSharedFlow() {
        val sink = DiagnosticSink(bufferCapacity = 2, clock = { 0L })

        repeat(10) {
            sink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "x")
        }

        val events = mutableListOf<DiagnosticEvent>()
        @Suppress("DEPRECATION")
        sink.drainTo(events)
        assertTrue(events.all { it.droppedCount == 0 }, "All events should have droppedCount=0")
    }
}
