package io.meshlink.crypto

/**
 * Pure Kotlin Ed25519 (EdDSA on edwards25519) per RFC 8032 Section 5.1.
 *
 * Curve: -x² + y² = 1 + d·x²·y²  where d = -121665/121666 mod p, p = 2²⁵⁵ - 19.
 * Uses extended coordinates (X:Y:Z:T) where x=X/Z, y=Y/Z, xy=T/Z.
 */
@Suppress("TooManyFunctions")
internal object Ed25519 {

    // ── Public API ──────────────────────────────────────────────────────

    fun generateKeyPair(): CryptoKeyPair {
        val privateKey = secureRandomBytes(32)
        val publicKey = publicKeyFromPrivate(privateKey)
        return CryptoKeyPair(publicKey = publicKey, privateKey = privateKey)
    }

    /**
     * Derive the Ed25519 public key from a 32-byte private key.
     */
    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Private key must be 32 bytes" }
        val h = sha512(privateKey)
        clampPrivateScalar(h)
        val a = h.copyOfRange(0, 32)
        val A = geScalarMultBase(a)
        return geToBytes(A)
    }

    /**
     * Sign a message per RFC 8032 Section 5.1.6.
     */
    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Private key must be 32 bytes" }
        val h = sha512(privateKey)
        clampPrivateScalar(h)
        val a = h.copyOfRange(0, 32)
        val prefix = h.copyOfRange(32, 64)

        val publicKey = geToBytes(geScalarMultBase(a))

        // r = SHA-512(prefix || message) mod L
        val rHash = sha512(prefix + message)
        val r = scReduce(rHash)

        // R = r * B
        val R = geScalarMultBase(r)
        val rBytes = geToBytes(R)

        // S = (r + SHA-512(R || A || M) * a) mod L
        val kHash = sha512(rBytes + publicKey + message)
        val k = scReduce(kHash)
        val s = scMulAdd(r, k, a)

        return rBytes + s
    }

    /**
     * Verify a signature per RFC 8032 Section 5.1.7.
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false

        // Reject if s >= L
        val sBytes = signature.copyOfRange(32, 64)
        if (!scIsCanonical(sBytes)) return false

        val A = try { geFromBytes(publicKey) } catch (_: Exception) { return false }

        val rBytes = signature.copyOfRange(0, 32)

        // k = SHA-512(R || A || M) mod L
        val kHash = sha512(rBytes + publicKey + message)
        val k = scReduce(kHash)

        // Check: [s]B = R + [k]A
        val sB = geScalarMultBase(sBytes)
        val kA = geScalarMult(k, A)
        val R2 = try { geFromBytes(rBytes) } catch (_: Exception) { return false }
        val rhs = geAdd(R2, kA)

        return geToBytes(sB).contentEquals(geToBytes(rhs))
    }

    // ── Curve constants ─────────────────────────────────────────────────

    /** d = -121665/121666 mod p */
    private val D: LongArray = Field25519.feUnpack(byteArrayOf(
        0xa3.toByte(), 0x78.toByte(), 0x59.toByte(), 0x13.toByte(),
        0xca.toByte(), 0x4d.toByte(), 0xeb.toByte(), 0x75.toByte(),
        0xab.toByte(), 0xd8.toByte(), 0x41.toByte(), 0x41.toByte(),
        0x4d.toByte(), 0x0a.toByte(), 0x70.toByte(), 0x00.toByte(),
        0x98.toByte(), 0xe8.toByte(), 0x79.toByte(), 0x77.toByte(),
        0x79.toByte(), 0x40.toByte(), 0xc7.toByte(), 0x8c.toByte(),
        0x73.toByte(), 0xfe.toByte(), 0x6f.toByte(), 0x2b.toByte(),
        0xee.toByte(), 0x6c.toByte(), 0x03.toByte(), 0x52.toByte(),
    ))

    /** Base point B encoded as 32 bytes */
    private val B_ENCODED = byteArrayOf(
        0x58.toByte(), 0x66.toByte(), 0x66.toByte(), 0x66.toByte(),
        0x66.toByte(), 0x66.toByte(), 0x66.toByte(), 0x66.toByte(),
        0x66.toByte(), 0x66.toByte(), 0x66.toByte(), 0x66.toByte(),
        0x66.toByte(), 0x66.toByte(), 0x66.toByte(), 0x66.toByte(),
        0x66.toByte(), 0x66.toByte(), 0x66.toByte(), 0x66.toByte(),
        0x66.toByte(), 0x66.toByte(), 0x66.toByte(), 0x66.toByte(),
        0x66.toByte(), 0x66.toByte(), 0x66.toByte(), 0x66.toByte(),
        0x66.toByte(), 0x66.toByte(), 0x66.toByte(), 0x66.toByte(),
    )

    /** Base point B (precomputed once) */
    private val B: GeP3 by lazy { geFromBytes(B_ENCODED) }

    // ── Point operations (affine coordinates for correctness) ──────────

    /** Extended point (X:Y:Z:T) where x=X/Z, y=Y/Z, T=X*Y/Z */
    private class GeP3(
        val x: LongArray,
        val y: LongArray,
        val z: LongArray,
        val t: LongArray,
    )

    /** Identity point (0, 1, 1, 0) */
    private fun geIdentity(): GeP3 = GeP3(
        Field25519.feZero(),
        Field25519.feOne(),
        Field25519.feOne(),
        Field25519.feZero(),
    )

    /**
     * Point addition on -x²+y²=1+dx²y² using the unified formula.
     * x3 = (x1*y2 + y1*x2) / (1 + d*x1*x2*y1*y2)
     * y3 = (y1*y2 + x1*x2) / (1 - d*x1*x2*y1*y2)
     */
    private fun geAdd(p: GeP3, q: GeP3): GeP3 {
        val pRecip = Field25519.feInvert(p.z)
        val px = Field25519.feMul(p.x, pRecip)
        val py = Field25519.feMul(p.y, pRecip)

        val qRecip = Field25519.feInvert(q.z)
        val qx = Field25519.feMul(q.x, qRecip)
        val qy = Field25519.feMul(q.y, qRecip)

        val x1y2 = Field25519.feMul(px, qy)
        val y1x2 = Field25519.feMul(py, qx)
        val x1x2 = Field25519.feMul(px, qx)
        val y1y2 = Field25519.feMul(py, qy)
        val dxy = Field25519.feMul(D, Field25519.feMul(x1x2, y1y2))
        val one = Field25519.feOne()

        val x3num = Field25519.feAdd(x1y2, y1x2)
        val x3den = Field25519.feAdd(one, dxy)
        val y3num = Field25519.feAdd(y1y2, x1x2)
        val y3den = Field25519.feSub(one, dxy)

        val x3 = Field25519.feMul(x3num, Field25519.feInvert(x3den))
        val y3 = Field25519.feMul(y3num, Field25519.feInvert(y3den))

        return GeP3(x3, y3, Field25519.feOne(), Field25519.feMul(x3, y3))
    }

    /**
     * Variable-base scalar multiplication: scalar * point.
     */
    private fun geScalarMult(scalar: ByteArray, point: GeP3): GeP3 {
        var result = geIdentity()
        var temp = point

        for (i in 0 until 256) {
            val byteIndex = i / 8
            val bitIndex = i % 8
            if (byteIndex < scalar.size) {
                val bit = (scalar[byteIndex].toInt() shr bitIndex) and 1
                if (bit == 1) {
                    result = geAdd(result, temp)
                }
            }
            temp = geAdd(temp, temp) // doubling via self-addition
        }
        return result
    }

    /**
     * Fixed-base scalar multiplication: scalar * B.
     */
    private fun geScalarMultBase(scalar: ByteArray): GeP3 =
        geScalarMult(scalar, B)

    /**
     * Encode a point to 32 bytes: y-coordinate with sign bit of x in MSB.
     */
    private fun geToBytes(p: GeP3): ByteArray {
        val recip = Field25519.feInvert(p.z)
        val x = Field25519.feMul(p.x, recip)
        val y = Field25519.feMul(p.y, recip)
        val s = Field25519.fePack(y)
        s[31] = (s[31].toInt() xor (Field25519.feIsNegative(x) shl 7)).toByte()
        return s
    }

    /**
     * Decode 32 bytes to an extended point, with validation.
     */
    private fun geFromBytes(s: ByteArray): GeP3 {
        require(s.size == 32)
        val y = Field25519.feUnpack(s)
        val z = Field25519.feOne()
        val y2 = Field25519.feSquare(y)
        val v = Field25519.feAdd(Field25519.feMul(y2, D), z)   // dy²+1
        val u = Field25519.feSub(y2, z)                         // y²-1

        val v3 = Field25519.feMul(Field25519.feSquare(v), v)
        val v7 = Field25519.feMul(Field25519.feSquare(v3), v)
        val uv7 = Field25519.feMul(u, v7)
        val uv3 = Field25519.feMul(u, v3)

        // x = u * v^3 * (u * v^7)^((p-5)/8)
        var x = Field25519.feMul(uv3, Field25519.fePow22523(uv7))

        val check = Field25519.feMul(Field25519.feSquare(x), v)
        if (!feEqual(check, u)) {
            // Try multiplying x by sqrt(-1)
            x = Field25519.feMul(x, SQRTM1)
            val check2 = Field25519.feMul(Field25519.feSquare(x), v)
            require(feEqual(check2, u)) { "Invalid Ed25519 point encoding" }
        }

        val xIsNeg = Field25519.feIsNegative(x)
        val signBit = (s[31].toInt() shr 7) and 1
        if (xIsNeg != signBit) {
            x = Field25519.feNeg(x)
        }

        // Reject x == 0 with sign bit set
        if (Field25519.feIsZero(x) && signBit == 1) {
            throw IllegalArgumentException("Invalid Ed25519 point: x=0 with sign bit")
        }

        val t = Field25519.feMul(x, y)
        return GeP3(x, y, z, t)
    }

    private fun feEqual(a: LongArray, b: LongArray): Boolean {
        val pa = Field25519.fePack(a)
        val pb = Field25519.fePack(b)
        return pa.contentEquals(pb)
    }

    /** sqrt(-1) mod p = 2^((p-1)/4) mod p */
    private val SQRTM1: LongArray = Field25519.feUnpack(byteArrayOf(
        0xb0.toByte(), 0xa0.toByte(), 0x0e.toByte(), 0x4a.toByte(),
        0x27.toByte(), 0x1b.toByte(), 0xee.toByte(), 0xc4.toByte(),
        0x78.toByte(), 0xe4.toByte(), 0x2f.toByte(), 0xad.toByte(),
        0x06.toByte(), 0x18.toByte(), 0x43.toByte(), 0x2f.toByte(),
        0xa7.toByte(), 0xd7.toByte(), 0xfb.toByte(), 0x3d.toByte(),
        0x99.toByte(), 0x00.toByte(), 0x4d.toByte(), 0x2b.toByte(),
        0x0b.toByte(), 0xdf.toByte(), 0xc1.toByte(), 0x4f.toByte(),
        0x80.toByte(), 0x24.toByte(), 0x83.toByte(), 0x2b.toByte(),
    ))

    // ── Scalar operations mod L ─────────────────────────────────────────

    /** L = 2^252 + 27742317777372353535851937790883648493 */
    private val L = byteArrayOf(
        0xed.toByte(), 0xd3.toByte(), 0xf5.toByte(), 0x5c.toByte(),
        0x1a.toByte(), 0x63.toByte(), 0x12.toByte(), 0x58.toByte(),
        0xd6.toByte(), 0x9c.toByte(), 0xf7.toByte(), 0xa2.toByte(),
        0xde.toByte(), 0xf9.toByte(), 0xde.toByte(), 0x14.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x10.toByte(),
    )

    /**
     * Check that s < L (the curve order).
     */
    private fun scIsCanonical(s: ByteArray): Boolean {
        if (s.size != 32) return false
        // Check s[31] high bits
        if (s[31].toInt() and 0xf0 != 0) return false
        // Compare from most significant byte
        for (i in 31 downTo 0) {
            val sb = s[i].toInt() and 0xff
            val lb = L[i].toInt() and 0xff
            if (sb < lb) return true
            if (sb > lb) return false
        }
        return false // s == L is not canonical
    }

    /**
     * Reduce a 64-byte scalar modulo L.
     * Returns a 32-byte reduced scalar.
     */
    private fun scReduce(s: ByteArray): ByteArray {
        require(s.size == 64)
        val n = bytesToBigVal(s)
        val result = bigMod(n, L_BIG)
        return bigToBytes32(result)
    }

    /**
     * Compute (a + b*c) mod L where a, b, c are 32-byte little-endian scalars.
     * Returns a 32-byte result.
     */
    private fun scMulAdd(a: ByteArray, b: ByteArray, c: ByteArray): ByteArray {
        val aVal = bytesToBigVal(a)
        val bVal = bytesToBigVal(b)
        val cVal = bytesToBigVal(c)
        // result = (a + b * c) mod L
        val product = bigMul(bVal, cVal)
        val sum = bigAdd(aVal, product)
        val result = bigMod(sum, L_BIG)
        return bigToBytes32(result)
    }

    // ── Big integer arithmetic (unsigned, little-endian LongArray) ────

    /** L = 2^252 + 27742317777372353535851937790883648493, as a big integer */
    private val L_BIG: LongArray by lazy {
        bytesToBigVal(L.copyOf().let { ByteArray(64).also { dst -> it.copyInto(dst) } })
    }

    /** Convert little-endian byte array to LongArray (16-bit digits, unsigned) */
    private fun bytesToBigVal(b: ByteArray): LongArray {
        val digits = LongArray((b.size + 1) / 2 + 1)
        for (i in b.indices) {
            digits[i / 2] = digits[i / 2] or ((b[i].toLong() and 0xff) shl ((i % 2) * 8))
        }
        return digits
    }

    /** Convert big integer to 32-byte little-endian array */
    private fun bigToBytes32(a: LongArray): ByteArray {
        val out = ByteArray(32)
        for (i in 0 until 32) {
            out[i] = ((a[i / 2] shr ((i % 2) * 8)) and 0xff).toByte()
        }
        return out
    }

    /** Add two big integers */
    private fun bigAdd(a: LongArray, b: LongArray): LongArray {
        val size = maxOf(a.size, b.size) + 1
        val r = LongArray(size)
        var carry = 0L
        for (i in 0 until size) {
            val sum = (if (i < a.size) a[i] else 0L) + (if (i < b.size) b[i] else 0L) + carry
            r[i] = sum and 0xffff
            carry = sum shr 16
        }
        return r
    }

    /** Multiply two big integers (schoolbook) */
    private fun bigMul(a: LongArray, b: LongArray): LongArray {
        val r = LongArray(a.size + b.size)
        for (i in a.indices) {
            if (a[i] == 0L) continue
            var carry = 0L
            for (j in b.indices) {
                val prod = a[i] * b[j] + r[i + j] + carry
                r[i + j] = prod and 0xffff
                carry = prod shr 16
            }
            if (carry != 0L) r[i + b.size] += carry
        }
        return r
    }

    /** Compare big integers: -1 if a<b, 0 if equal, 1 if a>b */
    private fun bigCmp(a: LongArray, b: LongArray): Int {
        val size = maxOf(a.size, b.size)
        for (i in size - 1 downTo 0) {
            val av = if (i < a.size) a[i] else 0L
            val bv = if (i < b.size) b[i] else 0L
            if (av < bv) return -1
            if (av > bv) return 1
        }
        return 0
    }

    /** Subtract b from a (assumes a >= b) */
    private fun bigSub(a: LongArray, b: LongArray): LongArray {
        val r = LongArray(a.size)
        var borrow = 0L
        for (i in r.indices) {
            val diff = a[i] - (if (i < b.size) b[i] else 0L) - borrow
            if (diff < 0) {
                r[i] = diff + 0x10000
                borrow = 1
            } else {
                r[i] = diff
                borrow = 0
            }
        }
        return r
    }

    /** Modulo: a mod m (simple repeated subtraction with shift) */
    private fun bigMod(a: LongArray, m: LongArray): LongArray {
        // Find the highest non-zero digit of a
        var aLen = a.size
        while (aLen > 0 && a[aLen - 1] == 0L) aLen--
        if (aLen == 0) return LongArray(m.size)

        var mLen = m.size
        while (mLen > 0 && m[mLen - 1] == 0L) mLen--

        var remainder = a.copyOf()
        val shift = aLen - mLen
        if (shift < 0) return remainder.copyOf(m.size)

        // Shift m left and repeatedly subtract
        for (s in shift downTo 0) {
            val shifted = LongArray(s + m.size)
            m.copyInto(shifted, s)
            while (bigCmp(remainder, shifted) >= 0) {
                remainder = bigSub(remainder, shifted)
            }
        }
        return remainder.copyOf(maxOf(remainder.size, m.size))
    }

    // ── Private key clamping ────────────────────────────────────────────

    private fun clampPrivateScalar(h: ByteArray) {
        h[0] = (h[0].toInt() and 248).toByte()
        h[31] = (h[31].toInt() and 127).toByte()
        h[31] = (h[31].toInt() or 64).toByte()
    }

    // ── SHA-512 (standalone implementation) ─────────────────────────────

    private val SHA512_K = longArrayOf(
        0x428a2f98d728ae22, 0x7137449123ef65cd, -0x4a3f043013b2c4d1, -0x164a245a7e762444,
        0x3956c25bf348b538, 0x59f111f1b605d019, -0x6dc07d5b50e6b065, -0x54e3a12a25927ee8,
        -0x27f855675cfcfdbe, 0x12835b0145706fbe, 0x243185be4ee4b28c, 0x550c7dc3d5ffb4e2,
        0x72be5d74f27b896f, -0x7f214e01c4e9694f, -0x6423f958da38edcb, -0x3e640e8b3096d96c,
        -0x1b64963e610eb52e, -0x1041b879c7b0da1d, 0x0fc19dc68b8cd5b5, 0x240ca1cc77ac9c65,
        0x2de92c6f592b0275, 0x4a7484aa6ea6e483, 0x5cb0a9dcbd41fbd4, 0x76f988da831153b5,
        -0x67c1aead11992055, -0x57ce3992d24bcdf0, -0x4ffcd8376704dec1, -0x40a680384110f11c,
        -0x391ff40cc257703e, -0x2a586eb86cf558db, 0x06ca6351e003826f, 0x142929670a0e6e70,
        0x27b70a8546d22ffc, 0x2e1b21385c26c926, 0x4d2c6dfc5ac42aed, 0x53380d139d95b3df,
        0x650a73548baf63de, 0x766a0abb3c77b2a8, -0x7e3d36d1b812511a, -0x6d8dd37aeb7dcac5,
        -0x5d40175eb30efc9c, -0x57e599b443bdcfff, -0x3db4748f2f07686f, -0x3893ae5cf9ab41d0,
        -0x2e6d17e62910ade8, -0x2966f9dbaa9a56f0, -0x0bf1ca7aa88edfd6, 0x106aa07032bbd1b8,
        0x19a4c116b8d2d0c8, 0x1e376c085141ab53, 0x2748774cdf8eeb99, 0x34b0bcb5e19b48a8,
        0x391c0cb3c5c95a63, 0x4ed8aa4ae3418acb, 0x5b9cca4f7763e373, 0x682e6ff3d6b2b8a3,
        0x748f82ee5defb2fc, 0x78a5636f43172f60, -0x7b3787eb5e0f548e, -0x7338fdf7e59bc614,
        -0x6f410005dc9ce1d8, -0x5baf9314217d4217, -0x41065c084d3986eb, -0x398e870d1c8dacd5,
        -0x35d8c13115d99e64, -0x2e794738de3f3df9, -0x15258229321f14e2, -0x0a82b08011912e88,
        0x06f067aa72176fba, 0x0a637dc5a2c898a6, 0x113f9804bef90dae, 0x1b710b35131c471b,
        0x28db77f523047d84, 0x32caab7b40c72493, 0x3c9ebe0a15c9bebc, 0x431d67c49c100d4c,
        0x4cc5d4becb3e42b6, 0x597f299cfc657e2a, 0x5fcb6fab3ad6faec, 0x6c44198c4a475817,
    )

    private val SHA512_IV = longArrayOf(
        0x6a09e667f3bcc908, -0x4498517a7b3558c5, 0x3c6ef372fe94f82b, -0x5ab00ac5a0e2c90f,
        0x510e527fade682d1, -0x64fa9773d4c193e1, 0x1f83d9abfb41bd6b, 0x5be0cd19137e2179,
    )

    fun sha512(data: ByteArray): ByteArray {
        // Pre-processing: add padding
        val ml = data.size.toLong() * 8  // message length in bits
        // pad to 896 mod 1024 bits (112 mod 128 bytes)
        val padLen = (112 - (data.size + 1) % 128 + 128) % 128 + 1
        val padded = ByteArray(data.size + padLen + 16)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()
        // Append 128-bit length (big-endian), but message length fits in 64 bits
        for (i in 0..7) {
            padded[padded.size - 8 + i] = (ml shr (56 - i * 8)).toByte()
        }

        val h = SHA512_IV.copyOf()
        val w = LongArray(80)

        for (blockStart in padded.indices step 128) {
            // Parse block into 16 64-bit words
            for (j in 0..15) {
                val off = blockStart + j * 8
                w[j] = 0L
                for (k in 0..7) {
                    w[j] = (w[j] shl 8) or (padded[off + k].toLong() and 0xff)
                }
            }
            // Extend to 80 words
            for (j in 16..79) {
                val s0 = w[j-15].rotateRight(1) xor w[j-15].rotateRight(8) xor (w[j-15] ushr 7)
                val s1 = w[j-2].rotateRight(19) xor w[j-2].rotateRight(61) xor (w[j-2] ushr 6)
                w[j] = w[j-16] + s0 + w[j-7] + s1
            }

            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]

            for (j in 0..79) {
                val s1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + ch + SHA512_K[j] + w[j]
                val s0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj

                hh = g; g = f; f = e; e = d + temp1
                d = c; c = b; b = a; a = temp1 + temp2
            }

            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }

        val digest = ByteArray(64)
        for (i in 0..7) {
            for (j in 0..7) {
                digest[i * 8 + j] = (h[i] shr (56 - j * 8)).toByte()
            }
        }
        return digest
    }

    private fun Long.rotateRight(n: Int): Long = (this ushr n) or (this shl (64 - n))
}
