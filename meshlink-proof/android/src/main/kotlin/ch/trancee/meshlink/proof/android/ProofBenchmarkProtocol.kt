package ch.trancee.meshlink.proof.android

import ch.trancee.meshlink.api.PeerId

internal const val PROOF_BENCHMARK_MAGIC: String = "MLBM1000"
internal const val PROOF_BENCHMARK_RECEIPT_PREFIX: String = "MLBM1_ACK:"
internal const val PROOF_BENCHMARK_HEADER_BYTES: Int = 16
internal const val PROOF_BENCHMARK_TOKEN_HEX_LENGTH: Int = 16

internal data class BenchmarkPayloadEnvelope(
    val tokenHex: String,
    val totalBytes: Int,
    val bytes: ByteArray,
) {
    fun encode(): ByteArray {
        return bytes.copyOf()
    }

    companion object {
        fun create(totalBytes: Int, tokenHex: String): BenchmarkPayloadEnvelope {
            require(totalBytes >= PROOF_BENCHMARK_HEADER_BYTES) {
                "Benchmark payload must be at least $PROOF_BENCHMARK_HEADER_BYTES bytes"
            }
            require(tokenHex.length == PROOF_BENCHMARK_TOKEN_HEX_LENGTH) {
                "Benchmark token must be $PROOF_BENCHMARK_TOKEN_HEX_LENGTH hex characters"
            }
            val payload = ByteArray(totalBytes) { index -> ((index * 31) and 0xFF).toByte() }
            PROOF_BENCHMARK_MAGIC.encodeToByteArray().copyInto(payload, 0)
            tokenHex.chunked(2).forEachIndexed { index, pair ->
                payload[PROOF_BENCHMARK_MAGIC.length + index] = pair.toInt(16).toByte()
            }
            return BenchmarkPayloadEnvelope(
                tokenHex = tokenHex,
                totalBytes = totalBytes,
                bytes = payload,
            )
        }

        fun decode(payload: ByteArray): BenchmarkPayloadEnvelope? {
            if (payload.size < PROOF_BENCHMARK_HEADER_BYTES) {
                return null
            }
            if (
                !payload.copyOfRange(0, PROOF_BENCHMARK_MAGIC.length).contentEquals(
                    PROOF_BENCHMARK_MAGIC.encodeToByteArray()
                )
            ) {
                return null
            }
            val tokenBytes =
                payload.copyOfRange(PROOF_BENCHMARK_MAGIC.length, PROOF_BENCHMARK_HEADER_BYTES)
            val tokenHex = tokenBytes.joinToString(separator = "") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
            return BenchmarkPayloadEnvelope(
                tokenHex = tokenHex,
                totalBytes = payload.size,
                bytes = payload.copyOf(),
            )
        }
    }
}

internal data class BenchmarkReceipt(
    val tokenHex: String,
    val totalBytes: Int,
) {
    fun encode(): ByteArray {
        return "$PROOF_BENCHMARK_RECEIPT_PREFIX$tokenHex:$totalBytes".encodeToByteArray()
    }

    companion object {
        fun decode(payload: ByteArray): BenchmarkReceipt? {
            val text = payload.decodeToString()
            if (!text.startsWith(PROOF_BENCHMARK_RECEIPT_PREFIX)) {
                return null
            }
            val parts = text.removePrefix(PROOF_BENCHMARK_RECEIPT_PREFIX).split(':', limit = 2)
            if (parts.size != 2) {
                return null
            }
            val totalBytes = parts[1].toIntOrNull() ?: return null
            return BenchmarkReceipt(tokenHex = parts[0], totalBytes = totalBytes)
        }
    }
}

internal data class ProofSnapshot(
    val state: String,
    val peers: List<String>,
    val logs: List<String>,
    val running: Boolean,
)

internal class KnownPeer private constructor(
    val peerId: PeerId,
    val peerBytes: ByteArray?,
) {
    companion object {
        fun from(peerId: PeerId): KnownPeer {
            return KnownPeer(peerId = peerId, peerBytes = peerId.value.toBytes())
        }
    }
}

internal fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) {
        return false
    }
    return prefix.indices.all { index -> this[index] == prefix[index] }
}

internal fun String.toBytes(): ByteArray? {
    if ((length and 1) != 0) {
        return null
    }
    return ByteArray(length / 2) { index ->
        (decodeHexByte(charIndex = index * 2) ?: return null).toByte()
    }
}

internal fun ByteArray.lexicographicallyPrecedesHexString(hex: String): Boolean {
    if (hex.length < size * 2) {
        return false
    }
    for (index in indices) {
        val current = this[index].toInt() and 0xFF
        val other = hex.decodeHexByte(charIndex = index * 2) ?: return false
        if (current != other) {
            return current < other
        }
    }
    return hex.length > size * 2
}

internal fun ByteArray.toLowerHexString(): String {
    return joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

internal fun String.decodeHexByte(charIndex: Int): Int? {
    if (charIndex + 1 >= length) {
        return null
    }
    val high = decodeHexNibble(this[charIndex]) ?: return null
    val low = decodeHexNibble(this[charIndex + 1]) ?: return null
    return (high shl 4) or low
}

internal fun decodeHexNibble(value: Char): Int? {
    return when (value) {
        in '0'..'9' -> value.code - '0'.code
        in 'a'..'f' -> value.code - 'a'.code + 10
        in 'A'..'F' -> value.code - 'A'.code + 10
        else -> null
    }
}
