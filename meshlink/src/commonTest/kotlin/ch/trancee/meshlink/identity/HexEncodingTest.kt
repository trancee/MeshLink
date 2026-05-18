package ch.trancee.meshlink.identity

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexEncodingTest {
    @Test
    fun hexContentEquals_matchesEquivalentBytesWithoutAllocatingHexString() {
        val bytes = byteArrayOf(0x01, 0xAB.toByte(), 0x7F)

        assertTrue("01ab7f".hexContentEquals(bytes))
        assertTrue("01AB7F".hexContentEquals(bytes))
        assertFalse("01ab70".hexContentEquals(bytes))
        assertFalse("01ab7".hexContentEquals(bytes))
        assertFalse("01ab7g".hexContentEquals(bytes))
    }

    @Test
    fun hexStartsWith_matchesBytePrefixesWithoutAllocatingHexString() {
        val prefix = byteArrayOf(0x10, 0x20, 0x30)

        assertTrue("10203040ff".hexStartsWith(prefix))
        assertTrue("102030".hexStartsWith(prefix))
        assertFalse("10203140ff".hexStartsWith(prefix))
        assertFalse("10203".hexStartsWith(prefix))
        assertFalse("10203z40ff".hexStartsWith(prefix))
    }

    @Test
    fun toHexByteArray_and_hexContentEquals_compareWithoutStringRoundTrip() {
        val raw = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0x10, 0x00)
        val hexBytes = raw.toHexByteArray()

        assertContentEquals("cafe1000".encodeToByteArray(), hexBytes)
        assertTrue(hexBytes.hexContentEquals(raw))
        assertFalse(hexBytes.hexContentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0x10, 0x01)))
    }
}
