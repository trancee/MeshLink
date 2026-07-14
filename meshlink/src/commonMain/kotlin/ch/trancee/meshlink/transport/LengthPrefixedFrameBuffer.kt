package ch.trancee.meshlink.transport

import ch.trancee.meshlink.api.MeshLinkException

internal const val DEFAULT_LENGTH_PREFIXED_FRAME_MAX_SIZE_BYTES: Int = 128 * 1024
internal const val LENGTH_PREFIX_SIZE_BYTES: Int = 4
private const val INITIAL_CAPACITY_BYTES: Int = 1024
private const val BYTE_MASK: Int = 0xFF
private const val BITS_PER_BYTE: Int = 8
private const val SHIFT_1_BYTE: Int = BITS_PER_BYTE
private const val SHIFT_2_BYTES: Int = BITS_PER_BYTE * 2
private const val SHIFT_3_BYTES: Int = BITS_PER_BYTE * 3
private const val BYTE_INDEX_1: Int = 1
private const val BYTE_INDEX_2: Int = 2
private const val BYTE_INDEX_3: Int = 3

internal fun writeIntLittleEndian(target: ByteArray, offset: Int, value: Int): Unit {
    target[offset] = (value and BYTE_MASK).toByte()
    target[offset + BYTE_INDEX_1] = ((value shr SHIFT_1_BYTE) and BYTE_MASK).toByte()
    target[offset + BYTE_INDEX_2] = ((value shr SHIFT_2_BYTES) and BYTE_MASK).toByte()
    target[offset + BYTE_INDEX_3] = ((value shr SHIFT_3_BYTES) and BYTE_MASK).toByte()
}

internal fun readIntLittleEndian(source: ByteArray, offset: Int): Int {
    return (source[offset].toInt() and BYTE_MASK) or
        ((source[offset + BYTE_INDEX_1].toInt() and BYTE_MASK) shl SHIFT_1_BYTE) or
        ((source[offset + BYTE_INDEX_2].toInt() and BYTE_MASK) shl SHIFT_2_BYTES) or
        ((source[offset + BYTE_INDEX_3].toInt() and BYTE_MASK) shl SHIFT_3_BYTES)
}

/**
 * Length-prefixes [frame] for length-prefixed wire framing (currently used by the L2CAP bearer on
 * both platforms) without allocating a [LengthPrefixedFrameBuffer] instance -- this is a hot
 * outbound-write path (every single L2CAP write goes through it on Android), and framing is a pure
 * function of [frame] and [maxFrameSizeBytes] with no buffered read state involved, so there is
 * nothing a [LengthPrefixedFrameBuffer] instance provides here beyond the throwaway cost of its
 * eagerly-allocated 1 KB internal buffer.
 */
internal fun encodeLengthPrefixedFrame(
    frame: ByteArray,
    maxFrameSizeBytes: Int = DEFAULT_LENGTH_PREFIXED_FRAME_MAX_SIZE_BYTES,
): ByteArray {
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

/**
 * Reassembles length-prefixed frames from a byte stream that may deliver them in arbitrarily split
 * or coalesced chunks -- the core framing algorithm shared by the Android and iOS L2CAP transport
 * bearers (each platform's `L2capFrameBuffer` delegates to this type; see
 * `platform/android/l2cap/L2capFrameBuffer.kt` and `platform/ios/L2capFrameBuffer.kt`). Framing
 * format: a 4-byte little-endian length prefix followed by that many payload bytes, with buffer
 * growth via doubling and periodic compaction once fully-consumed bytes accumulate at the front.
 *
 * This type is intentionally free of any platform-specific append overload (Android's diagnostics
 * variant, iOS's `NSData` overload) -- those stay thin wrappers in each platform's own
 * `L2capFrameBuffer`, calling into [append] here for the actual buffering/decode logic.
 */
internal class LengthPrefixedFrameBuffer(
    private val maxFrameSizeBytes: Int = DEFAULT_LENGTH_PREFIXED_FRAME_MAX_SIZE_BYTES
) {
    private var buffer: ByteArray = ByteArray(INITIAL_CAPACITY_BYTES)
    private var size: Int = 0
    private var readOffset: Int = 0

    internal fun encode(frame: ByteArray): ByteArray {
        return encodeLengthPrefixedFrame(frame = frame, maxFrameSizeBytes = maxFrameSizeBytes)
    }

    internal fun append(chunk: ByteArray): List<ByteArray> {
        return append(source = chunk, length = chunk.size)
    }

    internal fun append(source: ByteArray, length: Int): List<ByteArray> {
        bufferChunk(source, length)

        val frames = mutableListOf<ByteArray>()
        decodeAvailableFrames { decoded -> frames += decoded.frameBytes }
        compactIfNeeded()
        return frames
    }

    /**
     * Copies [length] bytes of [source] (clamped to its actual size) into the internal buffer,
     * growing it if necessary, and returns the clamped length actually appended. Shared by every
     * platform-specific append overload so buffer growth/copy logic has a single implementation.
     */
    internal fun bufferChunk(source: ByteArray, length: Int): Int {
        val chunkLength = length.coerceIn(0, source.size)
        ensureCapacity(size + chunkLength)
        source.copyInto(buffer, destinationOffset = size, endIndex = chunkLength)
        size += chunkLength
        return chunkLength
    }

    /**
     * Scans the buffer starting at [readOffset] for as many complete length-prefixed frames as are
     * currently available, invoking [onFrameDecoded] for each (passing the decoded frame bytes, raw
     * header bytes, and the buffer-offset bookkeeping diagnostics callers need) and advancing
     * [readOffset] past it. This is the single source of truth for frame-boundary decoding shared
     * by every platform's append overloads -- previously each platform (and, on Android, each of
     * its append variants) reimplemented this loop separately, risking silent divergence on framing
     * edge cases. Declared `inline` so the lean [append] path pays no extra lambda-allocation cost
     * for sharing this logic with diagnostics-oriented callers.
     */
    internal inline fun decodeAvailableFrames(
        onFrameDecoded: (decoded: DecodedFrame) -> Unit
    ): Unit {
        val totalBufferedBytes = size
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
            onFrameDecoded(
                DecodedFrame(
                    frameBytes = buffer.copyOfRange(frameStart, frameEnd),
                    headerBytes =
                        buffer.copyOfRange(
                            headerReadOffset,
                            headerReadOffset + LENGTH_PREFIX_SIZE_BYTES,
                        ),
                    frameSize = frameSize,
                    headerReadOffset = headerReadOffset,
                    frameStartOffset = frameStart,
                    frameEndOffset = frameEnd,
                    totalBufferedBytes = totalBufferedBytes,
                )
            )
            readOffset = frameEnd
        }
    }

    internal fun pendingBytes(): Int {
        return size - readOffset
    }

    internal fun clear(): Unit {
        size = 0
        readOffset = 0
    }

    internal fun compactIfNeeded(): Unit {
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
}

/**
 * One length-prefixed frame decoded by [LengthPrefixedFrameBuffer.decodeAvailableFrames], plus the
 * buffer-offset bookkeeping the Android diagnostics-oriented [L2capFrameBuffer.appendDetailed]
 * wrapper needs to build its `DecodedFrameObservation` records. Kept as a single data class rather
 * than several separate accessor methods on [LengthPrefixedFrameBuffer] so the shared type doesn't
 * grow additional public surface purely to expose internal buffer offsets to one platform's
 * diagnostics variant.
 */
internal data class DecodedFrame(
    val frameBytes: ByteArray,
    val headerBytes: ByteArray,
    val frameSize: Int,
    val headerReadOffset: Int,
    val frameStartOffset: Int,
    val frameEndOffset: Int,
    val totalBufferedBytes: Int,
)
