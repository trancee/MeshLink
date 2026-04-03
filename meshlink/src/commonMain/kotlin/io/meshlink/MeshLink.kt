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
import io.meshlink.diagnostics.MeshHealthReporter
import io.meshlink.diagnostics.MeshHealthSnapshot
import io.meshlink.diagnostics.Severity
import io.meshlink.diagnostics.SweepOperations
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
import io.meshlink.peer.PeerQueryService
import io.meshlink.power.ModeChangeResult
import io.meshlink.power.PowerCoordinator
import io.meshlink.power.PowerMode
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
import io.meshlink.util.DeliveryOutcome
import io.meshlink.util.PauseManager
import io.meshlink.util.PayloadEnvelope
import io.meshlink.util.PlatformLock
import io.meshlink.util.RateLimitPolicy
import io.meshlink.util.RateLimitResult
import io.meshlink.util.currentTimeMillis
import io.meshlink.util.toHex
import io.meshlink.util.toKey
import io.meshlink.util.withLock
import io.meshlink.wire.AdvertisementCodec
import io.meshlink.wire.NackReason
import io.meshlink.wire.WireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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
                val update = transferEngine.onAck(key, -1, 0uL, 0uL)
                if (update is TransferUpdate.Progress) {
                    dispatchChunks(s, outboundTracker.recipient(key)!!, update.chunksToSend, info.messageId)
                }
            }
        }
    }

    /**
     * Library lifecycle state machine per design §10.
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

    // ── Internal engines ────────────────────────────────────────

    private val transferEngine = TransferEngine(
        clock = clock,
        maxConcurrentInboundSessions = config.maxConcurrentInboundSessions,
    )

    private val outboundTracker = OutboundTracker()

    private val pauseManager = PauseManager(
        sendQueueCapacity = config.pendingMessageCapacity,
        relayQueueCapacity = config.relayQueueCapacity,
    )

    private val routingEngine = RoutingEngine(
        localPeerId = transport.localPeerId.toKey(),
        dedupCapacity = config.dedupCapacity,
        routeCacheTtlMillis = config.routeCacheTtlMillis,
        clock = clock,
    )

    private val diagnosticSink = DiagnosticSink(
        bufferCapacity = config.diagnosticBufferCapacity,
        clock = clock,
        enabled = config.diagnosticsEnabled,
    )

    private val deliveryPipeline = DeliveryPipeline(
        clock = clock,
        tombstoneWindowMillis = config.tombstoneWindowMillis,
        diagnosticSink = diagnosticSink,
    )

    private val powerCoordinator = PowerCoordinator(clock = clock)

    private val appIdFilter = AppIdFilter(config.appId)

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

    // ── Extracted helpers ───────────────────────────────────────

    private val payloadEnvelope = PayloadEnvelope(
        compressor = if (config.compressionEnabled) io.meshlink.util.Compressor() else null,
        compressionMinBytes = config.compressionMinBytes,
        compressionEnabled = config.compressionEnabled,
    )

    private val peerQueryService = PeerQueryService(
        routingEngine = routingEngine,
        securityEngine = securityEngine,
    )

    private val healthReporter = MeshHealthReporter(
        transferEngine = transferEngine,
        routingEngine = routingEngine,
        powerCoordinator = powerCoordinator,
        pauseManager = pauseManager,
        config = config,
        diagnosticSink = diagnosticSink,
        clock = clock,
    )

    override val meshHealthFlow: StateFlow<MeshHealthSnapshot> = healthReporter.meshHealthFlow

    private val sweepOps = SweepOperations(
        routingEngine = routingEngine,
        transferEngine = transferEngine,
        outboundTracker = outboundTracker,
        deliveryPipeline = deliveryPipeline,
        diagnosticSink = diagnosticSink,
        powerCoordinator = powerCoordinator,
        pauseManager = pauseManager,
        config = config,
        transferFailures = _transferFailures,
    )

    // ── Policy chains ───────────────────────────────────────────

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
        generateMessageId = { MessageId.random().bytes },
    )

    private val routeCoordinator = RouteCoordinator(
        routingEngine = routingEngine,
        diagnosticSink = diagnosticSink,
        keepaliveIntervalMillis = config.keepaliveIntervalMillis,
        sendFrame = { peerId, frame -> safeSend(peerId, frame) },
        clock = clock,
    )

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
        localMeshHash = AdvertisementCodec.meshHash(config.appId),
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
        sink = MeshLinkDispatchSink(),
        unwrapPayload = payloadEnvelope::unwrap,
    )

    // ── Named DispatchSink (was anonymous object) ───────────────

    private inner class MeshLinkDispatchSink : DispatchSink {
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
            healthReporter.emitHealthUpdate()
        }
    }

    // ── Public API ──────────────────────────────────────────────

    override val localPublicKey: ByteArray? get() = securityEngine?.localPublicKey
    override fun peerPublicKey(peerIdHex: String): ByteArray? = peerQueryService.peerPublicKey(peerIdHex)
    override val broadcastPublicKey: ByteArray? get() = securityEngine?.localBroadcastPublicKey
    override fun peerDetail(peerIdHex: String): PeerDetail? = peerQueryService.peerDetail(peerIdHex)
    override fun allPeerDetails(): List<PeerDetail> = peerQueryService.allPeerDetails()

    private fun encodeAdvertisementPayload(): ByteArray {
        val keyHash = securityEngine?.let { se ->
            crypto?.sha256(se.localPublicKey)
        } ?: ByteArray(AdvertisementCodec.KEY_HASH_SIZE)
        return AdvertisementCodec.encode(
            versionMajor = config.protocolVersion.major,
            versionMinor = config.protocolVersion.minor,
            powerMode = powerCoordinator.currentMode.ordinal,
            publicKeyHash = keyHash,
            meshHash = AdvertisementCodec.meshHash(config.appId),
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
        config.customPowerMode?.let { powerCoordinator.setCustomPowerMode(it) }
        securityEngine?.localPublicKey?.let {
            routingEngine.registerPublicKey(transport.localPeerId.toKey(), it)
        }

        val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            diagnosticSink.emit(
                DiagnosticCode.SEND_FAILED,
                Severity.ERROR,
                "Uncaught: ${throwable::class.simpleName}"
            )
        }
        val newScope = CoroutineScope(baseContext + SupervisorJob() + exceptionHandler)
        scope = newScope

        transport.advertisementServiceData = encodeAdvertisementPayload()
        launchEventCollectors(newScope)

        if (config.keepaliveIntervalMillis > 0) {
            newScope.launch {
                routeCoordinator.runKeepaliveLoop { lifecycleState == LifecycleState.RUNNING }
            }
        }

        return Result.success(Unit)
    }

    /** Launch transport event collection coroutines. */
    private fun launchEventCollectors(s: CoroutineScope) {
        s.launch {
            transport.advertisementEvents.collect { event ->
                handleAdvertisement(s, event.peerId, event.advertisementPayload)
            }
        }
        s.launch {
            transport.peerLostEvents.collect { event ->
                handlePeerLost(s, event.peerId)
            }
        }
        s.launch {
            transport.incomingData.collect { incoming ->
                messageDispatcher.dispatch(incoming.peerId, incoming.data)
            }
        }
        s.launch {
            transport.startAdvertisingAndScanning()
        }
    }

    private suspend fun handleAdvertisement(s: CoroutineScope, peerId: ByteArray, advPayload: ByteArray) {
        when (val action = peerConnectionCoordinator.onAdvertisementReceived(peerId, advPayload)) {
            is PeerConnectionAction.Rejected,
            is PeerConnectionAction.Skipped -> { /* no-op */ }
            is PeerConnectionAction.PeerUpdate -> {
                action.keyChangeEvent?.let { _keyChanges.tryEmit(it) }
                if (action.isNewPeer) {
                    safeEmit(_peers, PeerEvent.Found(action.peerId), "peers")
                    healthReporter.emitHealthUpdate()
                    val peerKey = action.peerId.toKey()
                    securityEngine?.peerPublicKey(peerKey)?.let { pubKey ->
                        routingEngine.registerPublicKey(peerKey, pubKey)
                    }
                    val preExistingTransferKeys = outboundTracker.allRecipientKeys()
                    flushPendingMessages(action.peerId.toKey(), s)
                    resumeTransfers(action.peerId, s, preExistingTransferKeys)
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

    private suspend fun handlePeerLost(s: CoroutineScope, peerId: ByteArray) {
        val action = peerConnectionCoordinator.onPeerLost(peerId)
        safeEmit(_peers, PeerEvent.Lost(action.peerId), "peers")
        healthReporter.emitHealthUpdate()
        val lostKey = peerId.toKey()
        val retraction = routingEngine.buildRetraction(lostKey)
        for (connectedId in routingEngine.connectedPeerIds()) {
            if (connectedId != lostKey) {
                s.launch { safeSend(connectedId.bytes, retraction) }
            }
        }
    }

    override fun stop() {
        when (lifecycleState) {
            LifecycleState.UNINITIALIZED, LifecycleState.STOPPED, LifecycleState.TERMINAL -> return
            LifecycleState.RUNNING, LifecycleState.PAUSED, LifecycleState.RECOVERABLE -> { /* proceed */ }
        }
        lifecycleState = LifecycleState.STOPPED
        scope?.cancel()
        runBlocking(kotlinx.coroutines.Dispatchers.Default) {
            withTimeoutOrNull(5_000L) { transport.stopAll() }
        }
        deliveryPipeline.cancelAllDeadlines()
        for (key in outboundTracker.allRecipientKeys()) {
            if (deliveryPipeline.recordFailure(key, DeliveryOutcome.FAILED_DELIVERY_TIMEOUT)) {
                _transferFailures.tryEmit(
                    TransferFailure(MessageId.fromBytes(key.bytes), DeliveryOutcome.FAILED_DELIVERY_TIMEOUT)
                )
            }
        }
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
        healthReporter.resetThrottle()
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
        for (relay in snapshot.pendingRelays) {
            s.launch { safeSend(relay.nextHop, relay.frame) }
        }
    }

    // ── Delegated diagnostics / sweep / health ──────────────────

    override fun meshHealth(): MeshHealthSnapshot = healthReporter.snapshot()
    override fun drainDiagnostics(): List<DiagnosticEvent> {
        val events = mutableListOf<DiagnosticEvent>()
        @Suppress("DEPRECATION")
        diagnosticSink.drainTo(events)
        return events
    }
    override val diagnosticEvents: Flow<DiagnosticEvent> = diagnosticSink.events
    override fun sweep(seenPeers: Set<String>): Set<String> = sweepOps.sweep(seenPeers)

    override fun addRoute(destination: String, nextHop: String, cost: Double, sequenceNumber: UInt) {
        routingEngine.addRoute(
            ByteArrayKey(io.meshlink.util.hexToBytes(destination)),
            ByteArrayKey(io.meshlink.util.hexToBytes(nextHop)),
            cost,
            sequenceNumber,
        )
    }

    override fun sweepStaleTransfers(maxAgeMillis: Long): Int = sweepOps.sweepStaleTransfers(maxAgeMillis)
    override fun sweepStaleReassemblies(maxAgeMillis: Long): Int = sweepOps.sweepStaleReassemblies(maxAgeMillis)
    override fun sweepExpiredPendingMessages(): Int = sweepOps.sweepExpiredPendingMessages()

    override fun updateBattery(batteryPercent: Int, isCharging: Boolean) {
        when (powerCoordinator.updateBattery(batteryPercent, isCharging)) {
            is ModeChangeResult.Changed -> healthReporter.emitHealthUpdate()
            is ModeChangeResult.Unchanged -> {}
        }
    }

    override fun setCustomPowerMode(mode: PowerMode?) {
        when (powerCoordinator.setCustomPowerMode(mode)) {
            is ModeChangeResult.Changed -> healthReporter.emitHealthUpdate()
            is ModeChangeResult.Unchanged -> {}
        }
    }

    override fun shedMemoryPressure(): List<String> = sweepOps.shedMemoryPressure()

    // ── Broadcast / Send ────────────────────────────────────────

    override fun broadcast(payload: ByteArray, maxHops: UByte, priority: Byte): Result<MessageId> {
        checkRunningOrPaused()
        val s = requireScope()
        val effectiveHops = minOf(maxHops, config.broadcastTtl)
        return when (val decision = broadcastPolicyChain.evaluate(payload, effectiveHops, priority)) {
            is BroadcastDecision.BufferFull ->
                Result.failure(IllegalArgumentException("bufferFull"))
            is BroadcastDecision.RateLimited -> {
                diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "broadcast rate limit exceeded")
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

    override fun send(recipient: ByteArray, payload: ByteArray, priority: Byte): Result<MessageId> {
        checkRunningOrPaused()
        return sendLock.withLock { sendInternal(recipient, payload, priority) }
    }

    private fun sendInternal(recipient: ByteArray, payload: ByteArray, priority: Byte = 0): Result<MessageId> {
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
                s.launch { safeEmit(_messages, Message(senderId = recipient, payload = payload), "messages") }
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
            is SendDecision.CircuitBreakerOpen ->
                Result.failure(IllegalStateException("Circuit breaker open — transport circuit tripped"))
            is SendDecision.Routed ->
                doRoutedSend(s, recipient, payload, decision.nextHopId, priority)
            is SendDecision.Unreachable -> {
                val destKey = recipient.toKey()
                val pending = pendingMessages.getOrPut(destKey) { mutableListOf() }
                pending.add(PendingSend(recipient, payload))
                s.launch { routeCoordinator.broadcastKeepalive() }
                Result.success(MessageId.random())
            }
            is SendDecision.MissingPublicKey ->
                Result.failure(IllegalStateException("Recipient public key unknown"))
            is SendDecision.Direct ->
                doSend(s, recipient, payload, priority)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun doSend(
        s: CoroutineScope,
        recipient: ByteArray,
        payload: ByteArray,
        priority: Byte = 0,
    ): Result<MessageId> {
        val messageId = MessageId.random().bytes
        val key = messageId.toKey()
        deliveryPipeline.registerOutbound(s, key, config.deliveryTimeoutMillis) { expiredKey ->
            _transferFailures.tryEmit(
                TransferFailure(MessageId.fromBytes(expiredKey.bytes), DeliveryOutcome.FAILED_DELIVERY_TIMEOUT)
            )
            transferEngine.removeOutbound(expiredKey)
            outboundTracker.removeRecipient(expiredKey)
            outboundTracker.removeNextHop(expiredKey)?.let { nextHop ->
                routingEngine.recordNextHopFailure(nextHop)
            }
        }

        val envelopedPayload = payloadEnvelope.wrap(payload)

        val recipientId = recipient.toKey()
        val wirePayload = when (val sr = securityEngine?.seal(recipientId, envelopedPayload)) {
            is SealResult.Sealed -> sr.ciphertext
            else -> envelopedPayload
        }

        val chunkSize = config.mtu - WireCodec.CHUNK_HEADER_SIZE_FIRST
        val handle = transferEngine.beginSend(key, messageId, wirePayload, chunkSize)
        outboundTracker.registerRecipient(key, recipient)

        healthReporter.checkBufferPressure()
        dispatchChunks(s, recipient, handle.chunks, messageId)

        return Result.success(MessageId.fromBytes(messageId))
    }

    private fun doRoutedSend(
        s: CoroutineScope,
        destination: ByteArray,
        payload: ByteArray,
        nextHopId: ByteArrayKey,
        priority: Byte = 0,
    ): Result<MessageId> {
        val messageId = MessageId.random()
        val key = messageId.bytes.toKey()
        outboundTracker.registerNextHop(key, nextHopId)

        val envelopedPayload = payloadEnvelope.wrap(payload)

        val recipientId = destination.toKey()
        val wirePayload = when (val sr = securityEngine?.seal(recipientId, envelopedPayload)) {
            is SealResult.Sealed -> sr.ciphertext
            else -> envelopedPayload
        }

        val encoded = WireCodec.encodeRoutedMessage(
            messageId = messageId.bytes,
            origin = transport.localPeerId,
            destination = destination,
            hopLimit = 10u,
            visitedList = listOf(transport.localPeerId),
            payload = wirePayload,
            replayCounter = outboundTracker.advanceReplayCounter(),
            priority = priority,
        )
        s.launch { safeSend(nextHopId.bytes, encoded) }
        return Result.success(messageId)
    }

    // Keep internal visibility for CompressionIntegrationTest
    internal fun wrapPayloadEnvelope(payload: ByteArray): ByteArray = payloadEnvelope.wrap(payload)
    internal fun unwrapPayloadEnvelope(envelope: ByteArray): ByteArray = payloadEnvelope.unwrap(envelope)

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
