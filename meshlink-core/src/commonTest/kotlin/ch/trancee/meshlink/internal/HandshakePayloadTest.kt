package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class HandshakePayloadTest {
    @Test
    fun aGatedPayloadCarriesTheResolvingKeyBeforeTheCertificate() {
        // Arrange
        val key = ResolvingKey(ByteArray(ResolvingKey.KEY_LENGTH) { it.toByte() }, NOT_AFTER)

        // Act
        val decoded = assertIs<Decoded.Valid<HandshakePayload>>(HandshakePayload.decode(payload(key).encode())).value

        // Assert
        assertEquals(listOf(RESOLVING_KEY, CERTIFICATE), decoded.tlvs.map { it.type })
        assertContentEquals(key.key, decoded.resolvingKey?.key)
        assertEquals(NOT_AFTER, decoded.resolvingKey?.notAfter)
        assertContentEquals(CERTIFICATE_BYTES, decoded.certificate)
    }

    @Test
    fun aPayloadWithoutAValidResolvingKeyHasNone() {
        val malformed = HandshakePayload(listOf(Tlv(RESOLVING_KEY, ByteArray(ResolvingKey.LENGTH - 1))))

        assertNull(payload(null).resolvingKey)
        assertNull(malformed.resolvingKey)
    }

    @Test
    fun aResolvingKeyDecodesOnlyFromExactly36Bytes() {
        val key = ResolvingKey(ByteArray(ResolvingKey.KEY_LENGTH) { 7 }, NOT_AFTER).encode()

        assertEquals(ResolvingKey.LENGTH, key.size)
        assertContentEquals(key, assertIs<Decoded.Valid<ResolvingKey>>(ResolvingKey.decode(key)).value.encode())
        assertEquals(DecodeError.TRUNCATED, assertIs<Decoded.Invalid>(ResolvingKey.decode(key.copyOf(35))).error)
        assertEquals(DecodeError.TRAILING_BYTES, assertIs<Decoded.Invalid>(ResolvingKey.decode(key + 0)).error)
    }

    private fun payload(key: ResolvingKey?): HandshakePayload = HandshakePayload.of(CERTIFICATE_BYTES, key)

    private companion object {
        const val RESOLVING_KEY = 0x02
        const val CERTIFICATE = 0x81
        const val NOT_AFTER = 1_700_086_400u
        val CERTIFICATE_BYTES = ByteArray(10) { 0x55 }
    }
}
