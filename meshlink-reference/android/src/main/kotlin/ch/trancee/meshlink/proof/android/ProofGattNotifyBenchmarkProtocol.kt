@file:Suppress("ReturnCount", "MagicNumber")

package ch.trancee.meshlink.proof.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.min

internal object ProofGattNotifyBenchmarkProtocol {
    internal val SERVICE_UUID: UUID = UUID.fromString("4d455348-1011-1000-8000-000000000000")
    internal val APP_HASH_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("4d455348-1012-1000-8000-000000000000")
    internal val NOTIFY_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("4d455348-1013-1000-8000-000000000000")
    internal val ACK_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("4d455348-1014-1000-8000-000000000000")
    internal val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val START_FRAME_TYPE: Byte = 0x01
    private const val DATA_FRAME_TYPE: Byte = 0x02
    private const val ACK_FRAME_TYPE: Byte = 0x11
    private const val TOKEN_BYTES: Int = 8
    private const val START_FRAME_BYTES: Int = 1 + TOKEN_BYTES + Int.SIZE_BYTES
    private const val ACK_FRAME_BYTES: Int = 1 + TOKEN_BYTES + Int.SIZE_BYTES
    private const val DATA_FRAME_HEADER_BYTES: Int = 1 + TOKEN_BYTES
    private const val PREFERRED_NOTIFICATION_FRAME_BYTES: Int = 495

    internal fun appHashValue(appId: String): ByteArray {
        val appHash = foldedAppHash(appId)
        return byteArrayOf(
            (appHash.toInt() and 0xFF).toByte(),
            ((appHash.toInt() ushr 8) and 0xFF).toByte(),
        )
    }

    internal fun matchesAppHash(value: ByteArray?, appId: String): Boolean {
        return value?.contentEquals(appHashValue(appId)) == true
    }

    internal fun encodeAck(tokenHex: String, totalBytes: Int): ByteArray {
        val tokenBytes = tokenHex.decodeHexTokenBytes() ?: error("Invalid benchmark token: $tokenHex")
        return ByteBuffer.allocate(ACK_FRAME_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(ACK_FRAME_TYPE)
            .put(tokenBytes)
            .putInt(totalBytes)
            .array()
    }

    internal fun decodeNotifyFrame(payload: ByteArray): NotifyFrame? {
        if (payload.isEmpty()) {
            return null
        }
        return when (payload[0]) {
            START_FRAME_TYPE -> decodeStartFrame(payload)
            DATA_FRAME_TYPE -> decodeDataFrame(payload)
            else -> null
        }
    }

    internal fun chunkPayloadBytes(maxUpdateValueLength: Int): Int {
        val maxFrameBytes = min(PREFERRED_NOTIFICATION_FRAME_BYTES, maxUpdateValueLength)
        return (maxFrameBytes - DATA_FRAME_HEADER_BYTES).coerceAtLeast(1)
    }

    private fun decodeStartFrame(payload: ByteArray): StartFrame? {
        if (payload.size != START_FRAME_BYTES) {
            return null
        }
        val tokenBytes = payload.copyOfRange(1, 1 + TOKEN_BYTES)
        val totalBytes =
            ByteBuffer.wrap(payload, 1 + TOKEN_BYTES, Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).int
        if (totalBytes <= 0) {
            return null
        }
        return StartFrame(tokenHex = tokenBytes.toHexToken(), totalBytes = totalBytes)
    }

    private fun decodeDataFrame(payload: ByteArray): DataFrame? {
        if (payload.size <= DATA_FRAME_HEADER_BYTES) {
            return null
        }
        val tokenBytes = payload.copyOfRange(1, 1 + TOKEN_BYTES)
        return DataFrame(
            tokenHex = tokenBytes.toHexToken(),
            chunk = payload.copyOfRange(DATA_FRAME_HEADER_BYTES, payload.size),
        )
    }

    private fun foldedAppHash(appId: String): UShort {
        val hash = fnv1a32(appId.encodeToByteArray())
        val folded = (((hash ushr 16) xor (hash and 0xFFFF)) and 0xFFFF).let { if (it == 0) 1 else it }
        return folded.toUShort()
    }

    private fun fnv1a32(bytes: ByteArray): Int {
        var hash = 0x811c9dc5.toInt()
        bytes.forEach { byte ->
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 0x01000193
        }
        return hash
    }


    internal sealed interface NotifyFrame

    internal data class StartFrame(val tokenHex: String, val totalBytes: Int) : NotifyFrame

    internal data class DataFrame(val tokenHex: String, val chunk: ByteArray) : NotifyFrame
}
