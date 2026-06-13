package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Ed25519KeyPair
import ch.trancee.meshlink.crypto.X25519KeyPair
import ch.trancee.meshlink.crypto.requireValidX25519SharedSecret
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure in-repo Android fallback provider for devices that do not expose X25519/XDH or
 * ChaCha20-Poly1305 through the platform provider.
 *
 * Ed25519 continues to use the existing in-repo fallback. X25519 follows RFC 7748, and
 * ChaCha20-Poly1305 follows RFC 8439.
 */
internal class AndroidFallbackCryptoProvider : CryptoProvider {
    private val secureRandom: SecureRandom = SecureRandom()
    private val ed25519Fallback =
        Ed25519Fallback(
            randomBytesProvider = { size -> ByteArray(size).also(secureRandom::nextBytes) }
        )
    private val sha256Digests = threadLocal { MessageDigest.getInstance("SHA-256") }
    private val hmacSha256Macs = threadLocal { Mac.getInstance("HmacSHA256") }

    override fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    override fun sha256(input: ByteArray): ByteArray {
        val digest = sha256Digests.value()
        digest.reset()
        return digest.digest(input)
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = hmacSha256Macs.value()
        mac.reset()
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun generateX25519KeyPair(): X25519KeyPair {
        val privateKey = randomBytes(X25519_KEY_SIZE_BYTES)
        clampX25519Scalar(privateKey)
        val publicKey = X25519Fallback.publicKeyFromPrivate(privateKey)
        return X25519KeyPair(privateKey = privateKey, publicKey = publicKey)
    }

    override fun generateEd25519KeyPair(): Ed25519KeyPair {
        return ed25519Fallback.generateKeyPair()
    }

    override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        return requireValidX25519SharedSecret(X25519Fallback.sharedSecret(privateKey, publicKey))
    }

    override fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        return ed25519Fallback.sign(privateKey, message)
    }

    override fun ed25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        return ed25519Fallback.verify(publicKey, message, signature)
    }

    override fun chacha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        require(key.size == CHACHA20_KEY_SIZE_BYTES) { "ChaCha20-Poly1305 key must be 32 bytes" }
        require(nonce.size == CHACHA20_NONCE_SIZE_BYTES) {
            "ChaCha20-Poly1305 nonce must be 12 bytes"
        }

        val poly1305Key = chacha20Block(key, counter = 0, nonce = nonce).copyOfRange(0, 32)
        val ciphertext =
            chacha20Xor(key = key, nonce = nonce, initialCounter = 1, input = plaintext)
        val tag = poly1305Mac(buildAeadAuthData(aad = aad, ciphertext = ciphertext), poly1305Key)
        return ciphertext + tag
    }

    override fun chacha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        require(ciphertext.size >= POLY1305_TAG_SIZE_BYTES) {
            "ChaCha20-Poly1305 ciphertext must include the authentication tag"
        }
        require(key.size == CHACHA20_KEY_SIZE_BYTES) { "ChaCha20-Poly1305 key must be 32 bytes" }
        require(nonce.size == CHACHA20_NONCE_SIZE_BYTES) {
            "ChaCha20-Poly1305 nonce must be 12 bytes"
        }

        val tagOffset = ciphertext.size - POLY1305_TAG_SIZE_BYTES
        val encrypted = ciphertext.copyOfRange(0, tagOffset)
        val providedTag = ciphertext.copyOfRange(tagOffset, ciphertext.size)
        val poly1305Key = chacha20Block(key, counter = 0, nonce = nonce).copyOfRange(0, 32)
        val expectedTag =
            poly1305Mac(buildAeadAuthData(aad = aad, ciphertext = encrypted), poly1305Key)
        if (!constantTimeEquals(expectedTag, providedTag)) {
            throw MeshLinkException.CryptoFailure("ChaCha20-Poly1305 tag verification failed")
        }
        return chacha20Xor(key = key, nonce = nonce, initialCounter = 1, input = encrypted)
    }

    private fun chacha20Xor(
        key: ByteArray,
        nonce: ByteArray,
        initialCounter: Int,
        input: ByteArray,
    ): ByteArray {
        val output = ByteArray(input.size)
        var counter = initialCounter
        var offset = 0
        while (offset < input.size) {
            val block = chacha20Block(key = key, counter = counter, nonce = nonce)
            val remaining = minOf(CHACHA20_BLOCK_SIZE_BYTES, input.size - offset)
            for (index in 0 until remaining) {
                output[offset + index] =
                    (input[offset + index].toInt() xor block[index].toInt()).toByte()
            }
            counter += 1
            offset += remaining
        }
        return output
    }

    private fun chacha20Block(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        require(key.size == CHACHA20_KEY_SIZE_BYTES) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == CHACHA20_NONCE_SIZE_BYTES) { "ChaCha20 nonce must be 12 bytes" }

        val state =
            intArrayOf(
                0x61707865,
                0x3320646e,
                0x79622d32,
                0x6b206574,
                toIntLE(key, 0),
                toIntLE(key, 4),
                toIntLE(key, 8),
                toIntLE(key, 12),
                toIntLE(key, 16),
                toIntLE(key, 20),
                toIntLE(key, 24),
                toIntLE(key, 28),
                counter,
                toIntLE(nonce, 0),
                toIntLE(nonce, 4),
                toIntLE(nonce, 8),
            )
        val working = state.copyOf()

        repeat(10) {
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
        }

        val output = ByteArray(CHACHA20_BLOCK_SIZE_BYTES)
        for (index in working.indices) {
            writeIntLE(output, index * 4, working[index] + state[index])
        }
        return output
    }

    private fun quarterRound(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
        state[a] += state[b]
        state[d] = rotateLeft(state[d] xor state[a], 16)
        state[c] += state[d]
        state[b] = rotateLeft(state[b] xor state[c], 12)
        state[a] += state[b]
        state[d] = rotateLeft(state[d] xor state[a], 8)
        state[c] += state[d]
        state[b] = rotateLeft(state[b] xor state[c], 7)
    }

    private fun buildAeadAuthData(aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(aad)
        out.write(pad16(aad.size))
        out.write(ciphertext)
        out.write(pad16(ciphertext.size))
        out.write(longToLittleEndian(aad.size.toLong()))
        out.write(longToLittleEndian(ciphertext.size.toLong()))
        return out.toByteArray()
    }

    private fun poly1305Mac(message: ByteArray, oneTimeKey: ByteArray): ByteArray {
        require(oneTimeKey.size == POLY1305_ONE_TIME_KEY_SIZE_BYTES) {
            "Poly1305 one-time key must be 32 bytes"
        }
        val rBytes = oneTimeKey.copyOfRange(0, 16)
        clampPoly1305R(rBytes)
        val sBytes = oneTimeKey.copyOfRange(16, 32)
        val r = littleEndianToBigInteger(rBytes)
        val s = littleEndianToBigInteger(sBytes)
        var accumulator = BigInteger.ZERO

        var offset = 0
        while (offset < message.size) {
            val block = message.copyOfRange(offset, minOf(offset + 16, message.size))
            val padded = block + byteArrayOf(1)
            accumulator = (accumulator + littleEndianToBigInteger(padded)).mod(POLY1305_MODULUS)
            accumulator = (accumulator * r).mod(POLY1305_MODULUS)
            offset += 16
        }

        val tag = accumulator.add(s).mod(POLY1305_TAG_MODULUS)
        return bigIntegerToLittleEndian(tag, POLY1305_TAG_SIZE_BYTES)
    }

    private fun clampX25519Scalar(bytes: ByteArray) {
        bytes[0] = (bytes[0].toInt() and 248).toByte()
        bytes[31] = (bytes[31].toInt() and 127).toByte()
        bytes[31] = (bytes[31].toInt() or 64).toByte()
    }

    private fun clampPoly1305R(bytes: ByteArray) {
        bytes[3] = (bytes[3].toInt() and 15).toByte()
        bytes[7] = (bytes[7].toInt() and 15).toByte()
        bytes[11] = (bytes[11].toInt() and 15).toByte()
        bytes[15] = (bytes[15].toInt() and 15).toByte()
        bytes[4] = (bytes[4].toInt() and 252).toByte()
        bytes[8] = (bytes[8].toInt() and 252).toByte()
        bytes[12] = (bytes[12].toInt() and 252).toByte()
    }

    private fun pad16(length: Int): ByteArray {
        val remainder = length % 16
        return if (remainder == 0) byteArrayOf() else ByteArray(16 - remainder)
    }

    private fun longToLittleEndian(value: Long): ByteArray {
        return ByteArray(8) { index -> ((value ushr (index * 8)) and 0xffL).toByte() }
    }

    private fun toIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun rotateLeft(value: Int, bits: Int): Int {
        return (value shl bits) or (value ushr (32 - bits))
    }

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        var diff = 0
        for (index in left.indices) {
            diff = diff or (left[index].toInt() xor right[index].toInt())
        }
        return diff == 0
    }

    private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger {
        return BigInteger(1, bytes.reversedArray())
    }

    private fun bigIntegerToLittleEndian(value: BigInteger, size: Int): ByteArray {
        val bigEndian =
            value.toByteArray().let {
                if (it.size > size) it.copyOfRange(it.size - size, it.size) else it
            }
        val padded = ByteArray(size)
        bigEndian.copyInto(padded, destinationOffset = size - bigEndian.size)
        return padded.reversedArray()
    }

    private fun <T> threadLocal(create: () -> T): ThreadLocal<T> {
        return object : ThreadLocal<T>() {
            override fun initialValue(): T {
                return create()
            }
        }
    }

    private fun <T> ThreadLocal<T>.value(): T {
        return checkNotNull(get())
    }

    private companion object {
        private const val X25519_KEY_SIZE_BYTES = 32
        private const val CHACHA20_KEY_SIZE_BYTES = 32
        private const val CHACHA20_NONCE_SIZE_BYTES = 12
        private const val CHACHA20_BLOCK_SIZE_BYTES = 64
        private const val POLY1305_TAG_SIZE_BYTES = 16
        private const val POLY1305_ONE_TIME_KEY_SIZE_BYTES = 32
        private val POLY1305_MODULUS = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
        private val POLY1305_TAG_MODULUS = BigInteger.ONE.shiftLeft(128)
    }
}
