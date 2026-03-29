package io.meshlink.transfer

/**
 * Buffer for cut-through relay forwarding.
 * Stores forwarded chunks for potential retransmission while forwarding
 * chunks as they arrive rather than waiting for full reassembly.
 */
internal class CutThroughBuffer(
    private val maxBufferBytes: Int = 262_144, // 256 KB default
) {
    data class BufferedChunk(
        val sequenceNumber: Int,
        val data: ByteArray,
        val forwardedAtMillis: Long = 0L,
    )

    data class RelaySession(
        val messageIdHex: String,
        val totalChunks: Int,
        val nextHop: ByteArray,
        val chunks: MutableMap<Int, BufferedChunk> = mutableMapOf(),
        var forwardedCount: Int = 0,
    ) {
        val isComplete: Boolean get() = forwardedCount >= totalChunks
        val bufferBytes: Int get() = chunks.values.sumOf { it.data.size }
    }

    private val sessions = mutableMapOf<String, RelaySession>()

    /**
     * Start a new relay session for a routed message.
     * Returns false if buffer capacity would be exceeded (no room for even one chunk).
     */
    fun startSession(messageIdHex: String, totalChunks: Int, nextHop: ByteArray): Boolean {
        if (sessions.containsKey(messageIdHex)) return true
        if (totalBufferedBytes() >= maxBufferBytes) return false
        sessions[messageIdHex] = RelaySession(messageIdHex, totalChunks, nextHop.copyOf())
        return true
    }

    /**
     * Buffer a chunk for forwarding. Returns the chunk data if it should be forwarded
     * (new chunk), or null if it's a duplicate or the session doesn't exist.
     */
    fun bufferChunk(
        messageIdHex: String,
        sequenceNumber: Int,
        data: ByteArray,
        timestampMillis: Long = 0L,
    ): ByteArray? {
        val session = sessions[messageIdHex] ?: return null
        if (session.chunks.containsKey(sequenceNumber)) return null

        val copy = data.copyOf()
        session.chunks[sequenceNumber] = BufferedChunk(sequenceNumber, copy, timestampMillis)
        session.forwardedCount++
        return copy
    }

    /**
     * Get a buffered chunk for retransmission.
     */
    fun getChunk(messageIdHex: String, sequenceNumber: Int): ByteArray? {
        val session = sessions[messageIdHex] ?: return null
        return session.chunks[sequenceNumber]?.data
    }

    /**
     * Mark a chunk as acknowledged by the next-hop. Frees the chunk buffer.
     */
    fun ackChunk(messageIdHex: String, sequenceNumber: Int) {
        val session = sessions[messageIdHex] ?: return
        session.chunks.remove(sequenceNumber)
    }

    /**
     * Check if a relay session is complete (all chunks forwarded).
     */
    fun isComplete(messageIdHex: String): Boolean {
        val session = sessions[messageIdHex] ?: return false
        return session.isComplete
    }

    /**
     * Remove a completed relay session and free its buffer.
     */
    fun removeSession(messageIdHex: String) {
        sessions.remove(messageIdHex)
    }

    /**
     * Total bytes buffered across all sessions.
     */
    fun totalBufferedBytes(): Int = sessions.values.sumOf { it.bufferBytes }

    /**
     * Whether adding more data would exceed the buffer limit.
     */
    fun isOverCapacity(): Boolean = totalBufferedBytes() >= maxBufferBytes

    /**
     * Clear all sessions.
     */
    fun clear() {
        sessions.clear()
    }

    /**
     * Number of active relay sessions.
     */
    fun sessionCount(): Int = sessions.size
}
