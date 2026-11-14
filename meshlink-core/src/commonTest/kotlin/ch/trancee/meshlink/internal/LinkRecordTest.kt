package ch.trancee.meshlink.internal

import ch.trancee.meshlink.vectors.recordsVectors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LinkRecordTest {
    @Test
    fun decodesEveryVector() {
        assertEquals((1..8).toList(), recordsVectors.map { it.tcId })
        recordsVectors.forEach { vector ->
            val result = LinkRecord.decode(vector.encoded.hex())
            val decoded = vector.decoded
            if (decoded == null) {
                assertIs<Decoded.Invalid>(result, "tcId ${vector.tcId}")
                assertEquals(vector.error, result.error.code, "tcId ${vector.tcId}")
            } else {
                val body = assertIs<Decoded.Valid<ByteArray>>(result, "tcId ${vector.tcId}").value
                assertEquals(decoded.getValue("body"), body.hex(), "tcId ${vector.tcId}")
            }
        }
    }

    @Test
    fun encodesEveryValidVector() {
        recordsVectors.filter { it.result == "valid" }.forEach { vector ->
            assertEquals(vector.encoded, LinkRecord.encode(vector.decoded!!.getValue("body").hex()).hex())
        }
    }

    @Test
    fun encodeRejectsBodiesOutsideTheRecordLimits() {
        assertFailsWith<IllegalArgumentException> { LinkRecord.encode(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { LinkRecord.encode(ByteArray(LinkRecord.BODY_MAX + 1)) }
    }

    @Test
    fun readerYieldsTheSameRecordsForEveryWriteSize() {
        val bodies = recordsVectors.filter { it.result == "valid" }.map { it.decoded!!.getValue("body").hex() }
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

    @Test
    fun decoderAndReaderSurviveEveryPrefixOverlongInputsAndSeededMutations() {
        val inputs = mutationInputs(recordsVectors[1].encoded.hex())

        inputs.forEach { input ->
            when (val result = LinkRecord.decode(input)) {
                is Decoded.Valid -> assertContentEquals(input, LinkRecord.encode(result.value))
                is Decoded.Invalid -> Unit
            }
            val reader = RecordReader()
            input.toList().chunked(READER_CHUNK).forEach { reader.read(it.toByteArray()) }
        }
    }

    private companion object {
        const val CHUNK_STEP = 7
        const val READER_CHUNK = 5
    }
}
