package io.meshlink

import io.meshlink.config.MeshLinkConfig
import io.meshlink.crypto.CryptoKeyPair
import io.meshlink.crypto.CryptoProvider
import io.meshlink.crypto.NoiseKSealer
import io.meshlink.crypto.PeerHandshakeManager
import io.meshlink.crypto.ReplayGuard
import io.meshlink.crypto.TrustStore
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
import io.meshlink.power.MemoryPressure
import io.meshlink.power.PowerModeEngine
import io.meshlink.power.TieredShedder
import io.meshlink.protocol.ProtocolVersion
import io.meshlink.routing.DedupSet
import io.meshlink.routing.PresenceTracker
import io.meshlink.routing.RoutingTable
import io.meshlink.transfer.SackTracker
import io.meshlink.transfer.TransferSession
import io.meshlink.transport.BleTransport
import io.meshlink.util.AppIdFilter
import io.meshlink.util.CircuitBreaker
import io.meshlink.util.DeliveryOutcome
import io.meshlink.util.DeliveryDeadlineTimer
import io.meshlink.util.DeliveryTracker
import io.meshlink.util.TombstoneSet
import io.meshlink.util.createPlatformLock
import io.meshlink.util.currentTimeMillis
import io.meshlink.util.RateLimiter
import io.meshlink.util.withLock
import io.meshlink.util.hexToBytes
import io.meshlink.util.toHex
import io.meshlink.wire.WireCodec
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

    private val rateLimiter: RateLimiter? =
        if (config.rateLimitMaxSends > 0) RateLimiter(config.rateLimitMaxSends, config.rateLimitWindowMs, clock) else null

    private val circuitBreaker: CircuitBreaker? =
        if (config.circuitBreakerMaxFailures > 0) CircuitBreaker(config.circuitBreakerMaxFailures, config.circuitBreakerWindowMs, config.circuitBreakerCooldownMs, clock) else null

    private val broadcastRateLimiter: RateLimiter? =
        if (config.broadcastRateLimitPerMinute > 0) RateLimiter(config.broadcastRateLimitPerMinute, 60_000L, clock) else null

    private val handshakeRateLimiter: RateLimiter? =
        if (config.handshakeRateLimitPerSec > 0) RateLimiter(config.handshakeRateLimitPerSec, 1_000L, clock) else null

    private val nackRateLimiter: RateLimiter? =
        if (config.nackRateLimitPerSec > 0) RateLimiter(config.nackRateLimitPerSec, 1_000L, clock) else null

    private val neighborAggregateRateLimiter: RateLimiter? =
        if (config.neighborAggregateLimitPerMin > 0) RateLimiter(config.neighborAggregateLimitPerMin, 60_000L, clock) else null

    private val senderNeighborRateLimiter: RateLimiter? =
        if (config.senderNeighborLimitPerMin > 0) RateLimiter(config.senderNeighborLimitPerMin, 60_000L, clock) else null

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

    private fun emitHealthUpdate() {
        _meshHealthFlow.value = meshHealth()
    }

    private fun checkBufferPressure() {
        if (config.bufferCapacity <= 0) return
        val usedBytes = outboundTransfers.values.sumOf { t ->
            t.chunks.sumOf { it.size }
        } + reassembly.values.sumOf { s ->
            s.chunks.values.sumOf { it.size }
        }
        val utilPercent = usedBytes * 100 / config.bufferCapacity
        if (utilPercent >= 80) {
            diagnosticSink.emit(DiagnosticCode.BUFFER_PRESSURE, Severity.WARN,
                "utilization=$utilPercent%, used=$usedBytes, capacity=${config.bufferCapacity}")
        }
    }

    private fun flushPendingMessages(peerHex: String, s: CoroutineScope) {
        val messages = pendingMessages.remove(peerHex) ?: return
        val now = clock()
        val recipientBytes = hexToBytes(peerHex)
        for (msg in messages) {
            if (config.pendingMessageTtlMs > 0 && (now - msg.enqueueTimeMs) >= config.pendingMessageTtlMs) {
                continue // TTL expired
            }
            doSend(s, recipientBytes, msg.payload)
        }
    }

    private fun resumeTransfers(peerId: ByteArray, s: CoroutineScope, eligibleKeys: Set<String>) {
        val peerHex = peerId.toHex()
        for (key in eligibleKeys) {
            val transfer = outboundTransfers[key] ?: continue
            if (transfer.recipient.toHex() == peerHex && !transfer.session.isComplete() && !transfer.session.isFailed()) {
                sendChunks(s, key)
            }
        }
    }

    private var started = false
    private var paused = false
    private var scope: CoroutineScope? = null
    private val baseContext = coroutineContext

    private fun requireScope(): CoroutineScope =
        scope ?: throw IllegalStateException("MeshLink not started")

    // Reassembly buffer: messageId hex → (sack tracker, received chunks map)
    private val reassembly = mutableMapOf<String, ReassemblyState>()

    // Outbound transfer sessions: messageId hex → (session, chunks data, recipient)
    private val outboundTransfers = mutableMapOf<String, OutboundTransfer>()

    // Queued sends while paused: (recipient, payload)
    private val pauseQueue = mutableListOf<Pair<ByteArray, ByteArray>>()

    // Queued relay messages while paused: (nextHop, encodedFrame)
    private val relayQueue = mutableListOf<Pair<ByteArray, ByteArray>>()

    // Peer presence tracking (replaces simple knownPeers set)
    private val presenceTracker = PresenceTracker()

    // Deduplication of fully reassembled messages
    private val dedup = DedupSet(capacity = config.dedupCapacity)

    // Delivery tracking for exactly-once terminal signals
    private val deliveryTracker = DeliveryTracker()

    // Tombstone set for detecting late ACKs after delivery resolution
    private val tombstoneSet = TombstoneSet(windowMs = config.tombstoneWindowMs, clock = clock)

    // Diagnostic event sink
    private val diagnosticSink = DiagnosticSink(bufferCapacity = config.diagnosticBufferCapacity, clock = clock)

    // Delivery deadline timer: fires DELIVERY_TIMEOUT diagnostic when bufferTtlMs expires
    private val deliveryDeadlineTimer = DeliveryDeadlineTimer(
        diagnosticSink = diagnosticSink,
        deliveryTracker = deliveryTracker,
    )

    // Routing table for multi-hop relay
    private val routingTable = RoutingTable()

    // Track previous next-hop per destination for poison reverse
    private val previousNextHop = mutableMapOf<String, String>()

    // Triggered gossip update signal channel (buffered to coalesce rapid changes)
    private val triggeredUpdateChannel = Channel<Unit>(Channel.CONFLATED)

    // Per-neighbor rate limit: last triggered update timestamp per peer hex
    private val lastTriggeredUpdateTime = mutableMapOf<String, Long>()

    // Timestamp of last gossip route update sent (used for keepalive scheduling)
    private var lastGossipSentMs: Long = 0L

    // Reverse path for delivery ACK relay: messageId hex → fromPeerId
    private val routedMsgSources = mutableMapOf<String, ByteArray>()

    // Power mode engine for battery-aware operation
    private val powerModeEngine = PowerModeEngine(clock = clock)
    private var currentPowerMode = "PERFORMANCE"

    // App ID filter for broadcast isolation
    private val appIdFilter = AppIdFilter(config.appId)

    // Replay protection for routed messages
    private val outboundReplayGuard = ReplayGuard()
    private val inboundReplayGuards = mutableMapOf<String, ReplayGuard>()

    // E2E encryption
    private var localKeyPair: CryptoKeyPair? = crypto?.generateX25519KeyPair()
    private var sealer: NoiseKSealer? = crypto?.let { NoiseKSealer(it) }
    override val localPublicKey: ByteArray? get() = localKeyPair?.publicKey
    override fun peerPublicKey(peerIdHex: String): ByteArray? = peerPublicKeys[peerIdHex]
    private val peerPublicKeys = mutableMapOf<String, ByteArray>()

    // Ed25519 signing for broadcasts and delivery ACKs
    private var broadcastKeyPair: CryptoKeyPair? = crypto?.generateEd25519KeyPair()
    override val broadcastPublicKey: ByteArray? get() = broadcastKeyPair?.publicKey

    // Noise XX handshake manager for hop-by-hop encryption
    private val handshakeManager: PeerHandshakeManager? = crypto?.let {
        PeerHandshakeManager(it, crypto.generateX25519KeyPair())
    }

    // Inbound rate limiting: per-sender message count within sliding window
    private val inboundRateCounts = mutableMapOf<String, MutableList<Long>>()

    // Store-and-forward: buffer messages for unreachable peers
    private data class PendingMessage(val recipient: ByteArray, val payload: ByteArray, val enqueueTimeMs: Long)
    private val pendingMessages = mutableMapOf<String, MutableList<PendingMessage>>()

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
                if (!paused) {
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
                    presenceTracker.peerSeen(event.peerId.toHex())
                    // Extract peer's X25519 public key from advertisement if present
                    if (advPayload.size >= 34) {
                        val newKey = advPayload.copyOfRange(2, 34)
                        val peerHex = event.peerId.toHex()
                        val previousKey = peerPublicKeys[peerHex]
                        if (previousKey != null && !previousKey.contentEquals(newKey)) {
                            _keyChanges.tryEmit(KeyChangeEvent(
                                peerId = event.peerId.copyOf(),
                                previousKey = previousKey.copyOf(),
                                newKey = newKey.copyOf(),
                            ))
                        }
                        peerPublicKeys[peerHex] = newKey
                    }
                    _peers.emit(PeerEvent.Discovered(event.peerId))
                    emitHealthUpdate()
                    // Initiate Noise XX handshake if crypto enabled
                    // Deterministic tie-breaking: lower peerId initiates
                    if (handshakeManager != null && !handshakeManager.isComplete(event.peerId)) {
                        if (transport.localPeerId.toHex() < event.peerId.toHex()) {
                            // Handshake rate limit: TOFI-pinned peers are exempt
                            val peerHex = event.peerId.toHex()
                            val isPinned = trustStore?.let { ts ->
                                val key = peerPublicKeys[peerHex]
                                key != null && ts.verify(peerHex, key) is io.meshlink.crypto.VerifyResult.Trusted
                            } ?: false
                            if (!isPinned && handshakeRateLimiter != null &&
                                !handshakeRateLimiter.tryAcquire(peerHex)) {
                                diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN,
                                    "handshake rate limit exceeded, peer=$peerHex")
                            } else {
                                val msg1 = handshakeManager.initiateHandshake(event.peerId)
                                if (msg1 != null) {
                                    safeSend(event.peerId, msg1)
                                }
                            }
                        }
                    }
                    // Flush buffered messages and resume interrupted transfers
                    val preExistingTransferKeys = outboundTransfers.keys.toSet()
                    flushPendingMessages(event.peerId.toHex(), newScope)
                    // Resume interrupted transfers that existed before this discovery
                    resumeTransfers(event.peerId, newScope, preExistingTransferKeys)
                }
            }
        }
        newScope.launch {
            transport.peerLostEvents.collect { event ->
                // Don't remove from presenceTracker here — let sweep handle it
                _peers.emit(PeerEvent.Lost(event.peerId))
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
                    val triggered = withTimeoutOrNull(effectiveGossipInterval()) {
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
                    val sinceLastGossip = clock() - lastGossipSentMs
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
        deliveryDeadlineTimer.cancelAll()
        clearState()
        scope?.cancel()
        scope = null
    }

    override fun rotateIdentity(): Result<Unit> {
        if (crypto == null) return Result.failure(IllegalStateException("Crypto not enabled"))
        localKeyPair = crypto.generateX25519KeyPair()
        sealer = NoiseKSealer(crypto)
        broadcastKeyPair = crypto.generateEd25519KeyPair()
        return Result.success(Unit)
    }

    private fun clearState() {
        reassembly.clear()
        outboundTransfers.clear()
        presenceTracker.clear()
        pauseQueue.clear()
        relayQueue.clear()
        routedMsgSources.clear()
        inboundReplayGuards.clear()
        inboundRateCounts.clear()
        dedup.clear()
        routingTable.clear()
        pendingMessages.clear()
        lastTriggeredUpdateTime.clear()
        lastGossipSentMs = 0L
        tombstoneSet.clear()
    }

    override fun pause() {
        paused = true
        scope?.launch { transport.stopAll() }
    }

    override fun resume() {
        paused = false
        scope?.launch { transport.startAdvertisingAndScanning() }
        val s = scope ?: return
        // Flush queued own-outbound sends
        val queued = pauseQueue.toList()
        pauseQueue.clear()
        for ((recipient, payload) in queued) {
            doSend(s, recipient, payload)
        }
        // Flush queued relay messages
        val relayQueued = relayQueue.toList()
        relayQueue.clear()
        for ((nextHop, frame) in relayQueued) {
            s.launch { safeSend(nextHop, frame) }
        }
    }

    override fun meshHealth(): MeshHealthSnapshot {
        val usedBytes = outboundTransfers.values.sumOf { t ->
            t.chunks.sumOf { it.size }
        } + reassembly.values.sumOf { s ->
            s.chunks.values.sumOf { it.size }
        }
        val utilPercent = if (config.bufferCapacity > 0) {
            (usedBytes * 100 / config.bufferCapacity).coerceIn(0, 100)
        } else 0
        return MeshHealthSnapshot(
            connectedPeers = presenceTracker.allPeerIds().size,
            reachablePeers = presenceTracker.connectedPeerIds().size,
            bufferUtilizationPercent = utilPercent,
            activeTransfers = outboundTransfers.size,
            powerMode = currentPowerMode,
            avgRouteCost = routingTable.avgCost(),
            relayQueueSize = relayQueue.size,
            effectiveGossipIntervalMs = effectiveGossipInterval(),
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
        val evicted = presenceTracker.sweep(seenPeers)
        for (peerId in evicted) {
            diagnosticSink.emit(DiagnosticCode.PEER_EVICTED, Severity.INFO, "peerId=$peerId")
        }
        return evicted
    }

    override fun addRoute(destination: String, nextHop: String, cost: Double, sequenceNumber: UInt) {
        routingTable.addRoute(destination, nextHop, cost, sequenceNumber)
    }

    private fun effectiveGossipInterval(): Long {
        val base = config.gossipIntervalMs
        if (base <= 0) return 0
        val routeCount = routingTable.size()
        val routeMultiplied = when {
            routeCount > 200 -> base * 2
            routeCount > 100 -> base * 3 / 2
            else -> base
        }
        return when (currentPowerMode) {
            "POWER_SAVER" -> routeMultiplied * 3
            "BALANCED" -> routeMultiplied * 2
            else -> routeMultiplied
        }
    }

    private fun isSignificantCostChange(oldCost: Double?, newCost: Double?): Boolean {
        // New route appeared or route withdrawn
        if (oldCost == null && newCost != null) return true
        if (oldCost != null && newCost == null) return true
        if (oldCost == null || newCost == null) return false
        // Withdrawal (MAX_VALUE) is always significant
        if (newCost == Double.MAX_VALUE || oldCost == Double.MAX_VALUE) return true
        if (oldCost == 0.0) return newCost > 0.0
        val ratio = kotlin.math.abs(newCost - oldCost) / oldCost
        return ratio > config.triggeredUpdateThreshold
    }

    override fun sweepStaleTransfers(maxAgeMs: Long): Int {
        val now = clock()
        val staleKeys = outboundTransfers.entries
            .filter { now - it.value.createdAtMs > maxAgeMs }
            .map { it.key }
        for (key in staleKeys) {
            val transfer = outboundTransfers.remove(key)
            transfer?.session?.onInactivityTimeout()
            val outcome = deliveryTracker.recordOutcome(key, DeliveryOutcome.FAILED_ACK_TIMEOUT)
            if (outcome != null) {
                tombstoneSet.add(key)
                _transferFailures.tryEmit(
                    TransferFailure(Uuid.fromByteArray(hexToBytes(key)), DeliveryOutcome.FAILED_ACK_TIMEOUT)
                )
            }
        }
        tombstoneSet.sweep()
        return staleKeys.size
    }

    override fun sweepStaleReassemblies(maxAgeMs: Long): Int {
        val now = clock()
        val staleKeys = reassembly.entries
            .filter { now - it.value.createdAtMs > maxAgeMs }
            .map { it.key }
        for (key in staleKeys) {
            reassembly.remove(key)
        }
        return staleKeys.size
    }

    override fun sweepExpiredPendingMessages(): Int {
        if (config.pendingMessageTtlMs <= 0) return 0
        val now = clock()
        var count = 0
        val emptyKeys = mutableListOf<String>()
        for ((peerHex, messages) in pendingMessages) {
            val before = messages.size
            messages.removeAll { (now - it.enqueueTimeMs) >= config.pendingMessageTtlMs }
            val expired = before - messages.size
            count += expired
            for (i in 0 until expired) {
                _transferFailures.tryEmit(
                    TransferFailure(Uuid.random(), DeliveryOutcome.FAILED_DELIVERY_TIMEOUT)
                )
            }
            if (messages.isEmpty()) emptyKeys.add(peerHex)
        }
        for (key in emptyKeys) pendingMessages.remove(key)
        return count
    }

    override fun updateBattery(batteryPercent: Int, isCharging: Boolean) {
        val oldMode = currentPowerMode
        currentPowerMode = powerModeEngine.update(batteryPercent, isCharging).name
        if (currentPowerMode != oldMode) {
            emitHealthUpdate()
        }
    }

    override fun shedMemoryPressure(): List<String> {
        val health = meshHealth()
        val level = when {
            health.bufferUtilizationPercent >= 90 -> MemoryPressure.CRITICAL
            health.bufferUtilizationPercent >= 70 -> MemoryPressure.HIGH
            health.bufferUtilizationPercent >= 50 -> MemoryPressure.MODERATE
            else -> return emptyList()
        }
        val shedder = TieredShedder(
            relayBufferCount = reassembly.size,
            dedupEntries = dedup.size(),
            connectionCount = presenceTracker.allPeerIds().size,
        )
        val results = shedder.shed(level)
        val actions = mutableListOf<String>()
        for (result in results) {
            when (result.action) {
                io.meshlink.power.ShedAction.RELAY_BUFFERS_CLEARED -> {
                    reassembly.clear()
                    val relayCount = relayQueue.size
                    relayQueue.clear()
                    actions.add("Cleared ${result.count} relay buffers, $relayCount queued relays")
                }
                io.meshlink.power.ShedAction.DEDUP_TRIMMED -> {
                    dedup.clear()
                    actions.add("Trimmed ${result.count} dedup entries")
                }
                io.meshlink.power.ShedAction.CONNECTIONS_DROPPED -> {
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
        if (broadcastRateLimiter != null) {
            if (!broadcastRateLimiter.tryAcquire("broadcast")) {
                diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN,
                    "broadcast rate limit exceeded")
                return Result.failure(IllegalStateException("Broadcast rate limit exceeded"))
            }
        }

        val messageId = Uuid.random()
        val appIdHash = config.appId?.let { AppIdFilter.hash(it) } ?: ByteArray(16)
        val msgIdBytes = messageId.toByteArray()
        val remainingHops = maxHops

        // Sign broadcast content with Ed25519 if crypto is available
        // Note: remainingHops is excluded from signed data because relays decrement it
        val bkp = broadcastKeyPair
        val signature = if (bkp != null && crypto != null) {
            val signedData = msgIdBytes + transport.localPeerId + appIdHash + payload
            crypto.sign(bkp.privateKey, signedData)
        } else ByteArray(0)

        val encoded = WireCodec.encodeBroadcast(
            messageId = msgIdBytes,
            origin = transport.localPeerId,
            remainingHops = remainingHops,
            appIdHash = appIdHash,
            payload = payload,
            signature = signature,
            signerPublicKey = bkp?.publicKey ?: ByteArray(0),
        )
        // Mark as seen so we don't deliver our own broadcast back to ourselves
        dedup.tryInsert(messageId.toByteArray().toHex())
        for (peerHex in presenceTracker.allPeerIds()) {
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
                _messages.emit(Message(senderId = recipient, payload = payload))
            }
            return Result.success(messageId)
        }

        if (paused) {
            pauseQueue.add(recipient to payload)
            return Result.success(Uuid.random())
        }

        // Rate limit check (per-recipient)
        if (rateLimiter != null) {
            val key = recipient.toHex()
            if (!rateLimiter.tryAcquire(key)) {
                diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "recipient=$key")
                return Result.failure(IllegalStateException("Rate limit exceeded"))
            }
        }

        // Circuit breaker check
        if (circuitBreaker != null && !circuitBreaker.allowAttempt()) {
            return Result.failure(IllegalStateException("Circuit breaker open — transport circuit tripped"))
        }

        // Check if recipient is known directly
        if (recipient.toHex() !in presenceTracker.allPeerIds()) {
            // Try routing table for multi-hop
            val route = routingTable.bestRoute(recipient.toHex())
            if (route != null) {
                return doRoutedSend(s, recipient, payload, route.nextHop)
            }
            // Buffer for store-and-forward if enabled
            if (config.pendingMessageTtlMs > 0) {
                val recipientHex = recipient.toHex()
                val list = pendingMessages.getOrPut(recipientHex) { mutableListOf() }
                list.add(PendingMessage(recipient, payload, clock()))
                // Evict oldest if over capacity
                while (list.size > config.pendingMessageCapacity) {
                    list.removeAt(0)
                    s.launch {
                        _transferFailures.emit(TransferFailure(Uuid.random(), DeliveryOutcome.FAILED_BUFFER_FULL))
                    }
                }
                return Result.success(Uuid.random())
            }
            val failureId = Uuid.random()
            s.launch {
                _transferFailures.emit(TransferFailure(failureId, DeliveryOutcome.FAILED_NO_ROUTE))
            }
            return Result.failure(IllegalStateException("No route to unknown peer"))
        }

        // Require recipient's public key when crypto is enabled
        if (crypto != null && recipient.toHex() !in peerPublicKeys) {
            return Result.failure(IllegalStateException("Recipient public key unknown"))
        }

        return doSend(s, recipient, payload)
    }

    private fun doSend(s: CoroutineScope, recipient: ByteArray, payload: ByteArray): Result<Uuid> {
        val messageId = Uuid.random().toByteArray()
        val key = messageId.toHex()
        deliveryTracker.register(key)

        // Start delivery deadline timer based on bufferTtlMs
        if (config.bufferTtlMs > 0) {
            deliveryDeadlineTimer.startTimer(
                scope = s,
                messageKey = key,
                deadlineMs = config.bufferTtlMs,
            ) { expiredKey ->
                val uuid = Uuid.fromByteArray(hexToBytes(expiredKey))
                _transferFailures.tryEmit(TransferFailure(uuid, DeliveryOutcome.FAILED_DELIVERY_TIMEOUT))
                outboundTransfers.remove(expiredKey)
            }
        }

        // Encrypt payload if crypto is available and we know the recipient's public key
        val recipientHex = recipient.toHex()
        val currentSealer = sealer
        val wirePayload = if (currentSealer != null && peerPublicKeys.containsKey(recipientHex)) {
            currentSealer.seal(peerPublicKeys[recipientHex]!!, payload)
        } else {
            payload
        }

        val chunkSize = config.mtu - WireCodec.CHUNK_HEADER_SIZE
        val chunks = if (wirePayload.isEmpty()) {
            listOf(ByteArray(0))
        } else {
            wirePayload.asSequence()
                .chunked(chunkSize)
                .map { it.toByteArray() }
                .toList()
        }
        val totalChunks = chunks.size
        val session = TransferSession(totalChunks, initialWindow = totalChunks)
        outboundTransfers[key] = OutboundTransfer(session, chunks, recipient, messageId, createdAtMs = clock())

        // Check buffer pressure after adding transfer
        checkBufferPressure()

        // Send initial batch
        sendChunks(s, key)

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

    private fun sendChunks(s: CoroutineScope, transferKey: String) {
        val transfer = outboundTransfers[transferKey] ?: return
        val toSend = transfer.session.nextChunksToSend()
        for (seqNum in toSend) {
            val encoded = WireCodec.encodeChunk(
                messageId = transfer.messageId,
                sequenceNumber = seqNum.toUShort(),
                totalChunks = transfer.chunks.size.toUShort(),
                payload = transfer.chunks[seqNum],
            )
            s.launch { safeSend(transfer.recipient, encoded) }
        }
    }

    private suspend fun broadcastRouteUpdate(isTriggered: Boolean = false) {
        val connectedPeers = presenceTracker.connectedPeerIds()
        if (connectedPeers.isEmpty()) return

        val allRoutes = routingTable.allBestRoutes()
        val now = clock()

        for (peerHex in connectedPeers) {
            // Per-neighbor rate limit: skip if this is a triggered update and
            // we already sent a triggered update to this peer within the gossip interval
            if (isTriggered) {
                val lastSent = lastTriggeredUpdateTime[peerHex]
                if (lastSent != null && (now - lastSent) < effectiveGossipInterval()) {
                    continue
                }
            }

            val entries = allRoutes.mapNotNull { route ->
                if (route.nextHop == peerHex) {
                    // Split horizon: don't advertise a route back to its next-hop
                    null
                } else if (previousNextHop[route.destination] == peerHex && route.nextHop != peerHex) {
                    // Poison reverse: tell old next-hop the route is gone
                    RouteUpdateEntry(
                        destination = hexToBytes(route.destination),
                        cost = Double.MAX_VALUE,
                        sequenceNumber = route.sequenceNumber,
                        hopCount = 1u,
                    )
                } else {
                    RouteUpdateEntry(
                        destination = hexToBytes(route.destination),
                        cost = route.cost,
                        sequenceNumber = route.sequenceNumber,
                        hopCount = 1u,
                    )
                }
            }
            val bkp2 = broadcastKeyPair
            val updateData = if (crypto != null && bkp2 != null) {
                val unsigned = WireCodec.encodeRouteUpdate(transport.localPeerId, entries)
                val dataToSign = unsigned + bkp2.publicKey
                val signature = crypto.sign(bkp2.privateKey, dataToSign)
                WireCodec.encodeSignedRouteUpdate(transport.localPeerId, entries, bkp2.publicKey, signature)
            } else {
                WireCodec.encodeRouteUpdate(transport.localPeerId, entries)
            }
            val peerId = hexToBytes(peerHex)
            safeSend(peerId, updateData)

            if (isTriggered) {
                lastTriggeredUpdateTime[peerHex] = now
            }
        }

        diagnosticSink.emit(DiagnosticCode.GOSSIP_TRAFFIC_REPORT, Severity.INFO,
            "peers=${connectedPeers.size}, routes=${allRoutes.size}")
        lastGossipSentMs = clock()
    }

    private suspend fun broadcastKeepalive() {
        val connectedPeers = presenceTracker.connectedPeerIds()
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
        presenceTracker.peerSeen(fromPeerId.toHex())
    }

    private suspend fun safeSend(peerId: ByteArray, data: ByteArray) {
        // Per-neighbor aggregate rate limit
        if (neighborAggregateRateLimiter != null) {
            if (!neighborAggregateRateLimiter.tryAcquire(peerId.toHex())) {
                diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN,
                    "neighbor aggregate limit exceeded, peer=${peerId.toHex()}")
                return
            }
        }
        try {
            transport.sendToPeer(peerId, data)
        } catch (e: Exception) {
            circuitBreaker?.recordFailure()
            diagnosticSink.emit(DiagnosticCode.SEND_FAILED, Severity.WARN,
                "peer=${peerId.toHex()}, error=${e.message}")
        }
    }

    internal fun sendNack(peerId: ByteArray, messageId: ByteArray) {
        val peerHex = peerId.toHex()
        if (nackRateLimiter != null && !nackRateLimiter.tryAcquire(peerHex)) {
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
        val mgr = handshakeManager ?: return
        val response = mgr.handleIncoming(fromPeerId, data)
        if (response != null) {
            safeSend(fromPeerId, response)
        }
    }

    private suspend fun handleRouteUpdate(fromPeerId: ByteArray, data: ByteArray) {
        val update = WireCodec.decodeRouteUpdate(data)
        // If signed, verify the signature before accepting
        if (update.signature != null && update.signerPublicKey != null && crypto != null) {
            val signedData = data.copyOfRange(0, data.size - 64)
            if (!crypto.verify(update.signerPublicKey, signedData, update.signature)) {
                diagnosticSink.emit(DiagnosticCode.MALFORMED_DATA, Severity.WARN,
                    "route_update signature verification failed from ${fromPeerId.toHex()}")
                return
            }
        }
        val senderHex = fromPeerId.toHex()
        var significantChange = false
        for (entry in update.entries) {
            val destHex = entry.destination.toHex()
            // Don't learn routes to ourselves
            if (destHex == transport.localPeerId.toHex()) continue
            val oldBest = routingTable.bestRoute(destHex)
            routingTable.addRoute(destHex, senderHex, entry.cost + 1.0, entry.sequenceNumber)
            val newBest = routingTable.bestRoute(destHex)
            if (oldBest != null && newBest != null && oldBest.nextHop != newBest.nextHop) {
                previousNextHop[destHex] = oldBest.nextHop
                diagnosticSink.emit(DiagnosticCode.ROUTE_CHANGED, Severity.INFO,
                    "dest=$destHex, oldNextHop=${oldBest.nextHop}, newNextHop=${newBest.nextHop}, oldCost=${oldBest.cost}, newCost=${newBest.cost}")
            }
            // Detect significant cost changes for triggered updates
            if (!significantChange) {
                significantChange = isSignificantCostChange(oldBest?.cost, newBest?.cost)
            }
        }
        if (significantChange && config.gossipIntervalMs > 0) {
            triggeredUpdateChannel.trySend(Unit)
        }
    }

    private suspend fun handleChunk(fromPeerId: ByteArray, data: ByteArray) {
        val chunk = WireCodec.decodeChunk(data)
        val key = chunk.messageId.toHex()

        val state = reassembly.getOrPut(key) {
            ReassemblyState(chunk.totalChunks.toInt(), createdAtMs = clock())
        }
        state.chunks[chunk.sequenceNumber.toInt()] = chunk.payload
        state.sackTracker.record(chunk.sequenceNumber.toInt())

        // Send per-chunk ACK with SACK bitmask
        val status = state.sackTracker.status()
        val ack = WireCodec.encodeChunkAck(
            messageId = chunk.messageId,
            ackSequence = status.ackSeq.toUShort(),
            sackBitmask = status.sackBitmask,
        )
        scope?.launch { safeSend(fromPeerId, ack) }

        if (state.sackTracker.isComplete()) {
            reassembly.remove(key)
            if (!dedup.tryInsert(key)) return // duplicate message
            val fullPayload = (0 until state.totalChunks)
                .map { state.chunks[it]!! }
                .reduce { acc, bytes -> acc + bytes }

            // Decrypt if crypto is available
            val cs = sealer
            val lkp = localKeyPair
            val decrypted = if (cs != null && lkp != null && fullPayload.size >= 48) {
                try {
                    cs.unseal(lkp.privateKey, fullPayload)
                } catch (_: Exception) {
                    diagnosticSink.emit(DiagnosticCode.DECRYPTION_FAILED, Severity.WARN, "chunk reassembly")
                    fullPayload // Decryption failed — deliver as-is (plaintext fallback)
                }
            } else {
                fullPayload
            }
            _messages.emit(Message(senderId = fromPeerId, payload = decrypted))
        }
    }

    private suspend fun handleChunkAck(data: ByteArray) {
        val ack = WireCodec.decodeChunkAck(data)
        val key = ack.messageId.toHex()
        val transfer = outboundTransfers[key] ?: run {
            // Legacy: no outbound tracking, just emit delivery confirmation
            _deliveryConfirmations.emit(Uuid.fromByteArray(ack.messageId))
            return
        }

        transfer.session.onAck(ack.ackSequence.toInt(), ack.sackBitmask)

        // Emit progress (use session's authoritative acked count)
        _transferProgress.emit(
            TransferProgress(
                messageId = Uuid.fromByteArray(ack.messageId),
                chunksAcked = transfer.session.ackedCount(),
                totalChunks = transfer.chunks.size,
            )
        )

        if (transfer.session.isComplete()) {
            outboundTransfers.remove(key)
            deliveryDeadlineTimer.cancel(key)
            emitHealthUpdate()
            if (deliveryTracker.recordOutcome(key, DeliveryOutcome.CONFIRMED) != null) {
                tombstoneSet.add(key)
                _deliveryConfirmations.emit(Uuid.fromByteArray(ack.messageId))
            }
        } else {
            // Retransmit missing chunks
            sendChunks(scope!!, key)
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
        if (crypto != null && broadcast.signature.isNotEmpty()) {
            val signedData = broadcast.messageId + broadcast.origin + broadcast.appIdHash + broadcast.payload
            if (!crypto.verify(broadcast.signerPublicKey, signedData, broadcast.signature)) return
        } else if (crypto != null && broadcast.signature.isEmpty()) {
            // Crypto enabled but no signature → reject
            return
        }

        // Dedup: reject if we've seen this broadcast before
        if (!dedup.tryInsert(key)) return

        // Deliver to self
        _messages.emit(Message(senderId = broadcast.origin, payload = broadcast.payload))

        // Re-flood to all known peers except sender, if hops remain and not paused
        if (broadcast.remainingHops > 0u && !paused) {
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
            for (peerHex in presenceTracker.allPeerIds()) {
                if (peerHex != senderHex) {
                    s.launch { safeSend(hexToBytes(peerHex), reflooded) }
                }
            }
        }
    }

    private suspend fun handleRoutedMessage(fromPeerId: ByteArray, data: ByteArray) {
        val routed = WireCodec.decodeRoutedMessage(data)
        val key = routed.messageId.toHex()

        if (!dedup.tryInsert(key)) return

        // Replay guard: check counter if present (counter=0 means unprotected/legacy)
        if (routed.replayCounter > 0u) {
            val originHex = routed.origin.toHex()
            val guard = inboundReplayGuards.getOrPut(originHex) { ReplayGuard() }
            if (!guard.check(routed.replayCounter)) {
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
                val now = clock()
                val timestamps = inboundRateCounts.getOrPut(originHex) { mutableListOf() }
                timestamps.removeAll { now - it > 60_000L }
                if (timestamps.size >= config.inboundRateLimitPerSenderPerMinute) {
                    diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN,
                        "inbound, origin=$originHex, count=${timestamps.size}")
                    return // Drop — sender exceeds inbound rate limit
                }
                timestamps.add(now)
            }
            // Decrypt if crypto is available
            val cs2 = sealer
            val lkp2 = localKeyPair
            val deliveredPayload = if (cs2 != null && lkp2 != null && routed.payload.size >= 48) {
                try {
                    cs2.unseal(lkp2.privateKey, routed.payload)
                } catch (_: Exception) {
                    diagnosticSink.emit(DiagnosticCode.DECRYPTION_FAILED, Severity.WARN, "routed message")
                    routed.payload // Decryption failed — deliver as-is
                }
            } else {
                routed.payload
            }
            _messages.emit(Message(senderId = routed.origin, payload = deliveredPayload))
            // Send signed delivery ACK back toward origin
            val bkp3 = broadcastKeyPair
            val ackSig = if (bkp3 != null && crypto != null) {
                crypto.sign(bkp3.privateKey, routed.messageId + transport.localPeerId)
            } else ByteArray(0)
            val ack = WireCodec.encodeDeliveryAck(
                routed.messageId, transport.localPeerId,
                signature = ackSig,
                signerPublicKey = bkp3?.publicKey ?: ByteArray(0),
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

        // Per-sender-per-neighbor rate limit for relay
        val destHex = routed.destination.toHex()
        val nextHop = if (destHex in presenceTracker.allPeerIds()) {
            routed.destination
        } else {
            val route = routingTable.bestRoute(destHex) ?: return
            hexToBytes(route.nextHop)
        }
        if (senderNeighborRateLimiter != null) {
            val senderNeighborKey = "${routed.origin.toHex()}->${nextHop.toHex()}"
            if (!senderNeighborRateLimiter.tryAcquire(senderNeighborKey)) {
                diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN,
                    "sender-neighbor relay limit exceeded, origin=${routed.origin.toHex()}, neighbor=${nextHop.toHex()}")
                return
            }
        }

        // Record reverse path for delivery ACK relay
        routedMsgSources[key] = fromPeerId

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
        if (paused) {
            relayQueue.add(nextHop to relayed)
            // Evict oldest if over capacity
            while (relayQueue.size > config.relayQueueCapacity) {
                relayQueue.removeAt(0)
            }
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
        if (crypto != null && ack.signature.isNotEmpty()) {
            val signedData = ack.messageId + ack.recipientId
            if (!crypto.verify(ack.signerPublicKey, signedData, ack.signature)) return
        } else if (crypto != null && ack.signature.isEmpty()) {
            return
        }

        // Check tombstone set first — ACK for already-resolved delivery
        if (tombstoneSet.contains(key)) {
            diagnosticSink.emit(DiagnosticCode.LATE_DELIVERY_ACK, Severity.INFO, "messageId=$key")
            return
        }

        // If tracked, use exactly-once guard; if untracked, accept (external ACK)
        val outcome = deliveryTracker.recordOutcome(key, DeliveryOutcome.CONFIRMED)
        if (outcome != null || !deliveryTracker.isTracked(key)) {
            deliveryDeadlineTimer.cancel(key)
            if (outcome != null) tombstoneSet.add(key)
            _deliveryConfirmations.emit(Uuid.fromByteArray(ack.messageId))
        } else if (deliveryTracker.isTracked(key)) {
            // Late ACK: messageId already resolved (tombstoned in delivery tracker)
            tombstoneSet.add(key)
            diagnosticSink.emit(DiagnosticCode.LATE_DELIVERY_ACK, Severity.INFO, "messageId=$key")
        }
        // Relay ACK back along reverse path if we were a relay node
        val source = routedMsgSources.remove(key)
        if (source != null) {
            scope?.launch { safeSend(source, data) }
        }
    }
}

internal class ReassemblyState(val totalChunks: Int, val createdAtMs: Long = 0L) {
    val chunks = mutableMapOf<Int, ByteArray>()
    val sackTracker = SackTracker(totalChunks)
}

internal class OutboundTransfer(
    val session: TransferSession,
    val chunks: List<ByteArray>,
    val recipient: ByteArray,
    val messageId: ByteArray,
    val createdAtMs: Long = 0L,
)
