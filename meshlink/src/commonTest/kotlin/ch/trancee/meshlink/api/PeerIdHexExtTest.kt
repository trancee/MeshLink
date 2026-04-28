package ch.trancee.meshlink.api

import kotlin.test.Test
import kotlin.test.assertEquals

class PeerIdHexExtTest {

    @Test
    fun `toPeerIdHex converts known bytes to lowercase hex`() {
        val bytes = byteArrayOf(0x0A, 0x1B, 0x2C.toByte(), 0xFF.toByte())
        val result = bytes.toPeerIdHex()
        assertEquals("0a1b2cff", result.hex)
    }

    @Test
    fun `toPeerIdHex empty array yields empty hex string`() {
        val result = ByteArray(0).toPeerIdHex()
        assertEquals("", result.hex)
    }

    @Test
    fun `toPeerIdHex single zero byte yields 00`() {
        val result = byteArrayOf(0x00).toPeerIdHex()
        assertEquals("00", result.hex)
    }

    @Test
    fun `toPeerIdHex 12-byte peer id produces 24-char hex string`() {
        val peerId = ByteArray(12) { it.toByte() }
        val result = peerId.toPeerIdHex()
        assertEquals(24, result.hex.length)
        assertEquals("000102030405060708090a0b", result.hex)
    }
}
