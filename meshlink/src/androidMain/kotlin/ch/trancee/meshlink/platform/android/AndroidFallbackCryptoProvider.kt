package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Ed25519KeyPair
import ch.trancee.meshlink.crypto.PureX25519
import ch.trancee.meshlink.crypto.X25519KeyPair
import ch.trancee.meshlink.crypto.requireValidX25519SharedSecret
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
        val publicKey = PureX25519.publicKeyFromClampedPrivate(privateKey)
        return X25519KeyPair(privateKey = privateKey, publicKey = publicKey)
    }

    override fun generateEd25519KeyPair(): Ed25519KeyPair {
        return ed25519Fallback.generateKeyPair()
    }

    override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        return requireValidX25519SharedSecret(PureX25519.sharedSecret(privateKey, publicKey))
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
        val aadPaddingSize = pad16Size(aad.size)
        val ciphertextPaddingSize = pad16Size(ciphertext.size)
        val authData =
            ByteArray(aad.size + aadPaddingSize + ciphertext.size + ciphertextPaddingSize + 16)
        var offset = 0
        aad.copyInto(authData, destinationOffset = offset)
        offset += aad.size + aadPaddingSize
        ciphertext.copyInto(authData, destinationOffset = offset)
        offset += ciphertext.size + ciphertextPaddingSize
        longToLittleEndian(aad.size.toLong()).copyInto(authData, destinationOffset = offset)
        offset += 8
        longToLittleEndian(ciphertext.size.toLong()).copyInto(authData, destinationOffset = offset)
        return authData
    }

    /**
     * RFC 8439 §2.5 Poly1305, implemented with the standard 5x26-bit limb ("poly1305-donna")
     * technique instead of arbitrary-precision [BigInteger] arithmetic. This avoids per-block heap
     * allocation and division/modulo overhead, and — unlike a generic bignum library — keeps every
     * operation a fixed sequence of adds, multiplies, and masks with no data-dependent branches,
     * matching the constant-time requirement for Poly1305.
     */
    private fun poly1305Mac(message: ByteArray, oneTimeKey: ByteArray): ByteArray {
        require(oneTimeKey.size == POLY1305_ONE_TIME_KEY_SIZE_BYTES) {
            "Poly1305 one-time key must be 32 bytes"
        }

        val t0 = toUnsignedIntLE(oneTimeKey, 0)
        val t1 = toUnsignedIntLE(oneTimeKey, 4)
        val t2 = toUnsignedIntLE(oneTimeKey, 8)
        val t3 = toUnsignedIntLE(oneTimeKey, 12)

        // Clamp r into 5 x 26-bit limbs (top 4 bits of bytes 3/7/11/15 and bottom 2 bits of
        // bytes 4/8/12 cleared per RFC 8439 §2.5.1), folded directly into the limb masks.
        val r0 = t0 and 0x3ffffffL
        val r1 = ((t0 ushr 26) or (t1 shl 6)) and 0x3ffff03L
        val r2 = ((t1 ushr 20) or (t2 shl 12)) and 0x3ffc0ffL
        val r3 = ((t2 ushr 14) or (t3 shl 18)) and 0x3f03fffL
        val r4 = (t3 ushr 8) and 0x00fffffL

        val s1 = r1 * 5L
        val s2 = r2 * 5L
        val s3 = r3 * 5L
        val s4 = r4 * 5L

        val h = LongArray(5)

        var offset = 0
        while (offset + 16 <= message.size) {
            poly1305Absorb(
                h,
                r0,
                r1,
                r2,
                r3,
                r4,
                s1,
                s2,
                s3,
                s4,
                message,
                offset,
                hibit = 1L shl 24,
            )
            offset += 16
        }
        if (offset < message.size) {
            val block = ByteArray(16)
            val remaining = message.size - offset
            message.copyInto(
                block,
                destinationOffset = 0,
                startIndex = offset,
                endIndex = message.size,
            )
            block[remaining] = 1
            poly1305Absorb(h, r0, r1, r2, r3, r4, s1, s2, s3, s4, block, 0, hibit = 0L)
        }

        val pad0 = toUnsignedIntLE(oneTimeKey, 16)
        val pad1 = toUnsignedIntLE(oneTimeKey, 20)
        val pad2 = toUnsignedIntLE(oneTimeKey, 24)
        val pad3 = toUnsignedIntLE(oneTimeKey, 28)
        return poly1305Finish(h, pad0, pad1, pad2, pad3)
    }

    private fun poly1305Absorb(
        h: LongArray,
        r0: Long,
        r1: Long,
        r2: Long,
        r3: Long,
        r4: Long,
        s1: Long,
        s2: Long,
        s3: Long,
        s4: Long,
        block: ByteArray,
        offset: Int,
        hibit: Long,
    ) {
        val t0 = toUnsignedIntLE(block, offset)
        val t1 = toUnsignedIntLE(block, offset + 4)
        val t2 = toUnsignedIntLE(block, offset + 8)
        val t3 = toUnsignedIntLE(block, offset + 12)

        var h0 = h[0] + (t0 and 0x3ffffffL)
        var h1 = h[1] + (((t1 shl 32) or t0) ushr 26 and 0x3ffffffL)
        var h2 = h[2] + (((t2 shl 32) or t1) ushr 20 and 0x3ffffffL)
        var h3 = h[3] + (((t3 shl 32) or t2) ushr 14 and 0x3ffffffL)
        var h4 = h[4] + ((t3 ushr 8) or hibit)

        val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
        val d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
        val d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
        val d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
        val d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

        var carry = d0 ushr 26
        h0 = d0 and 0x3ffffffL
        var acc = d1 + carry
        carry = acc ushr 26
        h1 = acc and 0x3ffffffL
        acc = d2 + carry
        carry = acc ushr 26
        h2 = acc and 0x3ffffffL
        acc = d3 + carry
        carry = acc ushr 26
        h3 = acc and 0x3ffffffL
        acc = d4 + carry
        carry = acc ushr 26
        h4 = acc and 0x3ffffffL
        h0 += carry * 5
        carry = h0 ushr 26
        h0 = h0 and 0x3ffffffL
        h1 += carry

        h[0] = h0
        h[1] = h1
        h[2] = h2
        h[3] = h3
        h[4] = h4
    }

    private fun poly1305Finish(
        h: LongArray,
        pad0: Long,
        pad1: Long,
        pad2: Long,
        pad3: Long,
    ): ByteArray {
        var h0 = h[0]
        var h1 = h[1]
        var h2 = h[2]
        var h3 = h[3]
        var h4 = h[4]

        var carry = h1 ushr 26
        h1 = h1 and 0x3ffffffL
        h2 += carry
        carry = h2 ushr 26
        h2 = h2 and 0x3ffffffL
        h3 += carry
        carry = h3 ushr 26
        h3 = h3 and 0x3ffffffL
        h4 += carry
        carry = h4 ushr 26
        h4 = h4 and 0x3ffffffL
        h0 += carry * 5
        carry = h0 ushr 26
        h0 = h0 and 0x3ffffffL
        h1 += carry

        var g0 = h0 + 5
        carry = g0 ushr 26
        g0 = g0 and 0x3ffffffL
        var g1 = h1 + carry
        carry = g1 ushr 26
        g1 = g1 and 0x3ffffffL
        var g2 = h2 + carry
        carry = g2 ushr 26
        g2 = g2 and 0x3ffffffL
        var g3 = h3 + carry
        carry = g3 ushr 26
        g3 = g3 and 0x3ffffffL
        val g4 = h4 + carry - (1L shl 26)

        // Constant-time select: use g (h - p) when it didn't borrow (h >= p), else keep h.
        val maskG = (g4 shr 63).inv()
        val maskH = maskG.inv()
        g0 = g0 and maskG
        g1 = g1 and maskG
        g2 = g2 and maskG
        g3 = g3 and maskG
        val g4Selected = g4 and maskG
        h0 = (h0 and maskH) or g0
        h1 = (h1 and maskH) or g1
        h2 = (h2 and maskH) or g2
        h3 = (h3 and maskH) or g3
        h4 = (h4 and maskH) or g4Selected

        val f0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val f1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val f2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val f3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL

        var acc = f0 + pad0
        val m0 = acc and 0xffffffffL
        acc = f1 + pad1 + (acc ushr 32)
        val m1 = acc and 0xffffffffL
        acc = f2 + pad2 + (acc ushr 32)
        val m2 = acc and 0xffffffffL
        acc = f3 + pad3 + (acc ushr 32)
        val m3 = acc and 0xffffffffL

        val tag = ByteArray(POLY1305_TAG_SIZE_BYTES)
        writeIntLE(tag, 0, m0.toInt())
        writeIntLE(tag, 4, m1.toInt())
        writeIntLE(tag, 8, m2.toInt())
        writeIntLE(tag, 12, m3.toInt())
        return tag
    }

    private fun clampX25519Scalar(bytes: ByteArray) {
        bytes[0] = (bytes[0].toInt() and 248).toByte()
        bytes[31] = (bytes[31].toInt() and 127).toByte()
        bytes[31] = (bytes[31].toInt() or 64).toByte()
    }

    private fun pad16Size(length: Int): Int {
        val remainder = length % 16
        return if (remainder == 0) 0 else 16 - remainder
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

    private fun toUnsignedIntLE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
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
    }
}
