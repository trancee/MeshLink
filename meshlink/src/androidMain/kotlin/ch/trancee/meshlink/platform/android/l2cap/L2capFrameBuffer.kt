package ch.trancee.meshlink.platform.android.l2cap

import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.transport.LengthPrefixedFrameBuffer
import ch.trancee.meshlink.transport.encodeLengthPrefixedFrame

internal const val DEFAULT_L2CAP_MAX_FRAME_SIZE_BYTES: Int = 128 * 1024
private const val HEX_SNIPPET_BYTES: Int = 16

/**
 * Length-prefixes [frame] for the L2CAP wire framing without allocating an [L2capFrameBuffer]
 * instance -- this is the hot outbound-write path (every single L2CAP write goes through it), and
 * framing is a pure function of [frame] and [maxFrameSizeBytes] with no buffered read state
 * involved, so there is nothing an [L2capFrameBuffer] instance provides here beyond the throwaway
 * cost of its eagerly-allocated 1 KB internal buffer.
 */
internal fun encodeL2capFrame(
    frame: ByteArray,
    maxFrameSizeBytes: Int = DEFAULT_L2CAP_MAX_FRAME_SIZE_BYTES,
): ByteArray {
    return encodeLengthPrefixedFrame(frame = frame, maxFrameSizeBytes = maxFrameSizeBytes)
}

/**
 * Android's L2CAP frame reassembler. The core length-prefixed framing algorithm (buffering,
 * frame-boundary decoding, buffer growth/compaction) lives in the cross-platform
 * [LengthPrefixedFrameBuffer] (`commonMain/transport/`), shared byte-for-byte with iOS's
 * `L2capFrameBuffer`. This type stays a thin Android-specific wrapper adding only the
 * diagnostics-oriented [appendDetailed] variant, which iOS has no equivalent of.
 */
internal class L2capFrameBuffer(
    private val maxFrameSizeBytes: Int = DEFAULT_L2CAP_MAX_FRAME_SIZE_BYTES
) {
    private val delegate = LengthPrefixedFrameBuffer(maxFrameSizeBytes = maxFrameSizeBytes)

    internal fun encode(frame: ByteArray): ByteArray {
        return delegate.encode(frame)
    }

    internal fun append(chunk: ByteArray): List<ByteArray> {
        return delegate.append(chunk)
    }

    internal fun append(source: ByteArray, length: Int): List<ByteArray> {
        return delegate.append(source, length)
    }

    internal fun appendDetailed(chunk: ByteArray): AppendResult {
        return appendDetailed(source = chunk, length = chunk.size)
    }

    internal fun appendDetailed(source: ByteArray, length: Int): AppendResult {
        val bufferedBytesBeforeAppend = delegate.pendingBytes()
        val appendedChunkPrefixHex = source.hexSnippetFromStart(length.coerceIn(0, source.size))
        val appendedChunkSuffixHex = source.hexSnippetFromEnd(length.coerceIn(0, source.size))
        val chunkLength = delegate.bufferChunk(source, length)

        val frames = mutableListOf<ByteArray>()
        val observations = mutableListOf<DecodedFrameObservation>()
        delegate.decodeAvailableFrames { decoded ->
            val headerHex = decoded.headerBytes.toHexString()
            observations +=
                DecodedFrameObservation(
                    frameIndexInAppend = observations.size + 1,
                    frameSizeBytes = decoded.frameSize,
                    headerHex = headerHex,
                    readOffsetBeforeFrame = decoded.headerReadOffset,
                    frameStartOffset = decoded.frameStartOffset,
                    frameEndOffset = decoded.frameEndOffset,
                    bufferedBytesBeforeAppend = bufferedBytesBeforeAppend,
                    totalBufferedBytesAfterAppend = decoded.totalBufferedBytes,
                    remainingBufferedBytesAfterFrame =
                        decoded.totalBufferedBytes - decoded.frameEndOffset,
                    headerStartsInPreviouslyBufferedBytes =
                        decoded.headerReadOffset < bufferedBytesBeforeAppend,
                    frameEndsBeyondPreviouslyBufferedBytes =
                        decoded.frameEndOffset > bufferedBytesBeforeAppend,
                    appendedChunkBytes = chunkLength,
                    appendedChunkPrefixHex = appendedChunkPrefixHex,
                    appendedChunkSuffixHex = appendedChunkSuffixHex,
                )
            frames += decoded.frameBytes
        }
        delegate.compactIfNeeded()
        return AppendResult(
            frames = frames,
            observations = observations,
            bufferedBytesBeforeAppend = bufferedBytesBeforeAppend,
            appendedChunkBytes = chunkLength,
            appendedChunkPrefixHex = appendedChunkPrefixHex,
            appendedChunkSuffixHex = appendedChunkSuffixHex,
            pendingBytesAfterAppend = delegate.pendingBytes(),
        )
    }

    internal fun pendingBytes(): Int {
        return delegate.pendingBytes()
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
}
