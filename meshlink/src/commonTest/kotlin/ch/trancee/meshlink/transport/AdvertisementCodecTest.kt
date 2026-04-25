package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Comprehensive golden-vector and coverage test suite for [AdvertisementCodec] and
 * [AdvertisementCodec.AdvertisementPayload].
 *
 * Spec §7 advertisement layout (byte-exact):
 * ```
 * Byte 0 [7:5]  protocolVersion  3 bits
 * Byte 0 [4:3]  powerMode        2 bits
 * Byte 0 [2:0]  reserved         0
 * Bytes 1–2     meshHash         LE UShort
 * Byte  3       l2capPsm         0x00 or 128–255
 * Bytes 4–15    keyHash          12 bytes
 * ```
 */
class AdvertisementCodecTest {

    // ── Shared test fixtures ──────────────────────────────────────────────────

    private val key12Zeros = ByteArray(12)
    private val key12Ff = ByteArray(12) { 0xFF.toByte() }
    private val key12Seq =
        byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C)

    // ── Round-trip golden vectors ─────────────────────────────────────────────

    @Test
    fun roundTripAllZeros() {
        // protocolVersion=0, powerMode=0, meshHash=0x0000, l2capPsm=0x00, keyHash=all-zero
        val p = AdvertisementCodec.AdvertisementPayload(0, 0, 0u, 0u, key12Zeros)
        val expected = ByteArray(16) // all bytes zero
        val encoded = AdvertisementCodec.encode(p)
        assertContentEquals(expected, encoded)
        assertEquals(p, AdvertisementCodec.decode(encoded))
    }

    @Test
    fun roundTripMaxValues() {
        // protocolVersion=7, powerMode=2, meshHash=0xFFFF, l2capPsm=255, keyHash=all-0xFF
        val p = AdvertisementCodec.AdvertisementPayload(7, 2, 0xFFFFu, 255u, key12Ff)
        // byte[0] = (7 shl 5) or (2 shl 3) = 0xE0 or 0x10 = 0xF0
        val expected = byteArrayOf(0xF0.toByte()) + ByteArray(15) { 0xFF.toByte() }
        val encoded = AdvertisementCodec.encode(p)
        assertContentEquals(expected, encoded)
        assertEquals(p, AdvertisementCodec.decode(encoded))
    }

    @Test
    fun roundTripMidRange() {
        // protocolVersion=3, powerMode=1 (Balanced), meshHash=0x1234, l2capPsm=128, keyHash=seq
        val p = AdvertisementCodec.AdvertisementPayload(3, 1, 0x1234u, 128u, key12Seq)
        // byte[0] = (3 shl 5) or (1 shl 3) = 0x60 or 0x08 = 0x68
        // bytes[1]=0x34 (LE low), bytes[2]=0x12 (LE high), bytes[3]=0x80 (128)
        val expected = byteArrayOf(0x68.toByte(), 0x34, 0x12, 0x80.toByte()) + key12Seq
        val encoded = AdvertisementCodec.encode(p)
        assertContentEquals(expected, encoded)
        assertEquals(p, AdvertisementCodec.decode(encoded))
    }

    // ── Bit-exact byte-0 encoding (spec §7) ──────────────────────────────────

    @Test
    fun byteZero_protocolVersion5_powerMode2() {
        // (5 shl 5) = 0xA0, (2 shl 3) = 0x10 → byte[0] = 0xB0
        val encoded = AdvertisementCodec.encode(
            AdvertisementCodec.AdvertisementPayload(5, 2, 0u, 0u, key12Zeros)
        )
        assertEquals(0xB0.toByte(), encoded[0])
    }

    @Test
    fun byteZero_protocolVersion0_powerMode0() {
        val encoded = AdvertisementCodec.encode(
            AdvertisementCodec.AdvertisementPayload(0, 0, 0u, 0u, key12Zeros)
        )
        assertEquals(0x00.toByte(), encoded[0])
    }

    @Test
    fun byteZero_protocolVersion7_powerMode2() {
        // (7 shl 5) = 0xE0, (2 shl 3) = 0x10 → byte[0] = 0xF0
        val encoded = AdvertisementCodec.encode(
            AdvertisementCodec.AdvertisementPayload(7, 2, 0u, 0u, key12Zeros)
        )
        assertEquals(0xF0.toByte(), encoded[0])
    }

    // ── meshHash little-endian encoding ──────────────────────────────────────

    @Test
    fun meshHashLittleEndianEncode() {
        // meshHash=0xABCD → bytes[1]=0xCD (low byte), bytes[2]=0xAB (high byte)
        val encoded = AdvertisementCodec.encode(
            AdvertisementCodec.AdvertisementPayload(0, 0, 0xABCDu, 0u, key12Zeros)
        )
        assertEquals(0xCD.toByte(), encoded[1])
        assertEquals(0xAB.toByte(), encoded[2])
    }

    @Test
    fun meshHashLittleEndianDecode() {
        val bytes = ByteArray(16)
        bytes[1] = 0xCD.toByte()
        bytes[2] = 0xAB.toByte()
        val p = AdvertisementCodec.decode(bytes)
        assertEquals(0xABCDu.toUShort(), p.meshHash)
    }

    // ── l2capPsm boundary encoding ────────────────────────────────────────────

    @Test
    fun l2capPsmZeroEncodesAs0x00() {
        val encoded = AdvertisementCodec.encode(
            AdvertisementCodec.AdvertisementPayload(0, 0, 0u, 0u, key12Zeros)
        )
        assertEquals(0x00.toByte(), encoded[3])
    }

    @Test
    fun l2capPsm128EncodesAs0x80() {
        val encoded = AdvertisementCodec.encode(
            AdvertisementCodec.AdvertisementPayload(0, 0, 0u, 128u, key12Zeros)
        )
        assertEquals(0x80.toByte(), encoded[3])
    }

    @Test
    fun l2capPsm255EncodesAs0xFF() {
        val encoded = AdvertisementCodec.encode(
            AdvertisementCodec.AdvertisementPayload(0, 0, 0u, 255u, key12Zeros)
        )
        assertEquals(0xFF.toByte(), encoded[3])
    }

    // ── Encode validation errors ──────────────────────────────────────────────

    @Test
    fun encodeRejectsProtocolVersionAboveMax() {
        // protocolVersion=8 > 7 → covers the `protocolVersion > 7` branch
        assertFailsWith<IllegalArgumentException> {
            AdvertisementCodec.encode(AdvertisementCodec.AdvertisementPayload(8, 0, 0u, 0u, key12Zeros))
        }
    }

    @Test
    fun encodeRejectsProtocolVersionNegative() {
        // protocolVersion=-1 < 0 → covers the `protocolVersion < 0` short-circuit branch
        assertFailsWith<IllegalArgumentException> {
            AdvertisementCodec.encode(AdvertisementCodec.AdvertisementPayload(-1, 0, 0u, 0u, key12Zeros))
        }
    }

    @Test
    fun encodeRejectsPowerModeReserved() {
        // powerMode=3 > 2 → covers the `powerMode > 2` branch
        assertFailsWith<IllegalArgumentException> {
            AdvertisementCodec.encode(AdvertisementCodec.AdvertisementPayload(0, 3, 0u, 0u, key12Zeros))
        }
    }

    @Test
    fun encodeRejectsPowerModeAboveReserved() {
        // powerMode=4 > 2 (spec allows any out-of-range Int)
        assertFailsWith<IllegalArgumentException> {
            AdvertisementCodec.encode(AdvertisementCodec.AdvertisementPayload(0, 4, 0u, 0u, key12Zeros))
        }
    }

    @Test
    fun encodeRejectsPowerModeNegative() {
        // powerMode=-1 < 0 → covers the `powerMode < 0` short-circuit branch
        assertFailsWith<IllegalArgumentException> {
            AdvertisementCodec.encode(AdvertisementCodec.AdvertisementPayload(0, -1, 0u, 0u, key12Zeros))
        }
    }

    @Test
    fun encodeRejectsL2capPsmLowerInvalidBound() {
        // psm=1 → lowest value in invalid range 1–127
        assertFailsWith<IllegalArgumentException> {
            AdvertisementCodec.encode(AdvertisementCodec.AdvertisementPayload(0, 0, 0u, 1u, key12Zeros))
        }
    }

    @Test
    fun encodeRejectsL2capPsmUpperInvalidBound() {
        // psm=127 → highest value in invalid range 1–127
        assertFailsWith<IllegalArgumentException> {
            AdvertisementCodec.encode(AdvertisementCodec.AdvertisementPayload(0, 0, 0u, 127u, key12Zeros))
        }
    }

    @Test
    fun encodeRejectsKeyHashTooShort() {
        assertFailsWith<IllegalArgumentException> {
            AdvertisementCodec.encode(AdvertisementCodec.AdvertisementPayload(0, 0, 0u, 0u, ByteArray(11)))
        }
    }

    @Test
    fun encodeRejectsKeyHashTooLong() {
        assertFailsWith<IllegalArgumentException> {
            AdvertisementCodec.encode(AdvertisementCodec.AdvertisementPayload(0, 0, 0u, 0u, ByteArray(13)))
        }
    }

    // ── Decode validation errors ──────────────────────────────────────────────

    @Test
    fun decodeRejectsZeroBytes() {
        assertFailsWith<IllegalArgumentException> { AdvertisementCodec.decode(ByteArray(0)) }
    }

    @Test
    fun decodeRejects15Bytes() {
        assertFailsWith<IllegalArgumentException> { AdvertisementCodec.decode(ByteArray(15)) }
    }

    @Test
    fun decodeRejects17Bytes() {
        assertFailsWith<IllegalArgumentException> { AdvertisementCodec.decode(ByteArray(17)) }
    }

    @Test
    fun decodeRejects100Bytes() {
        assertFailsWith<IllegalArgumentException> { AdvertisementCodec.decode(ByteArray(100)) }
    }

    // ── AdvertisementPayload equals / hashCode ────────────────────────────────

    @Test
    fun advertisementPayloadEqualsAndHashCode() {
        val base = AdvertisementCodec.AdvertisementPayload(3, 1, 0x1234u, 128u, key12Seq.copyOf())

        // 1. Same reference → true (short-circuits && chain via this === other)
        assertTrue(base.equals(base))

        // 2. Wrong type → false (other !is AdvertisementPayload)
        assertFalse(base.equals("not a payload"))

        // 3. Different reference, all fields equal → true (covers all && true branches)
        //    base.copy() also covers copy$default "use original" branches for every field.
        val duplicate = base.copy()
        assertTrue(base.equals(duplicate))
        assertEquals(base.hashCode(), duplicate.hashCode())

        // 4. protocolVersion differs → false (short-circuits at 1st &&)
        //    copy(protocolVersion=0) covers copy$default "use provided" for protocolVersion.
        assertFalse(base.equals(base.copy(protocolVersion = 0)))

        // 5. powerMode differs → false (protocolVersion equal, short-circuits at 2nd &&)
        //    copy(powerMode=0) covers copy$default "use provided" for powerMode.
        assertFalse(base.equals(base.copy(powerMode = 0)))

        // 6. meshHash differs → false (first two equal, short-circuits at 3rd &&)
        //    copy(meshHash=...) covers copy$default "use provided" for meshHash.
        assertFalse(base.equals(base.copy(meshHash = 0x0000u)))

        // 7. l2capPsm differs → false (first three equal, short-circuits at 4th &&)
        //    copy(l2capPsm=...) covers copy$default "use provided" for l2capPsm.
        assertFalse(base.equals(base.copy(l2capPsm = 0u)))

        // 8. keyHash content differs → false (first four equal, contentEquals returns false)
        //    copy(keyHash=...) covers copy$default "use provided" for keyHash.
        assertFalse(base.equals(base.copy(keyHash = ByteArray(12) { 0xFF.toByte() })))
    }

    // ── AdvertisementPayload toString ─────────────────────────────────────────

    @Test
    fun advertisementPayloadToStringContainsClassName() {
        val p = AdvertisementCodec.AdvertisementPayload(1, 0, 0x0001u, 128u, key12Zeros)
        assertTrue(p.toString().contains("AdvertisementPayload"))
    }

    // ── AdvertisementPayload destructuring (componentN coverage) ─────────────

    @Test
    fun advertisementPayloadDestructuring() {
        val p = AdvertisementCodec.AdvertisementPayload(4, 2, 0xABCDu, 200u, key12Seq.copyOf())
        val (pv, pm, mh, psm, kh) = p
        assertEquals(4, pv)
        assertEquals(2, pm)
        assertEquals(0xABCDu.toUShort(), mh)
        assertEquals(200u.toUByte(), psm)
        assertContentEquals(key12Seq, kh)
    }
}
