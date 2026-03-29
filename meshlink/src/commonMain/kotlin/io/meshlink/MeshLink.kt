package io.meshlink

import io.meshlink.config.MeshLinkConfig
import io.meshlink.crypto.CryptoProvider
import io.meshlink.crypto.HandshakePayload
import io.meshlink.crypto.SealResult
import io.meshlink.crypto.SecurityEngine
import io.meshlink.crypto.TrustStore
import io.meshlink.delivery.BufferResult
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
import io.meshlink.gossip.GossipCoordinator
import io.meshlink.model.KeyChangeEvent
import io.meshlink.model.Message
import io.meshlink.model.PeerEvent
import io.meshlink.model.TransferFailure
import io.meshlink.model.TransferProgress
import io.meshlink.peer.PeerConnectionAction
import io.meshlink.peer.PeerConnectionCoordinator
import io.meshlink.power.ModeChangeResult
import io.meshlink.power.PowerCoordinator
import io.meshlink.power.ShedAction
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
import io.meshlink.util.DeliveryOutcome
import io.meshlink.util.PauseManager
import io.meshlink.util.RateLimitPolicy
import io.meshlink.util.RateLimitResult
import io.meshlink.util.createPlatformLock
import io.meshlink.util.currentTimeMillis
import io.meshlink.util.hexToBytes
import io.meshlink.util.toHex
import io.meshlink.util.withLock
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val NEXTHOP_UNRELIABLE_THRESHOLD = 0.5
private const val NEXTHOP_MIN_SAMPLES = 3

@OptIn(ExperimentalUuidApi::class)
class MeshLink(
    private val transport: BleTransport,
    private val config: MeshLinkConfig = MeshLinkConfig(),
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    clock: () -> Long = { currentTimeMillis() },
    private val crypto: CryptoProvider? = null,
    private val trustStore: TrustStore? = null,
) : MeshLinkApi {

    private val clock = clock
    private val sendLock = createPlatformLock()

    private val rateLimitPolicy = RateLimitPolicy(config, clock)

    private val _peers = MutableSharedFlow<PeerEvent>(replay = 64)
    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    private val _deliveryConfirmations = MutableSharedFlow<Uuid>(extraBufferCapacity = 64)
    private val _transferFailures = MutableSharedFlow<TransferFailure>(extraBufferCapacity = 64)
    private val _transferProgress = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 64)
    private val _keyChanges = MutableSharedFlow<KeyChangeEvent>(extraBufferCapacity = 64)

    override val peers: Flow<PeerEvent> = _peers.asSharedFlow()
    override val messages: Flow<Message> = _messages.asSharedFlow()
    override val deliveryConfirmations: Flow<Uuid> = _deliveryConfirmations.asSharedFlow()
    override val transferFailures: Flow<TransferFailure> = _transferFailures.asSharedFlow()
    override val transferProgress: Flow<TransferProgress> = _transferProgress.asSharedFlow()
    override val keyChanges: Flow<KeyChangeEvent> = _keyChanges.asSharedFlow()

    private val _meshHealthFlow = MutableStateFlow(MeshHealthSnapshot(0, 0, 0, 0, "PERFORMANCE", 0.0))
    override val meshHealthFlow: StateFlow<MeshHealthSnapshot> = _meshHealthFlow.asStateFlow()

    // Health flow throttle: minimum 500ms between emissions (2/sec max)
    private var lastHealthUpdateMs: Long = 0L
    private val healthThrottleMs: Long = 500L

    private fun emitHealthUpdate() {
        val now = clock()
        if (now - lastHealthUpdateMs < healthThrottleMs) return
        lastHealthUpdateMs = now
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

    private fun flushPendingMessages(peerHex: String, s: CoroutineScope) {
        val flushed = deliveryPipeline.flushPending(peerHex, config.pendingMessageTtlMs)
        for (msg in flushed) {
            doSend(s, msg.recipient, msg.payload)
        }
    }

    private fun resumeTransfers(peerId: ByteArray, s: CoroutineScope, eligibleKeys: Set<String>) {
        val peerHex = peerId.toHex()
        for (key in eligibleKeys) {
            val info = transferEngine.getOutboundRecipientInfo(key) ?: continue
            val recipientHex = outboundTracker.recipient(key)?.toHex() ?: continue
            if (recipientHex == peerHex && !info.isComplete && !info.isFailed) {
                // Re-send remaining chunks via a dummy ACK to trigger retransmit
                val update = transferEngine.onAck(key, -1, 0uL)
                if (update is TransferUpdate.Progress) {
                    dispatchChunks(s, outboundTracker.recipient(key)!!, update.chunksToSend, info.messageId)
                }
            }
        }
    }

    private var started = false
    private var scope: CoroutineScope? = null
    private val baseContext = coroutineContext

    private fun requireScope(): CoroutineScope =
        scope ?: throw IllegalStateException("MeshLink not started")

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
        localPeerId = transport.localPeerId.toHex(),
        dedupCapacity = config.dedupCapacity,
        triggeredUpdateThreshold = config.triggeredUpdateThreshold,
        gossipIntervalMs = config.gossipIntervalMs,
        clock = clock,
    )

    // Diagnostic event sink
    private val diagnosticSink = DiagnosticSink(bufferCapacity = config.diagnosticBufferCapacity, clock = clock)

    // Delivery pipeline: consolidates delivery tracking, tombstones, deadlines,
    // reverse-path relay, replay guards, inbound rate limiting, and store-and-forward
    private val deliveryPipeline = DeliveryPipeline(
        clock = clock,
        tombstoneWindowMs = config.tombstoneWindowMs,
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
        )
    }

    private val sendPolicyChain = SendPolicyChain(
        bufferCapacity = config.bufferCapacity,
        localPeerId = transport.localPeerId,
        isPaused = { pauseManager.isPaused },
        checkSendRate = { rateLimitPolicy.checkSend(it) },
        checkCircuitBreaker = { rateLimitPolicy.checkCircuitBreaker() },
        resolveNextHop = { routingEngine.resolveNextHop(it) },
        peerPublicKey = securityEngine?.let { se -> { recipientHex: String -> se.peerPublicKey(recipientHex) } },
    )

    private val broadcastPolicyChain = BroadcastPolicyChain(
        bufferCapacity = config.bufferCapacity,
        checkBroadcastRate = { rateLimitPolicy.checkBroadcast() },
        signData = securityEngine?.let { se -> { data: ByteArray -> se.sign(data) } },
        appIdHash = config.appId?.let { AppIdFilter.hash(it) } ?: ByteArray(16),
        localPeerId = transport.localPeerId,
        markAsSeen = { routingEngine.isDuplicate(it) },
    )

    private val gossipCoordinator = GossipCoordinator(
        routingEngine = routingEngine,
        securityEngine = securityEngine,
        diagnosticSink = diagnosticSink,
        localPeerId = transport.localPeerId,
        gossipIntervalMs = config.gossipIntervalMs,
        triggeredUpdateBatchMs = config.triggeredUpdateBatchMs,
        keepaliveIntervalMs = config.keepaliveIntervalMs,
        currentPowerMode = { powerCoordinator.currentMode },
        sendFrame = { peerId, frame -> safeSend(peerId, frame) },
        clock = clock,
    )

    private val peerConnectionCoordinator = PeerConnectionCoordinator(
        routingEngine = routingEngine,
        securityEngine = securityEngine,
        rateLimitPolicy = { rateLimitPolicy.checkHandshake(it) },
        trustStore = trustStore,
        localPeerId = transport.localPeerId,
        protocolVersion = config.protocolVersion,
        isPaused = { pauseManager.isPaused },
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
                        messageId = Uuid.fromByteArray(messageId),
                        chunksAcked = chunksAcked,
                        totalChunks = totalChunks,
                    ),
                    "transferProgress"
                )
            }
            override suspend fun onDeliveryConfirmed(messageId: ByteArray) {
                val key = messageId.toHex()
                outboundTracker.removeNextHop(key)?.let { nextHop ->
                    routingEngine.recordNextHopSuccess(nextHop)
                }
                safeEmit(_deliveryConfirmations, Uuid.fromByteArray(messageId), "deliveryConfirmations")
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
            override fun triggerGossipUpdate() {
                gossipCoordinator.triggerUpdate()
            }
            override fun onOutboundComplete(key: String, messageId: ByteArray) {
                emitHealthUpdate()
            }
        },
    )

    override val localPublicKey: ByteArray? get() = securityEngine?.localPublicKey
    override fun peerPublicKey(peerIdHex: String): ByteArray? = securityEngine?.peerPublicKey(peerIdHex)
    override val broadcastPublicKey: ByteArray? get() = securityEngine?.localBroadcastPublicKey

    override fun start(): Result<Unit> {
        if (started) return Result.success(Unit)

        if (config.requireEncryption && crypto == null) {
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
            return Result.failure(IllegalArgumentException(violations.joinToString("; ")))
        }

        started = true
        val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            diagnosticSink.emit(DiagnosticCode.SEND_FAILED, Severity.ERROR, "Uncaught: ${throwable::class.simpleName}")
        }
        val newScope = CoroutineScope(baseContext + SupervisorJob() + exceptionHandler)
        scope = newScope

        newScope.launch {
            transport.startAdvertisingAndScanning()
        }
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
                            flushPendingMessages(action.peerId.toHex(), newScope)
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
            }
        }
        newScope.launch {
            transport.incomingData.collect { incoming ->
                handleIncomingData(incoming.peerId, incoming.data)
            }
        }

        // Gossip route exchange: periodically broadcast route table to connected peers
        // Also listens for triggered update signals to fire early gossip on significant route changes
        if (config.gossipIntervalMs > 0) {
            newScope.launch {
                gossipCoordinator.runGossipLoop { started }
            }
        }

        // Keepalive loop: send keepalives when topology is stable (no gossip sent recently)
        if (config.keepaliveIntervalMs > 0) {
            newScope.launch {
                gossipCoordinator.runKeepaliveLoop { started }
            }
        }

        return Result.success(Unit)
    }

    override fun stop() {
        started = false
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
                    TransferFailure(Uuid.fromByteArray(hexToBytes(key)), DeliveryOutcome.FAILED_DELIVERY_TIMEOUT)
                )
            }
        }
        // Emit DELIVERY_TIMEOUT for queued paused messages that were never sent
        for ((_, _) in pauseManager.drainSendQueue()) {
            _transferFailures.tryEmit(
                TransferFailure(Uuid.random(), DeliveryOutcome.FAILED_DELIVERY_TIMEOUT)
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
        lastHealthUpdateMs = 0L
    }

    override fun pause() {
        pauseManager.pause()
        scope?.launch { transport.stopAll() }
    }

    override fun resume() {
        val snapshot = pauseManager.resume()
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
        val usedBytes = transferEngine.outboundBufferBytes() + transferEngine.inboundBufferBytes()
        val utilPercent = if (config.bufferCapacity > 0) {
            (usedBytes * 100 / config.bufferCapacity).coerceIn(0, 100)
        } else {
            0
        }
        return MeshHealthSnapshot(
            connectedPeers = routingEngine.peerCount,
            reachablePeers = routingEngine.connectedPeerCount,
            bufferUtilizationPercent = utilPercent,
            activeTransfers = transferEngine.outboundCount,
            powerMode = powerCoordinator.currentMode,
            avgRouteCost = routingEngine.avgCost(),
            relayQueueSize = pauseManager.relayQueueSize,
            effectiveGossipIntervalMs = routingEngine.effectiveGossipInterval(powerCoordinator.currentMode),
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
        val evicted = routingEngine.sweepPresence(seenPeers)
        for (peerId in evicted) {
            diagnosticSink.emit(DiagnosticCode.PEER_EVICTED, Severity.INFO, "peerId=$peerId")
        }
        return evicted
    }

    override fun addRoute(destination: String, nextHop: String, cost: Double, sequenceNumber: UInt) {
        routingEngine.addRoute(destination, nextHop, cost, sequenceNumber)
    }

    override fun sweepStaleTransfers(maxAgeMs: Long): Int {
        val staleKeys = transferEngine.sweepStaleOutbound(maxAgeMs)
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
                    TransferFailure(Uuid.fromByteArray(hexToBytes(key)), DeliveryOutcome.FAILED_ACK_TIMEOUT)
                )
            }
        }
        return staleKeys.size
    }

    override fun sweepStaleReassemblies(maxAgeMs: Long): Int {
        val staleKeys = transferEngine.sweepStaleInbound(maxAgeMs)
        return staleKeys.size
    }

    override fun sweepExpiredPendingMessages(): Int {
        val expired = deliveryPipeline.sweepExpiredPending(config.pendingMessageTtlMs)
        repeat(expired) {
            _transferFailures.tryEmit(
                TransferFailure(Uuid.random(), DeliveryOutcome.FAILED_DELIVERY_TIMEOUT)
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

    override fun broadcast(payload: ByteArray, maxHops: UByte): Result<Uuid> {
        if (!started) throw IllegalStateException("MeshLink not started")
        val s = requireScope()

        return when (val decision = broadcastPolicyChain.evaluate(payload, maxHops)) {
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
                for (peerHex in routingEngine.allPeerIds()) {
                    s.launch { safeSend(hexToBytes(peerHex), decision.encodedFrame) }
                }
                Result.success(Uuid.fromByteArray(decision.messageId))
            }
        }
    }

    override fun send(recipient: ByteArray, payload: ByteArray): Result<Uuid> {
        if (!started) throw IllegalStateException("MeshLink not started")
        return sendLock.withLock { sendInternal(recipient, payload) }
    }

    private fun sendInternal(recipient: ByteArray, payload: ByteArray): Result<Uuid> {
        val s = requireScope()

        return when (val decision = sendPolicyChain.evaluate(recipient, payload.size)) {
            is SendDecision.BufferFull -> {
                val failureId = Uuid.random()
                s.launch {
                    _transferFailures.emit(TransferFailure(failureId, DeliveryOutcome.FAILED_BUFFER_FULL))
                }
                Result.failure(IllegalArgumentException("bufferFull"))
            }

            is SendDecision.Loopback -> {
                val messageId = Uuid.random()
                s.launch {
                    safeEmit(_messages, Message(senderId = recipient, payload = payload), "messages")
                }
                Result.success(messageId)
            }

            is SendDecision.Paused -> {
                pauseManager.queueSend(recipient, payload)
                Result.success(Uuid.random())
            }

            is SendDecision.RateLimited -> {
                diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "recipient=${decision.key}")
                Result.failure(IllegalStateException("Rate limit exceeded"))
            }

            is SendDecision.CircuitBreakerOpen -> {
                Result.failure(IllegalStateException("Circuit breaker open — transport circuit tripped"))
            }

            is SendDecision.Routed -> {
                doRoutedSend(s, recipient, payload, decision.nextHopHex)
            }

            is SendDecision.Unreachable -> {
                if (config.pendingMessageTtlMs > 0) {
                    val recipientHex = recipient.toHex()
                    when (
                        deliveryPipeline.bufferPending(
                            recipientHex,
                            recipient,
                            payload,
                            config.pendingMessageCapacity
                        )
                    ) {
                        is BufferResult.Evicted -> {
                            s.launch {
                                _transferFailures.emit(
                                    TransferFailure(
                                        Uuid.random(),
                                        DeliveryOutcome.FAILED_BUFFER_FULL
                                    )
                                )
                            }
                        }
                        is BufferResult.Buffered -> {}
                    }
                    Result.success(Uuid.random())
                } else {
                    val failureId = Uuid.random()
                    s.launch {
                        _transferFailures.emit(TransferFailure(failureId, DeliveryOutcome.FAILED_NO_ROUTE))
                    }
                    Result.failure(IllegalStateException("No route to unknown peer"))
                }
            }

            is SendDecision.MissingPublicKey -> {
                Result.failure(IllegalStateException("Recipient public key unknown"))
            }

            is SendDecision.Direct -> {
                doSend(s, recipient, payload)
            }
        }
    }

    private fun doSend(s: CoroutineScope, recipient: ByteArray, payload: ByteArray): Result<Uuid> {
        val messageId = Uuid.random().toByteArray()
        val key = messageId.toHex()
        deliveryPipeline.registerOutbound(s, key, config.bufferTtlMs) { expiredKey ->
            _transferFailures.tryEmit(
                TransferFailure(
                    Uuid.fromByteArray(hexToBytes(expiredKey)),
                    DeliveryOutcome.FAILED_DELIVERY_TIMEOUT
                )
            )
            transferEngine.removeOutbound(expiredKey)
            outboundTracker.removeRecipient(expiredKey)
            outboundTracker.removeNextHop(expiredKey)?.let { nextHop ->
                routingEngine.recordNextHopFailure(nextHop)
            }
        }

        // Encrypt payload via security engine
        val recipientHex = recipient.toHex()
        val wirePayload = when (val sr = securityEngine?.seal(recipientHex, payload)) {
            is SealResult.Sealed -> sr.ciphertext
            else -> payload
        }

        val chunkSize = config.mtu - WireCodec.CHUNK_HEADER_SIZE
        val handle = transferEngine.beginSend(key, messageId, wirePayload, chunkSize)
        outboundTracker.registerRecipient(key, recipient)

        // Check buffer pressure after adding transfer
        checkBufferPressure()

        dispatchChunks(s, recipient, handle.chunks, messageId)

        return Result.success(Uuid.fromByteArray(messageId))
    }

    private fun doRoutedSend(
        s: CoroutineScope,
        destination: ByteArray,
        payload: ByteArray,
        nextHopHex: String,
    ): Result<Uuid> {
        val messageId = Uuid.random()
        val key = messageId.toByteArray().toHex()
        outboundTracker.registerNextHop(key, nextHopHex)
        val encoded = WireCodec.encodeRoutedMessage(
            messageId = messageId.toByteArray(),
            origin = transport.localPeerId,
            destination = destination,
            hopLimit = 10u,
            visitedList = listOf(transport.localPeerId),
            payload = payload,
            replayCounter = outboundTracker.advanceReplayCounter(),
        )
        s.launch { safeSend(hexToBytes(nextHopHex), encoded) }
        return Result.success(messageId)
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
        if (rateLimitPolicy.checkNeighborAggregate(peerId.toHex()) is RateLimitResult.Limited) {
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

    internal fun sendNack(peerId: ByteArray, messageId: ByteArray) {
        val peerHex = peerId.toHex()
        if (rateLimitPolicy.checkNack(peerHex) is RateLimitResult.Limited) {
            diagnosticSink.emit(
                DiagnosticCode.RATE_LIMIT_HIT,
                Severity.WARN,
                "NACK rate limit exceeded, peer=$peerHex"
            )
            return
        }
        val frame = WireCodec.encodeNack(messageId)
        scope?.launch { safeSend(peerId, frame) }
    }

    private suspend fun handleIncomingData(fromPeerId: ByteArray, data: ByteArray) {
        messageDispatcher.dispatch(fromPeerId, data)
    }
}
