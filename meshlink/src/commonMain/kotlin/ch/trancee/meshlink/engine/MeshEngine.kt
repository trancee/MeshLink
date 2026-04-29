package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DiagnosticCode
import ch.trancee.meshlink.api.DiagnosticPayload
import ch.trancee.meshlink.api.toPeerIdHex
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.crypto.TrustStore
import ch.trancee.meshlink.messaging.Delivered
import ch.trancee.meshlink.messaging.DeliveryFailed
import ch.trancee.meshlink.messaging.DeliveryPipeline
import ch.trancee.meshlink.messaging.InboundMessage
import ch.trancee.meshlink.messaging.SendResult
import ch.trancee.meshlink.power.BatteryMonitor
import ch.trancee.meshlink.power.PowerManager
import ch.trancee.meshlink.power.PowerProfile
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.routing.DedupSet
import ch.trancee.meshlink.routing.PeerEvent
import ch.trancee.meshlink.routing.PeerInfo
import ch.trancee.meshlink.routing.PresenceTracker
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.routing.RoutingEngine
import ch.trancee.meshlink.routing.RoutingTable
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.transfer.Priority
import ch.trancee.meshlink.transfer.TransferEngine
import ch.trancee.meshlink.transfer.TransferEvent
import ch.trancee.meshlink.transfer.TransferScheduler
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.Logger
import ch.trancee.meshlink.wire.Handshake
import ch.trancee.meshlink.wire.InboundValidator
import ch.trancee.meshlink.wire.Valid
import ch.trancee.meshlink.wire.WireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Top-level facade for the MeshLink protocol stack.
 *
 * Composes all M002 subsystems — transport, routing, transfer, messaging, power, and crypto — into
 * a single coherent object. Lifecycle is managed via [start]/[stop]; the public API surfaces are
 * [send], [broadcast], and a set of [SharedFlow] outputs for observing protocol events.
 *
 * **Usage**: obtain an instance via [create], then call [start]. Subscribe to the SharedFlow
 * surfaces before calling [start] to avoid missing early events.
 */
internal class MeshEngine
private constructor(
    private val engineScope: CoroutineScope,
    private val transport: BleTransport,
    private val deliveryPipeline: DeliveryPipeline,
    private val routeCoordinator: RouteCoordinator,
    private val presenceTracker: PresenceTracker,
    private val powerManager: PowerManager,
    private val transferScheduler: TransferScheduler,
    private val noiseHandshakeManager: NoiseHandshakeManager,
    /**
     * Exposed so [ch.trancee.meshlink.api.MeshLink] can build [RoutingSnapshot] without traversing
     * the private [RouteCoordinator] hierarchy.
     */
    internal val routingTable: RoutingTable,
    private val pseudonymRotator: PseudonymRotator,
    private val cryptoProvider: CryptoProvider,
    private val diagnosticSink: ch.trancee.meshlink.api.DiagnosticSinkApi,
    private val meshStateManager: MeshStateManager,
) {
    // ── Public SharedFlow surfaces ────────────────────────────────────────────

    /** All received messages — unicast and broadcast. */
    val messages: SharedFlow<InboundMessage> = deliveryPipeline.messages

    /** Emits once per successfully confirmed unicast delivery. */
    val deliveryConfirmations: SharedFlow<Delivered> = deliveryPipeline.deliveryConfirmations

    /** Emits once per terminal delivery failure. */
    val transferFailures: SharedFlow<DeliveryFailed> = deliveryPipeline.transferFailures

    /** Chunk-level transfer progress for own unicast sessions. */
    val transferProgress: SharedFlow<TransferEvent.ChunkProgress> =
        deliveryPipeline.transferProgress

    /** Peer connect/disconnect events sourced from [PresenceTracker]. */
    val peerEvents: Flow<PeerEvent> = presenceTracker.peerEvents

    /** Power-tier transition events sourced from [PowerManager]. */
    val tierChanges: SharedFlow<PowerTier> = powerManager.tierChanges

    /** The current power tier (direct read, no suspension). */
    val currentTier: PowerTier
        get() = powerManager.currentTier

    /**
     * Returns the number of peers with an active presence-tracked connection.
     *
     * Exposed so [ch.trancee.meshlink.api.MeshLink] can fill [MeshHealthSnapshot] without accessing
     * [presenceTracker] directly.
     */
    internal fun connectedPeerCount(): Int = presenceTracker.connectedPeers().size

    /**
     * Returns the current [PowerTier] in effect.
     *
     * Exposed so [ch.trancee.meshlink.api.MeshLink] can fill [MeshHealthSnapshot] without accessing
     * [powerManager] directly.
     */
    internal val currentPowerTier: PowerTier
        get() = powerManager.currentTier

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts the MeshEngine: wires all internal subscriptions and begins advertising/scanning.
     *
     * Must be called once after construction. Call [stop] to tear down.
     */
    suspend fun start() {
        // Start pseudonym rotation before advertising — ensures no window of raw keyHash exposure.
        // PseudonymRotator.start() computes the initial pseudonym and invokes onRotation
        // synchronously, so transport.advertisementPseudonym is set before any advertisement goes
        // out.
        pseudonymRotator.start()

        // Set initial advertisement data from current power tier and pseudonym.
        updateAdvertisementData()

        // Start BLE advertising and scanning.
        transport.startAdvertisingAndScanning()

        // Wire advertisement events → handshake manager.
        // Cache last-seen advertisement per peer for PeerInfo construction on connect.
        val advertisementCache = HashMap<List<Byte>, Pair<ByteArray, Int>>()
        engineScope.launch {
            transport.advertisementEvents.collect { event ->
                advertisementCache[event.peerId.asList()] = Pair(event.serviceData, event.rssi)
                noiseHandshakeManager.onAdvertisementSeen(event.peerId, event.serviceData)

                // Pseudonym verification for known (already-connected) peers.
                // If serviceData carries a full 16-byte advertisement, bytes 4–15 are
                // the peer's pseudonym. Verify it against the peer's keyHash using HMAC
                // with ±1 epoch tolerance.
                if (event.serviceData.size >= 16) {
                    val connected = presenceTracker.connectedPeers()
                    val peerKeyHash = event.peerId
                    val isKnownPeer = connected.any { it.contentEquals(peerKeyHash) }
                    if (isKnownPeer) {
                        val receivedPseudonym = event.serviceData.copyOfRange(4, 16)
                        val epoch = pseudonymRotator.currentEpoch()
                        val valid =
                            PseudonymRotator.verifyPseudonym(
                                peerKeyHash,
                                receivedPseudonym,
                                epoch,
                                cryptoProvider,
                            )
                        if (!valid) {
                            val peerIdHex = peerKeyHash.toPeerIdHex()
                            diagnosticSink.emit(DiagnosticCode.DECRYPTION_FAILED) {
                                DiagnosticPayload.DecryptionFailed(
                                    peerIdHex,
                                    "pseudonym verification mismatch",
                                )
                            }
                        }
                    }
                }
            }
        }

        // Wire peer-lost events → presence tracker (triggers Disconnected state).
        // Route cleanup is deferred to MeshStateManager sweep when the peer transitions to Gone.
        // Power release remains immediate to free connection slots for new peers.
        engineScope.launch {
            transport.peerLostEvents.collect { event ->
                powerManager.releaseConnection(event.peerId)
                presenceTracker.onPeerDisconnected(event.peerId)
            }
        }

        // Wire inbound handshake messages from transport to handshake manager.
        launchInboundHandshakeSubscription()

        // Wire routing outbound frames → transport.
        launchOutboundFramesSubscription()

        // Wire power tier changes → transfer scheduler, presence timeout, transport ad data.
        launchTierChangesSubscription()

        // Wire eviction requests → disconnect, routing, presence.
        launchEvictionRequestsSubscription()

        // Wire handshake-complete callback → connection slot acquisition and routing registration.
        noiseHandshakeManager.onHandshakeComplete = { peerId ->
            val acquired = powerManager.tryAcquireConnection(peerId, Priority.NORMAL)
            if (!acquired) {
                rejectAndDisconnect(peerId)
            } else {
                connectVerifiedPeer(peerId, advertisementCache)
            }
        }

        // Wire send-handshake callback → encode and transmit via transport.
        noiseHandshakeManager.sendHandshake = { peerId, handshakeMsg ->
            dispatchHandshakeMessage(peerId, handshakeMsg)
        }

        // Start routing engine timers and outbound message collection.
        routeCoordinator.start()

        // Start the periodic sweep timer for peer lifecycle management.
        meshStateManager.start(engineScope)

        // Log delivery confirmations for diagnostic observability.
        launchDeliveryConfirmationLogging()
    }

    /** Stops the MeshEngine: cancels all internal coroutines and halts the transport. */
    suspend fun stop() {
        meshStateManager.stop()
        engineScope.cancel()
        transport.stopAll()
    }

    // ── Internal helpers ────────────────────────────────────────────────────────

    /**
     * Handles a tier-change event from [PowerManager]. Updates the transfer scheduler, presence
     * timeout, and advertisement data to reflect the new power tier.
     */
    private fun handleTierChange(tier: PowerTier) {
        val profile = powerManager.profile()
        transferScheduler.updateMaxConcurrent(profile.maxConnections)
        presenceTracker.updatePresenceTimeout(profile.presenceTimeoutMillis)
        updateAdvertisementData()
    }

    /**
     * Composes the full advertisement state: encodes current power tier via [PowerTierCodec],
     * ensures the transport's [BleTransport.advertisementPseudonym] reflects the current pseudonym
     * from [PseudonymRotator], and writes the encoded tier to
     * [BleTransport.advertisementServiceData].
     */
    private fun updateAdvertisementData() {
        transport.advertisementPseudonym = pseudonymRotator.currentPseudonym()
        transport.advertisementServiceData = PowerTierCodec.encode(powerManager.currentTier)
    }

    /**
     * Handles an eviction request from [PowerManager]. Disconnects the peer and removes it from
     * routing and presence tracking.
     */
    private suspend fun handleEvictionRequest(peerId: ByteArray) {
        transport.disconnect(peerId)
        routeCoordinator.onPeerDisconnected(peerId)
        presenceTracker.onPeerDisconnected(peerId)
    }

    /**
     * Launches a subscription on [BleTransport.incomingData] that routes [Handshake] wire messages
     * to [NoiseHandshakeManager.onInboundHandshake]. [DeliveryPipeline] already subscribes to the
     * same flow but silently drops Handshake messages; both collectors coexist on the SharedFlow.
     */
    private fun launchInboundHandshakeSubscription() {
        engineScope.launch {
            transport.incomingData.collect { incoming ->
                val result = InboundValidator.validate(incoming.data)
                if (result is Valid && result.message is Handshake) {
                    noiseHandshakeManager.onInboundHandshake(incoming.peerId, result.message)
                }
            }
        }
    }

    /**
     * Launches the [RouteCoordinator.outboundFrames] subscription in [engineScope]. Unicast frames
     * are sent to a specific peer; broadcast frames are flooded to all connected peers.
     */
    private fun launchOutboundFramesSubscription() {
        engineScope.launch {
            routeCoordinator.outboundFrames.collect { frame ->
                val encoded = WireCodec.encode(frame.message)
                if (frame.peerId == null) {
                    for (peer in presenceTracker.connectedPeers()) {
                        transport.sendToPeer(peer, encoded)
                    }
                } else {
                    transport.sendToPeer(frame.peerId, encoded)
                }
            }
        }
    }

    /**
     * Launches the [PowerManager.tierChanges] subscription in [engineScope]. Delegates to
     * [handleTierChange] for each emitted tier.
     */
    private fun launchTierChangesSubscription() {
        engineScope.launch { powerManager.tierChanges.collect { tier -> handleTierChange(tier) } }
    }

    /**
     * Launches the [PowerManager.evictionRequests] subscription in [engineScope]. Delegates to
     * [handleEvictionRequest] for each eviction request.
     */
    private fun launchEvictionRequestsSubscription() {
        engineScope.launch {
            powerManager.evictionRequests.collect { peerId -> handleEvictionRequest(peerId) }
        }
    }

    /**
     * Schedules a [BleTransport.disconnect] for a peer whose connection was rejected by
     * [PowerManager].
     */
    private fun rejectAndDisconnect(peerId: ByteArray) {
        engineScope.launch { transport.disconnect(peerId) }
    }

    /** Encodes and transmits a Noise handshake message via the transport. */
    private fun dispatchHandshakeMessage(peerId: ByteArray, handshakeMsg: Handshake) {
        engineScope.launch { transport.sendToPeer(peerId, WireCodec.encode(handshakeMsg)) }
    }

    /**
     * Registers a verified peer after [PowerManager] accepts the connection. Builds [PeerInfo] from
     * cached advertisement data and registers with [RouteCoordinator].
     */
    private fun connectVerifiedPeer(
        peerId: ByteArray,
        advertisementCache: HashMap<List<Byte>, Pair<ByteArray, Int>>,
    ) {
        val cached = advertisementCache[peerId.asList()] ?: Pair(ByteArray(0), -70)
        val serviceData = cached.first
        val rssi = cached.second
        val peerInfo =
            PeerInfo(
                peerId = peerId,
                powerMode =
                    if (serviceData.isNotEmpty()) serviceData[0]
                    else PowerTierCodec.encode(PowerTier.BALANCED)[0],
                rssi = rssi,
                lossRate = 0.0,
            )
        routeCoordinator.onPeerConnected(peerInfo)
        // Note: presenceTracker.onPeerConnected is called inside routeCoordinator.
        powerManager.onFirstConnectionEstablished()
    }

    // ── Public messaging API ──────────────────────────────────────────────────

    /** Subscribes to [DeliveryPipeline.deliveryConfirmations] and logs each confirmed delivery. */
    private fun launchDeliveryConfirmationLogging() {
        engineScope.launch {
            deliveryPipeline.deliveryConfirmations.collect { delivered ->
                val msgHex =
                    delivered.messageId.take(4).joinToString("") {
                        it.toUByte().toString(16).padStart(2, '0')
                    }
                Logger.d("MeshLink", "delivery confirmed msgId=$msgHex...")
            }
        }
    }

    /**
     * Sends [payload] to [recipient]. Delegates to [DeliveryPipeline.send].
     *
     * @return [SendResult.Sent] when accepted for immediate transmission, [SendResult.Queued] when
     *   deferred.
     */
    suspend fun send(
        recipient: ByteArray,
        payload: ByteArray,
        priority: Priority = Priority.NORMAL,
    ): SendResult = deliveryPipeline.send(recipient, payload, priority)

    /**
     * Flood-fills [payload] to all connected neighbours. Delegates to [DeliveryPipeline.broadcast].
     *
     * @return The 16-byte message ID assigned to this broadcast.
     */
    suspend fun broadcast(
        payload: ByteArray,
        maxHops: UByte,
        priority: Priority = Priority.NORMAL,
    ): ByteArray = deliveryPipeline.broadcast(payload, maxHops, priority)

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        /**
         * Creates a fully-wired [MeshEngine] instance with all subsystems instantiated.
         *
         * The engine creates a child [CoroutineScope] internally — calling [stop] cancels only the
         * engine's scope, not the parent [scope].
         *
         * @param identity The node's long-term cryptographic identity.
         * @param cryptoProvider Platform-specific crypto operations.
         * @param transport BLE transport for peer discovery and data exchange.
         * @param storage Persistent key storage backend.
         * @param batteryMonitor Source of battery level readings.
         * @param scope Parent coroutine scope — the engine attaches as a child Job.
         * @param clock Monotonic clock function (milliseconds since epoch).
         * @param config Aggregate configuration for all subsystems.
         */
        internal fun create(
            identity: Identity,
            cryptoProvider: CryptoProvider,
            transport: BleTransport,
            storage: SecureStorage,
            batteryMonitor: BatteryMonitor,
            scope: CoroutineScope,
            clock: () -> Long,
            config: MeshEngineConfig = MeshEngineConfig(),
            diagnosticSink: ch.trancee.meshlink.api.DiagnosticSinkApi =
                ch.trancee.meshlink.api.NoOpDiagnosticSink,
        ): MeshEngine {
            // Engine scope is a child of the passed scope so cancelling `engineScope` does not
            // propagate upward and does not affect the test scope in unit tests.
            val engineScope =
                CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))

            // ── Crypto / trust layer ──────────────────────────────────────────
            val trustStore = TrustStore(storage)

            // ── Routing layer ─────────────────────────────────────────────────
            val routingTable = RoutingTable(clock)
            val routingEngine =
                RoutingEngine(
                    routingTable = routingTable,
                    localPeerId = identity.keyHash,
                    localEdPublicKey = identity.edKeyPair.publicKey,
                    localDhPublicKey = identity.dhKeyPair.publicKey,
                    scope = engineScope,
                    clock = clock,
                    config = config.routing,
                    diagnosticSink = diagnosticSink,
                )
            val dedupSet =
                DedupSet(config.routing.dedupCapacity, config.routing.dedupTtlMillis, clock)
            val presenceTracker = PresenceTracker(clock)
            val routeCoordinator =
                RouteCoordinator(
                    localPeerId = identity.keyHash,
                    localEdPublicKey = identity.edKeyPair.publicKey,
                    localDhPublicKey = identity.dhKeyPair.publicKey,
                    routingTable = routingTable,
                    routingEngine = routingEngine,
                    dedupSet = dedupSet,
                    presenceTracker = presenceTracker,
                    trustStore = trustStore,
                    scope = engineScope,
                    clock = clock,
                    config = config.routing,
                    diagnosticSink = diagnosticSink,
                )

            // ── Transfer layer ────────────────────────────────────────────────
            val transferEngine =
                TransferEngine(
                    engineScope,
                    config.transfer,
                    config.chunkSize,
                    diagnosticSink = diagnosticSink,
                )
            val initialMaxConnections =
                PowerProfile.forTier(PowerTier.BALANCED, config.power).maxConnections
            val transferScheduler = TransferScheduler(maxConcurrent = initialMaxConnections)

            // ── Messaging layer ───────────────────────────────────────────────
            val deliveryPipeline =
                DeliveryPipeline(
                    scope = engineScope,
                    transport = transport,
                    routeCoordinator = routeCoordinator,
                    transferEngine = transferEngine,
                    inboundValidator = InboundValidator,
                    localIdentity = identity,
                    cryptoProvider = cryptoProvider,
                    trustStore = trustStore,
                    dedupSet = dedupSet,
                    config = config.messaging,
                    clock = clock,
                    diagnosticSink = diagnosticSink,
                )

            // ── Power management ──────────────────────────────────────────────
            val powerManager =
                PowerManager(
                    engineScope,
                    batteryMonitor,
                    clock,
                    config.power,
                    diagnosticSink = diagnosticSink,
                )

            // ── Noise handshake manager ───────────────────────────────────────
            val noiseHandshakeManager =
                NoiseHandshakeManager(
                    localIdentity = identity,
                    cryptoProvider = cryptoProvider,
                    trustStore = trustStore,
                    config = config.handshake,
                    clock = clock,
                    diagnosticSink = diagnosticSink,
                )

            // ── Pseudonym rotation ────────────────────────────────────────────
            // onRotation callback is set to a no-op here; MeshEngine.start() wires the
            // real callback that updates transport advertisement data. The rotator is
            // started in start() to ensure no window of raw keyHash exposure.
            val pseudonymRotator =
                PseudonymRotator(
                    keyHash = identity.keyHash,
                    cryptoProvider = cryptoProvider,
                    scope = engineScope,
                    clock = clock,
                    diagnosticSink = diagnosticSink,
                    onRotation = { newPseudonym ->
                        transport.advertisementPseudonym = newPseudonym
                        transport.advertisementServiceData =
                            PowerTierCodec.encode(powerManager.currentTier)
                    },
                    epochDurationMs = config.epochDurationMs,
                )

            // ── Sweep orchestrator ────────────────────────────────────────────
            val meshStateManager =
                MeshStateManager(
                    presenceTracker = presenceTracker,
                    routeCoordinator = routeCoordinator,
                    powerManager = powerManager,
                    deliveryPipeline = deliveryPipeline,
                    diagnosticSink = diagnosticSink,
                )

            return MeshEngine(
                engineScope = engineScope,
                transport = transport,
                deliveryPipeline = deliveryPipeline,
                routeCoordinator = routeCoordinator,
                presenceTracker = presenceTracker,
                powerManager = powerManager,
                transferScheduler = transferScheduler,
                noiseHandshakeManager = noiseHandshakeManager,
                routingTable = routingTable,
                pseudonymRotator = pseudonymRotator,
                cryptoProvider = cryptoProvider,
                diagnosticSink = diagnosticSink,
                meshStateManager = meshStateManager,
            )
        }
    }
}
