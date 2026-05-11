package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.MeshLinkException

internal class AndroidL2capFrameBuffer(
    private val maxFrameSizeBytes: Int = DEFAULT_MAX_FRAME_SIZE_BYTES
) {
    private var buffer: ByteArray = ByteArray(INITIAL_CAPACITY_BYTES)
    private var size: Int = 0
    private var readOffset: Int = 0

    internal fun encode(frame: ByteArray): ByteArray {
        if (frame.size > maxFrameSizeBytes) {
            throw MeshLinkException.TransportFailure(
                "L2CAP frame exceeds max size $maxFrameSizeBytes bytes"
            )
        }
        return ByteArray(LENGTH_PREFIX_SIZE_BYTES + frame.size).also { encoded ->
            writeIntLittleEndian(encoded, 0, frame.size)
            frame.copyInto(encoded, destinationOffset = LENGTH_PREFIX_SIZE_BYTES)
        }
    }

    internal fun append(chunk: ByteArray): List<ByteArray> {
        ensureCapacity(size + chunk.size)
        chunk.copyInto(buffer, destinationOffset = size)
        size += chunk.size

        val frames = mutableListOf<ByteArray>()
        while (size - readOffset >= LENGTH_PREFIX_SIZE_BYTES) {
            val frameSize = readIntLittleEndian(buffer, readOffset)
            if (frameSize < 0 || frameSize > maxFrameSizeBytes) {
                clear()
                throw MeshLinkException.TransportFailure(
                    "L2CAP frame exceeds max size $maxFrameSizeBytes bytes"
                )
            }
            val frameStart = readOffset + LENGTH_PREFIX_SIZE_BYTES
            if (size - frameStart < frameSize) {
                break
            }
            frames += buffer.copyOfRange(frameStart, frameStart + frameSize)
            readOffset = frameStart + frameSize
        }
        compactIfNeeded()
        return frames
    }

    private fun clear(): Unit {
        size = 0
        readOffset = 0
    }

    private fun compactIfNeeded(): Unit {
        if (readOffset == 0) {
            return
        }
        if (readOffset == size) {
            clear()
            return
        }
        buffer.copyInto(
            destination = buffer,
            destinationOffset = 0,
            startIndex = readOffset,
            endIndex = size,
        )
        size -= readOffset
        readOffset = 0
    }

    private fun ensureCapacity(requiredSize: Int): Unit {
        if (requiredSize <= buffer.size) {
            return
        }
        var newCapacity = buffer.size.coerceAtLeast(INITIAL_CAPACITY_BYTES)
        while (newCapacity < requiredSize) {
            newCapacity *= 2
        }
        buffer = buffer.copyOf(newCapacity)
    }

    private fun writeIntLittleEndian(target: ByteArray, offset: Int, value: Int): Unit {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readIntLittleEndian(source: ByteArray, offset: Int): Int {
        return (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)
    }

    private companion object {
        private const val DEFAULT_MAX_FRAME_SIZE_BYTES: Int = 128 * 1024
        private const val INITIAL_CAPACITY_BYTES: Int = 1024
        private const val LENGTH_PREFIX_SIZE_BYTES: Int = 4
    }
}
