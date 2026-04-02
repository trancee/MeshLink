@file:JvmName("CompressorJvm")

package io.meshlink.util

import java.util.zip.Deflater
import java.util.zip.Inflater

private class JvmCompressor : Compressor {
    private val deflater = Deflater(Deflater.BEST_SPEED, true)
    private val inflater = Inflater(true)

    override fun compress(data: ByteArray): ByteArray {
        synchronized(deflater) {
            deflater.reset()
            deflater.setInput(data)
            deflater.finish()
            val buf = ByteArray(data.size + 64)
            val len = deflater.deflate(buf)
            return buf.copyOf(len)
        }
    }

    override fun decompress(data: ByteArray, originalSize: Int): ByteArray {
        synchronized(inflater) {
            inflater.reset()
            inflater.setInput(data)
            val buf = ByteArray(originalSize)
            val len = inflater.inflate(buf)
            require(len == originalSize) { "decompressed $len bytes, expected $originalSize" }
            return buf
        }
    }
}

actual fun Compressor(): Compressor = JvmCompressor()
