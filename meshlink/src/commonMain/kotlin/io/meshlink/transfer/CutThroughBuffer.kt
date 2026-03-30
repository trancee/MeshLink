package io.meshlink.transfer

import io.meshlink.util.ByteArrayKey

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
        val messageId: ByteArrayKey,
        val totalChunks: Int,
        val nextHop: ByteArray,
        val chunks: MutableMap<Int, BufferedChunk> = mutableMapOf(),
        var forwardedCount: Int = 0,
    ) {
        val isComplete: Boolean get() = forwardedCount >= totalChunks
        val bufferBytes: Int get() = chunks.values.sumOf { it.data.size }
    }

    private val sessions = mutableMapOf<ByteArrayKey, RelaySession>()

    /**
     * Start a new relay session for a routed message.
     * Returns false if buffer capacity would be exceeded (no room for even one chunk).
     */
    fun startSession(messageId: ByteArrayKey, totalChunks: Int, nextHop: ByteArray): Boolean {
        if (sessions.containsKey(messageId)) return true
        if (totalBufferedBytes() >= maxBufferBytes) return false
        sessions[messageId] = RelaySession(messageId, totalChunks, nextHop.copyOf())
        return true
    }

    /**
     * Buffer a chunk for forwarding. Returns the chunk data if it should be forwarded
     * (new chunk), or null if it's a duplicate or the session doesn't exist.
     */
    fun bufferChunk(
        messageId: ByteArrayKey,
        sequenceNumber: Int,
        data: ByteArray,
        timestampMillis: Long = 0L,
    ): ByteArray? {
        val session = sessions[messageId] ?: return null
        if (session.chunks.containsKey(sequenceNumber)) return null

        val copy = data.copyOf()
        session.chunks[sequenceNumber] = BufferedChunk(sequenceNumber, copy, timestampMillis)
        session.forwardedCount++
        return copy
    }

    /**
     * Get a buffered chunk for retransmission.
     */
    fun getChunk(messageId: ByteArrayKey, sequenceNumber: Int): ByteArray? {
        val session = sessions[messageId] ?: return null
        return session.chunks[sequenceNumber]?.data
    }

    /**
     * Mark a chunk as acknowledged by the next-hop. Frees the chunk buffer.
     */
    fun ackChunk(messageId: ByteArrayKey, sequenceNumber: Int) {
        val session = sessions[messageId] ?: return
        session.chunks.remove(sequenceNumber)
    }

    /**
     * Check if a relay session is complete (all chunks forwarded).
     */
    fun isComplete(messageId: ByteArrayKey): Boolean {
        val session = sessions[messageId] ?: return false
        return session.isComplete
    }

    /**
     * Remove a completed relay session and free its buffer.
     */
    fun removeSession(messageId: ByteArrayKey) {
        sessions.remove(messageId)
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
