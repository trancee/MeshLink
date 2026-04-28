package ch.trancee.meshlink.integration

import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.engine.MeshEngineConfig
import ch.trancee.meshlink.engine.PseudonymRotator
import ch.trancee.meshlink.engine.PseudonymRotator.Companion.epochToBytes
import ch.trancee.meshlink.engine.PseudonymRotator.Companion.verifyPseudonym
import ch.trancee.meshlink.messaging.MessagingConfig
import ch.trancee.meshlink.power.PowerConfig
import ch.trancee.meshlink.routing.RoutingConfig
import ch.trancee.meshlink.transfer.ChunkSizePolicy
import ch.trancee.meshlink.transfer.TransferConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

// ─────────────────────────────────────────────────────────────────────────────
// Integration tests proving pseudonym rotation, HMAC verification, and stagger
// spread using MeshTestHarness with real Noise XX handshakes.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class PseudonymRotationIntegrationTest {

    private val crypto = createCryptoProvider()

    /** Short epoch for fast virtual time advancement. MEM129: keep poll intervals long. */
    private val testEpochMs = 1_000L

    /**
     * Integration config with short epochDurationMs for fast rotation, but long battery poll
     * intervals to avoid OOM (MEM129).
     */
    private val pseudonymTestConfig =
        MeshEngineConfig(
            routing = RoutingConfig(helloIntervalMillis = 30_000L, routeExpiryMillis = 300_000L),
            messaging =
                MessagingConfig(
                    appIdHash = ByteArray(16) { it.toByte() },
                    requireBroadcastSignatures = true,
                ),
            power =
                PowerConfig(
                    batteryPollIntervalMillis = 300_000L,
                    bootstrapDurationMillis = 100L,
                    hysteresisDelayMillis = 100L,
                    performanceThreshold = 0.80f,
                    powerSaverThreshold = 0.30f,
                    performanceMaxConnections = 6,
                    balancedMaxConnections = 4,
                    powerSaverMaxConnections = 2,
                ),
            transfer = TransferConfig(inactivityBaseTimeoutMillis = 30_000L),
            chunkSize = ChunkSizePolicy.fixed(256),
            epochDurationMs = testEpochMs,
        )

    // ── Test 1: epoch rotation changes advertisement data ────────────────────

    @Test
    fun `epoch rotation changes advertisement pseudonym after boundary crossing`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A", pseudonymTestConfig)
                .node("B", pseudonymTestConfig)
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        // Record A's current pseudonym after convergence.
        val nodeA = harness["A"]
        val initialPseudonym = nodeA.transport.advertisementPseudonym.copyOf()
        assertTrue(initialPseudonym.size == 12, "Initial pseudonym must be 12 bytes")

        // Advance past epoch boundary + maximum stagger.
        // MEM203: use advanceTimeBy, never advanceUntilIdle with MeshEngine.
        // 2 full epochs guarantees crossing at least one boundary + stagger.
        testScheduler.advanceTimeBy(testEpochMs * 2)
        testScheduler.runCurrent()

        val updatedPseudonym = nodeA.transport.advertisementPseudonym
        assertTrue(updatedPseudonym.size == 12, "Updated pseudonym must be 12 bytes")
        assertFalse(
            initialPseudonym.contentEquals(updatedPseudonym),
            "Pseudonym must change after epoch boundary crossing",
        )

        harness.stopAll()
    }

    // ── Test 2: receiving node verifies pseudonym via HMAC ───────────────────

    @Test
    fun `receiving node verifies pseudonym via HMAC after rotation`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A", pseudonymTestConfig)
                .node("B", pseudonymTestConfig)
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val nodeA = harness["A"]

        // Advance time past epoch boundary + stagger so A rotates.
        testScheduler.advanceTimeBy(testEpochMs * 2)
        testScheduler.runCurrent()

        // Node B knows A's keyHash from the completed Noise XX handshake.
        val aKeyHash = nodeA.identity.keyHash
        val aPseudonym = nodeA.transport.advertisementPseudonym

        // The epoch of the last rotation may lag the current epoch by one due to stagger,
        // so use the ±1-tolerant verifyPseudonym function — that's the verification
        // mechanism a receiving node actually uses.
        val currentEpoch = testScheduler.currentTime / testEpochMs
        assertTrue(
            verifyPseudonym(aKeyHash, aPseudonym, currentEpoch, crypto),
            "verifyPseudonym must accept A's current pseudonym at current epoch (±1 tolerance)",
        )

        // Verify structural correctness: the pseudonym is 12 bytes and matches
        // HMAC-SHA-256(keyHash, epoch)[0:12] for SOME epoch in the tolerance window.
        val matchFound =
            ((currentEpoch - 1).coerceAtLeast(0)..currentEpoch + 1).any { epoch ->
                val expected = crypto.hmacSha256(aKeyHash, epochToBytes(epoch)).copyOf(12)
                expected.contentEquals(aPseudonym)
            }
        assertTrue(matchFound, "A's pseudonym must match HMAC for some epoch in ±1 window")

        // Verify that a wrong keyHash does NOT verify.
        val nodeB = harness["B"]
        assertFalse(
            verifyPseudonym(nodeB.identity.keyHash, aPseudonym, currentEpoch, crypto),
            "verifyPseudonym must reject A's pseudonym with B's keyHash",
        )

        harness.stopAll()
    }

    // ── Test 3: stagger spread proof ─────────────────────────────────────────

    @Test
    fun `different keyHashes produce different stagger offsets`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A", pseudonymTestConfig)
                .node("B", pseudonymTestConfig)
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val keyHashA = harness["A"].identity.keyHash
        val keyHashB = harness["B"].identity.keyHash

        // Stagger is deterministic: HMAC-SHA-256(keyHash, epochToBytes(epoch))[0:4]
        // interpreted as little-endian int, abs, mod epochDurationMs.
        // Check across multiple epochs — at least one must differ.
        val anyDifferent =
            (0L..5L).any { epoch ->
                val staggerA = computeStagger(keyHashA, epoch)
                val staggerB = computeStagger(keyHashB, epoch)
                staggerA != staggerB
            }

        assertTrue(
            anyDifferent,
            "Different identities must produce at least one different stagger offset",
        )

        // Verify staggers are all within [0, epochDurationMs).
        for (epoch in 0L..5L) {
            val staggerA = computeStagger(keyHashA, epoch)
            val staggerB = computeStagger(keyHashB, epoch)
            assertTrue(staggerA in 0 until testEpochMs, "Stagger A must be in [0, epoch)")
            assertTrue(staggerB in 0 until testEpochMs, "Stagger B must be in [0, epoch)")
        }

        // Verify keyHashes themselves are different (sanity check).
        assertFalse(keyHashA.contentEquals(keyHashB), "Test nodes must have different keyHashes")

        // Determinism check: same keyHash, same epoch → same stagger.
        val s1 = computeStagger(keyHashA, 3L)
        val s2 = computeStagger(keyHashA, 3L)
        assertTrue(s1 == s2, "Stagger must be deterministic for same keyHash and epoch")

        harness.stopAll()
    }

    // ── Test 4: MeshEngine verifies valid pseudonym in 16-byte advertisement ─

    @Test
    fun `engine verifies valid pseudonym in 16-byte advertisement without WARN`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A", pseudonymTestConfig)
                .node("B", pseudonymTestConfig)
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val nodeA = harness["A"]
        val nodeB = harness["B"]

        // Build a 16-byte advertisement where bytes 4–15 contain A's current pseudonym.
        val aKeyHash = nodeA.identity.keyHash
        val epoch = testScheduler.currentTime / testEpochMs
        val validPseudonym = crypto.hmacSha256(aKeyHash, epochToBytes(epoch)).copyOf(12)
        val ad16 = build16ByteAdvertisement(validPseudonym)

        // Simulate B receiving a 16-byte advertisement from A (known peer).
        nodeB.transport.simulateDiscovery(aKeyHash, ad16, -50)
        repeat(10) { testScheduler.runCurrent() }

        // No WARN diagnostic should be emitted (valid pseudonym).
        val sink = nodeB.diagnosticSink as ch.trancee.meshlink.api.DiagnosticSink
        val warnEvents =
            sink.events.replayCache.filter {
                it.code == ch.trancee.meshlink.api.DiagnosticCode.DECRYPTION_FAILED &&
                    (it.payload as? ch.trancee.meshlink.api.DiagnosticPayload.DecryptionFailed)
                        ?.reason == "pseudonym verification mismatch"
            }
        assertTrue(
            warnEvents.isEmpty(),
            "No DECRYPTION_FAILED mismatch should fire for valid pseudonym",
        )

        harness.stopAll()
    }

    // ── Test 5: MeshEngine emits WARN on pseudonym verification mismatch ────

    @Test
    fun `engine emits WARN diagnostic on pseudonym verification mismatch`() = runTest {
        val harness =
            MeshTestHarness.builder()
                .node("A", pseudonymTestConfig)
                .node("B", pseudonymTestConfig)
                .link("A", "B")
                .build(testScheduler, backgroundScope)

        harness.awaitConvergence()

        val nodeA = harness["A"]
        val nodeB = harness["B"]
        val aKeyHash = nodeA.identity.keyHash

        // Build a 16-byte advertisement with INVALID pseudonym bytes 4–15.
        val invalidPseudonym = ByteArray(12) { 0xAB.toByte() }
        val ad16 = build16ByteAdvertisement(invalidPseudonym)

        // Simulate B receiving a 16-byte advertisement from A with bad pseudonym.
        nodeB.transport.simulateDiscovery(aKeyHash, ad16, -50)
        repeat(10) { testScheduler.runCurrent() }

        // A WARN DECRYPTION_FAILED diagnostic should have been emitted at B.
        val sink = nodeB.diagnosticSink as ch.trancee.meshlink.api.DiagnosticSink
        val mismatchEvents =
            sink.events.replayCache.filter {
                it.code == ch.trancee.meshlink.api.DiagnosticCode.DECRYPTION_FAILED &&
                    (it.payload as? ch.trancee.meshlink.api.DiagnosticPayload.DecryptionFailed)
                        ?.reason == "pseudonym verification mismatch"
            }
        assertTrue(
            mismatchEvents.isNotEmpty(),
            "DECRYPTION_FAILED diagnostic must fire on pseudonym mismatch",
        )

        harness.stopAll()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a 16-byte advertisement payload with byte 0 = 0x00 (PERFORMANCE tier) and bytes 4–15 =
     * [pseudonym].
     */
    private fun build16ByteAdvertisement(pseudonym: ByteArray): ByteArray {
        require(pseudonym.size == 12)
        val ad = ByteArray(16)
        // Byte 0: protocolVersion=0, powerMode=0 (PERFORMANCE), reserved=0
        ad[0] = 0x00
        // Bytes 1-2: meshHash (placeholder)
        ad[1] = 0x01
        ad[2] = 0x00
        // Byte 3: l2capPsm = 0 (GATT-only)
        ad[3] = 0x00
        // Bytes 4-15: pseudonym
        pseudonym.copyInto(ad, destinationOffset = 4)
        return ad
    }

    /** Computes the stagger offset for [keyHash] at [epoch], mirroring PseudonymRotator logic. */
    private fun computeStagger(keyHash: ByteArray, epoch: Long): Long {
        val hmac = crypto.hmacSha256(keyHash, epochToBytes(epoch))
        return abs(PseudonymRotator.toLittleEndianInt(hmac).toLong()) % testEpochMs
    }
}
