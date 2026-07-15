package ch.trancee.meshlink.platform.android.crypto

/**
 * RFC 8032/TweetNaCl-style radix-2^16 field arithmetic over GF(2^255-19).
 *
 * This is extracted verbatim from [Ed25519Fallback] so the signing API can stay focused on key
 * handling/signature assembly while field operations remain isolated and independently auditable.
 *
 * IMPORTANT: This class intentionally preserves the exact arithmetic, carry strategy, and helper
 * structure from the original fallback implementation. It is a structural extraction only.
 */
internal class Curve25519FieldArithmetic {
    internal fun add(output: LongArray, left: LongArray, right: LongArray): Unit {
        for (index in 0 until FIELD_ELEMENT_SIZE) {
            output[index] = left[index] + right[index]
        }
    }

    internal fun subtract(output: LongArray, left: LongArray, right: LongArray): Unit {
        for (index in 0 until FIELD_ELEMENT_SIZE) {
            output[index] = left[index] - right[index]
        }
    }

    internal fun multiply(
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

    internal fun square(output: LongArray, input: LongArray, temp: LongArray): Unit {
        // Schoolbook squaring skips recomputing symmetric cross terms (input[i]*input[j] ==
        // input[j]*input[i]) and doubles them once instead, roughly halving the number of
        // limb multiplications compared to calling the general multiply(input, input).
        temp.fill(0)
        for (leftIndex in 0 until FIELD_ELEMENT_SIZE) {
            val leftValue = input[leftIndex]
            temp[leftIndex * 2] += leftValue * leftValue
            for (rightIndex in leftIndex + 1 until FIELD_ELEMENT_SIZE) {
                temp[leftIndex + rightIndex] += 2L * leftValue * input[rightIndex]
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

    /**
     * Computes input^-1 mod p via Fermat's little theorem (input^(p-2)), but instead of the naive
     * one-bit-at-a-time square-and-multiply (254 squarings + 251 multiplications), this uses the
     * standard curve25519/ed25519 addition chain for the exponent p-2 = 2^255-21 (as used by
     * ref10/curve25519-donna): it builds up runs of consecutive 1 bits (2^k-1 patterns) via
     * repeated squaring plus a single multiply per run, cutting the multiplication count from 251
     * down to 11 while keeping the same 254 squarings.
     */
    internal fun invert(output: LongArray, input: LongArray, temp: LongArray): Unit {
        val z2 = fieldElement()
        val z9 = fieldElement()
        val z11 = fieldElement()
        val z2_5_0 = fieldElement()
        val z2_10_0 = fieldElement()
        val z2_20_0 = fieldElement()
        val z2_50_0 = fieldElement()
        val z2_100_0 = fieldElement()
        val t0 = fieldElement()
        val t1 = fieldElement()

        square(z2, input, temp) // 2
        square(t0, z2, temp) // 4
        square(t0, t0, temp) // 8
        multiply(z9, t0, input, temp) // 9
        multiply(z11, z9, z2, temp) // 11
        square(t0, z11, temp) // 22
        multiply(z2_5_0, t0, z9, temp) // 2^5 - 2^0 = 31

        square(t0, z2_5_0, temp)
        for (index in 1 until 5) square(t0, t0, temp) // 2^10 - 2^5
        multiply(z2_10_0, t0, z2_5_0, temp) // 2^10 - 2^0

        square(t0, z2_10_0, temp)
        for (index in 1 until 10) square(t0, t0, temp) // 2^20 - 2^10
        multiply(z2_20_0, t0, z2_10_0, temp) // 2^20 - 2^0

        square(t0, z2_20_0, temp)
        for (index in 1 until 20) square(t0, t0, temp) // 2^40 - 2^20
        multiply(t1, t0, z2_20_0, temp) // 2^40 - 2^0

        square(t0, t1, temp)
        for (index in 1 until 10) square(t0, t0, temp) // 2^50 - 2^10
        multiply(z2_50_0, t0, z2_10_0, temp) // 2^50 - 2^0

        square(t0, z2_50_0, temp)
        for (index in 1 until 50) square(t0, t0, temp) // 2^100 - 2^50
        multiply(z2_100_0, t0, z2_50_0, temp) // 2^100 - 2^0

        square(t0, z2_100_0, temp)
        for (index in 1 until 100) square(t0, t0, temp) // 2^200 - 2^100
        multiply(t1, t0, z2_100_0, temp) // 2^200 - 2^0

        square(t0, t1, temp)
        for (index in 1 until 50) square(t0, t0, temp) // 2^250 - 2^50
        multiply(t0, t0, z2_50_0, temp) // 2^250 - 2^0

        square(t0, t0, temp) // 2^251 - 2^1
        square(t0, t0, temp) // 2^252 - 2^2
        square(t0, t0, temp) // 2^253 - 2^3
        square(t0, t0, temp) // 2^254 - 2^4
        square(t0, t0, temp) // 2^255 - 2^5
        multiply(output, t0, z11, temp) // 2^255 - 21
    }

    /**
     * Computes input^((p-5)/8) mod p = input^(2^252-3), used to compute a candidate square root
     * during point decompression. Uses the same style of addition chain as [invert] (ref10's
     * fe_pow22523), reducing the multiplication count from 249 down to 9 while keeping the same 250
     * squarings as the naive one-bit-at-a-time approach.
     */
    internal fun power2523(output: LongArray, input: LongArray, temp: LongArray): Unit {
        val z2 = fieldElement()
        val z9 = fieldElement()
        val z11 = fieldElement()
        val z2_5_0 = fieldElement()
        val z2_10_0 = fieldElement()
        val z2_20_0 = fieldElement()
        val z2_50_0 = fieldElement()
        val z2_100_0 = fieldElement()
        val t0 = fieldElement()
        val t1 = fieldElement()

        square(z2, input, temp) // 2
        square(t0, z2, temp) // 4
        square(t0, t0, temp) // 8
        multiply(z9, t0, input, temp) // 9
        multiply(z11, z9, z2, temp) // 11
        square(t0, z11, temp) // 22
        multiply(z2_5_0, t0, z9, temp) // 2^5 - 2^0 = 31

        square(t0, z2_5_0, temp)
        for (index in 1 until 5) square(t0, t0, temp)
        multiply(z2_10_0, t0, z2_5_0, temp) // 2^10 - 2^0

        square(t0, z2_10_0, temp)
        for (index in 1 until 10) square(t0, t0, temp)
        multiply(z2_20_0, t0, z2_10_0, temp) // 2^20 - 2^0

        square(t0, z2_20_0, temp)
        for (index in 1 until 20) square(t0, t0, temp)
        multiply(t1, t0, z2_20_0, temp) // 2^40 - 2^0

        square(t0, t1, temp)
        for (index in 1 until 10) square(t0, t0, temp)
        multiply(z2_50_0, t0, z2_10_0, temp) // 2^50 - 2^0

        square(t0, z2_50_0, temp)
        for (index in 1 until 50) square(t0, t0, temp)
        multiply(z2_100_0, t0, z2_50_0, temp) // 2^100 - 2^0

        square(t0, z2_100_0, temp)
        for (index in 1 until 100) square(t0, t0, temp)
        multiply(t1, t0, z2_100_0, temp) // 2^200 - 2^0

        square(t0, t1, temp)
        for (index in 1 until 50) square(t0, t0, temp)
        multiply(t0, t0, z2_50_0, temp) // 2^250 - 2^0

        square(t0, t0, temp) // 2^251 - 2^1
        square(t0, t0, temp) // 2^252 - 2^2
        multiply(output, t0, input, temp) // 2^252 - 3
    }

    internal fun carry(output: LongArray): Unit {
        var carry = 1L
        for (index in 0 until FIELD_ELEMENT_SIZE) {
            val value = output[index] + carry + 65535L
            carry = value / 65536L
            output[index] = value - (carry * 65536L)
        }
        output[0] += (carry - 1L) + (37L * (carry - 1L))
    }

    internal fun select(first: LongArray, second: LongArray, bit: Int): Unit {
        val mask = -bit.toLong()
        for (index in 0 until FIELD_ELEMENT_SIZE) {
            val xor = first[index] xor second[index]
            val delta = mask and xor
            first[index] = first[index] xor delta
            second[index] = second[index] xor delta
        }
    }

    internal fun pack25519(output: ByteArray, input: LongArray): Unit {
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

    internal fun unpack25519(output: LongArray, input: ByteArray): Unit {
        for (index in 0 until FIELD_ELEMENT_SIZE) {
            output[index] =
                input[index * 2].toUnsignedLong() + (input[(index * 2) + 1].toUnsignedLong() shl 8)
        }
        output[15] = output[15] and 0x7fffL
    }

    internal fun zeroField(output: LongArray): Unit {
        output.fill(0)
    }

    internal fun copyField(output: LongArray, input: LongArray): Unit {
        input.copyInto(output)
    }

    private fun Byte.toUnsignedLong(): Long {
        return (toInt() and 0xFF).toLong()
    }

    internal companion object {
        internal fun fieldElement(): LongArray = LongArray(FIELD_ELEMENT_SIZE)

        internal const val FIELD_ELEMENT_SIZE: Int = 16
        internal const val MULTIPLICATION_SCRATCH_SIZE: Int = 31

        internal val FIELD_ZERO =
            longArrayOf(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)

        internal val FIELD_ONE =
            longArrayOf(1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)

        internal val D =
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

        internal val D2 =
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

        internal val BASE_X =
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

        internal val BASE_Y =
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

        internal val SQRT_MINUS_ONE =
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
    }
}
