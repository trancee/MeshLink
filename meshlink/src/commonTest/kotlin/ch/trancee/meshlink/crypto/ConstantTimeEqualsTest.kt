package ch.trancee.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConstantTimeEqualsTest {

    @Test
    fun equalArrays() {
        // Arrange
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(1, 2, 3, 4)

        // Act
        val result = constantTimeEquals(a, b)

        // Assert
        assertTrue(result)
    }

    @Test
    fun unequalArraysSameLengthFirstByteDiffers() {
        // Arrange
        val a = byteArrayOf(0, 2, 3, 4)
        val b = byteArrayOf(1, 2, 3, 4)

        // Act
        val result = constantTimeEquals(a, b)

        // Assert
        assertFalse(result)
    }

    @Test
    fun unequalArraysSameLengthLastByteDiffers() {
        // Arrange
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(1, 2, 3, 5)

        // Act
        val result = constantTimeEquals(a, b)

        // Assert
        assertFalse(result)
    }

    @Test
    fun differentLengths() {
        // Arrange
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(1, 2, 3, 4)

        // Act
        val result = constantTimeEquals(a, b)

        // Assert
        assertFalse(result)
    }

    @Test
    fun bothEmpty() {
        // Arrange
        val a = byteArrayOf()
        val b = byteArrayOf()

        // Act
        val result = constantTimeEquals(a, b)

        // Assert
        assertTrue(result)
    }

    @Test
    fun singleByteEqual() {
        // Arrange
        val a = byteArrayOf(42)
        val b = byteArrayOf(42)

        // Act
        val result = constantTimeEquals(a, b)

        // Assert
        assertTrue(result)
    }

    @Test
    fun singleByteUnequal() {
        // Arrange
        val a = byteArrayOf(0)
        val b = byteArrayOf(1)

        // Act
        val result = constantTimeEquals(a, b)

        // Assert
        assertFalse(result)
    }
}
