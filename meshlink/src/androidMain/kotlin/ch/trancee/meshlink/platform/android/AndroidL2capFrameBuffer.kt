package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.identity.toHexString

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
            val frameEnd = frameStart + frameSize
            frames += buffer.copyOfRange(frameStart, frameEnd)
            readOffset = frameEnd
        }
        compactIfNeeded()
        return frames
    }

    internal fun appendDetailed(chunk: ByteArray): AppendResult {
        val bufferedBytesBeforeAppend = pendingBytes()
        val appendedChunkPrefixHex = chunk.hexSnippetFromStart()
        val appendedChunkSuffixHex = chunk.hexSnippetFromEnd()
        ensureCapacity(size + chunk.size)
        chunk.copyInto(buffer, destinationOffset = size)
        size += chunk.size

        val frames = mutableListOf<ByteArray>()
        val observations = mutableListOf<DecodedFrameObservation>()
        while (size - readOffset >= LENGTH_PREFIX_SIZE_BYTES) {
            val headerHex =
                buffer.copyOfRange(readOffset, readOffset + LENGTH_PREFIX_SIZE_BYTES).toHexString()
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
            val frameEnd = frameStart + frameSize
            observations +=
                DecodedFrameObservation(
                    frameIndexInAppend = observations.size + 1,
                    frameSizeBytes = frameSize,
                    headerHex = headerHex,
                    readOffsetBeforeFrame = readOffset,
                    frameStartOffset = frameStart,
                    frameEndOffset = frameEnd,
                    bufferedBytesBeforeAppend = bufferedBytesBeforeAppend,
                    totalBufferedBytesAfterAppend = size,
                    remainingBufferedBytesAfterFrame = size - frameEnd,
                    headerStartsInPreviouslyBufferedBytes = readOffset < bufferedBytesBeforeAppend,
                    frameEndsBeyondPreviouslyBufferedBytes = frameEnd > bufferedBytesBeforeAppend,
                    appendedChunkBytes = chunk.size,
                    appendedChunkPrefixHex = appendedChunkPrefixHex,
                    appendedChunkSuffixHex = appendedChunkSuffixHex,
                )
            frames += buffer.copyOfRange(frameStart, frameEnd)
            readOffset = frameEnd
        }
        compactIfNeeded()
        return AppendResult(
            frames = frames,
            observations = observations,
            bufferedBytesBeforeAppend = bufferedBytesBeforeAppend,
            appendedChunkBytes = chunk.size,
            appendedChunkPrefixHex = appendedChunkPrefixHex,
            appendedChunkSuffixHex = appendedChunkSuffixHex,
            pendingBytesAfterAppend = pendingBytes(),
        )
    }

    internal fun pendingBytes(): Int {
        return size - readOffset
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

    private fun ByteArray.hexSnippetFromStart(): String {
        return copyOf(minOf(size, HEX_SNIPPET_BYTES)).toHexString()
    }

    private fun ByteArray.hexSnippetFromEnd(): String {
        val startIndex = maxOf(0, size - HEX_SNIPPET_BYTES)
        return copyOfRange(startIndex, size).toHexString()
    }

    internal data class AppendResult(
        val frames: List<ByteArray>,
        val observations: List<DecodedFrameObservation>,
        val bufferedBytesBeforeAppend: Int,
        val appendedChunkBytes: Int,
        val appendedChunkPrefixHex: String,
        val appendedChunkSuffixHex: String,
        val pendingBytesAfterAppend: Int,
    )

    internal data class DecodedFrameObservation(
        val frameIndexInAppend: Int,
        val frameSizeBytes: Int,
        val headerHex: String,
        val readOffsetBeforeFrame: Int,
        val frameStartOffset: Int,
        val frameEndOffset: Int,
        val bufferedBytesBeforeAppend: Int,
        val totalBufferedBytesAfterAppend: Int,
        val remainingBufferedBytesAfterFrame: Int,
        val headerStartsInPreviouslyBufferedBytes: Boolean,
        val frameEndsBeyondPreviouslyBufferedBytes: Boolean,
        val appendedChunkBytes: Int,
        val appendedChunkPrefixHex: String,
        val appendedChunkSuffixHex: String,
    )

    private companion object {
        private const val DEFAULT_MAX_FRAME_SIZE_BYTES: Int = 128 * 1024
        private const val INITIAL_CAPACITY_BYTES: Int = 1024
        private const val LENGTH_PREFIX_SIZE_BYTES: Int = 4
        private const val HEX_SNIPPET_BYTES: Int = 16
    }
}
