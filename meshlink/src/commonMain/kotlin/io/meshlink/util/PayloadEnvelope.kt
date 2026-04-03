package io.meshlink.util

private const val ENVELOPE_UNCOMPRESSED: Byte = 0x00
private const val ENVELOPE_COMPRESSED: Byte = 0x01

/**
 * Wraps/unwraps payload envelopes for optional compression.
 *
 * Envelope format:
 *  - `0x00` + raw payload  → uncompressed
 *  - `0x01` + originalSize(4 LE) + compressedData → compressed (raw DEFLATE)
 */
internal class PayloadEnvelope(
    private val compressor: Compressor?,
    private val compressionMinBytes: Int,
    private val compressionEnabled: Boolean,
) {

    fun wrap(payload: ByteArray): ByteArray {
        if (compressor == null) return payload
        if (payload.size < compressionMinBytes) {
            return uncompressedEnvelope(payload)
        }
        val compressed = compressor.compress(payload)
        if (compressed.size >= payload.size) {
            return uncompressedEnvelope(payload)
        }
        val envelope = ByteArray(5 + compressed.size)
        envelope[0] = ENVELOPE_COMPRESSED
        envelope[1] = (payload.size and 0xFF).toByte()
        envelope[2] = ((payload.size shr 8) and 0xFF).toByte()
        envelope[3] = ((payload.size shr 16) and 0xFF).toByte()
        envelope[4] = ((payload.size shr 24) and 0xFF).toByte()
        compressed.copyInto(envelope, 5)
        return envelope
    }

    fun unwrap(envelope: ByteArray): ByteArray {
        if (!compressionEnabled || envelope.isEmpty()) return envelope
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
}
