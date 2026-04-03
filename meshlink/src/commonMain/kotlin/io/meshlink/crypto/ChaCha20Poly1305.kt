package io.meshlink.crypto

import io.meshlink.util.constantTimeEquals

/**
 * Pure Kotlin ChaCha20-Poly1305 AEAD implementation per RFC 8439.
 * Provides a fallback for platforms without JCA ChaCha20-Poly1305 support.
 *
 * ChaCha20 uses a 256-bit key, 96-bit nonce, and 32-bit block counter.
 * Poly1305 uses 130-bit arithmetic with 5 × 26-bit limbs in LongArray
 * to remain platform-independent (no java.math.BigInteger).
 */
internal object ChaCha20Poly1305 {

    private const val TAG_SIZE = 16

    // ---- Little-endian helpers ----

    private fun u8to32le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun u32toLE(v: Long, b: ByteArray, off: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }

    private fun leToInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun intToLE(v: Int, b: ByteArray, off: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }

    private fun longToLE(v: Long, b: ByteArray, off: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
        b[off + 4] = (v ushr 32).toByte()
        b[off + 5] = (v ushr 40).toByte()
        b[off + 6] = (v ushr 48).toByte()
        b[off + 7] = (v ushr 56).toByte()
    }

    // ---- ChaCha20 Quarter Round (RFC 8439 §2.1) ----

    internal fun quarterRound(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
        state[a] += state[b]
        state[d] = (state[d] xor state[a]).rotateLeft(16)
        state[c] += state[d]
        state[b] = (state[b] xor state[c]).rotateLeft(12)
        state[a] += state[b]
        state[d] = (state[d] xor state[a]).rotateLeft(8)
        state[c] += state[d]
        state[b] = (state[b] xor state[c]).rotateLeft(7)
    }

    // ---- ChaCha20 Block Function (RFC 8439 §2.3) ----

    internal fun chacha20Block(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val state = IntArray(16)
        // Constants: "expand 32-byte k"
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0..7) state[4 + i] = leToInt(key, i * 4)
        state[12] = counter
        for (i in 0..2) state[13 + i] = leToInt(nonce, i * 4)

        val working = state.copyOf()
        repeat(10) {
            // Column rounds
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            // Diagonal rounds
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
        }

        val out = ByteArray(64)
        for (i in 0..15) intToLE(working[i] + state[i], out, i * 4)
        return out
    }

    // ---- ChaCha20 Encryption (RFC 8439 §2.4) ----

    internal fun chacha20Encrypt(
        key: ByteArray,
        initialCounter: Int,
        nonce: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val result = ByteArray(data.size)
        var counter = initialCounter
        var offset = 0
        while (offset < data.size) {
            val keystream = chacha20Block(key, counter, nonce)
            val len = minOf(64, data.size - offset)
            for (i in 0 until len) {
                result[offset + i] = (data[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += 64
            counter++
        }
        return result
    }

    // ---- Poly1305 MAC (RFC 8439 §2.5) ----
    // 130-bit arithmetic using 5 × 26-bit limbs stored in Long.

    internal fun poly1305Mac(key: ByteArray, message: ByteArray): ByteArray {
        // Clamp r (key[0..15])
        val cr = key.copyOfRange(0, 16)
        cr[3] = (cr[3].toInt() and 15).toByte()
        cr[7] = (cr[7].toInt() and 15).toByte()
        cr[11] = (cr[11].toInt() and 15).toByte()
        cr[15] = (cr[15].toInt() and 15).toByte()
        cr[4] = (cr[4].toInt() and 252).toByte()
        cr[8] = (cr[8].toInt() and 252).toByte()
        cr[12] = (cr[12].toInt() and 252).toByte()

        // Extract r as 5 × 26-bit limbs (donna layout)
        val rt0 = u8to32le(cr, 0)
        val rt1 = u8to32le(cr, 4)
        val rt2 = u8to32le(cr, 8)
        val rt3 = u8to32le(cr, 12)
        val r0 = rt0 and 0x3FFFFFF
        val r1 = ((rt0 ushr 26) or (rt1 shl 6)) and 0x3FFFFFF
        val r2 = ((rt1 ushr 20) or (rt2 shl 12)) and 0x3FFFFFF
        val r3 = ((rt2 ushr 14) or (rt3 shl 18)) and 0x3FFFFFF
        val r4 = rt3 ushr 8

        // Precompute 5·r_i for modular reduction (2^130 ≡ 5 mod p)
        val r1x5 = r1 * 5
        val r2x5 = r2 * 5
        val r3x5 = r3 * 5
        val r4x5 = r4 * 5

        // s = key[16..31] kept as 4 × 32-bit words for final addition
        val s0 = u8to32le(key, 16)
        val s1 = u8to32le(key, 20)
        val s2 = u8to32le(key, 24)
        val s3 = u8to32le(key, 28)

        // Accumulator
        var h0 = 0L
        var h1 = 0L
        var h2 = 0L
        var h3 = 0L
        var h4 = 0L

        // Process message in 16-byte blocks
        var offset = 0
        while (offset < message.size) {
            val blockLen = minOf(16, message.size - offset)

            // Pad block to 17 bytes: data || 0x01 || zeros
            val buf = ByteArray(17)
            message.copyInto(buf, 0, offset, offset + blockLen)
            buf[blockLen] = 1

            val t0 = u8to32le(buf, 0)
            val t1 = u8to32le(buf, 4)
            val t2 = u8to32le(buf, 8)
            val t3 = u8to32le(buf, 12)
            val hibit = (buf[16].toLong() and 0xFF) shl 24

            h0 += t0 and 0x3FFFFFF
            h1 += ((t0 ushr 26) or (t1 shl 6)) and 0x3FFFFFF
            h2 += ((t1 ushr 20) or (t2 shl 12)) and 0x3FFFFFF
            h3 += ((t2 ushr 14) or (t3 shl 18)) and 0x3FFFFFF
            h4 += (t3 ushr 8) or hibit

            // h = (h * r) mod (2^130 - 5)
            val d0 = h0 * r0 + h1 * r4x5 + h2 * r3x5 + h3 * r2x5 + h4 * r1x5
            val d1 = h0 * r1 + h1 * r0 + h2 * r4x5 + h3 * r3x5 + h4 * r2x5
            val d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * r4x5 + h4 * r3x5
            val d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * r4x5
            val d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

            // Carry propagation
            var c: Long
            c = d0 ushr 26
            h0 = d0 and 0x3FFFFFF
            val d1c = d1 + c
            c = d1c ushr 26
            h1 = d1c and 0x3FFFFFF
            val d2c = d2 + c
            c = d2c ushr 26
            h2 = d2c and 0x3FFFFFF
            val d3c = d3 + c
            c = d3c ushr 26
            h3 = d3c and 0x3FFFFFF
            val d4c = d4 + c
            c = d4c ushr 26
            h4 = d4c and 0x3FFFFFF
            h0 += c * 5
            c = h0 ushr 26
            h0 = h0 and 0x3FFFFFF
            h1 += c

            offset += 16
        }

        // ---- Finalization ----

        // Full carry
        var c: Long
        c = h1 ushr 26
        h1 = h1 and 0x3FFFFFF
        h2 += c
        c = h2 ushr 26
        h2 = h2 and 0x3FFFFFF
        h3 += c
        c = h3 ushr 26
        h3 = h3 and 0x3FFFFFF
        h4 += c
        c = h4 ushr 26
        h4 = h4 and 0x3FFFFFF
        h0 += c * 5
        c = h0 ushr 26
        h0 = h0 and 0x3FFFFFF
        h1 += c

        // Compute g = h + 5 and conditionally select h or g = h − p
        var g0 = h0 + 5
        c = g0 ushr 26
        g0 = g0 and 0x3FFFFFF
        var g1 = h1 + c
        c = g1 ushr 26
        g1 = g1 and 0x3FFFFFF
        var g2 = h2 + c
        c = g2 ushr 26
        g2 = g2 and 0x3FFFFFF
        var g3 = h3 + c
        c = g3 ushr 26
        g3 = g3 and 0x3FFFFFF
        var g4 = h4 + c - (1L shl 26)

        // If h < p then g4 is negative; mask selects h. Otherwise g.
        val mask = g4 shr 63 // -1L when h < p, 0L when h >= p
        h0 = (h0 and mask) or (g0 and mask.inv())
        h1 = (h1 and mask) or (g1 and mask.inv())
        h2 = (h2 and mask) or (g2 and mask.inv())
        h3 = (h3 and mask) or (g3 and mask.inv())
        h4 = (h4 and mask) or (g4 and mask.inv())

        // Pack h into 4 × 32-bit words and add s mod 2^128
        var f0 = (h0 or (h1 shl 26)) and 0xFFFFFFFFL
        var f1 = ((h1 ushr 6) or (h2 shl 20)) and 0xFFFFFFFFL
        var f2 = ((h2 ushr 12) or (h3 shl 14)) and 0xFFFFFFFFL
        var f3 = ((h3 ushr 18) or (h4 shl 8)) and 0xFFFFFFFFL

        f0 += s0
        c = f0 ushr 32
        f0 = f0 and 0xFFFFFFFFL
        f1 += s1 + c
        c = f1 ushr 32
        f1 = f1 and 0xFFFFFFFFL
        f2 += s2 + c
        c = f2 ushr 32
        f2 = f2 and 0xFFFFFFFFL
        f3 += s3 + c
        f3 = f3 and 0xFFFFFFFFL

        val tag = ByteArray(TAG_SIZE)
        u32toLE(f0, tag, 0)
        u32toLE(f1, tag, 4)
        u32toLE(f2, tag, 8)
        u32toLE(f3, tag, 12)
        return tag
    }

    // ---- AEAD Construction (RFC 8439 §2.8) ----

    private fun buildMacData(aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val aadPad = if (aad.isEmpty()) 0 else (16 - aad.size % 16) % 16
        val ctPad = if (ciphertext.isEmpty()) 0 else (16 - ciphertext.size % 16) % 16
        val data = ByteArray(aad.size + aadPad + ciphertext.size + ctPad + 16)

        aad.copyInto(data, 0)
        ciphertext.copyInto(data, aad.size + aadPad)

        val lenOffset = aad.size + aadPad + ciphertext.size + ctPad
        longToLE(aad.size.toLong(), data, lenOffset)
        longToLE(ciphertext.size.toLong(), data, lenOffset + 8)
        return data
    }

    // Tag comparison delegated to io.meshlink.util.constantTimeEquals

    /**
     * Encrypts [plaintext] with ChaCha20-Poly1305 AEAD.
     *
     * @param key   32-byte encryption key
     * @param nonce 12-byte unique nonce
     * @param aad   additional authenticated data (may be empty)
     * @return ciphertext with 16-byte authentication tag appended
     */
    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes" }
        require(nonce.size == 12) { "Nonce must be 12 bytes" }

        // Poly1305 one-time key from ChaCha20 block 0
        val polyKey = chacha20Block(key, 0, nonce).copyOf(32)
        val ciphertext = chacha20Encrypt(key, 1, nonce, plaintext)
        val tag = poly1305Mac(polyKey, buildMacData(aad, ciphertext))
        return ciphertext + tag
    }

    /**
     * Decrypts ChaCha20-Poly1305 AEAD [ciphertextWithTag].
     *
     * @param key              32-byte encryption key
     * @param nonce            12-byte nonce used during encryption
     * @param ciphertextWithTag ciphertext with 16-byte tag appended
     * @param aad              additional authenticated data (must match encryption)
     * @return plaintext
     * @throws IllegalArgumentException if authentication fails
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes" }
        require(nonce.size == 12) { "Nonce must be 12 bytes" }
        require(ciphertextWithTag.size >= TAG_SIZE) { "Ciphertext too short" }

        val ct = ciphertextWithTag.copyOfRange(0, ciphertextWithTag.size - TAG_SIZE)
        val tag = ciphertextWithTag.copyOfRange(ciphertextWithTag.size - TAG_SIZE, ciphertextWithTag.size)

        val polyKey = chacha20Block(key, 0, nonce).copyOf(32)
        val computedTag = poly1305Mac(polyKey, buildMacData(aad, ct))

        if (!constantTimeEquals(tag, computedTag)) {
            throw IllegalArgumentException("AEAD authentication failed")
        }
        return chacha20Encrypt(key, 1, nonce, ct)
    }
}
