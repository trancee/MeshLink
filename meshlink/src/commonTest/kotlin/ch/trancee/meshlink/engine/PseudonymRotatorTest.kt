package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DiagnosticCode
import ch.trancee.meshlink.api.DiagnosticPayload
import ch.trancee.meshlink.api.DiagnosticSink
import ch.trancee.meshlink.api.NoOpDiagnosticSink
import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.engine.PseudonymRotator.Companion.epochToBytes
import ch.trancee.meshlink.engine.PseudonymRotator.Companion.toLittleEndianInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

// ─────────────────────────────────────────────────────────────────────────────
// PseudonymRotator unit tests
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class PseudonymRotatorTest {

    private val crypto = createCryptoProvider()
    private val keyHashA = crypto.sha256("node-A-identity".encodeToByteArray()).copyOf(12)
    private val keyHashB = crypto.sha256("node-B-identity".encodeToByteArray()).copyOf(12)

    // Short epoch for fast virtual time advancement.
    private val testEpochMs = 1_000L

    // ── Epoch computation ────────────────────────────────────────────────────

    @Test
    fun `epoch computation is monotonic relative to clock`() {
        var now = 0L
        val rotator = createRotator(keyHashA) { now }

        assertEquals(0L, rotator.currentEpoch())
        now = 999L
        assertEquals(0L, rotator.currentEpoch())
        now = 1_000L
        assertEquals(1L, rotator.currentEpoch())
        now = 2_500L
        assertEquals(2L, rotator.currentEpoch())
        now = 5_000L
        assertEquals(5L, rotator.currentEpoch())
    }

    // ── Pseudonym computation ────────────────────────────────────────────────

    @Test
    fun `computePseudonym returns 12-byte HMAC truncation`() {
        val rotator = createRotator(keyHashA) { 0L }
        val pseudonym = rotator.computePseudonym(0L)

        assertEquals(12, pseudonym.size, "Pseudonym must be 12 bytes")

        // Manually verify: first 12 bytes of HMAC-SHA-256(keyHash, epochToBytes(0))
        val expected = crypto.hmacSha256(keyHashA, epochToBytes(0L)).copyOf(12)
        assertContentEquals(expected, pseudonym)
    }

    @Test
    fun `computePseudonym differs across epochs`() {
        val rotator = createRotator(keyHashA) { 0L }
        val p0 = rotator.computePseudonym(0L)
        val p1 = rotator.computePseudonym(1L)
        val p2 = rotator.computePseudonym(2L)

        assertFalse(p0.contentEquals(p1), "Different epochs must produce different pseudonyms")
        assertFalse(p1.contentEquals(p2), "Different epochs must produce different pseudonyms")
    }

    @Test
    fun `computePseudonym is deterministic for same inputs`() {
        val r1 = createRotator(keyHashA) { 0L }
        val r2 = createRotator(keyHashA) { 0L }
        assertContentEquals(r1.computePseudonym(5L), r2.computePseudonym(5L))
    }

    // ── Stagger computation ──────────────────────────────────────────────────

    @Test
    fun `stagger is deterministic for same keyHash and epoch`() {
        var now = 0L
        val r1 = createRotator(keyHashA) { now }
        val r2 = createRotator(keyHashA) { now }
        assertEquals(r1.computeStaggerMs(), r2.computeStaggerMs())
    }

    @Test
    fun `different keyHashes produce different stagger offsets`() {
        var now = 0L
        val rA = createRotator(keyHashA) { now }
        val rB = createRotator(keyHashB) { now }
        // While not guaranteed by construction, HMAC-SHA-256 with different keys
        // should produce different staggers. Verify at multiple epochs.
        val anyDifferent =
            (0L..5L).any {
                now = it * testEpochMs
                rA.computeStaggerMs() != rB.computeStaggerMs()
            }
        assertTrue(
            anyDifferent,
            "Different keyHashes should produce at least one different stagger",
        )
    }

    @Test
    fun `stagger is within epoch duration`() {
        var now = 0L
        val rotator = createRotator(keyHashA) { now }
        for (epoch in 0L..20L) {
            now = epoch * testEpochMs
            val stagger = rotator.computeStaggerMs()
            assertTrue(stagger >= 0, "Stagger must be non-negative, got $stagger")
            assertTrue(
                stagger < testEpochMs,
                "Stagger must be < epochDurationMs ($testEpochMs), got $stagger",
            )
        }
    }

    // ── epochToBytes / toLittleEndianInt helpers ─────────────────────────────

    @Test
    fun `epochToBytes encodes as little-endian 8 bytes`() {
        val bytes = epochToBytes(0x0102030405060708L)
        assertEquals(8, bytes.size)
        assertEquals(0x08.toByte(), bytes[0])
        assertEquals(0x07.toByte(), bytes[1])
        assertEquals(0x06.toByte(), bytes[2])
        assertEquals(0x05.toByte(), bytes[3])
        assertEquals(0x04.toByte(), bytes[4])
        assertEquals(0x03.toByte(), bytes[5])
        assertEquals(0x02.toByte(), bytes[6])
        assertEquals(0x01.toByte(), bytes[7])
    }

    @Test
    fun `epochToBytes zero`() {
        val bytes = epochToBytes(0L)
        for (b in bytes) assertEquals(0.toByte(), b)
    }

    @Test
    fun `toLittleEndianInt decodes correctly`() {
        val bytes = byteArrayOf(0x04, 0x03, 0x02, 0x01, 0x00, 0x00, 0x00, 0x00)
        assertEquals(0x01020304, toLittleEndianInt(bytes))
    }

    @Test
    fun `toLittleEndianInt negative value`() {
        // 0xFF 0xFF 0xFF 0xFF = -1 in signed int
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, toLittleEndianInt(bytes))
    }

    // ── Timer lifecycle ──────────────────────────────────────────────────────

    @Test
    fun `initial pseudonym computed on start`() = runTest {
        val callbacks = mutableListOf<ByteArray>()
        val rotator =
            PseudonymRotator(
                keyHash = keyHashA,
                cryptoProvider = crypto,
                scope = backgroundScope,
                clock = { testScheduler.currentTime },
                diagnosticSink = NoOpDiagnosticSink,
                onRotation = { callbacks.add(it.copyOf()) },
                epochDurationMs = testEpochMs,
            )

        rotator.start()
        testScheduler.runCurrent()

        assertEquals(1, callbacks.size, "onRotation should be called once on start")
        assertEquals(12, callbacks[0].size, "Pseudonym must be 12 bytes")
        assertContentEquals(rotator.currentPseudonym(), callbacks[0])
    }

    @Test
    fun `timer fires after epoch boundary plus stagger`() = runTest {
        val callbacks = mutableListOf<ByteArray>()
        val sink =
            DiagnosticSink(
                bufferCapacity = 16,
                redactFn = null,
                clock = { testScheduler.currentTime },
                wallClock = { testScheduler.currentTime },
            )
        val rotator =
            PseudonymRotator(
                keyHash = keyHashA,
                cryptoProvider = crypto,
                scope = backgroundScope,
                clock = { testScheduler.currentTime },
                diagnosticSink = sink,
                onRotation = { callbacks.add(it.copyOf()) },
                epochDurationMs = testEpochMs,
            )

        rotator.start()
        testScheduler.runCurrent()
        assertEquals(1, callbacks.size, "Initial callback")

        // Advance to just before epoch 1 boundary — no new callback yet.
        testScheduler.advanceTimeBy(testEpochMs - 1)
        testScheduler.runCurrent()
        assertEquals(1, callbacks.size, "No rotation before epoch boundary")

        // Advance past epoch 1 boundary + maximum possible stagger.
        // Stagger is < testEpochMs, so advancing by 2 * testEpochMs from start guarantees
        // we've crossed both the boundary and stagger.
        testScheduler.advanceTimeBy(testEpochMs + 1)
        testScheduler.runCurrent()
        assertTrue(
            callbacks.size >= 2,
            "Rotation should have fired after epoch 1 boundary + stagger",
        )

        // Verify the rotated pseudonym matches epoch 1 computation.
        val epoch1Pseudonym = rotator.computePseudonym(1L)
        assertContentEquals(epoch1Pseudonym, callbacks[1])

        // Verify diagnostic event was emitted.
        val lastEvent = sink.events.replayCache.lastOrNull()
        assertTrue(lastEvent != null, "Diagnostic event should be emitted on rotation")
        assertEquals(DiagnosticCode.HANDSHAKE_EVENT, lastEvent.code)
        val payload = lastEvent.payload
        assertTrue(payload is DiagnosticPayload.TextMessage)
        assertTrue(payload.message.contains("pseudonym_rotated"))
    }

    @Test
    fun `multiple epoch rotations fire sequentially`() = runTest {
        val callbacks = mutableListOf<ByteArray>()
        val rotator =
            PseudonymRotator(
                keyHash = keyHashA,
                cryptoProvider = crypto,
                scope = backgroundScope,
                clock = { testScheduler.currentTime },
                diagnosticSink = NoOpDiagnosticSink,
                onRotation = { callbacks.add(it.copyOf()) },
                epochDurationMs = testEpochMs,
            )

        rotator.start()
        testScheduler.runCurrent()
        assertEquals(1, callbacks.size)

        // Advance through 3 full epochs (enough time for boundary + stagger for each).
        testScheduler.advanceTimeBy(testEpochMs * 4)
        testScheduler.runCurrent()

        // Should have at least 4 callbacks: initial + 3 rotations.
        assertTrue(callbacks.size >= 4, "Expected at least 4 callbacks, got ${callbacks.size}")
    }

    @Test
    fun `scope cancellation stops timer`() = runTest {
        val callbacks = mutableListOf<ByteArray>()
        val rotator =
            PseudonymRotator(
                keyHash = keyHashA,
                cryptoProvider = crypto,
                scope = backgroundScope,
                clock = { testScheduler.currentTime },
                diagnosticSink = NoOpDiagnosticSink,
                onRotation = { callbacks.add(it.copyOf()) },
                epochDurationMs = testEpochMs,
            )

        rotator.start()
        testScheduler.runCurrent()
        assertEquals(1, callbacks.size, "Initial callback")

        // Cancel the scope.
        backgroundScope.cancel()
        testScheduler.runCurrent()

        val countAfterCancel = callbacks.size

        // Advance time past several epochs.
        testScheduler.advanceTimeBy(testEpochMs * 5)
        testScheduler.runCurrent()

        assertEquals(
            countAfterCancel,
            callbacks.size,
            "No further callbacks after scope cancellation",
        )
    }

    @Test
    fun `currentPseudonym updates after rotation`() = runTest {
        val rotator =
            PseudonymRotator(
                keyHash = keyHashA,
                cryptoProvider = crypto,
                scope = backgroundScope,
                clock = { testScheduler.currentTime },
                diagnosticSink = NoOpDiagnosticSink,
                onRotation = {},
                epochDurationMs = testEpochMs,
            )

        // Before start, currentPseudonym is empty.
        assertEquals(0, rotator.currentPseudonym().size)

        rotator.start()
        testScheduler.runCurrent()

        val initial = rotator.currentPseudonym().copyOf()
        assertEquals(12, initial.size)
        assertContentEquals(rotator.computePseudonym(0L), initial)

        // Advance past epoch 1 boundary + stagger.
        testScheduler.advanceTimeBy(testEpochMs * 2)
        testScheduler.runCurrent()

        val updated = rotator.currentPseudonym()
        assertEquals(12, updated.size)
        assertFalse(initial.contentEquals(updated), "Pseudonym should change after rotation")
    }

    // ── Default epoch constant ───────────────────────────────────────────────

    @Test
    fun `EPOCH_DURATION_MS is 15 minutes`() {
        assertEquals(900_000L, PseudonymRotator.EPOCH_DURATION_MS)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Creates a non-started rotator with NoOp diagnostic sink and a no-op callback for pure
     * computation tests.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun createRotator(keyHash: ByteArray, clock: () -> Long): PseudonymRotator =
        PseudonymRotator(
            keyHash = keyHash,
            cryptoProvider = crypto,
            scope = kotlinx.coroutines.GlobalScope, // never started in computation-only tests
            clock = clock,
            diagnosticSink = NoOpDiagnosticSink,
            onRotation = {},
            epochDurationMs = testEpochMs,
        )
}
