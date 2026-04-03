package io.meshlink

import io.meshlink.config.MeshLinkConfig
import io.meshlink.crypto.CryptoProvider
import io.meshlink.crypto.HandshakePayload
import io.meshlink.crypto.SealResult
import io.meshlink.crypto.SecurityEngine
import io.meshlink.crypto.TrustStore
import io.meshlink.delivery.DeliveryPipeline
import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticEvent
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.diagnostics.MeshHealthSnapshot
import io.meshlink.diagnostics.Severity
import io.meshlink.dispatch.DispatchSink
import io.meshlink.dispatch.InboundValidator
import io.meshlink.dispatch.MessageDispatcher
import io.meshlink.dispatch.OutboundTracker
import io.meshlink.model.KeyChangeEvent
import io.meshlink.model.Message
import io.meshlink.model.MessageId
import io.meshlink.model.PeerDetail
import io.meshlink.model.PeerEvent
import io.meshlink.model.TransferFailure
import io.meshlink.model.TransferProgress
import io.meshlink.peer.PeerConnectionAction
import io.meshlink.peer.PeerConnectionCoordinator
import io.meshlink.power.ModeChangeResult
import io.meshlink.power.PowerCoordinator
import io.meshlink.power.PowerMode
import io.meshlink.power.ShedAction
import io.meshlink.routing.RouteCoordinator
import io.meshlink.routing.RoutingEngine
import io.meshlink.send.BroadcastDecision
import io.meshlink.send.BroadcastPolicyChain
import io.meshlink.send.SendDecision
import io.meshlink.send.SendPolicyChain
import io.meshlink.transfer.ChunkData
import io.meshlink.transfer.TransferEngine
import io.meshlink.transfer.TransferUpdate
import io.meshlink.transport.BleTransport
import io.meshlink.util.AppIdFilter
import io.meshlink.util.ByteArrayKey
import io.meshlink.util.Compressor
import io.meshlink.util.DeliveryOutcome
import io.meshlink.util.PauseManager
import io.meshlink.util.PlatformLock
import io.meshlink.util.RateLimitPolicy
import io.meshlink.util.RateLimitResult
import io.meshlink.util.currentTimeMillis
import io.meshlink.util.hexToBytes
import io.meshlink.util.toHex
import io.meshlink.util.toKey
import io.meshlink.util.withLock
import io.meshlink.wire.AdvertisementCodec
import io.meshlink.wire.MessageIdGenerator
import io.meshlink.wire.NackReason
import io.meshlink.wire.WireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private const val NEXTHOP_UNRELIABLE_THRESHOLD = 0.5
private const val NEXTHOP_MIN_SAMPLES = 3
private const val ENVELOPE_UNCOMPRESSED: Byte = 0x00
private const val ENVELOPE_COMPRESSED: Byte = 0x01

class MeshLink(
    private val transport: BleTransport,
    private val config: MeshLinkConfig = MeshLinkConfig(),
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    clock: () -> Long = { currentTimeMillis() },
    private val crypto: CryptoProvider? = null,
    private val trustStore: TrustStore? = null,
) : MeshLinkApi {

    private val clock = clock
    private val sendLock = PlatformLock()
    private val compressor: Compressor? = if (config.compressionEnabled) Compressor() else null
    private val messageIdGenerator = MessageIdGenerator()

    private val rateLimitPolicy = RateLimitPolicy(config, clock)

    private val _peers = MutableSharedFlow<PeerEvent>(replay = 64)
    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    private val _deliveryConfirmations = MutableSharedFlow<MessageId>(extraBufferCapacity = 64)
    private val _transferFailures = MutableSharedFlow<TransferFailure>(extraBufferCapacity = 64)
    private val _transferProgress = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 64)
    private val _keyChanges = MutableSharedFlow<KeyChangeEvent>(extraBufferCapacity = 64)

    override val peers: Flow<PeerEvent> = _peers.asSharedFlow()
    override val messages: Flow<Message> = _messages.asSharedFlow()
    override val deliveryConfirmations: Flow<MessageId> = _deliveryConfirmations.asSharedFlow()
    override val transferFailures: Flow<TransferFailure> = _transferFailures.asSharedFlow()
    override val transferProgress: Flow<TransferProgress> = _transferProgress.asSharedFlow()
    override val keyChanges: Flow<KeyChangeEvent> = _keyChanges.asSharedFlow()

    private val _meshHealthFlow = MutableStateFlow(MeshHealthSnapshot(0, 0, 0, 0, PowerMode.PERFORMANCE, 0.0))
    override val meshHealthFlow: StateFlow<MeshHealthSnapshot> = _meshHealthFlow.asStateFlow()

    // Health flow throttle: minimum 500ms between emissions (2/sec max)
    private var lastHealthUpdateMillis: Long = 0L
    private val healthThrottleMillis: Long = 500L

    private fun emitHealthUpdate() {
        val now = clock()
        if (now - lastHealthUpdateMillis < healthThrottleMillis) return
        lastHealthUpdateMillis = now
        _meshHealthFlow.value = meshHealth()
    }

    // Callback isolation: catch exceptions from downstream collectors to prevent
    // a misbehaving subscriber from crashing the mesh engine.
    private suspend fun <T> safeEmit(flow: MutableSharedFlow<T>, value: T, label: String) {
        try {
            flow.emit(value)
        } catch (e: Exception) {
            diagnosticSink.emit(
                DiagnosticCode.SEND_FAILED,
                Severity.WARN,
                "callback exception in $label: ${e.message}"
            )
        }
    }

    private fun checkBufferPressure() {
        val usedBytes = (transferEngine.outboundBufferBytes() + transferEngine.inboundBufferBytes()).toLong()
        if (powerCoordinator.shouldWarnBufferPressure(usedBytes, config.bufferCapacity.toLong())) {
            val utilPercent = usedBytes * 100 / config.bufferCapacity
            diagnosticSink.emit(
                DiagnosticCode.BUFFER_PRESSURE,
                Severity.WARN,
                "utilization=$utilPercent%, used=$usedBytes, capacity=${config.bufferCapacity}"
            )
        }
    }

    private fun flushPendingMessages(peerId: ByteArrayKey, s: CoroutineScope) {
        val flushed = deliveryPipeline.flushPending(peerId, config.pendingMessageTtlMillis)
        for (msg in flushed) {
            doSend(s, msg.recipient, msg.payload)
        }
    }

    private fun resumeTransfers(peerId: ByteArray, s: CoroutineScope, eligibleKeys: Set<ByteArrayKey>) {
        val peerKey = peerId.toKey()
        for (key in eligibleKeys) {
            val info = transferEngine.getOutboundRecipientInfo(key) ?: continue
            val recipientKey = outboundTracker.recipient(key)?.toKey() ?: continue
            if (recipientKey == peerKey && !info.isComplete && !info.isFailed) {
                // Re-send remaining chunks via a dummy ACK to trigger retransmit
                val update = transferEngine.onAck(key, -1, 0uL, 0uL)
                if (update is TransferUpdate.Progress) {
                    dispatchChunks(s, outboundTracker.recipient(key)!!, update.chunksToSend, info.messageId)
                }
            }
        }
    }

    /**
     * Library lifecycle state machine per design §10.
     *
     * Valid transitions:
     *   start():  {UNINITIALIZED, STOPPED, RECOVERABLE} → RUNNING | RECOVERABLE | TERMINAL
     *   pause():  {RUNNING} → PAUSED
     *   resume(): {PAUSED} → RUNNING
     *   stop():   always safe (no-op from UNINITIALIZED, STOPPED, TERMINAL)
     */
    enum class LifecycleState {
        UNINITIALIZED,
        RUNNING,
        PAUSED,
        STOPPED,
        RECOVERABLE,
        TERMINAL,
    }

    private var lifecycleState = LifecycleState.UNINITIALIZED
    private var scope: CoroutineScope? = null
    private val baseContext = coroutineContext

    val state: LifecycleState get() = lifecycleState

    private fun checkRunningOrPaused() {
        when (lifecycleState) {
            LifecycleState.RUNNING, LifecycleState.PAUSED -> { /* ok */ }
            LifecycleState.TERMINAL ->
                throw IllegalStateException("MeshLink is in terminal state")
            else ->
                throw IllegalStateException("MeshLink not started (state=$lifecycleState)")
        }
    }

    private fun requireScope(): CoroutineScope =
        scope ?: throw IllegalStateException("MeshLink not started (state=$lifecycleState)")

    // Transfer engine (consolidates outbound chunking and inbound reassembly)
    private val transferEngine = TransferEngine(
        clock = clock,
        maxConcurrentInboundSessions = config.maxConcurrentInboundSessions,
    )

    // Outbound message state tracking (recipients, next-hops, replay counter)
    private val outboundTracker = OutboundTracker()

    private val pauseManager = PauseManager(
        sendQueueCapacity = config.pendingMessageCapacity,
        relayQueueCapacity = config.relayQueueCapacity,
    )

    // Routing engine: facade for routing table, presence tracking, dedup, and gossip
    private val routingEngine = RoutingEngine(
        localPeerId = transport.localPeerId.toKey(),
        dedupCapacity = config.dedupCapacity,
        routeCacheTtlMillis = config.routeCacheTtlMillis,
        maxHops = config.maxHops,
        clock = clock,
    )

    // Diagnostic event sink
    private val diagnosticSink = DiagnosticSink(
        bufferCapacity = config.diagnosticBufferCapacity,
        clock = clock,
        enabled = config.diagnosticsEnabled,
    )

    // Delivery pipeline: consolidates delivery tracking, tombstones, deadlines,
    // reverse-path relay, replay guards, inbound rate limiting, and store-and-forward
    private val deliveryPipeline = DeliveryPipeline(
        clock = clock,
        tombstoneWindowMillis = config.tombstoneWindowMillis,
        diagnosticSink = diagnosticSink,
    )

    // Power coordinator for battery-aware operation
    private val powerCoordinator = PowerCoordinator(clock = clock)

    // App ID filter for broadcast isolation
    private val appIdFilter = AppIdFilter(config.appId)

    // Security engine (consolidates E2E encryption, signatures, handshakes, key management)
    private val securityEngine: SecurityEngine? = crypto?.let {
        SecurityEngine(
            crypto = it,
            handshakePayload = HandshakePayload(
                protocolVersion = ((config.protocolVersion.major shl 8) or config.protocolVersion.minor).toUShort(),
                capabilityFlags = if (config.l2capEnabled) HandshakePayload.CAP_L2CAP else 0u,
                l2capPsm = 0u,
            ).encode(),
            clock = clock,
            diagnosticSink = diagnosticSink,
        )
    }

    private val sendPolicyChain = SendPolicyChain(
        bufferCapacity = config.bufferCapacity,
        localPeerId = transport.localPeerId,
        isPaused = { pauseManager.isPaused },
        checkSendRate = { rateLimitPolicy.checkSend(it) },
        checkCircuitBreaker = { rateLimitPolicy.checkCircuitBreaker() },
        resolveNextHop = { routingEngine.resolveNextHop(it) },
        peerPublicKey = securityEngine?.let { se -> { recipientId: ByteArrayKey -> se.peerPublicKey(recipientId) } },
    )

    private val broadcastPolicyChain = BroadcastPolicyChain(
        bufferCapacity = config.bufferCapacity,
        checkBroadcastRate = { rateLimitPolicy.checkBroadcast() },
        signData = securityEngine?.let { se -> { data: ByteArray -> se.sign(data) } },
        appIdHash = config.appId?.let { AppIdFilter.hash(it) } ?: ByteArray(8),
        localPeerId = transport.localPeerId,
        markAsSeen = { routingEngine.isDuplicate(it) },
        generateMessageId = { messageIdGenerator.generate().bytes },
    )

    private val routeCoordinator = RouteCoordinator(
        routingEngine = routingEngine,
        diagnosticSink = diagnosticSink,
        keepaliveIntervalMillis = config.keepaliveIntervalMillis,
        sendFrame = { peerId, frame -> safeSend(peerId, frame) },
        clock = clock,
    )

    // Pending route discoveries: destination → list of (payload, messageId) waiting for route
    private val pendingMessages = mutableMapOf<ByteArrayKey, MutableList<PendingSend>>()

    private val peerConnectionCoordinator = PeerConnectionCoordinator(
        routingEngine = routingEngine,
        securityEngine = securityEngine,
        rateLimitPolicy = { rateLimitPolicy.checkHandshake(it) },
        trustStore = trustStore ?: (if (crypto != null) TrustStore(config.trustMode) else null),
        localPeerId = transport.localPeerId,
        protocolVersion = config.protocolVersion,
        isPaused = { pauseManager.isPaused },
        localPowerMode = { powerCoordinator.currentMode.ordinal },
        localKeyHash = {
            securityEngine?.let { se ->
                crypto?.sha256(se.localPublicKey)
                    ?.copyOfRange(0, AdvertisementCodec.KEY_HASH_SIZE)
            } ?: ByteArray(0)
        },
    )

    private val inboundValidator = InboundValidator(
        securityEngine = securityEngine,
        deliveryPipeline = deliveryPipeline,
        rateLimitPolicy = rateLimitPolicy,
        appIdFilter = appIdFilter,
        diagnosticSink = diagnosticSink,
        localPeerId = transport.localPeerId,
        config = config,
    )

    private val messageDispatcher = MessageDispatcher(
        securityEngine = securityEngine,
        routingEngine = routingEngine,
        transferEngine = transferEngine,
        deliveryPipeline = deliveryPipeline,
        validator = inboundValidator,
        pauseManager = pauseManager,
        diagnosticSink = diagnosticSink,
        localPeerId = transport.localPeerId,
        config = config,
        outboundTracker = outboundTracker,
        sink = object : DispatchSink {
            override suspend fun onMessageReceived(senderId: ByteArray, payload: ByteArray) {
                safeEmit(_messages, Message(senderId = senderId, payload = payload), "messages")
            }
            override suspend fun onTransferProgress(messageId: ByteArray, chunksAcked: Int, totalChunks: Int) {
                safeEmit(
                    _transferProgress,
                    TransferProgress(
                        messageId = MessageId.fromBytes(messageId),
                        chunksAcked = chunksAcked,
                        totalChunks = totalChunks,
                    ),
                    "transferProgress"
                )
            }
            override suspend fun onDeliveryConfirmed(messageId: ByteArray) {
                val key = messageId.toKey()
                outboundTracker.removeNextHop(key)?.let { nextHop ->
                    routingEngine.recordNextHopSuccess(nextHop)
                }
                safeEmit(_deliveryConfirmations, MessageId.fromBytes(messageId), "deliveryConfirmations")
            }
            override fun onKeyChanged(event: KeyChangeEvent) {
                _keyChanges.tryEmit(event)
            }
            override suspend fun sendFrame(peerId: ByteArray, frame: ByteArray) {
                safeSend(peerId, frame)
            }
            override suspend fun dispatchChunks(recipient: ByteArray, chunks: List<ChunkData>, messageId: ByteArray) {
                this@MeshLink.dispatchChunks(scope!!, recipient, chunks, messageId)
            }
            override fun onRouteDiscovered(destination: ByteArrayKey) {
                drainPendingMessages(destination)
            }
            override fun onOutboundComplete(key: ByteArrayKey, messageId: ByteArray) {
                emitHealthUpdate()
            }
        },
        unwrapPayload = ::unwrapPayloadEnvelope,
    )

    override val localPublicKey: ByteArray? get() = securityEngine?.localPublicKey
    override fun peerPublicKey(peerIdHex: String): ByteArray? =
        securityEngine?.peerPublicKey(ByteArrayKey(hexToBytes(peerIdHex)))
    override val broadcastPublicKey: ByteArray? get() = securityEngine?.localBroadcastPublicKey

    override fun peerDetail(peerIdHex: String): PeerDetail? {
        val peerId = ByteArrayKey(hexToBytes(peerIdHex))
        val state = routingEngine.presenceState(peerId) ?: return null
        val route = routingEngine.bestRoute(peerId)
        val connectedIds = routingEngine.connectedPeerIds()
        val pubKey = securityEngine?.peerPublicKey(peerId)
        return PeerDetail(
            peerIdHex = peerIdHex,
            presenceState = state,
            isDirectNeighbor = peerId in connectedIds,
            routeNextHop = route?.nextHop?.toString(),
            routeCost = route?.cost,
            routeSequenceNumber = route?.sequenceNumber,
            publicKeyHex = pubKey?.toHex(),
            nextHopFailureRate = routingEngine.nextHopFailureRate(peerId),
            nextHopFailureCount = routingEngine.nextHopFailureCount(peerId),
        )
    }

    override fun allPeerDetails(): List<PeerDetail> {
        val allIds = routingEngine.allPeerIds()
        val connectedIds = routingEngine.connectedPeerIds()
        val allRoutes = routingEngine.allBestRoutes().associateBy { it.destination }
        return allIds.mapNotNull { peerId ->
            val state = routingEngine.presenceState(peerId) ?: return@mapNotNull null
            val route = allRoutes[peerId]
            val pubKey = securityEngine?.peerPublicKey(peerId)
            PeerDetail(
                peerIdHex = peerId.toString(),
                presenceState = state,
                isDirectNeighbor = peerId in connectedIds,
                routeNextHop = route?.nextHop?.toString(),
                routeCost = route?.cost,
                routeSequenceNumber = route?.sequenceNumber,
                publicKeyHex = pubKey?.toHex(),
                nextHopFailureRate = routingEngine.nextHopFailureRate(peerId),
                nextHopFailureCount = routingEngine.nextHopFailureCount(peerId),
            )
        }
    }

    /** Encode the AdvertisementCodec payload from current state. */
    private fun encodeAdvertisementPayload(): ByteArray {
        val keyHash = securityEngine?.let { se ->
            crypto?.sha256(se.localPublicKey)
        } ?: ByteArray(AdvertisementCodec.KEY_HASH_SIZE)
        return AdvertisementCodec.encode(
            versionMajor = config.protocolVersion.major,
            versionMinor = config.protocolVersion.minor,
            powerMode = powerCoordinator.currentMode.ordinal,
            publicKeyHash = keyHash,
        )
    }

    override fun start(): Result<Unit> {
        when (lifecycleState) {
            LifecycleState.RUNNING, LifecycleState.PAUSED -> return Result.success(Unit)
            LifecycleState.TERMINAL -> throw IllegalStateException(
                "MeshLink is in terminal state. Create a new instance."
            )
            LifecycleState.UNINITIALIZED, LifecycleState.STOPPED, LifecycleState.RECOVERABLE -> { /* proceed */ }
        }

        if (config.requireEncryption && crypto == null) {
            lifecycleState = LifecycleState.TERMINAL
            return Result.failure(
                IllegalStateException(
                    "CryptoProvider is required when requireEncryption is true. " +
                        "Pass a CryptoProvider to the MeshLink constructor, or set " +
                        "requireEncryption = false if plaintext operation is intentional."
                )
            )
        }

        val violations = config.validate()
        if (violations.isNotEmpty()) {
            lifecycleState = LifecycleState.RECOVERABLE
            return Result.failure(IllegalArgumentException(violations.joinToString("; ")))
        }

        lifecycleState = LifecycleState.RUNNING

        // Apply custom power mode from config if set
        config.customPowerMode?.let { powerCoordinator.setCustomPowerMode(it) }

        val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            diagnosticSink.emit(DiagnosticCode.SEND_FAILED, Severity.ERROR, "Uncaught: ${throwable::class.simpleName}")
        }
        val newScope = CoroutineScope(baseContext + SupervisorJob() + exceptionHandler)
        scope = newScope

        transport.advertisementServiceData = encodeAdvertisementPayload()
        newScope.launch {
            transport.advertisementEvents.collect { event ->
                when (
                    val action = peerConnectionCoordinator.onAdvertisementReceived(
                        event.peerId,
                        event.advertisementPayload
                    )
                ) {
                    is PeerConnectionAction.Rejected,
                    is PeerConnectionAction.Skipped -> { /* no-op */ }
                    is PeerConnectionAction.PeerUpdate -> {
                        action.keyChangeEvent?.let { _keyChanges.tryEmit(it) }
                        if (action.isNewPeer) {
                            safeEmit(_peers, PeerEvent.Found(action.peerId), "peers")
                            emitHealthUpdate()
                            val preExistingTransferKeys = outboundTracker.allRecipientKeys()
                            flushPendingMessages(action.peerId.toKey(), newScope)
                            resumeTransfers(action.peerId, newScope, preExistingTransferKeys)
                        }
                        if (action.handshakeRateLimited) {
                            diagnosticSink.emit(
                                DiagnosticCode.RATE_LIMIT_HIT,
                                Severity.WARN,
                                "handshake rate limit exceeded, peer=${action.peerId.toHex()}"
                            )
                        }
                        action.handshakeMessage?.let { safeSend(action.peerId, it) }
                    }
                    is PeerConnectionAction.Lost -> { /* handled separately */ }
                }
            }
        }
        newScope.launch {
            transport.peerLostEvents.collect { event ->
                val action = peerConnectionCoordinator.onPeerLost(event.peerId)
                safeEmit(_peers, PeerEvent.Lost(action.peerId), "peers")
                emitHealthUpdate()
                // Babel: send route retraction for the lost peer to remaining neighbors
                val lostKey = event.peerId.toKey()
                val retraction = routingEngine.buildRetraction(lostKey)
                for (peerId in routingEngine.connectedPeerIds()) {
                    if (peerId != lostKey) {
                        launch { safeSend(peerId.bytes, retraction) }
                    }
                }
            }
        }
        newScope.launch {
            transport.incomingData.collect { incoming ->
                handleIncomingData(incoming.peerId, incoming.data)
            }
        }
        newScope.launch {
            transport.startAdvertisingAndScanning()
        }

        // Keepalive loop: send keepalives to detect neighbour liveness
        if (config.keepaliveIntervalMillis > 0) {
            newScope.launch {
                routeCoordinator.runKeepaliveLoop { lifecycleState == LifecycleState.RUNNING }
            }
        }

        return Result.success(Unit)
    }

    override fun stop() {
        when (lifecycleState) {
            LifecycleState.UNINITIALIZED, LifecycleState.STOPPED, LifecycleState.TERMINAL -> return
            LifecycleState.RUNNING, LifecycleState.PAUSED, LifecycleState.RECOVERABLE -> { /* proceed */ }
        }
        lifecycleState = LifecycleState.STOPPED
        // Cancel scope first to stop gossip/keepalive loops, then await transport shutdown.
        scope?.cancel()
        // Await BLE transport shutdown on Default dispatcher to avoid deadlocking test dispatchers.
        runBlocking(kotlinx.coroutines.Dispatchers.Default) {
            withTimeoutOrNull(5_000L) { transport.stopAll() }
        }
        // Cancel all delivery deadline timers before processing in-flight transfers
        deliveryPipeline.cancelAllDeadlines()
        // Emit DELIVERY_TIMEOUT for all in-flight transfers before clearing
        for (key in outboundTracker.allRecipientKeys()) {
            if (deliveryPipeline.recordFailure(key, DeliveryOutcome.FAILED_DELIVERY_TIMEOUT)) {
                _transferFailures.tryEmit(
                    TransferFailure(MessageId.fromBytes(key.bytes), DeliveryOutcome.FAILED_DELIVERY_TIMEOUT)
                )
            }
        }
        // Emit DELIVERY_TIMEOUT for queued paused messages that were never sent
        for ((_, _) in pauseManager.drainSendQueue()) {
            _transferFailures.tryEmit(
                TransferFailure(MessageId.random(), DeliveryOutcome.FAILED_DELIVERY_TIMEOUT)
            )
        }
        clearState()
        scope = null
    }

    override fun rotateIdentity(): Result<Unit> {
        val se = securityEngine ?: return Result.failure(IllegalStateException("Crypto not enabled"))
        se.rotateIdentity()
        return Result.success(Unit)
    }

    private fun clearState() {
        transferEngine.clearAll()
        outboundTracker.clear()
        routingEngine.clear()
        pauseManager.clear()
        deliveryPipeline.clear()
        securityEngine?.clear()
        lastHealthUpdateMillis = 0L
    }

    override fun pause() {
        if (lifecycleState != LifecycleState.RUNNING) return
        lifecycleState = LifecycleState.PAUSED
        pauseManager.pause()
        scope?.launch { transport.stopAll() }
    }

    override fun resume() {
        if (lifecycleState != LifecycleState.PAUSED) return
        lifecycleState = LifecycleState.RUNNING
        val snapshot = pauseManager.resume()
        transport.advertisementServiceData = encodeAdvertisementPayload()
        scope?.launch { transport.startAdvertisingAndScanning() }
        val s = scope ?: return
        for ((recipient, payload) in snapshot.pendingSends) {
            doSend(s, recipient, payload)
        }
        for ((nextHop, frame) in snapshot.pendingRelays) {
            s.launch { safeSend(nextHop, frame) }
        }
    }

    override fun meshHealth(): MeshHealthSnapshot {
        val outboundBytes = transferEngine.outboundBufferBytes()
        val inboundBytes = transferEngine.inboundBufferBytes()
        val usedBytes = outboundBytes + inboundBytes
        val utilPercent = if (config.bufferCapacity > 0) {
            (usedBytes * 100 / config.bufferCapacity).coerceIn(0, 100)
        } else {
            0
        }
        val cap = config.bufferCapacity.toFloat().coerceAtLeast(1f)
        return MeshHealthSnapshot(
            connectedPeers = routingEngine.peerCount,
            reachablePeers = routingEngine.connectedPeerCount,
            bufferUtilizationPercent = utilPercent,
            activeTransfers = transferEngine.outboundCount,
            powerMode = powerCoordinator.currentMode,
            avgRouteCost = routingEngine.avgCost(),
            relayBufferUtilization = (inboundBytes.toFloat() / cap).coerceIn(0f, 1f),
            ownBufferUtilization = (outboundBytes.toFloat() / cap).coerceIn(0f, 1f),
            relayQueueSize = pauseManager.relayQueueSize,
        )
    }

    override fun drainDiagnostics(): List<DiagnosticEvent> {
        val events = mutableListOf<DiagnosticEvent>()
        @Suppress("DEPRECATION")
        diagnosticSink.drainTo(events)
        return events
    }

    override val diagnosticEvents: Flow<DiagnosticEvent> = diagnosticSink.events

    override fun sweep(seenPeers: Set<String>): Set<String> {
        val seenKeys = seenPeers.map { ByteArrayKey(hexToBytes(it)) }.toSet()
        val presenceEvicted = routingEngine.sweepPresence(seenKeys)
        for (peerId in presenceEvicted) {
            diagnosticSink.emit(DiagnosticCode.PEER_PRESENCE_EVICTED, Severity.INFO, "peerId=$peerId")
        }
        return presenceEvicted.map { it.toString() }.toSet()
    }

    override fun addRoute(destination: String, nextHop: String, cost: Double, sequenceNumber: UInt) {
        routingEngine.addRoute(
            ByteArrayKey(hexToBytes(destination)),
            ByteArrayKey(hexToBytes(nextHop)),
            cost,
            sequenceNumber,
        )
    }

    override fun sweepStaleTransfers(maxAgeMillis: Long): Int {
        val staleKeys = transferEngine.sweepStaleOutbound(maxAgeMillis)
        for (key in staleKeys) {
            outboundTracker.removeRecipient(key)
            outboundTracker.removeNextHop(key)?.let { nextHop ->
                routingEngine.recordNextHopFailure(nextHop)
                if (routingEngine.nextHopFailureRate(nextHop) > NEXTHOP_UNRELIABLE_THRESHOLD &&
                    routingEngine.nextHopFailureCount(nextHop) >= NEXTHOP_MIN_SAMPLES
                ) {
                    diagnosticSink.emit(
                        DiagnosticCode.NEXTHOP_UNRELIABLE,
                        Severity.WARN,
                        "nextHop=$nextHop, failureRate=${((routingEngine.nextHopFailureRate(nextHop) * 100).toInt())}%"
                    )
                }
            }
            if (deliveryPipeline.recordFailure(key, DeliveryOutcome.FAILED_ACK_TIMEOUT)) {
                _transferFailures.tryEmit(
                    TransferFailure(MessageId.fromBytes(key.bytes), DeliveryOutcome.FAILED_ACK_TIMEOUT)
                )
            }
        }
        return staleKeys.size
    }

    override fun sweepStaleReassemblies(maxAgeMillis: Long): Int {
        val staleKeys = transferEngine.sweepStaleInbound(maxAgeMillis)
        return staleKeys.size
    }

    override fun sweepExpiredPendingMessages(): Int {
        val expired = deliveryPipeline.sweepExpiredPending(config.pendingMessageTtlMillis)
        repeat(expired) {
            _transferFailures.tryEmit(
                TransferFailure(MessageId.random(), DeliveryOutcome.FAILED_DELIVERY_TIMEOUT)
            )
        }
        return expired
    }

    override fun updateBattery(batteryPercent: Int, isCharging: Boolean) {
        when (powerCoordinator.updateBattery(batteryPercent, isCharging)) {
            is ModeChangeResult.Changed -> emitHealthUpdate()
            is ModeChangeResult.Unchanged -> {}
        }
    }

    override fun setCustomPowerMode(mode: PowerMode?) {
        when (powerCoordinator.setCustomPowerMode(mode)) {
            is ModeChangeResult.Changed -> emitHealthUpdate()
            is ModeChangeResult.Unchanged -> {}
        }
    }

    override fun shedMemoryPressure(): List<String> {
        val health = meshHealth()
        val level = powerCoordinator.evaluatePressure(health.bufferUtilizationPercent)
            ?: return emptyList()
        val results = powerCoordinator.computeShedActions(
            level,
            transferEngine.inboundCount,
            routingEngine.dedupSize,
            routingEngine.peerCount
        )
        val actions = mutableListOf<String>()
        for (result in results) {
            when (result.action) {
                ShedAction.RELAY_BUFFERS_CLEARED -> {
                    transferEngine.clearAll()
                    val relayCount = pauseManager.relayQueueSize
                    pauseManager.clear()
                    actions.add("Cleared ${result.count} relay buffers, $relayCount queued relays")
                }
                ShedAction.DEDUP_TRIMMED -> {
                    routingEngine.clearDedup()
                    actions.add("Trimmed ${result.count} dedup entries")
                }
                ShedAction.CONNECTIONS_DROPPED -> {
                    actions.add("Would drop ${result.count} connections")
                }
            }
        }
        if (actions.isNotEmpty()) {
            diagnosticSink.emit(DiagnosticCode.MEMORY_PRESSURE, Severity.WARN, "level=$level, actions=$actions")
        }
        return actions
    }

    override fun broadcast(payload: ByteArray, maxHops: UByte): Result<MessageId> {
        checkRunningOrPaused()
        val s = requireScope()

        val effectiveHops = minOf(maxHops, config.broadcastTtl)
        return when (val decision = broadcastPolicyChain.evaluate(payload, effectiveHops)) {
            is BroadcastDecision.BufferFull ->
                Result.failure(IllegalArgumentException("bufferFull"))

            is BroadcastDecision.RateLimited -> {
                diagnosticSink.emit(
                    DiagnosticCode.RATE_LIMIT_HIT,
                    Severity.WARN,
                    "broadcast rate limit exceeded"
                )
                Result.failure(IllegalStateException("Broadcast rate limit exceeded"))
            }

            is BroadcastDecision.Proceed -> {
                for (peerId in routingEngine.allPeerIds()) {
                    s.launch { safeSend(peerId.bytes, decision.encodedFrame) }
                }
                Result.success(MessageId.fromBytes(decision.messageId))
            }
        }
    }

    override fun send(recipient: ByteArray, payload: ByteArray): Result<MessageId> {
        checkRunningOrPaused()
        return sendLock.withLock { sendInternal(recipient, payload) }
    }

    private fun sendInternal(recipient: ByteArray, payload: ByteArray): Result<MessageId> {
        val s = requireScope()

        return when (val decision = sendPolicyChain.evaluate(recipient, payload.size)) {
            is SendDecision.BufferFull -> {
                val failureId = MessageId.random()
                s.launch {
                    _transferFailures.emit(TransferFailure(failureId, DeliveryOutcome.FAILED_BUFFER_FULL))
                }
                Result.failure(IllegalArgumentException("bufferFull"))
            }

            is SendDecision.Loopback -> {
                val messageId = MessageId.random()
                s.launch {
                    safeEmit(_messages, Message(senderId = recipient, payload = payload), "messages")
                }
                Result.success(messageId)
            }

            is SendDecision.Paused -> {
                pauseManager.queueSend(recipient, payload)
                Result.success(MessageId.random())
            }

            is SendDecision.RateLimited -> {
                diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "recipient=${decision.key}")
                Result.failure(IllegalStateException("Rate limit exceeded"))
            }

            is SendDecision.CircuitBreakerOpen -> {
                Result.failure(IllegalStateException("Circuit breaker open — transport circuit tripped"))
            }

            is SendDecision.Routed -> {
                doRoutedSend(s, recipient, payload, decision.nextHopId)
            }

            is SendDecision.Unreachable -> {
                val destKey = recipient.toKey()
                val pending = pendingMessages.getOrPut(destKey) { mutableListOf() }
                pending.add(PendingSend(recipient, payload))
                // Initiate AODV route discovery
                val discovery = routingEngine.initiateRouteDiscovery(destKey)
                s.launch { routeCoordinator.floodRouteRequest(discovery.rreqFrame) }
                Result.success(MessageId.random())
            }

            is SendDecision.MissingPublicKey -> {
                Result.failure(IllegalStateException("Recipient public key unknown"))
            }

            is SendDecision.Direct -> {
                doSend(s, recipient, payload)
            }
        }
    }

    private fun doSend(s: CoroutineScope, recipient: ByteArray, payload: ByteArray): Result<MessageId> {
        val messageId = messageIdGenerator.generate().bytes
        val key = messageId.toKey()
        deliveryPipeline.registerOutbound(s, key, config.deliveryTimeoutMillis) { expiredKey ->
            _transferFailures.tryEmit(
                TransferFailure(
                    MessageId.fromBytes(expiredKey.bytes),
                    DeliveryOutcome.FAILED_DELIVERY_TIMEOUT
                )
            )
            transferEngine.removeOutbound(expiredKey)
            outboundTracker.removeRecipient(expiredKey)
            outboundTracker.removeNextHop(expiredKey)?.let { nextHop ->
                routingEngine.recordNextHopFailure(nextHop)
            }
        }

        // Compress payload if enabled and beneficial
        val envelopedPayload = wrapPayloadEnvelope(payload)

        // Encrypt payload via security engine
        val recipientId = recipient.toKey()
        val wirePayload = when (val sr = securityEngine?.seal(recipientId, envelopedPayload)) {
            is SealResult.Sealed -> sr.ciphertext
            else -> envelopedPayload
        }

        val chunkSize = config.mtu - WireCodec.CHUNK_HEADER_SIZE_FIRST
        val handle = transferEngine.beginSend(key, messageId, wirePayload, chunkSize)
        outboundTracker.registerRecipient(key, recipient)

        // Check buffer pressure after adding transfer
        checkBufferPressure()

        dispatchChunks(s, recipient, handle.chunks, messageId)

        return Result.success(MessageId.fromBytes(messageId))
    }

    private fun doRoutedSend(
        s: CoroutineScope,
        destination: ByteArray,
        payload: ByteArray,
        nextHopId: ByteArrayKey,
    ): Result<MessageId> {
        val messageId = messageIdGenerator.generate()
        val key = messageId.bytes.toKey()
        outboundTracker.registerNextHop(key, nextHopId)

        // Compress the payload but do not E2E-encrypt: routed messages
        // travel as plaintext because the sender typically has not performed
        // a Noise XX handshake with the (non-adjacent) destination.  Replay
        // counters, hop limits, and visited lists protect routing integrity.
        val routedPayload = wrapPayloadEnvelope(payload)

        val encoded = WireCodec.encodeRoutedMessage(
            messageId = messageId.bytes,
            origin = transport.localPeerId,
            destination = destination,
            hopLimit = 10u,
            visitedList = listOf(transport.localPeerId),
            payload = routedPayload,
            replayCounter = outboundTracker.advanceReplayCounter(),
        )
        s.launch { safeSend(nextHopId.bytes, encoded) }
        return Result.success(messageId)
    }

    internal fun wrapPayloadEnvelope(payload: ByteArray): ByteArray {
        if (compressor == null) return payload
        if (payload.size < config.compressionMinBytes) {
            return uncompressedEnvelope(payload)
        }
        val compressed = compressor.compress(payload)
        if (compressed.size >= payload.size) {
            return uncompressedEnvelope(payload)
        }
        val envelope = ByteArray(5 + compressed.size)
        envelope[0] = ENVELOPE_COMPRESSED
        envelope[1] = (payload.size and 0xFF).toByte()
        envelope[2] = ((payload.size shr 8) and 0xFF).toByte()
        envelope[3] = ((payload.size shr 16) and 0xFF).toByte()
        envelope[4] = ((payload.size shr 24) and 0xFF).toByte()
        compressed.copyInto(envelope, 5)
        return envelope
    }

    private fun uncompressedEnvelope(payload: ByteArray): ByteArray {
        val envelope = ByteArray(1 + payload.size)
        envelope[0] = ENVELOPE_UNCOMPRESSED
        payload.copyInto(envelope, 1)
        return envelope
    }

    internal fun unwrapPayloadEnvelope(envelope: ByteArray): ByteArray {
        if (!config.compressionEnabled || envelope.isEmpty()) return envelope
        return when (envelope[0]) {
            ENVELOPE_COMPRESSED -> {
                val originalSize = (envelope[1].toInt() and 0xFF) or
                    ((envelope[2].toInt() and 0xFF) shl 8) or
                    ((envelope[3].toInt() and 0xFF) shl 16) or
                    ((envelope[4].toInt() and 0xFF) shl 24)
                val compressed = envelope.copyOfRange(5, envelope.size)
                val c = compressor ?: Compressor()
                c.decompress(compressed, originalSize)
            }
            ENVELOPE_UNCOMPRESSED -> envelope.copyOfRange(1, envelope.size)
            else -> envelope
        }
    }

    private fun dispatchChunks(s: CoroutineScope, recipient: ByteArray, chunks: List<ChunkData>, messageId: ByteArray) {
        for (chunk in chunks) {
            val encoded = WireCodec.encodeChunk(
                messageId = messageId,
                sequenceNumber = chunk.seqNum.toUShort(),
                totalChunks = chunk.totalChunks.toUShort(),
                payload = chunk.payload,
            )
            s.launch { safeSend(recipient, encoded) }
        }
    }

    private suspend fun safeSend(peerId: ByteArray, data: ByteArray) {
        // Per-neighbor aggregate rate limit
        if (rateLimitPolicy.checkNeighborAggregate(peerId.toKey()) is RateLimitResult.Limited) {
            diagnosticSink.emit(
                DiagnosticCode.RATE_LIMIT_HIT,
                Severity.WARN,
                "neighbor aggregate limit exceeded, peer=${peerId.toHex()}"
            )
            return
        }
        try {
            transport.sendToPeer(peerId, data)
        } catch (e: Exception) {
            rateLimitPolicy.recordTransportFailure()
            diagnosticSink.emit(
                DiagnosticCode.SEND_FAILED,
                Severity.WARN,
                "peer=${peerId.toHex()}, error=${e.message}"
            )
        }
    }

    internal fun sendNack(
        peerId: ByteArray,
        messageId: ByteArray,
        reason: NackReason = NackReason.UNKNOWN,
    ) {
        val peerKey = peerId.toKey()
        if (rateLimitPolicy.checkNack(peerKey) is RateLimitResult.Limited) {
            diagnosticSink.emit(
                DiagnosticCode.RATE_LIMIT_HIT,
                Severity.WARN,
                "NACK rate limit exceeded, peer=$peerKey"
            )
            return
        }
        val frame = WireCodec.encodeNack(messageId, reason)
        scope?.launch { safeSend(peerId, frame) }
    }

    private suspend fun handleIncomingData(fromPeerId: ByteArray, data: ByteArray) {
        messageDispatcher.dispatch(fromPeerId, data)
    }

    /**
     * Drain pending messages for a newly-discovered destination.
     * Called when an RREP resolves a route.
     */
    private fun drainPendingMessages(destination: ByteArrayKey) {
        val pending = pendingMessages.remove(destination) ?: return
        val s = scope ?: return
        for (msg in pending) {
            s.launch { doSend(s, msg.recipient, msg.payload) }
        }
    }
}

private data class PendingSend(
    val recipient: ByteArray,
    val payload: ByteArray,
)
