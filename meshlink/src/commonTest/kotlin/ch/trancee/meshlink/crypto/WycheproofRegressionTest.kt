package ch.trancee.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WycheproofRegressionTest {
    @Test
    fun `chacha20 poly1305 matches wycheproof regression cases`() {
        // Arrange
        val support = loadSupport(fileName = "chacha20_poly1305.jsonl") ?: return
        val provider = support.provider
        val vectors = support.lines.map(::parseAeadVector)

        // Act / Assert
        vectors.forEach { vector ->
            val ciphertext =
                vector.ciphertext.copyOfRange(0, vector.ciphertext.size - AEAD_TAG_SIZE_BYTES)
            val tag =
                vector.ciphertext.copyOfRange(
                    vector.ciphertext.size - AEAD_TAG_SIZE_BYTES,
                    vector.ciphertext.size,
                )
            when (vector.result) {
                "valid" -> {
                    val sealed =
                        provider.chacha20Poly1305Seal(
                            key = vector.key,
                            nonce = vector.iv,
                            aad = vector.aad,
                            plaintext = vector.message,
                        )
                    val opened =
                        provider.chacha20Poly1305Open(
                            key = vector.key,
                            nonce = vector.iv,
                            aad = vector.aad,
                            ciphertext = ciphertext + tag,
                        )

                    assertContentEquals(vector.ciphertext, sealed)
                    assertContentEquals(vector.message, opened)
                }

                "invalid" -> {
                    assertFails {
                        provider.chacha20Poly1305Open(
                            key = vector.key,
                            nonce = vector.iv,
                            aad = vector.aad,
                            ciphertext = ciphertext + tag,
                        )
                    }
                }

                else -> error("Unsupported AEAD result ${vector.result}")
            }
        }
    }

    @Test
    fun `ed25519 verification matches wycheproof regression cases`() {
        // Arrange
        val support = loadSupport(fileName = "ed25519.jsonl") ?: return
        val provider = support.provider
        val vectors = support.lines.map(::parseEd25519Vector)

        // Act / Assert
        vectors.forEach { vector ->
            val isValid =
                provider.ed25519Verify(
                    publicKey = vector.publicKey,
                    message = vector.message,
                    signature = vector.signature,
                )

            when (vector.result) {
                "valid" -> assertTrue(isValid, "Expected tcId=${vector.tcId} to verify")
                "invalid" -> assertFalse(isValid, "Expected tcId=${vector.tcId} to fail")
                else -> error("Unsupported Ed25519 result ${vector.result}")
            }
        }
    }

    @Test
    fun `x25519 shared secrets match wycheproof regression cases`() {
        // Arrange
        val support = loadSupport(fileName = "x25519.jsonl") ?: return
        val provider = support.provider
        val vectors = support.lines.map(::parseX25519Vector)

        // Act / Assert
        vectors.forEach { vector ->
            val actualSharedSecret =
                provider.x25519(privateKey = vector.privateKey, publicKey = vector.publicKey)

            assertContentEquals(
                expected = vector.sharedSecret,
                actual = actualSharedSecret,
                message = "Expected tcId=${vector.tcId} to derive the documented shared secret",
            )
        }
    }

    @Test
    fun `hkdf sha256 matches wycheproof regression cases`() {
        // Arrange
        val support = loadSupport(fileName = "hkdf_sha256.jsonl") ?: return
        val provider = support.provider
        val vectors = support.lines.map(::parseHkdfVector)

        // Act / Assert
        vectors.forEach { vector ->
            when (vector.result) {
                "valid" -> {
                    val actualOkm =
                        hkdfSha256(
                            provider = provider,
                            ikm = vector.ikm,
                            salt = vector.salt,
                            info = vector.info,
                            size = vector.size,
                        )

                    assertContentEquals(
                        expected = vector.okm,
                        actual = actualOkm,
                        message = "Expected tcId=${vector.tcId} to derive the Wycheproof output",
                    )
                }

                "invalid" -> {
                    assertFailsWith<IllegalArgumentException> {
                        hkdfSha256(
                            provider = provider,
                            ikm = vector.ikm,
                            salt = vector.salt,
                            info = vector.info,
                            size = vector.size,
                        )
                    }
                }

                else -> error("Unsupported HKDF result ${vector.result}")
            }
        }
    }

    @Test
    fun `hmac sha256 matches wycheproof regression cases`() {
        // Arrange
        val support = loadSupport(fileName = "hmac_sha256.jsonl") ?: return
        val provider = support.provider
        val vectors = support.lines.map(::parseHmacVector)

        // Act / Assert
        vectors.forEach { vector ->
            val actualTag = provider.hmacSha256(key = vector.key, data = vector.message)

            when (vector.result) {
                "valid" -> {
                    assertContentEquals(
                        expected = vector.tag,
                        actual = actualTag,
                        message = "Expected tcId=${vector.tcId} to match the Wycheproof tag",
                    )
                }

                "invalid" -> {
                    assertFalse(
                        actual = actualTag.contentEquals(vector.tag),
                        message = "Expected tcId=${vector.tcId} to reject the modified tag",
                    )
                }

                else -> error("Unsupported HMAC result ${vector.result}")
            }
        }
    }

    private fun loadSupport(fileName: String): WycheproofTestSupport? {
        val provider = wycheproofProviderOrNull() ?: return null
        val lines =
            wycheproofResourceLinesOrNull(fileName)?.filter { line ->
                line.isNotBlank() && !line.trimStart().startsWith("#")
            } ?: return null
        return WycheproofTestSupport(provider = provider, lines = lines)
    }

    private fun parseAeadVector(line: String): AeadVector {
        val fields = parseFlatJsonLine(line)
        return AeadVector(
            tcId = fields.intValue("tcId"),
            result = fields.stringValue("result"),
            key = fields.hexValue("key"),
            iv = fields.hexValue("iv"),
            aad = fields.hexValue("aad"),
            message = fields.hexValue("msg"),
            ciphertext = fields.hexValue("ct") + fields.hexValue("tag"),
        )
    }

    private fun parseEd25519Vector(line: String): Ed25519Vector {
        val fields = parseFlatJsonLine(line)
        return Ed25519Vector(
            tcId = fields.intValue("tcId"),
            result = fields.stringValue("result"),
            publicKey = fields.hexValue("publicKey"),
            message = fields.hexValue("msg"),
            signature = fields.hexValue("sig"),
        )
    }

    private fun parseX25519Vector(line: String): X25519Vector {
        val fields = parseFlatJsonLine(line)
        return X25519Vector(
            tcId = fields.intValue("tcId"),
            privateKey = fields.hexValue("private"),
            publicKey = fields.hexValue("public"),
            sharedSecret = fields.hexValue("shared"),
        )
    }

    private fun parseHkdfVector(line: String): HkdfVector {
        val fields = parseFlatJsonLine(line)
        return HkdfVector(
            tcId = fields.intValue("tcId"),
            result = fields.stringValue("result"),
            ikm = fields.hexValue("ikm"),
            salt = fields.hexValue("salt"),
            info = fields.hexValue("info"),
            okm = fields.hexValue("okm"),
            size = fields.intValue("size"),
        )
    }

    private fun parseHmacVector(line: String): HmacVector {
        val fields = parseFlatJsonLine(line)
        return HmacVector(
            tcId = fields.intValue("tcId"),
            result = fields.stringValue("result"),
            key = fields.hexValue("key"),
            message = fields.hexValue("msg"),
            tag = fields.hexValue("tag"),
        )
    }

    private fun hkdfSha256(
        provider: CryptoProvider,
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        size: Int,
    ): ByteArray {
        require(size <= HKDF_MAX_OUTPUT_BYTES) {
            "HKDF output size must be at most $HKDF_MAX_OUTPUT_BYTES bytes"
        }
        val actualSalt = if (salt.isEmpty()) ByteArray(HKDF_HASH_SIZE_BYTES) else salt
        val prk = provider.hmacSha256(key = actualSalt, data = ikm)
        val blocks = if (size == 0) 0 else ((size - 1) / HKDF_HASH_SIZE_BYTES) + 1
        var previous = ByteArray(0)
        val output = ByteArray(size)
        var offset = 0
        for (counter in 1..blocks) {
            previous =
                provider.hmacSha256(
                    key = prk,
                    data = previous + info + byteArrayOf(counter.toByte()),
                )
            val copySize = minOf(previous.size, size - offset)
            previous.copyInto(output, destinationOffset = offset, endIndex = copySize)
            offset += copySize
        }
        return output
    }

    private fun parseFlatJsonLine(line: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        JSON_FIELD_REGEX.findAll(line).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groups[2]?.value ?: match.groupValues[3]
            values[key] = value
        }
        return values
    }

    private fun Map<String, String>.hexValue(key: String): ByteArray {
        val value = stringValue(key)
        if (value.isEmpty()) {
            return ByteArray(0)
        }
        return value
            .chunked(2) { chunk -> chunk.toString().toInt(radix = 16).toByte() }
            .toByteArray()
    }

    private fun Map<String, String>.intValue(key: String): Int = stringValue(key).toInt()

    private fun Map<String, String>.stringValue(key: String): String {
        return get(key) ?: error("Missing field '$key' in Wycheproof regression data")
    }

    private companion object {
        private const val AEAD_TAG_SIZE_BYTES: Int = 16
        private const val HKDF_HASH_SIZE_BYTES: Int = 32
        private const val HKDF_MAX_OUTPUT_BYTES: Int = 255 * HKDF_HASH_SIZE_BYTES
        private val JSON_FIELD_REGEX = Regex("\\\"([^\\\"]+)\\\":(?:\\\"([^\\\"]*)\\\"|(\\d+))")
    }
}

internal expect fun wycheproofProviderOrNull(): CryptoProvider?

internal expect fun wycheproofResourceLinesOrNull(fileName: String): List<String>?

private class WycheproofTestSupport
internal constructor(internal val provider: CryptoProvider, internal val lines: List<String>)

private class AeadVector
internal constructor(
    internal val tcId: Int,
    internal val result: String,
    internal val key: ByteArray,
    internal val iv: ByteArray,
    internal val aad: ByteArray,
    internal val message: ByteArray,
    ciphertext: ByteArray,
) {
    internal val ciphertext: ByteArray = ciphertext.copyOf()
}

private class Ed25519Vector
internal constructor(
    internal val tcId: Int,
    internal val result: String,
    publicKey: ByteArray,
    message: ByteArray,
    signature: ByteArray,
) {
    internal val publicKey: ByteArray = publicKey.copyOf()
    internal val message: ByteArray = message.copyOf()
    internal val signature: ByteArray = signature.copyOf()
}

private class X25519Vector
internal constructor(
    internal val tcId: Int,
    privateKey: ByteArray,
    publicKey: ByteArray,
    sharedSecret: ByteArray,
) {
    internal val privateKey: ByteArray = privateKey.copyOf()
    internal val publicKey: ByteArray = publicKey.copyOf()
    internal val sharedSecret: ByteArray = sharedSecret.copyOf()
}

private class HkdfVector
internal constructor(
    internal val tcId: Int,
    internal val result: String,
    ikm: ByteArray,
    salt: ByteArray,
    info: ByteArray,
    okm: ByteArray,
    internal val size: Int,
) {
    internal val ikm: ByteArray = ikm.copyOf()
    internal val salt: ByteArray = salt.copyOf()
    internal val info: ByteArray = info.copyOf()
    internal val okm: ByteArray = okm.copyOf()
}

private class HmacVector
internal constructor(
    internal val tcId: Int,
    internal val result: String,
    key: ByteArray,
    message: ByteArray,
    tag: ByteArray,
) {
    internal val key: ByteArray = key.copyOf()
    internal val message: ByteArray = message.copyOf()
    internal val tag: ByteArray = tag.copyOf()
}
