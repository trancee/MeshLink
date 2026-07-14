package ch.trancee.meshlink.platform.android.gatt

internal class GattNotifyWriteContext
internal constructor(
    internal val peerLogSuffix: String,
    internal val clientReady: Boolean,
    internal val hasGatt: Boolean,
    internal val hasWriteCharacteristic: Boolean,
    internal val maxChunkBytes: Int,
)

internal class GattNotifyWriteDependencies
internal constructor(
    internal val encode: (ByteArray) -> ByteArray,
    // Enqueues one chunk and returns quickly once it is accepted into the windowed pipeline
    // (it does not wait for that specific chunk's write completion -- see [drain]).
    internal val writeChunk:
        suspend (payloadBytes: Int, encodedBytes: Int, chunk: ByteArray) -> Boolean,
    // Awaits completion of every chunk enqueued so far for the current payload and reports
    // whether all of them succeeded. Keeping this separate from [writeChunk] is what allows
    // multiple chunks to be in flight at once instead of a stop-and-wait round trip per chunk.
    internal val drain: suspend () -> Boolean,
    internal val log: (String) -> Unit,
)

internal suspend fun writeViaGattNotify(
    payload: ByteArray,
    context: GattNotifyWriteContext,
    dependencies: GattNotifyWriteDependencies,
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
    var chunkStart = 0
    while (chunkStart < encoded.size) {
        val chunkEnd = minOf(chunkStart + chunkBytes, encoded.size)
        val didWrite =
            dependencies.writeChunk(
                payload.size,
                encoded.size,
                encoded.copyOfRange(chunkStart, chunkEnd),
            )
        if (!didWrite) {
            return false
        }
        chunkStart = chunkEnd
    }
    return dependencies.drain()
}
