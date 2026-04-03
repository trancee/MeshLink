package io.meshlink.util

private const val ENVELOPE_UNCOMPRESSED: Byte = 0x00
private const val ENVELOPE_COMPRESSED: Byte = 0x01
private const val ENVELOPE_PADDED: Byte = 0x02

/**
 * Wraps/unwraps payload envelopes for optional compression and padding.
 *
 * Envelope format:
 *  - `0x00` + raw payload  → uncompressed
 *  - `0x01` + originalSize(4 LE) + compressedData → compressed (raw DEFLATE)
 *  - `0x02` + originalSize(2 LE) + payload + padding → padded to block boundary
 *
 * Padding is applied after compression (if any) and before encryption.
 * This defeats size-based traffic analysis by making all envelopes
 * the same size within each block boundary.
 */
internal class PayloadEnvelope(
    private val compressor: Compressor?,
    private val compressionMinBytes: Int,
    private val compressionEnabled: Boolean,
    private val paddingBlockSize: Int = 0,
) {

    fun wrap(payload: ByteArray): ByteArray {
        // Step 1: compress if enabled
        val inner = if (compressor == null) {
            payload
        } else if (payload.size < compressionMinBytes) {
            uncompressedEnvelope(payload)
        } else {
            val compressed = compressor.compress(payload)
            if (compressed.size >= payload.size) {
                uncompressedEnvelope(payload)
            } else {
                val envelope = ByteArray(5 + compressed.size)
                envelope[0] = ENVELOPE_COMPRESSED
                envelope[1] = (payload.size and 0xFF).toByte()
                envelope[2] = ((payload.size shr 8) and 0xFF).toByte()
                envelope[3] = ((payload.size shr 16) and 0xFF).toByte()
                envelope[4] = ((payload.size shr 24) and 0xFF).toByte()
                compressed.copyInto(envelope, 5)
                envelope
            }
        }

        // Step 2: pad to block boundary if enabled (FIND-01 mitigation)
        if (paddingBlockSize <= 0) return inner
        return padToBlock(inner)
    }

    fun unwrap(envelope: ByteArray): ByteArray {
        if (envelope.isEmpty()) return envelope

        // If first byte is PADDED, strip padding first then recurse on inner
        if (envelope[0] == ENVELOPE_PADDED) {
            val innerSize = (envelope[1].toInt() and 0xFF) or
                ((envelope[2].toInt() and 0xFF) shl 8)
            val inner = envelope.copyOfRange(3, 3 + innerSize)
            return unwrap(inner)
        }

        if (!compressionEnabled) return envelope
        return when (envelope[0]) {
            ENVELOPE_COMPRESSED -> {
                val originalSize = (envelope[1].toInt() and 0xFF) or
                    ((envelope[2].toInt() and 0xFF) shl 8) or
                    ((envelope[3].toInt() and 0xFF) shl 16) or
                    ((envelope[4].toInt() and 0xFF) shl 24)
                val compressed = envelope.copyOfRange(5, envelope.size)
                val c = compressor ?: Compressor()
                c.decompress(compressed, originalSize)
            }
            ENVELOPE_UNCOMPRESSED -> envelope.copyOfRange(1, envelope.size)
            else -> envelope
        }
    }

    private fun uncompressedEnvelope(payload: ByteArray): ByteArray {
        val envelope = ByteArray(1 + payload.size)
        envelope[0] = ENVELOPE_UNCOMPRESSED
        payload.copyInto(envelope, 1)
        return envelope
    }

    /**
     * Pad inner envelope to the next [paddingBlockSize] boundary.
     * Format: `0x02` + innerSize(2 LE) + inner + zero-padding.
     * Max inner size: 65535 bytes (uint16).
     */
    private fun padToBlock(inner: ByteArray): ByteArray {
        val headerSize = 3 // type(1) + innerSize(2 LE)
        val totalContent = headerSize + inner.size
        val paddedSize = ((totalContent + paddingBlockSize - 1) / paddingBlockSize) * paddingBlockSize
        val result = ByteArray(paddedSize)
        result[0] = ENVELOPE_PADDED
        result[1] = (inner.size and 0xFF).toByte()
        result[2] = ((inner.size shr 8) and 0xFF).toByte()
        inner.copyInto(result, 3)
        // remaining bytes are zero (padding)
        return result
    }
}
