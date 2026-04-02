@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.meshlink.util

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.darwin.COMPRESSION_ZLIB
import platform.darwin.compression_decode_buffer
import platform.darwin.compression_encode_buffer

/**
 * Apple compressor using libcompression's COMPRESSION_ZLIB which produces raw DEFLATE (RFC 1951).
 */
private class AppleCompressor : Compressor {
    override fun compress(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val bound = data.size + data.size / 10 + 12
        val dest = ByteArray(bound)
        val compressedSize = data.usePinned { srcPinned ->
            dest.usePinned { dstPinned ->
                compression_encode_buffer(
                    dstPinned.addressOf(0).reinterpret(),
                    bound.toULong(),
                    srcPinned.addressOf(0).reinterpret(),
                    data.size.toULong(),
                    null,
                    COMPRESSION_ZLIB
                )
            }
        }
        if (compressedSize == 0uL) return data
        return dest.copyOf(compressedSize.toInt())
    }

    override fun decompress(data: ByteArray, originalSize: Int): ByteArray {
        if (originalSize == 0) return ByteArray(0)
        val dest = ByteArray(originalSize)
        val decompressedSize = data.usePinned { srcPinned ->
            dest.usePinned { dstPinned ->
                compression_decode_buffer(
                    dstPinned.addressOf(0).reinterpret(),
                    originalSize.toULong(),
                    srcPinned.addressOf(0).reinterpret(),
                    data.size.toULong(),
                    null,
                    COMPRESSION_ZLIB
                )
            }
        }
        require(decompressedSize > 0u) { "decompression failed" }
        return dest.copyOf(decompressedSize.toInt())
    }
}

actual fun Compressor(): Compressor = AppleCompressor()
