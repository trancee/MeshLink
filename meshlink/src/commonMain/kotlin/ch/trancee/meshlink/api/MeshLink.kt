package ch.trancee.meshlink.api

import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.engine.MeshEngine
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
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ── No-op BleTransport for headless/JVM use ────────────────────────────────

/**
 * No-operation [BleTransport] used by the default [MeshLink] factory on platforms where no real BLE
 * transport has been wired (JVM). On Android use [MeshLinkService]; on iOS use [MeshNode].
 *
 * Inlined as an anonymous object inside the companion factory. This private class exists only to
 * satisfy the `MeshLink(config)` default-transport path.
 */
internal object NoOpBleTransportInstance : BleTransport {
    override val localPeerId: ByteArray = ByteArray(12)
    override var advertisementServiceData: ByteArray = ByteArray(0)
    override var advertisementPseudonym: ByteArray = ByteArray(12)
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
    private val diagnosticSink: DiagnosticSinkApi = NoOpDiagnosticSink,
) : MeshLinkApi {

    // ── Diagnostic sink ────────────────────────────────────────────────────

    // diagnosticSink is now a constructor parameter — no in-line initialization needed.
    // The factory method creates the appropriate sink (live or no-op) and passes it to both
    // MeshEngine.create() and this constructor.

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

    // ── State guard ────────────────────────────────────────────────────────

    /** Throws [IllegalStateException] if the FSM is not in [MeshLinkState.RUNNING]. */
    private fun requireRunning(methodName: String) {
        val current = stateMachine.state.value
        if (current != MeshLinkState.RUNNING) {
            throw IllegalStateException("$methodName() called from invalid state: $current")
        }
    }

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

    override val peers: Flow<PeerEvent> = mapPeerEventsFlow(engine.peerEvents, clock)

    override val messages: Flow<ReceivedMessage> = engine.messages.map(::mapInboundMessage)

    override val deliveryConfirmations: Flow<MessageId> =
        engine.deliveryConfirmations.map { mapDelivered(it) }

    override val transferProgress: Flow<TransferProgress> =
        engine.transferProgress.map(::mapChunkProgress)

    override val transferFailures: Flow<TransferFailure> =
        engine.transferFailures.map(::mapDeliveryFailed)

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
        engine.updateBattery(percent, isCharging)
    }

    override fun setCustomPowerMode(mode: PowerTier?) {
        engine.setCustomPowerMode(mode)
    }

    // ── Identity & Trust ──────────────────────────────────────────────────

    override val localPublicKey: ByteArray
        get() = identity.edKeyPair.publicKey

    override fun peerPublicKey(id: ByteArray): ByteArray? = engine.peerPublicKey(id)

    override fun peerDetail(id: ByteArray): PeerDetail? {
        if (stateMachine.state.value != MeshLinkState.RUNNING) return null
        return engine.peerDetail(id)
    }

    override fun allPeerDetails(): List<PeerDetail> {
        if (stateMachine.state.value != MeshLinkState.RUNNING) return emptyList()
        return engine.allPeerDetails()
    }

    override fun peerFingerprint(id: ByteArray): String? = engine.peerFingerprint(id)

    override suspend fun rotateIdentity() {
        requireRunning("rotateIdentity")
        engine.rotateIdentity()
    }

    override suspend fun repinKey(id: ByteArray) {
        requireRunning("repinKey")
        engine.repinKey(id)
    }

    override suspend fun acceptKeyChange(peerId: ByteArray) {
        requireRunning("acceptKeyChange")
        engine.acceptKeyChange(peerId)
    }

    override suspend fun rejectKeyChange(peerId: ByteArray) {
        requireRunning("rejectKeyChange")
        engine.rejectKeyChange(peerId)
    }

    override fun pendingKeyChanges(): List<KeyChangeEvent> = engine.pendingKeyChangesList()

    // ── Health ─────────────────────────────────────────────────────────────

    override fun meshHealth(): MeshHealthSnapshot = buildHealthSnapshot()

    override fun shedMemoryPressure() {
        requireRunning("shedMemoryPressure")
        engine.shedMemoryPressure()
    }

    // ── Health helpers ─────────────────────────────────────────────────────

    /**
     * Cancels and clears the health ticker job. The lifecycle FSM guarantees [healthTickerJob] is
     * non-null when [stop] is called (start() always sets it before stop() can be reached).
     */
    private fun stopHealthTicker() {
        healthTickerJob!!.cancel()
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
            bufferUsageBytes = engine.bufferSizeBytes(),
            capturedAtMillis = clock(),
            reachablePeers = connected,
            bufferUtilizationPercent = engine.bufferUtilizationPercent(),
            activeTransfers = engine.activeTransferCount(),
            powerMode = engine.currentPowerTier,
            avgRouteCost = avgCost,
            relayQueueSize = engine.relayQueueSize(),
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
        requireRunning("forgetPeer")
        engine.forgetPeer(peerId)
    }

    override suspend fun factoryReset() {
        val current = stateMachine.state.value
        if (current != MeshLinkState.STOPPED && current != MeshLinkState.UNINITIALIZED) {
            throw IllegalStateException("factoryReset() called from invalid state: $current")
        }
        engine.factoryReset()
    }

    // ── Experimental ──────────────────────────────────────────────────────

    @ExperimentalMeshLinkApi
    override suspend fun addRoute(
        destination: ByteArray,
        nextHop: ByteArray,
        cost: Int,
        seqNo: Int,
    ) {
        requireRunning("addRoute")
        engine.addRoute(destination, nextHop, cost, seqNo)
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

    /** Test helper: inject a peer into the engine's presence tracker. */
    internal fun injectTestPeer(peerId: ByteArray) {
        engine.injectTestPeer(peerId)
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
            val transport = NoOpBleTransportInstance
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

            // Build the diagnostic sink early so it can be shared with MeshEngine + subsystems.
            val diagnosticSink: DiagnosticSinkApi =
                if (config.diagnostics.enabled) {
                    val redactFn: ((PeerIdHex) -> PeerIdHex)? =
                        if (config.diagnostics.redactPeerIds)
                            { peerId -> PeerIdHex(peerId.hex.take(8) + "…") }
                        else null
                    DiagnosticSink(
                        bufferCapacity = config.diagnostics.bufferCapacity,
                        redactFn = redactFn,
                        clock = clock,
                        wallClock = wallClock,
                    )
                } else {
                    NoOpDiagnosticSink
                }

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
                    diagnosticSink = diagnosticSink,
                )
            return MeshLink(
                config = config,
                identity = identity,
                engine = engine,
                transport = transport,
                scope = parentScope,
                clock = clock,
                wallClock = clock,
                diagnosticSink = diagnosticSink,
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

// Used in PeerEvent.Connected handler — exercised by integration tests with full Noise XX.
internal fun ByteArray.toHexString(): String =
    joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }

// ── Flow property mappers (extracted for testability — M005/S01) ──────────

/**
 * Maps a flow of engine-level peer events to a flow of public [PeerEvent]s.
 *
 * Extracted to a top-level function to avoid coroutine state machine phantom branches in MeshLink's
 * property initializer (Kover/JaCoCo MEM249). The function body is a simple flatMapConcat; the
 * mapPeerEvent transform logic is verified by unit tests.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun mapPeerEventsFlow(
    source: Flow<ch.trancee.meshlink.routing.PeerEvent>,
    clock: () -> Long,
): Flow<PeerEvent> = source.flatMapConcat { event -> mapPeerEvent(event, clock).asFlow() }

/**
 * Maps engine-level [ch.trancee.meshlink.routing.PeerEvent] to a list of public [PeerEvent]s.
 *
 * - Connected → [Found, StateChanged(CONNECTED)]
 * - Disconnected → [StateChanged(DISCONNECTED)]
 * - Gone → [Lost]
 *
 * Per MEM271: Gone emits only Lost (no StateChanged with a non-existent GONE value).
 */
internal fun mapPeerEvent(
    event: ch.trancee.meshlink.routing.PeerEvent,
    clock: () -> Long,
): List<PeerEvent> =
    when (event) {
        is ch.trancee.meshlink.routing.PeerEvent.Connected -> {
            // staticPublicKey wired to TrustStore in S02; stub with empty key for S01.
            val stubKey = ByteArray(32)
            listOf(
                PeerEvent.Found(
                    id = event.peerId,
                    detail =
                        PeerDetail(
                            id = event.peerId,
                            staticPublicKey = stubKey,
                            fingerprint = event.peerId.toHexString(),
                            state = PeerState.CONNECTED,
                            lastSeenTimestampMillis = clock(),
                            trustMode = TrustMode.STRICT,
                        ),
                ),
                PeerEvent.StateChanged(id = event.peerId, state = PeerState.CONNECTED),
            )
        }
        is ch.trancee.meshlink.routing.PeerEvent.Disconnected ->
            listOf(PeerEvent.StateChanged(id = event.peerId, state = PeerState.DISCONNECTED))
        is ch.trancee.meshlink.routing.PeerEvent.Gone -> listOf(PeerEvent.Lost(event.peerId))
    }

/** Maps engine-level [ch.trancee.meshlink.messaging.InboundMessage] to [ReceivedMessage]. */
internal fun mapInboundMessage(msg: ch.trancee.meshlink.messaging.InboundMessage): ReceivedMessage =
    ReceivedMessage(
        id = MessageId(msg.messageId),
        senderId = msg.senderId,
        payload = msg.payload,
        receivedAtMillis = msg.receivedAt,
    )

/** Maps engine-level [ch.trancee.meshlink.messaging.Delivered] to [MessageId]. */
internal fun mapDelivered(delivered: ch.trancee.meshlink.messaging.Delivered): MessageId =
    MessageId(delivered.messageId)

/**
 * Maps engine-level [ch.trancee.meshlink.transfer.TransferEvent.ChunkProgress] to
 * [TransferProgress].
 */
internal fun mapChunkProgress(
    progress: ch.trancee.meshlink.transfer.TransferEvent.ChunkProgress
): TransferProgress =
    TransferProgress(
        transferId = progress.messageId,
        // recipient is not tracked at TransferEngine layer; zero-filled stub for S01.
        recipient = ByteArray(12),
        bytesTransferred = progress.chunksReceived.toLong(),
        totalBytes = progress.totalChunks.toLong(),
    )

/** Maps engine-level [ch.trancee.meshlink.messaging.DeliveryFailed] to [TransferFailure]. */
internal fun mapDeliveryFailed(
    failure: ch.trancee.meshlink.messaging.DeliveryFailed
): TransferFailure =
    when (failure.outcome) {
        ch.trancee.meshlink.messaging.DeliveryOutcome.TIMED_OUT ->
            TransferFailure.Timeout(failure.messageId, ByteArray(12))
        ch.trancee.meshlink.messaging.DeliveryOutcome.DESTINATION_UNREACHABLE ->
            TransferFailure.PeerUnavailable(failure.messageId, ByteArray(12))
        ch.trancee.meshlink.messaging.DeliveryOutcome.SEND_FAILED ->
            TransferFailure.Cancelled(failure.messageId)
    }
