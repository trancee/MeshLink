package io.meshlink.util

/**
 * Platform-specific raw DEFLATE compression (RFC 1951).
 *
 * Produces raw DEFLATE streams without the zlib header/trailer — the Adler-32
 * checksum is redundant because payloads are protected by AEAD (ChaCha20-Poly1305).
 * Saves 6 bytes per compressed message compared to zlib-wrapped format.
 */
interface Compressor {
    fun compress(data: ByteArray): ByteArray
    fun decompress(data: ByteArray, originalSize: Int): ByteArray
}

expect fun Compressor(): Compressor
