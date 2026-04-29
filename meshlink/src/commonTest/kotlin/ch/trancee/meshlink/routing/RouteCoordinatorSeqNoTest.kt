package ch.trancee.meshlink.routing

import ch.trancee.meshlink.api.DiagnosticSink
import ch.trancee.meshlink.crypto.TrustStore
import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for the per-destination seqNo counter in [RouteCoordinator] (D044, MEM239).
 *
 * Verifies: counter starts at 1u, increments on reconnect, cleaned on removeSeqNo, fresh start
 * after cleanup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteCoordinatorSeqNoTest {

    private val peerIdA = ByteArray(12) { 0x0A }
    private val edKeyA = ByteArray(32) { it.toByte() }
    private val dhKeyA = ByteArray(32) { (it + 32).toByte() }

    private val peerIdB = ByteArray(12) { 0x0B }
    private val peerIdC = ByteArray(12) { 0x0C }

    private var clockMillis = 0L
    private val clock: () -> Long = { clockMillis }

    private val config =
        RoutingConfig(helloIntervalMillis = 1_000L, routeDiscoveryTimeoutMillis = 5_000L)

    private fun makeCoordinator(scope: kotlinx.coroutines.CoroutineScope): RouteCoordinator {
        val table = RoutingTable(clock)
        val storage = InMemorySecureStorage()
        val trustStore = TrustStore(storage)
        val engine =
            RoutingEngine(
                routingTable = table,
                localPeerId = peerIdA,
                localEdPublicKey = edKeyA,
                localDhPublicKey = dhKeyA,
                scope = scope,
                clock = clock,
                config = config,
            )
        val dedupSet = DedupSet(config.dedupCapacity, config.dedupTtlMillis, clock)
        val presenceTracker = PresenceTracker()
        val diagnosticSink =
            DiagnosticSink(bufferCapacity = 64, redactFn = null, clock = clock, wallClock = clock)
        return RouteCoordinator(
            localPeerId = peerIdA,
            localEdPublicKey = edKeyA,
            localDhPublicKey = dhKeyA,
            routingTable = table,
            routingEngine = engine,
            dedupSet = dedupSet,
            presenceTracker = presenceTracker,
            trustStore = trustStore,
            scope = scope,
            clock = clock,
            config = config,
            diagnosticSink = diagnosticSink,
        )
    }

    private fun makePeerInfo(id: ByteArray) =
        PeerInfo(peerId = id, powerMode = 0, rssi = -60, lossRate = 0.0)

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `nextSeqNoFor starts at 1 for first connection`() = runTest {
        val coord = makeCoordinator(backgroundScope)
        assertEquals(1u.toUShort(), coord.nextSeqNoFor(peerIdB))
    }

    @Test
    fun `nextSeqNoFor increments on subsequent calls`() = runTest {
        val coord = makeCoordinator(backgroundScope)
        assertEquals(1u.toUShort(), coord.nextSeqNoFor(peerIdB))
        assertEquals(2u.toUShort(), coord.nextSeqNoFor(peerIdB))
        assertEquals(3u.toUShort(), coord.nextSeqNoFor(peerIdB))
    }

    @Test
    fun `nextSeqNoFor tracks separate counters per destination`() = runTest {
        val coord = makeCoordinator(backgroundScope)
        assertEquals(1u.toUShort(), coord.nextSeqNoFor(peerIdB))
        assertEquals(1u.toUShort(), coord.nextSeqNoFor(peerIdC))
        assertEquals(2u.toUShort(), coord.nextSeqNoFor(peerIdB))
        assertEquals(2u.toUShort(), coord.nextSeqNoFor(peerIdC))
    }

    @Test
    fun `removeSeqNo cleans counter`() = runTest {
        val coord = makeCoordinator(backgroundScope)
        coord.nextSeqNoFor(peerIdB) // 1u
        coord.nextSeqNoFor(peerIdB) // 2u
        coord.removeSeqNo(peerIdB)
        // After cleanup, counter restarts at 1u.
        assertEquals(1u.toUShort(), coord.nextSeqNoFor(peerIdB))
    }

    @Test
    fun `removeSeqNo on unknown peer is no-op`() = runTest {
        val coord = makeCoordinator(backgroundScope)
        // Should not throw.
        coord.removeSeqNo(peerIdB)
        // Counter still starts at 1u.
        assertEquals(1u.toUShort(), coord.nextSeqNoFor(peerIdB))
    }

    @Test
    fun `onPeerConnected installs route with incrementing seqNo`() = runTest {
        val table = RoutingTable(clock)
        val storage = InMemorySecureStorage()
        val trustStore = TrustStore(storage)
        val engine =
            RoutingEngine(
                routingTable = table,
                localPeerId = peerIdA,
                localEdPublicKey = edKeyA,
                localDhPublicKey = dhKeyA,
                scope = backgroundScope,
                clock = clock,
                config = config,
            )
        val dedupSet = DedupSet(config.dedupCapacity, config.dedupTtlMillis, clock)
        val presenceTracker = PresenceTracker()
        val diagnosticSink =
            DiagnosticSink(bufferCapacity = 64, redactFn = null, clock = clock, wallClock = clock)
        val coordinator =
            RouteCoordinator(
                localPeerId = peerIdA,
                localEdPublicKey = edKeyA,
                localDhPublicKey = dhKeyA,
                routingTable = table,
                routingEngine = engine,
                dedupSet = dedupSet,
                presenceTracker = presenceTracker,
                trustStore = trustStore,
                scope = backgroundScope,
                clock = clock,
                config = config,
                diagnosticSink = diagnosticSink,
            )

        // First connection: seqNo should be 1u.
        coordinator.onPeerConnected(makePeerInfo(peerIdB))
        val route1 = table.lookupRoute(peerIdB)
        assertEquals(1u.toUShort(), route1?.seqNo)

        // Simulate disconnect + reconnect: seqNo should be 2u.
        coordinator.onPeerDisconnected(peerIdB)
        coordinator.onPeerConnected(makePeerInfo(peerIdB))
        val route2 = table.lookupRoute(peerIdB)
        assertEquals(2u.toUShort(), route2?.seqNo)
    }
}
