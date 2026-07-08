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
        return append(source = chunk, length = chunk.size)
    }

    internal fun append(source: ByteArray, length: Int): List<ByteArray> {
        val chunkLength = bufferChunk(source, length)

        val frames = mutableListOf<ByteArray>()
        decodeAvailableFrames { frameStart, frameEnd, _, _ ->
            frames += buffer.copyOfRange(frameStart, frameEnd)
        }
        compactIfNeeded()
        return frames
    }

    internal fun appendDetailed(chunk: ByteArray): AppendResult {
        return appendDetailed(source = chunk, length = chunk.size)
    }

    internal fun appendDetailed(source: ByteArray, length: Int): AppendResult {
        val bufferedBytesBeforeAppend = pendingBytes()
        val appendedChunkPrefixHex = source.hexSnippetFromStart(length.coerceIn(0, source.size))
        val appendedChunkSuffixHex = source.hexSnippetFromEnd(length.coerceIn(0, source.size))
        val chunkLength = bufferChunk(source, length)

        val frames = mutableListOf<ByteArray>()
        val observations = mutableListOf<DecodedFrameObservation>()
        decodeAvailableFrames { frameStart, frameEnd, frameSize, headerReadOffset ->
            val headerHex =
                buffer
                    .copyOfRange(headerReadOffset, headerReadOffset + LENGTH_PREFIX_SIZE_BYTES)
                    .toHexString()
            observations +=
                DecodedFrameObservation(
                    frameIndexInAppend = observations.size + 1,
                    frameSizeBytes = frameSize,
                    headerHex = headerHex,
                    readOffsetBeforeFrame = headerReadOffset,
                    frameStartOffset = frameStart,
                    frameEndOffset = frameEnd,
                    bufferedBytesBeforeAppend = bufferedBytesBeforeAppend,
                    totalBufferedBytesAfterAppend = size,
                    remainingBufferedBytesAfterFrame = size - frameEnd,
                    headerStartsInPreviouslyBufferedBytes =
                        headerReadOffset < bufferedBytesBeforeAppend,
                    frameEndsBeyondPreviouslyBufferedBytes = frameEnd > bufferedBytesBeforeAppend,
                    appendedChunkBytes = chunkLength,
                    appendedChunkPrefixHex = appendedChunkPrefixHex,
                    appendedChunkSuffixHex = appendedChunkSuffixHex,
                )
            frames += buffer.copyOfRange(frameStart, frameEnd)
        }
        compactIfNeeded()
        return AppendResult(
            frames = frames,
            observations = observations,
            bufferedBytesBeforeAppend = bufferedBytesBeforeAppend,
            appendedChunkBytes = chunkLength,
            appendedChunkPrefixHex = appendedChunkPrefixHex,
            appendedChunkSuffixHex = appendedChunkSuffixHex,
            pendingBytesAfterAppend = pendingBytes(),
        )
    }

    /**
     * Copies [length] bytes of [source] (clamped to its actual size) into the internal buffer,
     * growing it if necessary, and returns the clamped length actually appended. Shared by [append]
     * and [appendDetailed] so buffer growth/copy logic has a single implementation.
     */
    private fun bufferChunk(source: ByteArray, length: Int): Int {
        val chunkLength = length.coerceIn(0, source.size)
        ensureCapacity(size + chunkLength)
        source.copyInto(buffer, destinationOffset = size, endIndex = chunkLength)
        size += chunkLength
        return chunkLength
    }

    /**
     * Scans the buffer starting at [readOffset] for as many complete length-prefixed frames as are
     * currently available, invoking [onFrameDecoded] for each and advancing [readOffset] past it.
     * This is the single source of truth for frame-boundary decoding shared by [append] (the hot
     * receive path) and [appendDetailed] (the diagnostics variant) -- previously each reimplemented
     * this loop separately, risking the two silently diverging on framing edge cases. Declared
     * `inline` so the lean [append] path pays no extra lambda-allocation cost for sharing this
     * logic with [appendDetailed].
     */
    private inline fun decodeAvailableFrames(
        onFrameDecoded:
            (frameStart: Int, frameEnd: Int, frameSize: Int, headerReadOffset: Int) -> Unit
    ): Unit {
        while (size - readOffset >= LENGTH_PREFIX_SIZE_BYTES) {
            val headerReadOffset = readOffset
            val frameSize = readIntLittleEndian(buffer, headerReadOffset)
            if (frameSize < 0 || frameSize > maxFrameSizeBytes) {
                clear()
                throw MeshLinkException.TransportFailure(
                    "L2CAP frame exceeds max size $maxFrameSizeBytes bytes"
                )
            }
            val frameStart = headerReadOffset + LENGTH_PREFIX_SIZE_BYTES
            if (size - frameStart < frameSize) {
                break
            }
            val frameEnd = frameStart + frameSize
            onFrameDecoded(frameStart, frameEnd, frameSize, headerReadOffset)
            readOffset = frameEnd
        }
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

    private fun ByteArray.hexSnippetFromStart(length: Int): String {
        return copyOf(minOf(length, HEX_SNIPPET_BYTES)).toHexString()
    }

    private fun ByteArray.hexSnippetFromEnd(length: Int): String {
        val startIndex = maxOf(0, length - HEX_SNIPPET_BYTES)
        return copyOfRange(startIndex, length).toHexString()
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
