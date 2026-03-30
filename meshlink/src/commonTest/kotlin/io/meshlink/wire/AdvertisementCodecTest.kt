package io.meshlink.wire

import io.meshlink.crypto.Sha256
import io.meshlink.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvertisementCodecTest {

    // Fixed 32-byte key for deterministic tests
    private val testKey = ByteArray(32) { it.toByte() }
    private val testKeyHash = Sha256.hash(testKey)

    // ── Round-trip ───────────────────────────────────────────────────────

    @Test
    fun roundTripPreservesAllFields() {
        val encoded = AdvertisementCodec.encode(
            versionMajor = 1,
            versionMinor = 0,
            powerMode = 2,
            publicKeyHash = testKeyHash,
        )
        val decoded = AdvertisementCodec.decode(encoded)

        assertEquals(1, decoded.versionMajor)
        assertEquals(0, decoded.versionMinor)
        assertEquals(2, decoded.powerMode)
    }

    @Test
    fun roundTripPreservesKeyHash() {
        val encoded = AdvertisementCodec.encode(
            versionMajor = 3,
            versionMinor = 42,
            powerMode = 1,
            publicKeyHash = testKeyHash,
        )
        val decoded = AdvertisementCodec.decode(encoded)

        val expectedHash = testKeyHash.copyOfRange(0, AdvertisementCodec.KEY_HASH_SIZE)
        assertContentEquals(expectedHash, decoded.keyHash)
    }

    // ── Known vector ────────────────────────────────────────────────────

    @Test
    fun knownVectorMatchesSha256Prefix() {
        // SHA-256 of 32 zero bytes
        val zeroKey = ByteArray(32)
        val fullHash = Sha256.hash(zeroKey)
        val expectedPrefix = fullHash.copyOfRange(0, AdvertisementCodec.KEY_HASH_SIZE)

        val encoded = AdvertisementCodec.encode(
            versionMajor = 0,
            versionMinor = 0,
            powerMode = 0,
            publicKeyHash = fullHash,
        )

        // Bytes 2-(2+KEY_HASH_SIZE) must be the first KEY_HASH_SIZE bytes of the hash
        val hashSlice = encoded.copyOfRange(2, 2 + AdvertisementCodec.KEY_HASH_SIZE)
        assertContentEquals(expectedPrefix, hashSlice)
    }

    // ── Bit-packing ─────────────────────────────────────────────────────

    @Test
    fun bitPackingMajor1Minor0Power2() {
        val encoded = AdvertisementCodec.encode(
            versionMajor = 1,
            versionMinor = 0,
            powerMode = 2,
            publicKeyHash = testKeyHash,
        )
        // major=1 → upper nibble 0x1_, power=2 → lower nibble 0x_2 → 0x12
        assertEquals(0x12.toByte(), encoded[0])
        assertEquals(0x00.toByte(), encoded[1])
    }

    @Test
    fun bitPackingMajor15Minor255Power15() {
        val encoded = AdvertisementCodec.encode(
            versionMajor = 15,
            versionMinor = 255,
            powerMode = 15,
            publicKeyHash = testKeyHash,
        )
        // major=15 → 0xF_, power=15 → 0x_F → 0xFF
        assertEquals(0xFF.toByte(), encoded[0])
        assertEquals(0xFF.toByte(), encoded[1])
    }

    @Test
    fun bitPackingMajor0Minor0Power0() {
        val encoded = AdvertisementCodec.encode(
            versionMajor = 0,
            versionMinor = 0,
            powerMode = 0,
            publicKeyHash = testKeyHash,
        )
        assertEquals(0x00.toByte(), encoded[0])
        assertEquals(0x00.toByte(), encoded[1])
    }

    // ── Power mode encoding ─────────────────────────────────────────────

    @Test
    fun powerModePerformanceBalancedPowerSaver() {
        for ((mode, expectedNibble) in listOf(0 to 0x00, 1 to 0x01, 2 to 0x02)) {
            val encoded = AdvertisementCodec.encode(
                versionMajor = 5,
                versionMinor = 10,
                powerMode = mode,
                publicKeyHash = testKeyHash,
            )
            val byte0 = encoded[0].toInt() and 0xFF
            assertEquals(expectedNibble, byte0 and 0x0F, "Power mode $mode")
            assertEquals(5, byte0 ushr 4, "Major version preserved for power mode $mode")
        }
    }

    // ── Size ─────────────────────────────────────────────────────────────

    @Test
    fun encodedPayloadIsExactly10Bytes() {
        val encoded = AdvertisementCodec.encode(
            versionMajor = 1,
            versionMinor = 0,
            powerMode = 0,
            publicKeyHash = testKeyHash,
        )
        assertEquals(AdvertisementCodec.SIZE, encoded.size)
        assertEquals(10, encoded.size)
    }

    // ── Decode validation ───────────────────────────────────────────────

    @Test
    fun decodeWithShortDataThrows() {
        assertFailsWith<IllegalArgumentException> {
            AdvertisementCodec.decode(ByteArray(9))
        }
        assertFailsWith<IllegalArgumentException> {
            AdvertisementCodec.decode(ByteArray(0))
        }
    }

    // ── Different keys produce different hashes ─────────────────────────

    @Test
    fun differentKeysProduceDifferentHashes() {
        val hashA = Sha256.hash(ByteArray(32) { 0xAA.toByte() })
        val hashB = Sha256.hash(ByteArray(32) { 0xBB.toByte() })

        val encodedA = AdvertisementCodec.encode(1, 0, 0, hashA)
        val encodedB = AdvertisementCodec.encode(1, 0, 0, hashB)

        val sliceA = encodedA.copyOfRange(2, 2 + AdvertisementCodec.KEY_HASH_SIZE)
        val sliceB = encodedB.copyOfRange(2, 2 + AdvertisementCodec.KEY_HASH_SIZE)

        assertFalse(sliceA.contentEquals(sliceB), "Different keys must yield different hash prefixes")
    }

    // ── Clamping ────────────────────────────────────────────────────────

    @Test
    fun outOfRangeValuesAreClamped() {
        // Major > 15 clamped to 15
        val encoded = AdvertisementCodec.encode(
            versionMajor = 99,
            versionMinor = 300,
            powerMode = 20,
            publicKeyHash = testKeyHash,
        )
        val decoded = AdvertisementCodec.decode(encoded)
        assertEquals(15, decoded.versionMajor)
        assertEquals(255, decoded.versionMinor)
        assertEquals(15, decoded.powerMode)
    }

    // ── Decode with extra bytes succeeds ────────────────────────────────

    @Test
    fun decodeIgnoresTrailingBytes() {
        val encoded = AdvertisementCodec.encode(2, 7, 1, testKeyHash)
        val padded = encoded + ByteArray(10) // 27 bytes total
        val decoded = AdvertisementCodec.decode(padded)

        assertEquals(2, decoded.versionMajor)
        assertEquals(7, decoded.versionMinor)
        assertEquals(1, decoded.powerMode)
    }
}
