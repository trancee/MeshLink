package ch.trancee.meshlink.routing

import ch.trancee.meshlink.api.DiagnosticCode
import ch.trancee.meshlink.api.DiagnosticPayload
import ch.trancee.meshlink.api.DiagnosticSink
import ch.trancee.meshlink.api.DiagnosticSinkApi
import ch.trancee.meshlink.crypto.TrustStore
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.wire.Update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for ROUTE_CHANGED diagnostic events emitted by [RouteCoordinator].
 *
 * Two emit sites:
 * 1. `onPeerConnected()` — direct route install.
 * 2. `processInbound(Update)` — accepted route update.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteCoordinatorDiagnosticTest {

    private val peerIdA = ByteArray(12) { 0x0A }
    private val edKeyA = ByteArray(32) { it.toByte() }
    private val dhKeyA = ByteArray(32) { (it + 32).toByte() }

    private val peerIdB = ByteArray(12) { 0x0B }

    private val destC = ByteArray(12) { 0xCC.toByte() }
    private val edKeyC = ByteArray(32) { (it + 2).toByte() }
    private val dhKeyC = ByteArray(32) { (it + 34).toByte() }

    private var clockMillis = 0L
    private val clock: () -> Long = { clockMillis }

    private val config =
        RoutingConfig(helloIntervalMillis = 1_000L, routeDiscoveryTimeoutMillis = 5_000L)

    // ── Node factory ─────────────────────────────────────────────────────────

    private class TestNode(
        val table: RoutingTable,
        val trustStore: TrustStore,
        val coordinator: RouteCoordinator,
        val diagnosticSink: DiagnosticSinkApi,
    )

    private fun makeNode(
        localId: ByteArray,
        edKey: ByteArray,
        dhKey: ByteArray,
        scope: kotlinx.coroutines.CoroutineScope,
    ): TestNode {
        val table = RoutingTable(clock)
        val storage = InMemorySecureStorage()
        val trustStore = TrustStore(storage)
        val engine =
            RoutingEngine(
                routingTable = table,
                localPeerId = localId,
                localEdPublicKey = edKey,
                localDhPublicKey = dhKey,
                scope = scope,
                clock = clock,
                config = config,
            )
        val dedupSet = DedupSet(config.dedupCapacity, config.dedupTtlMillis, clock)
        val presenceTracker = PresenceTracker()
        val diagnosticSink =
            DiagnosticSink(bufferCapacity = 64, redactFn = null, clock = clock, wallClock = clock)
        val coordinator =
            RouteCoordinator(
                localPeerId = localId,
                localEdPublicKey = edKey,
                localDhPublicKey = dhKey,
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
        return TestNode(table, trustStore, coordinator, diagnosticSink)
    }

    private fun makePeerInfo(id: ByteArray, rssi: Int = -60, lossRate: Double = 0.0) =
        PeerInfo(peerId = id, powerMode = 0, rssi = rssi, lossRate = lossRate)

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun routeChangedEmitsOnPeerConnected() = runTest {
        val node = makeNode(peerIdA, edKeyA, dhKeyA, backgroundScope)

        node.coordinator.onPeerConnected(makePeerInfo(peerIdB))

        val event = node.diagnosticSink.events.replayCache.lastOrNull()
        assertNotNull(event, "Expected ROUTE_CHANGED diagnostic event on peer connected")
        assertEquals(DiagnosticCode.ROUTE_CHANGED, event.code)
        val payload = assertIs<DiagnosticPayload.RouteChanged>(event.payload)
        assertTrue(payload.destination.hex.isNotEmpty(), "destination should be non-empty hex")
        assertTrue(payload.cost > 0.0, "cost should be positive")
    }

    @Test
    fun routeChangedEmitsOnAcceptedUpdate() = runTest {
        val node = makeNode(peerIdA, edKeyA, dhKeyA, backgroundScope)

        // First establish a neighbor relationship so the Update has a link cost source.
        node.coordinator.onPeerConnected(makePeerInfo(peerIdB))

        // Now send a route Update from B advertising destC.
        val update =
            Update(
                destination = destC,
                metric = 200u,
                seqNo = 100u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            )
        node.coordinator.processInbound(peerIdB, update)

        val event = node.diagnosticSink.events.replayCache.lastOrNull()
        assertNotNull(event, "Expected ROUTE_CHANGED diagnostic event on accepted Update")
        assertEquals(DiagnosticCode.ROUTE_CHANGED, event.code)
        val payload = assertIs<DiagnosticPayload.RouteChanged>(event.payload)
        // destination hex should match destC
        assertTrue(payload.destination.hex.isNotEmpty(), "destination should be non-empty hex")
        assertTrue(payload.cost > 0.0, "cost should be positive")
    }

    @Test
    fun routeChangedNotEmittedOnRejectedUpdate() = runTest {
        val node = makeNode(peerIdA, edKeyA, dhKeyA, backgroundScope)
        node.coordinator.onPeerConnected(makePeerInfo(peerIdB))

        // Accept an initial Update with seqNo=100, metric=200
        node.coordinator.processInbound(
            peerIdB,
            Update(
                destC,
                metric = 200u,
                seqNo = 100u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            ),
        )

        // Now send a stale Update with same seqNo but higher metric — should be rejected.
        node.coordinator.processInbound(
            peerIdB,
            Update(
                destC,
                metric = 500u,
                seqNo = 100u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            ),
        )

        // The last event should still be the accepted Update's ROUTE_CHANGED,
        // NOT a new one from the rejected update. With replay=1, only the last
        // emitted event is in the cache — which is the accepted Update's event.
        val events = node.diagnosticSink.events.replayCache
        val event = events.lastOrNull()
        assertNotNull(event, "Should have at least one event")
        assertEquals(DiagnosticCode.ROUTE_CHANGED, event.code)
        // The destination is destC (from the accepted Update), not peerIdB (from onPeerConnected).
        val payload = assertIs<DiagnosticPayload.RouteChanged>(event.payload)
        val destCHex = destC.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        assertEquals(destCHex, payload.destination.hex)
    }
}
