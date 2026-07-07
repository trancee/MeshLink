package ch.trancee.meshlink.crypto

internal object PureX25519 {
    private val basePoint = ByteArray(32).also { it[0] = 9 }

    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        return scalarMult(privateKey = privateKey, publicKey = basePoint)
    }

    fun sharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        return scalarMult(privateKey = privateKey, publicKey = publicKey)
    }

    fun publicKeyFromClampedPrivate(privateKey: ByteArray): ByteArray {
        return scalarMultWithClampedScalar(privateKey = privateKey, publicKey = basePoint)
    }

    private fun scalarMult(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == X25519_KEY_SIZE_BYTES) { "X25519 private key must be 32 bytes" }
        require(publicKey.size == X25519_KEY_SIZE_BYTES) { "X25519 public key must be 32 bytes" }

        val scalar = privateKey.copyOf()
        clampScalar(scalar)
        return scalarMultWithClampedScalar(privateKey = scalar, publicKey = publicKey)
    }

    private fun scalarMultWithClampedScalar(
        privateKey: ByteArray,
        publicKey: ByteArray,
    ): ByteArray {
        require(privateKey.size == X25519_KEY_SIZE_BYTES) { "X25519 private key must be 32 bytes" }
        require(publicKey.size == X25519_KEY_SIZE_BYTES) { "X25519 public key must be 32 bytes" }

        val scalar = privateKey
        val x1 = unpack25519(publicKey)
        var a = LongArray(16)
        var b = x1.copyOf()
        var c = LongArray(16)
        var d = LongArray(16)
        val e = LongArray(16)
        val f = LongArray(16)
        val g = LongArray(16)
        val h = LongArray(16)
        val product = LongArray(31)
        a[0] = 1
        d[0] = 1

        for (bitIndex in 254 downTo 0) {
            val bit = (scalar[bitIndex ushr 3].toInt() ushr (bitIndex and 7)) and 1
            conditionalSwap(a, b, bit)
            conditionalSwap(c, d, bit)

            addInto(e, a, c)
            subtractInto(a, a, c)
            addInto(f, b, d)
            subtractInto(b, b, d)
            squareInto(d, e, product)
            squareInto(g, a, product)
            multiplyInto(a, f, a, product)
            multiplyInto(c, b, e, product)
            addInto(h, a, c)
            subtractInto(a, a, c)
            squareInto(b, a, product)
            subtractInto(c, d, g)
            multiplyByA24Into(a, c, product)
            addInto(a, a, d)
            multiplyInto(c, c, a, product)
            multiplyInto(a, d, g, product)
            multiplyInto(d, b, x1, product)
            squareInto(b, h, product)

            conditionalSwap(a, b, bit)
            conditionalSwap(c, d, bit)
        }

        val inverseC = invert(c, product)
        multiplyInto(a, a, inverseC, product)
        return pack25519(a)
    }

    private fun clampScalar(scalar: ByteArray) {
        scalar[0] = (scalar[0].toInt() and 248).toByte()
        scalar[31] = ((scalar[31].toInt() and 127) or 64).toByte()
    }

    private fun addInto(dest: LongArray, left: LongArray, right: LongArray) {
        for (index in 0 until 16) {
            dest[index] = left[index] + right[index]
        }
    }

    private fun subtractInto(dest: LongArray, left: LongArray, right: LongArray) {
        for (index in 0 until 16) {
            dest[index] = left[index] - right[index]
        }
    }

    private fun squareInto(dest: LongArray, value: LongArray, product: LongArray) {
        // Schoolbook squaring skips recomputing symmetric cross terms (value[i]*value[j] ==
        // value[j]*value[i]) and doubles them once instead, roughly halving the number of
        // limb multiplications compared to calling multiplyInto(value, value). The ladder and
        // the modular inverse each perform hundreds of squarings per scalar multiplication, so
        // this is the single biggest win available for this field.
        for (index in 0 until 31) {
            product[index] = 0L
        }
        for (i in 0 until 16) {
            val leftValue = value[i]
            product[i + i] += leftValue * leftValue
            for (j in i + 1 until 16) {
                product[i + j] += 2L * leftValue * value[j]
            }
        }
        for (i in 0 until 15) {
            product[i] += 38L * product[i + 16]
        }
        for (index in 0 until 16) {
            dest[index] = product[index]
        }
        carry25519(dest)
        carry25519(dest)
    }

    private fun multiplyInto(
        dest: LongArray,
        left: LongArray,
        right: LongArray,
        product: LongArray,
    ) {
        for (index in 0 until 31) {
            product[index] = 0L
        }
        for (i in 0 until 16) {
            val leftValue = left[i]
            for (j in 0 until 16) {
                product[i + j] += leftValue * right[j]
            }
        }
        for (i in 0 until 15) {
            product[i] += 38L * product[i + 16]
        }
        for (index in 0 until 16) {
            dest[index] = product[index]
        }
        carry25519(dest)
        carry25519(dest)
    }

    private fun multiplyByA24Into(dest: LongArray, value: LongArray, product: LongArray) {
        for (index in 0 until 31) {
            product[index] = 0L
        }
        for (index in 0 until 16) {
            product[index] += value[index] * 0xdb41L
            if (index + 1 < 31) {
                product[index + 1] += value[index]
            }
        }
        for (index in 0 until 15) {
            product[index] += 38L * product[index + 16]
        }
        for (index in 0 until 16) {
            dest[index] = product[index]
        }
        carry25519(dest)
        carry25519(dest)
    }

    /**
     * Computes value^-1 mod p via Fermat's little theorem (value^(p-2)), using the standard
     * curve25519 addition chain for the exponent p-2 = 2^255-21 (ref10/curve25519-donna style)
     * instead of the naive one-bit-at-a-time square-and-multiply. This builds up runs of
     * consecutive 1 bits (2^k-1 patterns) via repeated squaring plus a single multiply per run,
     * cutting the multiplication count from 251 down to 11 while keeping the same 254 squarings.
     */
    private fun invert(value: LongArray, product: LongArray): LongArray {
        val z2 = LongArray(16)
        val z9 = LongArray(16)
        val z11 = LongArray(16)
        val z2_5_0 = LongArray(16)
        val z2_10_0 = LongArray(16)
        val z2_20_0 = LongArray(16)
        val z2_50_0 = LongArray(16)
        val z2_100_0 = LongArray(16)
        val t0 = LongArray(16)
        val t1 = LongArray(16)
        val result = LongArray(16)

        squareInto(z2, value, product) // 2
        squareInto(t0, z2, product) // 4
        squareInto(t0, t0, product) // 8
        multiplyInto(z9, t0, value, product) // 9
        multiplyInto(z11, z9, z2, product) // 11
        squareInto(t0, z11, product) // 22
        multiplyInto(z2_5_0, t0, z9, product) // 2^5 - 2^0 = 31

        squareInto(t0, z2_5_0, product)
        for (index in 1 until 5) squareInto(t0, t0, product)
        multiplyInto(z2_10_0, t0, z2_5_0, product) // 2^10 - 2^0

        squareInto(t0, z2_10_0, product)
        for (index in 1 until 10) squareInto(t0, t0, product)
        multiplyInto(z2_20_0, t0, z2_10_0, product) // 2^20 - 2^0

        squareInto(t0, z2_20_0, product)
        for (index in 1 until 20) squareInto(t0, t0, product)
        multiplyInto(t1, t0, z2_20_0, product) // 2^40 - 2^0

        squareInto(t0, t1, product)
        for (index in 1 until 10) squareInto(t0, t0, product)
        multiplyInto(z2_50_0, t0, z2_10_0, product) // 2^50 - 2^0

        squareInto(t0, z2_50_0, product)
        for (index in 1 until 50) squareInto(t0, t0, product)
        multiplyInto(z2_100_0, t0, z2_50_0, product) // 2^100 - 2^0

        squareInto(t0, z2_100_0, product)
        for (index in 1 until 100) squareInto(t0, t0, product)
        multiplyInto(t1, t0, z2_100_0, product) // 2^200 - 2^0

        squareInto(t0, t1, product)
        for (index in 1 until 50) squareInto(t0, t0, product)
        multiplyInto(t0, t0, z2_50_0, product) // 2^250 - 2^0

        squareInto(t0, t0, product) // 2^251 - 2^1
        squareInto(t0, t0, product) // 2^252 - 2^2
        squareInto(t0, t0, product) // 2^253 - 2^3
        squareInto(t0, t0, product) // 2^254 - 2^4
        squareInto(t0, t0, product) // 2^255 - 2^5
        multiplyInto(result, t0, z11, product) // 2^255 - 21
        return result
    }

    private fun pack25519(value: LongArray): ByteArray {
        val reduced = value.copyOf()
        repeat(3) { carry25519(reduced) }
        val candidate = LongArray(16)
        repeat(2) {
            candidate[0] = reduced[0] - 0xffedL
            for (index in 1 until 15) {
                candidate[index] = reduced[index] - 0xffffL - ((candidate[index - 1] shr 16) and 1L)
                candidate[index - 1] = candidate[index - 1] and 0xffffL
            }
            candidate[15] = reduced[15] - 0x7fffL - ((candidate[14] shr 16) and 1L)
            val carryBit = ((candidate[15] shr 16) and 1L).toInt()
            candidate[14] = candidate[14] and 0xffffL
            conditionalSwap(reduced, candidate, 1 - carryBit)
        }

        return ByteArray(32).also { output ->
            for (index in 0 until 16) {
                output[index * 2] = (reduced[index] and 0xffL).toByte()
                output[index * 2 + 1] = ((reduced[index] shr 8) and 0xffL).toByte()
            }
        }
    }

    private fun unpack25519(value: ByteArray): LongArray {
        val unpacked = LongArray(16)
        for (index in 0 until 16) {
            unpacked[index] =
                (value[index * 2].toInt() and 0xff).toLong() or
                    (((value[index * 2 + 1].toInt() and 0xff).toLong()) shl 8)
        }
        unpacked[15] = unpacked[15] and 0x7fffL
        return unpacked
    }

    private fun carry25519(value: LongArray) {
        var carry = 1L
        for (index in 0 until 16) {
            val current = value[index] + carry + 65535L
            carry = current shr 16
            value[index] = current - (carry shl 16)
        }
        value[0] += (carry - 1L) + 37L * (carry - 1L)
    }

    private fun conditionalSwap(left: LongArray, right: LongArray, swap: Int) {
        val mask = -swap.toLong()
        for (index in 0 until 16) {
            val delta = mask and (left[index] xor right[index])
            left[index] = left[index] xor delta
            right[index] = right[index] xor delta
        }
    }

    private const val X25519_KEY_SIZE_BYTES = 32
}
