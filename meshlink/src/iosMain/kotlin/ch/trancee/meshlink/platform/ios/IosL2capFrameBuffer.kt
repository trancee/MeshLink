package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.MeshLinkException

internal class L2capFrameBuffer(private val maxFrameSizeBytes: Int = DEFAULT_MAX_FRAME_SIZE_BYTES) {
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
        writeLittleEndian(target = target, offset = offset, value = value.toLong())
    }

    private fun readIntLittleEndian(source: ByteArray, offset: Int): Int {
        return readLittleEndian(source = source, offset = offset).toInt()
    }

    private fun writeLittleEndian(target: ByteArray, offset: Int, value: Long): Unit {
        repeat(LENGTH_PREFIX_SIZE_BYTES) { index ->
            val byteOffset = offset + index
            val shiftBits = index * BITS_PER_BYTE
            target[byteOffset] = ((value shr shiftBits) and BYTE_MASK.toLong()).toByte()
        }
    }

    private fun readLittleEndian(source: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(LENGTH_PREFIX_SIZE_BYTES) { index ->
            val byteOffset = offset + index
            val shiftBits = index * BITS_PER_BYTE
            value = value or ((source[byteOffset].toLong() and BYTE_MASK.toLong()) shl shiftBits)
        }
        return value
    }

    private companion object {
        private const val BITS_PER_BYTE: Int = 8
        private const val BYTE_MASK: Int = 0xFF
        private const val DEFAULT_MAX_FRAME_SIZE_BYTES: Int = 128 * 1024
        private const val INITIAL_CAPACITY_BYTES: Int = 1024
        private const val LENGTH_PREFIX_SIZE_BYTES: Int = 4
    }
}
