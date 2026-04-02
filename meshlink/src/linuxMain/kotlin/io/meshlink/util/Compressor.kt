@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.meshlink.util

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import zlib.Z_BEST_SPEED
import zlib.Z_DEFAULT_STRATEGY
import zlib.Z_DEFLATED
import zlib.Z_FINISH
import zlib.Z_OK
import zlib.Z_STREAM_END
import zlib.compressBound
import zlib.deflate
import zlib.deflateEnd
import zlib.deflateInit2_
import zlib.inflate
import zlib.inflateEnd
import zlib.inflateInit2_
import zlib.z_stream
import zlib.zlibVersion

private const val RAW_DEFLATE_WINDOW_BITS = -15
private const val DEFAULT_MEM_LEVEL = 8

private class LinuxCompressor : Compressor {
    override fun compress(data: ByteArray): ByteArray {
        val bound = compressBound(data.size.toULong()).toInt()
        val dest = ByteArray(bound)
        memScoped {
            val stream = alloc<z_stream>()
            val initResult = deflateInit2_(
                stream.ptr,
                Z_BEST_SPEED,
                Z_DEFLATED,
                RAW_DEFLATE_WINDOW_BITS,
                DEFAULT_MEM_LEVEL,
                Z_DEFAULT_STRATEGY,
                zlibVersion(),
                sizeOf<z_stream>().toInt()
            )
            require(initResult == Z_OK) { "deflateInit2 failed: $initResult" }
            data.usePinned { srcPinned ->
                dest.usePinned { destPinned ->
                    stream.next_in = srcPinned.addressOf(0).reinterpret()
                    stream.avail_in = data.size.toUInt()
                    stream.next_out = destPinned.addressOf(0).reinterpret()
                    stream.avail_out = bound.toUInt()
                    val result = deflate(stream.ptr, Z_FINISH)
                    require(result == Z_STREAM_END) { "deflate failed: $result" }
                }
            }
            val compressedLen = stream.total_out.toInt()
            deflateEnd(stream.ptr)
            return dest.copyOf(compressedLen)
        }
    }

    override fun decompress(data: ByteArray, originalSize: Int): ByteArray {
        val dest = ByteArray(originalSize)
        memScoped {
            val stream = alloc<z_stream>()
            val initResult = inflateInit2_(
                stream.ptr,
                RAW_DEFLATE_WINDOW_BITS,
                zlibVersion(),
                sizeOf<z_stream>().toInt()
            )
            require(initResult == Z_OK) { "inflateInit2 failed: $initResult" }
            data.usePinned { srcPinned ->
                dest.usePinned { destPinned ->
                    stream.next_in = srcPinned.addressOf(0).reinterpret()
                    stream.avail_in = data.size.toUInt()
                    stream.next_out = destPinned.addressOf(0).reinterpret()
                    stream.avail_out = originalSize.toUInt()
                    val result = inflate(stream.ptr, Z_FINISH)
                    require(result == Z_STREAM_END) { "inflate failed: $result" }
                }
            }
            val decompressedLen = stream.total_out.toInt()
            inflateEnd(stream.ptr)
            require(decompressedLen == originalSize) {
                "decompressed $decompressedLen bytes, expected $originalSize"
            }
            return dest
        }
    }
}

actual fun Compressor(): Compressor = LinuxCompressor()
