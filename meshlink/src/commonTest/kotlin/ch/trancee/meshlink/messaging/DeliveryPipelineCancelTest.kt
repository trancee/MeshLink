package ch.trancee.meshlink.messaging

import ch.trancee.meshlink.api.DiagnosticSink
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.crypto.TrustStore
import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.routing.DedupSet
import ch.trancee.meshlink.routing.PresenceTracker
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.routing.RoutingConfig
import ch.trancee.meshlink.routing.RoutingEngine
import ch.trancee.meshlink.routing.RoutingTable
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.transfer.ChunkSizePolicy
import ch.trancee.meshlink.transfer.TransferConfig
import ch.trancee.meshlink.transfer.TransferEngine
import ch.trancee.meshlink.transport.VirtualMeshTransport
import ch.trancee.meshlink.wire.InboundValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [DeliveryPipeline.cancelMessagesFor].
 *
 * Verifies that cancelling messages for a peer:
 * - Removes matching entries from the send buffer.
 * - Emits [DeliveryFailed] with [DeliveryOutcome.TIMED_OUT] for each cancelled message.
 * - Does not affect messages for other peers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryPipelineCancelTest {

    private val appIdHash = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    // ── Test node factory ───────────────────────────────────────────────────

    private class TestNode(
        val identity: Identity,
        val trustStore: TrustStore,
        val routeCoordinator: RouteCoordinator,
        val pipeline: DeliveryPipeline,
    )

    private fun makeNode(
        scope: CoroutineScope,
        testScheduler: TestCoroutineScheduler,
        clock: () -> Long,
    ): TestNode {
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
            TransferEngine(
                scope,
                TransferConfig(inactivityBaseTimeoutMillis = 5_000L),
                ChunkSizePolicy.fixed(4096),
                true,
            )
        val messageDedupSet = DedupSet(10_000, 2_700_000L, clock)
        val messagingConfig =
            MessagingConfig(
                appIdHash = appIdHash,
                requireBroadcastSignatures = false,
                allowUnsignedBroadcasts = true,
                maxBufferedMessages = 20,
                outboundUnicastLimit = 200,
                outboundUnicastWindowMillis = 60_000L,
                broadcastLimit = 200,
                broadcastWindowMillis = 60_000L,
                relayPerSenderPerNeighborLimit = 100,
                relayPerSenderPerNeighborWindowMillis = 10_000L,
                perNeighborAggregateLimit = 200,
                perNeighborAggregateWindowMillis = 10_000L,
                perSenderInboundLimit = 100,
                perSenderInboundWindowMillis = 10_000L,
                nackLimit = 50,
                nackWindowMillis = 10_000L,
            )
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
                config = messagingConfig,
                clock = clock,
                diagnosticSink = diagnosticSink,
            )
        return TestNode(identity, trustStore, routeCoordinator, pipeline)
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `cancelMessagesFor emits DeliveryFailed for buffered messages`() = runTest {
        var now = 0L
        val clock: () -> Long = { now }
        val node = makeNode(backgroundScope, testScheduler, clock)

        // Create a remote peer identity and pin its key so send() can encrypt.
        val crypto = createCryptoProvider()
        val remoteStorage = InMemorySecureStorage()
        val remoteIdentity = Identity.loadOrGenerate(crypto, remoteStorage)
        node.trustStore.pinKey(remoteIdentity.keyHash, remoteIdentity.dhKeyPair.publicKey)

        // No route to remote → messages go into sendBuffer.
        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { node.pipeline.transferFailures.collect { failures.add(it) } }

        // Start the collector before send so emissions are captured.
        runCurrent()

        node.pipeline.send(remoteIdentity.keyHash, byteArrayOf(1, 2, 3))
        node.pipeline.send(remoteIdentity.keyHash, byteArrayOf(4, 5, 6))

        // cancelMessagesFor should drain the buffer and emit failures.
        node.pipeline.cancelMessagesFor(remoteIdentity.keyHash)

        // Give the shared flow a chance to deliver.
        runCurrent()

        assertEquals(2, failures.size, "Expected 2 DeliveryFailed events")
        assertTrue(failures.all { it.outcome == DeliveryOutcome.TIMED_OUT })
    }

    @Test
    fun `cancelMessagesFor does not affect other peers`() = runTest {
        var now = 0L
        val clock: () -> Long = { now }
        val node = makeNode(backgroundScope, testScheduler, clock)

        val crypto = createCryptoProvider()

        // Two remote peers.
        val remoteStorageA = InMemorySecureStorage()
        val remoteA = Identity.loadOrGenerate(crypto, remoteStorageA)
        node.trustStore.pinKey(remoteA.keyHash, remoteA.dhKeyPair.publicKey)

        val remoteStorageB = InMemorySecureStorage()
        val remoteB = Identity.loadOrGenerate(crypto, remoteStorageB)
        node.trustStore.pinKey(remoteB.keyHash, remoteB.dhKeyPair.publicKey)

        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { node.pipeline.transferFailures.collect { failures.add(it) } }

        // Start the collector before send so emissions are captured.
        runCurrent()

        // Buffer messages to both peers (no routes).
        node.pipeline.send(remoteA.keyHash, byteArrayOf(1))
        node.pipeline.send(remoteB.keyHash, byteArrayOf(2))

        // Cancel only peer A.
        node.pipeline.cancelMessagesFor(remoteA.keyHash)

        runCurrent()

        // Only 1 failure for peer A.
        assertEquals(1, failures.size, "Expected 1 DeliveryFailed event for peer A only")
    }

    @Test
    fun `cancelMessagesFor is no-op when no messages for peer`() = runTest {
        var now = 0L
        val clock: () -> Long = { now }
        val node = makeNode(backgroundScope, testScheduler, clock)

        val failures = mutableListOf<DeliveryFailed>()
        backgroundScope.launch { node.pipeline.transferFailures.collect { failures.add(it) } }

        // Start the collector.
        runCurrent()

        // Cancel for a peer with no buffered messages.
        node.pipeline.cancelMessagesFor(ByteArray(12) { 0xFF.toByte() })

        runCurrent()

        assertEquals(0, failures.size, "Expected no DeliveryFailed events")
    }
}
