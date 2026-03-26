package io.meshlink

import io.meshlink.config.MeshLinkConfig
import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticEvent
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.diagnostics.MeshHealthSnapshot
import io.meshlink.diagnostics.Severity
import io.meshlink.model.Message
import io.meshlink.model.PeerEvent
import io.meshlink.model.TransferProgress
import io.meshlink.power.MemoryPressure
import io.meshlink.power.PowerModeEngine
import io.meshlink.power.TieredShedder
import io.meshlink.routing.DedupSet
import io.meshlink.routing.PresenceTracker
import io.meshlink.routing.RoutingTable
import io.meshlink.transfer.SackTracker
import io.meshlink.transfer.TransferSession
import io.meshlink.transport.BleTransport
import io.meshlink.util.CircuitBreaker
import io.meshlink.util.DeliveryOutcome
import io.meshlink.util.DeliveryTracker
import io.meshlink.util.RateLimiter
import io.meshlink.util.hexToBytes
import io.meshlink.util.toHex
import io.meshlink.wire.WireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class MeshLink(
    private val transport: BleTransport,
    private val config: MeshLinkConfig = MeshLinkConfig(),
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    clock: () -> Long = { System.currentTimeMillis() },
) : MeshLinkApi {

    private val clock = clock

    private val rateLimiter: RateLimiter? =
        if (config.rateLimitMaxSends > 0) RateLimiter(config.rateLimitMaxSends, config.rateLimitWindowMs, clock) else null

    private val circuitBreaker: CircuitBreaker? =
        if (config.circuitBreakerMaxFailures > 0) CircuitBreaker(config.circuitBreakerMaxFailures, config.circuitBreakerWindowMs, config.circuitBreakerCooldownMs, clock) else null

    private val _peers = MutableSharedFlow<PeerEvent>(replay = 64)
    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    private val _deliveryConfirmations = MutableSharedFlow<Uuid>(extraBufferCapacity = 64)
    private val _transferProgress = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 64)

    override val peers: Flow<PeerEvent> = _peers.asSharedFlow()
    override val messages: Flow<Message> = _messages.asSharedFlow()
    override val deliveryConfirmations: Flow<Uuid> = _deliveryConfirmations.asSharedFlow()
    override val transferProgress: Flow<TransferProgress> = _transferProgress.asSharedFlow()

    private var started = false
    private var paused = false
    private var scope: CoroutineScope? = null
    private val baseContext = coroutineContext

    // Reassembly buffer: messageId hex → (sack tracker, received chunks map)
    private val reassembly = mutableMapOf<String, ReassemblyState>()

    // Outbound transfer sessions: messageId hex → (session, chunks data, recipient)
    private val outboundTransfers = mutableMapOf<String, OutboundTransfer>()

    // Queued sends while paused: (recipient, payload)
    private val pauseQueue = mutableListOf<Pair<ByteArray, ByteArray>>()

    // Peer presence tracking (replaces simple knownPeers set)
    private val presenceTracker = PresenceTracker()

    // Deduplication of fully reassembled messages
    private val dedup = DedupSet(capacity = config.dedupCapacity)

    // Delivery tracking for exactly-once terminal signals
    private val deliveryTracker = DeliveryTracker()

    // Diagnostic event sink
    private val diagnosticSink = DiagnosticSink(bufferCapacity = config.diagnosticBufferCapacity, clock = clock)

    // Routing table for multi-hop relay
    private val routingTable = RoutingTable()

    // Reverse path for delivery ACK relay: messageId hex → fromPeerId
    private val routedMsgSources = mutableMapOf<String, ByteArray>()

    // Power mode engine for battery-aware operation
    private val powerModeEngine = PowerModeEngine(clock = clock)
    private var currentPowerMode = "PERFORMANCE"

    override fun start(): Result<Unit> {
        if (started) return Result.success(Unit)

        val violations = config.validate()
        if (violations.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(violations.joinToString("; ")))
        }

        started = true
        val newScope = CoroutineScope(baseContext + SupervisorJob())
        scope = newScope

        newScope.launch {
            transport.startAdvertisingAndScanning()
        }
        newScope.launch {
            transport.advertisementEvents.collect { event ->
                if (!paused) {
                    presenceTracker.peerSeen(event.peerId.toHex())
                    _peers.emit(PeerEvent.Discovered(event.peerId))
                }
            }
        }
        newScope.launch {
            transport.peerLostEvents.collect { event ->
                // Don't remove from presenceTracker here — let sweep handle it
                _peers.emit(PeerEvent.Lost(event.peerId))
            }
        }
        newScope.launch {
            transport.incomingData.collect { incoming ->
                handleIncomingData(incoming.peerId, incoming.data)
            }
        }

        return Result.success(Unit)
    }

    override fun stop() {
        started = false
        reassembly.clear()
        outboundTransfers.clear()
        presenceTracker.clear()
        pauseQueue.clear()
        routedMsgSources.clear()
        scope?.cancel()
        scope = null
    }

    override fun pause() {
        paused = true
        scope?.launch { transport.stopAll() }
    }

    override fun resume() {
        paused = false
        scope?.launch { transport.startAdvertisingAndScanning() }
        val s = scope ?: return
        val queued = pauseQueue.toList()
        pauseQueue.clear()
        for ((recipient, payload) in queued) {
            doSend(s, recipient, payload)
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
        )
    }

    override fun drainDiagnostics(): List<DiagnosticEvent> {
        val events = mutableListOf<DiagnosticEvent>()
        diagnosticSink.drainTo(events)
        return events
    }

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

    override fun sweepStaleTransfers(maxAgeMs: Long): Int {
        val now = clock()
        val staleKeys = outboundTransfers.entries
            .filter { now - it.value.createdAtMs > maxAgeMs }
            .map { it.key }
        for (key in staleKeys) {
            val transfer = outboundTransfers.remove(key)
            transfer?.session?.onInactivityTimeout()
        }
        return staleKeys.size
    }

    override fun updateBattery(batteryPercent: Int, isCharging: Boolean) {
        currentPowerMode = powerModeEngine.update(batteryPercent, isCharging).name
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
                    actions.add("Cleared ${result.count} relay buffers")
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
        val s = scope ?: throw IllegalStateException("MeshLink not started")

        if (payload.size > config.bufferCapacity) {
            return Result.failure(IllegalArgumentException("bufferFull"))
        }

        val messageId = Uuid.random()
        val encoded = WireCodec.encodeBroadcast(
            messageId = messageId.toByteArray(),
            origin = transport.localPeerId,
            remainingHops = maxHops,
            payload = payload,
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
        val s = scope ?: throw IllegalStateException("MeshLink not started")

        if (payload.size > config.bufferCapacity) {
            return Result.failure(IllegalArgumentException("bufferFull"))
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

        // Self-send loopback: deliver locally without BLE
        if (recipient.contentEquals(transport.localPeerId)) {
            val messageId = Uuid.random()
            s.launch {
                _messages.emit(Message(senderId = recipient, payload = payload))
            }
            return Result.success(messageId)
        }

        // Check if recipient is known directly
        if (recipient.toHex() !in presenceTracker.allPeerIds()) {
            // Try routing table for multi-hop
            val route = routingTable.bestRoute(recipient.toHex())
            if (route != null) {
                return doRoutedSend(s, recipient, payload, route.nextHop)
            }
            return Result.failure(IllegalStateException("No route to unknown peer"))
        }

        return doSend(s, recipient, payload)
    }

    private fun doSend(s: CoroutineScope, recipient: ByteArray, payload: ByteArray): Result<Uuid> {
        val messageId = Uuid.random().toByteArray()
        val key = messageId.toHex()
        deliveryTracker.register(key)
        val chunkSize = config.mtu - WireCodec.CHUNK_HEADER_SIZE
        val chunks = if (payload.isEmpty()) {
            listOf(ByteArray(0))
        } else {
            payload.asSequence()
                .chunked(chunkSize)
                .map { it.toByteArray() }
                .toList()
        }
        val totalChunks = chunks.size
        val session = TransferSession(totalChunks, initialWindow = totalChunks)
        outboundTransfers[key] = OutboundTransfer(session, chunks, recipient, messageId, createdAtMs = clock())

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

    private suspend fun safeSend(peerId: ByteArray, data: ByteArray) {
        try {
            transport.sendToPeer(peerId, data)
        } catch (_: Exception) {
            circuitBreaker?.recordFailure()
        }
    }

    private suspend fun handleIncomingData(fromPeerId: ByteArray, data: ByteArray) {
        if (data.isEmpty()) return
        try {
            when (data[0]) {
                WireCodec.TYPE_CHUNK -> handleChunk(fromPeerId, data)
                WireCodec.TYPE_CHUNK_ACK -> handleChunkAck(data)
                WireCodec.TYPE_BROADCAST -> handleBroadcast(fromPeerId, data)
                WireCodec.TYPE_ROUTED_MESSAGE -> handleRoutedMessage(fromPeerId, data)
                WireCodec.TYPE_DELIVERY_ACK -> handleDeliveryAck(fromPeerId, data)
            }
        } catch (_: Exception) {
            // Silently drop malformed/truncated wire data
        }
    }

    private suspend fun handleChunk(fromPeerId: ByteArray, data: ByteArray) {
        val chunk = WireCodec.decodeChunk(data)
        val key = chunk.messageId.toHex()

        val state = reassembly.getOrPut(key) {
            ReassemblyState(chunk.totalChunks.toInt())
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
            _messages.emit(Message(senderId = fromPeerId, payload = fullPayload))
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

        // Emit progress
        val ackedCount = (0 until transfer.chunks.size).count { i ->
            // acked if <= ackSeq, or sack bit set
            i <= ack.ackSequence.toInt() ||
                (i > ack.ackSequence.toInt() && (i - ack.ackSequence.toInt() - 1) < 64 &&
                    ack.sackBitmask and (1uL shl (i - ack.ackSequence.toInt() - 1)) != 0uL)
        }
        _transferProgress.emit(
            TransferProgress(
                messageId = Uuid.fromByteArray(ack.messageId),
                chunksAcked = ackedCount,
                totalChunks = transfer.chunks.size,
            )
        )

        if (transfer.session.isComplete()) {
            outboundTransfers.remove(key)
            if (deliveryTracker.recordOutcome(key, DeliveryOutcome.CONFIRMED) != null) {
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

        // Dedup: reject if we've seen this broadcast before
        if (!dedup.tryInsert(key)) return

        // Deliver to self
        _messages.emit(Message(senderId = broadcast.origin, payload = broadcast.payload))

        // Re-flood to all known peers except sender, if hops remain and not paused
        if (broadcast.remainingHops > 0u && !paused) {
            val reflooded = WireCodec.encodeBroadcast(
                messageId = broadcast.messageId,
                origin = broadcast.origin,
                remainingHops = (broadcast.remainingHops - 1u).toUByte(),
                payload = broadcast.payload,
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

        // Loop detection: drop if we are already in the visited list
        val selfId = transport.localPeerId
        if (routed.visitedList.any { it.contentEquals(selfId) }) return

        // If we are the destination, deliver locally and send delivery ACK back
        if (routed.destination.contentEquals(transport.localPeerId)) {
            _messages.emit(Message(senderId = routed.origin, payload = routed.payload))
            // Send delivery ACK back toward origin
            val ack = WireCodec.encodeDeliveryAck(routed.messageId, transport.localPeerId)
            scope?.launch { safeSend(fromPeerId, ack) }
            return
        }

        // Otherwise relay: decrement hop limit, add self to visited, forward
        if (routed.hopLimit <= 0u) return
        if (paused) return // don't relay while paused

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
        )

        // Forward to destination if directly known, otherwise best route
        val destHex = routed.destination.toHex()
        val nextHop = if (destHex in presenceTracker.allPeerIds()) {
            routed.destination
        } else {
            val route = routingTable.bestRoute(destHex) ?: return
            hexToBytes(route.nextHop)
        }
        scope?.launch { safeSend(nextHop, relayed) }
    }

    private suspend fun handleDeliveryAck(fromPeerId: ByteArray, data: ByteArray) {
        val ack = WireCodec.decodeDeliveryAck(data)
        val key = ack.messageId.toHex()
        // If tracked, use exactly-once guard; if untracked, accept (external ACK)
        val outcome = deliveryTracker.recordOutcome(key, DeliveryOutcome.CONFIRMED)
        if (outcome != null || !deliveryTracker.isTracked(key)) {
            _deliveryConfirmations.emit(Uuid.fromByteArray(ack.messageId))
        }
        // Relay ACK back along reverse path if we were a relay node
        val source = routedMsgSources.remove(key)
        if (source != null) {
            scope?.launch { safeSend(source, data) }
        }
    }
}

internal class ReassemblyState(val totalChunks: Int) {
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
