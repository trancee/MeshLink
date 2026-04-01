package io.meshlink.util

/**
 * Platform-specific DEFLATE compression using zlib format (RFC 1950).
 * All platforms must produce the same wire-compatible format.
 */
interface Compressor {
    fun compress(data: ByteArray): ByteArray
    fun decompress(data: ByteArray, originalSize: Int): ByteArray
}

expect fun Compressor(): Compressor
