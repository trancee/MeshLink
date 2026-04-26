package ch.trancee.meshlink.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [MeshLinkStateMachine] — verifies all valid and invalid lifecycle transitions, the
 * oscillation bound (3 failed start()s within 60 s → TERMINAL), and the sliding window expiry.
 *
 * Also provides smoke-instantiation of all public [MeshLinkApi] event/data types to satisfy 100 %
 * Kover line coverage on the api package.
 */
class MeshLinkStateTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private val diagnostics = mutableListOf<DiagnosticEvent>()

    private fun fsm(nowMs: () -> Long = { 0L }): MeshLinkStateMachine =
        MeshLinkStateMachine(nowMs = nowMs, onDiagnostic = { diagnostics += it })

    /** Convenience: apply a sequence of events and assert each is a Success. */
    private fun MeshLinkStateMachine.applyAll(vararg events: LifecycleEvent) {
        for (event in events) {
            assertIs<TransitionResult.Success>(transition(event))
        }
    }

    // ── Valid transition: UNINITIALIZED → RUNNING ─────────────────────────

    @Test
    fun `UNINITIALIZED + StartSuccess → RUNNING`() {
        val machine = fsm()
        val result = machine.transition(LifecycleEvent.StartSuccess)
        assertIs<TransitionResult.Success>(result)
        assertEquals(MeshLinkState.RUNNING, machine.state.value)
    }

    // ── Valid transition: UNINITIALIZED → TERMINAL ────────────────────────

    @Test
    fun `UNINITIALIZED + StartFailure → TERMINAL`() {
        val machine = fsm()
        val result = machine.transition(LifecycleEvent.StartFailure)
        assertIs<TransitionResult.Success>(result)
        assertEquals(MeshLinkState.TERMINAL, machine.state.value)
    }

    // ── Valid transition: RUNNING → PAUSED ────────────────────────────────

    @Test
    fun `RUNNING + Pause → PAUSED`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess)
        val result = machine.transition(LifecycleEvent.Pause)
        assertIs<TransitionResult.Success>(result)
        assertEquals(MeshLinkState.PAUSED, machine.state.value)
    }

    // ── Valid transition: PAUSED → RUNNING ────────────────────────────────

    @Test
    fun `PAUSED + Resume → RUNNING`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.Pause)
        val result = machine.transition(LifecycleEvent.Resume)
        assertIs<TransitionResult.Success>(result)
        assertEquals(MeshLinkState.RUNNING, machine.state.value)
    }

    // ── Valid transition: RUNNING → STOPPED ──────────────────────────────

    @Test
    fun `RUNNING + Stop → STOPPED`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess)
        val result = machine.transition(LifecycleEvent.Stop)
        assertIs<TransitionResult.Success>(result)
        assertEquals(MeshLinkState.STOPPED, machine.state.value)
    }

    // ── Valid transition: PAUSED → STOPPED ───────────────────────────────

    @Test
    fun `PAUSED + Stop → STOPPED`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.Pause)
        val result = machine.transition(LifecycleEvent.Stop)
        assertIs<TransitionResult.Success>(result)
        assertEquals(MeshLinkState.STOPPED, machine.state.value)
    }

    // ── Valid transition: RUNNING → RECOVERABLE ───────────────────────────

    @Test
    fun `RUNNING + TransientFailure → RECOVERABLE`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess)
        val result = machine.transition(LifecycleEvent.TransientFailure)
        assertIs<TransitionResult.Success>(result)
        assertEquals(MeshLinkState.RECOVERABLE, machine.state.value)
    }

    // ── Valid transition: RECOVERABLE → RUNNING ───────────────────────────

    @Test
    fun `RECOVERABLE + StartSuccess → RUNNING`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.TransientFailure)
        val result = machine.transition(LifecycleEvent.StartSuccess)
        assertIs<TransitionResult.Success>(result)
        assertEquals(MeshLinkState.RUNNING, machine.state.value)
    }

    // ── Valid transition: RECOVERABLE → STOPPED ───────────────────────────

    @Test
    fun `RECOVERABLE + Stop → STOPPED`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.TransientFailure)
        val result = machine.transition(LifecycleEvent.Stop)
        assertIs<TransitionResult.Success>(result)
        assertEquals(MeshLinkState.STOPPED, machine.state.value)
    }

    // ── Oscillation bound: 3 failures within 60 s → TERMINAL ─────────────

    @Test
    fun `oscillation bound - 3 failures within 60s transitions to TERMINAL`() {
        var fakeTime = 0L
        val machine = fsm(nowMs = { fakeTime })
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.TransientFailure)

        // First failure: stays RECOVERABLE
        fakeTime = 0L
        val r1 = machine.transition(LifecycleEvent.StartFailure)
        assertIs<TransitionResult.Success>(r1)
        assertEquals(MeshLinkState.RECOVERABLE, machine.state.value)

        // Second failure: stays RECOVERABLE
        fakeTime = 20_000L // +20 s
        val r2 = machine.transition(LifecycleEvent.StartFailure)
        assertIs<TransitionResult.Success>(r2)
        assertEquals(MeshLinkState.RECOVERABLE, machine.state.value)

        // Third failure at 40 s — all 3 within 60 s → OscillationTerminal + TERMINAL
        fakeTime = 40_000L // +40 s
        val r3 = machine.transition(LifecycleEvent.StartFailure)
        assertIs<TransitionResult.OscillationTerminal>(r3)
        assertEquals(MeshLinkState.TERMINAL, machine.state.value)
    }

    // ── Oscillation bound: first failure, then explicit TERMINAL via start() ─

    @Test
    fun `RECOVERABLE - first startFailure is Success not OscillationTerminal`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.TransientFailure)
        val result = machine.transition(LifecycleEvent.StartFailure)
        assertIs<TransitionResult.Success>(result)
        assertEquals(MeshLinkState.RECOVERABLE, machine.state.value)
    }

    @Test
    fun `RECOVERABLE - second startFailure is still Success`() {
        var fakeTime = 0L
        val machine = fsm(nowMs = { fakeTime })
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.TransientFailure)
        machine.transition(LifecycleEvent.StartFailure) // 1st

        fakeTime = 10_000L
        val result = machine.transition(LifecycleEvent.StartFailure) // 2nd
        assertIs<TransitionResult.Success>(result)
        assertEquals(MeshLinkState.RECOVERABLE, machine.state.value)
    }

    // ── Oscillation window expiry: old failures pruned ────────────────────

    @Test
    fun `oscillation window - failures older than 60s are pruned and not counted`() {
        var fakeTime = 0L
        val machine = fsm(nowMs = { fakeTime })
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.TransientFailure)

        // Two failures at t=0 and t=30s
        fakeTime = 0L
        machine.transition(LifecycleEvent.StartFailure) // 1st (t=0)
        fakeTime = 30_000L
        machine.transition(LifecycleEvent.StartFailure) // 2nd (t=30s)

        // At t=61s, the first failure (t=0) falls outside the 60s window; pruned
        // Third failure at t=61s: window now contains only t=30s → count=1, then add t=61s →
        // count=2
        fakeTime = 61_000L
        val result = machine.transition(LifecycleEvent.StartFailure) // 3rd but oldest pruned
        assertIs<TransitionResult.Success>(result) // still 2 within window, not 3
        assertEquals(MeshLinkState.RECOVERABLE, machine.state.value)
    }

    // ── Success from RECOVERABLE clears failure history ───────────────────

    @Test
    fun `StartSuccess from RECOVERABLE clears failure history`() {
        var fakeTime = 0L
        val machine = fsm(nowMs = { fakeTime })
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.TransientFailure)

        // 2 failures → then success clears history
        fakeTime = 0L
        machine.transition(LifecycleEvent.StartFailure) // 1st
        fakeTime = 10_000L
        machine.transition(LifecycleEvent.StartFailure) // 2nd

        // Success clears failure history
        machine.applyAll(LifecycleEvent.StartSuccess)
        assertEquals(MeshLinkState.RUNNING, machine.state.value)

        // Back to RECOVERABLE after another transient failure
        machine.applyAll(LifecycleEvent.TransientFailure)

        // Now 2 more failures should NOT hit oscillation bound (history was cleared)
        fakeTime = 20_000L
        machine.transition(LifecycleEvent.StartFailure) // 1st after reset
        fakeTime = 30_000L
        val result = machine.transition(LifecycleEvent.StartFailure) // 2nd after reset
        assertIs<TransitionResult.Success>(result)
        assertEquals(MeshLinkState.RECOVERABLE, machine.state.value)
    }

    // ── Stop from RECOVERABLE clears failure history ──────────────────────

    @Test
    fun `Stop from RECOVERABLE clears failure history`() {
        var fakeTime = 0L
        val machine = fsm(nowMs = { fakeTime })
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.TransientFailure)
        machine.transition(LifecycleEvent.StartFailure) // 1st failure
        machine.transition(LifecycleEvent.Stop) // stop clears history, STOPPED
        assertEquals(MeshLinkState.STOPPED, machine.state.value)
    }

    // ── StateFlow reflects current state ─────────────────────────────────

    @Test
    fun `StateFlow value reflects current state after each transition`() {
        val machine = fsm()
        assertEquals(MeshLinkState.UNINITIALIZED, machine.state.value)
        machine.transition(LifecycleEvent.StartSuccess)
        assertEquals(MeshLinkState.RUNNING, machine.state.value)
        machine.transition(LifecycleEvent.Pause)
        assertEquals(MeshLinkState.PAUSED, machine.state.value)
        machine.transition(LifecycleEvent.Resume)
        assertEquals(MeshLinkState.RUNNING, machine.state.value)
        machine.transition(LifecycleEvent.Stop)
        assertEquals(MeshLinkState.STOPPED, machine.state.value)
    }

    // ── Diagnostic callback on invalid transitions ────────────────────────

    @Test
    fun `invalid transition emits InvalidStateTransition diagnostic`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess) // → RUNNING

        diagnostics.clear()
        machine.transition(LifecycleEvent.Resume) // invalid from RUNNING

        assertEquals(1, diagnostics.size)
        val diag = assertIs<DiagnosticEvent.InvalidStateTransition>(diagnostics[0])
        assertEquals(MeshLinkState.RUNNING, diag.from)
        assertEquals("Resume", diag.trigger)
    }

    @Test
    fun `invalid transition does not change state`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess) // → RUNNING

        val stateBefore = machine.state.value
        machine.transition(LifecycleEvent.StartSuccess) // invalid from RUNNING
        assertEquals(stateBefore, machine.state.value)
    }

    // ── RUNNING + invalid transitions ─────────────────────────────────────

    @Test
    fun `RUNNING + StartSuccess → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess)
        val result = machine.transition(LifecycleEvent.StartSuccess)
        assertIs<TransitionResult.InvalidTransition>(result)
        assertEquals(MeshLinkState.RUNNING, machine.state.value)
    }

    @Test
    fun `RUNNING + StartFailure → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess)
        val result = machine.transition(LifecycleEvent.StartFailure)
        assertIs<TransitionResult.InvalidTransition>(result)
        assertEquals(MeshLinkState.RUNNING, machine.state.value)
    }

    @Test
    fun `RUNNING + Resume → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess)
        val result = machine.transition(LifecycleEvent.Resume)
        assertIs<TransitionResult.InvalidTransition>(result)
        assertEquals(MeshLinkState.RUNNING, machine.state.value)
    }

    // ── PAUSED + invalid transitions ──────────────────────────────────────

    @Test
    fun `PAUSED + StartSuccess → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.Pause)
        val result = machine.transition(LifecycleEvent.StartSuccess)
        assertIs<TransitionResult.InvalidTransition>(result)
        assertEquals(MeshLinkState.PAUSED, machine.state.value)
    }

    @Test
    fun `PAUSED + StartFailure → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.Pause)
        val result = machine.transition(LifecycleEvent.StartFailure)
        assertIs<TransitionResult.InvalidTransition>(result)
        assertEquals(MeshLinkState.PAUSED, machine.state.value)
    }

    @Test
    fun `PAUSED + Pause → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.Pause)
        val result = machine.transition(LifecycleEvent.Pause)
        assertIs<TransitionResult.InvalidTransition>(result)
        assertEquals(MeshLinkState.PAUSED, machine.state.value)
    }

    @Test
    fun `PAUSED + TransientFailure → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.Pause)
        val result = machine.transition(LifecycleEvent.TransientFailure)
        assertIs<TransitionResult.InvalidTransition>(result)
        assertEquals(MeshLinkState.PAUSED, machine.state.value)
    }

    // ── STOPPED (absorbing) ───────────────────────────────────────────────

    @Test
    fun `STOPPED + StartSuccess → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.Stop)
        assertIs<TransitionResult.InvalidTransition>(
            machine.transition(LifecycleEvent.StartSuccess)
        )
        assertEquals(MeshLinkState.STOPPED, machine.state.value)
    }

    @Test
    fun `STOPPED + StartFailure → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.Stop)
        assertIs<TransitionResult.InvalidTransition>(
            machine.transition(LifecycleEvent.StartFailure)
        )
        assertEquals(MeshLinkState.STOPPED, machine.state.value)
    }

    @Test
    fun `STOPPED + Pause → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.Stop)
        assertIs<TransitionResult.InvalidTransition>(machine.transition(LifecycleEvent.Pause))
        assertEquals(MeshLinkState.STOPPED, machine.state.value)
    }

    @Test
    fun `STOPPED + Resume → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.Stop)
        assertIs<TransitionResult.InvalidTransition>(machine.transition(LifecycleEvent.Resume))
        assertEquals(MeshLinkState.STOPPED, machine.state.value)
    }

    @Test
    fun `STOPPED + Stop → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.Stop)
        assertIs<TransitionResult.InvalidTransition>(machine.transition(LifecycleEvent.Stop))
        assertEquals(MeshLinkState.STOPPED, machine.state.value)
    }

    @Test
    fun `STOPPED + TransientFailure → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.Stop)
        assertIs<TransitionResult.InvalidTransition>(
            machine.transition(LifecycleEvent.TransientFailure)
        )
        assertEquals(MeshLinkState.STOPPED, machine.state.value)
    }

    // ── TERMINAL (absorbing) ──────────────────────────────────────────────

    @Test
    fun `TERMINAL + StartSuccess → invalid`() {
        val machine = fsm()
        machine.transition(LifecycleEvent.StartFailure) // → TERMINAL
        assertIs<TransitionResult.InvalidTransition>(
            machine.transition(LifecycleEvent.StartSuccess)
        )
        assertEquals(MeshLinkState.TERMINAL, machine.state.value)
    }

    @Test
    fun `TERMINAL + StartFailure → invalid`() {
        val machine = fsm()
        machine.transition(LifecycleEvent.StartFailure)
        assertIs<TransitionResult.InvalidTransition>(
            machine.transition(LifecycleEvent.StartFailure)
        )
        assertEquals(MeshLinkState.TERMINAL, machine.state.value)
    }

    @Test
    fun `TERMINAL + Pause → invalid`() {
        val machine = fsm()
        machine.transition(LifecycleEvent.StartFailure)
        assertIs<TransitionResult.InvalidTransition>(machine.transition(LifecycleEvent.Pause))
        assertEquals(MeshLinkState.TERMINAL, machine.state.value)
    }

    @Test
    fun `TERMINAL + Resume → invalid`() {
        val machine = fsm()
        machine.transition(LifecycleEvent.StartFailure)
        assertIs<TransitionResult.InvalidTransition>(machine.transition(LifecycleEvent.Resume))
        assertEquals(MeshLinkState.TERMINAL, machine.state.value)
    }

    @Test
    fun `TERMINAL + Stop → invalid`() {
        val machine = fsm()
        machine.transition(LifecycleEvent.StartFailure)
        assertIs<TransitionResult.InvalidTransition>(machine.transition(LifecycleEvent.Stop))
        assertEquals(MeshLinkState.TERMINAL, machine.state.value)
    }

    @Test
    fun `TERMINAL + TransientFailure → invalid`() {
        val machine = fsm()
        machine.transition(LifecycleEvent.StartFailure)
        assertIs<TransitionResult.InvalidTransition>(
            machine.transition(LifecycleEvent.TransientFailure)
        )
        assertEquals(MeshLinkState.TERMINAL, machine.state.value)
    }

    // ── UNINITIALIZED + invalid transitions ───────────────────────────────

    @Test
    fun `UNINITIALIZED + Pause → invalid`() {
        val machine = fsm()
        assertIs<TransitionResult.InvalidTransition>(machine.transition(LifecycleEvent.Pause))
        assertEquals(MeshLinkState.UNINITIALIZED, machine.state.value)
    }

    @Test
    fun `UNINITIALIZED + Resume → invalid`() {
        val machine = fsm()
        assertIs<TransitionResult.InvalidTransition>(machine.transition(LifecycleEvent.Resume))
        assertEquals(MeshLinkState.UNINITIALIZED, machine.state.value)
    }

    @Test
    fun `UNINITIALIZED + Stop → invalid`() {
        val machine = fsm()
        assertIs<TransitionResult.InvalidTransition>(machine.transition(LifecycleEvent.Stop))
        assertEquals(MeshLinkState.UNINITIALIZED, machine.state.value)
    }

    @Test
    fun `UNINITIALIZED + TransientFailure → invalid`() {
        val machine = fsm()
        assertIs<TransitionResult.InvalidTransition>(
            machine.transition(LifecycleEvent.TransientFailure)
        )
        assertEquals(MeshLinkState.UNINITIALIZED, machine.state.value)
    }

    // ── RECOVERABLE + invalid transitions ────────────────────────────────

    @Test
    fun `RECOVERABLE + Pause → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.TransientFailure)
        val result = machine.transition(LifecycleEvent.Pause)
        assertIs<TransitionResult.InvalidTransition>(result)
        assertEquals(MeshLinkState.RECOVERABLE, machine.state.value)
    }

    @Test
    fun `RECOVERABLE + Resume → invalid`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.TransientFailure)
        val result = machine.transition(LifecycleEvent.Resume)
        assertIs<TransitionResult.InvalidTransition>(result)
        assertEquals(MeshLinkState.RECOVERABLE, machine.state.value)
    }

    @Test
    fun `RECOVERABLE + TransientFailure → invalid (already RECOVERABLE)`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.TransientFailure)
        val result = machine.transition(LifecycleEvent.TransientFailure)
        assertIs<TransitionResult.InvalidTransition>(result)
        assertEquals(MeshLinkState.RECOVERABLE, machine.state.value)
    }

    // ── InvalidTransition carries correct from/event ──────────────────────

    @Test
    fun `InvalidTransition carries correct from state and event`() {
        val machine = fsm()
        machine.applyAll(LifecycleEvent.StartSuccess) // → RUNNING

        val result = machine.transition(LifecycleEvent.StartSuccess) // invalid
        val invalid = assertIs<TransitionResult.InvalidTransition>(result)
        assertEquals(MeshLinkState.RUNNING, invalid.from)
        assertEquals(LifecycleEvent.StartSuccess, invalid.event)
    }

    // ── LifecycleEvent.displayName ────────────────────────────────────────

    @Test
    fun `LifecycleEvent displayName returns class simpleName`() {
        assertEquals("StartSuccess", LifecycleEvent.StartSuccess.displayName)
        assertEquals("StartFailure", LifecycleEvent.StartFailure.displayName)
        assertEquals("Stop", LifecycleEvent.Stop.displayName)
        assertEquals("Pause", LifecycleEvent.Pause.displayName)
        assertEquals("Resume", LifecycleEvent.Resume.displayName)
        assertEquals("TransientFailure", LifecycleEvent.TransientFailure.displayName)
    }

    // ── MeshLinkState enum values ─────────────────────────────────────────

    @Test
    fun `MeshLinkState enum contains all required values`() {
        val states = MeshLinkState.entries.map { it.name }
        assertTrue("UNINITIALIZED" in states)
        assertTrue("RUNNING" in states)
        assertTrue("PAUSED" in states)
        assertTrue("STOPPED" in states)
        assertTrue("RECOVERABLE" in states)
        assertTrue("TERMINAL" in states)
    }

    // ── Smoke: public API types construction and equality ─────────────────

    @Test
    fun `MessageId - content equality and hashCode`() {
        val a = MessageId(byteArrayOf(1, 2, 3))
        val b = MessageId(byteArrayOf(1, 2, 3))
        val c = MessageId(byteArrayOf(4, 5, 6))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
        val s = a.toString()
        assertTrue(s.isNotEmpty())
    }

    @Test
    fun `ReceivedMessage - content equality via MessageId`() {
        val id = MessageId(byteArrayOf(1))
        val msg1 = ReceivedMessage(id, byteArrayOf(10), byteArrayOf(99), 1000L)
        val msg2 = ReceivedMessage(id, byteArrayOf(20), byteArrayOf(88), 2000L)
        assertEquals(msg1, msg2) // same MessageId → equal
        assertEquals(msg1.hashCode(), msg2.hashCode())
        assertTrue(msg1.toString().isNotEmpty())
    }

    @Test
    fun `PeerDetail - content equality via id`() {
        val id = byteArrayOf(1, 2, 3)
        val key = byteArrayOf(4, 5, 6)
        val d1 = PeerDetail(id, key, "abc", true, 1000L, TrustMode.STRICT)
        val d2 = PeerDetail(id.copyOf(), key.copyOf(), "xyz", false, 2000L, TrustMode.STRICT)
        assertEquals(d1, d2) // same id bytes → equal
        assertEquals(d1.hashCode(), d2.hashCode())
        assertTrue(d1.toString().contains("abc"))
    }

    @Test
    fun `PeerDetail - different ids not equal`() {
        val d1 = PeerDetail(byteArrayOf(1), byteArrayOf(9), "fp1", true, 0L, TrustMode.STRICT)
        val d2 = PeerDetail(byteArrayOf(2), byteArrayOf(9), "fp2", true, 0L, TrustMode.STRICT)
        assertFalse(d1 == d2)
    }

    @Test
    fun `RoutingEntry - content equality`() {
        val dest = byteArrayOf(1, 2, 3)
        val hop = byteArrayOf(4, 5, 6)
        val e1 = RoutingEntry(dest, hop, 10, 42, 5000L)
        val e2 = RoutingEntry(dest.copyOf(), hop.copyOf(), 10, 42, 5000L)
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
        assertTrue(e1.toString().contains("10"))
    }

    @Test
    fun `RoutingEntry - different content not equal`() {
        val e1 = RoutingEntry(byteArrayOf(1), byteArrayOf(2), 5, 1, 100L)
        val e2 = RoutingEntry(byteArrayOf(3), byteArrayOf(4), 5, 1, 100L)
        assertFalse(e1 == e2)
    }

    @Test
    fun `RoutingSnapshot - construction and copy`() {
        val snap =
            RoutingSnapshot(
                capturedAtMs = 9000L,
                routes = listOf(RoutingEntry(byteArrayOf(1), byteArrayOf(2), 3, 0, 100L)),
            )
        assertEquals(9000L, snap.capturedAtMs)
        assertEquals(1, snap.routes.size)
        val copy = snap.copy(capturedAtMs = 10_000L)
        assertEquals(10_000L, copy.capturedAtMs)
    }

    @Test
    fun `PeerEvent Found - content equality`() {
        val id = byteArrayOf(7, 8, 9)
        val detail = PeerDetail(id, byteArrayOf(10), "fp", true, 0L, TrustMode.STRICT)
        val e1 = PeerEvent.Found(id, detail)
        val e2 = PeerEvent.Found(id.copyOf(), detail)
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    fun `PeerEvent Lost - content equality`() {
        val id = byteArrayOf(11, 12)
        val e1 = PeerEvent.Lost(id)
        val e2 = PeerEvent.Lost(id.copyOf())
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    fun `KeyChangeEvent - content equality via peerId`() {
        val pid = byteArrayOf(1, 2, 3)
        val kc1 = KeyChangeEvent(pid, byteArrayOf(4), byteArrayOf(5))
        val kc2 = KeyChangeEvent(pid.copyOf(), null, byteArrayOf(6))
        assertEquals(kc1, kc2)
        assertEquals(kc1.hashCode(), kc2.hashCode())
        assertTrue(kc1.toString().contains("hasOldKey=true"))
        assertTrue(kc2.toString().contains("hasOldKey=false"))
    }

    @Test
    fun `DiagnosticEvent InvalidStateTransition - construction`() {
        val evt = DiagnosticEvent.InvalidStateTransition(MeshLinkState.RUNNING, "Resume")
        assertEquals(MeshLinkState.RUNNING, evt.from)
        assertEquals("Resume", evt.trigger)
    }

    @Test
    fun `DiagnosticLevel enum values`() {
        val levels = DiagnosticLevel.entries.map { it.name }
        assertTrue("DEBUG" in levels)
        assertTrue("INFO" in levels)
        assertTrue("WARN" in levels)
        assertTrue("ERROR" in levels)
    }

    @Test
    fun `MeshHealthSnapshot - construction`() {
        val snap = MeshHealthSnapshot(3, 12, 65536L, 9000L)
        assertEquals(3, snap.connectedPeers)
        assertEquals(12, snap.routingTableSize)
        assertEquals(65536L, snap.bufferUsageBytes)
        assertEquals(9000L, snap.capturedAtMs)
        val copy = snap.copy(connectedPeers = 5)
        assertEquals(5, copy.connectedPeers)
    }

    @Test
    fun `TransferProgress - content equality via transferId`() {
        val tid = byteArrayOf(1, 2)
        val p1 = TransferProgress(tid, byteArrayOf(3), 100L, 200L)
        val p2 = TransferProgress(tid.copyOf(), byteArrayOf(4), 150L, 200L)
        assertEquals(p1, p2)
        assertEquals(p1.hashCode(), p2.hashCode())
        assertTrue(p1.toString().contains("100/200"))
    }

    @Test
    fun `TransferFailure Timeout - content equality`() {
        val tid = byteArrayOf(5, 6)
        val f1 = TransferFailure.Timeout(tid, byteArrayOf(7))
        val f2 = TransferFailure.Timeout(tid.copyOf(), byteArrayOf(8))
        assertEquals(f1, f2)
        assertEquals(f1.hashCode(), f2.hashCode())
    }

    @Test
    fun `TransferFailure PeerUnavailable - content equality`() {
        val tid = byteArrayOf(9, 10)
        val f1 = TransferFailure.PeerUnavailable(tid, byteArrayOf(11))
        val f2 = TransferFailure.PeerUnavailable(tid.copyOf(), byteArrayOf(12))
        assertEquals(f1, f2)
        assertEquals(f1.hashCode(), f2.hashCode())
    }

    @Test
    fun `TransferFailure Cancelled - content equality`() {
        val tid = byteArrayOf(13, 14)
        val f1 = TransferFailure.Cancelled(tid)
        val f2 = TransferFailure.Cancelled(tid.copyOf())
        assertEquals(f1, f2)
        assertEquals(f1.hashCode(), f2.hashCode())
    }

    @Test
    fun `MessagePriority enum contains all required values`() {
        val priorities = MessagePriority.entries.map { it.name }
        assertTrue("LOW" in priorities)
        assertTrue("NORMAL" in priorities)
        assertTrue("HIGH" in priorities)
    }

    @Test
    fun `OSCILLATION constants have correct values`() {
        assertEquals(60_000L, MeshLinkStateMachine.OSCILLATION_WINDOW_MS)
        assertEquals(3, MeshLinkStateMachine.OSCILLATION_MAX_FAILURES)
    }

    // ── ExperimentalMeshLinkApi annotation - existence check ─────────────

    @Test
    fun `ExperimentalMeshLinkApi annotation class exists`() {
        // Verify the annotation class is accessible by its simple name
        assertEquals("ExperimentalMeshLinkApi", ExperimentalMeshLinkApi::class.simpleName)
    }

    // ── Null safety on diagnostic callback default ────────────────────────

    @Test
    fun `default onDiagnostic callback is a no-op`() {
        // Construct without explicit onDiagnostic; invalid transition should not throw
        val machine = MeshLinkStateMachine()
        val result = machine.transition(LifecycleEvent.Pause) // invalid from UNINITIALIZED
        assertIs<TransitionResult.InvalidTransition>(result)
        // No exception → default callback is a no-op ✓
    }

    @Test
    fun `InvalidTransition result has null event reference safety`() {
        val machine = fsm()
        val result = machine.transition(LifecycleEvent.Stop) // invalid from UNINITIALIZED
        val inv = assertIs<TransitionResult.InvalidTransition>(result)
        assertEquals(MeshLinkState.UNINITIALIZED, inv.from)
        assertIs<LifecycleEvent.Stop>(inv.event)
    }

    // ── Pruning edge case: empty timestamps list ──────────────────────────

    @Test
    fun `pruneOldFailures is a no-op when timestamps list is empty`() {
        // Transition to RECOVERABLE and immediately call StartFailure → prune on empty list
        var fakeTime = 100_000L // start at 100s
        val machine = fsm(nowMs = { fakeTime })
        machine.applyAll(LifecycleEvent.StartSuccess, LifecycleEvent.TransientFailure)

        // First failure: failedStartTimestamps is empty before prune (no-op)
        val result = machine.transition(LifecycleEvent.StartFailure)
        assertIs<TransitionResult.Success>(result)
        assertEquals(MeshLinkState.RECOVERABLE, machine.state.value)
    }
}
