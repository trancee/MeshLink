package io.meshlink.dispatch

import io.meshlink.model.KeyChangeEvent
import io.meshlink.transfer.ChunkData
import kotlin.uuid.Uuid

/**
 * Callback interface for side-effectful actions that handlers need
 * MeshLink to perform (flow emission, transport sends, coroutine launches).
 */
interface DispatchSink {
    suspend fun onMessageReceived(senderId: ByteArray, payload: ByteArray)
    suspend fun onTransferProgress(messageId: ByteArray, chunksAcked: Int, totalChunks: Int)
    suspend fun onDeliveryConfirmed(messageId: ByteArray)
    fun onKeyChanged(event: KeyChangeEvent)
    suspend fun sendFrame(peerId: ByteArray, frame: ByteArray)
    suspend fun dispatchChunks(recipient: ByteArray, chunks: List<ChunkData>, messageId: ByteArray)
    fun triggerGossipUpdate()
    fun onOutboundComplete(key: String, messageId: ByteArray)
}
