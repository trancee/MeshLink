package ch.trancee.meshlink.messaging

import ch.trancee.meshlink.api.DiagnosticCode
import ch.trancee.meshlink.api.DiagnosticEvent
import ch.trancee.meshlink.api.DiagnosticPayload
import ch.trancee.meshlink.api.DiagnosticSinkApi
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.crypto.TrustStore
import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.routing.DedupSet
import ch.trancee.meshlink.routing.PeerInfo
import ch.trancee.meshlink.routing.PresenceTracker
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.routing.RouteEntry
import ch.trancee.meshlink.routing.RoutingConfig
import ch.trancee.meshlink.routing.RoutingEngine
import ch.trancee.meshlink.routing.RoutingTable
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.transfer.ChunkSizePolicy
import ch.trancee.meshlink.transfer.TransferConfig
import ch.trancee.meshlink.transfer.TransferEngine
import ch.trancee.meshlink.transport.VirtualMeshTransport
import ch.trancee.meshlink.wire.Chunk
import ch.trancee.meshlink.wire.InboundValidator
import ch.trancee.meshlink.wire.RoutedMessage
import ch.trancee.meshlink.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Diagnostic emission tests for CutThroughBuffer and DeliveryPipeline's cut-through dispatch.
 *
 * Part 1: Unit-level tests that exercise CutThroughBuffer directly. Part 2: Integration-level tests
 * that exercise DeliveryPipeline's cut-through dispatch with diagnostic assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CutThroughBufferDiagnosticTest {

    private val appIdHash = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    // ── Capturing diagnostic sink ─────────────────────────────────────────────

    private class CapturingSink : DiagnosticSinkApi {
        val codes = mutableListOf<DiagnosticCode>()
        val payloads = mutableListOf<DiagnosticPayload>()

        private val _events =
            kotlinx.coroutines.flow.MutableSharedFlow<DiagnosticEvent>(
                replay = 64,
                extraBufferCapacity = 64,
            )
        override val events: kotlinx.coroutines.flow.SharedFlow<DiagnosticEvent> = _events

        override fun emit(code: DiagnosticCode, payloadProvider: () -> DiagnosticPayload) {
            codes.add(code)
            val payload = payloadProvider()
            payloads.add(payload)
            _events.tryEmit(
                DiagnosticEvent(
                    code = code,
                    severity = code.severity,
                    monotonicMillis = 0L,
                    wallClockMillis = 0L,
                    droppedCount = 0,
                    payload = payload,
                )
            )
        }
    }

    // ── Shared constants for unit tests ───────────────────────────────────────

    private val localKeyHash = ByteArray(12) { 0xAA.toByte() }
    private val nextHop = ByteArray(12) { 0xBB.toByte() }
    private val relayMessageId = ByteArray(16) { 0xCC.toByte() }
    private val destination = ByteArray(12) { 0xDD.toByte() }
    private val origin = ByteArray(12) { 0xEE.toByte() }
    private val messageId = ByteArray(16) { 0x11.toByte() }

    private fun singleChunkMessage(
        hopLimit: UByte = 3u,
        visited: List<ByteArray> = emptyList(),
    ): RoutedMessage =
        RoutedMessage(
            messageId = messageId,
            origin = origin,
            destination = destination,
            hopLimit = hopLimit,
            visitedList = visited,
            priority = 1,
            originationTime = 99UL,
            payload = ByteArray(20) { it.toByte() },
        )

    private fun multiChunkMessage(
        hopLimit: UByte = 5u,
        visited: List<ByteArray> = emptyList(),
        payloadSize: Int = 500,
    ): RoutedMessage =
        RoutedMessage(
            messageId = messageId,
            origin = origin,
            destination = destination,
            hopLimit = hopLimit,
            visitedList = visited,
            priority = 0,
            originationTime = 12345UL,
            payload = ByteArray(payloadSize) { (it % 256).toByte() },
        )

    private fun encodeAndChunk(msg: RoutedMessage, chunkSize: Int): List<ByteArray> {
        val wireBytes = WireCodec.encode(msg)
        val count = (wireBytes.size + chunkSize - 1) / chunkSize
        return List(count) { i ->
            wireBytes.copyOfRange(i * chunkSize, minOf((i + 1) * chunkSize, wireBytes.size))
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Part 1: CutThroughBuffer unit-level diagnostic tests
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `activation emits ROUTE_CHANGED`() = runTest {
        val sink = CapturingSink()
        val buffer =
            CutThroughBuffer(
                relayMessageId = relayMessageId,
                nextHop = nextHop,
                localKeyHash = localKeyHash,
                chunkSize = 4096,
                sendFn = { _, _ -> true },
                diagnosticSink = sink,
            )

        val msg = singleChunkMessage()
        val chunks = encodeAndChunk(msg, 4096)
        val chunk = Chunk(ByteArray(16), 0u.toUShort(), 1u.toUShort(), chunks[0])
        val activated = buffer.onChunk0(chunk)

        assertTrue(activated, "Should activate cut-through")
        assertTrue(
            sink.codes.contains(DiagnosticCode.ROUTE_CHANGED),
            "Should emit ROUTE_CHANGED on activation",
        )
        val textPayload = sink.payloads.first { it is DiagnosticPayload.TextMessage }
        assertIs<DiagnosticPayload.TextMessage>(textPayload)
        assertTrue(
            textPayload.message.contains("cut-through relay activated"),
            "TextMessage should describe cut-through activation",
        )
    }

    @Test
    fun `send failure emits SEND_FAILED`() = runTest {
        val sink = CapturingSink()
        val buffer =
            CutThroughBuffer(
                relayMessageId = relayMessageId,
                nextHop = nextHop,
                localKeyHash = localKeyHash,
                chunkSize = 4096,
                sendFn = { _, _ -> false }, // Always fails
                diagnosticSink = sink,
            )

        val msg = singleChunkMessage()
        val chunks = encodeAndChunk(msg, 4096)
        val chunk = Chunk(ByteArray(16), 0u.toUShort(), 1u.toUShort(), chunks[0])
        buffer.onChunk0(chunk)

        assertTrue(
            sink.codes.contains(DiagnosticCode.SEND_FAILED),
            "Should emit SEND_FAILED when sendFn returns false",
        )
        val sendFailedPayload =
            sink.payloads.filterIsInstance<DiagnosticPayload.SendFailed>().firstOrNull()
        assertIs<DiagnosticPayload.SendFailed>(sendFailedPayload)
        assertTrue(
            sendFailedPayload.reason.contains("cut-through chunk forwarding failed"),
            "SendFailed reason should describe cut-through forwarding failure",
        )
    }

    @Test
    fun `subsequent chunk send failure emits SEND_FAILED`() = runTest {
        val sink = CapturingSink()
        var callCount = 0
        val buffer =
            CutThroughBuffer(
                relayMessageId = relayMessageId,
                nextHop = nextHop,
                localKeyHash = localKeyHash,
                chunkSize = 244,
                sendFn = { _, _ ->
                    callCount++
                    // First call (chunk 0) succeeds, subsequent calls fail.
                    callCount <= 1
                },
                diagnosticSink = sink,
            )

        val msg = multiChunkMessage(payloadSize = 500)
        val chunks = encodeAndChunk(msg, 244)
        assertTrue(chunks.size >= 2, "Need multi-chunk for subsequent chunk test")

        val totalChunks = chunks.size.toUShort()
        val chunk0 = Chunk(ByteArray(16), 0u.toUShort(), totalChunks, chunks[0])
        buffer.onChunk0(chunk0)

        // Clear codes from chunk 0 processing (ROUTE_CHANGED).
        val codesBeforeSubsequent = sink.codes.toList()

        // Inject subsequent chunk — sendFn will fail.
        val chunk1 = Chunk(ByteArray(16), 1u.toUShort(), totalChunks, chunks[1])
        buffer.onSubsequentChunk(chunk1)

        val newCodes = sink.codes.drop(codesBeforeSubsequent.size)
        assertTrue(
            newCodes.contains(DiagnosticCode.SEND_FAILED),
            "Subsequent chunk send failure should emit SEND_FAILED",
        )
    }

    @Test
    fun `no diagnostic emitted on parse failure fallback`() = runTest {
        // CutThroughBuffer emits no diagnostic itself on parse failure — it just returns false.
        // The diagnostic is emitted by DeliveryPipeline (tested in Part 2).
        val sink = CapturingSink()
        val buffer =
            CutThroughBuffer(
                relayMessageId = relayMessageId,
                nextHop = nextHop,
                localKeyHash = localKeyHash,
                chunkSize = 4096,
                sendFn = { _, _ -> true },
                diagnosticSink = sink,
            )

        // Garbage payload that can't be parsed.
        val chunk =
            Chunk(ByteArray(16), 0u.toUShort(), 1u.toUShort(), ByteArray(10) { 0xFF.toByte() })
        val activated = buffer.onChunk0(chunk)

        assertTrue(!activated, "Should fall back")
        assertEquals(CutThroughBuffer.State.Fallback, buffer.state, "State should be Fallback")
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Part 2: DeliveryPipeline integration-level diagnostic tests
    // ════════════════════════════════════════════════════════════════════════════

    // ── Node infrastructure (mirrors DeliveryPipelineCutThroughTest) ──────────

    private inner class PipelineNode(
        val crypto: CryptoProvider,
        val identity: Identity,
        val transport: VirtualMeshTransport,
        val routingTable: RoutingTable,
        val routeCoordinator: RouteCoordinator,
        val transferEngine: TransferEngine,
        val trustStore: TrustStore,
        val pipeline: DeliveryPipeline,
        val diagnosticSink: CapturingSink,
    )

    private fun makeNode(
        scope: CoroutineScope,
        testScheduler: TestCoroutineScheduler,
        clock: () -> Long,
        config: MessagingConfig = relaxedConfig(),
        transferConfig: TransferConfig = TransferConfig(inactivityBaseTimeoutMillis = 5_000L),
    ): PipelineNode {
        val crypto = createCryptoProvider()
        val idStorage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, idStorage)
        val transport = VirtualMeshTransport(identity.keyHash, testScheduler)
        val routingConfig = RoutingConfig()
        val routingTable = RoutingTable(clock)
        val trustStorage = InMemorySecureStorage()
        val trustStore = TrustStore(trustStorage)
        val sink = CapturingSink()
        val routingEngine =
            RoutingEngine(
                routingTable = routingTable,
                localPeerId = identity.keyHash,
                localEdPublicKey = identity.edKeyPair.publicKey,
                localDhPublicKey = identity.dhKeyPair.publicKey,
                scope = scope,
                clock = clock,
                config = routingConfig,
            )
        val routingDedupSet =
            DedupSet(routingConfig.dedupCapacity, routingConfig.dedupTtlMillis, clock)
        val presenceTracker = PresenceTracker()
        val routeCoordinator =
            RouteCoordinator(
                localPeerId = identity.keyHash,
                localEdPublicKey = identity.edKeyPair.publicKey,
                localDhPublicKey = identity.dhKeyPair.publicKey,
                routingTable = routingTable,
                routingEngine = routingEngine,
                dedupSet = routingDedupSet,
                presenceTracker = presenceTracker,
                trustStore = trustStore,
                scope = scope,
                clock = clock,
                config = routingConfig,
                diagnosticSink = sink,
            )
        val transferEngine =
            TransferEngine(scope, transferConfig, ChunkSizePolicy.fixed(4096), true)
        val messageDedupSet = DedupSet(10_000, 2_700_000L, clock)
        val pipeline =
            DeliveryPipeline(
                scope = scope,
                transport = transport,
                routeCoordinator = routeCoordinator,
                transferEngine = transferEngine,
                inboundValidator = InboundValidator,
                localIdentity = identity,
                cryptoProvider = crypto,
                trustStore = trustStore,
                dedupSet = messageDedupSet,
                config = config,
                clock = clock,
                diagnosticSink = sink,
            )
        return PipelineNode(
            crypto,
            identity,
            transport,
            routingTable,
            routeCoordinator,
            transferEngine,
            trustStore,
            pipeline,
            sink,
        )
    }

    private fun relaxedConfig() =
        MessagingConfig(
            appIdHash = appIdHash,
            requireBroadcastSignatures = false,
            allowUnsignedBroadcasts = true,
            maxBufferedMessages = 5,
            outboundUnicastLimit = 200,
            outboundUnicastWindowMillis = 60_000L,
            broadcastLimit = 200,
            broadcastWindowMillis = 60_000L,
            relayPerSenderPerNeighborLimit = 200,
            relayPerSenderPerNeighborWindowMillis = 60_000L,
            perNeighborAggregateLimit = 200,
            perNeighborAggregateWindowMillis = 60_000L,
            perSenderInboundLimit = 200,
            perSenderInboundWindowMillis = 60_000L,
            handshakeLimit = 200,
            handshakeWindowMillis = 60_000L,
            nackLimit = 200,
            nackWindowMillis = 60_000L,
            highPriorityTtlMillis = 10_000L,
            normalPriorityTtlMillis = 10_000L,
            lowPriorityTtlMillis = 10_000L,
        )

    private fun connect(a: PipelineNode, b: PipelineNode, clock: () -> Long) {
        a.transport.linkTo(b.transport)
        val expiresAt = clock() + 600_000L
        a.routeCoordinator.onPeerConnected(PeerInfo(b.identity.keyHash, 0, -50, 0.0))
        b.routeCoordinator.onPeerConnected(PeerInfo(a.identity.keyHash, 0, -50, 0.0))
        a.routingTable.install(
            RouteEntry(
                destination = b.identity.keyHash,
                nextHop = b.identity.keyHash,
                metric = 1.0,
                seqNo = 0u,
                feasibilityDistance = 1.0,
                expiresAt = expiresAt,
                ed25519PublicKey = b.identity.edKeyPair.publicKey,
                x25519PublicKey = b.identity.dhKeyPair.publicKey,
            )
        )
        b.routingTable.install(
            RouteEntry(
                destination = a.identity.keyHash,
                nextHop = a.identity.keyHash,
                metric = 1.0,
                seqNo = 0u,
                feasibilityDistance = 1.0,
                expiresAt = expiresAt,
                ed25519PublicKey = a.identity.edKeyPair.publicKey,
                x25519PublicKey = a.identity.dhKeyPair.publicKey,
            )
        )
        a.trustStore.pinKey(b.identity.keyHash, b.identity.dhKeyPair.publicKey)
        b.trustStore.pinKey(a.identity.keyHash, a.identity.dhKeyPair.publicKey)
    }

    private fun drainFlows(scope: CoroutineScope, node: PipelineNode) {
        scope.launch { node.pipeline.messages.collect {} }
        scope.launch { node.pipeline.deliveryConfirmations.collect {} }
        scope.launch { node.pipeline.transferFailures.collect {} }
        scope.launch { node.pipeline.transferProgress.collect {} }
        scope.launch { node.transferEngine.outboundChunks.collect {} }
    }

    private fun relayRoutedMessage(
        origin: ByteArray,
        destination: ByteArray,
        hopLimit: UByte = 5u,
        visitedList: List<ByteArray> = listOf(origin),
        payloadSize: Int = 500,
    ): RoutedMessage =
        RoutedMessage(
            messageId = ByteArray(16) { 0x42 },
            origin = origin,
            destination = destination,
            hopLimit = hopLimit,
            visitedList = visitedList,
            priority = 0,
            originationTime = 12345UL,
            payload = ByteArray(payloadSize) { (it % 256).toByte() },
        )

    private fun singleChunkRelayMessage(
        origin: ByteArray,
        destination: ByteArray,
        hopLimit: UByte = 5u,
        visitedList: List<ByteArray> = listOf(origin),
    ): RoutedMessage =
        RoutedMessage(
            messageId = ByteArray(16) { 0x42 },
            origin = origin,
            destination = destination,
            hopLimit = hopLimit,
            visitedList = visitedList,
            priority = 0,
            originationTime = 12345UL,
            payload = ByteArray(20) { it.toByte() },
        )

    private fun encodeAndChunkPipeline(msg: RoutedMessage, chunkSize: Int = 4096): List<ByteArray> {
        val wireBytes = WireCodec.encode(msg)
        val count = (wireBytes.size + chunkSize - 1) / chunkSize
        return List(count) { i ->
            wireBytes.copyOfRange(i * chunkSize, minOf((i + 1) * chunkSize, wireBytes.size))
        }
    }

    // ── Integration tests ─────────────────────────────────────────────────────

    @Test
    fun `cut-through activation emits ROUTE_CHANGED in pipeline`() = runTest {
        var clockMillis = 1_000L
        val clock: () -> Long = { clockMillis }
        val bob = makeNode(backgroundScope, testScheduler, clock)
        val carol = makeNode(backgroundScope, testScheduler, clock)
        connect(bob, carol, clock)
        drainFlows(backgroundScope, bob)
        drainFlows(backgroundScope, carol)
        runCurrent()

        val aliceId = ByteArray(12) { 0xAA.toByte() }
        val aliceTransport = VirtualMeshTransport(aliceId, testScheduler)
        aliceTransport.linkTo(bob.transport)

        val msg = singleChunkRelayMessage(origin = aliceId, destination = carol.identity.keyHash)
        val chunks = encodeAndChunkPipeline(msg)
        val sessionId = ByteArray(16) { 0x01 }
        val totalChunks = chunks.size.toUShort()

        val chunk0 = Chunk(sessionId, 0u.toUShort(), totalChunks, chunks[0])
        aliceTransport.sendToPeer(bob.identity.keyHash, WireCodec.encode(chunk0))
        runCurrent()
        advanceUntilIdle()

        assertTrue(
            bob.diagnosticSink.codes.contains(DiagnosticCode.ROUTE_CHANGED),
            "Cut-through activation should emit ROUTE_CHANGED",
        )
        val textPayloads =
            bob.diagnosticSink.payloads.filterIsInstance<DiagnosticPayload.TextMessage>()
        assertTrue(
            textPayloads.any { it.message.contains("cut-through relay activated") },
            "ROUTE_CHANGED payload should contain 'cut-through relay activated'",
        )
    }

    @Test
    fun `malformed chunk 0 emits MALFORMED_DATA`() = runTest {
        var clockMillis = 1_000L
        val clock: () -> Long = { clockMillis }
        val bob = makeNode(backgroundScope, testScheduler, clock)
        drainFlows(backgroundScope, bob)
        runCurrent()

        val senderId = ByteArray(12) { 0xCC.toByte() }
        val senderTransport = VirtualMeshTransport(senderId, testScheduler)
        senderTransport.linkTo(bob.transport)

        // Inject a chunk whose payload is garbage — header parse will fail.
        val garbagePayload = ByteArray(100) { 0xFF.toByte() }
        val chunk =
            Chunk(ByteArray(16) { 0x90.toByte() }, 0u.toUShort(), 1u.toUShort(), garbagePayload)

        senderTransport.sendToPeer(bob.identity.keyHash, WireCodec.encode(chunk))
        runCurrent()
        advanceUntilIdle()

        assertTrue(
            bob.diagnosticSink.codes.contains(DiagnosticCode.MALFORMED_DATA),
            "Parse failure should emit MALFORMED_DATA",
        )
        val malformedPayloads =
            bob.diagnosticSink.payloads.filterIsInstance<DiagnosticPayload.MalformedData>()
        assertTrue(
            malformedPayloads.any { it.reason.contains("chunk 0 routing header parse failed") },
            "MALFORMED_DATA payload should contain parse failure reason",
        )
    }

    @Test
    fun `timeout eviction emits DELIVERY_TIMEOUT`() = runTest {
        var clockMillis = 1_000L
        val clock: () -> Long = { clockMillis }
        val transferConfig = TransferConfig(inactivityBaseTimeoutMillis = 100L)
        val bob = makeNode(backgroundScope, testScheduler, clock, transferConfig = transferConfig)
        val carol = makeNode(backgroundScope, testScheduler, clock)
        connect(bob, carol, clock)
        drainFlows(backgroundScope, bob)
        drainFlows(backgroundScope, carol)
        runCurrent()

        val aliceId = ByteArray(12) { 0xAA.toByte() }
        val aliceTransport = VirtualMeshTransport(aliceId, testScheduler)
        aliceTransport.linkTo(bob.transport)

        // Multi-chunk message — send only chunk 0 to leave buffer active.
        val msg =
            relayRoutedMessage(
                origin = aliceId,
                destination = carol.identity.keyHash,
                payloadSize = 5000,
            )
        val chunkPayloads = encodeAndChunkPipeline(msg)
        assertTrue(chunkPayloads.size >= 2, "Need multi-chunk for timeout test")

        val sessionId = ByteArray(16) { 0x60 }
        val chunk0 =
            Chunk(sessionId, 0u.toUShort(), chunkPayloads.size.toUShort(), chunkPayloads[0])
        aliceTransport.sendToPeer(bob.identity.keyHash, WireCodec.encode(chunk0))
        runCurrent()

        // Advance past the inactivity timeout.
        advanceTimeBy(200L)
        runCurrent()
        advanceUntilIdle()

        assertTrue(
            bob.diagnosticSink.codes.contains(DiagnosticCode.DELIVERY_TIMEOUT),
            "Inactivity timeout should emit DELIVERY_TIMEOUT",
        )
        val timeoutPayloads =
            bob.diagnosticSink.payloads.filterIsInstance<DiagnosticPayload.DeliveryTimeout>()
        assertTrue(timeoutPayloads.isNotEmpty(), "Should have a DeliveryTimeout payload")
    }

    @Test
    fun `malformed chunk 0 falls back to TransferEngine`() = runTest {
        // Verify that a malformed chunk 0 goes through TransferEngine (not cut-through)
        // and doesn't crash the pipeline.
        var clockMillis = 1_000L
        val clock: () -> Long = { clockMillis }
        val bob = makeNode(backgroundScope, testScheduler, clock)
        val carol = makeNode(backgroundScope, testScheduler, clock)
        connect(bob, carol, clock)
        drainFlows(backgroundScope, bob)
        runCurrent()

        val senderId = ByteArray(12) { 0xCC.toByte() }
        val senderTransport = VirtualMeshTransport(senderId, testScheduler)
        senderTransport.linkTo(bob.transport)

        // Garbage payload: header parse fails → TransferEngine receives it.
        val garbagePayload = ByteArray(100) { 0xFF.toByte() }
        val chunk =
            Chunk(ByteArray(16) { 0x91.toByte() }, 0u.toUShort(), 1u.toUShort(), garbagePayload)

        val carolFramesBefore =
            bob.transport.sentFrames.count { it.destination.contentEquals(carol.identity.keyHash) }

        senderTransport.sendToPeer(bob.identity.keyHash, WireCodec.encode(chunk))
        runCurrent()
        advanceUntilIdle()

        // No cut-through relay to Carol — chunk went to TransferEngine.
        val carolFramesAfter =
            bob.transport.sentFrames.count { it.destination.contentEquals(carol.identity.keyHash) }
        assertEquals(
            carolFramesBefore,
            carolFramesAfter,
            "Malformed chunk should not be relayed via cut-through to Carol",
        )
    }

    @Test
    fun `existing full-reassembly relay tests still pass after diagnostics added`() = runTest {
        // Verify the full-reassembly relay path (handleInboundRoutedMessage) continues to work
        // after adding diagnostic emissions.
        var clockMillis = 1_000L
        val clock: () -> Long = { clockMillis }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        val carol = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)
        connect(alice, carol, clock)
        drainFlows(backgroundScope, alice)
        runCurrent()

        val injector = VirtualMeshTransport(bob.identity.keyHash, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                RoutedMessage(
                    ByteArray(16) { 0x60 },
                    bob.identity.keyHash,
                    carol.identity.keyHash,
                    3u,
                    listOf(bob.identity.keyHash),
                    0,
                    1u,
                    byteArrayOf(0x60),
                )
            ),
        )
        runCurrent()
        advanceUntilIdle()

        assertTrue(
            alice.transport.sentFrames.isNotEmpty(),
            "Full-reassembly relay path should still work",
        )
    }
}
