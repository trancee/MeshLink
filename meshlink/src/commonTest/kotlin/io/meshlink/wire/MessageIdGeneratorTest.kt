package io.meshlink.wire

import io.meshlink.model.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MessageIdGeneratorTest {

    @Test
    fun generatesCorrectSize() {
        val gen = MessageIdGenerator()
        val id = gen.generate()
        assertEquals(MessageId.SIZE, id.bytes.size, "MessageId should be ${MessageId.SIZE} bytes")
        assertEquals(MessageId.SIZE * 2, id.hex.length, "Hex should be ${MessageId.SIZE * 2} chars")
    }

    @Test
    fun generatesUniqueIds() {
        val gen = MessageIdGenerator()
        val ids = (1..100).map { gen.generate() }.toSet()
        assertEquals(100, ids.size, "100 generated IDs should all be unique")
    }

    @Test
    fun multipleGeneratorsProduceUniqueIds() {
        val gen1 = MessageIdGenerator()
        val gen2 = MessageIdGenerator()
        val id1 = gen1.generate()
        val id2 = gen2.generate()
        assertNotEquals(id1, id2, "Different generators should produce different IDs")
    }
}
