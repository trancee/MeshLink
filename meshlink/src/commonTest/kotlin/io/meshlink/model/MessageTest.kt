package io.meshlink.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageTest {

    @Test
    fun constructionPreservesFields() {
        val sender = byteArrayOf(0x01, 0x02)
        val payload = byteArrayOf(0x0A, 0x0B, 0x0C)
        val msg = Message(sender, payload)
        assertTrue(msg.senderId.contentEquals(sender))
        assertTrue(msg.payload.contentEquals(payload))
    }

    @Test
    fun emptyPayloadIsAllowed() {
        val msg = Message(byteArrayOf(0x01), byteArrayOf())
        assertEquals(0, msg.payload.size)
    }

    @Test
    fun largePayloadIsAllowed() {
        val payload = ByteArray(100_000) { it.toByte() }
        val msg = Message(byteArrayOf(0x01), payload)
        assertEquals(100_000, msg.payload.size)
    }
}
