package ch.trancee.meshlink.platform.android.crypto

/**
 * Edwards-curve point arithmetic used by [Ed25519Fallback].
 *
 * This preserves the exact formulas and constant-time/variable-time distinctions from the original
 * fallback implementation; the only change is extraction into a dedicated class to isolate this
 * concern from signing/key-management logic.
 */
internal class Curve25519PointArithmetic(private val field: Curve25519FieldArithmetic) {
    /**
     * Precomputed radix-16 comb table for the fixed base point: `baseCombTable[block][digit]` =
     * `digit * 16^block * B`. Built once (lazily, on first use) so every subsequent fixed-base
     * scalar multiplication (key generation, signing, and half of verification) needs only 64 point
     * additions and zero point doublings, instead of 256 doublings + 256 additions for a generic
     * double-and-add ladder. This is the single biggest cost driver in the fallback
     * signer/verifier, so trading ~64 * 15 precomputed points (~0.5 MB, built once per process) for
     * that speedup is a deliberate priority-over-footprint tradeoff.
     */
    private val baseCombTable: Array<Array<Point>> by lazy { buildBaseCombTable() }

    /**
     * Fixed-base scalar multiplication using the precomputed radix-16 comb table. Runs in constant
     * time with respect to `scalar` (via masked table selection) because this is used for both
     * secret scalars (nonce, private key during key generation/signing) and public ones
     * (verification); the selection cost is negligible compared to the field multiplications it
     * replaces, so there is no reason to special-case the public callers.
     */
    internal fun scalarBase(output: Point, scalar: ByteArray): Unit {
        require(scalar.size >= SCALAR_SIZE_BYTES) { "Ed25519 scalar must be at least 32 bytes" }
        setIdentity(output)
        val scratch = PointScratch()
        val selected = Point()
        for (block in 0 until COMB_BLOCK_COUNT) {
            selectPoint(selected, baseCombTable[block], nibbleAt(scalar, block))
            add(output, selected, scratch)
        }
    }

    /**
     * Variable-base windowed scalar multiplication (radix-16, MSB-first). Both `point` and `scalar`
     * are public values at every call site (signature verification only), so this intentionally
     * branches on scalar digits instead of using constant-time selection, trading side-channel
     * resistance we don't need here for fewer point operations.
     */
    internal fun windowedScalarMultiplyPublic(
        output: Point,
        point: Point,
        scalar: ByteArray,
    ): Unit {
        require(scalar.size >= SCALAR_SIZE_BYTES) { "Ed25519 scalar must be at least 32 bytes" }

        val addScratch = PointScratch()
        val table = arrayOfNulls<Point>(COMB_DIGIT_COUNT)
        table[1] = point.copy()
        for (digit in 2 until COMB_DIGIT_COUNT) {
            val next = table[digit - 1]!!.copy()
            add(next, point, addScratch)
            table[digit] = next
        }

        setIdentity(output)
        val dblScratch = PointScratch()
        for (block in COMB_BLOCK_COUNT - 1 downTo 0) {
            repeat(WINDOW_BITS) { double(output, dblScratch) }
            val digit = nibbleAt(scalar, block)
            if (digit != 0) {
                add(output, table[digit]!!, addScratch)
            }
        }
    }

    internal fun add(point: Point, other: Point): Unit {
        add(point, other, PointScratch())
    }

    internal fun pack(output: ByteArray, point: Point): Unit {
        val tx = fieldElement()
        val ty = fieldElement()
        val zi = fieldElement()
        val temp = LongArray(Curve25519FieldArithmetic.MULTIPLICATION_SCRATCH_SIZE)
        field.invert(zi, point.z, temp)
        field.multiply(tx, point.x, zi, temp)
        field.multiply(ty, point.y, zi, temp)
        field.pack25519(output, ty)
        output[31] = (output[31].toInt() xor (fieldParity(tx) shl 7)).toByte()
    }

    internal fun unpackNegative(output: Point, publicKey: ByteArray): Int {
        val t = fieldElement()
        val check = fieldElement()
        val numerator = fieldElement()
        val denominator = fieldElement()
        val denominator2 = fieldElement()
        val denominator4 = fieldElement()
        val denominator6 = fieldElement()
        val temp = LongArray(Curve25519FieldArithmetic.MULTIPLICATION_SCRATCH_SIZE)

        field.copyField(output.z, Curve25519FieldArithmetic.FIELD_ONE)
        field.unpack25519(output.y, publicKey)
        field.square(numerator, output.y, temp)
        field.multiply(denominator, numerator, Curve25519FieldArithmetic.D, temp)
        field.subtract(numerator, numerator, output.z)
        field.add(denominator, output.z, denominator)

        field.square(denominator2, denominator, temp)
        field.square(denominator4, denominator2, temp)
        field.multiply(denominator6, denominator4, denominator2, temp)
        field.multiply(t, denominator6, numerator, temp)
        field.multiply(t, t, denominator, temp)

        field.power2523(t, t, temp)
        field.multiply(t, t, numerator, temp)
        field.multiply(t, t, denominator, temp)
        field.multiply(t, t, denominator, temp)
        field.multiply(output.x, t, denominator, temp)

        field.square(check, output.x, temp)
        field.multiply(check, check, denominator, temp)
        if (fieldNotEqual(check, numerator)) {
            field.multiply(output.x, output.x, Curve25519FieldArithmetic.SQRT_MINUS_ONE, temp)
        }

        field.square(check, output.x, temp)
        field.multiply(check, check, denominator, temp)
        if (fieldNotEqual(check, numerator)) {
            return -1
        }

        if (fieldParity(output.x) == ((publicKey[31].toInt() and 0xFF) ushr 7)) {
            field.subtract(output.x, Curve25519FieldArithmetic.FIELD_ZERO, output.x)
        }
        field.multiply(output.t, output.x, output.y, temp)
        return 0
    }

    /** Extracts the 4-bit digit covering bits `[4 * block, 4 * block + 3]` of `scalar`. */
    private fun nibbleAt(scalar: ByteArray, block: Int): Int {
        val byteValue = scalar[block ushr 1].toInt()
        return if (block and 1 == 0) byteValue and 0x0F else (byteValue ushr 4) and 0x0F
    }

    /**
     * Builds the fixed-base comb table: `table[block][digit]` = `digit * 16^block * B`, for `block`
     * in `0 until 64` and `digit` in `1 until 16` (digit 0 is never stored; selection treats it as
     * the identity). This runs once per process (see `baseCombTable`).
     */
    private fun buildBaseCombTable(): Array<Array<Point>> {
        val base =
            Point().also { point ->
                field.copyField(point.x, Curve25519FieldArithmetic.BASE_X)
                field.copyField(point.y, Curve25519FieldArithmetic.BASE_Y)
                field.copyField(point.z, Curve25519FieldArithmetic.FIELD_ONE)
                field.multiply(
                    point.t,
                    Curve25519FieldArithmetic.BASE_X,
                    Curve25519FieldArithmetic.BASE_Y,
                    LongArray(Curve25519FieldArithmetic.MULTIPLICATION_SCRATCH_SIZE),
                )
            }

        var blockBase = base
        return Array(COMB_BLOCK_COUNT) { block ->
            val addScratch = PointScratch()
            val row = arrayOfNulls<Point>(COMB_DIGIT_COUNT)
            row[1] = blockBase
            for (digit in 2 until COMB_DIGIT_COUNT) {
                val next = row[digit - 1]!!.copy()
                add(next, blockBase, addScratch)
                row[digit] = next
            }

            if (block != COMB_BLOCK_COUNT - 1) {
                val nextBlockBase = blockBase.copy()
                val dblScratch = PointScratch()
                repeat(WINDOW_BITS) { double(nextBlockBase, dblScratch) }
                blockBase = nextBlockBase
            }

            @Suppress("UNCHECKED_CAST") (row as Array<Point>)
        }
    }

    /**
     * Constant-time selection of `candidates[digit]` into `output`, where `digit` is in `0 until
     * 16` and `candidates[0]` is implicitly the identity point (not stored). Scans every candidate
     * and masks in the match so execution time and memory access pattern do not depend on `digit`.
     */
    private fun selectPoint(output: Point, candidates: Array<Point>, digit: Int): Unit {
        setIdentity(output)
        for (index in 1 until COMB_DIGIT_COUNT) {
            val mask = maskEquals(digit, index)
            conditionalCopy(output.x, candidates[index].x, mask)
            conditionalCopy(output.y, candidates[index].y, mask)
            conditionalCopy(output.z, candidates[index].z, mask)
            conditionalCopy(output.t, candidates[index].t, mask)
        }
    }

    /** Returns an all-ones mask when `value == target`, otherwise an all-zeros mask. */
    private fun maskEquals(value: Int, target: Int): Long {
        val diff = value xor target
        // `diff` is in [0, 15], so `diff - 1` is -1 (all bits set) exactly when diff == 0, and a
        // small non-negative number otherwise; sign-extending shift turns that into 0.
        return ((diff - 1) shr 31).toLong()
    }

    private fun conditionalCopy(destination: LongArray, source: LongArray, mask: Long): Unit {
        for (index in 0 until Curve25519FieldArithmetic.FIELD_ELEMENT_SIZE) {
            destination[index] = (destination[index] and mask.inv()) or (source[index] and mask)
        }
    }

    private fun setIdentity(point: Point): Unit {
        field.zeroField(point.x)
        field.copyField(point.y, Curve25519FieldArithmetic.FIELD_ONE)
        field.copyField(point.z, Curve25519FieldArithmetic.FIELD_ONE)
        field.zeroField(point.t)
    }

    private fun add(point: Point, other: Point, scratch: PointScratch): Unit {
        field.subtract(scratch.a, point.y, point.x)
        field.subtract(scratch.t, other.y, other.x)
        field.multiply(scratch.a, scratch.a, scratch.t, scratch.temp)
        field.add(scratch.b, point.x, point.y)
        field.add(scratch.t, other.x, other.y)
        field.multiply(scratch.b, scratch.b, scratch.t, scratch.temp)
        field.multiply(scratch.c, point.t, other.t, scratch.temp)
        field.multiply(scratch.c, scratch.c, Curve25519FieldArithmetic.D2, scratch.temp)
        field.multiply(scratch.d, point.z, other.z, scratch.temp)
        field.add(scratch.d, scratch.d, scratch.d)
        field.subtract(scratch.e, scratch.b, scratch.a)
        field.subtract(scratch.f, scratch.d, scratch.c)
        field.add(scratch.g, scratch.d, scratch.c)
        field.add(scratch.h, scratch.b, scratch.a)
        field.multiply(point.x, scratch.e, scratch.f, scratch.temp)
        field.multiply(point.y, scratch.h, scratch.g, scratch.temp)
        field.multiply(point.z, scratch.g, scratch.f, scratch.temp)
        field.multiply(point.t, scratch.e, scratch.h, scratch.temp)
    }

    /**
     * Dedicated point doubling (dbl-2008-hwcd) for the twisted Edwards curve with a = -1. This is
     * mathematically equivalent to `add(point, point, scratch)` but costs 4 squarings + 4
     * multiplications instead of the unified addition formula's 9 multiplications, which matters
     * because every scalar-multiplication bit performs one doubling.
     */
    private fun double(point: Point, scratch: PointScratch): Unit {
        field.square(scratch.a, point.x, scratch.temp)
        field.square(scratch.b, point.y, scratch.temp)
        field.square(scratch.c, point.z, scratch.temp)
        field.add(scratch.c, scratch.c, scratch.c)
        field.add(scratch.h, point.x, point.y)
        field.square(scratch.e, scratch.h, scratch.temp)
        field.subtract(scratch.e, scratch.e, scratch.a)
        field.subtract(scratch.e, scratch.e, scratch.b)
        // d = a * A = -A since a = -1
        field.subtract(scratch.d, Curve25519FieldArithmetic.FIELD_ZERO, scratch.a)
        field.add(scratch.g, scratch.d, scratch.b)
        field.subtract(scratch.f, scratch.g, scratch.c)
        field.subtract(scratch.h, scratch.d, scratch.b)
        field.multiply(point.x, scratch.e, scratch.f, scratch.temp)
        field.multiply(point.y, scratch.g, scratch.h, scratch.temp)
        field.multiply(point.t, scratch.e, scratch.h, scratch.temp)
        field.multiply(point.z, scratch.f, scratch.g, scratch.temp)
    }

    private fun fieldParity(input: LongArray): Int {
        val packed = ByteArray(PUBLIC_KEY_SIZE_BYTES)
        field.pack25519(packed, input)
        return packed[0].toInt() and 1
    }

    private fun fieldNotEqual(first: LongArray, second: LongArray): Boolean {
        val packedFirst = ByteArray(PUBLIC_KEY_SIZE_BYTES)
        val packedSecond = ByteArray(PUBLIC_KEY_SIZE_BYTES)
        field.pack25519(packedFirst, first)
        field.pack25519(packedSecond, second)
        return !constantTimeEquals(packedFirst, 0, packedSecond, 0, PUBLIC_KEY_SIZE_BYTES)
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

    private fun fieldElement(): LongArray {
        return Curve25519FieldArithmetic.fieldElement()
    }

    private companion object {
        private const val PUBLIC_KEY_SIZE_BYTES: Int = 32
        private const val SCALAR_SIZE_BYTES: Int = 32

        /** Window width (bits) for the radix-16 comb/windowed scalar multiplications. */
        private const val WINDOW_BITS: Int = 4

        /** Number of 4-bit windows covering a 256-bit scalar (32 bytes * 2 nibbles/byte). */
        private const val COMB_BLOCK_COUNT: Int = 64

        /** Number of representable digit values per window (0 until 16, digit 0 is implicit). */
        private const val COMB_DIGIT_COUNT: Int = 16
    }
}

internal class Point {
    val x: LongArray = Curve25519FieldArithmetic.fieldElement()
    val y: LongArray = Curve25519FieldArithmetic.fieldElement()
    val z: LongArray = Curve25519FieldArithmetic.fieldElement()
    val t: LongArray = Curve25519FieldArithmetic.fieldElement()

    fun copy(): Point {
        return Point().also { point ->
            x.copyInto(point.x)
            y.copyInto(point.y)
            z.copyInto(point.z)
            t.copyInto(point.t)
        }
    }
}

internal class PointScratch {
    val a: LongArray = Curve25519FieldArithmetic.fieldElement()
    val b: LongArray = Curve25519FieldArithmetic.fieldElement()
    val c: LongArray = Curve25519FieldArithmetic.fieldElement()
    val d: LongArray = Curve25519FieldArithmetic.fieldElement()
    val e: LongArray = Curve25519FieldArithmetic.fieldElement()
    val f: LongArray = Curve25519FieldArithmetic.fieldElement()
    val g: LongArray = Curve25519FieldArithmetic.fieldElement()
    val h: LongArray = Curve25519FieldArithmetic.fieldElement()
    val t: LongArray = Curve25519FieldArithmetic.fieldElement()
    val temp: LongArray = LongArray(Curve25519FieldArithmetic.MULTIPLICATION_SCRATCH_SIZE)
}
