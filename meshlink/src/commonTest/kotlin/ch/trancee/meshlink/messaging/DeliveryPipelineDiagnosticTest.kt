package ch.trancee.meshlink.messaging

import ch.trancee.meshlink.api.DiagnosticCode
import ch.trancee.meshlink.api.DiagnosticPayload
import ch.trancee.meshlink.api.DiagnosticSink
import ch.trancee.meshlink.api.DiagnosticSinkApi
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.crypto.TrustStore
import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.crypto.noise.noiseKSeal
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
import ch.trancee.meshlink.transfer.Priority
import ch.trancee.meshlink.transfer.TransferConfig
import ch.trancee.meshlink.transfer.TransferEngine
import ch.trancee.meshlink.transport.VirtualMeshTransport
import ch.trancee.meshlink.wire.Hello
import ch.trancee.meshlink.wire.InboundValidator
import ch.trancee.meshlink.wire.RoutedMessage
import ch.trancee.meshlink.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
 * Unit tests for the 8 diagnostic emit sites wired into [DeliveryPipeline].
 *
 * Each test creates a [PipelineNode] with a real [DiagnosticSink], triggers the code path that
 * fires the emit, then asserts `diagnosticSink.events.replayCache.lastOrNull()` has the correct
 * [DiagnosticCode] and payload type.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryPipelineDiagnosticTest {

    private val appIdHash = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    // ── Node infrastructure ──────────────────────────────────────────────────

    private class PipelineNode(
        val crypto: CryptoProvider,
        val identity: Identity,
        val transport: VirtualMeshTransport,
        val routingTable: RoutingTable,
        val routeCoordinator: RouteCoordinator,
        val transferEngine: TransferEngine,
        val trustStore: TrustStore,
        val pipeline: DeliveryPipeline,
        val diagnosticSink: DiagnosticSinkApi,
    )

    private fun makeNode(
        scope: CoroutineScope,
        testScheduler: TestCoroutineScheduler,
        clock: () -> Long,
        config: MessagingConfig = relaxedConfig(),
        transferConfig: TransferConfig = TransferConfig(inactivityBaseTimeoutMillis = 5_000L),
        cryptoOverride: CryptoProvider? = null,
    ): PipelineNode {
        val crypto = createCryptoProvider()
        val pipelineCrypto = cryptoOverride ?: crypto
        val idStorage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, idStorage)
        val transport = VirtualMeshTransport(identity.keyHash, testScheduler)
        val routingConfig = RoutingConfig()
        val routingTable = RoutingTable(clock)
        val trustStorage = InMemorySecureStorage()
        val trustStore = TrustStore(trustStorage)
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
        val diagnosticSink =
            DiagnosticSink(bufferCapacity = 64, redactFn = null, clock = clock, wallClock = clock)
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
                diagnosticSink = diagnosticSink,
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
                cryptoProvider = pipelineCrypto,
                trustStore = trustStore,
                dedupSet = messageDedupSet,
                config = config,
                clock = clock,
                diagnosticSink = diagnosticSink,
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
            diagnosticSink,
        )
    }

    private fun relaxedConfig(
        requireBroadcastSignatures: Boolean = false,
        allowUnsignedBroadcasts: Boolean = true,
        maxBufferedMessages: Int = 5,
    ) =
        MessagingConfig(
            appIdHash = appIdHash,
            requireBroadcastSignatures = requireBroadcastSignatures,
            allowUnsignedBroadcasts = allowUnsignedBroadcasts,
            maxBufferedMessages = maxBufferedMessages,
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
            circuitBreaker =
                MessagingConfig.CircuitBreakerConfig(
                    windowMillis = 60_000L,
                    maxFailures = 3,
                    cooldownMillis = 5_000L,
                ),
        )

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Link two nodes and install direct route entries so they can send to each other. */
    private fun linkNodes(a: PipelineNode, b: PipelineNode, clock: () -> Long) {
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

    /** Drain all pipeline flows so events don't back-pressure. */
    private fun drainFlows(scope: CoroutineScope, node: PipelineNode) {
        scope.launch { node.pipeline.messages.collect {} }
        scope.launch { node.pipeline.deliveryConfirmations.collect {} }
        scope.launch { node.pipeline.transferFailures.collect {} }
        scope.launch { node.pipeline.transferProgress.collect {} }
        scope.launch { node.transferEngine.outboundChunks.collect {} }
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    // 1. MALFORMED_DATA — InboundValidator rejects a frame
    @Test
    fun malformedDataEmitsOnRejectedFrame() = runTest {
        var time = 0L
        val node = makeNode(backgroundScope, testScheduler, { time })
        drainFlows(backgroundScope, node)
        runCurrent() // start collectors

        // Inject raw garbage bytes that InboundValidator will reject
        node.transport.injectRawIncoming(
            ByteArray(32) { 0xAA.toByte() },
            byteArrayOf(0xFF.toByte()),
        )
        runCurrent()

        val event = node.diagnosticSink.events.replayCache.lastOrNull()
        assertNotNull(event, "Expected MALFORMED_DATA diagnostic event")
        assertEquals(DiagnosticCode.MALFORMED_DATA, event.code)
        val payload = assertIs<DiagnosticPayload.MalformedData>(event.payload)
        assertEquals("InboundValidator rejected frame", payload.reason)
    }

    // 2. DECRYPTION_FAILED — AEAD tag verification fails
    @Test
    fun decryptionFailedEmitsOnBadPayload() = runTest {
        var time = 0L
        val sender = makeNode(backgroundScope, testScheduler, { time })
        val receiver = makeNode(backgroundScope, testScheduler, { time })
        linkNodes(sender, receiver, { time })
        drainFlows(backgroundScope, receiver)
        runCurrent()

        // Build a routed message with garbage payload (not a valid Noise K sealed box)
        val badMsg =
            RoutedMessage(
                messageId = ByteArray(16) { it.toByte() },
                origin = sender.identity.keyHash,
                destination = receiver.identity.keyHash,
                hopLimit = 5u,
                visitedList = listOf(sender.identity.keyHash),
                priority = Priority.NORMAL.wire,
                originationTime = 1000uL,
                payload = ByteArray(64) { 0xBB.toByte() }, // garbage — AEAD will fail
            )
        val encoded = WireCodec.encode(badMsg)
        receiver.transport.injectRawIncoming(sender.identity.keyHash, encoded)
        runCurrent()

        val event = receiver.diagnosticSink.events.replayCache.lastOrNull()
        assertNotNull(event, "Expected DECRYPTION_FAILED diagnostic event")
        assertEquals(DiagnosticCode.DECRYPTION_FAILED, event.code)
        val payload = assertIs<DiagnosticPayload.DecryptionFailed>(event.payload)
        assertTrue(payload.peerId.hex.isNotEmpty(), "peerId should be non-empty hex")
    }

    // 3. REPLAY_REJECTED — replay guard rejects a duplicate originationTime
    @Test
    fun replayRejectedEmitsOnDuplicateMessage() = runTest {
        var time = 0L
        val sender = makeNode(backgroundScope, testScheduler, { time })
        val receiver = makeNode(backgroundScope, testScheduler, { time })
        linkNodes(sender, receiver, { time })
        drainFlows(backgroundScope, receiver)
        runCurrent()

        val sealedPayload =
            noiseKSeal(
                sender.crypto,
                sender.identity.dhKeyPair,
                receiver.identity.dhKeyPair.publicKey,
                "hello".encodeToByteArray(),
            )
        val msg =
            RoutedMessage(
                messageId = ByteArray(16) { it.toByte() },
                origin = sender.identity.keyHash,
                destination = receiver.identity.keyHash,
                hopLimit = 5u,
                visitedList = listOf(sender.identity.keyHash),
                priority = Priority.NORMAL.wire,
                originationTime = 42uL,
                payload = sealedPayload,
            )
        val encoded = WireCodec.encode(msg)

        // First delivery — accepted
        receiver.transport.injectRawIncoming(sender.identity.keyHash, encoded)
        runCurrent()

        // Second delivery with same originationTime — replay rejected
        receiver.transport.injectRawIncoming(sender.identity.keyHash, encoded)
        runCurrent()

        val event = receiver.diagnosticSink.events.replayCache.lastOrNull()
        assertNotNull(event, "Expected REPLAY_REJECTED diagnostic event")
        assertEquals(DiagnosticCode.REPLAY_REJECTED, event.code)
        assertIs<DiagnosticPayload.ReplayRejected>(event.payload)
    }

    // 4. HOP_LIMIT_EXCEEDED — relay with hopLimit == 0
    @Test
    fun hopLimitExceededEmitsOnZeroHopRelay() = runTest {
        var time = 0L
        val relay = makeNode(backgroundScope, testScheduler, { time })
        val recipient = makeNode(backgroundScope, testScheduler, { time })
        val fakeSender = makeNode(backgroundScope, testScheduler, { time })
        linkNodes(relay, recipient, { time })
        drainFlows(backgroundScope, relay)
        runCurrent()

        // Message addressed to recipient but arriving at relay with hopLimit=0
        val msg =
            RoutedMessage(
                messageId = ByteArray(16) { it.toByte() },
                origin = fakeSender.identity.keyHash,
                destination = recipient.identity.keyHash,
                hopLimit = 0u,
                visitedList = listOf(fakeSender.identity.keyHash),
                priority = Priority.NORMAL.wire,
                originationTime = 1000uL,
                payload = ByteArray(64) { 0xCC.toByte() },
            )
        val encoded = WireCodec.encode(msg)
        relay.transport.injectRawIncoming(fakeSender.identity.keyHash, encoded)
        runCurrent()

        val event = relay.diagnosticSink.events.replayCache.lastOrNull()
        assertNotNull(event, "Expected HOP_LIMIT_EXCEEDED diagnostic event")
        assertEquals(DiagnosticCode.HOP_LIMIT_EXCEEDED, event.code)
        val payload = assertIs<DiagnosticPayload.HopLimitExceeded>(event.payload)
        assertEquals(1, payload.hops) // visitedList has 1 entry
        assertEquals(5, payload.maxHops) // DEFAULT_HOP_LIMIT
    }

    // 5. LOOP_DETECTED — relay node's own keyHash is in visitedList
    @Test
    fun loopDetectedEmitsWhenLocalNodeInVisitedList() = runTest {
        var time = 0L
        val relay = makeNode(backgroundScope, testScheduler, { time })
        val recipient = makeNode(backgroundScope, testScheduler, { time })
        val fakeSender = makeNode(backgroundScope, testScheduler, { time })
        linkNodes(relay, recipient, { time })
        drainFlows(backgroundScope, relay)
        runCurrent()

        // Message addressed to recipient but relay's own keyHash is already in visitedList
        val msg =
            RoutedMessage(
                messageId = ByteArray(16) { it.toByte() },
                origin = fakeSender.identity.keyHash,
                destination = recipient.identity.keyHash,
                hopLimit = 5u,
                visitedList = listOf(fakeSender.identity.keyHash, relay.identity.keyHash), // loop!
                priority = Priority.NORMAL.wire,
                originationTime = 1000uL,
                payload = ByteArray(64) { 0xDD.toByte() },
            )
        val encoded = WireCodec.encode(msg)
        relay.transport.injectRawIncoming(fakeSender.identity.keyHash, encoded)
        runCurrent()

        val event = relay.diagnosticSink.events.replayCache.lastOrNull()
        assertNotNull(event, "Expected LOOP_DETECTED diagnostic event")
        assertEquals(DiagnosticCode.LOOP_DETECTED, event.code)
        val payload = assertIs<DiagnosticPayload.LoopDetected>(event.payload)
        assertTrue(payload.destination.hex.isNotEmpty())
    }

    // 6. DELIVERY_TIMEOUT — ACK deadline fires without ACK
    @Test
    fun deliveryTimeoutEmitsOnAckDeadline() = runTest {
        var time = 0L
        val sender = makeNode(backgroundScope, testScheduler, { time })
        val receiver = makeNode(backgroundScope, testScheduler, { time })
        linkNodes(sender, receiver, { time })
        drainFlows(backgroundScope, sender)
        drainFlows(backgroundScope, receiver)
        runCurrent()

        sender.pipeline.send(receiver.identity.keyHash, "test".encodeToByteArray())
        runCurrent()

        // Advance past the ACK deadline (inactivityBaseTimeoutMillis = 5000)
        time += 6_000L
        advanceTimeBy(6_000L)
        runCurrent()

        val event = sender.diagnosticSink.events.replayCache.lastOrNull()
        assertNotNull(event, "Expected DELIVERY_TIMEOUT diagnostic event")
        assertEquals(DiagnosticCode.DELIVERY_TIMEOUT, event.code)
        val payload = assertIs<DiagnosticPayload.DeliveryTimeout>(event.payload)
        assertTrue(payload.messageIdHex.isNotEmpty(), "messageIdHex should be non-empty")
    }

    // 7. SEND_FAILED — transport.sendToPeer returns Failure
    @Test
    fun sendFailedEmitsOnTransportWriteFailure() = runTest {
        var time = 0L
        val sender = makeNode(backgroundScope, testScheduler, { time })
        val receiver = makeNode(backgroundScope, testScheduler, { time })
        linkNodes(sender, receiver, { time })
        drainFlows(backgroundScope, sender)
        drainFlows(backgroundScope, receiver)
        runCurrent()

        // Simulate write failure to receiver
        sender.transport.simulateWriteFailure(receiver.identity.keyHash)

        sender.pipeline.send(receiver.identity.keyHash, "test".encodeToByteArray())
        runCurrent()
        advanceUntilIdle()

        val event = sender.diagnosticSink.events.replayCache.lastOrNull()
        assertNotNull(event, "Expected SEND_FAILED diagnostic event")
        assertEquals(DiagnosticCode.SEND_FAILED, event.code)
        val payload = assertIs<DiagnosticPayload.SendFailed>(event.payload)
        assertTrue(payload.reason.isNotEmpty(), "failure reason should be non-empty")
    }

    // 8. UNKNOWN_MESSAGE_TYPE — reassembled payload decodes to unrecognized type
    @Test
    fun unknownMessageTypeEmitsOnUnrecognizedWireMessage() = runTest {
        var time = 0L
        val sender = makeNode(backgroundScope, testScheduler, { time })
        val receiver = makeNode(backgroundScope, testScheduler, { time })
        linkNodes(sender, receiver, { time })
        drainFlows(backgroundScope, sender)
        drainFlows(backgroundScope, receiver)
        runCurrent()

        // Encode a Hello message — valid wire message but NOT RoutedMessage/Broadcast/DeliveryAck.
        // When received via TransferEngine assembly, handleAssemblyComplete will hit the `else`
        // branch and emit UNKNOWN_MESSAGE_TYPE.
        val helloEncoded =
            WireCodec.encode(Hello(sender = sender.identity.keyHash, seqNo = 0u, routeDigest = 0u))
        val sessionId = ByteArray(16) { (it + 0x20).toByte() }
        sender.transferEngine.send(
            sessionId,
            helloEncoded,
            receiver.identity.keyHash,
            Priority.NORMAL,
        )
        runCurrent()
        advanceUntilIdle()

        val event = receiver.diagnosticSink.events.replayCache.lastOrNull()
        assertNotNull(event, "Expected UNKNOWN_MESSAGE_TYPE diagnostic event")
        assertEquals(DiagnosticCode.UNKNOWN_MESSAGE_TYPE, event.code)
        assertIs<DiagnosticPayload.UnknownMessageType>(event.payload)
    }

    // 9. DECRYPTION_FAILED with null exception message — covers the elvis ?: fallback
    @Test
    fun decryptionFailedFallbackReasonWhenExceptionMessageIsNull() = runTest {
        var time = 0L
        val realCrypto = createCryptoProvider()

        // Wrap the real CryptoProvider: delegate everything except aeadDecrypt, which throws
        // RuntimeException() with null message to exercise the `e.message ?: "..."` fallback.
        val nullMsgCrypto =
            object : CryptoProvider {
                override fun generateEd25519KeyPair() = realCrypto.generateEd25519KeyPair()

                override fun sign(privateKey: ByteArray, message: ByteArray) =
                    realCrypto.sign(privateKey, message)

                override fun verify(
                    publicKey: ByteArray,
                    message: ByteArray,
                    signature: ByteArray,
                ) = realCrypto.verify(publicKey, message, signature)

                override fun generateX25519KeyPair() = realCrypto.generateX25519KeyPair()

                override fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray) =
                    realCrypto.x25519SharedSecret(privateKey, publicKey)

                override fun aeadEncrypt(
                    key: ByteArray,
                    nonce: ByteArray,
                    plaintext: ByteArray,
                    aad: ByteArray,
                ) = realCrypto.aeadEncrypt(key, nonce, plaintext, aad)

                @Suppress("TooGenericExceptionThrown")
                override fun aeadDecrypt(
                    key: ByteArray,
                    nonce: ByteArray,
                    ciphertext: ByteArray,
                    aad: ByteArray,
                ): ByteArray {
                    // Throw with null message to cover the elvis fallback branch.
                    throw RuntimeException()
                }

                override fun sha256(input: ByteArray) = realCrypto.sha256(input)

                override fun hkdfSha256(
                    salt: ByteArray,
                    ikm: ByteArray,
                    info: ByteArray,
                    length: Int,
                ) = realCrypto.hkdfSha256(salt, ikm, info, length)
            }

        val sender = makeNode(backgroundScope, testScheduler, { time })
        val receiver =
            makeNode(backgroundScope, testScheduler, { time }, cryptoOverride = nullMsgCrypto)
        linkNodes(sender, receiver, { time })
        drainFlows(backgroundScope, receiver)
        runCurrent()

        // Build a RoutedMessage with garbage payload — noiseKOpen will call aeadDecrypt which
        // throws RuntimeException() with null message.
        val badMsg =
            RoutedMessage(
                messageId = ByteArray(16) { it.toByte() },
                origin = sender.identity.keyHash,
                destination = receiver.identity.keyHash,
                hopLimit = 5u,
                visitedList = listOf(sender.identity.keyHash),
                priority = Priority.NORMAL.wire,
                originationTime = 1000uL,
                payload = ByteArray(64) { 0xBB.toByte() },
            )
        val encoded = WireCodec.encode(badMsg)
        receiver.transport.injectRawIncoming(sender.identity.keyHash, encoded)
        runCurrent()

        val event = receiver.diagnosticSink.events.replayCache.lastOrNull()
        assertNotNull(event, "Expected DECRYPTION_FAILED diagnostic event")
        assertEquals(DiagnosticCode.DECRYPTION_FAILED, event.code)
        val payload = assertIs<DiagnosticPayload.DecryptionFailed>(event.payload)
        assertEquals("AEAD verification failed", payload.reason) // fallback reason used
    }
}
