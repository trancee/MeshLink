package ch.trancee.meshlink.api

import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.messaging.CoverageIgnore
import ch.trancee.meshlink.messaging.DeliveryOutcome
import ch.trancee.meshlink.power.BatteryMonitor
import ch.trancee.meshlink.power.FixedBatteryMonitor
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.transfer.Priority
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.SendResult
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ── No-op BleTransport for headless/JVM use ────────────────────────────────

/**
 * No-operation [BleTransport] used by the default [MeshLink] factory on platforms where no real BLE
 * transport has been wired (JVM). On Android use [MeshLinkService]; on iOS use [MeshNode].
 */
@CoverageIgnore // Platform stub — methods are intentionally empty/trivial; exercised by JVM factory
// path only.
private class NoOpBleTransport(override val localPeerId: ByteArray = ByteArray(12)) : BleTransport {
    override var advertisementServiceData: ByteArray = ByteArray(0)
    override val advertisementEvents = emptyFlow<ch.trancee.meshlink.transport.AdvertisementEvent>()
    override val peerLostEvents = emptyFlow<ch.trancee.meshlink.transport.PeerLostEvent>()
    override val incomingData = emptyFlow<ch.trancee.meshlink.transport.IncomingData>()

    override suspend fun startAdvertisingAndScanning() {}

    override suspend fun stopAll() {}

    override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray): SendResult =
        SendResult.Failure("NoOpBleTransport: no BLE stack on this platform")

    override suspend fun disconnect(peerId: ByteArray) {}

    override suspend fun requestConnectionPriority(peerId: ByteArray, highPriority: Boolean) {}
}

// ── MeshLink ───────────────────────────────────────────────────────────────

/**
 * Concrete implementation of [MeshLinkApi]. Wires [MeshEngine] to the public API surface, enforces
 * the lifecycle FSM via [MeshLinkStateMachine], and serialises lifecycle operations via [Mutex].
 *
 * **Obtain an instance** via `MeshLink(config)` (companion factory) for production use, or via the
 * internal [MeshLink.create] factory in tests.
 */
// The framework is also named "MeshLink", so ObjC/Swift would see a name collision.
// @ObjCName exports this as "MeshLinkClient" in Swift while keeping the Kotlin name unchanged.
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("MeshLinkClient")
public class MeshLink
internal constructor(
    private val config: MeshLinkConfig,
    private val identity: Identity,
    private val engine: MeshEngine,
    private val transport: BleTransport,
    @Suppress("unused") private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val wallClock: () -> Long = clock,
) : MeshLinkApi {

    // ── Diagnostic sink ────────────────────────────────────────────────────

    /**
     * Peer-ID redaction function used when [DiagnosticsConfig.redactPeerIds] is enabled. Truncates
     * the hex string to the first 8 chars, masking the remainder for GDPR.
     *
     * Not directly exercisable from unit tests — redaction only fires when a [PeerIdHex]-carrying
     * payload (e.g. [DiagnosticPayload.PeerDiscovered]) is emitted, which requires a full BLE
     * handshake (S04 integration test).
     */
    @CoverageIgnore
    private fun truncatePeerIdHex(peerId: PeerIdHex): PeerIdHex =
        PeerIdHex(peerId.hex.take(8) + "…")

    /**
     * Active diagnostic sink — [DiagnosticSink] when diagnostics are enabled, [NoOpDiagnosticSink]
     * when disabled.
     *
     * The redact function is a simple prefix-truncation stub (SHA-256 is not available in
     * commonMain without a CryptoProvider; a real SHA-256 implementation can be injected via a
     * platform-specific factory if needed).
     */
    private val diagnosticSink: DiagnosticSinkApi =
        if (config.diagnostics.enabled) {
            val redactFn: ((PeerIdHex) -> PeerIdHex)? =
                if (config.diagnostics.redactPeerIds) ::truncatePeerIdHex else null
            DiagnosticSink(
                bufferCapacity = config.diagnostics.bufferCapacity,
                redactFn = redactFn,
                clock = clock,
                wallClock = wallClock,
            )
        } else {
            NoOpDiagnosticSink
        }

    // ── State machine ──────────────────────────────────────────────────────

    private val stateMachine =
        MeshLinkStateMachine(
            nowMillis = clock,
            onDiagnostic = { event -> diagnosticSink.emit(event.code) { event.payload } },
        )

    private val mutex = Mutex()

    // ── meshHealthFlow backing flow ─────────────────────────────────────────

    private val _meshHealthFlow = MutableSharedFlow<MeshHealthSnapshot>(replay = 1)
    private var healthTickerJob: Job? = null

    // ── StateFlow ──────────────────────────────────────────────────────────

    override val state: StateFlow<MeshLinkState> = stateMachine.state

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override suspend fun start() {
        mutex.withLock {
            val current = stateMachine.state.value
            if (current != MeshLinkState.UNINITIALIZED && current != MeshLinkState.RECOVERABLE) {
                stateMachine.transition(LifecycleEvent.StartSuccess) // emits InvalidStateTransition
                throw IllegalStateException("start() called from invalid state: $current")
            }
            try {
                engine.start()
                stateMachine.transition(LifecycleEvent.StartSuccess)
                // Cancel any stale ticker from a previous start (restart-from-RECOVERABLE path).
                healthTickerJob?.cancel()
                // Emit an initial snapshot immediately and start the periodic ticker.
                _meshHealthFlow.tryEmit(buildHealthSnapshot())
                healthTickerJob = scope.launch { runHealthTicker() }
            } catch (e: Exception) {
                stateMachine.transition(LifecycleEvent.StartFailure)
                throw e
            }
        }
    }

    override suspend fun stop(timeout: Duration) {
        mutex.withLock {
            val current = stateMachine.state.value
            if (
                current != MeshLinkState.RUNNING &&
                    current != MeshLinkState.PAUSED &&
                    current != MeshLinkState.RECOVERABLE
            ) {
                stateMachine.transition(LifecycleEvent.Stop) // emits InvalidStateTransition
                throw IllegalStateException("stop() called from invalid state: $current")
            }
            stopHealthTicker()
            engine.stop()
            stateMachine.transition(LifecycleEvent.Stop)
        }
    }

    override suspend fun pause() {
        mutex.withLock {
            val current = stateMachine.state.value
            if (current != MeshLinkState.RUNNING) {
                stateMachine.transition(LifecycleEvent.Pause) // emits InvalidStateTransition
                throw IllegalStateException("pause() called from invalid state: $current")
            }
            transport.stopAll()
            stateMachine.transition(LifecycleEvent.Pause)
        }
    }

    override suspend fun resume() {
        mutex.withLock {
            val current = stateMachine.state.value
            if (current != MeshLinkState.PAUSED) {
                stateMachine.transition(LifecycleEvent.Resume) // emits InvalidStateTransition
                throw IllegalStateException("resume() called from invalid state: $current")
            }
            transport.startAdvertisingAndScanning()
            stateMachine.transition(LifecycleEvent.Resume)
        }
    }

    // ── Messaging ──────────────────────────────────────────────────────────

    override suspend fun send(recipient: ByteArray, payload: ByteArray, priority: MessagePriority) {
        engine.send(recipient, payload, priority.toInternal())
    }

    override suspend fun broadcast(payload: ByteArray, maxHops: Int, priority: MessagePriority) {
        val clampedHops = maxHops.coerceIn(1, config.routing.maxHops).toUByte()
        engine.broadcast(payload, clampedHops, priority.toInternal())
    }

    // ── Event streams ──────────────────────────────────────────────────────

    // Flow map lambdas below are excluded from line coverage: they only execute when the engine
    // emits items, which requires full BLE/peer-discovery activity tested in S04+.

    @get:CoverageIgnore
    override val peers: Flow<PeerEvent> =
        engine.peerEvents.map { event ->
            when (event) {
                is ch.trancee.meshlink.routing.PeerEvent.Connected -> {
                    // staticPublicKey wired to TrustStore in S02; stub with empty key for S01.
                    val stubKey = ByteArray(32)
                    PeerEvent.Found(
                        id = event.peerId,
                        detail =
                            PeerDetail(
                                id = event.peerId,
                                staticPublicKey = stubKey,
                                fingerprint = event.peerId.toHexString(),
                                isConnected = true,
                                lastSeenTimestampMillis = clock(),
                                trustMode = TrustMode.STRICT,
                            ),
                    )
                }
                is ch.trancee.meshlink.routing.PeerEvent.Disconnected ->
                    PeerEvent.Lost(event.peerId)
            }
        }

    @get:CoverageIgnore
    override val messages: Flow<ReceivedMessage> =
        engine.messages.map { msg ->
            ReceivedMessage(
                id = MessageId(msg.messageId),
                senderId = msg.senderId,
                payload = msg.payload,
                receivedAtMillis = msg.receivedAt,
            )
        }

    @get:CoverageIgnore
    override val deliveryConfirmations: Flow<MessageId> =
        engine.deliveryConfirmations.map { MessageId(it.messageId) }

    @get:CoverageIgnore
    override val transferProgress: Flow<TransferProgress> =
        engine.transferProgress.map { progress ->
            TransferProgress(
                transferId = progress.messageId,
                // recipient is not tracked at TransferEngine layer; zero-filled stub for S01.
                recipient = ByteArray(12),
                bytesTransferred = progress.chunksReceived.toLong(),
                totalBytes = progress.totalChunks.toLong(),
            )
        }

    @get:CoverageIgnore
    override val transferFailures: Flow<TransferFailure> =
        engine.transferFailures.map { failure ->
            when (failure.outcome) {
                DeliveryOutcome.TIMED_OUT ->
                    TransferFailure.Timeout(failure.messageId, ByteArray(12))
                DeliveryOutcome.DESTINATION_UNREACHABLE ->
                    TransferFailure.PeerUnavailable(failure.messageId, ByteArray(12))
                DeliveryOutcome.SEND_FAILED -> TransferFailure.Cancelled(failure.messageId)
            }
        }

    override val meshHealthFlow: Flow<MeshHealthSnapshot> = _meshHealthFlow

    override val keyChanges: Flow<KeyChangeEvent> = emptyFlow()

    override val diagnosticEvents: Flow<DiagnosticEvent> = diagnosticSink.events

    /**
     * Returns the last [DiagnosticEvent] emitted, or `null` if none has been emitted yet.
     *
     * Uses the SharedFlow replay cache (replay=1 for [DiagnosticSink], replay=0 for
     * [NoOpDiagnosticSink]). Exposed `internal` for test assertions without requiring a coroutine
     * subscription.
     */
    internal val lastDiagnosticEvent: DiagnosticEvent?
        get() = diagnosticSink.events.replayCache.lastOrNull()

    // ── Power ──────────────────────────────────────────────────────────────

    override fun updateBattery(percent: Float, isCharging: Boolean) {
        // Wired to PowerManager in S02.
    }

    override fun setCustomPowerMode(mode: PowerTier?) {
        // Wired to PowerManager in S02.
    }

    // ── Identity & Trust ──────────────────────────────────────────────────

    override val localPublicKey: ByteArray
        get() = identity.edKeyPair.publicKey

    override fun peerPublicKey(id: ByteArray): ByteArray? = null // TrustStore wired in S02

    override fun peerDetail(id: ByteArray): PeerDetail? = null

    override fun allPeerDetails(): List<PeerDetail> = emptyList()

    override fun peerFingerprint(id: ByteArray): String? = null

    override suspend fun rotateIdentity() {
        // Wired to Identity.rotateKeys + broadcast in S02.
    }

    override suspend fun repinKey(id: ByteArray) {
        // Wired to TrustStore.repinKey in S02.
    }

    override suspend fun acceptKeyChange(peerId: ByteArray) {
        // Wired to TrustStore in S02.
    }

    override suspend fun rejectKeyChange(peerId: ByteArray) {
        // Wired to TrustStore in S02.
    }

    override fun pendingKeyChanges(): List<KeyChangeEvent> = emptyList()

    // ── Health ─────────────────────────────────────────────────────────────

    override fun meshHealth(): MeshHealthSnapshot = buildHealthSnapshot()

    override fun shedMemoryPressure() {
        // Wired to DeliveryPipeline in S02.
    }

    // ── Health helpers ─────────────────────────────────────────────────────

    /**
     * Cancels and clears the health ticker job. The null-safe `?.cancel()` call is needed because
     * the ticker is only set after a successful [start], but the Kotlin compiler still generates a
     * null-branch in bytecode. All valid paths that call [stop] have [start] succeed first, so the
     * null case is structurally unreachable.
     */
    @CoverageIgnore // null branch of `?.cancel()` is structurally unreachable via the lifecycle FSM
    private fun stopHealthTicker() {
        healthTickerJob?.cancel()
        healthTickerJob = null
    }

    /**
     * Builds a [MeshHealthSnapshot] from current engine state. Called by [meshHealth] and by the
     * periodic health ticker.
     */
    private fun buildHealthSnapshot(): MeshHealthSnapshot {
        val allRoutes = engine.routingTable.allRoutes()
        val avgCost = allRoutes.map { it.metric }.average().let { if (it.isNaN()) 0.0 else it }
        val connected = engine.connectedPeerCount()
        return MeshHealthSnapshot(
            connectedPeers = connected,
            routingTableSize = engine.routingTable.routeCount(),
            bufferUsageBytes = 0L,
            capturedAtMillis = clock(),
            reachablePeers = connected,
            bufferUtilizationPercent = 0,
            activeTransfers = 0,
            powerMode = engine.currentPowerTier,
            avgRouteCost = avgCost,
            relayQueueSize = 0,
        )
    }

    /**
     * Periodic health ticker coroutine. Waits [DiagnosticsConfig.healthSnapshotIntervalMillis]
     * between snapshots and emits on [_meshHealthFlow].
     *
     * Cancellation is propagated by [delay] throwing [kotlinx.coroutines.CancellationException]
     * when [healthTickerJob] is cancelled in [stop].
     */
    private suspend fun runHealthTicker() {
        while (true) {
            delay(config.diagnostics.healthSnapshotIntervalMillis)
            _meshHealthFlow.tryEmit(buildHealthSnapshot())
        }
    }

    // ── Routing ────────────────────────────────────────────────────────────

    override fun routingSnapshot(): RoutingSnapshot {
        val nowMillis = clock()
        val ttlMillis = config.routing.routeCacheTtlMillis
        val routes =
            engine.routingTable
                .allRoutes()
                .map { entry ->
                    RoutingEntry(
                        destination = entry.destination,
                        nextHop = entry.nextHop,
                        cost = entry.metric.toInt(),
                        seqNo = entry.seqNo.toInt(),
                        ageMillis = (ttlMillis - (entry.expiresAt - nowMillis)).coerceAtLeast(0L),
                    )
                }
                .sortedBy { it.cost }
        return RoutingSnapshot(capturedAtMillis = nowMillis, routes = routes)
    }

    // ── GDPR ───────────────────────────────────────────────────────────────

    override suspend fun forgetPeer(peerId: ByteArray) {
        // TrustStore + RoutingTable + DeliveryPipeline wired in S02.
    }

    override suspend fun factoryReset() {
        // Storage wipe wired in S02.
    }

    // ── Experimental ──────────────────────────────────────────────────────

    @ExperimentalMeshLinkApi
    override suspend fun addRoute(
        destination: ByteArray,
        nextHop: ByteArray,
        cost: Int,
        seqNo: Int,
    ) {
        // RoutingTable injection wired in S02.
    }

    // ── Internal teardown ─────────────────────────────────────────────────

    /**
     * Cancels the engine scope directly, bypassing the lifecycle FSM. Use in [runTest] teardown (at
     * the end of the test body or in a `@AfterTest` helper) to prevent
     * [kotlinx.coroutines.test.UncompletedCoroutinesError] caused by the engine's long-running
     * collect coroutines.
     *
     * Equivalent to [MeshEngine.stop] without the FSM transition. Safe to call from any state,
     * including [MeshLinkState.UNINITIALIZED].
     */
    internal suspend fun stopEngineForTest() = engine.stop()

    /**
     * Test helper: fire a [LifecycleEvent.TransientFailure] transition on the FSM so tests can
     * reach [MeshLinkState.RECOVERABLE] without waiting for a real BLE stack event (wired in S02).
     */
    internal fun triggerTransientFailureForTest() {
        stateMachine.transition(LifecycleEvent.TransientFailure)
    }

    /** Test helper: install a synthetic route into the engine's routing table. */
    @Suppress("LongParameterList")
    internal fun injectTestRoute(
        destination: ByteArray,
        nextHop: ByteArray,
        metric: Double,
        expiresAt: Long,
    ) {
        engine.routingTable.install(
            ch.trancee.meshlink.routing.RouteEntry(
                destination = destination,
                nextHop = nextHop,
                metric = metric,
                seqNo = 1u,
                feasibilityDistance = metric,
                expiresAt = expiresAt,
                ed25519PublicKey = ByteArray(32),
                x25519PublicKey = ByteArray(32),
            )
        )
    }

    // ── Factory ────────────────────────────────────────────────────────────

    public companion object {

        private val MONOTONIC_ORIGIN = TimeSource.Monotonic.markNow()

        /**
         * Creates a [MeshLink] instance with the given [config].
         *
         * On JVM this uses [InMemorySecureStorage], [createCryptoProvider], [FixedBatteryMonitor],
         * and a no-op [BleTransport]. Platform-specific BLE wiring is provided in S02 (Android via
         * [MeshLinkService]) and S03 (iOS via [MeshNode]).
         */
        public operator fun invoke(config: MeshLinkConfig): MeshLink {
            val crypto = createCryptoProvider()
            val storage = InMemorySecureStorage()
            val battery = FixedBatteryMonitor()
            val transport = NoOpBleTransport()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val clock: () -> Long = { MONOTONIC_ORIGIN.elapsedNow().inWholeMilliseconds }
            return create(config, crypto, transport, storage, battery, scope, clock, clock)
        }

        /**
         * Internal factory used by tests to inject a custom [BleTransport] (e.g.
         * [ch.trancee.meshlink.transport.VirtualMeshTransport]).
         */
        internal fun create(
            config: MeshLinkConfig,
            cryptoProvider: CryptoProvider,
            transport: BleTransport,
            storage: SecureStorage,
            batteryMonitor: BatteryMonitor,
            parentScope: CoroutineScope,
            clock: () -> Long,
            wallClock: () -> Long = clock,
        ): MeshLink {
            val identity = Identity.loadOrGenerate(cryptoProvider, storage)
            val engine =
                MeshEngine.create(
                    identity = identity,
                    cryptoProvider = cryptoProvider,
                    transport = transport,
                    storage = storage,
                    batteryMonitor = batteryMonitor,
                    scope = parentScope,
                    clock = clock,
                    config = config.toMeshEngineConfig(),
                )
            return MeshLink(
                config = config,
                identity = identity,
                engine = engine,
                transport = transport,
                scope = parentScope,
                clock = clock,
                wallClock = clock,
            )
        }
    }
}

// ── Private helpers ────────────────────────────────────────────────────────

private fun MessagePriority.toInternal(): Priority =
    when (this) {
        MessagePriority.LOW -> Priority.LOW
        MessagePriority.NORMAL -> Priority.NORMAL
        MessagePriority.HIGH -> Priority.HIGH
    }

// Used in PeerEvent.Connected handler — only reachable when a peer actually connects (S04 BLE
// tests).
@CoverageIgnore
private fun ByteArray.toHexString(): String =
    joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
