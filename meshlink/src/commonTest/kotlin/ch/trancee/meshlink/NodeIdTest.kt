package ch.trancee.meshlink

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

class NodeIdTest {
    @Test
    fun nodeIdsWithEqualBytesAreEqualAndPrintAsLowercaseHex() {
        // Arrange
        val bytes = ByteArray(32) { (it * 8).toByte() }

        // Act
        val id = NodeId(bytes)

        // Assert
        assertEquals(NodeId(bytes.copyOf()), id)
        assertEquals(NodeId(bytes.copyOf()).hashCode(), id.hashCode())
        assertEquals((0 until 32).joinToString("") { (it * 8).toString(16).padStart(2, '0') }, id.toString())
    }

    @Test
    fun nodeIdsWithDifferentBytesOrTypesDiffer() {
        // Arrange
        val id = NodeId(ByteArray(32))
        val hex: Any = id.toString()

        // Act
        val equalToOtherBytes = id.equals(NodeId(ByteArray(32) { 1 }))
        val equalToItsHex = id.equals(hex)

        // Assert
        assertFalse(equalToOtherBytes)
        assertFalse(equalToItsHex)
    }

    @Test
    fun nodeIdKeepsItsOwnCopyOfTheBytes() {
        // Arrange
        val bytes = ByteArray(32) { 3 }
        val id = NodeId(bytes)

        // Act
        bytes[0] = 9
        val copy = id.toByteArray()
        copy[1] = 9

        // Assert
        assertContentEquals(ByteArray(32) { 3 }, id.toByteArray())
        assertNotSame(id.toByteArray(), id.toByteArray())
    }

    @Test
    fun aNodeIdIsExactly32Bytes() {
        // Act + Assert
        assertFailsWith<IllegalArgumentException> { NodeId(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { NodeId(ByteArray(33)) }
    }
}
