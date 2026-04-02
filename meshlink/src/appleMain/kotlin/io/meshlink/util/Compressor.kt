@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.meshlink.util

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDataCompressionAlgorithmZlib
import platform.Foundation.create

/**
 * Apple compressor using NSData's COMPRESSION_ZLIB which produces raw DEFLATE (RFC 1951).
 */
private class AppleCompressor : Compressor {
    override fun compress(data: ByteArray): ByteArray {
        val nsData = data.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = data.size.toULong())
        }
        val compressed = nsData.compressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, null)
            ?: return data
        return compressed.toByteArray()
    }

    override fun decompress(data: ByteArray, originalSize: Int): ByteArray {
        val nsData = data.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = data.size.toULong())
        }
        val decompressed = nsData.decompressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, null)
            ?: error("decompression failed")
        return decompressed.toByteArray()
    }

    private fun NSData.toByteArray(): ByteArray {
        val len = length.toInt()
        if (len == 0) return ByteArray(0)
        val result = ByteArray(len)
        result.usePinned { pinned ->
            val src = bytes
            if (src != null) {
                platform.posix.memcpy(pinned.addressOf(0), src, length)
            }
        }
        return result
    }
}

actual fun Compressor(): Compressor = AppleCompressor()
