package io.meshlink.crypto

/**
 * Field arithmetic in GF(2^255 - 19).
 *
 * Elements are represented as LongArray(10) with alternating 26/25-bit limbs:
 * limbs [0,2,4,6,8] carry 26 bits; limbs [1,3,5,7,9] carry 25 bits.
 *
 * Shared by both X25519 and Ed25519 implementations.
 */
internal object Field25519 {

    fun feZero(): LongArray = LongArray(10)

    fun feOne(): LongArray = LongArray(10).also { it[0] = 1L }

    fun feCopy(dst: LongArray, src: LongArray) {
        src.copyInto(dst)
    }

    fun feCopy(src: LongArray): LongArray = src.copyOf()

    fun feAdd(a: LongArray, b: LongArray): LongArray {
        val h = LongArray(10)
        for (i in 0..9) h[i] = a[i] + b[i]
        return h
    }

    fun feSub(a: LongArray, b: LongArray): LongArray {
        val h = LongArray(10)
        for (i in 0..9) h[i] = a[i] - b[i]
        return h
    }

    fun feNeg(f: LongArray): LongArray {
        val h = LongArray(10)
        for (i in 0..9) h[i] = -f[i]
        return h
    }

    /**
     * Multiply two field elements. Uses schoolbook multiplication
     * with 19-reduction for limbs above 2^255.
     */
    fun feMul(f: LongArray, g: LongArray): LongArray {
        val f0 = f[0]; val f1 = f[1]; val f2 = f[2]; val f3 = f[3]; val f4 = f[4]
        val f5 = f[5]; val f6 = f[6]; val f7 = f[7]; val f8 = f[8]; val f9 = f[9]
        val g0 = g[0]; val g1 = g[1]; val g2 = g[2]; val g3 = g[3]; val g4 = g[4]
        val g5 = g[5]; val g6 = g[6]; val g7 = g[7]; val g8 = g[8]; val g9 = g[9]
        val g1_19 = 19 * g1; val g2_19 = 19 * g2; val g3_19 = 19 * g3
        val g4_19 = 19 * g4; val g5_19 = 19 * g5; val g6_19 = 19 * g6
        val g7_19 = 19 * g7; val g8_19 = 19 * g8; val g9_19 = 19 * g9
        val f1_2 = 2 * f1; val f3_2 = 2 * f3; val f5_2 = 2 * f5
        val f7_2 = 2 * f7; val f9_2 = 2 * f9

        val h0 = f0*g0 + f1_2*g9_19 + f2*g8_19 + f3_2*g7_19 + f4*g6_19 + f5_2*g5_19 + f6*g4_19 + f7_2*g3_19 + f8*g2_19 + f9_2*g1_19
        val h1 = f0*g1 + f1*g0 + f2*g9_19 + f3*g8_19 + f4*g7_19 + f5*g6_19 + f6*g5_19 + f7*g4_19 + f8*g3_19 + f9*g2_19
        val h2 = f0*g2 + f1_2*g1 + f2*g0 + f3_2*g9_19 + f4*g8_19 + f5_2*g7_19 + f6*g6_19 + f7_2*g5_19 + f8*g4_19 + f9_2*g3_19
        val h3 = f0*g3 + f1*g2 + f2*g1 + f3*g0 + f4*g9_19 + f5*g8_19 + f6*g7_19 + f7*g6_19 + f8*g5_19 + f9*g4_19
        val h4 = f0*g4 + f1_2*g3 + f2*g2 + f3_2*g1 + f4*g0 + f5_2*g9_19 + f6*g8_19 + f7_2*g7_19 + f8*g6_19 + f9_2*g5_19
        val h5 = f0*g5 + f1*g4 + f2*g3 + f3*g2 + f4*g1 + f5*g0 + f6*g9_19 + f7*g8_19 + f8*g7_19 + f9*g6_19
        val h6 = f0*g6 + f1_2*g5 + f2*g4 + f3_2*g3 + f4*g2 + f5_2*g1 + f6*g0 + f7_2*g9_19 + f8*g8_19 + f9_2*g7_19
        val h7 = f0*g7 + f1*g6 + f2*g5 + f3*g4 + f4*g3 + f5*g2 + f6*g1 + f7*g0 + f8*g9_19 + f9*g8_19
        val h8 = f0*g8 + f1_2*g7 + f2*g6 + f3_2*g5 + f4*g4 + f5_2*g3 + f6*g2 + f7_2*g1 + f8*g0 + f9_2*g9_19
        val h9 = f0*g9 + f1*g8 + f2*g7 + f3*g6 + f4*g5 + f5*g4 + f6*g3 + f7*g2 + f8*g1 + f9*g0

        return feCarry(longArrayOf(h0, h1, h2, h3, h4, h5, h6, h7, h8, h9))
    }

    fun feSquare(f: LongArray): LongArray {
        val f0 = f[0]; val f1 = f[1]; val f2 = f[2]; val f3 = f[3]; val f4 = f[4]
        val f5 = f[5]; val f6 = f[6]; val f7 = f[7]; val f8 = f[8]; val f9 = f[9]
        val f0_2 = 2*f0; val f1_2 = 2*f1; val f2_2 = 2*f2; val f3_2 = 2*f3
        val f4_2 = 2*f4; val f5_2 = 2*f5; val f6_2 = 2*f6; val f7_2 = 2*f7
        val f5_38 = 38*f5; val f6_19 = 19*f6; val f7_38 = 38*f7; val f8_19 = 19*f8; val f9_38 = 38*f9

        val h0 = f0*f0 + f1_2*f9_38 + f2_2*f8_19 + f3_2*f7_38 + f4_2*f6_19 + f5*f5_38
        val h1 = f0_2*f1 + f2*f9_38 + f3_2*f8_19 + f4*f7_38 + f5_2*f6_19
        val h2 = f0_2*f2 + f1_2*f1 + f3_2*f9_38 + f4_2*f8_19 + f5_2*f7_38 + f6*f6_19
        val h3 = f0_2*f3 + f1_2*f2 + f4*f9_38 + f5_2*f8_19 + f6*f7_38
        val h4 = f0_2*f4 + f1_2*f3_2 + f2*f2 + f5_2*f9_38 + f6_2*f8_19 + f7*f7_38
        val h5 = f0_2*f5 + f1_2*f4 + f2_2*f3 + f6*f9_38 + f7_2*f8_19
        val h6 = f0_2*f6 + f1_2*f5_2 + f2_2*f4 + f3_2*f3 + f7_2*f9_38 + f8*f8_19
        val h7 = f0_2*f7 + f1_2*f6 + f2_2*f5 + f3_2*f4 + f8*f9_38
        val h8 = f0_2*f8 + f1_2*f7_2 + f2_2*f6 + f3_2*f5_2 + f4*f4 + f9*f9_38
        val h9 = f0_2*f9 + f1_2*f8 + f2_2*f7 + f3_2*f6 + f4_2*f5

        return feCarry(longArrayOf(h0, h1, h2, h3, h4, h5, h6, h7, h8, h9))
    }

    /**
     * Carry and reduce limbs so each fits in its designated bit-width.
     */
    private fun feCarry(h: LongArray): LongArray {
        var carry: Long
        for (i in 0..9) {
            val bits = if (i % 2 == 0) 26 else 25
            carry = (h[i] + (1L shl (bits - 1))) shr bits
            h[(i + 1) % 10] += if (i == 9) carry * 19 else carry
            h[i] -= carry shl bits
        }
        // Second pass needed for limb 0 after limb 9 carry
        carry = (h[0] + (1L shl 25)) shr 26
        h[1] += carry
        h[0] -= carry shl 26
        return h
    }

    /** Multiply field element by the constant 121666. */
    fun feMul121666(f: LongArray): LongArray {
        val h = LongArray(10)
        for (i in 0..9) h[i] = f[i] * 121666L
        return feCarry(h)
    }

    /**
     * Pack a field element into 32 bytes (little-endian canonical form).
     */
    fun fePack(h: LongArray): ByteArray {
        val t = h.copyOf()
        // Fully reduce modulo p
        feReduce(t)

        val s = ByteArray(32)
        s[0]  = (t[0]).toByte()
        s[1]  = (t[0] shr 8).toByte()
        s[2]  = (t[0] shr 16).toByte()
        s[3]  = ((t[0] shr 24) or (t[1] shl 2)).toByte()
        s[4]  = (t[1] shr 6).toByte()
        s[5]  = (t[1] shr 14).toByte()
        s[6]  = ((t[1] shr 22) or (t[2] shl 3)).toByte()
        s[7]  = (t[2] shr 5).toByte()
        s[8]  = (t[2] shr 13).toByte()
        s[9]  = ((t[2] shr 21) or (t[3] shl 5)).toByte()
        s[10] = (t[3] shr 3).toByte()
        s[11] = (t[3] shr 11).toByte()
        s[12] = ((t[3] shr 19) or (t[4] shl 6)).toByte()
        s[13] = (t[4] shr 2).toByte()
        s[14] = (t[4] shr 10).toByte()
        s[15] = (t[4] shr 18).toByte()
        s[16] = (t[5]).toByte()
        s[17] = (t[5] shr 8).toByte()
        s[18] = (t[5] shr 16).toByte()
        s[19] = ((t[5] shr 24) or (t[6] shl 1)).toByte()
        s[20] = (t[6] shr 7).toByte()
        s[21] = (t[6] shr 15).toByte()
        s[22] = ((t[6] shr 23) or (t[7] shl 3)).toByte()
        s[23] = (t[7] shr 5).toByte()
        s[24] = (t[7] shr 13).toByte()
        s[25] = ((t[7] shr 21) or (t[8] shl 4)).toByte()
        s[26] = (t[8] shr 4).toByte()
        s[27] = (t[8] shr 12).toByte()
        s[28] = ((t[8] shr 20) or (t[9] shl 6)).toByte()
        s[29] = (t[9] shr 2).toByte()
        s[30] = (t[9] shr 10).toByte()
        s[31] = (t[9] shr 18).toByte()
        return s
    }

    /**
     * Unpack 32 bytes (little-endian) into a field element.
     * Uses load4 to ensure enough bits are available for each limb.
     * The sign bit (bit 255 = MSB of byte 31) is masked off by the h[9] mask.
     */
    fun feUnpack(s: ByteArray): LongArray {
        fun load4(off: Int): Long =
            (s[off].toLong() and 0xff) or
            ((s[off+1].toLong() and 0xff) shl 8) or
            ((s[off+2].toLong() and 0xff) shl 16) or
            ((s[off+3].toLong() and 0xff) shl 24)

        val h = LongArray(10)
        h[0] = load4(0) and 0x3ffffff
        h[1] = (load4(3) shr 2) and 0x1ffffff
        h[2] = (load4(6) shr 3) and 0x3ffffff
        h[3] = (load4(9) shr 5) and 0x1ffffff
        h[4] = (load4(12) shr 6) and 0x3ffffff
        h[5] = load4(16) and 0x1ffffff
        h[6] = (load4(19) shr 1) and 0x3ffffff
        h[7] = (load4(22) shr 3) and 0x1ffffff
        h[8] = (load4(25) shr 4) and 0x3ffffff
        h[9] = (load4(28) shr 6) and 0x1ffffff
        return h
    }

    /**
     * Fully reduce modulo p = 2^255 - 19.
     */
    private fun feReduce(t: LongArray) {
        // Carry chain
        var carry: Long
        for (pass in 0..1) {
            for (i in 0..9) {
                val bits = if (i % 2 == 0) 26 else 25
                carry = t[i] shr bits
                t[(i + 1) % 10] += if (i == 9) carry * 19 else carry
                t[i] -= carry shl bits
            }
        }
        // Now reduce: if t >= p, subtract p
        // q = (t[0] + 19) >> 26 then propagate
        carry = (t[0] + 19) shr 26
        for (i in 1..9) {
            val bits = if (i % 2 == 0) 26 else 25
            carry = (t[i] + carry) shr bits
        }
        // carry is now 1 if t >= p, 0 otherwise
        t[0] += 19 * carry
        // propagate
        for (i in 0..9) {
            val bits = if (i % 2 == 0) 26 else 25
            val c = t[i] shr bits
            t[(i + 1) % 10] += if (i == 9) 0 else c
            t[i] -= c shl bits
        }
    }

    /**
     * Invert a field element: a^(p-2) mod p via Fermat's little theorem.
     * Exponent: p-2 = 2^255-21 = (2^250-1)*2^5 + 11.
     */
    fun feInvert(z: LongArray): LongArray {
        var t0 = feSquare(z)            // z^2
        var t1 = feSquare(t0)           // z^4
        t1 = feSquare(t1)               // z^8
        t1 = feMul(z, t1)              // z^9
        t0 = feMul(t0, t1)             // z^11
        var t2 = feSquare(t0)           // z^22
        t1 = feMul(t1, t2)             // z^(2^5 - 1)
        t2 = feSquare(t1)
        for (i in 1..4) t2 = feSquare(t2)
        t1 = feMul(t2, t1)             // z^(2^10 - 1)
        t2 = feSquare(t1)
        for (i in 1..9) t2 = feSquare(t2)
        t2 = feMul(t2, t1)             // z^(2^20 - 1)
        var t3 = feSquare(t2)
        for (i in 1..19) t3 = feSquare(t3)
        t2 = feMul(t3, t2)             // z^(2^40 - 1)
        t2 = feSquare(t2)
        for (i in 1..9) t2 = feSquare(t2)
        t1 = feMul(t2, t1)             // z^(2^50 - 1)
        t2 = feSquare(t1)
        for (i in 1..49) t2 = feSquare(t2)
        t2 = feMul(t2, t1)             // z^(2^100 - 1)
        t3 = feSquare(t2)
        for (i in 1..99) t3 = feSquare(t3)
        t2 = feMul(t3, t2)             // z^(2^200 - 1)
        t2 = feSquare(t2)
        for (i in 1..49) t2 = feSquare(t2)
        t1 = feMul(t2, t1)             // z^(2^250 - 1)
        t1 = feSquare(t1)
        for (i in 1..4) t1 = feSquare(t1) // z^(2^255 - 32)
        return feMul(t1, t0)            // z^(2^255 - 32 + 11) = z^(p-2)
    }

    /**
     * Compute z^((p+3)/8) = z^(2^252 - 3).
     * Used for Ed25519 point decompression (square root computation).
     */
    fun fePow22523(z: LongArray): LongArray {
        var t0 = feSquare(z)            // z^2
        var t1 = feSquare(t0)
        t1 = feSquare(t1)               // z^8
        t1 = feMul(z, t1)              // z^9
        t0 = feMul(t0, t1)             // z^11
        t0 = feSquare(t0)               // z^22
        t0 = feMul(t1, t0)             // z^(2^5 - 21) = z^31 -- actually z^(2^5-1)
        t1 = feSquare(t0)
        for (i in 1..4) t1 = feSquare(t1)
        t0 = feMul(t1, t0)             // z^(2^10 - 1)
        t1 = feSquare(t0)
        for (i in 1..9) t1 = feSquare(t1)
        t1 = feMul(t1, t0)             // z^(2^20 - 1)
        var t2 = feSquare(t1)
        for (i in 1..19) t2 = feSquare(t2)
        t1 = feMul(t2, t1)             // z^(2^40 - 1)
        t1 = feSquare(t1)
        for (i in 1..9) t1 = feSquare(t1)
        t0 = feMul(t1, t0)             // z^(2^50 - 1)
        t1 = feSquare(t0)
        for (i in 1..49) t1 = feSquare(t1)
        t1 = feMul(t1, t0)             // z^(2^100 - 1)
        t2 = feSquare(t1)
        for (i in 1..99) t2 = feSquare(t2)
        t1 = feMul(t2, t1)             // z^(2^200 - 1)
        t1 = feSquare(t1)
        for (i in 1..49) t1 = feSquare(t1)
        t0 = feMul(t1, t0)             // z^(2^250 - 1)
        t0 = feSquare(t0)
        t0 = feSquare(t0)
        return feMul(t0, z)             // z^(2^252 - 3)
    }

    fun feIsNegative(f: LongArray): Int {
        val s = fePack(f)
        return s[0].toInt() and 1
    }

    fun feIsZero(f: LongArray): Boolean {
        val s = fePack(f)
        var d = 0
        for (b in s) d = d or b.toInt()
        return d == 0
    }

    /** Constant-time conditional swap of f and g if b == 1. */
    fun feConditionalSwap(f: LongArray, g: LongArray, b: Int) {
        val mask = (-b).toLong()
        for (i in 0..9) {
            val x = mask and (f[i] xor g[i])
            f[i] = f[i] xor x
            g[i] = g[i] xor x
        }
    }

    /** Constant-time conditional move: f = g if b == 1. */
    fun feConditionalMove(f: LongArray, g: LongArray, b: Int) {
        val mask = (-b).toLong()
        for (i in 0..9) {
            f[i] = f[i] xor (mask and (f[i] xor g[i]))
        }
    }
}
