package ch.trancee.meshlink.integration

import ch.trancee.meshlink.api.DiagnosticSinkApi
import ch.trancee.meshlink.api.NoOpDiagnosticSink
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.engine.MeshEngineConfig
import ch.trancee.meshlink.engine.PowerTierCodec
import ch.trancee.meshlink.messaging.MessagingConfig
import ch.trancee.meshlink.power.PowerConfig
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.power.StubBatteryMonitor
import ch.trancee.meshlink.routing.RoutingConfig
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.transfer.ChunkSizePolicy
import ch.trancee.meshlink.transfer.TransferConfig
import ch.trancee.meshlink.transport.VirtualLink
import ch.trancee.meshlink.transport.VirtualMeshTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler

// ── MeshNode: per-node state exposed for test assertions ─────────────────────

/** Holds every test-visible artifact for a single mesh node. Created by [MeshTestHarness.build]. */
internal data class MeshNode(
    val name: String,
    val identity: Identity,
    val engine: MeshEngine,
    val transport: VirtualMeshTransport,
    val diagnosticSink: DiagnosticSinkApi,
    val batteryMonitor: StubBatteryMonitor,
    val storage: SecureStorage,
)

// ── MeshTestHarness ──────────────────────────────────────────────────────────

/**
 * N-node integration test harness with full Noise XX handshake support.
 *
 * Built via the companion [Builder]:
 * ```
 * val harness = MeshTestHarness.builder()
 *     .node("A")
 *     .node("B")
 *     .link("A", "B")
 *     .build(testScheduler, backgroundScope)
 * ```
 *
 * After [awaitConvergence], every linked pair has completed a full 3-step Noise XX exchange and
 * propagated routes so that multi-hop sends can succeed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MeshTestHarness
private constructor(
    private val nodes: Map<String, MeshNode>,
    private val links: List<LinkSpec>,
    private val testScheduler: TestCoroutineScheduler,
) {

    // ── Public accessors ──────────────────────────────────────────────────────

    /** Look up a node by its builder name. */
    operator fun get(name: String): MeshNode =
        nodes[name] ?: throw IllegalArgumentException("Unknown node: $name (known: ${nodes.keys})")

    /**
     * Stop all engines. Must be called at the end of each test to prevent infinite timer advance.
     */
    suspend fun stopAll() {
        nodes.values.forEach { it.engine.stop() }
    }

    // ── Convergence helper ────────────────────────────────────────────────────

    /**
     * Deterministically drives the virtual-time scheduler through peer discovery, full 3-step Noise
     * XX handshakes, and route propagation for every configured link.
     *
     * **Must be called after [build]** to bring the mesh into a connected, routable state.
     *
     * Uses repeated [TestCoroutineScheduler.runCurrent] cycles (never `advanceUntilIdle`) to avoid
     * the PowerManager infinite-poll trap (MEM184).
     */
    suspend fun awaitConvergence() {
        val adData = PowerTierCodec.encode(PowerTier.PERFORMANCE)

        // ── Phase 1: bidirectional discovery for each link ────────────────────
        // Each direction triggers onAdvertisementSeen → NoiseHandshakeManager initiates full
        // Noise XX (keys are NOT pre-pinned). The handshake messages flow through
        // VirtualMeshTransport.incomingData and complete after 3 steps.
        for (link in links) {
            val nodeA = this[link.a]
            val nodeB = this[link.b]

            // A discovers B
            nodeA.transport.simulateDiscovery(nodeB.identity.keyHash, adData, link.rssi)
            repeat(20) { testScheduler.runCurrent() }

            // B discovers A
            nodeB.transport.simulateDiscovery(nodeA.identity.keyHash, adData, link.rssi)
            repeat(20) { testScheduler.runCurrent() }
        }

        // ── Phase 2: second-round discovery to trigger full route dumps ───────
        // After all direct links are established, each node's routing table contains direct
        // routes. Re-discovering triggers onPeerConnected again, which sends ALL routes in
        // the routing table to the peer — so multi-hop routes propagate.
        for (link in links) {
            val nodeA = this[link.a]
            val nodeB = this[link.b]

            nodeB.transport.simulateDiscovery(nodeA.identity.keyHash, adData, link.rssi)
            repeat(10) { testScheduler.runCurrent() }

            nodeA.transport.simulateDiscovery(nodeB.identity.keyHash, adData, link.rssi)
            repeat(10) { testScheduler.runCurrent() }
        }

        // Flush all pending outbound frames (routing Updates)
        repeat(100) { testScheduler.runCurrent() }

        // ── Phase 3: advance virtual time by 1ms ─────────────────────────────
        // ReplayGuard rejects counter == 0 (RFC 9147 §6.5 R4); without this all unicast
        // messages are silently dropped even after correct assembly.
        testScheduler.advanceTimeBy(1L)
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    internal data class LinkSpec(val a: String, val b: String, val rssi: Int)

    class Builder {
        private val nodeNames = mutableListOf<String>()
        private val linkSpecs = mutableListOf<LinkSpec>()

        /** Add a node with the given human-readable name. Identity is auto-generated. */
        fun node(name: String): Builder {
            require(name !in nodeNames) { "Duplicate node name: $name" }
            nodeNames.add(name)
            return this
        }

        /** Create a bidirectional VirtualMeshTransport link between two named nodes. */
        fun link(a: String, b: String, rssi: Int = -50): Builder {
            linkSpecs.add(LinkSpec(a, b, rssi))
            return this
        }

        /**
         * Build the harness: creates identities, transports, engines, starts them, and returns a
         * [MeshTestHarness] ready for [awaitConvergence].
         *
         * Uses [integrationConfig] with long poll intervals to avoid OOM (MEM129).
         */
        suspend fun build(
            testScheduler: TestCoroutineScheduler,
            backgroundScope: CoroutineScope,
        ): MeshTestHarness {
            val crypto: CryptoProvider = createCryptoProvider()
            val clock: () -> Long = { testScheduler.currentTime }

            // Validate link references
            for (link in linkSpecs) {
                require(link.a in nodeNames) { "Link references unknown node: ${link.a}" }
                require(link.b in nodeNames) { "Link references unknown node: ${link.b}" }
            }

            // ── Create per-node artifacts ─────────────────────────────────────
            val builtNodes = mutableMapOf<String, MeshNode>()
            for (name in nodeNames) {
                val storage = InMemorySecureStorage()
                val identity = Identity.loadOrGenerate(crypto, storage)
                val transport = VirtualMeshTransport(identity.keyHash, testScheduler)
                val batteryMonitor = StubBatteryMonitor(level = 1.0f)
                val diagnosticSink: DiagnosticSinkApi = NoOpDiagnosticSink

                val engine =
                    MeshEngine.create(
                        identity = identity,
                        cryptoProvider = crypto,
                        transport = transport,
                        storage = storage,
                        batteryMonitor = batteryMonitor,
                        scope = backgroundScope,
                        clock = clock,
                        config = integrationConfig,
                    )

                builtNodes[name] =
                    MeshNode(
                        name = name,
                        identity = identity,
                        engine = engine,
                        transport = transport,
                        diagnosticSink = diagnosticSink,
                        batteryMonitor = batteryMonitor,
                        storage = storage,
                    )
            }

            // ── Wire VirtualMeshTransport links ───────────────────────────────
            for (link in linkSpecs) {
                builtNodes[link.a]!!
                    .transport
                    .linkTo(builtNodes[link.b]!!.transport, VirtualLink(rssi = link.rssi))
            }

            // ── Start all engines ─────────────────────────────────────────────
            for (node in builtNodes.values) {
                node.engine.start()
            }
            testScheduler.runCurrent() // flush subscription-launch coroutines

            return MeshTestHarness(builtNodes, linkSpecs, testScheduler)
        }
    }

    companion object {
        /** Entry point for the fluent builder API. */
        fun builder(): Builder = Builder()

        /**
         * Integration config: long poll intervals to avoid timer-explosion OOM (MEM129). Battery
         * poll at 300s prevents ~150 timer firings during typical 30s virtual-time advances.
         */
        internal val integrationConfig =
            MeshEngineConfig(
                routing =
                    RoutingConfig(helloIntervalMillis = 30_000L, routeExpiryMillis = 300_000L),
                messaging =
                    MessagingConfig(
                        appIdHash = ByteArray(16) { it.toByte() },
                        requireBroadcastSignatures = true,
                    ),
                power =
                    PowerConfig(
                        batteryPollIntervalMillis = 300_000L,
                        bootstrapDurationMillis = 100L,
                        hysteresisDelayMillis = 100L,
                        performanceThreshold = 0.80f,
                        powerSaverThreshold = 0.30f,
                        performanceMaxConnections = 6,
                        balancedMaxConnections = 4,
                        powerSaverMaxConnections = 2,
                    ),
                transfer = TransferConfig(inactivityBaseTimeoutMillis = 30_000L),
                chunkSize = ChunkSizePolicy.fixed(256),
            )
    }
}
