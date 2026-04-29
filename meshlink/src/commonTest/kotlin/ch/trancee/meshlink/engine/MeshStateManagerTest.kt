package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DiagnosticCode
import ch.trancee.meshlink.api.DiagnosticEvent
import ch.trancee.meshlink.api.DiagnosticPayload
import ch.trancee.meshlink.api.DiagnosticSink
import ch.trancee.meshlink.api.DiagnosticSinkApi
import ch.trancee.meshlink.api.NoOpDiagnosticSink
import ch.trancee.meshlink.crypto.TrustStore
import ch.trancee.meshlink.power.PowerConfig
import ch.trancee.meshlink.power.PowerManager
import ch.trancee.meshlink.power.StubBatteryMonitor
import ch.trancee.meshlink.routing.DedupSet
import ch.trancee.meshlink.routing.PeerInfo
import ch.trancee.meshlink.routing.PresenceTracker
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.routing.RoutingConfig
import ch.trancee.meshlink.routing.RoutingEngine
import ch.trancee.meshlink.routing.RoutingTable
import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MeshStateManagerTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private val localId = ByteArray(12) { 0x01 }
    private val edKey = ByteArray(32) { it.toByte() }
    private val dhKey = ByteArray(32) { (it + 32).toByte() }

    private val peerA = ByteArray(12) { 0x0A }
    private val peerB = ByteArray(12) { 0x0B }

    private fun makePeerInfo(id: ByteArray, rssi: Int = -50) =
        PeerInfo(peerId = id, powerMode = 0, rssi = rssi, lossRate = 0.0)

    /**
     * Builds an isolated test harness with all subsystems wired. Returns a [TestHarness] tuple for
     * fine-grained assertion.
     */
    private data class TestHarness(
        val presenceTracker: PresenceTracker,
        val routeCoordinator: RouteCoordinator,
        val powerManager: PowerManager,
        val meshStateManager: MeshStateManager,
        val diagnosticSink: DiagnosticSinkApi,
    )

    private fun buildHarness(
        scope: kotlinx.coroutines.CoroutineScope,
        clock: () -> Long,
        sweepIntervalMillis: Long = 30_000L,
        useDiagnosticSink: Boolean = false,
    ): TestHarness {
        val routingTable = RoutingTable(clock)
        val config = RoutingConfig(helloIntervalMillis = 30_000L, routeExpiryMillis = 300_000L)
        val routingEngine =
            RoutingEngine(
                routingTable = routingTable,
                localPeerId = localId,
                localEdPublicKey = edKey,
                localDhPublicKey = dhKey,
                scope = scope,
                clock = clock,
                config = config,
            )
        val dedupSet = DedupSet(config.dedupCapacity, config.dedupTtlMillis, clock)
        val presenceTracker = PresenceTracker(clock)
        val trustStore = TrustStore(InMemorySecureStorage())
        val routeCoordinator =
            RouteCoordinator(
                localPeerId = localId,
                localEdPublicKey = edKey,
                localDhPublicKey = dhKey,
                routingTable = routingTable,
                routingEngine = routingEngine,
                dedupSet = dedupSet,
                presenceTracker = presenceTracker,
                trustStore = trustStore,
                scope = scope,
                clock = clock,
                config = config,
            )
        val powerConfig =
            PowerConfig(
                batteryPollIntervalMillis = 300_000L,
                bootstrapDurationMillis = 100L,
                hysteresisDelayMillis = 100L,
            )
        val diagnosticSink: DiagnosticSinkApi =
            if (useDiagnosticSink) {
                DiagnosticSink(
                    bufferCapacity = 128,
                    redactFn = null,
                    clock = clock,
                    wallClock = clock,
                )
            } else {
                NoOpDiagnosticSink
            }
        val powerManager =
            PowerManager(
                scope = scope,
                batteryMonitor = StubBatteryMonitor(),
                clock = clock,
                config = powerConfig,
                diagnosticSink = diagnosticSink,
            )
        val meshStateManager =
            MeshStateManager(
                presenceTracker = presenceTracker,
                routeCoordinator = routeCoordinator,
                powerManager = powerManager,
                diagnosticSink = diagnosticSink,
                sweepIntervalMillis = sweepIntervalMillis,
            )
        return TestHarness(
            presenceTracker,
            routeCoordinator,
            powerManager,
            meshStateManager,
            diagnosticSink,
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `sweep does not run before interval elapses`() = runTest {
        val h =
            buildHarness(
                backgroundScope,
                { testScheduler.currentTime },
                sweepIntervalMillis = 1_000L,
            )
        h.presenceTracker.onPeerConnected(peerA)
        h.presenceTracker.onPeerDisconnected(peerA)

        h.meshStateManager.start(backgroundScope)
        testScheduler.runCurrent()

        // Advance 999ms — sweep should NOT have run yet.
        testScheduler.advanceTimeBy(999L)
        testScheduler.runCurrent()

        // Peer should still be Disconnected (not evicted).
        assertEquals(
            ch.trancee.meshlink.routing.InternalPeerState.DISCONNECTED,
            h.presenceTracker.peerState(peerA),
        )

        h.meshStateManager.stop()
    }

    @Test
    fun `sweep runs at configured interval`() = runTest {
        val h =
            buildHarness(
                backgroundScope,
                { testScheduler.currentTime },
                sweepIntervalMillis = 1_000L,
            )
        h.presenceTracker.onPeerConnected(peerA)
        h.presenceTracker.onPeerDisconnected(peerA)

        h.meshStateManager.start(backgroundScope)
        testScheduler.runCurrent()

        // Advance past one sweep interval.
        testScheduler.advanceTimeBy(1_001L)
        testScheduler.runCurrent()

        // After 1 sweep: sweepCount incremented to 1, still tracked (not evicted yet).
        assertEquals(
            ch.trancee.meshlink.routing.InternalPeerState.DISCONNECTED,
            h.presenceTracker.peerState(peerA),
        )

        h.meshStateManager.stop()
    }

    @Test
    fun `peer evicted after 3 sweep ticks (sweepCount reaches 2)`() = runTest {
        val h =
            buildHarness(
                backgroundScope,
                { testScheduler.currentTime },
                sweepIntervalMillis = 1_000L,
            )
        h.presenceTracker.onPeerConnected(peerA)
        h.presenceTracker.onPeerDisconnected(peerA)

        h.meshStateManager.start(backgroundScope)
        testScheduler.runCurrent()

        // Sweep 1: sweepCount 0→1
        testScheduler.advanceTimeBy(1_001L)
        testScheduler.runCurrent()
        assertEquals(
            ch.trancee.meshlink.routing.InternalPeerState.DISCONNECTED,
            h.presenceTracker.peerState(peerA),
        )

        // Sweep 2: sweepCount 1→2
        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()
        assertEquals(
            ch.trancee.meshlink.routing.InternalPeerState.DISCONNECTED,
            h.presenceTracker.peerState(peerA),
        )

        // Sweep 3: sweepCount ≥2 → evict (Gone)
        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()
        assertNull(h.presenceTracker.peerState(peerA), "Peer should be evicted after 3 sweeps")

        h.meshStateManager.stop()
    }

    @Test
    fun `Gone peer routes are cleaned up via RouteCoordinator`() = runTest {
        val h =
            buildHarness(
                backgroundScope,
                { testScheduler.currentTime },
                sweepIntervalMillis = 1_000L,
            )
        // Connect peer so it has a route in the routing table.
        h.routeCoordinator.onPeerConnected(makePeerInfo(peerA))

        // Now disconnect — presenceTracker records Disconnected.
        h.presenceTracker.onPeerDisconnected(peerA)

        h.meshStateManager.start(backgroundScope)
        testScheduler.runCurrent()

        // Route should still exist before eviction.
        assertTrue(h.routeCoordinator.connectedPeers().any { it.contentEquals(peerA) })

        // 3 sweep ticks to reach Gone.
        repeat(3) {
            testScheduler.advanceTimeBy(1_001L)
            testScheduler.runCurrent()
        }

        // After eviction, route should be cleaned up.
        assertTrue(
            h.routeCoordinator.connectedPeers().none { it.contentEquals(peerA) },
            "Routes should be cleaned up after Gone eviction",
        )

        h.meshStateManager.stop()
    }

    @Test
    fun `reconnect during grace period prevents eviction`() = runTest {
        val h =
            buildHarness(
                backgroundScope,
                { testScheduler.currentTime },
                sweepIntervalMillis = 1_000L,
            )
        h.presenceTracker.onPeerConnected(peerA)
        h.presenceTracker.onPeerDisconnected(peerA)

        h.meshStateManager.start(backgroundScope)
        testScheduler.runCurrent()

        // Sweep 1: sweepCount 0→1
        testScheduler.advanceTimeBy(1_001L)
        testScheduler.runCurrent()

        // Reconnect during grace period.
        h.presenceTracker.onPeerConnected(peerA)

        // Sweep 2 + 3: peer is Connected, should not be evicted.
        repeat(2) {
            testScheduler.advanceTimeBy(1_000L)
            testScheduler.runCurrent()
        }

        assertEquals(
            ch.trancee.meshlink.routing.InternalPeerState.CONNECTED,
            h.presenceTracker.peerState(peerA),
            "Reconnected peer should remain Connected",
        )

        h.meshStateManager.stop()
    }

    @Test
    fun `multiple peers tracked independently`() = runTest {
        val h =
            buildHarness(
                backgroundScope,
                { testScheduler.currentTime },
                sweepIntervalMillis = 1_000L,
            )

        // PeerA disconnects first.
        h.presenceTracker.onPeerConnected(peerA)
        h.presenceTracker.onPeerDisconnected(peerA)

        h.meshStateManager.start(backgroundScope)
        testScheduler.runCurrent()

        // After 2 sweeps, disconnect peerB.
        repeat(2) {
            testScheduler.advanceTimeBy(1_001L)
            testScheduler.runCurrent()
        }
        h.presenceTracker.onPeerConnected(peerB)
        h.presenceTracker.onPeerDisconnected(peerB)

        // Sweep 3: peerA evicted, peerB sweepCount 0→1.
        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()

        assertNull(h.presenceTracker.peerState(peerA), "PeerA should be evicted")
        assertEquals(
            ch.trancee.meshlink.routing.InternalPeerState.DISCONNECTED,
            h.presenceTracker.peerState(peerB),
            "PeerB should still be in grace period",
        )

        h.meshStateManager.stop()
    }

    @Test
    fun `stop cancels sweep timer`() = runTest {
        val h =
            buildHarness(
                backgroundScope,
                { testScheduler.currentTime },
                sweepIntervalMillis = 1_000L,
            )
        h.presenceTracker.onPeerConnected(peerA)
        h.presenceTracker.onPeerDisconnected(peerA)

        h.meshStateManager.start(backgroundScope)
        testScheduler.runCurrent()

        // Stop before any sweep runs.
        h.meshStateManager.stop()

        // Advance well past multiple sweep intervals.
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()

        // Peer should still be tracked (no eviction because sweep stopped).
        assertEquals(
            ch.trancee.meshlink.routing.InternalPeerState.DISCONNECTED,
            h.presenceTracker.peerState(peerA),
            "Sweep should not run after stop",
        )
    }

    @Test
    fun `diagnostic events emitted on sweep tick and Gone eviction`() = runTest {
        val h =
            buildHarness(
                backgroundScope,
                { testScheduler.currentTime },
                sweepIntervalMillis = 1_000L,
                useDiagnosticSink = true,
            )
        h.presenceTracker.onPeerConnected(peerA)
        h.presenceTracker.onPeerDisconnected(peerA)

        // Collect diagnostic events.
        val events = mutableListOf<DiagnosticEvent>()
        val collectJob = backgroundScope.launch {
            h.diagnosticSink.events.collect { events.add(it) }
        }
        testScheduler.runCurrent()

        h.meshStateManager.start(backgroundScope)
        testScheduler.runCurrent()

        // 3 sweep ticks to evict.
        repeat(3) {
            testScheduler.advanceTimeBy(1_001L)
            testScheduler.runCurrent()
        }

        h.meshStateManager.stop()
        collectJob.cancel()

        // Filter to just MeshStateManager events (sweep tick LOG + eviction THRESHOLD).
        val sweepTickEvents = events.filter {
            it.code == DiagnosticCode.ROUTE_CHANGED &&
                (it.payload as? DiagnosticPayload.RouteChanged)
                    ?.destination
                    ?.hex
                    ?.startsWith("sweep:") == true
        }
        val evictionEvents = events.filter { it.code == DiagnosticCode.PEER_PRESENCE_EVICTED }

        // Should have 3 sweep tick events (one per sweep).
        assertEquals(3, sweepTickEvents.size, "Expected 3 sweep tick diagnostic events")

        // Should have 1 eviction event (peerA evicted on sweep 3).
        assertEquals(1, evictionEvents.size, "Expected 1 eviction diagnostic event")
        val evictionPayload = evictionEvents[0].payload as DiagnosticPayload.PeerPresenceEvicted
        assertTrue(evictionPayload.peerId.hex.isNotEmpty(), "Eviction should include peerId")
    }

    @Test
    fun `diagnostic emitted on Connected-to-Disconnected transition`() = runTest {
        val h =
            buildHarness(
                backgroundScope,
                { testScheduler.currentTime },
                sweepIntervalMillis = 10_000L,
                useDiagnosticSink = true,
            )

        // Collect diagnostic events.
        val events = mutableListOf<DiagnosticEvent>()
        val collectJob = backgroundScope.launch {
            h.diagnosticSink.events.collect { events.add(it) }
        }
        testScheduler.runCurrent()

        h.meshStateManager.start(backgroundScope)
        testScheduler.runCurrent()

        // Connect then disconnect — should emit transition:disconnected diagnostic.
        h.presenceTracker.onPeerConnected(peerA)
        testScheduler.runCurrent()
        h.presenceTracker.onPeerDisconnected(peerA)
        testScheduler.runCurrent()

        h.meshStateManager.stop()
        collectJob.cancel()

        val disconnectEvents = events.filter {
            it.code == DiagnosticCode.ROUTE_CHANGED &&
                (it.payload as? DiagnosticPayload.RouteChanged)
                    ?.destination
                    ?.hex
                    ?.startsWith("transition:disconnected:") == true
        }
        assertEquals(1, disconnectEvents.size, "Expected 1 disconnect transition diagnostic")
    }

    @Test
    fun `diagnostic emitted on Disconnected-to-Connected reconnect`() = runTest {
        val h =
            buildHarness(
                backgroundScope,
                { testScheduler.currentTime },
                sweepIntervalMillis = 10_000L,
                useDiagnosticSink = true,
            )

        // Collect diagnostic events.
        val events = mutableListOf<DiagnosticEvent>()
        val collectJob = backgroundScope.launch {
            h.diagnosticSink.events.collect { events.add(it) }
        }
        testScheduler.runCurrent()

        h.meshStateManager.start(backgroundScope)
        testScheduler.runCurrent()

        // Connect → disconnect → reconnect.
        h.presenceTracker.onPeerConnected(peerA)
        testScheduler.runCurrent()
        h.presenceTracker.onPeerDisconnected(peerA)
        testScheduler.runCurrent()
        h.presenceTracker.onPeerConnected(peerA)
        testScheduler.runCurrent()

        h.meshStateManager.stop()
        collectJob.cancel()

        val reconnectEvents = events.filter {
            it.code == DiagnosticCode.ROUTE_CHANGED &&
                (it.payload as? DiagnosticPayload.RouteChanged)
                    ?.destination
                    ?.hex
                    ?.startsWith("transition:connected:") == true
        }
        // 2 connected events: initial connect + reconnect.
        assertEquals(2, reconnectEvents.size, "Expected 2 connected transition diagnostics")
    }

    @Test
    fun `all 4 diagnostic event types emitted in full lifecycle`() = runTest {
        val h =
            buildHarness(
                backgroundScope,
                { testScheduler.currentTime },
                sweepIntervalMillis = 1_000L,
                useDiagnosticSink = true,
            )

        val events = mutableListOf<DiagnosticEvent>()
        val collectJob = backgroundScope.launch {
            h.diagnosticSink.events.collect { events.add(it) }
        }
        testScheduler.runCurrent()

        h.meshStateManager.start(backgroundScope)
        testScheduler.runCurrent()

        // Full lifecycle: connect → disconnect → 3 sweeps (eviction) → reconnect.
        h.presenceTracker.onPeerConnected(peerA)
        testScheduler.runCurrent()
        h.presenceTracker.onPeerDisconnected(peerA)
        testScheduler.runCurrent()

        // 3 sweeps to evict.
        repeat(3) {
            testScheduler.advanceTimeBy(1_001L)
            testScheduler.runCurrent()
        }

        // Reconnect after eviction.
        h.presenceTracker.onPeerConnected(peerA)
        testScheduler.runCurrent()

        h.meshStateManager.stop()
        collectJob.cancel()

        // Verify all 4 diagnostic event types were emitted.
        val sweepTicks = events.filter {
            it.code == DiagnosticCode.ROUTE_CHANGED &&
                (it.payload as? DiagnosticPayload.RouteChanged)
                    ?.destination
                    ?.hex
                    ?.startsWith("sweep:") == true
        }
        val disconnectTransitions = events.filter {
            it.code == DiagnosticCode.ROUTE_CHANGED &&
                (it.payload as? DiagnosticPayload.RouteChanged)
                    ?.destination
                    ?.hex
                    ?.startsWith("transition:disconnected:") == true
        }
        val evictions = events.filter { it.code == DiagnosticCode.PEER_PRESENCE_EVICTED }
        val connectTransitions = events.filter {
            it.code == DiagnosticCode.ROUTE_CHANGED &&
                (it.payload as? DiagnosticPayload.RouteChanged)
                    ?.destination
                    ?.hex
                    ?.startsWith("transition:connected:") == true
        }

        assertTrue(sweepTicks.isNotEmpty(), "Expected sweep tick diagnostics")
        assertTrue(disconnectTransitions.isNotEmpty(), "Expected disconnect transition diagnostic")
        assertTrue(evictions.isNotEmpty(), "Expected eviction diagnostic")
        assertTrue(connectTransitions.isNotEmpty(), "Expected connect transition diagnostic")
    }

    @Test
    fun `connected peer not evicted by sweep`() = runTest {
        val h =
            buildHarness(
                backgroundScope,
                { testScheduler.currentTime },
                sweepIntervalMillis = 1_000L,
            )
        h.presenceTracker.onPeerConnected(peerA)
        // peerA stays Connected — never disconnected.

        h.meshStateManager.start(backgroundScope)
        testScheduler.runCurrent()

        // Run many sweeps.
        repeat(5) {
            testScheduler.advanceTimeBy(1_001L)
            testScheduler.runCurrent()
        }

        assertEquals(
            ch.trancee.meshlink.routing.InternalPeerState.CONNECTED,
            h.presenceTracker.peerState(peerA),
            "Connected peer should never be evicted by sweep",
        )

        h.meshStateManager.stop()
    }
}
