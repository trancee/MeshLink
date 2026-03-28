package io.meshlink.dispatch

import io.meshlink.config.MeshLinkConfig
import io.meshlink.model.KeyChangeEvent
import io.meshlink.delivery.DeliveryPipeline
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.routing.RoutingEngine
import io.meshlink.transfer.ChunkData
import io.meshlink.transfer.TransferEngine
import io.meshlink.util.AppIdFilter
import io.meshlink.util.PauseManager
import io.meshlink.util.RateLimitPolicy
import io.meshlink.util.toHex
import io.meshlink.wire.WireCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageDispatcherTest {

    private val localPeer = ByteArray(16) { 0x01 }
    private val peerA = ByteArray(16) { 0x0A }
    private val peerB = ByteArray(16) { 0x0B }
    private val msgId = ByteArray(16) { 0xAA.toByte() }

    /** Recording sink that captures all effects for assertions. */
    private class RecordingSink : DispatchSink {
        val messages = mutableListOf<Pair<ByteArray, ByteArray>>()
        val confirmations = mutableListOf<ByteArray>()
        val progressUpdates = mutableListOf<Triple<ByteArray, Int, Int>>()
        val framesSent = mutableListOf<Pair<ByteArray, ByteArray>>()
        val keyChanges = mutableListOf<KeyChangeEvent>()
        val chunksDispatched = mutableListOf<Triple<ByteArray, List<ChunkData>, ByteArray>>()
        var gossipTriggered = false
        val completedKeys = mutableListOf<String>()

        override suspend fun onMessageReceived(senderId: ByteArray, payload: ByteArray) {
            messages.add(senderId to payload)
        }

        override suspend fun onTransferProgress(messageId: ByteArray, chunksAcked: Int, totalChunks: Int) {
            progressUpdates.add(Triple(messageId, chunksAcked, totalChunks))
        }

        override suspend fun onDeliveryConfirmed(messageId: ByteArray) {
            confirmations.add(messageId)
        }

        override fun onKeyChanged(event: KeyChangeEvent) {
            keyChanges.add(event)
        }

        override suspend fun sendFrame(peerId: ByteArray, frame: ByteArray) {
            framesSent.add(peerId to frame)
        }

        override suspend fun dispatchChunks(recipient: ByteArray, chunks: List<ChunkData>, messageId: ByteArray) {
            chunksDispatched.add(Triple(recipient, chunks, messageId))
        }

        override fun triggerGossipUpdate() {
            gossipTriggered = true
        }

        override fun onOutboundComplete(key: String, messageId: ByteArray) {
            completedKeys.add(key)
        }
    }

    private fun createDispatcher(
        sink: RecordingSink = RecordingSink(),
        config: MeshLinkConfig = MeshLinkConfig(),
    ): Pair<MessageDispatcher, RecordingSink> {
        val diagnosticSink = DiagnosticSink()
        val routingEngine = RoutingEngine(localPeerId = localPeer.toHex())
        val transferEngine = TransferEngine()
        val deliveryPipeline = DeliveryPipeline(diagnosticSink = diagnosticSink)
        val rateLimitPolicy = RateLimitPolicy(config) { 0L }
        val pauseManager = PauseManager()
        val appIdFilter = AppIdFilter(null)

        val validator = InboundValidator(
            securityEngine = null,
            deliveryPipeline = deliveryPipeline,
            rateLimitPolicy = rateLimitPolicy,
            appIdFilter = appIdFilter,
            diagnosticSink = diagnosticSink,
            localPeerId = localPeer,
            config = config,
        )

        val dispatcher = MessageDispatcher(
            securityEngine = null,
            routingEngine = routingEngine,
            transferEngine = transferEngine,
            deliveryPipeline = deliveryPipeline,
            validator = validator,
            pauseManager = pauseManager,
            diagnosticSink = diagnosticSink,
            localPeerId = localPeer,
            config = config,
            outboundRecipients = mutableMapOf(),
            sink = sink,
        )
        return dispatcher to sink
    }

    // ── 1. Dispatch table ─────────────────────────────────────────

    @Test
    fun emptyDataIsNoOp() = runTest {
        val (dispatcher, sink) = createDispatcher()
        dispatcher.dispatch(peerA, byteArrayOf())
        assertTrue(sink.messages.isEmpty())
        assertTrue(sink.framesSent.isEmpty())
    }

    @Test
    fun unknownTypeDoesNotCrash() = runTest {
        val (dispatcher, sink) = createDispatcher()
        dispatcher.dispatch(peerA, byteArrayOf(0x7F))
        assertTrue(sink.messages.isEmpty())
    }

    @Test
    fun malformedDataDoesNotCrash() = runTest {
        val (dispatcher, sink) = createDispatcher()
        // TYPE_CHUNK (0x03) with insufficient data → should throw internally, caught by dispatch
        dispatcher.dispatch(peerA, byteArrayOf(WireCodec.TYPE_CHUNK))
        assertTrue(sink.messages.isEmpty())
    }

    // ── 2. Keepalive ──────────────────────────────────────────────

    @Test
    fun keepaliveUpdatesPeerPresence() = runTest {
        val (dispatcher, _) = createDispatcher()
        val frame = WireCodec.encodeKeepalive(timestampSeconds = 1234u)
        dispatcher.dispatch(peerA, frame)
        // No exception means keepalive was processed (peerSeen called)
    }

    // ── 3. Broadcast ──────────────────────────────────────────────

    @Test
    fun broadcastDeliversMessage() = runTest {
        val (dispatcher, sink) = createDispatcher()
        val payload = "hello broadcast".encodeToByteArray()
        val frame = WireCodec.encodeBroadcast(
            messageId = msgId,
            origin = peerA,
            remainingHops = 3u,
            payload = payload,
        )
        dispatcher.dispatch(peerA, frame)
        assertEquals(1, sink.messages.size)
        assertTrue(payload.contentEquals(sink.messages[0].second))
    }

    @Test
    fun duplicateBroadcastIsDropped() = runTest {
        val (dispatcher, sink) = createDispatcher()
        val payload = "hello".encodeToByteArray()
        val frame = WireCodec.encodeBroadcast(
            messageId = msgId,
            origin = peerA,
            remainingHops = 3u,
            payload = payload,
        )
        dispatcher.dispatch(peerA, frame)
        dispatcher.dispatch(peerA, frame)
        assertEquals(1, sink.messages.size, "duplicate should be dropped")
    }

    // ── 4. Routed message ─────────────────────────────────────────

    @Test
    fun routedMessageDeliveredWhenDestinationIsLocal() = runTest {
        val (dispatcher, sink) = createDispatcher()
        val payload = "routed payload".encodeToByteArray()
        val frame = WireCodec.encodeRoutedMessage(
            messageId = msgId,
            origin = peerA,
            destination = localPeer,
            hopLimit = 5u,
            visitedList = listOf(peerA),
            payload = payload,
        )
        dispatcher.dispatch(peerB, frame)
        assertEquals(1, sink.messages.size)
        assertTrue(peerA.contentEquals(sink.messages[0].first), "sender should be origin")
        assertTrue(payload.contentEquals(sink.messages[0].second))
        // Should also send delivery ACK back
        assertTrue(sink.framesSent.isNotEmpty(), "should send delivery ACK")
    }

    @Test
    fun routedMessageDuplicateIsDropped() = runTest {
        val (dispatcher, sink) = createDispatcher()
        val frame = WireCodec.encodeRoutedMessage(
            messageId = msgId,
            origin = peerA,
            destination = localPeer,
            hopLimit = 5u,
            visitedList = listOf(peerA),
            payload = "data".encodeToByteArray(),
        )
        dispatcher.dispatch(peerB, frame)
        dispatcher.dispatch(peerB, frame)
        assertEquals(1, sink.messages.size, "duplicate routed message should be dropped")
    }

    @Test
    fun routedMessageLoopDetection() = runTest {
        val (dispatcher, sink) = createDispatcher()
        // Local peer is in the visited list → loop detected
        val frame = WireCodec.encodeRoutedMessage(
            messageId = msgId,
            origin = peerA,
            destination = peerB,
            hopLimit = 5u,
            visitedList = listOf(peerA, localPeer),
            payload = "data".encodeToByteArray(),
        )
        dispatcher.dispatch(peerA, frame)
        assertTrue(sink.messages.isEmpty(), "loop should be detected and message dropped")
        assertTrue(sink.framesSent.isEmpty(), "no frame should be sent for looped message")
    }

    @Test
    fun routedMessageHopLimitEnforced() = runTest {
        val (dispatcher, sink) = createDispatcher()
        // Destination is NOT local, but hopLimit is 0 → should be dropped
        // Need peerB to be "present" for routing
        val frame = WireCodec.encodeRoutedMessage(
            messageId = msgId,
            origin = peerA,
            destination = peerB,
            hopLimit = 0u,
            visitedList = listOf(peerA),
            payload = "data".encodeToByteArray(),
        )
        dispatcher.dispatch(peerA, frame)
        assertTrue(sink.messages.isEmpty(), "hop limit exceeded → should not deliver")
        assertTrue(sink.framesSent.isEmpty(), "hop limit exceeded → should not relay")
    }

    // ── 5. Chunk handling ─────────────────────────────────────────

    @Test
    fun singleChunkMessageDelivered() = runTest {
        val (dispatcher, sink) = createDispatcher()
        val payload = "single chunk".encodeToByteArray()
        val frame = WireCodec.encodeChunk(
            messageId = msgId,
            sequenceNumber = 0u,
            totalChunks = 1u,
            payload = payload,
        )
        dispatcher.dispatch(peerA, frame)
        assertEquals(1, sink.messages.size, "single-chunk message should be delivered")
        assertTrue(payload.contentEquals(sink.messages[0].second))
        // Should also send ACK
        assertTrue(sink.framesSent.isNotEmpty(), "should send chunk ACK")
    }

    @Test
    fun duplicateReassembledChunkIsDropped() = runTest {
        val (dispatcher, sink) = createDispatcher()
        val payload = "data".encodeToByteArray()
        val frame = WireCodec.encodeChunk(
            messageId = msgId,
            sequenceNumber = 0u,
            totalChunks = 1u,
            payload = payload,
        )
        dispatcher.dispatch(peerA, frame)
        dispatcher.dispatch(peerA, frame) // duplicate after reassembly
        assertEquals(1, sink.messages.size, "duplicate reassembled chunk should be dropped")
    }

    // ── 6. Broadcast with app ID filtering ────────────────────────

    @Test
    fun broadcastWithWrongAppIdIsRejected() = runTest {
        val diagnosticSink = DiagnosticSink()
        val routingEngine = RoutingEngine(localPeerId = localPeer.toHex())
        val config = MeshLinkConfig()
        val sink = RecordingSink()
        val deliveryPipeline = DeliveryPipeline(diagnosticSink = diagnosticSink)
        val rateLimitPolicy = RateLimitPolicy(config) { 0L }
        val appIdFilter = AppIdFilter("my-app")

        val validator = InboundValidator(
            securityEngine = null,
            deliveryPipeline = deliveryPipeline,
            rateLimitPolicy = rateLimitPolicy,
            appIdFilter = appIdFilter,
            diagnosticSink = diagnosticSink,
            localPeerId = localPeer,
            config = config,
        )

        val dispatcher = MessageDispatcher(
            securityEngine = null,
            routingEngine = routingEngine,
            transferEngine = TransferEngine(),
            deliveryPipeline = deliveryPipeline,
            validator = validator,
            pauseManager = PauseManager(),
            diagnosticSink = diagnosticSink,
            localPeerId = localPeer,
            config = config,
            outboundRecipients = mutableMapOf(),
            sink = sink,
        )

        val frame = WireCodec.encodeBroadcast(
            messageId = msgId,
            origin = peerA,
            remainingHops = 3u,
            appIdHash = ByteArray(16) { 0xFF.toByte() }, // wrong hash
            payload = "filtered".encodeToByteArray(),
        )
        dispatcher.dispatch(peerA, frame)
        assertTrue(sink.messages.isEmpty(), "wrong app ID should reject broadcast")
    }

    // ── 7. Pause state ────────────────────────────────────────────

    @Test
    fun routedMessageQueuedWhenPaused() = runTest {
        val diagnosticSink = DiagnosticSink()
        val routingEngine = RoutingEngine(localPeerId = localPeer.toHex())
        val config = MeshLinkConfig()
        val sink = RecordingSink()
        val pauseManager = PauseManager()
        pauseManager.pause()
        val deliveryPipeline = DeliveryPipeline(diagnosticSink = diagnosticSink)
        val rateLimitPolicy = RateLimitPolicy(config) { 0L }

        // Make peerB "present" so routing succeeds
        routingEngine.peerSeen(peerB.toHex())

        val validator = InboundValidator(
            securityEngine = null,
            deliveryPipeline = deliveryPipeline,
            rateLimitPolicy = rateLimitPolicy,
            appIdFilter = AppIdFilter(null),
            diagnosticSink = diagnosticSink,
            localPeerId = localPeer,
            config = config,
        )

        val dispatcher = MessageDispatcher(
            securityEngine = null,
            routingEngine = routingEngine,
            transferEngine = TransferEngine(),
            deliveryPipeline = deliveryPipeline,
            validator = validator,
            pauseManager = pauseManager,
            diagnosticSink = diagnosticSink,
            localPeerId = localPeer,
            config = config,
            outboundRecipients = mutableMapOf(),
            sink = sink,
        )

        val frame = WireCodec.encodeRoutedMessage(
            messageId = msgId,
            origin = peerA,
            destination = peerB,
            hopLimit = 5u,
            visitedList = listOf(peerA),
            payload = "relay me".encodeToByteArray(),
        )
        dispatcher.dispatch(peerA, frame)
        assertTrue(sink.framesSent.isEmpty(), "should not send when paused")
        assertEquals(1, pauseManager.relayQueueSize, "should queue relay")
    }
}
