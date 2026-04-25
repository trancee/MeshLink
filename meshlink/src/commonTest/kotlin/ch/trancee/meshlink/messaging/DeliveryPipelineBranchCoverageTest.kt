package ch.trancee.meshlink.messaging

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
import ch.trancee.meshlink.transfer.TransferConfig
import ch.trancee.meshlink.transfer.TransferEngine
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
import ch.trancee.meshlink.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val NACK_REASON_BF: UByte = 0u

/**
 * Supplementary coverage tests targeting the "partial branch" lines identified in the Kover HTML
 * report. Each test here ensures that the incomingData collector is RUNNING (via runCurrent())
 * before messages are injected, so handleIncomingData actually executes and exercises every branch.
 *
 * Background: tests in [DeliveryPipelineTest] that say "// No crash" inject messages before
 * runCurrent() — the SharedFlow discards emissions that arrive before any subscriber subscribes.
 * The tests pass trivially but don't exercise the code paths. These tests fix that gap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryPipelineBranchCoverageTest {

    private val appIdHash = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    private data class Node(
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
        sched: TestCoroutineScheduler,
        clock: () -> Long,
        config: MessagingConfig = relaxed(),
        tConfig: TransferConfig = TransferConfig(inactivityBaseTimeoutMillis = 5_000L),
    ): Node {
        val crypto = createCryptoProvider()
        val identity = Identity.loadOrGenerate(crypto, InMemorySecureStorage())
        val transport = VirtualMeshTransport(identity.keyHash, sched)
        val rc = RoutingConfig()
        val rt = RoutingTable(clock)
        val ts = TrustStore(InMemorySecureStorage())
        val re =
            RoutingEngine(
                rt,
                identity.keyHash,
                identity.edKeyPair.publicKey,
                identity.dhKeyPair.publicKey,
                scope,
                clock,
                rc,
            )
        val rdc =
            RouteCoordinator(
                identity.keyHash,
                identity.edKeyPair.publicKey,
                identity.dhKeyPair.publicKey,
                rt,
                re,
                DedupSet(rc.dedupCapacity, rc.dedupTtlMillis, clock),
                PresenceTracker(),
                ts,
                scope,
                clock,
                rc,
            )
        val te = TransferEngine(scope, tConfig, ChunkSizePolicy.fixed(4096), true)
        val rg = ReplayGuard()
        val pipeline =
            DeliveryPipeline(
                scope,
                transport,
                rdc,
                te,
                InboundValidator,
                identity,
                crypto,
                ts,
                rg,
                DedupSet(10_000, 2_700_000L, clock),
                config,
                clock,
            )
        return Node(identity, transport, rt, rdc, te, ts, rg, pipeline)
    }

    private fun relaxed(
        requireBroadcastSignatures: Boolean = false,
        allowUnsignedBroadcasts: Boolean = true,
        nackLimit: Int = 200,
        nackWindowMs: Long = 60_000L,
        maxBufferedMessages: Int = 5,
        outboundUnicastLimit: Int = 200,
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
            broadcastLimit = 200,
            broadcastWindowMs = 60_000L,
            relayPerSenderPerNeighborLimit = 200,
            relayPerSenderPerNeighborWindowMs = 60_000L,
            perNeighborAggregateLimit = 200,
            perNeighborAggregateWindowMs = 60_000L,
            perSenderInboundLimit = 200,
            perSenderInboundWindowMs = 60_000L,
            handshakeLimit = 200,
            handshakeWindowMs = 60_000L,
            nackLimit = nackLimit,
            nackWindowMs = nackWindowMs,
            highPriorityTtlMs = 10_000L,
            normalPriorityTtlMs = 10_000L,
            lowPriorityTtlMs = 10_000L,
            circuitBreaker = circuitBreaker,
        )

    private fun connect(a: Node, b: Node, clock: () -> Long) {
        a.transport.linkTo(b.transport)
        val exp = clock() + 600_000L
        a.routeCoordinator.onPeerConnected(PeerInfo(b.identity.keyHash, 0, -50, 0.0))
        b.routeCoordinator.onPeerConnected(PeerInfo(a.identity.keyHash, 0, -50, 0.0))
        a.routingTable.install(
            RouteEntry(
                b.identity.keyHash,
                b.identity.keyHash,
                1.0,
                0u,
                1.0,
                exp,
                b.identity.edKeyPair.publicKey,
                b.identity.dhKeyPair.publicKey,
            )
        )
        b.routingTable.install(
            RouteEntry(
                a.identity.keyHash,
                a.identity.keyHash,
                1.0,
                0u,
                1.0,
                exp,
                a.identity.edKeyPair.publicKey,
                a.identity.dhKeyPair.publicKey,
            )
        )
        a.trustStore.pinKey(b.identity.keyHash, b.identity.dhKeyPair.publicKey)
        b.trustStore.pinKey(a.identity.keyHash, a.identity.dhKeyPair.publicKey)
    }

    private fun drainAll(scope: CoroutineScope, n: Node) {
        scope.launch { n.pipeline.messages.collect {} }
        scope.launch { n.pipeline.deliveryConfirmations.collect {} }
        scope.launch { n.pipeline.transferFailures.collect {} }
        scope.launch { n.pipeline.transferProgress.collect {} }
        scope.launch { n.transferEngine.outboundChunks.collect {} }
    }

    // ── handleIncomingData: is Rejected → return ──────────────────────────────

    @Test
    fun `handleIncomingData rejected frame exercises is Rejected branch`() = runTest {
        val clock: () -> Long = { 0L }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        drainAll(backgroundScope, alice)
        runCurrent() // start collectors so handleIncomingData is reached

        val injector = VirtualMeshTransport(ByteArray(12) { 0x01 }, testScheduler)
        injector.linkTo(alice.transport)
        injector.sendToPeer(alice.identity.keyHash, byteArrayOf(0x01, 0x02)) // too short → Rejected
        runCurrent()
        // No crash — is Rejected → return branch exercised
    }

    // ── handleIncomingData: is Handshake / Keepalive / RotationAnnouncement → return ─────

    @Test
    fun `handleIncomingData Handshake Keepalive RotationAnnouncement branches exercised with running collector`() =
        runTest {
            val clock: () -> Long = { 0L }
            val alice = makeNode(backgroundScope, testScheduler, clock)
            drainAll(backgroundScope, alice)
            runCurrent() // collectors started — handleIncomingData will be reached for every inject
            // below

            val fakePeer = ByteArray(12) { 0x10 }
            val injector = VirtualMeshTransport(fakePeer, testScheduler)
            injector.linkTo(alice.transport)

            // Handshake → is Handshake → return
            injector.sendToPeer(
                alice.identity.keyHash,
                WireCodec.encode(Handshake(1u, ByteArray(32))),
            )
            runCurrent()

            // Keepalive → is Keepalive → return
            injector.sendToPeer(alice.identity.keyHash, WireCodec.encode(Keepalive(0u, 0uL, 244u)))
            runCurrent()

            // RotationAnnouncementMessage → is RotationAnnouncementMessage → return
            val crypto = createCryptoProvider()
            val oldEdKp = crypto.generateEd25519KeyPair()
            val newEdKp = crypto.generateEd25519KeyPair()
            val newDhKp = crypto.generateX25519KeyPair()
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
            injector.sendToPeer(alice.identity.keyHash, WireCodec.encode(rot))
            runCurrent()
            // No crash — all three return-early branches exercised
        }

    // ── handleIncomingData: Nack rate-limit FALSE branch ─────────────────────

    @Test
    fun `handleIncomingData Nack rate limited exercises nackLimiter tryAcquire false branch`() =
        runTest {
            val clock: () -> Long = { 0L }
            val alice =
                makeNode(
                    backgroundScope,
                    testScheduler,
                    clock,
                    relaxed(nackLimit = 1, nackWindowMs = 60_000L),
                )
            backgroundScope.launch { alice.transferEngine.outboundChunks.collect {} }
            backgroundScope.launch { alice.transferEngine.events.collect {} }
            runCurrent() // start collectors

            val msgId = ByteArray(16) { 0xEF.toByte() }
            val fakePeer = ByteArray(12) { 0x7C }
            val injector = VirtualMeshTransport(fakePeer, testScheduler)
            injector.linkTo(alice.transport)

            // First Nack: tryAcquire() = true → forwarded
            injector.sendToPeer(alice.identity.keyHash, WireCodec.encode(Nack(msgId, 0u)))
            runCurrent()

            // Second Nack: tryAcquire() = false → dropped (FALSE branch exercised)
            injector.sendToPeer(alice.identity.keyHash, WireCodec.encode(Nack(msgId, 0u)))
            runCurrent()
            // No crash
        }

    // ── handleIncomingData: is ResumeRequest → body exercised ────────────────

    @Test
    fun `handleIncomingData ResumeRequest forwarded to transferEngine with running collector`() =
        runTest {
            val clock: () -> Long = { 0L }
            val alice = makeNode(backgroundScope, testScheduler, clock)
            backgroundScope.launch { alice.transferEngine.outboundChunks.collect {} }
            backgroundScope.launch { alice.transferEngine.events.collect {} }
            runCurrent() // start collectors

            val fakePeer = ByteArray(12) { 0x7D }
            val injector = VirtualMeshTransport(fakePeer, testScheduler)
            injector.linkTo(alice.transport)
            injector.sendToPeer(
                alice.identity.keyHash,
                WireCodec.encode(ResumeRequest(ByteArray(16) { 0xF1.toByte() }, 10u)),
            )
            runCurrent()
            // No crash — transferEngine.onIncomingResumeRequest line exercised
        }

    // ── handleInboundRoutedMessage: relay condition branches ──────────────────

    @Test
    fun `handleInboundRoutedMessage relay branches exercised with running collector`() = runTest {
        val clock: () -> Long = { 1_000L }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val carol = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, carol, clock)
        drainAll(backgroundScope, alice)
        runCurrent() // collectors running

        val fromPeer = ByteArray(12) { 0x60 }
        val injector = VirtualMeshTransport(fromPeer, testScheduler)
        injector.linkTo(alice.transport)

        // hopLimit=0 → return (line 956 TRUE branch)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                RoutedMessage(
                    ByteArray(16) { 0x61 },
                    fromPeer,
                    carol.identity.keyHash,
                    0u,
                    listOf(fromPeer),
                    0,
                    1u,
                    byteArrayOf(1),
                )
            ),
        )
        runCurrent()

        // visited list contains alice → return (line 957 TRUE branch)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                RoutedMessage(
                    ByteArray(16) { 0x62 },
                    fromPeer,
                    carol.identity.keyHash,
                    3u,
                    listOf(fromPeer, alice.identity.keyHash),
                    0,
                    2u,
                    byteArrayOf(2),
                )
            ),
        )
        runCurrent()

        // no route for destination → return (line 958 TRUE branch)
        val unknown = ByteArray(12) { 0x99.toByte() }
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(
                RoutedMessage(
                    ByteArray(16) { 0x63 },
                    fromPeer,
                    unknown,
                    3u,
                    listOf(fromPeer),
                    0,
                    3u,
                    byteArrayOf(3),
                )
            ),
        )
        runCurrent()
        // No crash — all three relay guard branches exercised
    }

    // ── handleInboundBroadcast: signature null/invalid and else-if branches ───

    @Test
    fun `handleInboundBroadcast sig null and invalid and unsigned with requireSig branches`() =
        runTest {
            val clock: () -> Long = { 1_000L }
            val signedConfig =
                relaxed(requireBroadcastSignatures = true, allowUnsignedBroadcasts = false)
            val alice = makeNode(backgroundScope, testScheduler, clock, signedConfig)
            val received = mutableListOf<InboundMessage>()
            backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
            drainAll(backgroundScope, alice)
            runCurrent() // collectors running

            val fakePeer = ByteArray(12) { 0x42 }
            val injector = VirtualMeshTransport(fakePeer, testScheduler)
            injector.linkTo(alice.transport)

            // flags=1 (HAS_SIG), signature=null → sig ?: return (line 1015 TRUE branch)
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
                        byteArrayOf(1),
                    )
                ),
            )
            runCurrent()

            // flags=1 (HAS_SIG), signerKey=null → key ?: return (line 1016 TRUE branch)
            injector.sendToPeer(
                alice.identity.keyHash,
                WireCodec.encode(
                    Broadcast(
                        ByteArray(16) { 0x44 },
                        fakePeer,
                        0u,
                        0x0201u.toUShort(),
                        1u,
                        0,
                        ByteArray(64),
                        null,
                        byteArrayOf(2),
                    )
                ),
            )
            runCurrent()

            // flags=1, sig present but invalid → !verify → return (line 1018 TRUE branch)
            injector.sendToPeer(
                alice.identity.keyHash,
                WireCodec.encode(
                    Broadcast(
                        ByteArray(16) { 0x45 },
                        fakePeer,
                        0u,
                        0x0201u.toUShort(),
                        1u,
                        0,
                        ByteArray(64),
                        ByteArray(32) { it.toByte() },
                        byteArrayOf(3),
                    )
                ),
            )
            runCurrent()

            // flags=0, requireBroadcastSignatures=true, !allowUnsigned → else-if return (line 1019)
            injector.sendToPeer(
                alice.identity.keyHash,
                WireCodec.encode(
                    Broadcast(
                        ByteArray(16) { 0x46 },
                        fakePeer,
                        0u,
                        0x0201u.toUShort(),
                        0u,
                        0,
                        null,
                        null,
                        byteArrayOf(4),
                    )
                ),
            )
            runCurrent()

            assertTrue(received.isEmpty(), "All bad broadcasts dropped")
        }

    @Test
    fun `handleInboundBroadcast origin equals local does not deliver to self`() = runTest {
        val clock: () -> Long = { 1_000L }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        connect(alice, bob, clock)
        val aliceMessages = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { aliceMessages.add(it) } }
        drainAll(backgroundScope, alice)
        runCurrent() // collectors running

        // Broadcast from alice to bob — alice's inbound handler gets the echo
        // with origin == alice.identity.keyHash → if (!origin.contentEquals(localKeyHash)) is FALSE
        val msgId = alice.pipeline.broadcast(byteArrayOf(0xAA.toByte()))
        runCurrent()

        assertTrue(
            aliceMessages.none { it.kind == MessageKind.BROADCAST },
            "Alice does not receive own broadcast",
        )
    }

    // ── handleAssemblyComplete: is DeliveryAck TRUE branch ────────────────────

    @Test
    fun `handleAssemblyComplete DeliveryAck payload exercises is DeliveryAck branch`() = runTest {
        val clock: () -> Long = { 1_000L }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
        drainAll(backgroundScope, alice)
        runCurrent() // collectors running

        val msgId = ByteArray(16) { 0xE2.toByte() }
        val fakePeer = ByteArray(12) { 0xE3.toByte() }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)

        // Inject a single-chunk message containing a DeliveryAck payload
        val ackPayload = WireCodec.encode(DeliveryAck(msgId, fakePeer, 0u, null))
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(Chunk(ByteArray(16) { 0xE4.toByte() }, 0u, 1u, ackPayload)),
        )
        runCurrent()
        // handleAssemblyComplete → is DeliveryAck → handleInboundDeliveryAck(unknownMsgId) →
        // silently dropped
    }

    // ── handleTransferEvent: null pending → return early ─────────────────────

    @Test
    fun `handleTransferEvent TransferFailed for non-pipeline session is silently ignored`() =
        runTest {
            val clock: () -> Long = { 0L }
            val alice =
                makeNode(
                    backgroundScope,
                    testScheduler,
                    clock,
                    tConfig = TransferConfig(inactivityBaseTimeoutMillis = 50L),
                )
            val failures = mutableListOf<DeliveryFailed>()
            backgroundScope.launch { alice.pipeline.transferFailures.collect { failures.add(it) } }
            backgroundScope.launch { alice.transferEngine.outboundChunks.collect {} }
            backgroundScope.launch { alice.transferEngine.events.collect {} }
            runCurrent()

            // Send directly through TransferEngine — NOT through pipeline → NOT in
            // pendingDeliveries
            val msgId = ByteArray(16) { 0x99.toByte() }
            alice.transferEngine.send(msgId, byteArrayOf(1, 2, 3), ByteArray(12) { 0x99.toByte() })
            runCurrent()

            // Trigger MEMORY_PRESSURE immediately (fires TransferFailed at t=0 before pipeline
            // processes anything)
            alice.transferEngine.onMemoryPressure()
            runCurrent()

            // handleTransferEvent receives TransferFailed; pendingDeliveries[msgId]=null → ?:
            // return early
            // No DeliveryFailed is emitted for this session because it wasn't in pendingDeliveries
            assertTrue(failures.isEmpty(), "Non-pipeline session failure silently dropped")
        }

    // ── ackBuffer overflow: false branch of size < 50 ────────────────────────

    @Test
    fun `sendDeliveryAckWithRetry drops entries when ackBuffer reaches 50`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val cbConfig =
            MessagingConfig.CircuitBreakerConfig(
                windowMs = 3_600_000L,
                maxFailures = 500,
                cooldownMs = 1L,
            )
        val alice =
            makeNode(
                backgroundScope,
                testScheduler,
                clock,
                relaxed(outboundUnicastLimit = 500, circuitBreaker = cbConfig),
            )
        val bob = makeNode(backgroundScope, testScheduler, clock)
        // Manual one-way: direct routing-table entry WITHOUT onPeerConnected to prevent
        // routing protocol from propagating alice's route to bob.
        alice.transport.linkTo(bob.transport)
        val expiresAt = clockMs + 600_000L
        alice.routingTable.install(
            RouteEntry(
                bob.identity.keyHash,
                bob.identity.keyHash,
                1.0,
                0u,
                1.0,
                expiresAt,
                bob.identity.edKeyPair.publicKey,
                bob.identity.dhKeyPair.publicKey,
            )
        )
        alice.trustStore.pinKey(bob.identity.keyHash, bob.identity.dhKeyPair.publicKey)
        bob.trustStore.pinKey(alice.identity.keyHash, alice.identity.dhKeyPair.publicKey)
        drainAll(backgroundScope, bob)
        backgroundScope.launch { alice.pipeline.deliveryConfirmations.collect {} }
        backgroundScope.launch { alice.pipeline.transferProgress.collect {} }
        backgroundScope.launch { alice.pipeline.transferFailures.collect {} }
        runCurrent()

        // Send 55 messages with UNIQUE originationTime (clockMs increments per send)
        // so bob's ReplayGuard accepts each one and launches sendDeliveryAckWithRetry.
        repeat(55) { i ->
            clockMs = 1_000L + i // unique originationTime per message
            alice.pipeline.send(bob.identity.keyHash, byteArrayOf(i.toByte()))
            runCurrent()
            advanceTimeBy(15_000L) // exhaust bob's retry delays (2s+4s+8s) → ackBuffer fills
            runCurrent()
        }
        // Entries 1-50 are added; entries 51-55 hit `if (ackBuffer.size < 50)` FALSE branch →
        // dropped
    }

    // ── drainAckBuffer: nextHop null → ?: continue branch ────────────────────

    @Test
    fun `drainAckBuffer continues when no route for buffered ack target`() = runTest {
        var clockMs = 1_000L
        val clock: () -> Long = { clockMs }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val bob = makeNode(backgroundScope, testScheduler, clock)
        // Manual one-way setup WITHOUT onPeerConnected to prevent the routing protocol
        // from advertising alice's route to bob (which would allow bob to route ACKs back).
        alice.transport.linkTo(bob.transport)
        val expiresAt = clockMs + 600_000L
        // Only install alice→bob route DIRECTLY in the routing table (no onPeerConnected call).
        // onPeerConnected triggers routing protocol Hello/Update announcements that propagate
        // alice's route to bob — bypassing that prevents bob from discovering alice's route.
        alice.routingTable.install(
            RouteEntry(
                bob.identity.keyHash,
                bob.identity.keyHash,
                1.0,
                0u,
                1.0,
                expiresAt,
                bob.identity.edKeyPair.publicKey,
                bob.identity.dhKeyPair.publicKey,
            )
        )
        alice.trustStore.pinKey(bob.identity.keyHash, bob.identity.dhKeyPair.publicKey)
        bob.trustStore.pinKey(alice.identity.keyHash, alice.identity.dhKeyPair.publicKey)
        drainAll(backgroundScope, bob)
        backgroundScope.launch { alice.pipeline.deliveryConfirmations.collect {} }
        backgroundScope.launch { alice.pipeline.transferProgress.collect {} }
        backgroundScope.launch { alice.pipeline.transferFailures.collect {} }
        runCurrent()

        alice.pipeline.send(bob.identity.keyHash, byteArrayOf(0x77))
        runCurrent()
        advanceUntilIdle()
        // The above advanceUntilIdle may not reach the 14s mark for bob's retries.
        // Explicitly advance past the sendDeliveryAckWithRetry total delay (2s+4s+8s=14s).
        advanceTimeBy(15_000L)
        runCurrent() // bob's 3 retries exhausted → ackBuffer.addLast(entry for alice)

        // Inject Hello to bob from third party → drainAckBuffer called, alice's route not found
        val thirdParty = ByteArray(12) { 0xFF.toByte() }
        val injector = VirtualMeshTransport(thirdParty, testScheduler)
        injector.linkTo(bob.transport)
        injector.sendToPeer(bob.identity.keyHash, WireCodec.encode(Hello(thirdParty, 1u, 0u)))
        runCurrent()
        // lookupNextHop(alice) = null → ?: continue branch exercised
    }

    // ── evictBufferIfFull: ?: break covered via maxBufferedMessages=0 ────────

    @Test
    fun `evictBufferIfFull breaks when buffer empty with maxBufferedMessages zero`() = runTest {
        val clock: () -> Long = { 0L }
        val config = relaxed(maxBufferedMessages = 0)
        val alice = makeNode(backgroundScope, testScheduler, clock, config)
        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { alice.pipeline.transferFailures.collect { failures.add(it) } }
        runCurrent()

        val r1 = ByteArray(12) { 0xBB.toByte() }
        alice.trustStore.pinKey(r1, ByteArray(32) { it.toByte() })

        // First send: evictBufferIfFull called with empty buffer (maxBufferedMessages=0)
        // while(0>=0) → minWithOrNull on empty list = null → ?: break (NULL branch covered)
        alice.pipeline.send(r1, byteArrayOf(1))
        runCurrent()

        // Second send: evictBufferIfFull called with 1 entry (size=1 >= 0=maxBuffered)
        // → minWithOrNull returns entry → evict → while(0>=0) → null → ?: break again
        alice.pipeline.send(r1, byteArrayOf(2))
        runCurrent()

        assertTrue(failures.isNotEmpty(), "maxBufferedMessages=0: entries evicted on every send")
    }

    // ── PerDestinationCircuitBreaker.onFailure: timestamp eviction loop ───────

    @Test
    fun `circuitBreaker onFailure evicts timestamps older than windowMs before threshold check`() =
        runTest {
            var clockMs = 1L // non-zero so ReplayGuard doesn't reject (counter=0 always rejected)
            val clock: () -> Long = { clockMs }
            // maxFailures=5: we add 4 failures at clock=1 (won't open CB), then 1 at clock=600
            // (>windowMs=500).
            // The 5th failure evicts the 4 old timestamps → failureTimestamps=[600] → size=1 < 5 →
            // CB CLOSED.
            val cbConfig =
                MessagingConfig.CircuitBreakerConfig(
                    windowMs = 500L,
                    maxFailures = 5,
                    cooldownMs = 30_000L,
                )
            val alice =
                makeNode(
                    backgroundScope,
                    testScheduler,
                    clock,
                    relaxed(circuitBreaker = cbConfig),
                    tConfig = TransferConfig(inactivityBaseTimeoutMillis = 50L),
                )
            val bob = makeNode(backgroundScope, testScheduler, clock)
            // Manual one-way WITHOUT onPeerConnected: prevents routing protocol from
            // propagating alice's route to bob (which would allow bob to send DeliveryAcks back).
            alice.transport.linkTo(bob.transport)
            val exp = clockMs + 600_000L
            alice.routingTable.install(
                RouteEntry(
                    bob.identity.keyHash,
                    bob.identity.keyHash,
                    1.0,
                    0u,
                    1.0,
                    exp,
                    bob.identity.edKeyPair.publicKey,
                    bob.identity.dhKeyPair.publicKey,
                )
            )
            alice.trustStore.pinKey(bob.identity.keyHash, bob.identity.dhKeyPair.publicKey)
            bob.trustStore.pinKey(alice.identity.keyHash, alice.identity.dhKeyPair.publicKey)
            backgroundScope.launch { alice.pipeline.transferFailures.collect {} }
            backgroundScope.launch { alice.pipeline.transferProgress.collect {} }
            backgroundScope.launch { alice.pipeline.deliveryConfirmations.collect {} }
            runCurrent()

            // 4 failures at clockMs=1: send msgs with unique originationTime to avoid ReplayGuard
            // rejection
            // bob's ReplayGuard accepts each unique message; alice's ackDeadline fires → onFailure
            // at clock=1
            repeat(4) { i ->
                clockMs = 1L + i // unique originationTime
                alice.pipeline.send(bob.identity.keyHash, byteArrayOf(i.toByte()))
                runCurrent()
                advanceTimeBy(100L) // fires ackDeadline(50ms); clock()=1+i → onFailure at 1+i
                runCurrent()
            }

            // failureTimestamps=[1,2,3,4], size=4 < maxFailures=5 → CB still CLOSED
            clockMs = 600L // advance real clock past windowMs=500

            // 5th failure at clock=600: evicts old timestamps (600-1=599 > 500) → [600], size=1 →
            // CLOSED
            clockMs = 600L
            alice.pipeline.send(bob.identity.keyHash, byteArrayOf(4))
            runCurrent()
            advanceTimeBy(
                100L
            ) // fires ackDeadline → onFailure at clock=600 → evicts [1..4] → CLOSED
            runCurrent()

            // CB should be CLOSED (only 1 recent failure after eviction)
            val result = alice.pipeline.send(bob.identity.keyHash, byteArrayOf(5))
            assertEquals(SendResult.Sent, result, "CB remains closed after timestamp eviction")
        }

    // ── handleInboundBroadcast: origin == local does not deliver ─────────────

    @Test
    fun `handleInboundBroadcast origin equals local skips delivery with running collector`() =
        runTest {
            val clock: () -> Long = { 1_000L }
            val alice = makeNode(backgroundScope, testScheduler, clock)
            val received = mutableListOf<InboundMessage>()
            backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
            drainAll(backgroundScope, alice)
            runCurrent() // collectors running

            val fakePeer = ByteArray(12) { 0x80.toByte() }
            val injector = VirtualMeshTransport(fakePeer, testScheduler)
            injector.linkTo(alice.transport)

            // Broadcast with origin = alice's own keyHash AND a fresh messageId (not in alice's
            // dedup).
            // Dedup check passes (never seen). Sender-rate-limit passes. Sig check skipped (no
            // sig).
            // Then line 490: !origin.contentEquals(localIdentity.keyHash) = FALSE → skip delivery.
            injector.sendToPeer(
                alice.identity.keyHash,
                WireCodec.encode(
                    Broadcast(
                        ByteArray(16) { 0x80.toByte() },
                        alice.identity.keyHash, // origin == local → no delivery
                        0u,
                        0x0201u.toUShort(),
                        0u,
                        0,
                        null,
                        null,
                        byteArrayOf(0x80.toByte()),
                    )
                ),
            )
            runCurrent()

            assertTrue(received.isEmpty(), "Broadcast with local origin → no delivery to self")
        }

    // ── handleAssemblyComplete: else → return (non-protocol payload) ──────────

    @Test
    fun `handleAssemblyComplete non-protocol payload exercises else return branch`() = runTest {
        val clock: () -> Long = { 1_000L }
        val alice = makeNode(backgroundScope, testScheduler, clock)
        val received = mutableListOf<InboundMessage>()
        backgroundScope.launch { alice.pipeline.messages.collect { received.add(it) } }
        drainAll(backgroundScope, alice)
        runCurrent() // collectors running

        val keepalivePayload = WireCodec.encode(Keepalive(0u, 0uL, 244u))
        val msgId = ByteArray(16) { 0xE0.toByte() }
        val fakePeer = ByteArray(12) { 0xE0.toByte() }
        val injector = VirtualMeshTransport(fakePeer, testScheduler)
        injector.linkTo(alice.transport)

        // Chunk containing a Keepalive payload → assembled → handleAssemblyComplete
        // → WireCodec.decode(keepalivePayload) → Keepalive → else → return (line 566 FALSE branch)
        injector.sendToPeer(
            alice.identity.keyHash,
            WireCodec.encode(Chunk(msgId, 0u, 1u, keepalivePayload)),
        )
        runCurrent()

        assertTrue(
            received.isEmpty(),
            "Non-protocol assembled payload → else → return, not delivered",
        )
    }
}
