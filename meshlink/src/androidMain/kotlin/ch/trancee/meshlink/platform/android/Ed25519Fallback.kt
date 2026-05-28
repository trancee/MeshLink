package ch.trancee.meshlink.platform.android

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
        scalarBase(noncePoint, reducedNonce)
        val encodedR = ByteArray(PUBLIC_KEY_SIZE_BYTES)
        pack(encodedR, noncePoint)

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
        if (unpackNegative(publicPoint, publicKey) != 0) {
            return false
        }

        val reducedChallenge =
            reduceScalar(
                sha512(signature.copyOfRange(0, PUBLIC_KEY_SIZE_BYTES), publicKey, message)
            )
        val leftSide = Point()
        scalarMultiply(leftSide, publicPoint, reducedChallenge)

        val rightSide = Point()
        scalarBase(rightSide, scalarComponent)

        add(leftSide, rightSide, PointScratch())
        val encoded = ByteArray(PUBLIC_KEY_SIZE_BYTES)
        pack(encoded, leftSide)
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
        scalarBase(publicPoint, scalar)
        val publicKey = ByteArray(PUBLIC_KEY_SIZE_BYTES)
        pack(publicKey, publicPoint)
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

    private fun scalarBase(output: Point, scalar: ByteArray): Unit {
        val base = Point()
        copyField(base.x, BASE_X)
        copyField(base.y, BASE_Y)
        copyField(base.z, FIELD_ONE)
        multiply(base.t, BASE_X, BASE_Y, LongArray(MULTIPLICATION_SCRATCH_SIZE))
        scalarMultiply(output, base, scalar)
    }

    private fun scalarMultiply(output: Point, point: Point, scalar: ByteArray): Unit {
        require(scalar.size >= SCALAR_SIZE_BYTES) { "Ed25519 scalar must be at least 32 bytes" }

        val workingPoint = point.copy()
        val scratch = PointScratch()
        setIdentity(output)
        for (bitIndex in 255 downTo 0) {
            val bit = (scalar[bitIndex ushr 3].toInt() ushr (bitIndex and 7)) and 1
            conditionalSwap(output, workingPoint, bit)
            add(workingPoint, output, scratch)
            add(output, output, scratch)
            conditionalSwap(output, workingPoint, bit)
        }
    }

    private fun setIdentity(point: Point): Unit {
        zeroField(point.x)
        copyField(point.y, FIELD_ONE)
        copyField(point.z, FIELD_ONE)
        zeroField(point.t)
    }

    private fun add(point: Point, other: Point, scratch: PointScratch): Unit {
        subtract(scratch.a, point.y, point.x)
        subtract(scratch.t, other.y, other.x)
        multiply(scratch.a, scratch.a, scratch.t, scratch.temp)
        add(scratch.b, point.x, point.y)
        add(scratch.t, other.x, other.y)
        multiply(scratch.b, scratch.b, scratch.t, scratch.temp)
        multiply(scratch.c, point.t, other.t, scratch.temp)
        multiply(scratch.c, scratch.c, D2, scratch.temp)
        multiply(scratch.d, point.z, other.z, scratch.temp)
        add(scratch.d, scratch.d, scratch.d)
        subtract(scratch.e, scratch.b, scratch.a)
        subtract(scratch.f, scratch.d, scratch.c)
        add(scratch.g, scratch.d, scratch.c)
        add(scratch.h, scratch.b, scratch.a)
        multiply(point.x, scratch.e, scratch.f, scratch.temp)
        multiply(point.y, scratch.h, scratch.g, scratch.temp)
        multiply(point.z, scratch.g, scratch.f, scratch.temp)
        multiply(point.t, scratch.e, scratch.h, scratch.temp)
    }

    private fun conditionalSwap(first: Point, second: Point, bit: Int): Unit {
        select(first.x, second.x, bit)
        select(first.y, second.y, bit)
        select(first.z, second.z, bit)
        select(first.t, second.t, bit)
    }

    private fun pack(output: ByteArray, point: Point): Unit {
        val tx = fieldElement()
        val ty = fieldElement()
        val zi = fieldElement()
        val temp = LongArray(MULTIPLICATION_SCRATCH_SIZE)
        invert(zi, point.z, temp)
        multiply(tx, point.x, zi, temp)
        multiply(ty, point.y, zi, temp)
        pack25519(output, ty)
        output[31] = (output[31].toInt() xor (fieldParity(tx) shl 7)).toByte()
    }

    private fun unpackNegative(output: Point, publicKey: ByteArray): Int {
        val t = fieldElement()
        val check = fieldElement()
        val numerator = fieldElement()
        val denominator = fieldElement()
        val denominator2 = fieldElement()
        val denominator4 = fieldElement()
        val denominator6 = fieldElement()
        val temp = LongArray(MULTIPLICATION_SCRATCH_SIZE)

        copyField(output.z, FIELD_ONE)
        unpack25519(output.y, publicKey)
        square(numerator, output.y, temp)
        multiply(denominator, numerator, D, temp)
        subtract(numerator, numerator, output.z)
        add(denominator, output.z, denominator)

        square(denominator2, denominator, temp)
        square(denominator4, denominator2, temp)
        multiply(denominator6, denominator4, denominator2, temp)
        multiply(t, denominator6, numerator, temp)
        multiply(t, t, denominator, temp)

        power2523(t, t, temp)
        multiply(t, t, numerator, temp)
        multiply(t, t, denominator, temp)
        multiply(t, t, denominator, temp)
        multiply(output.x, t, denominator, temp)

        square(check, output.x, temp)
        multiply(check, check, denominator, temp)
        if (fieldNotEqual(check, numerator)) {
            multiply(output.x, output.x, SQRT_MINUS_ONE, temp)
        }

        square(check, output.x, temp)
        multiply(check, check, denominator, temp)
        if (fieldNotEqual(check, numerator)) {
            return -1
        }

        if (fieldParity(output.x) == ((publicKey[31].toInt() and 0xFF) ushr 7)) {
            subtract(output.x, FIELD_ZERO, output.x)
        }
        multiply(output.t, output.x, output.y, temp)
        return 0
    }

    private fun add(output: LongArray, left: LongArray, right: LongArray): Unit {
        for (index in 0 until FIELD_ELEMENT_SIZE) {
            output[index] = left[index] + right[index]
        }
    }

    private fun subtract(output: LongArray, left: LongArray, right: LongArray): Unit {
        for (index in 0 until FIELD_ELEMENT_SIZE) {
            output[index] = left[index] - right[index]
        }
    }

    private fun multiply(
        output: LongArray,
        left: LongArray,
        right: LongArray,
        temp: LongArray,
    ): Unit {
        temp.fill(0)
        for (leftIndex in 0 until FIELD_ELEMENT_SIZE) {
            val leftValue = left[leftIndex]
            for (rightIndex in 0 until FIELD_ELEMENT_SIZE) {
                temp[leftIndex + rightIndex] += leftValue * right[rightIndex]
            }
        }
        for (index in 0 until FIELD_ELEMENT_SIZE - 1) {
            temp[index] += 38L * temp[index + FIELD_ELEMENT_SIZE]
        }
        for (index in 0 until FIELD_ELEMENT_SIZE) {
            output[index] = temp[index]
        }
        carry(output)
        carry(output)
    }

    private fun square(output: LongArray, input: LongArray, temp: LongArray): Unit {
        multiply(output, input, input, temp)
    }

    private fun invert(output: LongArray, input: LongArray, temp: LongArray): Unit {
        val c = input.copyOf()
        for (index in 253 downTo 0) {
            square(c, c, temp)
            if (index != 2 && index != 4) {
                multiply(c, c, input, temp)
            }
        }
        copyField(output, c)
    }

    private fun power2523(output: LongArray, input: LongArray, temp: LongArray): Unit {
        val c = input.copyOf()
        for (index in 250 downTo 0) {
            square(c, c, temp)
            if (index != 1) {
                multiply(c, c, input, temp)
            }
        }
        copyField(output, c)
    }

    private fun carry(output: LongArray): Unit {
        var carry = 1L
        for (index in 0 until FIELD_ELEMENT_SIZE) {
            val value = output[index] + carry + 65535L
            carry = value / 65536L
            output[index] = value - (carry * 65536L)
        }
        output[0] += (carry - 1L) + (37L * (carry - 1L))
    }

    private fun select(first: LongArray, second: LongArray, bit: Int): Unit {
        val mask = -bit.toLong()
        for (index in 0 until FIELD_ELEMENT_SIZE) {
            val xor = first[index] xor second[index]
            val delta = mask and xor
            first[index] = first[index] xor delta
            second[index] = second[index] xor delta
        }
    }

    private fun pack25519(output: ByteArray, input: LongArray): Unit {
        val reduced = input.copyOf()
        val candidate = fieldElement()
        repeat(3) { carry(reduced) }
        repeat(2) {
            candidate[0] = reduced[0] - 0xffedL
            for (index in 1 until FIELD_ELEMENT_SIZE - 1) {
                candidate[index] = reduced[index] - 0xffffL - ((candidate[index - 1] shr 16) and 1L)
                candidate[index - 1] = candidate[index - 1] and 0xffffL
            }
            candidate[15] = reduced[15] - 0x7fffL - ((candidate[14] shr 16) and 1L)
            val borrow = ((candidate[15] shr 16) and 1L).toInt()
            candidate[14] = candidate[14] and 0xffffL
            select(reduced, candidate, 1 - borrow)
        }
        for (index in 0 until FIELD_ELEMENT_SIZE) {
            output[index * 2] = (reduced[index] and 0xffL).toByte()
            output[(index * 2) + 1] = ((reduced[index] shr 8) and 0xffL).toByte()
        }
    }

    private fun unpack25519(output: LongArray, input: ByteArray): Unit {
        for (index in 0 until FIELD_ELEMENT_SIZE) {
            output[index] =
                input[index * 2].toUnsignedLong() + (input[(index * 2) + 1].toUnsignedLong() shl 8)
        }
        output[15] = output[15] and 0x7fffL
    }

    private fun fieldParity(input: LongArray): Int {
        val packed = ByteArray(PUBLIC_KEY_SIZE_BYTES)
        pack25519(packed, input)
        return packed[0].toInt() and 1
    }

    private fun fieldNotEqual(first: LongArray, second: LongArray): Boolean {
        val packedFirst = ByteArray(PUBLIC_KEY_SIZE_BYTES)
        val packedSecond = ByteArray(PUBLIC_KEY_SIZE_BYTES)
        pack25519(packedFirst, first)
        pack25519(packedSecond, second)
        return !constantTimeEquals(packedFirst, 0, packedSecond, 0, PUBLIC_KEY_SIZE_BYTES)
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

    private fun zeroField(output: LongArray): Unit {
        output.fill(0)
    }

    private fun copyField(output: LongArray, input: LongArray): Unit {
        input.copyInto(output)
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

    private class Point {
        val x: LongArray = fieldElement()
        val y: LongArray = fieldElement()
        val z: LongArray = fieldElement()
        val t: LongArray = fieldElement()

        fun copy(): Point {
            return Point().also { point ->
                x.copyInto(point.x)
                y.copyInto(point.y)
                z.copyInto(point.z)
                t.copyInto(point.t)
            }
        }
    }

    private class PointScratch {
        val a: LongArray = fieldElement()
        val b: LongArray = fieldElement()
        val c: LongArray = fieldElement()
        val d: LongArray = fieldElement()
        val e: LongArray = fieldElement()
        val f: LongArray = fieldElement()
        val g: LongArray = fieldElement()
        val h: LongArray = fieldElement()
        val t: LongArray = fieldElement()
        val temp: LongArray = LongArray(MULTIPLICATION_SCRATCH_SIZE)
    }

    private companion object {
        private fun fieldElement(): LongArray = LongArray(FIELD_ELEMENT_SIZE)

        private const val FIELD_ELEMENT_SIZE: Int = 16
        private const val HASH_SIZE_BYTES: Int = 64
        private const val MULTIPLICATION_SCRATCH_SIZE: Int = 31
        private const val PRIVATE_KEY_SIZE_BYTES: Int = 32
        private const val PUBLIC_KEY_SIZE_BYTES: Int = 32
        private const val SCALAR_SIZE_BYTES: Int = 32
        private const val SIGNATURE_SIZE_BYTES: Int = 64

        private val FIELD_ZERO =
            longArrayOf(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)

        private val FIELD_ONE =
            longArrayOf(1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)

        private val D =
            longArrayOf(
                0x78a3L,
                0x1359L,
                0x4dcaL,
                0x75ebL,
                0xd8abL,
                0x4141L,
                0x0a4dL,
                0x0070L,
                0xe898L,
                0x7779L,
                0x4079L,
                0x8cc7L,
                0xfe73L,
                0x2b6fL,
                0x6ceeL,
                0x5203L,
            )

        private val D2 =
            longArrayOf(
                0xf159L,
                0x26b2L,
                0x9b94L,
                0xebd6L,
                0xb156L,
                0x8283L,
                0x149aL,
                0x00e0L,
                0xd130L,
                0xeef3L,
                0x80f2L,
                0x198eL,
                0xfce7L,
                0x56dfL,
                0xd9dcL,
                0x2406L,
            )

        private val BASE_X =
            longArrayOf(
                0xd51aL,
                0x8f25L,
                0x2d60L,
                0xc956L,
                0xa7b2L,
                0x9525L,
                0xc760L,
                0x692cL,
                0xdc5cL,
                0xfdd6L,
                0xe231L,
                0xc0a4L,
                0x53feL,
                0xcd6eL,
                0x36d3L,
                0x2169L,
            )

        private val BASE_Y =
            longArrayOf(
                0x6658L,
                0x6666L,
                0x6666L,
                0x6666L,
                0x6666L,
                0x6666L,
                0x6666L,
                0x6666L,
                0x6666L,
                0x6666L,
                0x6666L,
                0x6666L,
                0x6666L,
                0x6666L,
                0x6666L,
                0x6666L,
            )

        private val SQRT_MINUS_ONE =
            longArrayOf(
                0xa0b0L,
                0x4a0eL,
                0x1b27L,
                0xc4eeL,
                0xe478L,
                0xad2fL,
                0x1806L,
                0x2f43L,
                0xd7a7L,
                0x3dfbL,
                0x0099L,
                0x2b4dL,
                0xdf0bL,
                0x4fc1L,
                0x2480L,
                0x2b83L,
            )

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
