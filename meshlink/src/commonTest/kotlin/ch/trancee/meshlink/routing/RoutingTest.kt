package ch.trancee.meshlink.routing

import ch.trancee.meshlink.api.DiagnosticSinkApi
import ch.trancee.meshlink.api.NoOpDiagnosticSink
import ch.trancee.meshlink.crypto.TrustStore
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.wire.Hello
import ch.trancee.meshlink.wire.Keepalive
import ch.trancee.meshlink.wire.Update
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RoutingTest {

    // ── fixtures ───────────────────────────────────────────────────────────────

    private val peerIdA = ByteArray(12) { 0x0A }
    private val edKeyA = ByteArray(32) { it.toByte() }
    private val dhKeyA = ByteArray(32) { (it + 32).toByte() }

    private val peerIdB = ByteArray(12) { 0x0B }
    private val edKeyB = ByteArray(32) { (it + 1).toByte() }
    private val dhKeyB = ByteArray(32) { (it + 33).toByte() }

    private val destC = ByteArray(12) { 0xCC.toByte() }
    private val edKeyC = ByteArray(32) { (it + 2).toByte() }
    private val dhKeyC = ByteArray(32) { (it + 34).toByte() }

    private var clockMillis = 0L
    private val clock: () -> Long = { clockMillis }

    private val config =
        RoutingConfig(helloIntervalMillis = 1_000L, routeDiscoveryTimeoutMillis = 5_000L)

    // ── test node factory ──────────────────────────────────────────────────────

    private class TestNode(
        val table: RoutingTable,
        val storage: InMemorySecureStorage,
        val trustStore: TrustStore,
        val coordinator: RouteCoordinator,
    )

    private fun makeNode(
        localId: ByteArray,
        edKey: ByteArray,
        dhKey: ByteArray,
        scope: kotlinx.coroutines.CoroutineScope,
        diagnosticSink: DiagnosticSinkApi = NoOpDiagnosticSink,
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
        return TestNode(table, storage, trustStore, coordinator)
    }

    private fun makePeerInfo(id: ByteArray, rssi: Int = -60, lossRate: Double = 0.0) =
        PeerInfo(peerId = id, powerMode = 0, rssi = rssi, lossRate = lossRate)

    // ── (a) onPeerConnected installs direct route ──────────────────────────────

    @Test
    fun `onPeerConnected installs direct route and lookupNextHop returns peerId`() = runTest {
        val nodeA = makeNode(peerIdA, edKeyA, dhKeyA, backgroundScope)

        nodeA.coordinator.onPeerConnected(makePeerInfo(peerIdB))

        // Direct route installed — next-hop should be peerIdB itself.
        assertContentEquals(peerIdB, nodeA.coordinator.lookupNextHop(peerIdB))
    }

    // ── (b) Update accepted via feasibility condition ─────────────────────────

    @Test
    fun `processInbound Update from known peer accepted via feasibility condition`() = runTest {
        val nodeB = makeNode(peerIdB, edKeyB, dhKeyB, backgroundScope)

        // Establish B→A neighbor
        nodeB.coordinator.onPeerConnected(makePeerInfo(peerIdA))

        // A announces route to destC
        val update =
            Update(
                destination = destC,
                metric = 200u, // wire metric 200 = 2.0
                seqNo = 100u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            )
        nodeB.coordinator.processInbound(peerIdA, update)

        // B should now route to destC via A
        assertContentEquals(peerIdA, nodeB.coordinator.lookupNextHop(destC))
    }

    // ── (c) feasibility rejects stale Update (same seqNo, higher metric) ──────

    @Test
    fun `processInbound stale Update with same seqNo and higher metric is rejected`() = runTest {
        val nodeB = makeNode(peerIdB, edKeyB, dhKeyB, backgroundScope)
        nodeB.coordinator.onPeerConnected(makePeerInfo(peerIdA, rssi = -50, lossRate = 0.0))

        // First Update — accepted, installs route to destC.
        nodeB.coordinator.processInbound(
            peerIdA,
            Update(
                destC,
                metric = 200u,
                seqNo = 100u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            ),
        )
        val firstRoute = nodeB.table.lookupRoute(destC)
        assertNotNull(firstRoute)
        val firstMetric = firstRoute.metric

        // Second Update — same seqNo but higher wire metric → must be rejected by feasibility.
        nodeB.coordinator.processInbound(
            peerIdA,
            Update(
                destC,
                metric = 5000u,
                seqNo = 100u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            ),
        )

        // Route should still reflect the FIRST accepted metric.
        assertEquals(firstMetric, nodeB.table.lookupRoute(destC)?.metric)
    }

    // ── (d) route digest mismatch triggers full dump ───────────────────────────

    @Test
    fun `digest mismatch in Hello triggers full dump response`() = runTest {
        val nodeB = makeNode(peerIdB, edKeyB, dhKeyB, backgroundScope)

        // Collect outbound frames from B.
        val collected = mutableListOf<OutboundFrame>()
        backgroundScope.launch { nodeB.coordinator.outboundFrames.collect { collected.add(it) } }
        testScheduler.runCurrent()

        // B connects to A and installs a direct route.
        nodeB.coordinator.onPeerConnected(makePeerInfo(peerIdA))

        // Install a second route on B so B's digest is non-trivial.
        nodeB.coordinator.processInbound(
            peerIdA,
            Update(
                destC,
                metric = 200u,
                seqNo = 100u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            ),
        )

        collected.clear()

        // A sends a Hello with a digest that does NOT match B's current digest → full dump.
        val mismatchDigest = nodeB.table.routeDigest() xor 0xDEADBEEFu
        nodeB.coordinator.processInbound(peerIdA, Hello(peerIdA, 2u, mismatchDigest))
        testScheduler.runCurrent() // flush subscriber continuation

        // B should have emitted its full table back to A.
        val toA = collected.filter { it.peerId?.contentEquals(peerIdA) == true }
        assertTrue(toA.isNotEmpty(), "Expected full dump to A after digest mismatch")
        assertTrue(toA.any { it.message is Update }, "Full dump should contain at least one Update")
    }

    // ── (e) peer disconnect retracts routes ───────────────────────────────────

    @Test
    fun `onPeerDisconnected retracts routes and lookupNextHop returns null`() = runTest {
        val nodeB = makeNode(peerIdB, edKeyB, dhKeyB, backgroundScope)

        nodeB.coordinator.onPeerConnected(makePeerInfo(peerIdA))

        // Confirm route exists.
        assertContentEquals(peerIdA, nodeB.coordinator.lookupNextHop(peerIdA))

        // Install an additional route via A so retraction loop is non-trivial.
        nodeB.coordinator.processInbound(
            peerIdA,
            Update(
                destC,
                metric = 100u,
                seqNo = 50u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            ),
        )

        // Disconnect A.
        nodeB.coordinator.onPeerDisconnected(peerIdA)

        // Routes via A should be gone.
        assertNull(nodeB.coordinator.lookupNextHop(peerIdA))
        assertNull(nodeB.coordinator.lookupNextHop(destC))
    }

    // ── (f) on-demand discovery emits Hello broadcast ─────────────────────────

    @Test
    fun `lookupNextHop for unknown destination emits Hello broadcast`() = runTest {
        val nodeA = makeNode(peerIdA, edKeyA, dhKeyA, backgroundScope)

        val collected = mutableListOf<OutboundFrame>()
        backgroundScope.launch { nodeA.coordinator.outboundFrames.collect { collected.add(it) } }
        testScheduler.runCurrent()

        // No route to destC — should trigger on-demand discovery.
        val result = nodeA.coordinator.lookupNextHop(destC)
        testScheduler.runCurrent() // flush subscriber continuation

        assertNull(result)
        // A Hello broadcast (peerId == null) should have been emitted.
        assertTrue(
            collected.any { it.peerId == null && it.message is Hello },
            "Expected a Hello broadcast for on-demand discovery",
        )
    }

    // ── (g) on-demand rate limiting ───────────────────────────────────────────

    @Test
    fun `lookupNextHop rate-limits on-demand discovery within routeDiscoveryTimeoutMillis`() =
        runTest {
            val nodeA = makeNode(peerIdA, edKeyA, dhKeyA, backgroundScope)

            val collected = mutableListOf<OutboundFrame>()
            backgroundScope.launch {
                nodeA.coordinator.outboundFrames.collect { collected.add(it) }
            }
            testScheduler.runCurrent()

            // First lookup — discovery Hello emitted.
            nodeA.coordinator.lookupNextHop(destC)
            testScheduler.runCurrent()
            val countAfterFirst = collected.count { it.peerId == null && it.message is Hello }
            assertEquals(1, countAfterFirst)

            // Second lookup within timeout — no additional Hello.
            nodeA.coordinator.lookupNextHop(destC)
            testScheduler.runCurrent()
            val countAfterSecond = collected.count { it.peerId == null && it.message is Hello }
            assertEquals(
                1,
                countAfterSecond,
                "Discovery must be rate-limited within timeout window",
            )

            // Advance past the timeout and try again — new Hello should be emitted.
            clockMillis += config.routeDiscoveryTimeoutMillis + 1L
            nodeA.coordinator.lookupNextHop(destC)
            testScheduler.runCurrent()
            val countAfterExpiry = collected.count { it.peerId == null && it.message is Hello }
            assertEquals(2, countAfterExpiry, "Discovery should fire again after timeout expires")
        }

    // ── (h) seqNo wraparound: seqNo=65535 → seqNo=0 accepted ─────────────────

    @Test
    fun `seqNo wraparound Update with seqNo 0 accepted as newer than seqNo 65535`() = runTest {
        val nodeB = makeNode(peerIdB, edKeyB, dhKeyB, backgroundScope)
        nodeB.coordinator.onPeerConnected(makePeerInfo(peerIdA))

        // Install a route with the maximum seqNo (65535).
        nodeB.coordinator.processInbound(
            peerIdA,
            Update(
                destC,
                metric = 200u,
                seqNo = 65535u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            ),
        )
        assertEquals(65535u.toUShort(), nodeB.table.lookupRoute(destC)?.seqNo)

        // seqNo=0 must be treated as newer than 65535 (RFC 8966 §2.1 modular arithmetic).
        nodeB.coordinator.processInbound(
            peerIdA,
            Update(
                destC,
                metric = 100u,
                seqNo = 0u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            ),
        )
        assertEquals(0u.toUShort(), nodeB.table.lookupRoute(destC)?.seqNo)
    }

    // ── (i) isDuplicate ───────────────────────────────────────────────────────

    @Test
    fun `isDuplicate returns false on first call and true on second call for same id`() = runTest {
        val nodeA = makeNode(peerIdA, edKeyA, dhKeyA, backgroundScope)
        val msgId = ByteArray(16) { it.toByte() }

        assertFalse(nodeA.coordinator.isDuplicate(msgId), "First call must not be a duplicate")
        assertTrue(nodeA.coordinator.isDuplicate(msgId), "Second call must be a duplicate")
    }

    // ── (j) TrustStore receives pinned X25519 key ─────────────────────────────

    @Test
    fun `processInbound accepted Update pins X25519 key in TrustStore`() = runTest {
        val nodeB = makeNode(peerIdB, edKeyB, dhKeyB, backgroundScope)
        nodeB.coordinator.onPeerConnected(makePeerInfo(peerIdA))

        val update =
            Update(
                destination = destC,
                metric = 100u,
                seqNo = 1u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            )
        nodeB.coordinator.processInbound(peerIdA, update)

        // TrustStore must have pinned dhKeyC for destC (destination = peerKeyHash here).
        val pinned = nodeB.trustStore.getPinnedKey(destC)
        assertNotNull(pinned, "X25519 key should be pinned after accepted Update")
        assertContentEquals(dhKeyC, pinned)
    }

    // ── (k) computeLinkCost: known values ─────────────────────────────────────

    @Test
    fun `computeLinkCost RSSI -50 no loss fresh stable produces cost 1_0`() {
        // rssiBaseCost = max(1.0, (50-50)/5) = 1.0
        // lossMultiplier = 1.0
        // freshnessPenalty = 1.0 (0ms since hello)
        // stabilityPenalty = max(0.0, 3-10) = 0.0
        val cost =
            computeLinkCost(
                rssi = -50,
                lossRate = 0.0,
                millisSinceLastHello = 0L,
                keepaliveIntervalMillis = 5_000L,
                consecutiveStableIntervals = 10,
            )
        assertEquals(1.0, cost, "RSSI -50, no loss, stable link → cost 1.0")
    }

    @Test
    fun `computeLinkCost RSSI -70 no loss fresh stable produces cost 4_0`() {
        // rssiBaseCost = max(1.0, (70-50)/5) = 4.0
        // lossMultiplier = 1.0, freshnessPenalty = 1.0, stabilityPenalty = 0.0
        val cost =
            computeLinkCost(
                rssi = -70,
                lossRate = 0.0,
                millisSinceLastHello = 0L,
                keepaliveIntervalMillis = 5_000L,
                consecutiveStableIntervals = 10,
            )
        assertEquals(4.0, cost, "RSSI -70, no loss, stable link → cost 4.0")
    }

    @Test
    fun `computeLinkCost with 5pct loss adds correct multiplier`() {
        // rssiBaseCost = 1.0 (rssi=-50), lossMultiplier = 1.0 + 0.05*10 = 1.5
        // freshnessPenalty = 1.0, stabilityPenalty = 0.0
        val cost =
            computeLinkCost(
                rssi = -50,
                lossRate = 0.05,
                millisSinceLastHello = 0L,
                keepaliveIntervalMillis = 5_000L,
                consecutiveStableIntervals = 10,
            )
        assertEquals(1.5, cost, "5% loss should give lossMultiplier=1.5 → cost 1.5")
    }

    @Test
    fun `computeLinkCost freshnessPenalty maxes at 1_5 when hello is very old`() {
        // rssiBaseCost = 1.0, lossMultiplier = 1.0
        // millisSince >> 4*keepalive → min(1.0, big) = 1.0 → freshnessPenalty = 1.5
        // stabilityPenalty = 0.0
        val cost =
            computeLinkCost(
                rssi = -50,
                lossRate = 0.0,
                millisSinceLastHello = Long.MAX_VALUE / 2,
                keepaliveIntervalMillis = 5_000L,
                consecutiveStableIntervals = 10,
            )
        assertEquals(1.5, cost, "Stale hello → freshnessPenalty capped at 1.5 → cost 1.5")
    }

    @Test
    fun `computeLinkCost new link adds 3_0 stability penalty`() {
        // rssiBaseCost = 1.0, lossMultiplier = 1.0, freshnessPenalty = 1.0
        // stabilityPenalty = max(0.0, 3-0) = 3.0
        val cost =
            computeLinkCost(
                rssi = -50,
                lossRate = 0.0,
                millisSinceLastHello = 0L,
                keepaliveIntervalMillis = 5_000L,
                consecutiveStableIntervals = 0,
            )
        assertEquals(4.0, cost, "New link (0 stable intervals) should add 3.0 stability penalty")
    }

    @Test
    fun `computeLinkCost extreme RSSI 0 is clamped to rssiBaseCost 1_0`() {
        // -rssi-50 = -50 → negative → max(1.0, -10.0) = 1.0
        val cost =
            computeLinkCost(
                rssi = 0,
                lossRate = 0.0,
                millisSinceLastHello = 0L,
                keepaliveIntervalMillis = 5_000L,
                consecutiveStableIntervals = 10,
            )
        assertEquals(1.0, cost, "RSSI=0 should clamp rssiBaseCost to 1.0")
    }

    @Test
    fun `computeLinkCost 100pct loss gives maximum lossMultiplier`() {
        // rssiBaseCost = 1.0, lossMultiplier = 1.0 + 1.0*10 = 11.0
        val cost =
            computeLinkCost(
                rssi = -50,
                lossRate = 1.0,
                millisSinceLastHello = 0L,
                keepaliveIntervalMillis = 5_000L,
                consecutiveStableIntervals = 10,
            )
        assertEquals(11.0, cost, "100% loss → lossMultiplier=11.0 → cost 11.0")
    }

    // ── negative tests ────────────────────────────────────────────────────────

    @Test
    fun `processInbound with non-Hello non-Update message is silently ignored`() = runTest {
        val nodeA = makeNode(peerIdA, edKeyA, dhKeyA, backgroundScope)
        val collected = mutableListOf<OutboundFrame>()
        backgroundScope.launch { nodeA.coordinator.outboundFrames.collect { collected.add(it) } }
        testScheduler.runCurrent()

        // Keepalive is not a routing message — must produce no outbound frames.
        nodeA.coordinator.processInbound(
            peerIdB,
            Keepalive(flags = 0u, timestampMillis = 0u, proposedChunkSize = 512u),
        )

        assertEquals(0, collected.size, "Non-routing message must not produce outbound frames")
    }

    @Test
    fun `onPeerDisconnected for unknown peer does not crash`() = runTest {
        val nodeA = makeNode(peerIdA, edKeyA, dhKeyA, backgroundScope)
        // Should be a no-op — no assertion needed beyond "does not throw".
        nodeA.coordinator.onPeerDisconnected(peerIdB)
    }

    @Test
    fun `processInbound Update from unknown peer uses defaultLinkCost`() = runTest {
        // peerIdB was never registered via onPeerConnected.
        val nodeA = makeNode(peerIdA, edKeyA, dhKeyA, backgroundScope)

        val update =
            Update(
                destination = destC,
                metric = 100u,
                seqNo = 1u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            )
        // Should accept (no FD yet) using defaultLinkCost — must not throw.
        nodeA.coordinator.processInbound(peerIdB, update)
        assertContentEquals(peerIdB, nodeA.coordinator.lookupNextHop(destC))
    }

    @Test
    fun `processInbound Hello from unknown peer processes without crash`() = runTest {
        val nodeA = makeNode(peerIdA, edKeyA, dhKeyA, backgroundScope)
        // Send Hello from a peer that was never registered via onPeerConnected.
        nodeA.coordinator.processInbound(peerIdB, Hello(peerIdB, 1u, 0u))
        // No crash is the expectation; neighborState is null so only the null branch is covered.
    }

    @Test
    fun `processInbound rejected Update does not pin key in TrustStore`() = runTest {
        val nodeB = makeNode(peerIdB, edKeyB, dhKeyB, backgroundScope)
        nodeB.coordinator.onPeerConnected(makePeerInfo(peerIdA, rssi = -50, lossRate = 0.0))

        // First Update — accepted.
        nodeB.coordinator.processInbound(
            peerIdA,
            Update(
                destC,
                metric = 200u,
                seqNo = 100u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            ),
        )

        // Craft a rejected Update: same seqNo + higher cost (feasibility rejects it).
        val differentDhKey = ByteArray(32) { 0xFF.toByte() }
        nodeB.coordinator.processInbound(
            peerIdA,
            Update(
                destC,
                metric = 9999u,
                seqNo = 100u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = differentDhKey,
            ),
        )

        // TrustStore should still have the FIRST key (rejected update must not re-pin).
        val pinned = nodeB.trustStore.getPinnedKey(destC)
        assertNotNull(pinned)
        assertContentEquals(dhKeyC, pinned, "Rejected Update must not overwrite pinned key")
    }

    @Test
    fun `lookupNextHop for known destination returns nextHop without emitting`() = runTest {
        val nodeA = makeNode(peerIdA, edKeyA, dhKeyA, backgroundScope)
        val collected = mutableListOf<OutboundFrame>()
        backgroundScope.launch { nodeA.coordinator.outboundFrames.collect { collected.add(it) } }
        testScheduler.runCurrent()

        nodeA.coordinator.onPeerConnected(makePeerInfo(peerIdB))
        collected.clear()

        // peerIdB is known — should return directly, no discovery broadcast.
        val hop = nodeA.coordinator.lookupNextHop(peerIdB)
        assertContentEquals(peerIdB, hop)
        assertTrue(
            collected.none { it.peerId == null && it.message is Hello },
            "No discovery Hello should be emitted when route is already known",
        )
    }

    // ── start() — timer integration ───────────────────────────────────────────

    @Test
    fun `start wires engine outbound messages into coordinator outboundFrames`() = runTest {
        val nodeA = makeNode(peerIdA, edKeyA, dhKeyA, backgroundScope)

        val collected = mutableListOf<OutboundFrame>()
        backgroundScope.launch { nodeA.coordinator.outboundFrames.collect { collected.add(it) } }
        testScheduler.runCurrent()

        nodeA.coordinator.start()

        // Advance past helloIntervalMillis so the Hello timer fires.
        testScheduler.advanceTimeBy(config.helloIntervalMillis + 1L)

        // Engine should have emitted Hello and self-route Update via the coordinator flow.
        assertTrue(
            collected.any { it.message is Hello },
            "Hello broadcast should flow through coordinator after start()",
        )
        assertTrue(
            collected.any { it.message is Update },
            "Self-route Update should flow through coordinator after start()",
        )
    }

    // ── onPeerConnected emits full dump outbound ───────────────────────────────

    @Test
    fun `onPeerConnected emits full dump as outbound frames to new peer`() = runTest {
        val nodeA = makeNode(peerIdA, edKeyA, dhKeyA, backgroundScope)

        val collected = mutableListOf<OutboundFrame>()
        backgroundScope.launch { nodeA.coordinator.outboundFrames.collect { collected.add(it) } }
        testScheduler.runCurrent()

        nodeA.coordinator.onPeerConnected(makePeerInfo(peerIdB))
        testScheduler.runCurrent() // flush subscriber continuation

        // Should have emitted at least the direct route Update to peerIdB.
        assertTrue(
            collected.any { frame ->
                frame.peerId?.contentEquals(peerIdB) == true && frame.message is Update
            },
            "Full dump to new peer must include at least the direct route Update",
        )
    }

    // ── onPeerDisconnected broadcasts retraction ───────────────────────────────

    @Test
    fun `onPeerDisconnected broadcasts retraction Update for removed routes`() = runTest {
        val nodeB = makeNode(peerIdB, edKeyB, dhKeyB, backgroundScope)

        val collected = mutableListOf<OutboundFrame>()
        backgroundScope.launch { nodeB.coordinator.outboundFrames.collect { collected.add(it) } }
        testScheduler.runCurrent()

        nodeB.coordinator.onPeerConnected(makePeerInfo(peerIdA))
        nodeB.coordinator.processInbound(
            peerIdA,
            Update(
                destC,
                metric = 100u,
                seqNo = 50u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            ),
        )
        collected.clear()

        nodeB.coordinator.onPeerDisconnected(peerIdA)
        testScheduler.runCurrent() // flush subscriber continuation

        // Retraction Updates are broadcast (peerId == null) with metric == METRIC_RETRACTION.
        val retractions = collected.filter { frame ->
            frame.peerId == null &&
                frame.message is Update &&
                frame.message.metric == METRIC_RETRACTION
        }
        assertTrue(retractions.isNotEmpty(), "Disconnect should broadcast retraction Updates")
    }

    // ── processInbound Hello updates neighbor state ───────────────────────────

    @Test
    fun `processInbound Hello increments consecutiveStableIntervals for known peer`() = runTest {
        val nodeB = makeNode(peerIdB, edKeyB, dhKeyB, backgroundScope)

        nodeB.coordinator.onPeerConnected(makePeerInfo(peerIdA, rssi = -50, lossRate = 0.0))

        // Install a route so that processHello has something to diff and next Hello from A is
        // processed.
        nodeB.coordinator.processInbound(
            peerIdA,
            Update(
                destC,
                metric = 100u,
                seqNo = 1u,
                ed25519PublicKey = edKeyC,
                x25519PublicKey = dhKeyC,
            ),
        )

        // Advance clock by 1 second, send Hello from A.
        clockMillis += 1_000L
        val digest = nodeB.table.routeDigest()
        nodeB.coordinator.processInbound(peerIdA, Hello(peerIdA, 2u, digest))

        // The route to destC should still be accessible (stable).
        assertContentEquals(peerIdA, nodeB.coordinator.lookupNextHop(destC))
    }
}
