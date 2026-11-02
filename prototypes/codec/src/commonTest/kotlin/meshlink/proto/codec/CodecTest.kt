package meshlink.proto.codec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodecTest {
    private val cc = CodecC()

    @Test
    fun bitPackedCodecsProduceIdenticalBytes() {
        for (s in samples) {
            assertContentEquals(CodecB.encodeToArray(s.frame), cc.encodeToArray(s.frame), s.name)
        }
    }

    @Test
    fun everyCodecRoundTripsEverySample() {
        for (s in samples) {
            for (codec in listOf(CodecA, CodecB, cc)) {
                val bytes = codec.encodeToArray(s.frame)
                val decoded = codec.decodeAny(s, bytes, bytes.size)
                assertIs<Frame>(decoded, "${codec.name} ${s.name}")
                val expected = if (codec === CodecA && s.frame is Advert) {
                    s.frame.describe().replace(Regex(",r=\\d+,"), ",r=0,")
                } else {
                    s.frame.describe()
                }
                assertEquals(expected, decoded.describe(), "${codec.name} ${s.name}")
            }
        }
    }

    @Test
    fun truncatedPrefixesAreTypedErrorsExceptImplicitLengthFragments() {
        for (s in samples) {
            for (codec in listOf(CodecA, CodecB, cc)) {
                val bytes = codec.encodeToArray(s.frame)
                for (k in 0 until bytes.size) {
                    val r = codec.decodeAny(s, bytes.copyOf(k), k)
                    if (s.frame is Fragment && r is Frame) continue
                    assertIs<DecodeError>(r, "${codec.name} ${s.name} prefix $k")
                }
            }
        }
    }

    @Test
    fun malformedHeadersAreRejectedAlikeByBAndC() {
        val unicast = CodecB.encodeToArray(samples.first { it.name == "unicast" }.frame)
        val cases = mapOf(
            DecodeError.UNSUPPORTED_VERSION to unicast.copyOf().also { it[0] = (it[0].toInt() xor 2).toByte() },
            DecodeError.UNKNOWN_TYPE to unicast.copyOf().also { it[0] = (1 or (31 shl 3)).toByte() },
            DecodeError.OUT_OF_RANGE to unicast.copyOf().also { it[2] = 0; it[3] = 0 },
            DecodeError.RESERVED_BITS_SET to unicast.copyOf().also { it[5] = (it[5].toInt() or 0x80).toByte() },
            DecodeError.TRAILING_BYTES to unicast + byteArrayOf(0),
            DecodeError.TRUNCATED to ByteArray(0),
        )
        for ((expected, input) in cases) {
            assertEquals(expected, CodecB.decode(input, input.size), "B $expected")
            assertEquals(expected, cc.decode(input, input.size), "C $expected")
        }
    }

    @Test
    fun shortDifferentialFuzzFindsNoProblems() {
        for (s in fuzzSamples) {
            val r = fuzzBC(s, 2_000, 0x4D455348)
            assertTrue(r.exceptions == 0L && r.disagreements == 0L && r.nonCanonical == 0L, r.toString())
        }
    }
}
