package io.meshlink.transfer

import io.meshlink.util.ByteArrayKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

class CutThroughBufferTest {

    private val nextHop = byteArrayOf(0x01, 0x02, 0x03)

    private fun key(s: String) = ByteArrayKey(s.encodeToByteArray())

    @Test
    fun startSessionAndBufferFirstChunkReturnsDataForForwarding() {
        val buffer = CutThroughBuffer()
        assertTrue(buffer.startSession(key("msg1"), 4, nextHop))

        val data = byteArrayOf(10, 20, 30)
        val result = buffer.bufferChunk(key("msg1"), 0, data)

        assertNotNull(result)
        assertContentEquals(data, result)
        assertEquals(1, buffer.sessionCount())
    }

    @Test
    fun duplicateChunkReturnsNull() {
        val buffer = CutThroughBuffer()
        buffer.startSession(key("msg1"), 4, nextHop)

        val data = byteArrayOf(10, 20, 30)
        assertNotNull(buffer.bufferChunk(key("msg1"), 0, data))
        assertNull(buffer.bufferChunk(key("msg1"), 0, data))
    }

    @Test
    fun allChunksBufferedMarksComplete() {
        val buffer = CutThroughBuffer()
        buffer.startSession(key("msg1"), 3, nextHop)

        buffer.bufferChunk(key("msg1"), 0, byteArrayOf(1))
        buffer.bufferChunk(key("msg1"), 1, byteArrayOf(2))
        assertFalse(buffer.isComplete(key("msg1")))

        buffer.bufferChunk(key("msg1"), 2, byteArrayOf(3))
        assertTrue(buffer.isComplete(key("msg1")))
    }

    @Test
    fun getChunkForRetransmit() {
        val buffer = CutThroughBuffer()
        buffer.startSession(key("msg1"), 4, nextHop)

        val data = byteArrayOf(42, 43, 44)
        buffer.bufferChunk(key("msg1"), 2, data)

        val retrieved = buffer.getChunk(key("msg1"), 2)
        assertNotNull(retrieved)
        assertContentEquals(data, retrieved)
    }

    @Test
    fun ackChunkFreesBufferedData() {
        val buffer = CutThroughBuffer()
        buffer.startSession(key("msg1"), 2, nextHop)

        val data = byteArrayOf(1, 2, 3, 4, 5)
        buffer.bufferChunk(key("msg1"), 0, data)
        assertEquals(5, buffer.totalBufferedBytes())

        buffer.ackChunk(key("msg1"), 0)
        assertNull(buffer.getChunk(key("msg1"), 0))
        assertEquals(0, buffer.totalBufferedBytes())
    }

    @Test
    fun removeCompletedSessionFreesMemory() {
        val buffer = CutThroughBuffer()
        buffer.startSession(key("msg1"), 2, nextHop)
        buffer.bufferChunk(key("msg1"), 0, byteArrayOf(1, 2, 3))
        buffer.bufferChunk(key("msg1"), 1, byteArrayOf(4, 5, 6))
        assertEquals(6, buffer.totalBufferedBytes())
        assertEquals(1, buffer.sessionCount())

        buffer.removeSession(key("msg1"))
        assertEquals(0, buffer.totalBufferedBytes())
        assertEquals(0, buffer.sessionCount())
    }

    @Test
    fun bufferCapacityLimitPreventsNewSession() {
        val buffer = CutThroughBuffer(maxBufferBytes = 10)
        buffer.startSession(key("msg1"), 1, nextHop)
        buffer.bufferChunk(key("msg1"), 0, ByteArray(10))

        assertFalse(buffer.startSession(key("msg2"), 1, nextHop))
        assertEquals(1, buffer.sessionCount())
    }

    @Test
    fun multipleConcurrentRelaySessions() {
        val buffer = CutThroughBuffer()
        buffer.startSession(key("msg1"), 2, nextHop)
        buffer.startSession(key("msg2"), 3, byteArrayOf(0x04, 0x05))

        buffer.bufferChunk(key("msg1"), 0, byteArrayOf(1))
        buffer.bufferChunk(key("msg2"), 0, byteArrayOf(2))
        buffer.bufferChunk(key("msg2"), 1, byteArrayOf(3))

        assertEquals(2, buffer.sessionCount())
        assertFalse(buffer.isComplete(key("msg1")))
        assertFalse(buffer.isComplete(key("msg2")))

        buffer.bufferChunk(key("msg1"), 1, byteArrayOf(4))
        buffer.bufferChunk(key("msg2"), 2, byteArrayOf(5))

        assertTrue(buffer.isComplete(key("msg1")))
        assertTrue(buffer.isComplete(key("msg2")))
    }

    @Test
    fun totalBufferedBytesTracksCorrectly() {
        val buffer = CutThroughBuffer()
        assertEquals(0, buffer.totalBufferedBytes())

        buffer.startSession(key("msg1"), 3, nextHop)
        buffer.bufferChunk(key("msg1"), 0, ByteArray(100))
        assertEquals(100, buffer.totalBufferedBytes())

        buffer.bufferChunk(key("msg1"), 1, ByteArray(50))
        assertEquals(150, buffer.totalBufferedBytes())

        buffer.startSession(key("msg2"), 1, nextHop)
        buffer.bufferChunk(key("msg2"), 0, ByteArray(25))
        assertEquals(175, buffer.totalBufferedBytes())

        buffer.ackChunk(key("msg1"), 0)
        assertEquals(75, buffer.totalBufferedBytes())
    }

    @Test
    fun clearRemovesAllSessions() {
        val buffer = CutThroughBuffer()
        buffer.startSession(key("msg1"), 2, nextHop)
        buffer.startSession(key("msg2"), 2, nextHop)
        buffer.bufferChunk(key("msg1"), 0, ByteArray(10))
        buffer.bufferChunk(key("msg2"), 0, ByteArray(20))

        buffer.clear()

        assertEquals(0, buffer.sessionCount())
        assertEquals(0, buffer.totalBufferedBytes())
        assertNull(buffer.getChunk(key("msg1"), 0))
    }

    @Test
    fun sessionCountAccuracy() {
        val buffer = CutThroughBuffer()
        assertEquals(0, buffer.sessionCount())

        buffer.startSession(key("a"), 1, nextHop)
        assertEquals(1, buffer.sessionCount())

        buffer.startSession(key("b"), 1, nextHop)
        buffer.startSession(key("c"), 1, nextHop)
        assertEquals(3, buffer.sessionCount())

        buffer.removeSession(key("b"))
        assertEquals(2, buffer.sessionCount())

        buffer.removeSession(key("nonexistent"))
        assertEquals(2, buffer.sessionCount())
    }

    @Test
    fun getChunkForNonExistentSessionReturnsNull() {
        val buffer = CutThroughBuffer()
        assertNull(buffer.getChunk(key("no-such-session"), 0))
    }

    @Test
    fun outOfOrderChunksHandledCorrectly() {
        val buffer = CutThroughBuffer()
        buffer.startSession(key("msg1"), 4, nextHop)

        // Buffer chunks out of order: 3, 1, 0, 2
        assertNotNull(buffer.bufferChunk(key("msg1"), 3, byteArrayOf(40)))
        assertNotNull(buffer.bufferChunk(key("msg1"), 1, byteArrayOf(20)))
        assertNotNull(buffer.bufferChunk(key("msg1"), 0, byteArrayOf(10)))
        assertFalse(buffer.isComplete(key("msg1")))

        assertNotNull(buffer.bufferChunk(key("msg1"), 2, byteArrayOf(30)))
        assertTrue(buffer.isComplete(key("msg1")))

        // All chunks retrievable regardless of insertion order
        assertContentEquals(byteArrayOf(10), buffer.getChunk(key("msg1"), 0))
        assertContentEquals(byteArrayOf(20), buffer.getChunk(key("msg1"), 1))
        assertContentEquals(byteArrayOf(30), buffer.getChunk(key("msg1"), 2))
        assertContentEquals(byteArrayOf(40), buffer.getChunk(key("msg1"), 3))
    }

    @Test
    fun isOverCapacityDetection() {
        val buffer = CutThroughBuffer(maxBufferBytes = 20)
        assertFalse(buffer.isOverCapacity())

        buffer.startSession(key("msg1"), 2, nextHop)
        buffer.bufferChunk(key("msg1"), 0, ByteArray(15))
        assertFalse(buffer.isOverCapacity())

        buffer.bufferChunk(key("msg1"), 1, ByteArray(5))
        assertTrue(buffer.isOverCapacity())

        // Freeing via ack brings it back under
        buffer.ackChunk(key("msg1"), 1)
        assertFalse(buffer.isOverCapacity())
    }
}
