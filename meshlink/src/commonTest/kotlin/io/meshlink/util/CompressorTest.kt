package io.meshlink.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class CompressorTest {

    private val compressor = Compressor()

    @Test
    fun compressDecompressRoundTrip() {
        val original = "Hello, MeshLink! This is a test payload for compression.".encodeToByteArray()
        val compressed = compressor.compress(original)
        val decompressed = compressor.decompress(compressed, original.size)
        assertContentEquals(original, decompressed)
    }

    @Test
    fun compressEmptyPayload() {
        val original = ByteArray(0)
        val compressed = compressor.compress(original)
        val decompressed = compressor.decompress(compressed, 0)
        assertContentEquals(original, decompressed)
    }

    @Test
    fun compressLargeRepetitivePayload() {
        val original = ByteArray(10_000) { (it % 256).toByte() }
        val compressed = compressor.compress(original)
        assertTrue(compressed.size < original.size, "Repetitive data should compress well")
        val decompressed = compressor.decompress(compressed, original.size)
        assertContentEquals(original, decompressed)
    }

    @Test
    fun compressIncompressibleData() {
        val original = ByteArray(256) { it.toByte() }
        val compressed = compressor.compress(original)
        val decompressed = compressor.decompress(compressed, original.size)
        assertContentEquals(original, decompressed)
    }

    @Test
    fun compressAllZeros() {
        val original = ByteArray(1000)
        val compressed = compressor.compress(original)
        assertTrue(compressed.size < original.size, "All-zero data should compress very well")
        val decompressed = compressor.decompress(compressed, original.size)
        assertContentEquals(original, decompressed)
    }

    @Test
    fun compressSingleByte() {
        val original = byteArrayOf(0x42)
        val compressed = compressor.compress(original)
        val decompressed = compressor.decompress(compressed, original.size)
        assertContentEquals(original, decompressed)
    }
}
