package io.meshlink.transfer

/**
 * Manages the complete lifecycle of chunked message transfers:
 * outbound chunking + AIMD congestion control + retransmission,
 * and inbound reassembly + SACK tracking.
 *
 * Hides: AIMD window management, SACK bitmask computation, chunk reassembly
 * state, retransmission gap detection, stale transfer cleanup, and
 * outbound/inbound transfer bookkeeping.
 */
class TransferEngine(
    private val clock: () -> Long = { 0L },
    private val maxConcurrentInboundSessions: Int = 100,
) {
    // Outbound state: messageId hex → transfer
    private val outbound = mutableMapOf<String, OutboundState>()

    // Inbound state: messageId hex → reassembly
    private val inbound = mutableMapOf<String, InboundState>()

    // --- Outbound: chunking and sending ---

    fun beginSend(
        messageIdHex: String,
        messageId: ByteArray,
        payload: ByteArray,
        chunkSize: Int,
    ): OutboundHandle {
        val chunks = if (payload.isEmpty()) {
            listOf(ByteArray(0))
        } else {
            payload.asSequence()
                .chunked(chunkSize)
                .map { it.toByteArray() }
                .toList()
        }
        val totalChunks = chunks.size
        val session = TransferSession(totalChunks, initialWindow = totalChunks)
        outbound[messageIdHex] = OutboundState(session, chunks, messageId, createdAtMillis = clock())
        val initialSeqs = session.nextChunksToSend()
        return OutboundHandle(
            messageId = messageId,
            chunks = initialSeqs.map { seq -> ChunkData(seq, totalChunks, chunks[seq]) },
            totalChunks = totalChunks,
        )
    }

    fun onAck(messageIdHex: String, ackSeq: Int, sackBitmask: ULong): TransferUpdate {
        val state = outbound[messageIdHex] ?: return TransferUpdate.Unknown
        state.session.onAck(ackSeq, sackBitmask)

        if (state.session.isComplete()) {
            outbound.remove(messageIdHex)
            return TransferUpdate.Complete(state.messageId, state.session.ackedCount(), state.chunks.size)
        }

        val retransmit = state.session.nextChunksToSend()
        return TransferUpdate.Progress(
            ackedCount = state.session.ackedCount(),
            totalChunks = state.chunks.size,
            chunksToSend = retransmit.map { seq -> ChunkData(seq, state.chunks.size, state.chunks[seq]) },
        )
    }

    fun getOutboundRecipientInfo(messageIdHex: String): OutboundInfo? {
        val state = outbound[messageIdHex] ?: return null
        return OutboundInfo(
            messageId = state.messageId,
            isComplete = state.session.isComplete(),
            isFailed = state.session.isFailed(),
        )
    }

    fun removeOutbound(messageIdHex: String) {
        outbound.remove(messageIdHex)
    }

    // --- Inbound: reassembly ---

    fun onChunkReceived(
        messageIdHex: String,
        seqNum: Int,
        totalChunks: Int,
        chunkPayload: ByteArray,
    ): ChunkAcceptResult {
        val existing = inbound[messageIdHex]
        if (existing == null && inbound.size >= maxConcurrentInboundSessions) {
            return ChunkAcceptResult.Rejected
        }
        val state = existing ?: InboundState(totalChunks, createdAtMillis = clock()).also {
            inbound[messageIdHex] = it
        }
        state.chunks[seqNum] = chunkPayload
        state.sackTracker.record(seqNum)

        val status = state.sackTracker.status()

        if (state.sackTracker.isComplete()) {
            inbound.remove(messageIdHex)
            val fullPayload = (0 until state.totalChunks)
                .map { state.chunks[it]!! }
                .reduce { acc, bytes -> acc + bytes }
            return ChunkAcceptResult.MessageComplete(
                ackSeq = status.ackSeq,
                sackBitmask = status.sackBitmask,
                reassembledPayload = fullPayload,
            )
        }

        return ChunkAcceptResult.Ack(
            ackSeq = status.ackSeq,
            sackBitmask = status.sackBitmask,
        )
    }

    // --- Sweep stale ---

    fun sweepStaleOutbound(maxAgeMillis: Long): List<String> {
        val now = clock()
        val stale = outbound.entries
            .filter { (now - it.value.createdAtMillis) >= maxAgeMillis }
            .map { it.key }
        stale.forEach { outbound.remove(it) }
        return stale
    }

    fun sweepStaleInbound(maxAgeMillis: Long): List<String> {
        val now = clock()
        val stale = inbound.entries
            .filter { (now - it.value.createdAtMillis) >= maxAgeMillis }
            .map { it.key }
        stale.forEach { inbound.remove(it) }
        return stale
    }

    // --- State ---

    val outboundCount: Int get() = outbound.size
    val inboundCount: Int get() = inbound.size

    fun outboundBufferBytes(): Int =
        outbound.values.sumOf { s -> s.chunks.sumOf { it.size } }

    fun inboundBufferBytes(): Int =
        inbound.values.sumOf { s -> s.chunks.values.sumOf { it.size } }

    fun clearAll() {
        outbound.clear()
        inbound.clear()
    }

    // --- Internal state classes ---

    private class OutboundState(
        val session: TransferSession,
        val chunks: List<ByteArray>,
        val messageId: ByteArray,
        val createdAtMillis: Long,
    )

    private class InboundState(
        val totalChunks: Int,
        val createdAtMillis: Long,
    ) {
        val chunks = mutableMapOf<Int, ByteArray>()
        val sackTracker = SackTracker(totalChunks)
    }
}

// --- Result types ---

data class OutboundHandle(
    val messageId: ByteArray,
    val chunks: List<ChunkData>,
    val totalChunks: Int,
)

data class ChunkData(
    val seqNum: Int,
    val totalChunks: Int,
    val payload: ByteArray,
)

sealed interface TransferUpdate {
    data class Complete(val messageId: ByteArray, val ackedCount: Int, val totalChunks: Int) : TransferUpdate
    data class Progress(
        val ackedCount: Int,
        val totalChunks: Int,
        val chunksToSend: List<ChunkData>,
    ) : TransferUpdate
    data object Unknown : TransferUpdate
}

sealed interface ChunkAcceptResult {
    data class Ack(val ackSeq: Int, val sackBitmask: ULong) : ChunkAcceptResult
    data class MessageComplete(
        val ackSeq: Int,
        val sackBitmask: ULong,
        val reassembledPayload: ByteArray,
    ) : ChunkAcceptResult
    data object Rejected : ChunkAcceptResult
}

data class OutboundInfo(
    val messageId: ByteArray,
    val isComplete: Boolean,
    val isFailed: Boolean,
)
