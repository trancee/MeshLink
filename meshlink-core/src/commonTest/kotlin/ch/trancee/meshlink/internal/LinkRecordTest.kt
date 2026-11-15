package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LinkRecordTest {
    @Test
    fun encodeRejectsBodiesOutsideTheRecordLimits() {
        assertFailsWith<IllegalArgumentException> { LinkRecord.encode(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { LinkRecord.encode(ByteArray(LinkRecord.BODY_MAX + 1)) }
    }

    @Test
    fun readerYieldsTheSameRecordsForEveryWriteSize() {
        val bodies = listOf(1, BODY, LinkRecord.BODY_MAX).map { size -> ByteArray(size) { it.toByte() } }
        val stream = bodies.map(LinkRecord::encode).reduce(ByteArray::plus)

        (1..stream.size step CHUNK_STEP).forEach { size ->
            val reader = RecordReader()
            val read =
                stream.toList().chunked(size).flatMap { chunk ->
                    (reader.read(chunk.toByteArray()) as Decoded.Valid).value
                }

            assertEquals(bodies.map { it.hex() }, read.map { it.hex() }, "chunk size $size")
        }
    }

    @Test
    fun readerFailsForGoodOnALengthOutsideTheLimits() {
        listOf("0000", "0401").forEach { header ->
            val reader = RecordReader()

            val first = reader.read(header.hex())
            val next = reader.read(LinkRecord.encode(byteArrayOf(1)))

            assertEquals(DecodeError.OUT_OF_RANGE, assertIs<Decoded.Invalid>(first).error)
            assertEquals(DecodeError.OUT_OF_RANGE, assertIs<Decoded.Invalid>(next).error)
        }
    }

    private companion object {
        const val CHUNK_STEP = 7
        const val BODY = 16
    }
}
