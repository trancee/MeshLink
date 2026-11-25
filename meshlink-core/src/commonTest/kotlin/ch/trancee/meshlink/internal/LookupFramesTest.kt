package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LookupFramesTest {
    @Test
    fun aLookupRequestDecodesItsFieldsAndReEncodes() {
        // Arrange
        val bytes = REQUEST.hex()

        // Act
        val request = (LookupRequest.decode(bytes) as Decoded.Valid).value

        // Assert
        assertEquals(HOP_LIMIT_REQUEST, request.header.hopLimit)
        assertContentEquals("57fac0f41ce4572c65194c2e".hex(), request.header.messageId)
        assertEquals(EXPIRY, request.header.expiry)
        assertContentEquals(SALT.hex(), request.salt)
        assertContentEquals("29d269005a5cf1ce".hex(), request.lookupTag)
        val key = REQUEST.hex().copyOfRange(LookupRequest.LENGTH - KEY, LookupRequest.LENGTH)
        assertContentEquals(key, request.ephemeralKey)
        assertContentEquals(bytes, request.encode())
    }

    @Test
    fun aLookupReplyDecodesItsFieldsAndReEncodes() {
        val bytes = REPLY.hex()

        val reply = (LookupReply.decode(bytes) as Decoded.Valid).value

        assertEquals(HOP_LIMIT_REPLY, reply.header.hopLimit)
        assertContentEquals(SALT.hex(), reply.header.destinationTag)
        assertContentEquals("05514919d593747f".hex(), reply.header.sourceTag)
        assertContentEquals(bytes.copyOfRange(KEY_AT, KEY_AT + KEY), reply.ephemeralKey)
        assertContentEquals(bytes.copyOfRange(SEALED_AT, bytes.size), reply.sealedCertificate)
        assertEquals(LookupReply.CLASSICAL_LENGTH, bytes.size)
        assertContentEquals(bytes, reply.encode())
    }

    @Test
    fun aPostQuantumLookupReplyCarriesTheLongerSeal() {
        val valid = REPLY.hex()
        val sealed = ByteArray(LookupReply.POST_QUANTUM_LENGTH - SEALED_AT)
        val bytes = valid.copyOf(SEALED_LENGTH_AT) + u16(sealed.size) + sealed

        val reply = (LookupReply.decode(bytes) as Decoded.Valid).value

        assertEquals(sealed.size, reply.sealedCertificate.size)
        assertContentEquals(bytes, reply.encode())
    }

    @Test
    fun aMalformedLookupRequestIsRejected() {
        val valid = REQUEST.hex()
        val cases =
            mapOf(
                ByteArray(0) to DecodeError.TRUNCATED,
                valid.copyOf().also { it[0] = RouteRequest.TYPE.toByte() } to DecodeError.UNKNOWN_TYPE,
                valid.copyOf(LookupRequest.LENGTH - 1) to DecodeError.TRUNCATED,
                valid.copyOf(LookupRequest.LENGTH + 1) to DecodeError.TRAILING_BYTES,
                valid.copyOf().also { it[1] = 0 } to DecodeError.OUT_OF_RANGE,
            )

        val errors = cases.keys.map { (LookupRequest.decode(it) as Decoded.Invalid).error }

        assertEquals(cases.values.toList(), errors)
    }

    @Test
    fun aMalformedLookupReplyIsRejected() {
        val valid = REPLY.hex()
        val cases =
            mapOf(
                ByteArray(0) to DecodeError.TRUNCATED,
                valid.copyOf().also { it[0] = RouteReply.TYPE.toByte() } to DecodeError.UNKNOWN_TYPE,
                valid.copyOf(SEALED_AT - 1) to DecodeError.TRUNCATED,
                valid.copyOf(LookupReply.CLASSICAL_LENGTH - 1) to DecodeError.TRUNCATED,
                valid.copyOf(LookupReply.CLASSICAL_LENGTH + 1) to DecodeError.TRAILING_BYTES,
                valid.copyOf(LookupReply.CLASSICAL_LENGTH - 1).also { it[SEALED_LENGTH_AT + 1]-- } to
                    DecodeError.OUT_OF_RANGE,
                valid.copyOf().also { it[1] = 0 } to DecodeError.OUT_OF_RANGE,
            )

        val errors = cases.keys.map { (LookupReply.decode(it) as Decoded.Invalid).error }

        assertEquals(cases.values.toList(), errors)
    }

    @Test
    fun theConstructorsRejectOutOfRangeFields() {
        val request = (LookupRequest.decode(REQUEST.hex()) as Decoded.Valid).value
        val reply = (LookupReply.decode(REPLY.hex()) as Decoded.Valid).value
        val (salt, tag, key) = Triple(request.salt, request.lookupTag, request.ephemeralKey)

        assertFailsWith<IllegalArgumentException> { LookupRequest(request.header, ByteArray(1), tag, key) }
        assertFailsWith<IllegalArgumentException> { LookupRequest(request.header, salt, ByteArray(1), key) }
        assertFailsWith<IllegalArgumentException> { LookupRequest(request.header, salt, tag, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { LookupReply(reply.header, ByteArray(1), reply.sealedCertificate) }
        assertFailsWith<IllegalArgumentException> { LookupReply(reply.header, key, ByteArray(1)) }
    }

    private companion object {
        // routed-frames.json tcIds 14 and 9.
        const val REQUEST =
            "824057fac0f41ce4572c65194c2e6b36eed8d1a81eaa5b83263e29d269005a5cf1ce" +
                "d6bd89e77c6a308c887853dc64084b1c639f93ab6d9649e4be4bf40f72d842af"
        const val REPLY =
            "44ff008f024f5d07235072ec8b12a46b36eed8d1a81eaa5b83263e05514919d593747f" +
                "0a6e4271427fee04f7cf1799b294197d4c58c432a6dbd06865ddc5fe3608d391009a" +
                "8af5c4292aad2cb6e496cd1e592686a54d98ccdc0a7c6fe94ec103cd6a04922c0fdae18880baf912ddd7542d6039801c" +
                "e70fb973e71c8a131ddd9b70c908da358a1b842a797a1c4dd358e68fef1a83ec474d231e8fd417bbc48e5156b7b9baa9" +
                "a72e925e893097ea0b95613d227c6dd19e34cee6aa790215ff9b9a4d442f63c6ae7ab34d3b5f0922718207d2b2071f8b" +
                "e1cd0c6ac8b20497a72e"
        const val SALT = "d1a81eaa5b83263e"
        const val HOP_LIMIT_REQUEST = 64
        const val HOP_LIMIT_REPLY = 255
        const val EXPIRY = 1_798_762_200u
        const val KEY = 32
        const val KEY_AT = 35
        const val SEALED_LENGTH_AT = 67
        const val SEALED_AT = 69
    }
}
