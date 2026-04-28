package ch.trancee.meshlink.engine

import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.engine.PseudonymRotator.Companion.epochToBytes
import ch.trancee.meshlink.engine.PseudonymRotator.Companion.verifyPseudonym
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ─────────────────────────────────────────────────────────────────────────────
// PseudonymVerification unit tests — ±1 epoch tolerance, mismatch detection
// ─────────────────────────────────────────────────────────────────────────────

class PseudonymVerificationTest {

    private val crypto = createCryptoProvider()
    private val keyHashA = crypto.sha256("node-A-identity".encodeToByteArray()).copyOf(12)
    private val keyHashB = crypto.sha256("node-B-identity".encodeToByteArray()).copyOf(12)

    // ── Happy-path: tolerance window ─────────────────────────────────────────

    @Test
    fun `valid pseudonym for current epoch passes`() {
        val epoch = 42L
        val pseudonym = computePseudonym(keyHashA, epoch)
        assertTrue(
            verifyPseudonym(keyHashA, pseudonym, epoch, crypto),
            "Pseudonym for current epoch must verify",
        )
    }

    @Test
    fun `valid pseudonym for previous epoch passes`() {
        val epoch = 42L
        val pseudonym = computePseudonym(keyHashA, epoch - 1)
        assertTrue(
            verifyPseudonym(keyHashA, pseudonym, epoch, crypto),
            "Pseudonym for epoch-1 must verify (±1 tolerance)",
        )
    }

    @Test
    fun `valid pseudonym for next epoch passes`() {
        val epoch = 42L
        val pseudonym = computePseudonym(keyHashA, epoch + 1)
        assertTrue(
            verifyPseudonym(keyHashA, pseudonym, epoch, crypto),
            "Pseudonym for epoch+1 must verify (±1 tolerance)",
        )
    }

    // ── Negative cases ───────────────────────────────────────────────────────

    @Test
    fun `invalid pseudonym fails`() {
        val epoch = 42L
        val randomPseudonym = ByteArray(12) { (it * 7 + 3).toByte() }
        assertFalse(
            verifyPseudonym(keyHashA, randomPseudonym, epoch, crypto),
            "Random 12-byte pseudonym must not verify",
        )
    }

    @Test
    fun `wrong keyHash fails`() {
        val epoch = 42L
        // Pseudonym computed with keyHashA, verified against keyHashB → mismatch
        val pseudonymFromA = computePseudonym(keyHashA, epoch)
        assertFalse(
            verifyPseudonym(keyHashB, pseudonymFromA, epoch, crypto),
            "Pseudonym from different keyHash must not verify",
        )
    }

    // ── Boundary conditions ──────────────────────────────────────────────────

    @Test
    fun `epoch 0 handles gracefully without negative epoch check`() {
        val epoch = 0L
        val pseudonym = computePseudonym(keyHashA, epoch)
        // At epoch 0, the tolerance window is [0, 0, 1] (negative epoch skipped).
        assertTrue(
            verifyPseudonym(keyHashA, pseudonym, epoch, crypto),
            "Pseudonym for epoch 0 must verify",
        )
    }

    @Test
    fun `epoch 0 does not crash when checking minus-1`() {
        // Pseudonym for epoch 0, verified at epoch 1 → should pass (epoch-1 = 0).
        val pseudonym = computePseudonym(keyHashA, 0L)
        assertTrue(
            verifyPseudonym(keyHashA, pseudonym, 1L, crypto),
            "Pseudonym for epoch 0 verified at epoch 1 must pass (±1 tolerance)",
        )
    }

    @Test
    fun `pseudonym two epochs away fails`() {
        val epoch = 42L
        // Pseudonym for epoch-2 should NOT match at epoch 42 (outside ±1).
        val pseudonym = computePseudonym(keyHashA, epoch - 2)
        assertFalse(
            verifyPseudonym(keyHashA, pseudonym, epoch, crypto),
            "Pseudonym from 2 epochs ago must not verify (outside ±1 tolerance)",
        )
    }

    @Test
    fun `wrong-length pseudonym returns false`() {
        val epoch = 42L
        // 11-byte pseudonym (too short).
        assertFalse(
            verifyPseudonym(keyHashA, ByteArray(11), epoch, crypto),
            "Pseudonym shorter than 12 bytes must not verify",
        )
        // 13-byte pseudonym (too long).
        assertFalse(
            verifyPseudonym(keyHashA, ByteArray(13), epoch, crypto),
            "Pseudonym longer than 12 bytes must not verify",
        )
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    /** Computes the expected 12-byte pseudonym for [keyHash] at [epoch]. */
    private fun computePseudonym(keyHash: ByteArray, epoch: Long): ByteArray =
        crypto.hmacSha256(keyHash, epochToBytes(epoch)).copyOf(12)
}
