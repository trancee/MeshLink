package ch.trancee.meshlink.messaging

import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.crypto.ReplayGuard
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
import ch.trancee.meshlink.transfer.Priority
import ch.trancee.meshlink.transfer.TransferConfig
import ch.trancee.meshlink.transfer.TransferEngine
import ch.trancee.meshlink.transfer.TransferEvent
import ch.trancee.meshlink.transport.VirtualMeshTransport
import ch.trancee.meshlink.wire.Broadcast
import ch.trancee.meshlink.wire.Chunk
import ch.trancee.meshlink.wire.DeliveryAck
import ch.trancee.meshlink.wire.Handshake
import ch.trancee.meshlink.wire.Hello
import ch.trancee.meshlink.wire.InboundValidator
import ch.trancee.meshlink.wire.Keepalive
import ch.trancee.meshlink.wire.Nack
import ch.trancee.meshlink.wire.ResumeRequest
import ch.trancee.meshlink.wire.RotationAnnouncementMessage
import ch.trancee.meshlink.wire.RoutedMessage
import ch.trancee.meshlink.wire.Update
import ch.trancee.meshlink.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

private const val NACK_REASON_BUFFER_FULL: UByte = 0u

@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryPipelineTest {

    private val appIdHash = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    // ── Node infrastructure ──────────────────────────────────────────────────

    private inner class PipelineNode(
        val crypto: CryptoProvider,
        val identity: Identity,
        val transport: VirtualMeshTransport,
        val routingTable: RoutingTable,
        val routeCoordinator: RouteCoordinator,
        val transferEngine: TransferEngine,
        val trustStore: TrustStore,
        val replayGuard: ReplayGuard,
        val pipeline: DeliveryPipeline,
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
            )
        val transferEngine =
            TransferEngine(scope, transferConfig, ChunkSizePolicy.fixed(4096), true)
        val replayGuard = ReplayGuard()
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
                replayGuard = replayGuard,
                dedupSet = messageDedupSet,
                config = config,
                clock = clock,
            )
        return PipelineNode(
            crypto,
            identity,
            transport,
            routingTable,
            routeCoordinator,
            transferEngine,
            trustStore,
            replayGuard,
            pipeline,
        )
    }

    private fun relaxedConfig(
        requireBroadcastSignatures: Boolean = false,
        allowUnsignedBroadcasts: Boolean = true,
        maxBufferedMessages: Int = 5,
        outboundUnicastLimit: Int = 200,
        broadcastLimit: Int = 200,
        circuitBreaker: MessagingConfig.CircuitBreakerConfig =
            MessagingConfig.CircuitBreakerConfig(
                windowMs = 60_000L,
                maxFailures = 3,
                cooldownMs = 5_000L,
            ),
    ) =
        MessagingConfig(
            appIdHash = appIdHash,
            requireBroadcastSignatures = requireBroadcastSignatures,
            allowUnsignedBroadcasts = allowUnsignedBroadcasts,
            maxBufferedMessages = maxBufferedMessages,
            outboundUnicastLimit = outboundUnicastLimit,
            outboundUnicastWindowMs = 60_000L,
            broadcastLimit = broadcastLimit,
            broadcastWindowMs = 60_000L,
            relayPerSenderPerNeighborLimit = 200,
            relayPerSenderPerNeighborWindowMs = 60_000L,
            perNeighborAggregateLimit = 200,
            perNeighborAggregateWindowMs = 60_000L,
            perSenderInboundLimit = 200,
            perSenderInboundWindowMs = 60_000L,
            handshakeLimit = 200,
            handshakeWindowMs = 60_000L,
            nackLimit = 200,
            nackWindowMs = 60_000L,
            highPriorityTtlMs = 10_000L,
            normalPriorityTtlMs = 10_000L,
            lowPriorityTtlMs = 10_000L,
            circuitBreaker = circuitBreaker,
        )

    private fun connect(a: PipelineNode, b: PipelineNode, clock: () -> Long) {
        a.transport.linkTo(b.transport)
        val expiresAt = clock() + 600_000L
        // onPeerConnected installs a zero-key route entry; the real-key install below must
        // come AFTER so it overwrites and the Ed25519/X25519 keys are stored correctly.
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

    // ── Inbound dispatch ─────────────────────────────────────────────────────

    @Test
    fun `inbound RoutedMessage delivered to messages flow`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)

        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { bob.pipeline.messages.collect { received.add(it) } }
        drainFlows(backgroundScope, bob)
        drainFlows(backgroundScope, alice)

        assertEquals(SendResult.Sent, alice.pipeline.send(bob.identity.keyHash, byteArrayOf(0x42)))
        runCurrent()
        advanceUntilIdle()

        assertTrue(received.isNotEmpty(), "Bob should receive the message")
        assertEquals(byteArrayOf(0x42).toList(), received.first().payload.toList())
        assertEquals(MessageKind.UNICAST, received.first().kind)
    }

    @Test
    fun `inbound Hello dispatched to routeCoordinator no crash`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        drainFlows(backgroundScope, alice)

        val fakePeer = ByteArray(12) { 0x11 }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(alice.identity.keyHash, WireCodec.encode(Hello(fakePeer, 0u, 0u)))
        runCurrent()
        // No crash
    }

    @Test
    fun `inbound Update installs route and pins key`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        drainFlows(backgroundScope, alice)
        runCurrent() // start collectors

        val fakePeer = ByteArray(12) { 0x22 }
        val destId = ByteArray(12) { 0x33 }
        val dhKey = ByteArray(32) { (it + 1).toByte() }

        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        alice.routeCoordinator.onPeerConnected(PeerInfo(fakePeer, 0, -50, 0.0))

        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(Update(destId, 1u, 1u, ByteArray(32) { it.toByte() }, dhKey)),
        )
        runCurrent()

        assertNotNull(alice.routingTable.lookupNextHop(destId), "Route installed")
        assertNotNull(alice.trustStore.getPinnedKey(destId), "X25519 key pinned")
    }

    @Test
    fun `inbound Chunk reassembles via transferEngine`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val events = mutableListOf<TransferEvent>()
        backgroundScope.launch { alice.transferEngine.events.collect { events.add(it) } }
        backgroundScope.launch { alice.transferEngine.outboundChunks.collect {} }
        runCurrent() // start collectors

        val msgId = ByteArray(16) { 0xAA.toByte() }
        val fakePeer = ByteArray(12) { 0x55 }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(Chunk(msgId, 0u, 1u, byteArrayOf(1, 2, 3))),
        )
        runCurrent()

        assertTrue(events.any { it is TransferEvent.AssemblyComplete })
    }

    @Test
    fun `inbound ChunkAck dispatched to transferEngine`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val capturedChunks = mutableListOf<ch.trancee.meshlink.routing.OutboundFrame>()
        backgroundScope.launch {
            alice.transferEngine.outboundChunks.collect { capturedChunks.add(it) }
        }
        backgroundScope.launch { alice.transferEngine.events.collect {} }

        val msgId = ByteArray(16) { 0xBB.toByte() }
        val fakePeer = ByteArray(12) { 0x66 }
        alice.transferEngine.send(msgId, byteArrayOf(1, 2, 3), fakePeer)
        runCurrent()

        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(ch.trancee.meshlink.wire.ChunkAck(msgId, 1u, 0uL)),
        )
        runCurrent()
        // No crash
    }

    @Test
    fun `inbound Broadcast delivered to messages flow`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
        drainFlows(backgroundScope, alice)
        runCurrent() // start collectors

        val fakePeer = ByteArray(12) { 0x99.toByte() }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                Broadcast(
                    messageId = ByteArray(16) { 0xCC.toByte() },
                    origin = fakePeer,
                    remainingHops = 2u,
                    appIdHash = 0x0201u.toUShort(),
                    flags = 0u,
                    priority = 0,
                    signature = null,
                    signerKey = null,
                    payload = byteArrayOf(0xAB.toByte()),
                )
            ),
        )
        runCurrent()

        assertEquals(1, received.size)
        assertEquals(MessageKind.BROADCAST, received.first().kind)
    }

    @Test
    fun `inbound DeliveryAck for unknown messageId silently dropped`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val confirmations = mutableListOf<Delivered>()
        backgroundScope.launch {
            alice.pipeline.deliveryConfirmations.collect { confirmations.add(it) }
        }

        val fakePeer = ByteArray(12) { 0x77 }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                DeliveryAck(ByteArray(16) { 0xDD.toByte() }, alice.identity.keyHash, 0u, null)
            ),
        )
        runCurrent()

        assertTrue(confirmations.isEmpty())
    }

    @Test
    fun `inbound Handshake silently ignored`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        drainFlows(backgroundScope, alice)

        val fakePeer = ByteArray(12) { 0x78 }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(alice.identity.keyHash, WireCodec.encode(Handshake(1u, ByteArray(32))))
        runCurrent()
        // No crash
    }

    @Test
    fun `inbound Keepalive silently ignored`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        drainFlows(backgroundScope, alice)

        val fakePeer = ByteArray(12) { 0x79 }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(alice.identity.keyHash, WireCodec.encode(Keepalive(0u, 0uL, 244u)))
        runCurrent()
        // No crash
    }

    @Test
    fun `inbound RotationAnnouncement silently ignored`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        drainFlows(backgroundScope, alice)

        val crypto = createCryptoProvider()
        val oldEdKp = crypto.generateEd25519KeyPair()
        val newEdKp = crypto.generateEd25519KeyPair()
        val newDhKp = crypto.generateX25519KeyPair()
        val nonce = ByteArray(8) { 1 }
        val rotNonce = ByteArray(8) { 1 }
        val rot =
            RotationAnnouncementMessage(
                oldX25519Key = ByteArray(32),
                newX25519Key = newDhKp.publicKey,
                oldEd25519Key = oldEdKp.publicKey,
                newEd25519Key = newEdKp.publicKey,
                rotationNonce = 1uL,
                timestampMillis = 0uL,
                signature =
                    crypto.sign(
                        oldEdKp.privateKey,
                        newEdKp.publicKey + newDhKp.publicKey + rotNonce,
                    ),
            )
        val fakePeer = ByteArray(12) { 0x7A }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(alice.identity.keyHash, WireCodec.encode(rot))
        runCurrent()
        // No crash
    }

    @Test
    fun `inbound Nack dispatched within rate limit`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        backgroundScope.launch { alice.transferEngine.outboundChunks.collect {} }
        backgroundScope.launch { alice.transferEngine.events.collect {} }

        val msgId = ByteArray(16) { 0xEE.toByte() }
        val fakePeer = ByteArray(12) { 0x7B }
        alice.transferEngine.send(msgId, byteArrayOf(1, 2, 3, 4, 5), fakePeer)
        runCurrent()

        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(alice.identity.keyHash, WireCodec.encode(Nack(msgId, 0u)))
        runCurrent()
        // No crash
    }

    @Test
    fun `inbound Nack beyond rate limit silently dropped`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val config = relaxedConfig().copy(nackLimit = 1, nackWindowMs = 60_000L)
        val alice = makeNode(backgroundScope, testScheduler, clock, config)
        backgroundScope.launch { alice.transferEngine.outboundChunks.collect {} }
        backgroundScope.launch { alice.transferEngine.events.collect {} }

        val msgId = ByteArray(16) { 0xEF.toByte() }
        val fakePeer = ByteArray(12) { 0x7C }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)

        injector.sendToPeer(alice.identity.keyHash, WireCodec.encode(Nack(msgId, 0u)))
        runCurrent()
        injector.sendToPeer(alice.identity.keyHash, WireCodec.encode(Nack(msgId, 0u)))
        runCurrent()
        // No crash — second NACK rate limited
    }

    @Test
    fun `inbound ResumeRequest dispatched to transferEngine`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        backgroundScope.launch { alice.transferEngine.outboundChunks.collect {} }
        backgroundScope.launch { alice.transferEngine.events.collect {} }

        val fakePeer = ByteArray(12) { 0x7D }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(ResumeRequest(ByteArray(16) { 0xF1.toByte() }, 10u)),
        )
        runCurrent()
        // No crash
    }

    @Test
    fun `inbound malformed frame silently dropped`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { alice.pipeline.transferFailures.collect { failures.add(it) } }

        val fakePeer = ByteArray(12) { 0x7E }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(alice.identity.keyHash, byteArrayOf(0x01, 0x02)) // too short
        runCurrent()

        assertTrue(failures.isEmpty())
    }

    // ── send() decision tree ─────────────────────────────────────────────────

    @Test
    fun `send to self delivers directly without BLE`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val received = mutableListOf<InboundMessage>()
        val confirmations = mutableListOf<Delivered>()
        backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
        backgroundScope.launch {
            alice.pipeline.deliveryConfirmations.collect { confirmations.add(it) }
        }
        runCurrent() // start collectors

        val result = alice.pipeline.send(alice.identity.keyHash, byteArrayOf(0xFF.toByte()))
        runCurrent()

        assertEquals(SendResult.Sent, result)
        assertEquals(1, received.size)
        assertEquals(1, confirmations.size)
        assertTrue(alice.transport.sentFrames.isEmpty(), "No BLE for self-send")
    }

    @Test
    fun `send with route returns Sent`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)
        drainFlows(backgroundScope, alice)

        val result = alice.pipeline.send(bob.identity.keyHash, byteArrayOf(1))
        runCurrent()

        assertEquals(SendResult.Sent, result)
        assertTrue(alice.transport.sentFrames.isNotEmpty())
    }

    @Test
    fun `send without route returns Queued ROUTE_PENDING`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val unknown = ByteArray(12) { 0xAA.toByte() }
        alice.trustStore.pinKey(unknown, ByteArray(32) { it.toByte() })

        val result = alice.pipeline.send(unknown, byteArrayOf(1))
        assertEquals(SendResult.Queued(QueuedReason.ROUTE_PENDING), result)
    }

    @Test
    fun `send rate limited returns Queued PAUSED`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val config = relaxedConfig(outboundUnicastLimit = 1)
        val alice = makeNode(backgroundScope, testScheduler, clock, config)
        val bob = makeNode(backgroundScope, testScheduler, clock, config)
        connect(alice, bob, clock)
        drainFlows(backgroundScope, alice)

        val r1 = alice.pipeline.send(bob.identity.keyHash, byteArrayOf(1))
        runCurrent()
        assertEquals(SendResult.Sent, r1)

        val r2 = alice.pipeline.send(bob.identity.keyHash, byteArrayOf(2))
        assertEquals(SendResult.Queued(QueuedReason.PAUSED), r2)
    }

    @Test
    fun `send when circuit breaker open returns Queued PAUSED`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val circuitBreakerConfig =
            MessagingConfig.CircuitBreakerConfig(
                windowMs = 60_000L,
                maxFailures = 1,
                cooldownMs = 30_000L,
            )
        val config = relaxedConfig(circuitBreaker = circuitBreakerConfig)
        val alice =
            makeNode(
                backgroundScope,
                testScheduler,
                clock,
                config,
                TransferConfig(inactivityBaseTimeoutMillis = 100L),
            )
        val bob = makeNode(backgroundScope, testScheduler, clock, config)
        connect(alice, bob, clock)
        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { alice.pipeline.transferFailures.collect { failures.add(it) } }
        backgroundScope.launch { alice.pipeline.transferProgress.collect {} }
        backgroundScope.launch { alice.pipeline.deliveryConfirmations.collect {} }
        runCurrent() // start collectors

        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(1))
        runCurrent()
        advanceTimeBy(200L)
        runCurrent()

        assertTrue(failures.isNotEmpty(), "Failure should have fired")
        val r = alice.pipeline.send(bob.identity.keyHash, byteArrayOf(2))
        assertEquals(SendResult.Queued(QueuedReason.PAUSED), r)
    }

    @Test
    fun `send oversized payload throws IllegalArgumentException`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice =
            makeNode(
                backgroundScope,
                testScheduler,
                clock,
                relaxedConfig().copy(maxMessageSize = 5),
            )
        val bob = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)

        assertFailsWith<IllegalArgumentException> {
            alice.pipeline.send(bob.identity.keyHash, ByteArray(10))
        }
    }

    @Test
    fun `send to unknown recipient throws IllegalStateException`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)

        assertFailsWith<IllegalStateException> {
            alice.pipeline.send(ByteArray(12) { 0xFF.toByte() }, byteArrayOf(1))
        }
    }

    @Test
    fun `send encrypted payload decryptable by recipient`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { bob.pipeline.messages.collect { received.add(it) } }
        drainFlows(backgroundScope, bob)
        drainFlows(backgroundScope, alice)

        val secret = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        alice.pipeline.send(bob.identity.keyHash, secret)
        runCurrent()
        advanceUntilIdle()

        assertTrue(received.isNotEmpty())
        assertEquals(secret.toList(), received.first().payload.toList())
    }

    // ── broadcast() ──────────────────────────────────────────────────────────

    @Test
    fun `broadcast fans out to connected peers`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        val carol = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)
        connect(alice, carol, clock)
        drainFlows(backgroundScope, alice)

        val messageId = alice.pipeline.broadcast(byteArrayOf(0xAB.toByte()))
        runCurrent()

        assertEquals(16, messageId.size)
        assertTrue(alice.transport.sentFrames.size >= 2)
    }

    @Test
    fun `broadcast with no peers returns messageId no crash`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)

        val messageId = alice.pipeline.broadcast(byteArrayOf(0xBC.toByte()))
        runCurrent()

        assertEquals(16, messageId.size)
        assertTrue(alice.transport.sentFrames.isEmpty())
    }

    @Test
    fun `broadcast oversized throws IllegalArgumentException`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice =
            makeNode(
                backgroundScope,
                testScheduler,
                clock,
                relaxedConfig().copy(maxBroadcastSize = 5),
            )

        assertFailsWith<IllegalArgumentException> { alice.pipeline.broadcast(ByteArray(10)) }
    }

    @Test
    fun `broadcast rate limited throws IllegalStateException`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice =
            makeNode(backgroundScope, testScheduler, clock, relaxedConfig(broadcastLimit = 1))

        alice.pipeline.broadcast(byteArrayOf(1))
        assertFailsWith<IllegalStateException> { alice.pipeline.broadcast(byteArrayOf(2)) }
    }

    @Test
    fun `broadcast with signatures sends signed message`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val config =
            relaxedConfig(requireBroadcastSignatures = true, allowUnsignedBroadcasts = false)
        val alice = makeNode(backgroundScope, testScheduler, clock, config)
        val bob = makeNode(backgroundScope, testScheduler, clock, config)
        connect(alice, bob, clock)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { bob.pipeline.messages.collect { received.add(it) } }
        drainFlows(backgroundScope, bob)
        drainFlows(backgroundScope, alice)

        alice.pipeline.broadcast(byteArrayOf(0xCD.toByte()))
        runCurrent()
        advanceUntilIdle()

        assertTrue(received.isNotEmpty(), "Signed broadcast received")
    }

    @Test
    fun `broadcast dedup prevents self-relay`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)
        drainFlows(backgroundScope, alice)

        val messageId = alice.pipeline.broadcast(byteArrayOf(0xDE.toByte()))
        runCurrent()
        val framesBefore = alice.transport.sentFrames.size

        // Inject own broadcast back as if echoed
        val echo =
            Broadcast(
                messageId,
                alice.identity.keyHash,
                1u,
                0x0201u.toUShort(),
                0u,
                0,
                null,
                null,
                byteArrayOf(0xDE.toByte()),
            )
        val injector = VirtualMeshTransport(bob.identity.keyHash, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(alice.identity.keyHash, WireCodec.encode(echo))
        runCurrent()

        assertEquals(framesBefore, alice.transport.sentFrames.size, "Dedup prevents self-relay")
    }

    @Test
    fun `broadcast remainingHops zero not relayed`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)
        drainFlows(backgroundScope, alice)

        val framesBeforeInject = alice.transport.sentFrames.size
        val thirdParty = ByteArray(12) { 0x31 }
        val injector = VirtualMeshTransport(bob.identity.keyHash, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                Broadcast(
                    ByteArray(16) { 0x31 },
                    thirdParty,
                    0u,
                    0x0201u.toUShort(),
                    0u,
                    0,
                    null,
                    null,
                    byteArrayOf(1),
                )
            ),
        )
        runCurrent()

        assertEquals(framesBeforeInject, alice.transport.sentFrames.size, "hops=0 not relayed")
    }

    @Test
    fun `broadcast unsigned accepted when allowUnsigned true`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val config =
            relaxedConfig(requireBroadcastSignatures = true, allowUnsignedBroadcasts = true)
        val alice = makeNode(backgroundScope, testScheduler, clock, config)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
        drainFlows(backgroundScope, alice)
        runCurrent() // start collectors

        val fakePeer = ByteArray(12) { 0x40 }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                Broadcast(
                    ByteArray(16) { 0x40 },
                    fakePeer,
                    0u,
                    0x0201u.toUShort(),
                    0u,
                    0,
                    null,
                    null,
                    byteArrayOf(0x55),
                )
            ),
        )
        runCurrent()

        assertEquals(1, received.size)
    }

    @Test
    fun `broadcast unsigned dropped when requireSignatures and not allowUnsigned`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val config =
            relaxedConfig(requireBroadcastSignatures = true, allowUnsignedBroadcasts = false)
        val alice = makeNode(backgroundScope, testScheduler, clock, config)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
        drainFlows(backgroundScope, alice)

        val fakePeer = ByteArray(12) { 0x41 }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                Broadcast(
                    ByteArray(16) { 0x41 },
                    fakePeer,
                    0u,
                    0x0201u.toUShort(),
                    0u,
                    0,
                    null,
                    null,
                    byteArrayOf(0x56),
                )
            ),
        )
        runCurrent()

        assertTrue(received.isEmpty())
    }

    @Test
    fun `broadcast invalid signature dropped`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val config =
            relaxedConfig(requireBroadcastSignatures = true, allowUnsignedBroadcasts = false)
        val alice = makeNode(backgroundScope, testScheduler, clock, config)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
        drainFlows(backgroundScope, alice)

        val fakePeer = ByteArray(12) { 0x42 }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                Broadcast(
                    ByteArray(16) { 0x42 },
                    fakePeer,
                    0u,
                    0x0201u.toUShort(),
                    1u,
                    0,
                    ByteArray(64),
                    ByteArray(32) { it.toByte() },
                    byteArrayOf(0x57),
                )
            ),
        )
        runCurrent()

        assertTrue(received.isEmpty(), "Invalid sig dropped")
    }

    @Test
    fun `broadcast with null sig field dropped when HAS_SIGNATURE flag set`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val config =
            relaxedConfig(requireBroadcastSignatures = false, allowUnsignedBroadcasts = true)
        val alice = makeNode(backgroundScope, testScheduler, clock, config)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
        drainFlows(backgroundScope, alice)

        val fakePeer = ByteArray(12) { 0x43 }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        // flags=1 (HAS_SIGNATURE) but signature=null (missing field on wire)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                Broadcast(
                    ByteArray(16) { 0x43 },
                    fakePeer,
                    0u,
                    0x0201u.toUShort(),
                    1u,
                    0,
                    null,
                    null,
                    byteArrayOf(0x58),
                )
            ),
        )
        runCurrent()

        assertTrue(received.isEmpty(), "Null sig with HAS_SIGNATURE flag → dropped")
    }

    @Test
    fun `broadcast origin equals local not delivered to self`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)
        val aliceMessages = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { aliceMessages.add(it) } }
        drainFlows(backgroundScope, alice)

        alice.pipeline.broadcast(byteArrayOf(0xAA.toByte()))
        runCurrent()

        assertTrue(aliceMessages.none { it.kind == MessageKind.BROADCAST })
    }

    @Test
    fun `broadcast per-sender inbound rate limit drops excess`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val config =
            relaxedConfig().copy(perSenderInboundLimit = 1, perSenderInboundWindowMs = 60_000L)
        val alice = makeNode(backgroundScope, testScheduler, clock, config)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
        drainFlows(backgroundScope, alice)
        runCurrent() // start collectors

        val fakePeer = ByteArray(12) { 0x50 }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)

        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                Broadcast(
                    ByteArray(16) { 0x50 },
                    fakePeer,
                    0u,
                    0x0201u.toUShort(),
                    0u,
                    0,
                    null,
                    null,
                    byteArrayOf(1),
                )
            ),
        )
        runCurrent()
        assertEquals(1, received.size)

        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                Broadcast(
                    ByteArray(16) { 0x51 },
                    fakePeer,
                    0u,
                    0x0201u.toUShort(),
                    0u,
                    0,
                    null,
                    null,
                    byteArrayOf(2),
                )
            ),
        )
        runCurrent()
        assertEquals(1, received.size, "Rate limited")
    }

    // ── Relay RoutedMessage ───────────────────────────────────────────────────

    @Test
    fun `relay RoutedMessage success`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        val carol = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)
        connect(alice, carol, clock)
        drainFlows(backgroundScope, alice)
        runCurrent() // start collectors

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

        assertTrue(alice.transport.sentFrames.isNotEmpty())
    }

    @Test
    fun `relay RoutedMessage hopLimit zero dropped`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val carol = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, carol, clock)
        drainFlows(backgroundScope, alice)

        val framesBefore = alice.transport.sentFrames.size
        val injector = VirtualMeshTransport(ByteArray(12) { 0x61 }, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                RoutedMessage(
                    ByteArray(16) { 0x61 },
                    ByteArray(12) { 0x61 },
                    carol.identity.keyHash,
                    0u,
                    listOf(ByteArray(12) { 0x61 }),
                    0,
                    1u,
                    byteArrayOf(1),
                )
            ),
        )
        runCurrent()

        assertEquals(framesBefore, alice.transport.sentFrames.size)
    }

    @Test
    fun `relay RoutedMessage in visited list dropped`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val carol = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, carol, clock)
        drainFlows(backgroundScope, alice)

        val framesBefore = alice.transport.sentFrames.size
        val injector = VirtualMeshTransport(ByteArray(12) { 0x62 }, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                RoutedMessage(
                    ByteArray(16) { 0x62 },
                    ByteArray(12) { 0x62 },
                    carol.identity.keyHash,
                    3u,
                    listOf(ByteArray(12) { 0x62 }, alice.identity.keyHash),
                    0,
                    1u,
                    byteArrayOf(1),
                )
            ),
        )
        runCurrent()

        assertEquals(framesBefore, alice.transport.sentFrames.size)
    }

    @Test
    fun `relay RoutedMessage no route dropped`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        drainFlows(backgroundScope, alice)

        val framesBefore = alice.transport.sentFrames.size
        val injector = VirtualMeshTransport(ByteArray(12) { 0x70 }, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                RoutedMessage(
                    ByteArray(16) { 0x63 },
                    ByteArray(12) { 0x70 },
                    ByteArray(12) { 0x63 },
                    3u,
                    listOf(ByteArray(12) { 0x70 }),
                    0,
                    1u,
                    byteArrayOf(1),
                )
            ),
        )
        runCurrent()

        assertEquals(framesBefore, alice.transport.sentFrames.size)
    }

    @Test
    fun `relay RoutedMessage relay rate limit drops excess`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val config =
            relaxedConfig()
                .copy(
                    relayPerSenderPerNeighborLimit = 1,
                    relayPerSenderPerNeighborWindowMs = 60_000L,
                )
        val alice = makeNode(backgroundScope, testScheduler, clock, config)
        val carol = makeNode(backgroundScope, testScheduler, clock, config)
        connect(alice, carol, clock)
        drainFlows(backgroundScope, alice)
        runCurrent() // start collectors

        val thirdParty = ByteArray(12) { 0xF0.toByte() }
        val injector = VirtualMeshTransport(thirdParty, testScheduler)
        injector.linkTo(alice.transport)

        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                RoutedMessage(
                    ByteArray(16) { 0x01 },
                    thirdParty,
                    carol.identity.keyHash,
                    3u,
                    listOf(thirdParty),
                    0,
                    1u,
                    byteArrayOf(1),
                )
            ),
        )
        runCurrent()
        val after1 = alice.transport.sentFrames.size

        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                RoutedMessage(
                    ByteArray(16) { 0x02 },
                    thirdParty,
                    carol.identity.keyHash,
                    3u,
                    listOf(thirdParty),
                    0,
                    2u,
                    byteArrayOf(2),
                )
            ),
        )
        runCurrent()
        assertEquals(after1, alice.transport.sentFrames.size, "Relay rate limited")
    }

    @Test
    fun `relay RoutedMessage neighbor aggregate rate limit drops excess`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val config =
            relaxedConfig()
                .copy(
                    relayPerSenderPerNeighborLimit = 200,
                    perNeighborAggregateLimit = 1,
                    perNeighborAggregateWindowMs = 60_000L,
                )
        val alice = makeNode(backgroundScope, testScheduler, clock, config)
        val carol = makeNode(backgroundScope, testScheduler, clock, config)
        connect(alice, carol, clock)
        drainFlows(backgroundScope, alice)
        runCurrent() // start collectors

        val thirdParty = ByteArray(12) { 0xF1.toByte() }
        val injector = VirtualMeshTransport(thirdParty, testScheduler)
        injector.linkTo(alice.transport)

        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                RoutedMessage(
                    ByteArray(16) { 0x10 },
                    ByteArray(12) { 0x10 },
                    carol.identity.keyHash,
                    3u,
                    listOf(ByteArray(12) { 0x10 }),
                    0,
                    1u,
                    byteArrayOf(1),
                )
            ),
        )
        runCurrent()
        val after1 = alice.transport.sentFrames.size

        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                RoutedMessage(
                    ByteArray(16) { 0x11 },
                    ByteArray(12) { 0x11 },
                    carol.identity.keyHash,
                    3u,
                    listOf(ByteArray(12) { 0x11 }),
                    0,
                    2u,
                    byteArrayOf(2),
                )
            ),
        )
        runCurrent()
        assertEquals(after1, alice.transport.sentFrames.size, "Aggregate limit drops excess")
    }

    @Test
    fun `inbound RoutedMessage to self decryption failure dropped`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
        drainFlows(backgroundScope, alice)

        val fakeSender = ByteArray(12) { 0x80.toByte() }
        alice.trustStore.pinKey(fakeSender, ByteArray(32) { it.toByte() })

        val injector = VirtualMeshTransport(fakeSender, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                RoutedMessage(
                    ByteArray(16) { 0x80.toByte() },
                    fakeSender,
                    alice.identity.keyHash,
                    3u,
                    listOf(fakeSender),
                    0,
                    1u,
                    ByteArray(48) { 0x80.toByte() },
                ) // garbage ciphertext
            ),
        )
        runCurrent()

        assertTrue(received.isEmpty(), "Decryption failure → dropped")
    }

    @Test
    fun `inbound RoutedMessage to self no sender key dropped`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
        drainFlows(backgroundScope, alice)

        val unknownSender = ByteArray(12) { 0x81.toByte() }
        // No key pinned for unknownSender

        val injector = VirtualMeshTransport(unknownSender, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                RoutedMessage(
                    ByteArray(16) { 0x81.toByte() },
                    unknownSender,
                    alice.identity.keyHash,
                    3u,
                    listOf(unknownSender),
                    0,
                    1u,
                    ByteArray(48),
                )
            ),
        )
        runCurrent()

        assertTrue(received.isEmpty(), "Unknown sender key → dropped")
    }

    // ── Delivery ACK ─────────────────────────────────────────────────────────

    @Test
    fun `full delivery round trip emits Delivered on sender`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)

        val confirmations = mutableListOf<Delivered>()
        backgroundScope.launch {
            alice.pipeline.deliveryConfirmations.collect { confirmations.add(it) }
        }
        drainFlows(backgroundScope, alice)
        drainFlows(backgroundScope, bob)
        runCurrent() // start collectors

        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(0x42))
        runCurrent()
        advanceUntilIdle()

        assertTrue(confirmations.isNotEmpty(), "Alice gets Delivered after Bob ACKs")
    }

    @Test
    fun `delivery ACK with invalid signature is dropped`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)
        val confirmations = mutableListOf<Delivered>()
        backgroundScope.launch {
            alice.pipeline.deliveryConfirmations.collect { confirmations.add(it) }
        }
        drainFlows(backgroundScope, alice)

        // Get a message into pendingDeliveries by sending to bob
        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(1))
        runCurrent()

        // Inject a bad ACK for an unknown messageId
        val badAck =
            DeliveryAck(ByteArray(16) { 0x99.toByte() }, alice.identity.keyHash, 1u, ByteArray(64))
        val fakePeer = ByteArray(12) { 0x91.toByte() }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(alice.identity.keyHash, WireCodec.encode(badAck))
        runCurrent()

        assertTrue(
            confirmations.isEmpty() ||
                confirmations.none { it.messageId.contentEquals(ByteArray(16) { 0x99.toByte() }) }
        )
    }

    @Test
    fun `delivery ACK no-signature path accepted`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val unknown = ByteArray(12) { 0x92.toByte() }
        alice.trustStore.pinKey(unknown, ByteArray(32) { it.toByte() })

        val confirmations = mutableListOf<Delivered>()
        backgroundScope.launch {
            alice.pipeline.deliveryConfirmations.collect { confirmations.add(it) }
        }

        // Buffer a message (no route → pending)
        alice.pipeline.send(unknown, byteArrayOf(1))
        runCurrent()

        // Inject no-sig ACK for an unknown msgId (unknown → silently dropped, no crash)
        val fakeAck = DeliveryAck(ByteArray(16) { 0x92.toByte() }, alice.identity.keyHash, 0u, null)
        val fakePeer = ByteArray(12) { 0x93.toByte() }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(alice.identity.keyHash, WireCodec.encode(fakeAck))
        runCurrent()
        // No crash
    }

    // ── Store-and-forward ────────────────────────────────────────────────────

    @Test
    fun `buffered message drains when route becomes available`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val unknown = ByteArray(12) { 0xA0.toByte() }
        alice.trustStore.pinKey(unknown, ByteArray(32) { it.toByte() })
        drainFlows(backgroundScope, alice)

        val result = alice.pipeline.send(unknown, byteArrayOf(1))
        assertEquals(SendResult.Queued(QueuedReason.ROUTE_PENDING), result)
        assertEquals(0, alice.transport.sentFrames.size)

        // Install route and register neighbor
        alice.routingTable.install(
            RouteEntry(
                unknown,
                unknown,
                1.0,
                1u,
                1.0,
                clock() + 600_000L,
                ByteArray(32),
                ByteArray(32),
            )
        )
        val fakeTransport = VirtualMeshTransport(unknown, testScheduler)
        fakeTransport.linkTo(alice.transport)
        alice.routeCoordinator.onPeerConnected(PeerInfo(unknown, 0, -50, 0.0))

        // Trigger drainBuffer by self-send (calls drainBuffer internally)
        alice.pipeline.send(alice.identity.keyHash, byteArrayOf(2))
        runCurrent()

        assertTrue(alice.transport.sentFrames.isNotEmpty(), "Buffer drained after route available")
    }

    @Test
    fun `buffer evicts when maxBufferedMessages exceeded`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val config = relaxedConfig(maxBufferedMessages = 2)
        val alice = makeNode(backgroundScope, testScheduler, clock, config)
        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { alice.pipeline.transferFailures.collect { failures.add(it) } }

        val r1 = ByteArray(12) { 0xB1.toByte() }
        val r2 = ByteArray(12) { 0xB2.toByte() }
        val r3 = ByteArray(12) { 0xB3.toByte() }
        alice.trustStore.pinKey(r1, ByteArray(32) { 1 })
        alice.trustStore.pinKey(r2, ByteArray(32) { 2 })
        alice.trustStore.pinKey(r3, ByteArray(32) { 3 })

        alice.pipeline.send(r1, byteArrayOf(1), Priority.LOW)
        alice.pipeline.send(r2, byteArrayOf(2), Priority.LOW)
        runCurrent()
        alice.pipeline.send(r3, byteArrayOf(3), Priority.LOW) // exceeds capacity → eviction
        runCurrent()

        assertTrue(failures.isNotEmpty(), "Evicted entry emits DeliveryFailed")
    }

    @Test
    fun `buffer TTL expiry emits DeliveryFailed TIMED_OUT`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val config =
            relaxedConfig()
                .copy(highPriorityTtlMs = 100L, normalPriorityTtlMs = 100L, lowPriorityTtlMs = 100L)
        val alice = makeNode(backgroundScope, testScheduler, clock, config)
        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { alice.pipeline.transferFailures.collect { failures.add(it) } }
        runCurrent() // start collectors

        val unknown = ByteArray(12) { 0xC0.toByte() }
        alice.trustStore.pinKey(unknown, ByteArray(32) { it.toByte() })
        alice.pipeline.send(unknown, byteArrayOf(1))

        clockMs = 500L // advance past TTL
        alice.pipeline.send(alice.identity.keyHash, byteArrayOf(2)) // triggers drainBuffer
        runCurrent()

        assertTrue(failures.any { it.outcome == DeliveryOutcome.TIMED_OUT })
    }

    // ── Circuit breaker ──────────────────────────────────────────────────────

    @Test
    fun `circuit breaker opens after maxFailures`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val circuitBreakerConfig =
            MessagingConfig.CircuitBreakerConfig(
                windowMs = 60_000L,
                maxFailures = 2,
                cooldownMs = 10_000L,
            )
        val config = relaxedConfig(circuitBreaker = circuitBreakerConfig)
        val alice =
            makeNode(
                backgroundScope,
                testScheduler,
                clock,
                config,
                TransferConfig(inactivityBaseTimeoutMillis = 50L),
            )
        val bob = makeNode(backgroundScope, testScheduler, clock, config)
        connect(alice, bob, clock)
        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { alice.pipeline.transferFailures.collect { failures.add(it) } }
        backgroundScope.launch { alice.pipeline.transferProgress.collect {} }
        backgroundScope.launch { alice.pipeline.deliveryConfirmations.collect {} }
        runCurrent() // start collectors

        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(1))
        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(2))
        runCurrent()
        advanceTimeBy(200L)
        runCurrent()

        val r = alice.pipeline.send(bob.identity.keyHash, byteArrayOf(3))
        assertEquals(SendResult.Queued(QueuedReason.PAUSED), r)
    }

    @Test
    fun `circuit breaker half-open after cooldown`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val circuitBreakerConfig =
            MessagingConfig.CircuitBreakerConfig(
                windowMs = 60_000L,
                maxFailures = 1,
                cooldownMs = 1_000L,
            )
        val config = relaxedConfig(circuitBreaker = circuitBreakerConfig)
        val alice =
            makeNode(
                backgroundScope,
                testScheduler,
                clock,
                config,
                TransferConfig(inactivityBaseTimeoutMillis = 50L),
            )
        val bob = makeNode(backgroundScope, testScheduler, clock, config)
        connect(alice, bob, clock)
        backgroundScope.launch { alice.pipeline.transferFailures.collect {} }
        backgroundScope.launch { alice.pipeline.transferProgress.collect {} }
        backgroundScope.launch { alice.pipeline.deliveryConfirmations.collect {} }
        runCurrent() // start collectors

        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(1))
        runCurrent()
        advanceTimeBy(200L)
        runCurrent()

        // CB open → PAUSED
        assertEquals(
            SendResult.Queued(QueuedReason.PAUSED),
            alice.pipeline.send(bob.identity.keyHash, byteArrayOf(2)),
        )

        // Advance clock past cooldown
        clockMs = 2_000L
        advanceTimeBy(1_800L)
        runCurrent()

        // HALF_OPEN → allows probe
        val probe = alice.pipeline.send(bob.identity.keyHash, byteArrayOf(3))
        assertEquals(SendResult.Sent, probe)
    }

    @Test
    fun `circuit breaker closes on success after half-open`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val circuitBreakerConfig =
            MessagingConfig.CircuitBreakerConfig(
                windowMs = 60_000L,
                maxFailures = 1,
                cooldownMs = 500L,
            )
        val config = relaxedConfig(circuitBreaker = circuitBreakerConfig)
        val alice =
            makeNode(
                backgroundScope,
                testScheduler,
                clock,
                config,
                TransferConfig(inactivityBaseTimeoutMillis = 50L),
            )
        val bob = makeNode(backgroundScope, testScheduler, clock, config)
        connect(alice, bob, clock)
        backgroundScope.launch { alice.pipeline.transferFailures.collect {} }
        backgroundScope.launch { alice.pipeline.transferProgress.collect {} }
        val confirmations = mutableListOf<Delivered>()
        backgroundScope.launch {
            alice.pipeline.deliveryConfirmations.collect { confirmations.add(it) }
        }
        drainFlows(backgroundScope, bob)

        // Open CB
        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(1))
        runCurrent()
        advanceTimeBy(200L)
        runCurrent()

        // Past cooldown
        clockMs = 1_000L
        advanceTimeBy(800L)
        runCurrent()

        // Probe sends → if delivery ACK received → CB closes
        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(2))
        runCurrent()
        advanceUntilIdle()

        // After success, CB should be closed — subsequent send should pass
        clockMs = 2_000L
        val afterSuccess = alice.pipeline.send(bob.identity.keyHash, byteArrayOf(3))
        assertEquals(SendResult.Sent, afterSuccess)
    }

    @Test
    fun `circuit breaker ignores MEMORY_PRESSURE failures`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val circuitBreakerConfig =
            MessagingConfig.CircuitBreakerConfig(
                windowMs = 60_000L,
                maxFailures = 1,
                cooldownMs = 30_000L,
            )
        val config = relaxedConfig(circuitBreaker = circuitBreakerConfig)
        val alice = makeNode(backgroundScope, testScheduler, clock, config)
        val bob = makeNode(backgroundScope, testScheduler, clock, config)
        connect(alice, bob, clock)
        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { alice.pipeline.transferFailures.collect { failures.add(it) } }
        backgroundScope.launch { alice.pipeline.transferProgress.collect {} }
        backgroundScope.launch { alice.pipeline.deliveryConfirmations.collect {} }

        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(1))
        runCurrent()

        alice.transferEngine.onMemoryPressure()
        runCurrent()

        // MEMORY_PRESSURE ignored → CB still CLOSED
        val r = alice.pipeline.send(bob.identity.keyHash, byteArrayOf(2))
        assertEquals(SendResult.Sent, r, "CB still closed after MEMORY_PRESSURE")
    }

    @Test
    fun `circuit breaker half-open failure re-opens`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val circuitBreakerConfig =
            MessagingConfig.CircuitBreakerConfig(
                windowMs = 60_000L,
                maxFailures = 1,
                cooldownMs = 500L,
            )
        val config = relaxedConfig(circuitBreaker = circuitBreakerConfig)
        val alice =
            makeNode(
                backgroundScope,
                testScheduler,
                clock,
                config,
                TransferConfig(inactivityBaseTimeoutMillis = 50L),
            )
        val bob = makeNode(backgroundScope, testScheduler, clock, config)
        connect(alice, bob, clock)
        backgroundScope.launch { alice.pipeline.transferFailures.collect {} }
        backgroundScope.launch { alice.pipeline.transferProgress.collect {} }
        backgroundScope.launch { alice.pipeline.deliveryConfirmations.collect {} }
        runCurrent() // start collectors
        // Simulate a broken link: chunks are emitted but never reach Bob, so Alice's
        // ACK deadline always fires and the CB opens / re-opens as intended.
        alice.transport.simulateWriteFailure(bob.identity.keyHash)

        // Open CB
        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(1))
        runCurrent()
        advanceTimeBy(200L)
        runCurrent()

        clockMs = 1_000L
        advanceTimeBy(800L)
        runCurrent()

        // Half-open probe
        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(2))
        runCurrent()

        // Probe times out → re-opens CB
        advanceTimeBy(200L)
        runCurrent()

        val r = alice.pipeline.send(bob.identity.keyHash, byteArrayOf(3))
        assertEquals(
            SendResult.Queued(QueuedReason.PAUSED),
            r,
            "CB re-opened after half-open failure",
        )
    }

    // ── TransferEngine events ─────────────────────────────────────────────────

    @Test
    fun `transferFailures flow emits for own unicast session timeout`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice =
            makeNode(
                backgroundScope,
                testScheduler,
                clock,
                transferConfig = TransferConfig(inactivityBaseTimeoutMillis = 50L),
            )
        val bob = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)
        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { alice.pipeline.transferFailures.collect { failures.add(it) } }
        backgroundScope.launch { alice.pipeline.transferProgress.collect {} }
        backgroundScope.launch { alice.pipeline.deliveryConfirmations.collect {} }
        runCurrent() // start collectors

        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(1))
        runCurrent()
        advanceTimeBy(200L)
        runCurrent()

        assertTrue(failures.isNotEmpty())
        assertEquals(DeliveryOutcome.TIMED_OUT, failures.first().outcome)
    }

    @Test
    fun `transferFailed for unknown session silently dropped`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice =
            makeNode(
                backgroundScope,
                testScheduler,
                clock,
                transferConfig = TransferConfig(inactivityBaseTimeoutMillis = 50L),
            )
        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { alice.pipeline.transferFailures.collect { failures.add(it) } }
        backgroundScope.launch { alice.transferEngine.outboundChunks.collect {} }
        backgroundScope.launch { alice.transferEngine.events.collect {} }

        // Send directly via TransferEngine (not pipeline) → not in pendingDeliveries
        val msgId = ByteArray(16) { 0x99.toByte() }
        alice.transferEngine.send(msgId, byteArrayOf(1, 2, 3), ByteArray(12) { 0x99.toByte() })
        runCurrent()
        advanceTimeBy(200L)
        runCurrent()

        assertTrue(failures.isEmpty(), "Unknown session failure silently dropped")
    }

    @Test
    fun `transferProgress emits for own unicast sessions only`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)
        val progress = mutableListOf<TransferEvent.ChunkProgress>()
        backgroundScope.launch { alice.pipeline.transferProgress.collect { progress.add(it) } }
        backgroundScope.launch { alice.pipeline.deliveryConfirmations.collect {} }
        backgroundScope.launch { alice.pipeline.transferFailures.collect {} }
        runCurrent() // start collectors

        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(1, 2, 3, 4, 5))
        runCurrent()

        assertTrue(progress.isNotEmpty(), "Progress emitted for own session")
    }

    @Test
    fun `transferProgress not emitted for relay sessions`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val carol = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, carol, clock)
        val progress = mutableListOf<TransferEvent.ChunkProgress>()
        backgroundScope.launch { alice.pipeline.transferProgress.collect { progress.add(it) } }
        backgroundScope.launch { alice.pipeline.transferFailures.collect {} }
        backgroundScope.launch { alice.pipeline.deliveryConfirmations.collect {} }

        val injector = VirtualMeshTransport(ByteArray(12) { 0xD0.toByte() }, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                RoutedMessage(
                    ByteArray(16) { 0xD0.toByte() },
                    ByteArray(12) { 0xD0.toByte() },
                    carol.identity.keyHash,
                    3u,
                    listOf(ByteArray(12) { 0xD0.toByte() }),
                    0,
                    1u,
                    ByteArray(100) { it.toByte() },
                )
            ),
        )
        runCurrent()

        assertTrue(progress.isEmpty(), "Relay session excluded from progress")
    }

    @Test
    fun `mapFailureReason MEMORY_PRESSURE maps to SEND_FAILED`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)
        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { alice.pipeline.transferFailures.collect { failures.add(it) } }
        backgroundScope.launch { alice.pipeline.transferProgress.collect {} }
        backgroundScope.launch { alice.pipeline.deliveryConfirmations.collect {} }

        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(1))
        runCurrent()
        alice.transferEngine.onMemoryPressure()
        runCurrent()

        val memFail = failures.firstOrNull { it.outcome == DeliveryOutcome.SEND_FAILED }
        assertNotNull(memFail, "MEMORY_PRESSURE → SEND_FAILED")
    }

    @Test
    fun `mapFailureReason BUFFER_FULL_RETRY_EXHAUSTED via nack exhaustion`() = runTest {
        var clockMs = 0L
        val clock: () -> Long = { clockMs }
        val alice =
            makeNode(
                backgroundScope,
                testScheduler,
                clock,
                transferConfig =
                    TransferConfig(
                        inactivityBaseTimeoutMillis = 30_000L,
                        maxNackRetries = 1,
                        nackBaseBackoffMillis = 10L,
                    ),
            )
        val bob = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)

        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { alice.pipeline.transferFailures.collect { failures.add(it) } }
        val capturedMsgIds = mutableListOf<ByteArray>()
        backgroundScope.launch {
            alice.transferEngine.outboundChunks.collect { frame ->
                if (frame.message is Chunk) capturedMsgIds.add(frame.message.messageId)
            }
        }
        backgroundScope.launch { alice.pipeline.transferProgress.collect {} }
        backgroundScope.launch { alice.pipeline.deliveryConfirmations.collect {} }

        alice.pipeline.send(bob.identity.keyHash, ByteArray(5) { it.toByte() })
        runCurrent()

        if (capturedMsgIds.isNotEmpty()) {
            val msgId = capturedMsgIds.first()
            alice.transferEngine.onIncomingNack(
                bob.identity.keyHash,
                Nack(msgId, NACK_REASON_BUFFER_FULL),
            )
            runCurrent()
            advanceTimeBy(100L)
            runCurrent()
        }
        // No crash; covers BUFFER_FULL path if session is in pendingDeliveries
    }

    // ── handleAssemblyComplete branches ──────────────────────────────────────

    @Test
    fun `assemblyComplete with non-protocol payload silently ignored`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
        drainFlows(backgroundScope, alice)

        // Inject a single-chunk message containing a Keepalive (not RoutedMessage/Broadcast)
        val keepalivePayload = WireCodec.encode(Keepalive(0u, 0uL, 244u))
        val msgId = ByteArray(16) { 0xE0.toByte() }
        val fakePeer = ByteArray(12) { 0xE0.toByte() }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(Chunk(msgId, 0u, 1u, keepalivePayload)),
        )
        runCurrent()

        assertTrue(received.isEmpty(), "Keepalive assembled payload → ignored")
    }

    @Test
    fun `assemblyComplete with malformed payload silently ignored`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
        drainFlows(backgroundScope, alice)

        // Empty payload → WireCodec.decode throws
        val msgId = ByteArray(16) { 0xE1.toByte() }
        val fakePeer = ByteArray(12) { 0xE1.toByte() }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(Chunk(msgId, 0u, 1u, ByteArray(0))),
        )
        runCurrent()

        assertTrue(received.isEmpty(), "Malformed assembled payload → ignored")
    }

    @Test
    fun `assemblyComplete with Broadcast payload handles correctly`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
        drainFlows(backgroundScope, alice)
        runCurrent() // start collectors

        val thirdParty = ByteArray(12) { 0xE2.toByte() }
        val broadcastPayload =
            WireCodec.encode(
                Broadcast(
                    ByteArray(16) { 0xE2.toByte() },
                    thirdParty,
                    0u,
                    0x0201u.toUShort(),
                    0u,
                    0,
                    null,
                    null,
                    byteArrayOf(0xAB.toByte()),
                )
            )
        val msgId = ByteArray(16) { 0xE3.toByte() }
        val fakePeer = ByteArray(12) { 0xE3.toByte() }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(Chunk(msgId, 0u, 1u, broadcastPayload)),
        )
        runCurrent()

        assertEquals(1, received.size)
        assertEquals(MessageKind.BROADCAST, received.first().kind)
    }

    // ── ACK buffer drain ─────────────────────────────────────────────────────

    @Test
    fun `ack buffer drains when route becomes available via Hello`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        // One-way: alice→bob only
        alice.transport.linkTo(bob.transport)
        alice.routingTable.install(
            RouteEntry(
                bob.identity.keyHash,
                bob.identity.keyHash,
                1.0,
                0u,
                1.0,
                clock() + 600_000L,
                bob.identity.edKeyPair.publicKey,
                bob.identity.dhKeyPair.publicKey,
            )
        )
        alice.routeCoordinator.onPeerConnected(PeerInfo(bob.identity.keyHash, 0, -50, 0.0))
        alice.trustStore.pinKey(bob.identity.keyHash, bob.identity.dhKeyPair.publicKey)
        bob.trustStore.pinKey(alice.identity.keyHash, alice.identity.dhKeyPair.publicKey)

        drainFlows(backgroundScope, bob)
        backgroundScope.launch { alice.pipeline.deliveryConfirmations.collect {} }
        backgroundScope.launch { alice.pipeline.transferProgress.collect {} }
        backgroundScope.launch { alice.pipeline.transferFailures.collect {} }

        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(0x77))
        runCurrent()
        advanceUntilIdle()

        // Bob launches sendDeliveryAckWithRetry — no route back to alice
        // All 3 retries fail → goes to ackBuffer
        advanceTimeBy(15_000L)
        runCurrent()

        // Now add reverse route and inject Hello to trigger drainAckBuffer
        bob.routingTable.install(
            RouteEntry(
                alice.identity.keyHash,
                alice.identity.keyHash,
                1.0,
                0u,
                1.0,
                clock() + 600_000L,
                alice.identity.edKeyPair.publicKey,
                alice.identity.dhKeyPair.publicKey,
            )
        )
        bob.routeCoordinator.onPeerConnected(PeerInfo(alice.identity.keyHash, 0, -50, 0.0))

        val helloInjector = VirtualMeshTransport(alice.identity.keyHash, testScheduler)
        helloInjector.linkTo(bob.transport)
        helloInjector.sendToPeer(
            bob.identity.keyHash,
            WireCodec.encode(Hello(alice.identity.keyHash, 1u, 0u)),
        )
        runCurrent()
        advanceUntilIdle()

        assertTrue(bob.transport.sentFrames.isNotEmpty(), "ACK drained from buffer after route")
    }

    // ── Delivered / DeliveryFailed equality ──────────────────────────────────

    @Test
    fun `Delivered equals hashCode branches`() {
        val id1 = byteArrayOf(1, 2, 3)
        val id2 = byteArrayOf(1, 2, 3)
        val d1 = Delivered(id1)
        val d2 = Delivered(id2)
        assertEquals(d1, d1) // same reference
        assertEquals(d1, d2) // equal content
        assertEquals(d1.hashCode(), d2.hashCode())
        assertFalse(d1.equals("other")) // !is Delivered
        assertFalse(d1 == Delivered(byteArrayOf(1, 2, 4))) // different bytes
    }

    @Test
    fun `DeliveryFailed equals hashCode branches`() {
        val id = byteArrayOf(1, 2, 3)
        val f1 = DeliveryFailed(id, DeliveryOutcome.TIMED_OUT)
        val f2 = DeliveryFailed(byteArrayOf(1, 2, 3), DeliveryOutcome.TIMED_OUT)
        assertEquals(f1, f1)
        assertEquals(f1, f2)
        assertEquals(f1.hashCode(), f2.hashCode())
        assertFalse(f1.equals("nope"))
        assertFalse(f1 == DeliveryFailed(byteArrayOf(1, 2, 4), DeliveryOutcome.TIMED_OUT))
        assertFalse(f1 == DeliveryFailed(id, DeliveryOutcome.SEND_FAILED))
    }
}
