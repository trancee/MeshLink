package io.meshlink

import io.meshlink.config.MeshLinkConfig
import io.meshlink.model.Message
import io.meshlink.model.PeerEvent
import io.meshlink.transport.BleTransport
import io.meshlink.wire.WireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class MeshLink(
    private val transport: BleTransport,
    private val config: MeshLinkConfig = MeshLinkConfig(),
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
) : MeshLinkApi {

    private val _peers = MutableSharedFlow<PeerEvent>(replay = 64)
    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    private val _deliveryConfirmations = MutableSharedFlow<Uuid>(extraBufferCapacity = 64)

    override val peers: Flow<PeerEvent> = _peers.asSharedFlow()
    override val messages: Flow<Message> = _messages.asSharedFlow()
    override val deliveryConfirmations: Flow<Uuid> = _deliveryConfirmations.asSharedFlow()

    private var started = false
    private var paused = false
    private var scope: CoroutineScope? = null
    private val baseContext = coroutineContext

    // Reassembly buffer: messageId hex → (totalChunks, received chunks map)
    private val reassembly = mutableMapOf<String, ReassemblyState>()

    // Queued sends while paused: (recipient, payload)
    private val pauseQueue = mutableListOf<Pair<ByteArray, ByteArray>>()

    override fun start(): Result<Unit> {
        if (started) return Result.success(Unit)
        started = true
        val newScope = CoroutineScope(baseContext + SupervisorJob())
        scope = newScope

        newScope.launch {
            transport.startAdvertisingAndScanning()
        }
        newScope.launch {
            transport.advertisementEvents.collect { event ->
                if (!paused) {
                    _peers.emit(PeerEvent.Discovered(event.peerId))
                }
            }
        }
        newScope.launch {
            transport.peerLostEvents.collect { event ->
                _peers.emit(PeerEvent.Lost(event.peerId))
            }
        }
        newScope.launch {
            transport.incomingData.collect { incoming ->
                handleIncomingData(incoming.peerId, incoming.data)
            }
        }

        return Result.success(Unit)
    }

    override fun stop() {
        started = false
        reassembly.clear()
        scope?.cancel()
        scope = null
    }

    override fun pause() {
        paused = true
        scope?.launch { transport.stopAll() }
    }

    override fun resume() {
        paused = false
        scope?.launch { transport.startAdvertisingAndScanning() }
        val s = scope ?: return
        val queued = pauseQueue.toList()
        pauseQueue.clear()
        for ((recipient, payload) in queued) {
            doSend(s, recipient, payload)
        }
    }

    override fun send(recipient: ByteArray, payload: ByteArray): Result<Uuid> {
        if (!started) throw IllegalStateException("MeshLink not started")
        val s = scope ?: throw IllegalStateException("MeshLink not started")

        if (payload.size > config.bufferCapacity) {
            return Result.failure(IllegalArgumentException("bufferFull"))
        }

        if (paused) {
            pauseQueue.add(recipient to payload)
            return Result.success(Uuid.random())
        }

        return doSend(s, recipient, payload)
    }

    private fun doSend(s: CoroutineScope, recipient: ByteArray, payload: ByteArray): Result<Uuid> {
        val messageId = Uuid.random().toByteArray()
        val chunkSize = config.mtu - WireCodec.CHUNK_HEADER_SIZE
        val chunks = payload.asSequence()
            .chunked(chunkSize)
            .map { it.toByteArray() }
            .toList()
        val totalChunks = chunks.size.coerceAtLeast(1)

        if (chunks.isEmpty()) {
            val encoded = WireCodec.encodeChunk(messageId, 0u, 1u, ByteArray(0))
            s.launch { transport.sendToPeer(recipient, encoded) }
        } else {
            chunks.forEachIndexed { index, chunkPayload ->
                val encoded = WireCodec.encodeChunk(
                    messageId = messageId,
                    sequenceNumber = index.toUShort(),
                    totalChunks = totalChunks.toUShort(),
                    payload = chunkPayload,
                )
                s.launch { transport.sendToPeer(recipient, encoded) }
            }
        }

        return Result.success(Uuid.fromByteArray(messageId))
    }

    private suspend fun handleIncomingData(fromPeerId: ByteArray, data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0]) {
            WireCodec.TYPE_CHUNK -> handleChunk(fromPeerId, data)
            WireCodec.TYPE_CHUNK_ACK -> handleChunkAck(data)
        }
    }

    private suspend fun handleChunk(fromPeerId: ByteArray, data: ByteArray) {
        val chunk = WireCodec.decodeChunk(data)
        val key = chunk.messageId.toHex()

        val state = reassembly.getOrPut(key) {
            ReassemblyState(chunk.totalChunks.toInt())
        }
        state.chunks[chunk.sequenceNumber.toInt()] = chunk.payload

        if (state.chunks.size == state.totalChunks) {
            reassembly.remove(key)
            val fullPayload = (0 until state.totalChunks)
                .map { state.chunks[it]!! }
                .reduce { acc, bytes -> acc + bytes }
            _messages.emit(Message(senderId = fromPeerId, payload = fullPayload))

            // Send chunk_ack back to sender
            val ack = WireCodec.encodeChunkAck(
                messageId = chunk.messageId,
                ackSequence = (state.totalChunks - 1).toUShort(),
                sackBitmask = 0u,
            )
            scope?.launch { transport.sendToPeer(fromPeerId, ack) }
        }
    }

    private suspend fun handleChunkAck(data: ByteArray) {
        val ack = WireCodec.decodeChunkAck(data)
        _deliveryConfirmations.emit(Uuid.fromByteArray(ack.messageId))
    }
}

private class ReassemblyState(val totalChunks: Int) {
    val chunks = mutableMapOf<Int, ByteArray>()
}

private fun ByteArray.toHex(): String =
    joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
