package io.meshlink.crypto

/**
 * Pure Kotlin X25519 (Curve25519 Diffie-Hellman) implementation per RFC 7748.
 * Provides a fallback for platforms without JCA X25519 support (Android API 26-32).
 *
 * All operations are constant-time to prevent timing side-channel attacks.
 *
 * Field elements are represented as LongArray(10) with alternating 26/25-bit limbs
 * following the standard ref10 representation.
 */
@Suppress("TooManyFunctions")
internal object X25519 {

    private val BASEPOINT = ByteArray(32).also { it[0] = 9 }

    /** Generate an X25519 key pair using platform-specific secure random. */
    fun generateKeyPair(): CryptoKeyPair {
        val privateKey = secureRandomBytes(32)
        clampPrivateKey(privateKey)
        val publicKey = scalarMult(privateKey, BASEPOINT)
        return CryptoKeyPair(publicKey = publicKey, privateKey = privateKey)
    }

    /**
     * Compute the X25519 shared secret from a private key and a peer's public key.
     */
    fun sharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        return scalarMult(privateKey, publicKey)
    }

    /**
     * X25519 scalar multiplication: compute scalar × point on Curve25519.
     * Both [scalar] and [point] must be 32 bytes (little-endian).
     * Returns a 32-byte result (little-endian).
     *
     * The scalar is clamped and the point's high bit is cleared per RFC 7748.
     */
    internal fun scalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
        require(scalar.size == 32) { "Scalar must be 32 bytes" }
        require(point.size == 32) { "Point must be 32 bytes" }

        // Decode scalar (clamp per RFC 7748 Section 5)
        val e = scalar.copyOf()
        e[0] = (e[0].toInt() and 248).toByte()
        e[31] = (e[31].toInt() and 127).toByte()
        e[31] = (e[31].toInt() or 64).toByte()

        // Decode u-coordinate (clear high bit)
        val p = point.copyOf()
        p[31] = (p[31].toInt() and 127).toByte()

        val u = LongArray(10)
        feUnpack(u, p)

        // Montgomery ladder
        val x2 = LongArray(10).also { it[0] = 1L }
        val z2 = LongArray(10)
        val x3 = u.copyOf()
        val z3 = LongArray(10).also { it[0] = 1L }

        val a = LongArray(10)
        val b = LongArray(10)
        val c = LongArray(10)
        val d = LongArray(10)
        val aa = LongArray(10)
        val bb = LongArray(10)
        val ev = LongArray(10)
        val da = LongArray(10)
        val cb = LongArray(10)
        val t = LongArray(10)

        var swap = 0

        for (pos in 254 downTo 0) {
            val bit = (e[pos shr 3].toInt() ushr (pos and 7)) and 1
            swap = swap xor bit
            feConditionalSwap(x2, x3, swap)
            feConditionalSwap(z2, z3, swap)
            swap = bit

            feAdd(a, x2, z2)
            feSub(b, x2, z2)
            feSquare(aa, a)
            feSquare(bb, b)
            feSub(ev, aa, bb)
            feAdd(c, x3, z3)
            feSub(d, x3, z3)
            feMul(da, d, a)
            feMul(cb, c, b)
            feAdd(t, da, cb)
            feSquare(x3, t)
            feSub(t, da, cb)
            feSquare(t, t)
            feMul(z3, u, t)
            feMul(x2, aa, bb)
            feMul121666(t, ev)
            feAdd(t, t, bb)
            feMul(z2, ev, t)
        }

        feConditionalSwap(x2, x3, swap)
        feConditionalSwap(z2, z3, swap)

        val z2Inv = LongArray(10)
        feInvert(z2Inv, z2)
        val result = LongArray(10)
        feMul(result, x2, z2Inv)

        val out = ByteArray(32)
        fePack(out, result)
        return out
    }

    // --- Private key clamping per RFC 7748 Section 5 ---

    private fun clampPrivateKey(key: ByteArray) {
        key[0] = (key[0].toInt() and 248).toByte()
        key[31] = (key[31].toInt() and 127).toByte()
        key[31] = (key[31].toInt() or 64).toByte()
    }

    // --- Field arithmetic over GF(2^255 - 19) ---

    private fun load4(b: ByteArray, off: Int): Long {
        return (b[off].toLong() and 0xffL) or
                ((b[off + 1].toLong() and 0xffL) shl 8) or
                ((b[off + 2].toLong() and 0xffL) shl 16) or
                ((b[off + 3].toLong() and 0xffL) shl 24)
    }

    private fun feUnpack(out: LongArray, b: ByteArray) {
        out[0] = load4(b, 0) and 0x3ffffffL
        out[1] = (load4(b, 3) shr 2) and 0x1ffffffL
        out[2] = (load4(b, 6) shr 3) and 0x3ffffffL
        out[3] = (load4(b, 9) shr 5) and 0x1ffffffL
        out[4] = (load4(b, 12) shr 6) and 0x3ffffffL
        out[5] = load4(b, 16) and 0x1ffffffL
        out[6] = (load4(b, 19) shr 1) and 0x3ffffffL
        out[7] = (load4(b, 22) shr 3) and 0x1ffffffL
        out[8] = (load4(b, 25) shr 4) and 0x3ffffffL
        out[9] = (load4(b, 28) shr 6) and 0x1ffffffL
    }

    private fun fePack(out: ByteArray, h: LongArray) {
        var h0 = h[0]; var h1 = h[1]; var h2 = h[2]; var h3 = h[3]; var h4 = h[4]
        var h5 = h[5]; var h6 = h[6]; var h7 = h[7]; var h8 = h[8]; var h9 = h[9]

        // Compute q = floor((h + 19) / (2^255)) to determine if we need to subtract p
        var q = (19L * h9 + (1L shl 24)) shr 25
        q = (h0 + q) shr 26
        q = (h1 + q) shr 25
        q = (h2 + q) shr 26
        q = (h3 + q) shr 25
        q = (h4 + q) shr 26
        q = (h5 + q) shr 25
        q = (h6 + q) shr 26
        q = (h7 + q) shr 25
        q = (h8 + q) shr 26
        q = (h9 + q) shr 25

        h0 += 19L * q

        var c: Long
        c = h0 shr 26; h1 += c; h0 -= c shl 26
        c = h1 shr 25; h2 += c; h1 -= c shl 25
        c = h2 shr 26; h3 += c; h2 -= c shl 26
        c = h3 shr 25; h4 += c; h3 -= c shl 25
        c = h4 shr 26; h5 += c; h4 -= c shl 26
        c = h5 shr 25; h6 += c; h5 -= c shl 25
        c = h6 shr 26; h7 += c; h6 -= c shl 26
        c = h7 shr 25; h8 += c; h7 -= c shl 25
        c = h8 shr 26; h9 += c; h8 -= c shl 26
        c = h9 shr 25;          h9 -= c shl 25

        out[0] = h0.toByte()
        out[1] = (h0 shr 8).toByte()
        out[2] = (h0 shr 16).toByte()
        out[3] = ((h0 shr 24) or (h1 shl 2)).toByte()
        out[4] = (h1 shr 6).toByte()
        out[5] = (h1 shr 14).toByte()
        out[6] = ((h1 shr 22) or (h2 shl 3)).toByte()
        out[7] = (h2 shr 5).toByte()
        out[8] = (h2 shr 13).toByte()
        out[9] = ((h2 shr 21) or (h3 shl 5)).toByte()
        out[10] = (h3 shr 3).toByte()
        out[11] = (h3 shr 11).toByte()
        out[12] = ((h3 shr 19) or (h4 shl 6)).toByte()
        out[13] = (h4 shr 2).toByte()
        out[14] = (h4 shr 10).toByte()
        out[15] = (h4 shr 18).toByte()
        out[16] = h5.toByte()
        out[17] = (h5 shr 8).toByte()
        out[18] = (h5 shr 16).toByte()
        out[19] = ((h5 shr 24) or (h6 shl 1)).toByte()
        out[20] = (h6 shr 7).toByte()
        out[21] = (h6 shr 15).toByte()
        out[22] = ((h6 shr 23) or (h7 shl 3)).toByte()
        out[23] = (h7 shr 5).toByte()
        out[24] = (h7 shr 13).toByte()
        out[25] = ((h7 shr 21) or (h8 shl 4)).toByte()
        out[26] = (h8 shr 4).toByte()
        out[27] = (h8 shr 12).toByte()
        out[28] = ((h8 shr 20) or (h9 shl 6)).toByte()
        out[29] = (h9 shr 2).toByte()
        out[30] = (h9 shr 10).toByte()
        out[31] = (h9 shr 18).toByte()
    }

    private fun feAdd(out: LongArray, a: LongArray, b: LongArray) {
        for (i in 0..9) out[i] = a[i] + b[i]
    }

    private fun feSub(out: LongArray, a: LongArray, b: LongArray) {
        for (i in 0..9) out[i] = a[i] - b[i]
    }

    private fun feConditionalSwap(f: LongArray, g: LongArray, b: Int) {
        val mask = (-b).toLong()
        for (i in 0..9) {
            val x = mask and (f[i] xor g[i])
            f[i] = f[i] xor x
            g[i] = g[i] xor x
        }
    }

    private fun feMul(out: LongArray, f: LongArray, g: LongArray) {
        val f0 = f[0]; val f1 = f[1]; val f2 = f[2]; val f3 = f[3]; val f4 = f[4]
        val f5 = f[5]; val f6 = f[6]; val f7 = f[7]; val f8 = f[8]; val f9 = f[9]
        val g0 = g[0]; val g1 = g[1]; val g2 = g[2]; val g3 = g[3]; val g4 = g[4]
        val g5 = g[5]; val g6 = g[6]; val g7 = g[7]; val g8 = g[8]; val g9 = g[9]

        val g1_19 = 19L * g1; val g2_19 = 19L * g2; val g3_19 = 19L * g3
        val g4_19 = 19L * g4; val g5_19 = 19L * g5; val g6_19 = 19L * g6
        val g7_19 = 19L * g7; val g8_19 = 19L * g8; val g9_19 = 19L * g9

        val f1_2 = 2L * f1; val f3_2 = 2L * f3; val f5_2 = 2L * f5
        val f7_2 = 2L * f7; val f9_2 = 2L * f9

        val h0 = f0*g0 + f1_2*g9_19 + f2*g8_19 + f3_2*g7_19 + f4*g6_19 +
                f5_2*g5_19 + f6*g4_19 + f7_2*g3_19 + f8*g2_19 + f9_2*g1_19
        val h1 = f0*g1 + f1*g0 + f2*g9_19 + f3*g8_19 + f4*g7_19 +
                f5*g6_19 + f6*g5_19 + f7*g4_19 + f8*g3_19 + f9*g2_19
        val h2 = f0*g2 + f1_2*g1 + f2*g0 + f3_2*g9_19 + f4*g8_19 +
                f5_2*g7_19 + f6*g6_19 + f7_2*g5_19 + f8*g4_19 + f9_2*g3_19
        val h3 = f0*g3 + f1*g2 + f2*g1 + f3*g0 + f4*g9_19 +
                f5*g8_19 + f6*g7_19 + f7*g6_19 + f8*g5_19 + f9*g4_19
        val h4 = f0*g4 + f1_2*g3 + f2*g2 + f3_2*g1 + f4*g0 +
                f5_2*g9_19 + f6*g8_19 + f7_2*g7_19 + f8*g6_19 + f9_2*g5_19
        val h5 = f0*g5 + f1*g4 + f2*g3 + f3*g2 + f4*g1 +
                f5*g0 + f6*g9_19 + f7*g8_19 + f8*g7_19 + f9*g6_19
        val h6 = f0*g6 + f1_2*g5 + f2*g4 + f3_2*g3 + f4*g2 +
                f5_2*g1 + f6*g0 + f7_2*g9_19 + f8*g8_19 + f9_2*g7_19
        val h7 = f0*g7 + f1*g6 + f2*g5 + f3*g4 + f4*g3 +
                f5*g2 + f6*g1 + f7*g0 + f8*g9_19 + f9*g8_19
        val h8 = f0*g8 + f1_2*g7 + f2*g6 + f3_2*g5 + f4*g4 +
                f5_2*g3 + f6*g2 + f7_2*g1 + f8*g0 + f9_2*g9_19
        val h9 = f0*g9 + f1*g8 + f2*g7 + f3*g6 + f4*g5 +
                f5*g4 + f6*g3 + f7*g2 + f8*g1 + f9*g0

        feCarryMul(out, h0, h1, h2, h3, h4, h5, h6, h7, h8, h9)
    }

    private fun feSquare(out: LongArray, f: LongArray) {
        val f0 = f[0]; val f1 = f[1]; val f2 = f[2]; val f3 = f[3]; val f4 = f[4]
        val f5 = f[5]; val f6 = f[6]; val f7 = f[7]; val f8 = f[8]; val f9 = f[9]

        val f0_2 = 2L * f0; val f1_2 = 2L * f1; val f2_2 = 2L * f2
        val f3_2 = 2L * f3; val f4_2 = 2L * f4; val f5_2 = 2L * f5
        val f6_2 = 2L * f6; val f7_2 = 2L * f7
        val f5_38 = 38L * f5; val f6_19 = 19L * f6; val f7_38 = 38L * f7
        val f8_19 = 19L * f8; val f9_38 = 38L * f9

        val h0 = f0*f0     + f1_2*f9_38 + f2_2*f8_19 + f3_2*f7_38 + f4_2*f6_19 + f5*f5_38
        val h1 = f0_2*f1   + f2*f9_38   + f3_2*f8_19 + f4*f7_38   + f5_2*f6_19
        val h2 = f0_2*f2   + f1_2*f1    + f3_2*f9_38 + f4_2*f8_19 + f5_2*f7_38 + f6*f6_19
        val h3 = f0_2*f3   + f1_2*f2    + f4*f9_38   + f5_2*f8_19 + f6*f7_38
        val h4 = f0_2*f4   + f1_2*f3_2  + f2*f2      + f5_2*f9_38 + f6_2*f8_19 + f7*f7_38
        val h5 = f0_2*f5   + f1_2*f4    + f2_2*f3    + f6*f9_38   + f7_2*f8_19
        val h6 = f0_2*f6   + f1_2*f5_2  + f2_2*f4    + f3_2*f3    + f7_2*f9_38 + f8*f8_19
        val h7 = f0_2*f7   + f1_2*f6    + f2_2*f5    + f3_2*f4    + f8*f9_38
        val h8 = f0_2*f8   + f1_2*f7_2  + f2_2*f6    + f3_2*f5_2  + f4*f4      + f9*f9_38
        val h9 = f0_2*f9   + f1_2*f8    + f2_2*f7    + f3_2*f6    + f4_2*f5

        feCarryMul(out, h0, h1, h2, h3, h4, h5, h6, h7, h8, h9)
    }

    @Suppress("LongParameterList")
    private fun feCarryMul(
        out: LongArray,
        h0In: Long, h1In: Long, h2In: Long, h3In: Long, h4In: Long,
        h5In: Long, h6In: Long, h7In: Long, h8In: Long, h9In: Long
    ) {
        var h0 = h0In; var h1 = h1In; var h2 = h2In; var h3 = h3In; var h4 = h4In
        var h5 = h5In; var h6 = h6In; var h7 = h7In; var h8 = h8In; var h9 = h9In

        var c: Long
        c = (h0 + (1L shl 25)) shr 26; h1 += c; h0 -= c shl 26
        c = (h4 + (1L shl 25)) shr 26; h5 += c; h4 -= c shl 26

        c = (h1 + (1L shl 24)) shr 25; h2 += c; h1 -= c shl 25
        c = (h5 + (1L shl 24)) shr 25; h6 += c; h5 -= c shl 25

        c = (h2 + (1L shl 25)) shr 26; h3 += c; h2 -= c shl 26
        c = (h6 + (1L shl 25)) shr 26; h7 += c; h6 -= c shl 26

        c = (h3 + (1L shl 24)) shr 25; h4 += c; h3 -= c shl 25
        c = (h7 + (1L shl 24)) shr 25; h8 += c; h7 -= c shl 25

        c = (h4 + (1L shl 25)) shr 26; h5 += c; h4 -= c shl 26
        c = (h8 + (1L shl 25)) shr 26; h9 += c; h8 -= c shl 26

        c = (h9 + (1L shl 24)) shr 25; h0 += c * 19L; h9 -= c shl 25
        c = (h0 + (1L shl 25)) shr 26; h1 += c; h0 -= c shl 26

        out[0] = h0; out[1] = h1; out[2] = h2; out[3] = h3; out[4] = h4
        out[5] = h5; out[6] = h6; out[7] = h7; out[8] = h8; out[9] = h9
    }

    private fun feMul121666(out: LongArray, a: LongArray) {
        var h0 = a[0] * 121666L
        var h1 = a[1] * 121666L
        var h2 = a[2] * 121666L
        var h3 = a[3] * 121666L
        var h4 = a[4] * 121666L
        var h5 = a[5] * 121666L
        var h6 = a[6] * 121666L
        var h7 = a[7] * 121666L
        var h8 = a[8] * 121666L
        var h9 = a[9] * 121666L

        var c: Long
        c = (h9 + (1L shl 24)) shr 25; h0 += c * 19L; h9 -= c shl 25
        c = (h0 + (1L shl 25)) shr 26; h1 += c; h0 -= c shl 26
        c = (h1 + (1L shl 24)) shr 25; h2 += c; h1 -= c shl 25
        c = (h2 + (1L shl 25)) shr 26; h3 += c; h2 -= c shl 26
        c = (h3 + (1L shl 24)) shr 25; h4 += c; h3 -= c shl 25
        c = (h4 + (1L shl 25)) shr 26; h5 += c; h4 -= c shl 26
        c = (h5 + (1L shl 24)) shr 25; h6 += c; h5 -= c shl 25
        c = (h6 + (1L shl 25)) shr 26; h7 += c; h6 -= c shl 26
        c = (h7 + (1L shl 24)) shr 25; h8 += c; h7 -= c shl 25
        c = (h8 + (1L shl 25)) shr 26; h9 += c; h8 -= c shl 26

        out[0] = h0; out[1] = h1; out[2] = h2; out[3] = h3; out[4] = h4
        out[5] = h5; out[6] = h6; out[7] = h7; out[8] = h8; out[9] = h9
    }

    private fun feInvert(out: LongArray, z: LongArray) {
        val t0 = LongArray(10)
        val t1 = LongArray(10)
        val t2 = LongArray(10)
        val t3 = LongArray(10)

        feSquare(t0, z)             // t0 = z^2
        feSquare(t1, t0)            // t1 = z^4
        feSquare(t1, t1)            // t1 = z^8
        feMul(t1, z, t1)            // t1 = z^9
        feMul(t0, t0, t1)           // t0 = z^11
        feSquare(t2, t0)            // t2 = z^22
        feMul(t1, t1, t2)           // t1 = z^(2^5 - 1)

        feSquare(t2, t1)            // t2 = z^(2^6 - 2)
        for (i in 1 until 5) feSquare(t2, t2)   // t2 = z^(2^10 - 2^5)
        feMul(t1, t2, t1)           // t1 = z^(2^10 - 1)

        feSquare(t2, t1)            // t2 = z^(2^11 - 2)
        for (i in 1 until 10) feSquare(t2, t2)  // t2 = z^(2^20 - 2^10)
        feMul(t2, t2, t1)           // t2 = z^(2^20 - 1)

        feSquare(t3, t2)
        for (i in 1 until 20) feSquare(t3, t3)  // t3 = z^(2^40 - 2^20)
        feMul(t2, t3, t2)           // t2 = z^(2^40 - 1)

        feSquare(t2, t2)
        for (i in 1 until 10) feSquare(t2, t2)  // t2 = z^(2^50 - 2^10)
        feMul(t1, t2, t1)           // t1 = z^(2^50 - 1)

        feSquare(t2, t1)
        for (i in 1 until 50) feSquare(t2, t2)  // t2 = z^(2^100 - 2^50)
        feMul(t2, t2, t1)           // t2 = z^(2^100 - 1)

        feSquare(t3, t2)
        for (i in 1 until 100) feSquare(t3, t3) // t3 = z^(2^200 - 2^100)
        feMul(t2, t3, t2)           // t2 = z^(2^200 - 1)

        feSquare(t2, t2)
        for (i in 1 until 50) feSquare(t2, t2)  // t2 = z^(2^250 - 2^50)
        feMul(t1, t2, t1)           // t1 = z^(2^250 - 1)

        feSquare(t1, t1)
        for (i in 1 until 5) feSquare(t1, t1)   // t1 = z^(2^255 - 2^5)
        feMul(out, t1, t0)          // out = z^(2^255 - 21) = z^(p-2)
    }
}
