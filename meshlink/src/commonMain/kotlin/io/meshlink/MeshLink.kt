package io.meshlink

import io.meshlink.config.MeshLinkConfig
import io.meshlink.crypto.CryptoProvider
import io.meshlink.crypto.HandshakePayload
import io.meshlink.crypto.KeyRegistrationResult
import io.meshlink.crypto.ReplayGuard
import io.meshlink.crypto.RotationResult
import io.meshlink.crypto.SealResult
import io.meshlink.crypto.SecurityEngine
import io.meshlink.crypto.TrustStore
import io.meshlink.crypto.UnsealResult
import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticEvent
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.diagnostics.MeshHealthSnapshot
import io.meshlink.diagnostics.Severity
import io.meshlink.model.KeyChangeEvent
import io.meshlink.model.Message
import io.meshlink.model.PeerEvent
import io.meshlink.model.TransferFailure
import io.meshlink.model.TransferProgress
import io.meshlink.power.ModeChangeResult
import io.meshlink.power.PowerCoordinator
import io.meshlink.power.ShedAction
import io.meshlink.protocol.ProtocolVersion
import io.meshlink.util.PauseManager
import io.meshlink.routing.GossipEntry
import io.meshlink.routing.LearnedRoute
import io.meshlink.routing.NextHopResult
import io.meshlink.routing.PresenceState
import io.meshlink.routing.RouteLearnResult
import io.meshlink.routing.RoutingEngine
import io.meshlink.transfer.ChunkAcceptResult
import io.meshlink.transfer.ChunkData
import io.meshlink.transfer.TransferEngine
import io.meshlink.transfer.TransferUpdate
import io.meshlink.transport.BleTransport
import io.meshlink.util.AppIdFilter
import io.meshlink.delivery.AckResult
import io.meshlink.delivery.BufferResult
import io.meshlink.delivery.DeliveryPipeline
import io.meshlink.util.DeliveryOutcome
import io.meshlink.util.createPlatformLock
import io.meshlink.util.currentTimeMillis
import io.meshlink.util.RateLimitPolicy
import io.meshlink.util.RateLimitResult
import io.meshlink.util.withLock
import io.meshlink.util.hexToBytes
import io.meshlink.util.toHex
import io.meshlink.wire.WireCodec
import io.meshlink.wire.RotationAnnouncement
import io.meshlink.wire.RouteUpdateEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
            diagnosticSink.emit(DiagnosticCode.SEND_FAILED, Severity.WARN,
                "callback exception in $label: ${e.message}")
        }
    }

    private fun checkBufferPressure() {
        val usedBytes = (transferEngine.outboundBufferBytes() + transferEngine.inboundBufferBytes()).toLong()
        if (powerCoordinator.shouldWarnBufferPressure(usedBytes, config.bufferCapacity.toLong())) {
            val utilPercent = usedBytes * 100 / config.bufferCapacity
            diagnosticSink.emit(DiagnosticCode.BUFFER_PRESSURE, Severity.WARN,
                "utilization=$utilPercent%, used=$usedBytes, capacity=${config.bufferCapacity}")
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
            val recipientHex = outboundRecipients[key]?.toHex() ?: continue
            if (recipientHex == peerHex && !info.isComplete && !info.isFailed) {
                // Re-send remaining chunks via a dummy ACK to trigger retransmit
                val update = transferEngine.onAck(key, -1, 0uL)
                if (update is TransferUpdate.Progress) {
                    dispatchChunks(s, outboundRecipients[key]!!, update.chunksToSend, info.messageId)
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
    private val transferEngine = TransferEngine(clock = clock)

    // Outbound recipient tracking: messageId hex → recipient peerId
    private val outboundRecipients = mutableMapOf<String, ByteArray>()

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

    // Triggered gossip update signal channel (buffered to coalesce rapid changes)
    private val triggeredUpdateChannel = Channel<Unit>(Channel.CONFLATED)

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
        )
    }

    override val localPublicKey: ByteArray? get() = securityEngine?.localPublicKey
    override fun peerPublicKey(peerIdHex: String): ByteArray? = securityEngine?.peerPublicKey(peerIdHex)
    override val broadcastPublicKey: ByteArray? get() = securityEngine?.localBroadcastPublicKey

    // Replay protection (independent of crypto — counters are not cryptographic)
    private val outboundReplayGuard = ReplayGuard()

    override fun start(): Result<Unit> {
        if (started) return Result.success(Unit)

        val violations = config.validate()
        if (violations.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(violations.joinToString("; ")))
        }

        started = true
        val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            diagnosticSink.emit(DiagnosticCode.SEND_FAILED, Severity.ERROR, "Uncaught: ${throwable.message}")
        }
        val newScope = CoroutineScope(baseContext + SupervisorJob() + exceptionHandler)
        scope = newScope

        newScope.launch {
            transport.startAdvertisingAndScanning()
        }
        newScope.launch {
            transport.advertisementEvents.collect { event ->
                if (!pauseManager.isPaused) {
                    // Negotiate protocol version from advertisement payload
                    val advPayload = event.advertisementPayload
                    if (advPayload.size >= 2) {
                        val remoteMajor = advPayload[0].toInt() and 0xFF
                        val remoteMinor = advPayload[1].toInt() and 0xFF
                        val remoteVersion = ProtocolVersion(remoteMajor, remoteMinor)
                        if (config.protocolVersion.negotiate(remoteVersion) == null) {
                            return@collect // Incompatible version — reject peer
                        }
                    }
                    val isNewPeer = routingEngine.presenceState(event.peerId.toHex()) != PresenceState.CONNECTED
                    routingEngine.peerSeen(event.peerId.toHex())
                    // Extract peer's X25519 public key from advertisement if present
                    if (advPayload.size >= 34 && securityEngine != null) {
                        val newKey = advPayload.copyOfRange(2, 34)
                        val peerHex = event.peerId.toHex()
                        val regResult = securityEngine.registerPeerKey(peerHex, newKey)
                        if (regResult is KeyRegistrationResult.Changed) {
                            _keyChanges.tryEmit(KeyChangeEvent(
                                peerId = event.peerId.copyOf(),
                                previousKey = regResult.previousKey.copyOf(),
                                newKey = newKey.copyOf(),
                            ))
                        }
                    }
                    if (isNewPeer) {
                        safeEmit(_peers, PeerEvent.Discovered(event.peerId), "peers")
                        emitHealthUpdate()
                        // Flush buffered messages and resume interrupted transfers
                        val preExistingTransferKeys = outboundRecipients.keys.toSet()
                        flushPendingMessages(event.peerId.toHex(), newScope)
                        resumeTransfers(event.peerId, newScope, preExistingTransferKeys)
                    }
                    // Initiate Noise XX handshake if crypto enabled
                    // Deterministic tie-breaking: lower peerId initiates
                    if (securityEngine != null && !securityEngine.isHandshakeComplete(event.peerId)) {
                        if (transport.localPeerId.toHex() < event.peerId.toHex()) {
                            // Handshake rate limit: TOFI-pinned peers are exempt
                            val peerHex = event.peerId.toHex()
                            val isPinned = trustStore?.let { ts ->
                                val key = securityEngine.peerPublicKey(peerHex)
                                key != null && ts.verify(peerHex, key) is io.meshlink.crypto.VerifyResult.Trusted
                            } ?: false
                            if (!isPinned && rateLimitPolicy.checkHandshake(peerHex) is RateLimitResult.Limited) {
                                diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN,
                                    "handshake rate limit exceeded, peer=$peerHex")
                            } else {
                                val msg1 = securityEngine.initiateHandshake(event.peerId)
                                if (msg1 != null) {
                                    safeSend(event.peerId, msg1)
                                }
                            }
                        }
                    }
                }
            }
        }
        newScope.launch {
            transport.peerLostEvents.collect { event ->
                routingEngine.markDisconnected(event.peerId.toHex())
                safeEmit(_peers, PeerEvent.Lost(event.peerId), "peers")
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
                while (started) {
                    val triggered = withTimeoutOrNull(routingEngine.effectiveGossipInterval(powerCoordinator.currentMode)) {
                        triggeredUpdateChannel.receive()
                    }
                    if (!started) break
                    if (triggered != null) {
                        // Batch triggered updates: wait a short window to coalesce rapid changes
                        delay(config.triggeredUpdateBatchMs)
                        if (!started) break
                    }
                    broadcastRouteUpdate(isTriggered = triggered != null)
                }
            }
        }

        // Keepalive loop: send keepalives when topology is stable (no gossip sent recently)
        if (config.keepaliveIntervalMs > 0) {
            newScope.launch {
                while (started) {
                    delay(config.keepaliveIntervalMs)
                    if (!started) break
                    val sinceLastGossip = routingEngine.timeSinceLastGossip()
                    if (config.gossipIntervalMs <= 0 || sinceLastGossip >= config.keepaliveIntervalMs) {
                        broadcastKeepalive()
                    }
                }
            }
        }

        return Result.success(Unit)
    }

    override fun stop() {
        started = false
        // Stop BLE transport before cancelling scope
        CoroutineScope(baseContext).launch { transport.stopAll() }
        // Cancel all delivery deadline timers before processing in-flight transfers
        deliveryPipeline.cancelAllDeadlines()
        // Emit DELIVERY_TIMEOUT for all in-flight transfers before clearing
        for (key in outboundRecipients.keys) {
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
        scope?.cancel()
        scope = null
    }

    override fun rotateIdentity(): Result<Unit> {
        val se = securityEngine ?: return Result.failure(IllegalStateException("Crypto not enabled"))
        se.rotateIdentity()
        return Result.success(Unit)
    }

    private fun clearState() {
        transferEngine.clearAll()
        outboundRecipients.clear()
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
        } else 0
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
            outboundRecipients.remove(key)
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
        for (i in 0 until expired) {
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
            level, transferEngine.inboundCount, routingEngine.dedupSize, routingEngine.peerCount
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

        if (payload.size > config.bufferCapacity) {
            return Result.failure(IllegalArgumentException("bufferFull"))
        }

        // Broadcast rate limiting
        if (rateLimitPolicy.checkBroadcast() is RateLimitResult.Limited) {
            diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN,
                "broadcast rate limit exceeded")
            return Result.failure(IllegalStateException("Broadcast rate limit exceeded"))
        }

        val messageId = Uuid.random()
        val appIdHash = config.appId?.let { AppIdFilter.hash(it) } ?: ByteArray(16)
        val msgIdBytes = messageId.toByteArray()
        val remainingHops = maxHops

        // Sign broadcast content with Ed25519 if crypto is available
        // Note: remainingHops is excluded from signed data because relays decrement it
        val signedData = msgIdBytes + transport.localPeerId + appIdHash + payload
        val signed = securityEngine?.sign(signedData)
        val signature = signed?.signature ?: ByteArray(0)

        val encoded = WireCodec.encodeBroadcast(
            messageId = msgIdBytes,
            origin = transport.localPeerId,
            remainingHops = remainingHops,
            appIdHash = appIdHash,
            payload = payload,
            signature = signature,
            signerPublicKey = signed?.signerPublicKey ?: ByteArray(0),
        )
        // Mark as seen so we don't deliver our own broadcast back to ourselves
        routingEngine.isDuplicate(messageId.toByteArray().toHex())
        for (peerHex in routingEngine.allPeerIds()) {
            s.launch { safeSend(hexToBytes(peerHex), encoded) }
        }
        return Result.success(messageId)
    }

    override fun send(recipient: ByteArray, payload: ByteArray): Result<Uuid> {
        if (!started) throw IllegalStateException("MeshLink not started")
        return sendLock.withLock { sendInternal(recipient, payload) }
    }

    private fun sendInternal(recipient: ByteArray, payload: ByteArray): Result<Uuid> {
        val s = requireScope()

        if (payload.size > config.bufferCapacity) {
            val failureId = Uuid.random()
            s.launch {
                _transferFailures.emit(TransferFailure(failureId, DeliveryOutcome.FAILED_BUFFER_FULL))
            }
            return Result.failure(IllegalArgumentException("bufferFull"))
        }

        // Self-send loopback: deliver locally without BLE (bypasses pause)
        if (recipient.contentEquals(transport.localPeerId)) {
            val messageId = Uuid.random()
            s.launch {
                safeEmit(_messages, Message(senderId = recipient, payload = payload), "messages")
            }
            return Result.success(messageId)
        }

        if (pauseManager.isPaused) {
            pauseManager.queueSend(recipient, payload)
            return Result.success(Uuid.random())
        }

        // Rate limit check (per-recipient)
        when (val rl = rateLimitPolicy.checkSend(recipient.toHex())) {
            is RateLimitResult.Allowed -> {}
            is RateLimitResult.Limited -> {
                diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "recipient=${rl.key}")
                return Result.failure(IllegalStateException("Rate limit exceeded"))
            }
        }

        // Circuit breaker check
        if (rateLimitPolicy.checkCircuitBreaker() is RateLimitResult.Limited) {
            return Result.failure(IllegalStateException("Circuit breaker open — transport circuit tripped"))
        }

        // Check if recipient is reachable (directly or via routing)
        when (val hop = routingEngine.resolveNextHop(recipient.toHex())) {
            is NextHopResult.Direct -> {
                // Directly reachable — fall through to doSend below
            }
            is NextHopResult.ViaRoute -> {
                return doRoutedSend(s, recipient, payload, hop.nextHop)
            }
            is NextHopResult.Unreachable -> {
                // Buffer for store-and-forward if enabled
                if (config.pendingMessageTtlMs > 0) {
                    val recipientHex = recipient.toHex()
                    when (deliveryPipeline.bufferPending(recipientHex, recipient, payload, config.pendingMessageCapacity)) {
                        is BufferResult.Evicted -> {
                            s.launch {
                                _transferFailures.emit(TransferFailure(Uuid.random(), DeliveryOutcome.FAILED_BUFFER_FULL))
                            }
                        }
                        is BufferResult.Buffered -> {}
                    }
                    return Result.success(Uuid.random())
                }
                val failureId = Uuid.random()
                s.launch {
                    _transferFailures.emit(TransferFailure(failureId, DeliveryOutcome.FAILED_NO_ROUTE))
                }
                return Result.failure(IllegalStateException("No route to unknown peer"))
            }
        }

        // Require recipient's public key when crypto is enabled
        if (securityEngine != null && securityEngine.peerPublicKey(recipient.toHex()) == null) {
            return Result.failure(IllegalStateException("Recipient public key unknown"))
        }

        return doSend(s, recipient, payload)
    }

    private fun doSend(s: CoroutineScope, recipient: ByteArray, payload: ByteArray): Result<Uuid> {
        val messageId = Uuid.random().toByteArray()
        val key = messageId.toHex()
        deliveryPipeline.registerOutbound(s, key, config.bufferTtlMs) { expiredKey ->
            _transferFailures.tryEmit(TransferFailure(Uuid.fromByteArray(hexToBytes(expiredKey)), DeliveryOutcome.FAILED_DELIVERY_TIMEOUT))
            transferEngine.removeOutbound(expiredKey)
            outboundRecipients.remove(expiredKey)
        }

        // Encrypt payload via security engine
        val recipientHex = recipient.toHex()
        val wirePayload = when (val sr = securityEngine?.seal(recipientHex, payload)) {
            is SealResult.Sealed -> sr.ciphertext
            else -> payload
        }

        val chunkSize = config.mtu - WireCodec.CHUNK_HEADER_SIZE
        val handle = transferEngine.beginSend(key, messageId, wirePayload, chunkSize)
        outboundRecipients[key] = recipient

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
        val encoded = WireCodec.encodeRoutedMessage(
            messageId = messageId.toByteArray(),
            origin = transport.localPeerId,
            destination = destination,
            hopLimit = 10u,
            visitedList = listOf(transport.localPeerId),
            payload = payload,
            replayCounter = outboundReplayGuard.advance(),
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

    private suspend fun broadcastRouteUpdate(isTriggered: Boolean = false) {
        val connectedPeers = routingEngine.connectedPeerIds()
        if (connectedPeers.isEmpty()) return

        for (peerHex in connectedPeers) {
            if (isTriggered && !routingEngine.shouldSendTriggeredUpdate(peerHex, powerCoordinator.currentMode)) {
                continue
            }

            val gossipEntries = routingEngine.prepareGossipEntries(peerHex)
            val entries = gossipEntries.map { ge ->
                RouteUpdateEntry(
                    destination = hexToBytes(ge.destination),
                    cost = ge.cost,
                    sequenceNumber = ge.sequenceNumber,
                    hopCount = ge.hopCount,
                )
            }
            val updateData = if (securityEngine != null) {
                val unsigned = WireCodec.encodeRouteUpdate(transport.localPeerId, entries)
                val signed = securityEngine.sign(unsigned + securityEngine.localBroadcastPublicKey)
                WireCodec.encodeSignedRouteUpdate(transport.localPeerId, entries, signed.signerPublicKey, signed.signature)
            } else {
                WireCodec.encodeRouteUpdate(transport.localPeerId, entries)
            }
            val peerId = hexToBytes(peerHex)
            safeSend(peerId, updateData)

            if (isTriggered) {
                routingEngine.recordTriggeredUpdate(peerHex)
            }
        }

        diagnosticSink.emit(DiagnosticCode.GOSSIP_TRAFFIC_REPORT, Severity.INFO,
            "peers=${connectedPeers.size}, routes=${routingEngine.routeCount}")
        routingEngine.recordGossipSent()
    }

    private suspend fun broadcastKeepalive() {
        val connectedPeers = routingEngine.connectedPeerIds()
        if (connectedPeers.isEmpty()) return
        val nowSeconds = (clock() / 1000).toUInt()
        val frame = WireCodec.encodeKeepalive(nowSeconds)
        for (peerHex in connectedPeers) {
            safeSend(hexToBytes(peerHex), frame)
        }
    }

    private fun handleKeepalive(fromPeerId: ByteArray, @Suppress("UNUSED_PARAMETER") data: ByteArray) {
        // Validate the frame (will throw on malformed data, caught by handleIncomingData)
        WireCodec.decodeKeepalive(data)
        routingEngine.peerSeen(fromPeerId.toHex())
    }

    private suspend fun safeSend(peerId: ByteArray, data: ByteArray) {
        // Per-neighbor aggregate rate limit
        if (rateLimitPolicy.checkNeighborAggregate(peerId.toHex()) is RateLimitResult.Limited) {
            diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN,
                "neighbor aggregate limit exceeded, peer=${peerId.toHex()}")
            return
        }
        try {
            transport.sendToPeer(peerId, data)
        } catch (e: Exception) {
            rateLimitPolicy.recordTransportFailure()
            diagnosticSink.emit(DiagnosticCode.SEND_FAILED, Severity.WARN,
                "peer=${peerId.toHex()}, error=${e.message}")
        }
    }

    internal fun sendNack(peerId: ByteArray, messageId: ByteArray) {
        val peerHex = peerId.toHex()
        if (rateLimitPolicy.checkNack(peerHex) is RateLimitResult.Limited) {
            diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN,
                "NACK rate limit exceeded, peer=$peerHex")
            return
        }
        val frame = WireCodec.encodeNack(messageId)
        scope?.launch { safeSend(peerId, frame) }
    }

    private suspend fun handleIncomingData(fromPeerId: ByteArray, data: ByteArray) {
        if (data.isEmpty()) return
        try {
            when (data[0]) {
                WireCodec.TYPE_HANDSHAKE -> handleHandshake(fromPeerId, data)
                WireCodec.TYPE_CHUNK -> handleChunk(fromPeerId, data)
                WireCodec.TYPE_CHUNK_ACK -> handleChunkAck(data)
                WireCodec.TYPE_BROADCAST -> handleBroadcast(fromPeerId, data)
                WireCodec.TYPE_ROUTE_UPDATE -> handleRouteUpdate(fromPeerId, data)
                WireCodec.TYPE_ROUTED_MESSAGE -> handleRoutedMessage(fromPeerId, data)
                WireCodec.TYPE_DELIVERY_ACK -> handleDeliveryAck(fromPeerId, data)
                WireCodec.TYPE_RESUME_REQUEST -> handleResumeRequest(fromPeerId, data)
                WireCodec.TYPE_KEEPALIVE -> handleKeepalive(fromPeerId, data)
                WireCodec.TYPE_NACK -> { /* NACK received — no-op for now */ }
                WireCodec.TYPE_ROTATION -> handleRotationAnnouncement(fromPeerId, data)
                else -> {
                    diagnosticSink.emit(DiagnosticCode.UNKNOWN_MESSAGE_TYPE, Severity.WARN,
                        "type=0x${data[0].toUByte().toString(16).padStart(2, '0')}, size=${data.size}")
                }
            }
        } catch (e: Exception) {
            diagnosticSink.emit(DiagnosticCode.MALFORMED_DATA, Severity.WARN,
                "type=0x${data[0].toUByte().toString(16)}, size=${data.size}, error=${e.message}")
        }
    }

    private suspend fun handleHandshake(fromPeerId: ByteArray, data: ByteArray) {
        val se = securityEngine ?: return
        val response = se.handleHandshakeMessage(fromPeerId, data)
        if (response != null) {
            safeSend(fromPeerId, response)
        }
    }

    private suspend fun handleRouteUpdate(fromPeerId: ByteArray, data: ByteArray) {
        val update = WireCodec.decodeRouteUpdate(data)
        // If signed, verify the signature before accepting
        if (update.signature != null && update.signerPublicKey != null && securityEngine != null) {
            val signedData = data.copyOfRange(0, data.size - 64)
            if (!securityEngine.verify(update.signerPublicKey, signedData, update.signature)) {
                diagnosticSink.emit(DiagnosticCode.MALFORMED_DATA, Severity.WARN,
                    "route_update signature verification failed from ${fromPeerId.toHex()}")
                return
            }
        }
        val learned = update.entries.map { entry ->
            LearnedRoute(entry.destination.toHex(), entry.cost, entry.sequenceNumber)
        }
        val result = routingEngine.learnRoutes(fromPeerId.toHex(), learned)
        when (result) {
            is RouteLearnResult.SignificantChange -> {
                for (change in result.routeChanges) {
                    diagnosticSink.emit(DiagnosticCode.ROUTE_CHANGED, Severity.INFO,
                        "dest=${change.destination}, oldNextHop=${change.oldNextHop}, newNextHop=${change.newNextHop}")
                }
                if (config.gossipIntervalMs > 0) {
                    triggeredUpdateChannel.trySend(Unit)
                }
            }
            is RouteLearnResult.NoSignificantChange -> {}
        }
    }

    private suspend fun handleChunk(fromPeerId: ByteArray, data: ByteArray) {
        val chunk = WireCodec.decodeChunk(data)
        val key = chunk.messageId.toHex()

        val result = transferEngine.onChunkReceived(key, chunk.sequenceNumber.toInt(), chunk.totalChunks.toInt(), chunk.payload)

        when (result) {
            is ChunkAcceptResult.Ack -> {
                val ack = WireCodec.encodeChunkAck(
                    messageId = chunk.messageId,
                    ackSequence = result.ackSeq.toUShort(),
                    sackBitmask = result.sackBitmask,
                )
                scope?.launch { safeSend(fromPeerId, ack) }
            }
            is ChunkAcceptResult.MessageComplete -> {
                val ack = WireCodec.encodeChunkAck(
                    messageId = chunk.messageId,
                    ackSequence = result.ackSeq.toUShort(),
                    sackBitmask = result.sackBitmask,
                )
                scope?.launch { safeSend(fromPeerId, ack) }

                if (routingEngine.isDuplicate(key)) return

                val decrypted = when (val ur = securityEngine?.unseal(result.reassembledPayload)) {
                    is UnsealResult.Decrypted -> ur.plaintext
                    is UnsealResult.Failed -> {
                        diagnosticSink.emit(DiagnosticCode.DECRYPTION_FAILED, Severity.WARN, "chunk reassembly")
                        ur.originalPayload
                    }
                    is UnsealResult.TooShort -> ur.originalPayload
                    null -> result.reassembledPayload
                }
                safeEmit(_messages, Message(senderId = fromPeerId, payload = decrypted), "messages")
            }
        }
    }

    private suspend fun handleChunkAck(data: ByteArray) {
        val ack = WireCodec.decodeChunkAck(data)
        val key = ack.messageId.toHex()
        val recipient = outboundRecipients[key]

        val update = transferEngine.onAck(key, ack.ackSequence.toInt(), ack.sackBitmask)

        when (update) {
            is TransferUpdate.Complete -> {
                outboundRecipients.remove(key)
                deliveryPipeline.cancelDeadline(key)
                emitHealthUpdate()
                safeEmit(_transferProgress, TransferProgress(
                    messageId = Uuid.fromByteArray(ack.messageId),
                    chunksAcked = update.ackedCount,
                    totalChunks = update.totalChunks,
                ), "transferProgress")
                if (deliveryPipeline.recordFailure(key, DeliveryOutcome.CONFIRMED)) {
                    safeEmit(_deliveryConfirmations, Uuid.fromByteArray(ack.messageId), "deliveryConfirmations")
                }
            }
            is TransferUpdate.Progress -> {
                safeEmit(_transferProgress, TransferProgress(
                    messageId = Uuid.fromByteArray(ack.messageId),
                    chunksAcked = update.ackedCount,
                    totalChunks = update.totalChunks,
                ), "transferProgress")
                if (recipient != null) {
                    dispatchChunks(scope!!, recipient, update.chunksToSend, ack.messageId)
                }
            }
            is TransferUpdate.Unknown -> {
                safeEmit(_deliveryConfirmations, Uuid.fromByteArray(ack.messageId), "deliveryConfirmations")
            }
        }
    }

    private suspend fun handleBroadcast(fromPeerId: ByteArray, data: ByteArray) {
        val broadcast = WireCodec.decodeBroadcast(data)
        val key = broadcast.messageId.toHex()

        // AppId filter: reject broadcasts from a different app
        if (!appIdFilter.accepts(broadcast.appIdHash)) {
            diagnosticSink.emit(DiagnosticCode.APP_ID_REJECTED, Severity.INFO,
                "messageId=$key")
            return
        }

        // Ed25519 signature verification (when crypto is available)
        if (securityEngine != null && broadcast.signature.isNotEmpty()) {
            val signedData = broadcast.messageId + broadcast.origin + broadcast.appIdHash + broadcast.payload
            if (!securityEngine.verify(broadcast.signerPublicKey, signedData, broadcast.signature)) return
        } else if (securityEngine != null && broadcast.signature.isEmpty()) {
            // Crypto enabled but no signature → reject
            return
        }

        // Dedup: reject if we've seen this broadcast before
        if (routingEngine.isDuplicate(key)) return

        // Deliver to self
        safeEmit(_messages, Message(senderId = broadcast.origin, payload = broadcast.payload), "messages")

        // Re-flood to all known peers except sender, if hops remain and not paused
        if (broadcast.remainingHops > 0u && !pauseManager.isPaused) {
            val reflooded = WireCodec.encodeBroadcast(
                messageId = broadcast.messageId,
                origin = broadcast.origin,
                remainingHops = minOf((broadcast.remainingHops - 1u).toUByte(), config.maxHops),
                appIdHash = broadcast.appIdHash,
                payload = broadcast.payload,
                signature = broadcast.signature,
                signerPublicKey = broadcast.signerPublicKey,
            )
            val senderHex = fromPeerId.toHex()
            val s = scope ?: return
            for (peerHex in routingEngine.allPeerIds()) {
                if (peerHex != senderHex) {
                    s.launch { safeSend(hexToBytes(peerHex), reflooded) }
                }
            }
        }
    }

    private suspend fun handleRoutedMessage(fromPeerId: ByteArray, data: ByteArray) {
        val routed = WireCodec.decodeRoutedMessage(data)
        val key = routed.messageId.toHex()

        if (routingEngine.isDuplicate(key)) return

        // Replay guard: check counter if present (counter=0 means unprotected/legacy)
        if (routed.replayCounter > 0u) {
            if (!deliveryPipeline.checkReplay(routed.origin.toHex(), routed.replayCounter)) {
                diagnosticSink.emit(DiagnosticCode.REPLAY_REJECTED, Severity.WARN,
                    "messageId=$key, origin=${routed.origin.toHex()}, counter=${routed.replayCounter}")
                return
            }
        }

        // Loop detection: drop if we are already in the visited list
        val selfId = transport.localPeerId
        if (routed.visitedList.any { it.contentEquals(selfId) }) {
            diagnosticSink.emit(DiagnosticCode.LOOP_DETECTED, Severity.WARN,
                "messageId=$key, origin=${routed.origin.toHex()}")
            return
        }

        // If we are the destination, deliver locally and send delivery ACK back
        if (routed.destination.contentEquals(transport.localPeerId)) {
            // Inbound rate limiting: check per-sender rate
            if (config.inboundRateLimitPerSenderPerMinute > 0) {
                val originHex = routed.origin.toHex()
                if (!deliveryPipeline.checkInboundRate(originHex, config.inboundRateLimitPerSenderPerMinute)) {
                    diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN,
                        "inbound, origin=$originHex")
                    return
                }
            }
            // Decrypt via security engine
            val deliveredPayload = when (val ur = securityEngine?.unseal(routed.payload)) {
                is UnsealResult.Decrypted -> ur.plaintext
                is UnsealResult.Failed -> {
                    diagnosticSink.emit(DiagnosticCode.DECRYPTION_FAILED, Severity.WARN, "routed message")
                    ur.originalPayload
                }
                is UnsealResult.TooShort -> ur.originalPayload
                null -> routed.payload
            }
            safeEmit(_messages, Message(senderId = routed.origin, payload = deliveredPayload), "messages")
            // Send signed delivery ACK back toward origin
            val signed = securityEngine?.sign(routed.messageId + transport.localPeerId)
            val ack = WireCodec.encodeDeliveryAck(
                routed.messageId, transport.localPeerId,
                signature = signed?.signature ?: ByteArray(0),
                signerPublicKey = signed?.signerPublicKey ?: ByteArray(0),
            )
            scope?.launch { safeSend(fromPeerId, ack) }
            return
        }

        // Otherwise relay: decrement hop limit, add self to visited, forward
        if (routed.hopLimit <= 0u) {
            diagnosticSink.emit(DiagnosticCode.HOP_LIMIT_EXCEEDED, Severity.INFO,
                "messageId=$key, origin=${routed.origin.toHex()}")
            return
        }

        val destHex = routed.destination.toHex()
        val nextHop = when (val hop = routingEngine.resolveNextHop(destHex)) {
            is NextHopResult.Direct -> routed.destination
            is NextHopResult.ViaRoute -> hexToBytes(hop.nextHop)
            is NextHopResult.Unreachable -> return
        }
        val originHex = routed.origin.toHex()
        val neighborHex = nextHop.toHex()
        if (rateLimitPolicy.checkSenderNeighborRelay(originHex, neighborHex) is RateLimitResult.Limited) {
            diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN,
                "sender-neighbor relay limit exceeded, origin=$originHex, neighbor=$neighborHex")
            return
        }

        // Record reverse path for delivery ACK relay
        deliveryPipeline.recordReversePath(key, fromPeerId)

        val newVisited = routed.visitedList + listOf(transport.localPeerId)
        val relayed = WireCodec.encodeRoutedMessage(
            messageId = routed.messageId,
            origin = routed.origin,
            destination = routed.destination,
            hopLimit = (routed.hopLimit - 1u).toUByte(),
            visitedList = newVisited,
            payload = routed.payload,
            replayCounter = routed.replayCounter,
        )

        // Forward to destination if directly known, otherwise best route
        if (pauseManager.isPaused) {
            pauseManager.queueRelay(nextHop, relayed)
        } else {
            scope?.launch { safeSend(nextHop, relayed) }
        }
    }

    private suspend fun handleResumeRequest(fromPeerId: ByteArray, data: ByteArray) {
        // Resume logic will be implemented in p3-byte-offset-resume
        val request = WireCodec.decodeResumeRequest(data)
        diagnosticSink.emit(DiagnosticCode.TRANSPORT_MODE_CHANGED, Severity.INFO,
            "resume_request: messageId=${request.messageId.toHex()}, bytesReceived=${request.bytesReceived}")
    }

    private suspend fun handleDeliveryAck(fromPeerId: ByteArray, data: ByteArray) {
        val ack = WireCodec.decodeDeliveryAck(data)
        val key = ack.messageId.toHex()

        // Ed25519 signature verification for delivery ACKs
        if (securityEngine != null && ack.signature.isNotEmpty()) {
            val signedData = ack.messageId + ack.recipientId
            if (!securityEngine.verify(ack.signerPublicKey, signedData, ack.signature)) return
        } else if (securityEngine != null && ack.signature.isEmpty()) {
            return
        }

        when (val result = deliveryPipeline.processAck(key)) {
            is AckResult.Confirmed -> {
                safeEmit(_deliveryConfirmations, Uuid.fromByteArray(ack.messageId), "deliveryConfirmations")
            }
            is AckResult.ConfirmedAndRelay -> {
                safeEmit(_deliveryConfirmations, Uuid.fromByteArray(ack.messageId), "deliveryConfirmations")
                scope?.launch { safeSend(result.relayTo, data) }
            }
            is AckResult.Late -> {
                diagnosticSink.emit(DiagnosticCode.LATE_DELIVERY_ACK, Severity.INFO, "messageId=$key")
            }
        }
    }

    private fun handleRotationAnnouncement(fromPeerId: ByteArray, data: ByteArray) {
        val se = securityEngine ?: return
        val msg = RotationAnnouncement.decode(data)
        val peerHex = fromPeerId.toHex()
        when (val result = se.handleRotationAnnouncement(peerHex, msg)) {
            is RotationResult.Accepted -> _keyChanges.tryEmit(result.event)
            is RotationResult.Rejected -> diagnosticSink.emit(DiagnosticCode.MALFORMED_DATA, Severity.WARN,
                "rotation announcement signature verification failed from $peerHex")
            is RotationResult.UnknownPeer -> { /* ignore rotation from unknown peer */ }
        }
    }
}

