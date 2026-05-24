@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSOutputStream

internal interface IosL2capOutputStreamAdapter {
    fun streamStatus(): ULong

    fun hasError(): Boolean

    fun hasSpaceAvailable(): Boolean

    fun write(buffer: ByteArray, offset: Int, requestedBytes: Int): Long
}

internal class NsOutputStreamAdapter(private val outputStream: NSOutputStream) :
    IosL2capOutputStreamAdapter {
    override fun streamStatus(): ULong {
        return outputStream.streamStatus
    }

    override fun hasError(): Boolean {
        return outputStream.streamError != null
    }

    override fun hasSpaceAvailable(): Boolean {
        return outputStream.hasSpaceAvailable()
    }

    override fun write(buffer: ByteArray, offset: Int, requestedBytes: Int): Long {
        return buffer.usePinned { pinned ->
            outputStream.write(pinned.addressOf(offset).reinterpret(), requestedBytes.toULong())
        }
    }
}
