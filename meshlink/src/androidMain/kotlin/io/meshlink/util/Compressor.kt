@file:JvmName("CompressorAndroid")

package io.meshlink.util

import java.util.zip.Deflater
import java.util.zip.Inflater

private class AndroidCompressor : Compressor {
    override fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(data)
            deflater.finish()
            val buf = ByteArray(data.size + 64)
            val len = deflater.deflate(buf)
            return buf.copyOf(len)
        } finally {
            deflater.end()
        }
    }

    override fun decompress(data: ByteArray, originalSize: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val buf = ByteArray(originalSize)
            val len = inflater.inflate(buf)
            require(len == originalSize) { "decompressed $len bytes, expected $originalSize" }
            return buf
        } finally {
            inflater.end()
        }
    }
}

actual fun Compressor(): Compressor = AndroidCompressor()
