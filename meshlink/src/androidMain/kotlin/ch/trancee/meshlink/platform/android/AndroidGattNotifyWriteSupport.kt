package ch.trancee.meshlink.platform.android

internal class AndroidGattNotifyWriteContext
internal constructor(
    internal val peerLogSuffix: String,
    internal val clientReady: Boolean,
    internal val hasGatt: Boolean,
    internal val hasWriteCharacteristic: Boolean,
    internal val maxChunkBytes: Int,
)

internal class AndroidGattNotifyWriteDependencies
internal constructor(
    internal val encode: (ByteArray) -> ByteArray,
    internal val writeChunk:
        suspend (payloadBytes: Int, encodedBytes: Int, chunk: ByteArray) -> Boolean,
    internal val log: (String) -> Unit,
)

internal suspend fun writeViaAndroidGattNotify(
    payload: ByteArray,
    context: AndroidGattNotifyWriteContext,
    dependencies: AndroidGattNotifyWriteDependencies,
): Boolean {
    if (!context.clientReady) {
        dependencies.log(
            "GATT notify side link ${context.peerLogSuffix} write skipped: client not ready"
        )
        return false
    }

    if (!context.hasGatt || !context.hasWriteCharacteristic) {
        dependencies.log(
            "GATT notify side link ${context.peerLogSuffix} write skipped: missing GATT state " +
                "gatt=${context.hasGatt} characteristic=${context.hasWriteCharacteristic}"
        )
        return false
    }

    val encoded = dependencies.encode(payload)
    val chunkBytes = context.maxChunkBytes.coerceAtLeast(1)
    return encoded.asList().chunked(chunkBytes).all { chunk ->
        dependencies.writeChunk(payload.size, encoded.size, chunk.toByteArray())
    }
}
