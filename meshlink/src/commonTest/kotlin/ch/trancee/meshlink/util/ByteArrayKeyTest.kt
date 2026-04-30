package ch.trancee.meshlink.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ByteArrayKeyTest {

    @Test
    fun `equals returns true for same content`() {
        val a = ByteArrayKey(byteArrayOf(1, 2, 3))
        val b = ByteArrayKey(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
    }

    @Test
    fun `equals returns false for different content`() {
        val a = ByteArrayKey(byteArrayOf(1, 2, 3))
        val b = ByteArrayKey(byteArrayOf(4, 5, 6))
        assertNotEquals(a, b)
    }

    @Test
    fun `equals returns true for same instance`() {
        val a = ByteArrayKey(byteArrayOf(1, 2, 3))
        assertTrue(a.equals(a))
    }

    @Test
    fun `equals returns false for non-ByteArrayKey`() {
        val a = ByteArrayKey(byteArrayOf(1, 2, 3))
        assertFalse(a.equals("not a ByteArrayKey"))
    }

    @Test
    fun `equals returns false for null`() {
        val a = ByteArrayKey(byteArrayOf(1, 2, 3))
        assertFalse(a.equals(null))
    }

    @Test
    fun `hashCode is same for equal keys`() {
        val a = ByteArrayKey(byteArrayOf(10, 20, 30))
        val b = ByteArrayKey(byteArrayOf(10, 20, 30))
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `hashCode differs for different content`() {
        val a = ByteArrayKey(byteArrayOf(1, 2, 3))
        val b = ByteArrayKey(byteArrayOf(4, 5, 6))
        // Not guaranteed by contract, but overwhelmingly likely for distinct data.
        assertNotEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `toString shows byte count`() {
        val key = ByteArrayKey(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals("ByteArrayKey(5 bytes)", key.toString())
    }

    @Test
    fun `works correctly as HashMap key`() {
        val map = HashMap<ByteArrayKey, String>()
        val key1 = ByteArrayKey(byteArrayOf(0x0A, 0x0B))
        val key2 = ByteArrayKey(byteArrayOf(0x0A, 0x0B)) // same content, different instance
        map[key1] = "value"
        assertEquals("value", map[key2])
    }

    @Test
    fun `empty byte arrays are equal`() {
        val a = ByteArrayKey(byteArrayOf())
        val b = ByteArrayKey(byteArrayOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
