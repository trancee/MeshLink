package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionInitiationPolicyTest {

    // ── Helper: build a 12-byte key hash from Int varargs ────────────────────

    private fun keyHash(vararg bytes: Int): ByteArray {
        val b = ByteArray(12)
        for (i in bytes.indices) b[i] = bytes[i].toByte()
        return b
    }

    // ── powerMode tie-breaking ────────────────────────────────────────────────

    @Test
    fun lowerPowerModeInitiates() {
        // Local powerMode=0 (Performance) vs remote powerMode=1 (Balanced) → local initiates
        val kh = keyHash(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertTrue(ConnectionInitiationPolicy.shouldInitiate(kh, 0, kh, 1))
    }

    @Test
    fun higherPowerModeDoesNotInitiate() {
        // Local powerMode=1 vs remote powerMode=0 → remote should have initiated, not local
        val kh = keyHash(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertFalse(ConnectionInitiationPolicy.shouldInitiate(kh, 1, kh, 0))
    }

    @Test
    fun powerModePowerSaverVsPerformance() {
        // Local powerMode=2 (PowerSaver) vs remote=0 (Performance) → local should NOT initiate
        val kh = keyHash(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertFalse(ConnectionInitiationPolicy.shouldInitiate(kh, 2, kh, 0))
    }

    // ── keyHash tie-breaking (same powerMode) ─────────────────────────────────

    @Test
    fun samePowerModeHigherFirstByteInitiates() {
        // First byte of local (0x02) > first byte of remote (0x01) → local initiates
        val local = keyHash(0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val remote = keyHash(0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertTrue(ConnectionInitiationPolicy.shouldInitiate(local, 1, remote, 1))
    }

    @Test
    fun samePowerModeLowerFirstByteDoesNotInitiate() {
        // First byte of local (0x01) < first byte of remote (0x02) → local does NOT initiate
        val local = keyHash(0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val remote = keyHash(0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertFalse(ConnectionInitiationPolicy.shouldInitiate(local, 1, remote, 1))
    }

    @Test
    fun samePowerModeIdenticalKeyHashTieReturnsFalse() {
        // All bytes equal → tie, neither device should initiate
        val kh = keyHash(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C)
        assertFalse(ConnectionInitiationPolicy.shouldInitiate(kh, 0, kh.copyOf(), 0))
    }

    @Test
    fun laterByteBreaksTieWhenFirstByteEqual() {
        // First byte same (0x01), second byte differs (0x03 vs 0x02) → local higher, initiates
        val local = keyHash(0x01, 0x03, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val remote = keyHash(0x01, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertTrue(ConnectionInitiationPolicy.shouldInitiate(local, 1, remote, 1))
    }

    @Test
    fun unsignedByteComparisonHighByteWins() {
        // 0xFF (unsigned 255) > 0x7F (unsigned 127) when treated as unsigned
        val local = keyHash(0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val remote = keyHash(0x7F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertTrue(ConnectionInitiationPolicy.shouldInitiate(local, 0, remote, 0))
    }

    // ── staggerDelayMs ────────────────────────────────────────────────────────

    @Test
    fun staggerDelayDeterministicForSameInputs() {
        val kh1 = keyHash(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C)
        val kh2 = keyHash(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
        val delay1 = ConnectionInitiationPolicy.staggerDelayMs(kh1, kh2)
        val delay2 = ConnectionInitiationPolicy.staggerDelayMs(kh1, kh2)
        assertEquals(delay1, delay2)
        assertTrue(delay1 in 0L until 2_000L)
    }

    @Test
    fun staggerDelaySymmetricXorCommutativity() {
        // XOR is commutative so staggerDelayMs(A, B) == staggerDelayMs(B, A)
        val kh1 = keyHash(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C)
        val kh2 = keyHash(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
        assertEquals(
            ConnectionInitiationPolicy.staggerDelayMs(kh1, kh2),
            ConnectionInitiationPolicy.staggerDelayMs(kh2, kh1),
        )
    }

    // ── shouldInitiate validation ─────────────────────────────────────────────

    @Test
    fun shouldInitiateLocalKeyHashTooShortThrows() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionInitiationPolicy.shouldInitiate(ByteArray(11), 0, ByteArray(12), 0)
        }
    }

    @Test
    fun shouldInitiateLocalKeyHashTooLongThrows() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionInitiationPolicy.shouldInitiate(ByteArray(13), 0, ByteArray(12), 0)
        }
    }

    @Test
    fun shouldInitiateRemoteKeyHashTooShortThrows() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionInitiationPolicy.shouldInitiate(ByteArray(12), 0, ByteArray(11), 0)
        }
    }

    @Test
    fun shouldInitiateRemoteKeyHashTooLongThrows() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionInitiationPolicy.shouldInitiate(ByteArray(12), 0, ByteArray(13), 0)
        }
    }

    // ── staggerDelayMs validation ─────────────────────────────────────────────

    @Test
    fun staggerDelayLocalKeyHashTooShortThrows() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionInitiationPolicy.staggerDelayMs(ByteArray(11), ByteArray(12))
        }
    }

    @Test
    fun staggerDelayLocalKeyHashTooLongThrows() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionInitiationPolicy.staggerDelayMs(ByteArray(13), ByteArray(12))
        }
    }

    @Test
    fun staggerDelayRemoteKeyHashTooShortThrows() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionInitiationPolicy.staggerDelayMs(ByteArray(12), ByteArray(11))
        }
    }

    @Test
    fun staggerDelayRemoteKeyHashTooLongThrows() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionInitiationPolicy.staggerDelayMs(ByteArray(12), ByteArray(13))
        }
    }
}
