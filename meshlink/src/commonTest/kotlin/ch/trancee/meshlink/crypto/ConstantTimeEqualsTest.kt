package ch.trancee.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConstantTimeEqualsTest {

    @Test
    fun equalArrays() {
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(1, 2, 3, 4)
        assertTrue(constantTimeEquals(a, b))
    }

    @Test
    fun unequalArraysSameLengthFirstByteDiffers() {
        val a = byteArrayOf(0, 2, 3, 4)
        val b = byteArrayOf(1, 2, 3, 4)
        assertFalse(constantTimeEquals(a, b))
    }

    @Test
    fun unequalArraysSameLengthLastByteDiffers() {
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(1, 2, 3, 5)
        assertFalse(constantTimeEquals(a, b))
    }

    @Test
    fun differentLengths() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(1, 2, 3, 4)
        assertFalse(constantTimeEquals(a, b))
    }

    @Test
    fun bothEmpty() {
        assertTrue(constantTimeEquals(byteArrayOf(), byteArrayOf()))
    }

    @Test
    fun singleByteEqual() {
        assertTrue(constantTimeEquals(byteArrayOf(42), byteArrayOf(42)))
    }

    @Test
    fun singleByteUnequal() {
        assertFalse(constantTimeEquals(byteArrayOf(0), byteArrayOf(1)))
    }
}
