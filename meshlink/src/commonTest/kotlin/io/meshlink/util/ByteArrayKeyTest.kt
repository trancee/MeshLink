package io.meshlink.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ByteArrayKeyTest {

    @Test
    fun equalContentProducesEqualKeys() {
        val a = ByteArrayKey(byteArrayOf(1, 2, 3))
        val b = ByteArrayKey(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun differentContentProducesDifferentKeys() {
        val a = ByteArrayKey(byteArrayOf(1, 2, 3))
        val b = ByteArrayKey(byteArrayOf(1, 2, 4))
        assertNotEquals(a, b)
    }

    @Test
    fun emptyArraysAreEqual() {
        assertEquals(ByteArrayKey(byteArrayOf()), ByteArrayKey(byteArrayOf()))
    }

    @Test
    fun worksAsMapKey() {
        val map = mutableMapOf<ByteArrayKey, String>()
        val key1 = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val key2 = byteArrayOf(0xAA.toByte(), 0xBB.toByte()) // same content, different instance

        map[ByteArrayKey(key1)] = "hello"
        assertEquals("hello", map[ByteArrayKey(key2)])
    }

    @Test
    fun worksInSet() {
        val set = mutableSetOf<ByteArrayKey>()
        set.add(byteArrayOf(1, 2).toKey())
        assertFalse(set.add(byteArrayOf(1, 2).toKey()), "Duplicate should not be added")
        assertTrue(set.add(byteArrayOf(3, 4).toKey()), "New key should be added")
        assertEquals(2, set.size)
    }

    @Test
    fun toStringReturnsHex() {
        val key = ByteArrayKey(byteArrayOf(0xDE.toByte(), 0xAD.toByte()))
        assertEquals("dead", key.toString())
    }

    @Test
    fun toKeyExtensionRoundTrips() {
        val original = byteArrayOf(0x01, 0x02, 0x03)
        val key = original.toKey()
        assertTrue(original.contentEquals(key.bytes))
    }
}
