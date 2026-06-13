package ch.trancee.meshlink.platform.android

internal object X25519Fallback {
    private val a24 = longArrayOf(0xdb41, 0x0001, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private val basePoint = ByteArray(32).also { it[0] = 9 }

    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        return scalarMult(privateKey = privateKey, publicKey = basePoint)
    }

    fun sharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        return scalarMult(privateKey = privateKey, publicKey = publicKey)
    }

    private fun scalarMult(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == X25519_KEY_SIZE_BYTES) { "X25519 private key must be 32 bytes" }
        require(publicKey.size == X25519_KEY_SIZE_BYTES) { "X25519 public key must be 32 bytes" }

        val scalar = privateKey.copyOf()
        clampScalar(scalar)
        val x1 = unpack25519(publicKey)
        var a = LongArray(16)
        var b = x1.copyOf()
        var c = LongArray(16)
        var d = LongArray(16)
        a[0] = 1
        d[0] = 1

        for (bitIndex in 254 downTo 0) {
            val bit = (scalar[bitIndex ushr 3].toInt() ushr (bitIndex and 7)) and 1
            conditionalSwap(a, b, bit)
            conditionalSwap(c, d, bit)

            val e = add(a, c)
            a = subtract(a, c)
            val f = add(b, d)
            b = subtract(b, d)
            d = square(e)
            val g = square(a)
            a = multiply(f, a)
            c = multiply(b, e)
            val h = add(a, c)
            a = subtract(a, c)
            b = square(a)
            c = subtract(d, g)
            a = multiply(c, a24)
            a = add(a, d)
            c = multiply(c, a)
            a = multiply(d, g)
            d = multiply(b, x1)
            b = square(h)

            conditionalSwap(a, b, bit)
            conditionalSwap(c, d, bit)
        }

        return pack25519(multiply(a, invert(c)))
    }

    private fun clampScalar(scalar: ByteArray) {
        scalar[0] = (scalar[0].toInt() and 248).toByte()
        scalar[31] = ((scalar[31].toInt() and 127) or 64).toByte()
    }

    private fun add(left: LongArray, right: LongArray): LongArray {
        return LongArray(16) { index -> left[index] + right[index] }
    }

    private fun subtract(left: LongArray, right: LongArray): LongArray {
        return LongArray(16) { index -> left[index] - right[index] }
    }

    private fun square(value: LongArray): LongArray {
        return multiply(value, value)
    }

    private fun multiply(left: LongArray, right: LongArray): LongArray {
        val product = LongArray(31)
        for (i in 0 until 16) {
            for (j in 0 until 16) {
                product[i + j] += left[i] * right[j]
            }
        }
        for (i in 0 until 15) {
            product[i] += 38L * product[i + 16]
        }
        val reduced = LongArray(16) { index -> product[index] }
        carry25519(reduced)
        carry25519(reduced)
        return reduced
    }

    private fun invert(value: LongArray): LongArray {
        var result = value.copyOf()
        for (iteration in 253 downTo 0) {
            result = square(result)
            if (iteration != 2 && iteration != 4) {
                result = multiply(result, value)
            }
        }
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
