package ch.trancee.meshlink.integration

import ch.trancee.meshlink.api.DiagnosticCode
import ch.trancee.meshlink.api.DiagnosticEvent
import ch.trancee.meshlink.engine.PowerTierCodec
import ch.trancee.meshlink.power.PowerTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Integration test proving the three-state peer lifecycle FSM:
 * ```
 * Connected → Disconnected → Gone (evicted)
 * ```
 *
 * Topology: A ↔ B ↔ C (linear mesh). Uses [MeshTestHarness] with full Noise XX handshakes, virtual
 * time, and MeshStateManager sweep orchestration.
 *
 * In this topology, A↔B and B↔C are direct BLE links (full Noise XX). A and C are NOT directly
 * connected — they reach each other via multi-hop routing only. PresenceTracker tracks direct
 * connections, so A sees 1 connected peer (B) and B sees 2 (A and C).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerLifecycleIntegrationTest {

    @Test
    fun `peer lifecycle Connected to Disconnected to Gone with sweep eviction and reconnect`() =
        runTest {
            val harness =
                MeshTestHarness.builder()
                    .node("A")
                    .node("B")
                    .node("C")
                    .link("A", "B")
                    .link("B", "C")
                    .build(testScheduler, backgroundScope)

            harness.awaitConvergence()

            // ── Phase 1: Direct peers connected ───────────────────────────────
            // A↔B (direct), B↔C (direct); A and C only have multi-hop routes.
            assertEquals(1, harness["A"].engine.connectedPeerCount(), "A sees 1 direct peer (B)")
            assertEquals(2, harness["B"].engine.connectedPeerCount(), "B sees 2 direct peers")
            assertEquals(1, harness["C"].engine.connectedPeerCount(), "C sees 1 direct peer (B)")

            // A has a multi-hop route to C through B.
            val routeToCAtA =
                harness["A"].engine.routingTable.lookupRoute(harness["C"].identity.keyHash)
            assertNotNull(routeToCAtA, "A must have a multi-hop route to C via B")

            // ── Collect diagnostic events from A for later assertions ─────────
            val diagnosticEventsA = mutableListOf<DiagnosticEvent>()
            backgroundScope.launch {
                harness["A"].diagnosticSink.events.collect { diagnosticEventsA.add(it) }
            }
            testScheduler.runCurrent()

            // ── Phase 2: Disconnect A↔B link ──────────────────────────────────
            // disconnect() severs the VirtualMeshTransport link AND emits peerLostEvents
            // on both sides — matching real BLE disconnect behavior.
            val bKey = harness["B"].identity.keyHash
            harness["A"].transport.disconnect(bKey)
            testScheduler.runCurrent()
            repeat(20) { testScheduler.runCurrent() }

            // A's connectedPeerCount drops to 0 (B was A's only direct peer).
            assertEquals(
                0,
                harness["A"].engine.connectedPeerCount(),
                "A sees 0 connected peers after B disconnects",
            )

            // Routes are still kept during grace period — the PresenceTracker marks B as
            // Disconnected but MeshStateManager has not swept yet.
            val routeToBAtA = harness["A"].engine.routingTable.lookupRoute(bKey)
            assertNotNull(routeToBAtA, "A must still have a route to B during grace period")

            // ── Phase 3: Advance through 3 sweep cycles to trigger Gone eviction ──
            // The sweep timer fires every 30s from engine start. B is now Disconnected.
            // Sweep lifecycle: sweepCount=0 → 1 (first sweep), 1 → 2 (second), ≥2 → evict
            // (third). We advance 4×30s to guarantee 3+ sweeps run while B is Disconnected,
            // regardless of timer phase alignment.
            testScheduler.advanceTimeBy(120_000L)
            testScheduler.runCurrent()
            repeat(20) { testScheduler.runCurrent() }

            // sweepCount was 2 (≥ 2) → evict. B is now Gone.
            val routeToBAfterGone = harness["A"].engine.routingTable.lookupRoute(bKey)
            assertNull(routeToBAfterGone, "A must have retracted route to B after Gone eviction")

            // Verify PEER_PRESENCE_EVICTED diagnostic was emitted at A.
            val evictionEvents = diagnosticEventsA.filter {
                it.code == DiagnosticCode.PEER_PRESENCE_EVICTED
            }
            assertTrue(
                evictionEvents.isNotEmpty(),
                "A must emit PEER_PRESENCE_EVICTED diagnostic for B; events=$diagnosticEventsA",
            )

            // ── Phase 4: Reconnect B to A ─────────────────────────────────────
            val adData = PowerTierCodec.encode(PowerTier.PERFORMANCE)

            // Re-establish the VirtualMeshTransport link (disconnect() severed it).
            harness["A"]
                .transport
                .linkTo(
                    harness["B"].transport,
                    ch.trancee.meshlink.transport.VirtualLink(rssi = -50),
                )

            // A discovers B → triggers full Noise XX handshake.
            harness["A"].transport.simulateDiscovery(bKey, adData, -50)
            repeat(20) { testScheduler.runCurrent() }

            // B discovers A → completes handshake from B's side.
            harness["B"].transport.simulateDiscovery(harness["A"].identity.keyHash, adData, -50)
            repeat(20) { testScheduler.runCurrent() }

            // Second-round discovery to propagate multi-hop routes.
            harness["B"].transport.simulateDiscovery(harness["A"].identity.keyHash, adData, -50)
            repeat(10) { testScheduler.runCurrent() }

            // Flush routing updates.
            repeat(50) { testScheduler.runCurrent() }

            // ── Phase 5: Verify B is Connected again with fresh routes ────────
            assertEquals(
                1,
                harness["A"].engine.connectedPeerCount(),
                "A sees 1 connected peer (B) after reconnect",
            )

            val routeToBReconnected = harness["A"].engine.routingTable.lookupRoute(bKey)
            assertNotNull(routeToBReconnected, "A must have a fresh route to B after reconnect")

            // seqNo must be > 0 (incrementing per-destination counter).
            // After Gone, removeSeqNo is called, so reconnect starts at seqNo=1.
            assertTrue(
                routeToBReconnected.seqNo > 0u,
                "Route to B must have seqNo > 0 after reconnect; got ${routeToBReconnected.seqNo}",
            )

            harness.stopAll()
        }
}
