package io.meshlink.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HexUtilTest {

    // --- Batch 9 Cycle 2: HexUtil round-trip correctness ---

    @Test
    fun toHexAndHexToBytesRoundTrip() {
        // Empty
        assertEquals("", ByteArray(0).toHex())
        assertContentEquals(ByteArray(0), hexToBytes(""))

        // Single byte edge cases
        assertEquals("00", byteArrayOf(0x00).toHex())
        assertEquals("0f", byteArrayOf(0x0F).toHex())
        assertEquals("f0", byteArrayOf(0xF0.toByte()).toHex())
        assertEquals("ff", byteArrayOf(0xFF.toByte()).toHex())

        // Round-trip: arbitrary 16-byte peer ID
        val peerId = ByteArray(8) { (0xA0 + it).toByte() }
        val hex = peerId.toHex()
        assertEquals(16, hex.length)
        assertContentEquals(peerId, hexToBytes(hex))

        // Round-trip: all-zero
        val zeros = ByteArray(16)
        assertContentEquals(zeros, hexToBytes(zeros.toHex()))

        // Round-trip: all-FF
        val ffs = ByteArray(8) { 0xFF.toByte() }
        assertContentEquals(ffs, hexToBytes(ffs.toHex()))
    }
}
