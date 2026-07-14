package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.transport.DEFAULT_LENGTH_PREFIXED_FRAME_MAX_SIZE_BYTES
import ch.trancee.meshlink.transport.LengthPrefixedFrameBuffer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * iOS's L2CAP frame reassembler. The core length-prefixed framing algorithm (buffering,
 * frame-boundary decoding, buffer growth/compaction) lives in the cross-platform
 * [LengthPrefixedFrameBuffer] (`commonMain/transport/`), shared byte-for-byte with Android's
 * `L2capFrameBuffer`. This type stays a thin iOS-specific wrapper adding only the [NSData] append
 * overload, which Android has no equivalent of.
 */
internal class L2capFrameBuffer(
    private val maxFrameSizeBytes: Int = DEFAULT_LENGTH_PREFIXED_FRAME_MAX_SIZE_BYTES
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

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    internal fun append(chunk: NSData): List<ByteArray> {
        val chunkLength = chunk.length.toInt()
        val bytes = ByteArray(chunkLength)
        if (chunkLength > 0) {
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), chunk.bytes, chunk.length) }
        }
        return delegate.append(bytes)
    }
}
