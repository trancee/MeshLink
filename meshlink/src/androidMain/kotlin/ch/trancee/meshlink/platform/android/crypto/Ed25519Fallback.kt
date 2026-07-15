package ch.trancee.meshlink.platform.android.crypto

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.crypto.Ed25519KeyPair
import java.security.MessageDigest
import java.util.WeakHashMap

/**
 * Pure Kotlin Ed25519 fallback for Android devices that expose XDH and ChaCha20-Poly1305 through
 * JCA but do not provide Ed25519 key or signature primitives (for example Samsung Android 12
 * devices observed during validation).
 *
 * The implementation follows RFC 8032 with TweetNaCl-style 16-limb field arithmetic so the hot path
 * stays on primitive arrays instead of `BigInteger`. Expanded private keys are cached by
 * `ByteArray` object identity to avoid repeatedly hashing and recomputing the public key for
 * long-lived local identities during signing.
 */
internal class Ed25519Fallback(private val randomBytesProvider: (Int) -> ByteArray) {
    private val expandedPrivateKeys = WeakHashMap<ByteArray, ExpandedPrivateKey>()
    private val sha512Digests =
        object : ThreadLocal<MessageDigest>() {
            override fun initialValue(): MessageDigest {
                return MessageDigest.getInstance("SHA-512")
            }
        }

    private val fieldArithmetic = Curve25519FieldArithmetic()
    private val pointArithmetic = Curve25519PointArithmetic(fieldArithmetic)

    internal fun generateKeyPair(): Ed25519KeyPair {
        val privateKey =
            requireSized(
                value = randomBytesProvider(PRIVATE_KEY_SIZE_BYTES).copyOf(),
                expectedSize = PRIVATE_KEY_SIZE_BYTES,
                label = "Ed25519 private",
            )
        val expanded = expandedPrivateKey(privateKey)
        return Ed25519KeyPair(privateKey = privateKey, publicKey = expanded.publicKey.copyOf())
    }

    internal fun deriveKeyPair(privateKey: ByteArray): Ed25519KeyPair {
        val seed =
            requireSized(
                value = privateKey,
                expectedSize = PRIVATE_KEY_SIZE_BYTES,
                label = "Ed25519 private",
            )
        val expanded = expandedPrivateKey(seed)
        return Ed25519KeyPair(privateKey = seed.copyOf(), publicKey = expanded.publicKey.copyOf())
    }

    internal fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val expanded = expandedPrivateKey(privateKey)
        val reducedNonce = reduceScalar(sha512(expanded.prefix, message))
        val noncePoint = Point()
        pointArithmetic.scalarBase(noncePoint, reducedNonce)
        val encodedR = ByteArray(PUBLIC_KEY_SIZE_BYTES)
        pointArithmetic.pack(encodedR, noncePoint)

        val reducedChallenge = reduceScalar(sha512(encodedR, expanded.publicKey, message))
        val s = ByteArray(SCALAR_SIZE_BYTES)
        val x = LongArray(64)
        for (index in 0 until SCALAR_SIZE_BYTES) {
            x[index] = reducedNonce[index].toUnsignedLong()
        }
        for (i in 0 until SCALAR_SIZE_BYTES) {
            val challengeByte = reducedChallenge[i].toUnsignedLong()
            for (j in 0 until SCALAR_SIZE_BYTES) {
                x[i + j] += challengeByte * expanded.scalar[j].toUnsignedLong()
            }
        }
        reduceModGroupOrder(output = s, input = x)

        return ByteArray(SIGNATURE_SIZE_BYTES).also { signature ->
            encodedR.copyInto(signature, destinationOffset = 0)
            s.copyInto(signature, destinationOffset = PUBLIC_KEY_SIZE_BYTES)
        }
    }

    internal fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_SIZE_BYTES || signature.size != SIGNATURE_SIZE_BYTES) {
            return false
        }
        val scalarComponent = signature.copyOfRange(PUBLIC_KEY_SIZE_BYTES, SIGNATURE_SIZE_BYTES)
        if (!isCanonicalScalar(scalarComponent)) {
            return false
        }

        val publicPoint = Point()
        if (pointArithmetic.unpackNegative(publicPoint, publicKey) != 0) {
            return false
        }

        val reducedChallenge =
            reduceScalar(
                sha512(signature.copyOfRange(0, PUBLIC_KEY_SIZE_BYTES), publicKey, message)
            )
        // The challenge and public key are both public (part of the signature/identity), so this
        // multiplication does not need to be constant-time; a plain windowed method is used
        // instead of the constant-time comb selection that fixed-base multiplication requires.
        val leftSide = Point()
        pointArithmetic.windowedScalarMultiplyPublic(leftSide, publicPoint, reducedChallenge)

        val rightSide = Point()
        pointArithmetic.scalarBase(rightSide, scalarComponent)

        pointArithmetic.add(leftSide, rightSide)
        val encoded = ByteArray(PUBLIC_KEY_SIZE_BYTES)
        pointArithmetic.pack(encoded, leftSide)
        return constantTimeEquals(signature, 0, encoded, 0, PUBLIC_KEY_SIZE_BYTES)
    }

    private fun expandedPrivateKey(privateKey: ByteArray): ExpandedPrivateKey {
        val seed =
            requireSized(
                value = privateKey,
                expectedSize = PRIVATE_KEY_SIZE_BYTES,
                label = "Ed25519 private",
            )
        synchronized(expandedPrivateKeys) {
            expandedPrivateKeys[seed]?.let { cached ->
                return cached
            }
        }

        val hash = sha512(seed)
        val scalar = hash.copyOfRange(0, SCALAR_SIZE_BYTES).also(::clampScalar)
        val prefix = hash.copyOfRange(SCALAR_SIZE_BYTES, HASH_SIZE_BYTES)
        val publicPoint = Point()
        pointArithmetic.scalarBase(publicPoint, scalar)
        val publicKey = ByteArray(PUBLIC_KEY_SIZE_BYTES)
        pointArithmetic.pack(publicKey, publicPoint)
        val expanded = ExpandedPrivateKey(scalar = scalar, prefix = prefix, publicKey = publicKey)

        synchronized(expandedPrivateKeys) { expandedPrivateKeys[seed] = expanded }
        return expanded
    }

    private fun clampScalar(scalar: ByteArray): Unit {
        scalar[0] = (scalar[0].toInt() and 248).toByte()
        scalar[31] = ((scalar[31].toInt() and 127) or 64).toByte()
    }

    private fun reduceScalar(hash: ByteArray): ByteArray {
        require(hash.size == HASH_SIZE_BYTES) { "Ed25519 scalar reduction requires a 64-byte hash" }
        val reduced = hash.copyOf()
        reduce(reduced)
        return reduced.copyOf(SCALAR_SIZE_BYTES)
    }

    private fun sha512(vararg chunks: ByteArray): ByteArray {
        val digest = checkNotNull(sha512Digests.get())
        digest.reset()
        chunks.forEach(digest::update)
        return digest.digest()
    }

    private fun reduce(input: ByteArray): Unit {
        val expanded = LongArray(64) { index -> input[index].toUnsignedLong() }
        input.fill(0)
        reduceModGroupOrder(output = input, input = expanded)
    }

    private fun reduceModGroupOrder(output: ByteArray, input: LongArray): Unit {
        var carry: Long
        for (index in 63 downTo SCALAR_SIZE_BYTES) {
            carry = 0L
            var outputIndex = index - SCALAR_SIZE_BYTES
            val outputLimit = index - 12
            while (outputIndex < outputLimit) {
                input[outputIndex] +=
                    carry -
                        (16L *
                            input[index] *
                            GROUP_ORDER[outputIndex - (index - SCALAR_SIZE_BYTES)])
                carry = (input[outputIndex] + 128L) shr 8
                input[outputIndex] -= carry shl 8
                outputIndex += 1
            }
            input[outputIndex] += carry
            input[index] = 0L
        }

        carry = 0L
        for (index in 0 until SCALAR_SIZE_BYTES) {
            input[index] += carry - ((input[31] shr 4) * GROUP_ORDER[index])
            carry = input[index] shr 8
            input[index] = input[index] and 255L
        }
        for (index in 0 until SCALAR_SIZE_BYTES) {
            input[index] -= carry * GROUP_ORDER[index]
        }
        for (index in 0 until SCALAR_SIZE_BYTES) {
            input[index + 1] += input[index] shr 8
            output[index] = (input[index] and 255L).toByte()
        }
    }

    private fun isCanonicalScalar(scalar: ByteArray): Boolean {
        if (scalar.size != SCALAR_SIZE_BYTES) {
            return false
        }
        for (index in SCALAR_SIZE_BYTES - 1 downTo 0) {
            val actual = scalar[index].toInt() and 0xFF
            val limit = GROUP_ORDER[index].toInt()
            if (actual != limit) {
                return actual < limit
            }
        }
        return false
    }

    private fun constantTimeEquals(
        first: ByteArray,
        firstOffset: Int,
        second: ByteArray,
        secondOffset: Int,
        length: Int,
    ): Boolean {
        var diff = 0
        for (index in 0 until length) {
            diff =
                diff or
                    ((first[firstOffset + index].toInt() and 0xFF) xor
                        (second[secondOffset + index].toInt() and 0xFF))
        }
        return diff == 0
    }

    private fun requireSized(value: ByteArray, expectedSize: Int, label: String): ByteArray {
        if (value.size != expectedSize) {
            throw MeshLinkException.CryptoFailure("$label key must be $expectedSize bytes")
        }
        return value
    }

    private fun Byte.toUnsignedLong(): Long {
        return (toInt() and 0xFF).toLong()
    }

    private class ExpandedPrivateKey(scalar: ByteArray, prefix: ByteArray, publicKey: ByteArray) {
        val scalar: ByteArray = scalar.copyOf()
        val prefix: ByteArray = prefix.copyOf()
        val publicKey: ByteArray = publicKey.copyOf()
    }

    private companion object {
        private const val HASH_SIZE_BYTES: Int = 64
        private const val PRIVATE_KEY_SIZE_BYTES: Int = 32
        private const val PUBLIC_KEY_SIZE_BYTES: Int = 32
        private const val SCALAR_SIZE_BYTES: Int = 32
        private const val SIGNATURE_SIZE_BYTES: Int = 64

        private val GROUP_ORDER =
            longArrayOf(
                0xedL,
                0xd3L,
                0xf5L,
                0x5cL,
                0x1aL,
                0x63L,
                0x12L,
                0x58L,
                0xd6L,
                0x9cL,
                0xf7L,
                0xa2L,
                0xdeL,
                0xf9L,
                0xdeL,
                0x14L,
                0x00L,
                0x00L,
                0x00L,
                0x00L,
                0x00L,
                0x00L,
                0x00L,
                0x00L,
                0x00L,
                0x00L,
                0x00L,
                0x00L,
                0x00L,
                0x00L,
                0x00L,
                0x10L,
            )
    }
}
