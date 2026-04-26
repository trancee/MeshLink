package ch.trancee.meshlink.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for:
 * - [DiagnosticCode] — 27 codes with correct severities
 * - [DiagnosticPayload] — all 28 subtypes constructable
 * - [DiagnosticSink] — emit, lazy payload, dropped-count tracking, redaction
 * - [NoOpDiagnosticSink] — emit is truly no-op
 */
class DiagnosticSinkTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun sink(
        bufferCapacity: Int = 100,
        redactFn: ((PeerIdHex) -> PeerIdHex)? = null,
        clock: () -> Long = { 1000L },
        wallClock: () -> Long = { 2000L },
    ): DiagnosticSink = DiagnosticSink(bufferCapacity, redactFn, clock, wallClock)

    /** Emits one event and returns the value from the replay cache. */
    private fun DiagnosticSink.emitAndGet(
        code: DiagnosticCode,
        payload: DiagnosticPayload,
    ): DiagnosticEvent {
        emit(code) { payload }
        return checkNotNull(events.replayCache.lastOrNull()) { "No event in replay cache" }
    }

    // ── DiagnosticCode: 27 entries ────────────────────────────────────────

    @Test
    fun `DiagnosticCode has exactly 27 entries`() {
        assertEquals(27, DiagnosticCode.entries.size)
    }

    @Test
    fun `DiagnosticCode critical tier severities`() {
        assertEquals(DiagnosticLevel.ERROR, DiagnosticCode.DUPLICATE_IDENTITY.severity)
        assertEquals(DiagnosticLevel.ERROR, DiagnosticCode.BLE_STACK_UNRESPONSIVE.severity)
        assertEquals(DiagnosticLevel.WARN, DiagnosticCode.DECRYPTION_FAILED.severity)
        assertEquals(DiagnosticLevel.ERROR, DiagnosticCode.ROTATION_FAILED.severity)
    }

    @Test
    fun `DiagnosticCode threshold tier severities`() {
        assertEquals(DiagnosticLevel.WARN, DiagnosticCode.REPLAY_REJECTED.severity)
        assertEquals(DiagnosticLevel.WARN, DiagnosticCode.RATE_LIMIT_HIT.severity)
        assertEquals(DiagnosticLevel.WARN, DiagnosticCode.BUFFER_PRESSURE.severity)
        assertEquals(DiagnosticLevel.ERROR, DiagnosticCode.MEMORY_PRESSURE.severity)
        assertEquals(DiagnosticLevel.WARN, DiagnosticCode.NEXTHOP_UNRELIABLE.severity)
        assertEquals(DiagnosticLevel.WARN, DiagnosticCode.LOOP_DETECTED.severity)
        assertEquals(DiagnosticLevel.INFO, DiagnosticCode.HOP_LIMIT_EXCEEDED.severity)
        assertEquals(DiagnosticLevel.INFO, DiagnosticCode.MESSAGE_AGE_EXCEEDED.severity)
    }

    @Test
    fun `DiagnosticCode log tier severities`() {
        assertEquals(DiagnosticLevel.INFO, DiagnosticCode.ROUTE_CHANGED.severity)
        assertEquals(DiagnosticLevel.INFO, DiagnosticCode.PEER_PRESENCE_EVICTED.severity)
        assertEquals(DiagnosticLevel.INFO, DiagnosticCode.TRANSPORT_MODE_CHANGED.severity)
        assertEquals(DiagnosticLevel.WARN, DiagnosticCode.L2CAP_FALLBACK.severity)
        assertEquals(DiagnosticLevel.INFO, DiagnosticCode.GOSSIP_TRAFFIC_REPORT.severity)
        assertEquals(DiagnosticLevel.INFO, DiagnosticCode.HANDSHAKE_EVENT.severity)
        assertEquals(DiagnosticLevel.WARN, DiagnosticCode.LATE_DELIVERY_ACK.severity)
        assertEquals(DiagnosticLevel.WARN, DiagnosticCode.SEND_FAILED.severity)
        assertEquals(DiagnosticLevel.WARN, DiagnosticCode.DELIVERY_TIMEOUT.severity)
        assertEquals(DiagnosticLevel.INFO, DiagnosticCode.APP_ID_REJECTED.severity)
        assertEquals(DiagnosticLevel.WARN, DiagnosticCode.UNKNOWN_MESSAGE_TYPE.severity)
        assertEquals(DiagnosticLevel.WARN, DiagnosticCode.MALFORMED_DATA.severity)
        assertEquals(DiagnosticLevel.INFO, DiagnosticCode.PEER_DISCOVERED.severity)
        assertEquals(DiagnosticLevel.INFO, DiagnosticCode.CONFIG_CLAMPED.severity)
        assertEquals(DiagnosticLevel.WARN, DiagnosticCode.INVALID_STATE_TRANSITION.severity)
    }

    // ── DiagnosticPayload: all 28 subtypes constructable ─────────────────

    @Test
    fun `all DiagnosticPayload subtypes can be constructed`() {
        val pid = PeerIdHex("aabbccdd")

        // Critical
        assertIs<DiagnosticPayload.DuplicateIdentity>(DiagnosticPayload.DuplicateIdentity(pid))
        assertIs<DiagnosticPayload.BleStackUnresponsive>(
            DiagnosticPayload.BleStackUnresponsive("err")
        )
        assertIs<DiagnosticPayload.DecryptionFailed>(DiagnosticPayload.DecryptionFailed(pid, "err"))
        assertIs<DiagnosticPayload.RotationFailed>(DiagnosticPayload.RotationFailed("err"))

        // Threshold
        assertIs<DiagnosticPayload.ReplayRejected>(DiagnosticPayload.ReplayRejected(pid))
        assertIs<DiagnosticPayload.RateLimitHit>(DiagnosticPayload.RateLimitHit(pid, 60, 1000L))
        assertIs<DiagnosticPayload.BufferPressure>(DiagnosticPayload.BufferPressure(80))
        assertIs<DiagnosticPayload.MemoryPressure>(DiagnosticPayload.MemoryPressure(1024L, 2048L))
        assertIs<DiagnosticPayload.NexthopUnreliable>(
            DiagnosticPayload.NexthopUnreliable(pid, 0.3f)
        )
        assertIs<DiagnosticPayload.LoopDetected>(DiagnosticPayload.LoopDetected(pid))
        assertIs<DiagnosticPayload.HopLimitExceeded>(DiagnosticPayload.HopLimitExceeded(12, 10))
        assertIs<DiagnosticPayload.MessageAgeExceeded>(
            DiagnosticPayload.MessageAgeExceeded(5000L, 3000L)
        )

        // Log
        assertIs<DiagnosticPayload.RouteChanged>(DiagnosticPayload.RouteChanged(pid, 1.5))
        assertIs<DiagnosticPayload.PeerPresenceEvicted>(DiagnosticPayload.PeerPresenceEvicted(pid))
        assertIs<DiagnosticPayload.TransportModeChanged>(
            DiagnosticPayload.TransportModeChanged("L2CAP")
        )
        assertIs<DiagnosticPayload.L2capFallback>(DiagnosticPayload.L2capFallback(pid))
        assertIs<DiagnosticPayload.GossipTrafficReport>(DiagnosticPayload.GossipTrafficReport(5))
        assertIs<DiagnosticPayload.HandshakeEvent>(
            DiagnosticPayload.HandshakeEvent(pid, "XX_COMPLETE")
        )
        assertIs<DiagnosticPayload.LateDeliveryAck>(DiagnosticPayload.LateDeliveryAck("msgid"))
        assertIs<DiagnosticPayload.SendFailed>(DiagnosticPayload.SendFailed(pid, "timeout"))
        assertIs<DiagnosticPayload.DeliveryTimeout>(DiagnosticPayload.DeliveryTimeout("msgid"))
        assertIs<DiagnosticPayload.AppIdRejected>(DiagnosticPayload.AppIdRejected("com.ex"))
        assertIs<DiagnosticPayload.UnknownMessageType>(DiagnosticPayload.UnknownMessageType(0xFF))
        assertIs<DiagnosticPayload.MalformedData>(DiagnosticPayload.MalformedData("bad header"))
        assertIs<DiagnosticPayload.PeerDiscovered>(DiagnosticPayload.PeerDiscovered(pid))
        assertIs<DiagnosticPayload.ConfigClamped>(DiagnosticPayload.ConfigClamped("f", "1", "2"))
        assertIs<DiagnosticPayload.InvalidStateTransition>(
            DiagnosticPayload.InvalidStateTransition("RUNNING", "Resume")
        )
        assertIs<DiagnosticPayload.TextMessage>(DiagnosticPayload.TextMessage("hello"))
    }

    // ── DiagnosticSink: emit populates correct event fields ───────────────

    @Test
    fun `emit constructs DiagnosticEvent with correct fields`() {
        val s = sink(clock = { 500L }, wallClock = { 999L })
        val evt = s.emitAndGet(DiagnosticCode.BUFFER_PRESSURE, DiagnosticPayload.BufferPressure(75))
        assertEquals(DiagnosticCode.BUFFER_PRESSURE, evt.code)
        assertEquals(DiagnosticLevel.WARN, evt.severity)
        assertEquals(500L, evt.monotonicMillis)
        assertEquals(999L, evt.wallClockMillis)
        assertEquals(0, evt.droppedCount)
        assertEquals(DiagnosticPayload.BufferPressure(75), evt.payload)
    }

    @Test
    fun `events SharedFlow exposes emitted events via replay cache`() {
        val s = sink()
        assertNull(s.events.replayCache.lastOrNull())
        s.emit(DiagnosticCode.MALFORMED_DATA) { DiagnosticPayload.MalformedData("x") }
        assertNotNull(s.events.replayCache.lastOrNull())
    }

    // ── DiagnosticSink: lazy payload ──────────────────────────────────────

    @Test
    fun `payloadProvider is called exactly once per emit`() {
        val s = sink()
        var callCount = 0
        s.emit(DiagnosticCode.UNKNOWN_MESSAGE_TYPE) {
            callCount++
            DiagnosticPayload.UnknownMessageType(1)
        }
        assertEquals(1, callCount)
    }

    // ── DiagnosticSink: buffer overflow + droppedCount ────────────────────

    @Test
    fun `droppedCount is zero while buffer is not full`() {
        val capacity = 5
        val s = sink(bufferCapacity = capacity)
        // Fill the buffer exactly (replay=1 + extra=capacity = capacity+1 total slots)
        repeat(capacity + 1) { i ->
            val evt =
                s.emitAndGet(
                    DiagnosticCode.GOSSIP_TRAFFIC_REPORT,
                    DiagnosticPayload.GossipTrafficReport(i),
                )
            assertEquals(0, evt.droppedCount, "Expected 0 drops at emit #${i + 1}")
        }
    }

    @Test
    fun `droppedCount is positive after buffer overflow`() {
        val capacity = 3
        val s = sink(bufferCapacity = capacity)
        // Emit capacity+1 events to completely fill the buffer
        repeat(capacity + 1) { i ->
            s.emit(DiagnosticCode.GOSSIP_TRAFFIC_REPORT) {
                DiagnosticPayload.GossipTrafficReport(i)
            }
        }
        // Next event (capacity+2 total) overflows → droppedCount > 0
        val overflow =
            s.emitAndGet(DiagnosticCode.BUFFER_PRESSURE, DiagnosticPayload.BufferPressure(99))
        assertTrue(
            overflow.droppedCount > 0,
            "Expected droppedCount > 0, got ${overflow.droppedCount}",
        )
    }

    @Test
    fun `droppedCount accumulates across multiple overflow events`() {
        val capacity = 2
        val s = sink(bufferCapacity = capacity)
        // Fill buffer: capacity+1 = 3 events
        repeat(capacity + 1) {
            s.emit(DiagnosticCode.GOSSIP_TRAFFIC_REPORT) {
                DiagnosticPayload.GossipTrafficReport(0)
            }
        }
        // Overflow 3 more times without collecting
        repeat(3) {
            s.emit(DiagnosticCode.GOSSIP_TRAFFIC_REPORT) {
                DiagnosticPayload.GossipTrafficReport(0)
            }
        }
        // Next event should see accumulated drops
        val evt = s.emitAndGet(DiagnosticCode.BUFFER_PRESSURE, DiagnosticPayload.BufferPressure(1))
        assertTrue(
            evt.droppedCount >= 1,
            "Expected accumulated droppedCount, got ${evt.droppedCount}",
        )
    }

    // ── DiagnosticSink: redaction ─────────────────────────────────────────

    private val REDACT_FN: (PeerIdHex) -> PeerIdHex = { PeerIdHex("redacted:${it.hex}") }
    private val rawPeer = PeerIdHex("cafebabe")
    private val redactedPeer = PeerIdHex("redacted:cafebabe")

    @Test
    fun `redactFn transforms PeerIdHex in DuplicateIdentity`() {
        val s = sink(redactFn = REDACT_FN)
        val evt =
            s.emitAndGet(
                DiagnosticCode.DUPLICATE_IDENTITY,
                DiagnosticPayload.DuplicateIdentity(rawPeer),
            )
        assertEquals(redactedPeer, (evt.payload as DiagnosticPayload.DuplicateIdentity).peerId)
    }

    @Test
    fun `redactFn leaves BleStackUnresponsive unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.BleStackUnresponsive("crash")
        val evt = s.emitAndGet(DiagnosticCode.BLE_STACK_UNRESPONSIVE, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn transforms PeerIdHex in DecryptionFailed`() {
        val s = sink(redactFn = REDACT_FN)
        val evt =
            s.emitAndGet(
                DiagnosticCode.DECRYPTION_FAILED,
                DiagnosticPayload.DecryptionFailed(rawPeer, "bad mac"),
            )
        assertEquals(redactedPeer, (evt.payload as DiagnosticPayload.DecryptionFailed).peerId)
    }

    @Test
    fun `redactFn leaves RotationFailed unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.RotationFailed("io error")
        val evt = s.emitAndGet(DiagnosticCode.ROTATION_FAILED, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn transforms PeerIdHex in ReplayRejected`() {
        val s = sink(redactFn = REDACT_FN)
        val evt =
            s.emitAndGet(DiagnosticCode.REPLAY_REJECTED, DiagnosticPayload.ReplayRejected(rawPeer))
        assertEquals(redactedPeer, (evt.payload as DiagnosticPayload.ReplayRejected).peerId)
    }

    @Test
    fun `redactFn transforms PeerIdHex in RateLimitHit`() {
        val s = sink(redactFn = REDACT_FN)
        val evt =
            s.emitAndGet(
                DiagnosticCode.RATE_LIMIT_HIT,
                DiagnosticPayload.RateLimitHit(rawPeer, 60, 1000L),
            )
        assertEquals(redactedPeer, (evt.payload as DiagnosticPayload.RateLimitHit).peerId)
    }

    @Test
    fun `redactFn leaves BufferPressure unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.BufferPressure(80)
        val evt = s.emitAndGet(DiagnosticCode.BUFFER_PRESSURE, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn leaves MemoryPressure unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.MemoryPressure(512L, 1024L)
        val evt = s.emitAndGet(DiagnosticCode.MEMORY_PRESSURE, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn transforms PeerIdHex in NexthopUnreliable`() {
        val s = sink(redactFn = REDACT_FN)
        val evt =
            s.emitAndGet(
                DiagnosticCode.NEXTHOP_UNRELIABLE,
                DiagnosticPayload.NexthopUnreliable(rawPeer, 0.5f),
            )
        assertEquals(redactedPeer, (evt.payload as DiagnosticPayload.NexthopUnreliable).peerId)
    }

    @Test
    fun `redactFn transforms PeerIdHex in LoopDetected`() {
        val s = sink(redactFn = REDACT_FN)
        val evt =
            s.emitAndGet(DiagnosticCode.LOOP_DETECTED, DiagnosticPayload.LoopDetected(rawPeer))
        assertEquals(redactedPeer, (evt.payload as DiagnosticPayload.LoopDetected).destination)
    }

    @Test
    fun `redactFn leaves HopLimitExceeded unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.HopLimitExceeded(12, 10)
        val evt = s.emitAndGet(DiagnosticCode.HOP_LIMIT_EXCEEDED, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn leaves MessageAgeExceeded unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.MessageAgeExceeded(5000L, 2700000L)
        val evt = s.emitAndGet(DiagnosticCode.MESSAGE_AGE_EXCEEDED, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn transforms PeerIdHex in RouteChanged`() {
        val s = sink(redactFn = REDACT_FN)
        val evt =
            s.emitAndGet(DiagnosticCode.ROUTE_CHANGED, DiagnosticPayload.RouteChanged(rawPeer, 2.0))
        assertEquals(redactedPeer, (evt.payload as DiagnosticPayload.RouteChanged).destination)
    }

    @Test
    fun `redactFn transforms PeerIdHex in PeerPresenceEvicted`() {
        val s = sink(redactFn = REDACT_FN)
        val evt =
            s.emitAndGet(
                DiagnosticCode.PEER_PRESENCE_EVICTED,
                DiagnosticPayload.PeerPresenceEvicted(rawPeer),
            )
        assertEquals(redactedPeer, (evt.payload as DiagnosticPayload.PeerPresenceEvicted).peerId)
    }

    @Test
    fun `redactFn leaves TransportModeChanged unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.TransportModeChanged("L2CAP")
        val evt = s.emitAndGet(DiagnosticCode.TRANSPORT_MODE_CHANGED, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn transforms PeerIdHex in L2capFallback`() {
        val s = sink(redactFn = REDACT_FN)
        val evt =
            s.emitAndGet(DiagnosticCode.L2CAP_FALLBACK, DiagnosticPayload.L2capFallback(rawPeer))
        assertEquals(redactedPeer, (evt.payload as DiagnosticPayload.L2capFallback).peerId)
    }

    @Test
    fun `redactFn leaves GossipTrafficReport unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.GossipTrafficReport(10)
        val evt = s.emitAndGet(DiagnosticCode.GOSSIP_TRAFFIC_REPORT, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn transforms PeerIdHex in HandshakeEvent`() {
        val s = sink(redactFn = REDACT_FN)
        val evt =
            s.emitAndGet(
                DiagnosticCode.HANDSHAKE_EVENT,
                DiagnosticPayload.HandshakeEvent(rawPeer, "XX_START"),
            )
        assertEquals(redactedPeer, (evt.payload as DiagnosticPayload.HandshakeEvent).peerId)
    }

    @Test
    fun `redactFn leaves LateDeliveryAck unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.LateDeliveryAck("abc123")
        val evt = s.emitAndGet(DiagnosticCode.LATE_DELIVERY_ACK, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn transforms PeerIdHex in SendFailed`() {
        val s = sink(redactFn = REDACT_FN)
        val evt =
            s.emitAndGet(
                DiagnosticCode.SEND_FAILED,
                DiagnosticPayload.SendFailed(rawPeer, "ble error"),
            )
        assertEquals(redactedPeer, (evt.payload as DiagnosticPayload.SendFailed).peerId)
    }

    @Test
    fun `redactFn leaves DeliveryTimeout unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.DeliveryTimeout("abc123")
        val evt = s.emitAndGet(DiagnosticCode.DELIVERY_TIMEOUT, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn leaves AppIdRejected unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.AppIdRejected("com.example")
        val evt = s.emitAndGet(DiagnosticCode.APP_ID_REJECTED, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn leaves UnknownMessageType unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.UnknownMessageType(0xAB)
        val evt = s.emitAndGet(DiagnosticCode.UNKNOWN_MESSAGE_TYPE, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn leaves MalformedData unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.MalformedData("bad frame")
        val evt = s.emitAndGet(DiagnosticCode.MALFORMED_DATA, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn transforms PeerIdHex in PeerDiscovered`() {
        val s = sink(redactFn = REDACT_FN)
        val evt =
            s.emitAndGet(DiagnosticCode.PEER_DISCOVERED, DiagnosticPayload.PeerDiscovered(rawPeer))
        assertEquals(redactedPeer, (evt.payload as DiagnosticPayload.PeerDiscovered).peerId)
    }

    @Test
    fun `redactFn leaves ConfigClamped unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.ConfigClamped("maxHops", "25", "20")
        val evt = s.emitAndGet(DiagnosticCode.CONFIG_CLAMPED, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn leaves InvalidStateTransition unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.InvalidStateTransition("RUNNING", "Resume")
        val evt = s.emitAndGet(DiagnosticCode.INVALID_STATE_TRANSITION, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `redactFn leaves TextMessage unchanged`() {
        val s = sink(redactFn = REDACT_FN)
        val payload = DiagnosticPayload.TextMessage("hello")
        val evt = s.emitAndGet(DiagnosticCode.INVALID_STATE_TRANSITION, payload)
        assertEquals(payload, evt.payload)
    }

    @Test
    fun `null redactFn leaves PeerIdHex values unchanged`() {
        val s = sink(redactFn = null)
        val evt =
            s.emitAndGet(
                DiagnosticCode.DUPLICATE_IDENTITY,
                DiagnosticPayload.DuplicateIdentity(rawPeer),
            )
        assertEquals(rawPeer, (evt.payload as DiagnosticPayload.DuplicateIdentity).peerId)
    }

    // ── NoOpDiagnosticSink ────────────────────────────────────────────────

    @Test
    fun `NoOpDiagnosticSink emit does nothing and does not throw`() {
        NoOpDiagnosticSink.emit(DiagnosticCode.BLE_STACK_UNRESPONSIVE) {
            DiagnosticPayload.BleStackUnresponsive("crash")
        }
        // No exception → pass
    }

    @Test
    fun `NoOpDiagnosticSink emit does not call payloadProvider`() {
        var called = false
        NoOpDiagnosticSink.emit(DiagnosticCode.MALFORMED_DATA) {
            called = true
            DiagnosticPayload.MalformedData("x")
        }
        // With NoOp, the lambda should not be invoked (no events needed)
        assertEquals(false, called)
    }

    @Test
    fun `NoOpDiagnosticSink events flow has no replay cache`() {
        assertEquals(0, NoOpDiagnosticSink.events.replayCache.size)
    }

    // ── PeerIdHex ────────────────────────────────────────────────────────

    @Test
    fun `PeerIdHex equality and toString`() {
        val a = PeerIdHex("aabb")
        val b = PeerIdHex("aabb")
        val c = PeerIdHex("ccdd")
        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a.hex.isNotEmpty())
    }
}
