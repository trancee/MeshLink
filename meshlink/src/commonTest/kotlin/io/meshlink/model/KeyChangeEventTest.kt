package io.meshlink.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeyChangeEventTest {

    private val peerId = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val oldKey = ByteArray(32) { 0xAA.toByte() }
    private val newKey = ByteArray(32) { 0xBB.toByte() }

    @Test
    fun constructionPreservesFields() {
        val event = KeyChangeEvent(peerId, oldKey, newKey)
        assertTrue(event.peerId.contentEquals(peerId))
        assertTrue(event.previousKey.contentEquals(oldKey))
        assertTrue(event.newKey.contentEquals(newKey))
    }

    @Test
    fun equalsUsesContentEquality() {
        val a = KeyChangeEvent(peerId.copyOf(), oldKey.copyOf(), newKey.copyOf())
        val b = KeyChangeEvent(peerId.copyOf(), oldKey.copyOf(), newKey.copyOf())
        assertEquals(a, b)
    }

    @Test
    fun equalsDetectsDifferences() {
        val base = KeyChangeEvent(peerId, oldKey, newKey)
        val differentPeer = KeyChangeEvent(byteArrayOf(0xFF.toByte()), oldKey, newKey)
        val differentOldKey = KeyChangeEvent(peerId, ByteArray(32) { 0xCC.toByte() }, newKey)
        val differentNewKey = KeyChangeEvent(peerId, oldKey, ByteArray(32) { 0xDD.toByte() })
        assertNotEquals(base, differentPeer)
        assertNotEquals(base, differentOldKey)
        assertNotEquals(base, differentNewKey)
    }

    @Test
    fun hashCodeConsistentWithEquals() {
        val a = KeyChangeEvent(peerId.copyOf(), oldKey.copyOf(), newKey.copyOf())
        val b = KeyChangeEvent(peerId.copyOf(), oldKey.copyOf(), newKey.copyOf())
        assertEquals(a.hashCode(), b.hashCode())
    }
}
