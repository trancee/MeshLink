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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ── No-op BleTransport for headless/JVM use ────────────────────────────────

/**
 * No-operation [BleTransport] used by the default [MeshLink] factory on platforms where no real BLE
 * transport has been wired (JVM). On Android use [MeshLinkService]; on iOS use [MeshNode].
 */
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
public class MeshLink
internal constructor(
    private val config: MeshLinkConfig,
    private val identity: Identity,
    private val engine: MeshEngine,
    private val transport: BleTransport,
    @Suppress("unused") private val scope: CoroutineScope,
    private val clock: () -> Long,
) : MeshLinkApi {

    // ── State machine ──────────────────────────────────────────────────────

    private val _diagnosticFlow =
        MutableSharedFlow<DiagnosticEvent>(
            replay = 0,
            extraBufferCapacity = config.diagnostics.bufferCapacity.coerceAtLeast(1),
        )

    private val stateMachine =
        MeshLinkStateMachine(
            nowMs = clock,
            onDiagnostic = { event -> _diagnosticFlow.tryEmit(event) },
        )

    private val mutex = Mutex()

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
                                lastSeenTimestampMs = clock(),
                                trustMode = TrustMode.STRICT,
                            ),
                    )
                }
                is ch.trancee.meshlink.routing.PeerEvent.Disconnected ->
                    PeerEvent.Lost(event.peerId)
            }
        }

    override val messages: Flow<ReceivedMessage> =
        engine.messages.map { msg ->
            ReceivedMessage(
                id = MessageId(msg.messageId),
                senderId = msg.senderId,
                payload = msg.payload,
                receivedAtMs = msg.receivedAt,
            )
        }

    override val deliveryConfirmations: Flow<MessageId> =
        engine.deliveryConfirmations.map { MessageId(it.messageId) }

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

    override val meshHealthFlow: Flow<MeshHealthSnapshot> = emptyFlow()

    override val keyChanges: Flow<KeyChangeEvent> = emptyFlow()

    override val diagnosticEvents: Flow<DiagnosticEvent> = _diagnosticFlow

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

    override fun meshHealth(): MeshHealthSnapshot =
        MeshHealthSnapshot(
            connectedPeers = 0, // PresenceTracker wired in S02
            routingTableSize = engine.routingTable.routeCount(),
            bufferUsageBytes = 0L,
            capturedAtMs = clock(),
        )

    override fun shedMemoryPressure() {
        // Wired to DeliveryPipeline in S02.
    }

    // ── Routing ────────────────────────────────────────────────────────────

    override fun routingSnapshot(): RoutingSnapshot {
        val nowMs = clock()
        val ttlMs = config.routing.routeCacheTtlMillis
        val routes =
            engine.routingTable
                .allRoutes()
                .map { entry ->
                    RoutingEntry(
                        destination = entry.destination,
                        nextHop = entry.nextHop,
                        cost = entry.metric.toInt(),
                        seqNo = entry.seqNo.toInt(),
                        ageMs = (ttlMs - (entry.expiresAt - nowMs)).coerceAtLeast(0L),
                    )
                }
                .sortedBy { it.cost }
        return RoutingSnapshot(capturedAtMs = nowMs, routes = routes)
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
            return create(config, crypto, transport, storage, battery, scope, clock)
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

private fun ByteArray.toHexString(): String =
    joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
