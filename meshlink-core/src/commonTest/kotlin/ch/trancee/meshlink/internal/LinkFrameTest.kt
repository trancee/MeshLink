package ch.trancee.meshlink.internal

import ch.trancee.meshlink.vectors.linkFramesVectors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LinkFrameTest {
    private val vectors = linkFramesVectors.filter { it.tcId in TC_IDS }

    @Test
    fun decodesTheHelloAndCloseVectors() {
        assertEquals(TC_IDS, vectors.map { it.tcId })
        vectors.forEach { vector ->
            val result = LinkFrame.decode(vector.encoded.hex())
            val decoded = vector.decoded
            when {
                decoded == null -> {
                    assertIs<Decoded.Invalid>(result, "tcId ${vector.tcId}")
                    assertEquals(vector.error, result.error.code, "tcId ${vector.tcId}")
                }

                decoded.getValue("type") == "0x03" -> {
                    val hello = assertIs<LinkFrame.PlainHello>((result as Decoded.Valid).value)
                    assertEquals(decoded.getValue("version").toInt(), hello.version)
                    assertEquals(decoded.getValue("profile").toInt(), WireProfile.UNSECURED.code)
                    assertEquals(decoded.getValue("mesh_id_digest"), hello.meshIdDigest.hex())
                    assertEquals(decoded.getValue("node_id"), hello.nodeId.hex())
                }

                else -> {
                    val close = assertIs<LinkFrame.Close>((result as Decoded.Valid).value)
                    assertEquals(decoded.getValue("reason").toInt(), close.code, "tcId ${vector.tcId}")
                }
            }
        }
    }

    @Test
    fun encodesEveryValidVector() {
        vectors.filter { it.result == "valid" }.forEach { vector ->
            val frame = (LinkFrame.decode(vector.encoded.hex()) as Decoded.Valid).value

            assertEquals(vector.encoded, frame.encode().hex(), "tcId ${vector.tcId}")
        }
    }

    @Test
    fun anUnknownCloseReasonMeansNormal() {
        assertEquals(CloseReason.DUPLICATE_LINK, LinkFrame.Close(1).reason)
        assertEquals(CloseReason.NORMAL, LinkFrame.Close(UNKNOWN_REASON).reason)
        assertEquals(CloseReason.NORMAL, CloseReason.of(-1))
        CloseReason.entries.forEach { assertEquals(it, LinkFrame.Close(it).reason) }
    }

    @Test
    fun typesThisDecoderDoesNotHandleAreUnknownAndAnEmptyFrameIsTruncated() {
        listOf("0100", "0200", "40", "80", "bf").forEach {
            assertEquals(DecodeError.UNKNOWN_TYPE, (LinkFrame.decode(it.hex()) as Decoded.Invalid).error, it)
        }
        assertEquals(DecodeError.TRUNCATED, (LinkFrame.decode(ByteArray(0)) as Decoded.Invalid).error)
    }

    @Test
    fun helloRejectsVersionZeroAndWrongFieldSizes() {
        val hello = LinkFrame.PlainHello(1, ByteArray(DIGEST), ByteArray(DIGEST)).encode()
        hello[1] = 0

        assertEquals(DecodeError.OUT_OF_RANGE, (LinkFrame.decode(hello) as Decoded.Invalid).error)
        assertFailsWith<IllegalArgumentException> { LinkFrame.PlainHello(0, ByteArray(DIGEST), ByteArray(DIGEST)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.PlainHello(256, ByteArray(DIGEST), ByteArray(DIGEST)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.PlainHello(1, ByteArray(DIGEST - 1), ByteArray(DIGEST)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.PlainHello(1, ByteArray(DIGEST), ByteArray(DIGEST + 1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Close(-1) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Close(256) }
    }

    @Test
    fun decoderSurvivesEveryPrefixOverlongInputsAndSeededMutations() {
        vectors.filter { it.result == "valid" }.forEach { vector ->
            mutationInputs(vector.encoded.hex()).forEach { input ->
                when (val result = LinkFrame.decode(input)) {
                    is Decoded.Valid -> assertContentEquals(input, result.value.encode())
                    is Decoded.Invalid -> Unit
                }
            }
        }
    }

    private companion object {
        val TC_IDS = listOf(5, 6, 7, 21, 22, 23, 24, 25, 26, 54, 55, 56, 57, 64)
        const val UNKNOWN_REASON = 0x7F
        const val DIGEST = 32
    }
}
