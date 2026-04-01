@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.meshlink.util

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import zlib.Z_OK
import zlib.compress
import zlib.compressBound
import zlib.uLongfVar
import zlib.uncompress

private class LinuxCompressor : Compressor {
    override fun compress(data: ByteArray): ByteArray {
        val bound = compressBound(data.size.toULong()).toInt()
        val dest = ByteArray(bound)
        memScoped {
            val destLen = alloc<uLongfVar>()
            destLen.value = bound.toULong()
            val result = data.usePinned { srcPinned ->
                dest.usePinned { destPinned ->
                    compress(
                        destPinned.addressOf(0).reinterpret(),
                        destLen.ptr,
                        srcPinned.addressOf(0).reinterpret(),
                        data.size.toULong()
                    )
                }
            }
            require(result == Z_OK) { "zlib compress failed: $result" }
            return dest.copyOf(destLen.value.toInt())
        }
    }

    override fun decompress(data: ByteArray, originalSize: Int): ByteArray {
        val dest = ByteArray(originalSize)
        memScoped {
            val destLen = alloc<uLongfVar>()
            destLen.value = originalSize.toULong()
            val result = data.usePinned { srcPinned ->
                dest.usePinned { destPinned ->
                    uncompress(
                        destPinned.addressOf(0).reinterpret(),
                        destLen.ptr,
                        srcPinned.addressOf(0).reinterpret(),
                        data.size.toULong()
                    )
                }
            }
            require(result == Z_OK) { "zlib uncompress failed: $result" }
            require(destLen.value.toInt() == originalSize) {
                "decompressed ${destLen.value} bytes, expected $originalSize"
            }
            return dest
        }
    }
}

actual fun Compressor(): Compressor = LinuxCompressor()
