package ch.trancee.meshlink.messaging

import ch.trancee.meshlink.api.DiagnosticCode
import ch.trancee.meshlink.api.DiagnosticPayload
import ch.trancee.meshlink.api.toPeerIdHex
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.crypto.ReplayGuard
import ch.trancee.meshlink.crypto.TrustStore
import ch.trancee.meshlink.crypto.noise.noiseKOpen
import ch.trancee.meshlink.crypto.noise.noiseKSeal
import ch.trancee.meshlink.routing.DedupSet
import ch.trancee.meshlink.routing.OutboundFrame
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transfer.FailureReason
import ch.trancee.meshlink.transfer.Priority
import ch.trancee.meshlink.transfer.TransferEngine
import ch.trancee.meshlink.transfer.TransferEvent
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.Logger
import ch.trancee.meshlink.wire.Broadcast
import ch.trancee.meshlink.wire.DeliveryAck
import ch.trancee.meshlink.wire.InboundValidator
import ch.trancee.meshlink.wire.Rejected
import ch.trancee.meshlink.wire.RoutedMessage
import ch.trancee.meshlink.wire.Valid
import ch.trancee.meshlink.wire.WireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

private const val BROADCAST_FLAG_HAS_SIGNATURE = 1
private const val ACK_FLAG_HAS_SIGNATURE = 1
private val DEFAULT_HOP_LIMIT: UByte = 5u

/**
 * Maps a [FailureReason] from the transfer layer to a [DeliveryOutcome] for the messaging API.
 *
 * Extracted as a package-internal function so it can be verified directly in unit tests without
 * requiring a full DeliveryPipeline wire-up (the function is pure).
 */
internal fun mapDeliveryFailureReason(reason: FailureReason): DeliveryOutcome =
    when (reason) {
        FailureReason.INACTIVITY_TIMEOUT -> DeliveryOutcome.TIMED_OUT
        FailureReason.MEMORY_PRESSURE -> DeliveryOutcome.SEND_FAILED
        FailureReason.BUFFER_FULL_RETRY_EXHAUSTED -> DeliveryOutcome.SEND_FAILED
        FailureReason.DEGRADATION_PROBE_FAILED -> DeliveryOutcome.SEND_FAILED
        FailureReason.RESUME_FAILED -> DeliveryOutcome.SEND_FAILED
    }

/**
 * Central protocol dispatcher and messaging API.
 *
 * Subscribes to [BleTransport.incomingData] and [TransferEngine.events] to route inbound frames and
 * reassembled payloads; exposes [send] (unicast) and [broadcast] (flood-fill) for outbound
 * messages. All policy layers — rate limiters, circuit breaker, store-and-forward buffer, and
 * Delivery-ACK lifecycle — live here.
 *
 * **Thread-safety**: Not thread-safe. All coroutines must run on the same single-threaded
 * dispatcher, or the caller must provide external synchronisation.
 */
internal class DeliveryPipeline(
    private val scope: CoroutineScope,
    private val transport: BleTransport,
    private val routeCoordinator: RouteCoordinator,
    private val transferEngine: TransferEngine,
    private val inboundValidator: InboundValidator,
    private val localIdentity: Identity,
    private val cryptoProvider: CryptoProvider,
    private val trustStore: TrustStore,
    private val dedupSet: DedupSet,
    private val config: MessagingConfig,
    private val clock: () -> Long,
    private val diagnosticSink: ch.trancee.meshlink.api.DiagnosticSinkApi =
        ch.trancee.meshlink.api.NoOpDiagnosticSink,
) {

    // ── Per-sender replay guard ───────────────────────────────────────────────
    // Keyed by sender keyHash (as List<Byte> for content-equality).
    // A shared ReplayGuard across all senders would incorrectly reject messages
    // from different senders that happen to share the same originationTime counter.
    private val replayGuards = HashMap<List<Byte>, ReplayGuard>()

    private fun checkReplay(senderId: ByteArray, counter: ULong): Boolean =
        replayGuards.getOrPut(senderId.asList()) { ReplayGuard() }.check(counter)

    // ── SharedFlow outputs ────────────────────────────────────────────────────

    private val _messages = MutableSharedFlow<InboundMessage>(extraBufferCapacity = 64)

    /** All received messages — unicast and broadcast. */
    val messages: SharedFlow<InboundMessage> = _messages

    private val _deliveryConfirmations = MutableSharedFlow<Delivered>(extraBufferCapacity = 64)

    /** Emits once per successfully confirmed unicast delivery. */
    val deliveryConfirmations: SharedFlow<Delivered> = _deliveryConfirmations

    private val _transferFailures = MutableSharedFlow<DeliveryFailed>(extraBufferCapacity = 64)

    /** Emits once per terminal delivery failure. */
    val transferFailures: SharedFlow<DeliveryFailed> = _transferFailures

    private val _transferProgress =
        MutableSharedFlow<TransferEvent.ChunkProgress>(extraBufferCapacity = 64)

    /** Chunk-level progress for own (non-relay, non-broadcast) unicast sessions. */
    val transferProgress: SharedFlow<TransferEvent.ChunkProgress> = _transferProgress

    // ── Rate limiters ─────────────────────────────────────────────────────────

    private val outboundUnicastLimiter =
        SlidingWindowRateLimiter(
            config.outboundUnicastLimit,
            config.outboundUnicastWindowMillis,
            clock,
        )
    private val broadcastLimiter =
        SlidingWindowRateLimiter(config.broadcastLimit, config.broadcastWindowMillis, clock)

    // Lazy-instantiated per (sender, neighbor) pair
    private val relayLimiters = HashMap<PeerPair, SlidingWindowRateLimiter>()

    // Lazy-instantiated per neighbour
    private val neighborAggregateLimiters = HashMap<List<Byte>, SlidingWindowRateLimiter>()

    // Lazy-instantiated per inbound sender
    private val inboundSenderLimiters = HashMap<List<Byte>, SlidingWindowRateLimiter>()

    // Lazy-instantiated per unknown peer (for handshake traffic)
    private val handshakeLimiters = HashMap<List<Byte>, SlidingWindowRateLimiter>()

    // Lazy-instantiated per neighbour (for NACK traffic)
    private val nackLimiters = HashMap<List<Byte>, SlidingWindowRateLimiter>()

    // ── Internal state ────────────────────────────────────────────────────────

    /** In-flight unicast deliveries awaiting ACK. Keyed by TransferEngine session = messageId. */
    private val pendingDeliveries = HashMap<MessageIdKey, PendingDelivery>()

    /** Own unicast session IDs — used to filter ChunkProgress events. */
    private val ownUnicastSessions = HashSet<MessageIdKey>()

    /** Store-and-forward buffer (up to [MessagingConfig.maxBufferedMessages]). */
    private val sendBuffer = mutableListOf<BufferedMessage>()

    /** Per-destination circuit breakers. */
    private val circuitBreakers = HashMap<List<Byte>, PerDestinationCircuitBreaker>()

    /** Pending ACKs that could not be sent (no route). Drained when route becomes available. */
    private val ackBuffer = ArrayDeque<AckRetryEntry>()

    /** Monotonic counter used to make generated message IDs unique per clock tick. */
    private var msgIdCounter = 0L

    // ── Init: background coroutines ───────────────────────────────────────────

    init {
        // 1. Inbound dispatch
        scope.launch {
            transport.incomingData.collect { incoming ->
                handleIncomingData(incoming.peerId, incoming.data)
            }
        }

        // 2. Forward outbound chunks to transport
        scope.launch { transferEngine.outboundChunks.collect(::forwardOutboundChunk) }

        // 3. Handle TransferEngine events
        scope.launch { transferEngine.events.collect { event -> handleTransferEvent(event) } }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Send [payload] to [recipient]. Returns [SendResult.Sent] when accepted for immediate
     * transmission or [SendResult.Queued] when deferred.
     *
     * Decision tree: self-send → rate limit → circuit breaker → size → encrypt → route → transfer.
     *
     * @throws IllegalArgumentException if [payload] exceeds [MessagingConfig.maxMessageSize].
     * @throws IllegalStateException if the recipient's X25519 key is not pinned.
     */
    suspend fun send(
        recipient: ByteArray,
        payload: ByteArray,
        priority: Priority = Priority.NORMAL,
    ): SendResult {
        // Opportunistically drain buffered messages on every send call.
        drainBuffer()

        // Self-send shortcut — no BLE involved.
        if (recipient.contentEquals(localIdentity.keyHash)) {
            val msgId = generateMessageId()
            _messages.tryEmit(
                InboundMessage(
                    messageId = msgId,
                    senderId = localIdentity.keyHash,
                    payload = payload,
                    priority = priority,
                    receivedAt = clock(),
                    kind = MessageKind.UNICAST,
                )
            )
            _deliveryConfirmations.tryEmit(Delivered(msgId))
            return SendResult.Sent
        }

        // Rate limit check.
        if (!outboundUnicastLimiter.tryAcquire()) return SendResult.Queued(QueuedReason.PAUSED)

        // Circuit breaker check.
        val cbKey = recipient.asList()
        val cb =
            circuitBreakers.getOrPut(cbKey) {
                PerDestinationCircuitBreaker(config.circuitBreaker, clock)
            }
        if (!cb.canSend()) return SendResult.Queued(QueuedReason.PAUSED)

        // Size check — fail fast before encryption.
        if (payload.size > config.maxMessageSize) {
            throw IllegalArgumentException(
                "Payload size ${payload.size} exceeds maxMessageSize ${config.maxMessageSize}"
            )
        }

        // Retrieve recipient's X25519 public key for Noise K seal.
        val recipientPubKey =
            trustStore.getPinnedKey(recipient)
                ?: throw IllegalStateException("Unknown recipient: X25519 key not pinned")

        // Noise K seal: encrypt user payload.
        val sealedPayload =
            noiseKSeal(cryptoProvider, localIdentity.dhKeyPair, recipientPubKey, payload)

        // Build the outer RoutedMessage.
        val messageId = generateMessageId()
        val routedMessage =
            RoutedMessage(
                messageId = messageId,
                origin = localIdentity.keyHash,
                destination = recipient,
                hopLimit = DEFAULT_HOP_LIMIT,
                visitedList = listOf(localIdentity.keyHash),
                priority = priority.wire,
                originationTime = clock().toULong(),
                payload = sealedPayload,
            )
        val encoded = WireCodec.encode(routedMessage)

        // Retrieve recipient's Ed25519 key for future ACK verification.
        val recipientEdPubKey = routeCoordinator.lookupEdPublicKey(recipient) ?: ByteArray(32)

        // Compute TTL.
        val ttlMillis =
            when (priority) {
                Priority.HIGH -> config.highPriorityTtlMillis
                Priority.NORMAL -> config.normalPriorityTtlMillis
                Priority.LOW -> config.lowPriorityTtlMillis
            }
        val expiresAt = clock() + ttlMillis

        // Route lookup.
        val nextHop = routeCoordinator.lookupNextHop(recipient)
        if (nextHop == null) {
            // No route — buffer for later.
            pendingDeliveries[MessageIdKey(messageId)] =
                PendingDelivery(recipient, recipientEdPubKey, expiresAt, cb)
            evictBufferIfFull()
            sendBuffer.add(
                BufferedMessage(messageId, recipient, encoded, priority, clock(), expiresAt)
            )
            return SendResult.Queued(QueuedReason.ROUTE_PENDING)
        }

        // Send via TransferEngine.
        transferEngine.send(messageId, encoded, nextHop, priority)
        pendingDeliveries[MessageIdKey(messageId)] =
            PendingDelivery(recipient, recipientEdPubKey, expiresAt, cb)
        ownUnicastSessions.add(MessageIdKey(messageId))
        // Start a delivery-ACK deadline timer. If no DeliveryAck arrives within one
        // inactivity-timeout window, treat the pending delivery as timed out.
        val ackDeadline = transferEngine.ackDeadlineMillis
        val capturedMsgId = messageId.copyOf()
        scope.launch {
            delay(ackDeadline)
            processAckDeadline(MessageIdKey(capturedMsgId), cb, capturedMsgId)
        }
        return SendResult.Sent
    }

    /**
     * Flood-fill [payload] to all connected neighbours, signing if
     * [MessagingConfig.requireBroadcastSignatures] is set.
     *
     * @return The 16-byte message ID assigned to this broadcast.
     * @throws IllegalStateException if the broadcast rate limit is exceeded.
     * @throws IllegalArgumentException if [payload] exceeds [MessagingConfig.maxBroadcastSize].
     */
    suspend fun broadcast(
        payload: ByteArray,
        maxHops: UByte = config.broadcastTtl,
        priority: Priority = Priority.NORMAL,
    ): ByteArray {
        if (!broadcastLimiter.tryAcquire()) {
            throw IllegalStateException("Broadcast rate limit exceeded")
        }
        if (payload.size > config.maxBroadcastSize) {
            throw IllegalArgumentException(
                "Broadcast payload size ${payload.size} exceeds maxBroadcastSize ${config.maxBroadcastSize}"
            )
        }

        val messageId = generateMessageId()

        val hasSig = config.requireBroadcastSignatures
        val signedData = messageId + localIdentity.keyHash + config.appIdHash + payload
        val signature: ByteArray?
        val signerKey: ByteArray?
        val flags: UByte
        if (hasSig) {
            signature = cryptoProvider.sign(localIdentity.edKeyPair.privateKey, signedData)
            signerKey = localIdentity.edKeyPair.publicKey
            flags = BROADCAST_FLAG_HAS_SIGNATURE.toUByte()
        } else {
            signature = null
            signerKey = null
            flags = 0u
        }

        val b0 = config.appIdHash[0].toUByte().toUInt()
        val b1 = if (config.appIdHash.size >= 2) config.appIdHash[1].toUByte().toUInt() else 0u
        val appIdHashShort = (b0 or (b1 shl 8)).toUShort()

        val broadcastMsg =
            Broadcast(
                messageId = messageId,
                origin = localIdentity.keyHash,
                remainingHops = maxHops,
                appIdHash = appIdHashShort,
                flags = flags,
                priority = priority.wire,
                signature = signature,
                signerKey = signerKey,
                payload = payload,
            )

        // Record in dedup to prevent self-relay when the broadcast echoes back.
        dedupSet.add(messageId)

        val encoded = WireCodec.encode(broadcastMsg)
        for (peer in routeCoordinator.connectedPeers()) {
            val sessionId = generateMessageId()
            transferEngine.send(sessionId, encoded, peer, priority)
        }

        return messageId
    }

    // ── Inbound handlers ──────────────────────────────────────────────────────

    private fun handleIncomingData(fromPeerId: ByteArray, data: ByteArray) {
        when (val result = inboundValidator.validate(data)) {
            is Rejected -> {
                diagnosticSink.emit(DiagnosticCode.MALFORMED_DATA) {
                    DiagnosticPayload.MalformedData(reason = "InboundValidator rejected frame")
                }
                return
            }
            is Valid ->
                when (val msg = result.message) {
                    is ch.trancee.meshlink.wire.Handshake -> return
                    is ch.trancee.meshlink.wire.Keepalive -> return
                    is ch.trancee.meshlink.wire.RotationAnnouncementMessage -> return
                    is ch.trancee.meshlink.wire.Hello -> {
                        routeCoordinator.processInbound(fromPeerId, msg)
                        drainBuffer()
                        drainAckBuffer()
                    }
                    is ch.trancee.meshlink.wire.Update -> {
                        routeCoordinator.processInbound(fromPeerId, msg)
                        drainBuffer()
                        drainAckBuffer()
                    }
                    is ch.trancee.meshlink.wire.Chunk ->
                        transferEngine.onIncomingChunk(fromPeerId, msg)
                    is ch.trancee.meshlink.wire.ChunkAck ->
                        transferEngine.onIncomingChunkAck(fromPeerId, msg)
                    is ch.trancee.meshlink.wire.Nack -> {
                        val nackLimiter =
                            nackLimiters.getOrPut(fromPeerId.asList()) {
                                SlidingWindowRateLimiter(
                                    config.nackLimit,
                                    config.nackWindowMillis,
                                    clock,
                                )
                            }
                        if (nackLimiter.tryAcquire()) {
                            transferEngine.onIncomingNack(fromPeerId, msg)
                        }
                    }
                    is ch.trancee.meshlink.wire.ResumeRequest ->
                        transferEngine.onIncomingResumeRequest(fromPeerId, msg)
                    is RoutedMessage -> handleInboundRoutedMessage(fromPeerId, msg)
                    is Broadcast -> handleInboundBroadcast(fromPeerId, msg)
                    is DeliveryAck -> handleInboundDeliveryAck(msg)
                }
        }
    }

    private fun handleInboundRoutedMessage(fromPeerId: ByteArray, msg: RoutedMessage) {
        if (msg.destination.contentEquals(localIdentity.keyHash)) {
            // Message is addressed to us — decrypt and deliver.
            val senderPubKey = trustStore.getPinnedKey(msg.origin) ?: return
            val plaintext =
                try {
                    noiseKOpen(cryptoProvider, localIdentity.dhKeyPair, senderPubKey, msg.payload)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    val reason = e.message ?: "AEAD verification failed"
                    diagnosticSink.emit(DiagnosticCode.DECRYPTION_FAILED) {
                        DiagnosticPayload.DecryptionFailed(
                            peerId = msg.origin.toPeerIdHex(),
                            reason = reason,
                        )
                    }
                    return // AEAD tag verification failed — drop.
                }
            // Replay guard uses originationTime as the monotonic counter.
            if (!checkReplay(msg.origin, msg.originationTime)) {
                diagnosticSink.emit(DiagnosticCode.REPLAY_REJECTED) {
                    DiagnosticPayload.ReplayRejected(peerId = msg.origin.toPeerIdHex())
                }
                return
            }

            // DeliveryAck messages are routed back to the original sender wrapped inside a
            // RoutedMessage so they traverse relay nodes correctly. Detect them here and
            // process the inner ACK without emitting to the app layer or sending a re-ACK
            // (which would create an infinite ACK loop).
            val innerMsg =
                try {
                    WireCodec.decode(plaintext)
                } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                    null
                }
            if (innerMsg is DeliveryAck) {
                handleInboundDeliveryAck(innerMsg)
                return
            }

            val senderHex =
                msg.origin.take(4).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
            Logger.d("MeshLink", "message received sender=$senderHex... size=${plaintext.size}")
            _messages.tryEmit(
                InboundMessage(
                    messageId = msg.messageId,
                    senderId = msg.origin,
                    payload = plaintext,
                    priority = Priority.fromWire(msg.priority),
                    receivedAt = clock(),
                    kind = MessageKind.UNICAST,
                )
            )
            // Send delivery ACK asynchronously with retries.
            // senderPubKey is captured here (already validated non-null above) so
            // sendDeliveryAckWithRetry can use it directly without a second null check.
            scope.launch { sendDeliveryAckWithRetry(msg.messageId, msg.origin, senderPubKey) }
        } else {
            // Not for us — relay if possible.
            if (msg.hopLimit == 0u.toUByte()) {
                diagnosticSink.emit(DiagnosticCode.HOP_LIMIT_EXCEEDED) {
                    DiagnosticPayload.HopLimitExceeded(
                        hops = msg.visitedList.size,
                        maxHops = DEFAULT_HOP_LIMIT.toInt(),
                    )
                }
                return
            }
            if (msg.visitedList.any { it.contentEquals(localIdentity.keyHash) }) {
                diagnosticSink.emit(DiagnosticCode.LOOP_DETECTED) {
                    DiagnosticPayload.LoopDetected(destination = msg.destination.toPeerIdHex())
                }
                return
            }
            val nextHop = routeCoordinator.lookupNextHop(msg.destination) ?: return

            // Relay rate limiters.
            val senderKey = MessageIdKey(msg.origin)
            val neighborKey = MessageIdKey(fromPeerId)
            val relayKey = PeerPair(neighborKey, senderKey)
            val relayLimiter =
                relayLimiters.getOrPut(relayKey) {
                    SlidingWindowRateLimiter(
                        config.relayPerSenderPerNeighborLimit,
                        config.relayPerSenderPerNeighborWindowMillis,
                        clock,
                    )
                }
            if (!relayLimiter.tryAcquire()) return
            val neighborAggLimiter =
                neighborAggregateLimiters.getOrPut(fromPeerId.asList()) {
                    SlidingWindowRateLimiter(
                        config.perNeighborAggregateLimit,
                        config.perNeighborAggregateWindowMillis,
                        clock,
                    )
                }
            if (!neighborAggLimiter.tryAcquire()) return

            val newHopLimit = (msg.hopLimit.toUInt() - 1u).toUByte()
            val newVisited = msg.visitedList + localIdentity.keyHash
            val relayMsg = msg.copy(hopLimit = newHopLimit, visitedList = newVisited)
            val relaySessionId = generateMessageId()
            transferEngine.send(
                relaySessionId,
                WireCodec.encode(relayMsg),
                nextHop,
                Priority.fromWire(msg.priority),
            )
        }
    }

    private fun handleInboundBroadcast(fromPeerId: ByteArray, msg: Broadcast) {
        // Dedup: drop if already seen.
        if (dedupSet.isDuplicate(msg.messageId)) return
        dedupSet.add(msg.messageId)

        // Per-sender inbound rate limit.
        val senderLimiter =
            inboundSenderLimiters.getOrPut(msg.origin.asList()) {
                SlidingWindowRateLimiter(
                    config.perSenderInboundLimit,
                    config.perSenderInboundWindowMillis,
                    clock,
                )
            }
        if (!senderLimiter.tryAcquire()) return

        // Signature verification.
        val hasSignature = (msg.flags.toInt() and BROADCAST_FLAG_HAS_SIGNATURE) != 0
        if (hasSignature) {
            val sig = msg.signature ?: return
            val key = msg.signerKey ?: return
            val signedData = msg.messageId + msg.origin + config.appIdHash + msg.payload
            if (!cryptoProvider.verify(key, signedData, sig)) return
        } else if (config.requireBroadcastSignatures && !config.allowUnsignedBroadcasts) {
            return
        }

        // Deliver to this node — skip if we are the origin (we already know our own payload).
        if (!msg.origin.contentEquals(localIdentity.keyHash)) {
            _messages.tryEmit(
                InboundMessage(
                    messageId = msg.messageId,
                    senderId = msg.origin,
                    payload = msg.payload,
                    priority = Priority.fromWire(msg.priority),
                    receivedAt = clock(),
                    kind = MessageKind.BROADCAST,
                )
            )
        }

        // Relay if hops remain.
        if (msg.remainingHops == 0u.toUByte()) return
        val relayMsg = msg.copy(remainingHops = (msg.remainingHops.toUInt() - 1u).toUByte())
        val encoded = WireCodec.encode(relayMsg)
        for (peer in routeCoordinator.connectedPeers()) {
            if (peer.contentEquals(fromPeerId)) continue
            val relayLimiter =
                relayLimiters.getOrPut(PeerPair(MessageIdKey(peer), MessageIdKey(msg.origin))) {
                    SlidingWindowRateLimiter(
                        config.relayPerSenderPerNeighborLimit,
                        config.relayPerSenderPerNeighborWindowMillis,
                        clock,
                    )
                }
            if (!relayLimiter.tryAcquire()) continue
            val neighborAggLimiter =
                neighborAggregateLimiters.getOrPut(peer.asList()) {
                    SlidingWindowRateLimiter(
                        config.perNeighborAggregateLimit,
                        config.perNeighborAggregateWindowMillis,
                        clock,
                    )
                }
            if (!neighborAggLimiter.tryAcquire()) continue
            val sessionId = generateMessageId()
            transferEngine.send(sessionId, encoded, peer, Priority.fromWire(msg.priority))
        }
    }

    private fun handleInboundDeliveryAck(msg: DeliveryAck) {
        val key = MessageIdKey(msg.messageId)
        val pending = pendingDeliveries[key] ?: return // unknown message ID — silently drop

        // Signature verification.
        // Only verify if the recipient's Ed25519 key is known (non-zero). When the key is
        // all-zeros it means the routing protocol has not yet propagated the Ed25519 key for
        // this peer (e.g., direct neighbour whose key was set during handshake but not yet
        // received via a routing Update). In that case we accept the ACK without verification
        // rather than silently dropping it — the Noise K seal on the enclosing RoutedMessage
        // already provides authentication of the ACK envelope.
        val hasSignature = (msg.flags.toInt() and ACK_FLAG_HAS_SIGNATURE) != 0
        val keyKnown = pending.recipientEdPubKey.any { it != 0.toByte() }
        if (hasSignature && keyKnown) {
            val sig = msg.signature ?: return
            val signedData = msg.messageId + msg.recipientId
            if (!cryptoProvider.verify(pending.recipientEdPubKey, signedData, sig)) return
        }

        // Single-signal rule: remove then emit.
        pendingDeliveries.remove(key)
        ownUnicastSessions.remove(key)
        // Signal circuit-breaker success so the CB closes after a confirmed delivery.
        pending.cb.onSuccess()
        _deliveryConfirmations.tryEmit(Delivered(msg.messageId))
    }

    private fun handleAssemblyComplete(
        sessionId: ByteArray,
        payload: ByteArray,
        fromPeerId: ByteArray,
    ) {
        val wireMsg =
            try {
                WireCodec.decode(payload)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                return
            }
        when (wireMsg) {
            is RoutedMessage -> handleInboundRoutedMessage(fromPeerId, wireMsg)
            is Broadcast -> handleInboundBroadcast(fromPeerId, wireMsg)
            is DeliveryAck -> handleInboundDeliveryAck(wireMsg)
            else -> {
                diagnosticSink.emit(DiagnosticCode.UNKNOWN_MESSAGE_TYPE) {
                    DiagnosticPayload.UnknownMessageType(typeCode = -1)
                }
                return
            }
        }
    }

    private fun handleTransferEvent(event: TransferEvent) {
        when (event) {
            is TransferEvent.AssemblyComplete ->
                handleAssemblyComplete(event.messageId, event.payload, event.fromPeerId)
            is TransferEvent.TransferFailed -> {
                val key = MessageIdKey(event.messageId)
                val pending = pendingDeliveries[key] ?: return
                pendingDeliveries.remove(key)
                ownUnicastSessions.remove(key)
                pending.cb.onFailure(event.reason)
                _transferFailures.tryEmit(
                    DeliveryFailed(event.messageId, mapDeliveryFailureReason(event.reason))
                )
            }
            is TransferEvent.ChunkProgress -> {
                if (ownUnicastSessions.contains(MessageIdKey(event.messageId))) {
                    _transferProgress.tryEmit(event)
                }
            }
        }
    }

    // ── ACK sending with retry ────────────────────────────────────────────────

    private suspend fun sendDeliveryAckWithRetry(
        originalMessageId: ByteArray,
        targetId: ByteArray,
        senderKey: ByteArray,
    ) {
        val signature =
            cryptoProvider.sign(
                localIdentity.edKeyPair.privateKey,
                originalMessageId + localIdentity.keyHash,
            )
        val ack =
            DeliveryAck(
                messageId = originalMessageId,
                recipientId = localIdentity.keyHash,
                flags = ACK_FLAG_HAS_SIGNATURE.toUByte(),
                signature = signature,
            )
        val encodedAck = WireCodec.encode(ack)

        val retryDelays = longArrayOf(2_000L, 4_000L, 8_000L)
        for (delayMillis in retryDelays) {
            val nextHop = routeCoordinator.lookupNextHop(targetId)
            if (nextHop != null) {
                // Wrap the ACK in a RoutedMessage so relay nodes forward it hop-by-hop
                // back to the original sender. A raw DeliveryAck would be silently dropped
                // by relay nodes that don't have the message ID in their pendingDeliveries.
                val sealed =
                    noiseKSeal(cryptoProvider, localIdentity.dhKeyPair, senderKey, encodedAck)
                val routedAck =
                    RoutedMessage(
                        messageId = generateMessageId(),
                        origin = localIdentity.keyHash,
                        destination = targetId,
                        hopLimit = DEFAULT_HOP_LIMIT,
                        visitedList = listOf(localIdentity.keyHash),
                        priority = Priority.NORMAL.wire,
                        originationTime = clock().toULong(),
                        payload = sealed,
                    )
                val payload = WireCodec.encode(routedAck)
                transferEngine.send(generateMessageId(), payload, nextHop, Priority.NORMAL)
                return
            }
            delay(delayMillis)
        }
        // All retries failed — buffer for later delivery.
        if (ackBuffer.size < 50) {
            ackBuffer.addLast(AckRetryEntry(originalMessageId, encodedAck, targetId))
        }
    }

    private fun drainAckBuffer() {
        val it = ackBuffer.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val nextHop = routeCoordinator.lookupNextHop(entry.targetId) ?: continue
            it.remove()
            transferEngine.send(generateMessageId(), entry.encodedAck, nextHop, Priority.NORMAL)
        }
    }

    // ── Store-and-forward buffer ──────────────────────────────────────────────

    private fun drainBuffer() {
        val now = clock()
        val it = sendBuffer.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now >= entry.expiresAt) {
                it.remove()
                pendingDeliveries.remove(MessageIdKey(entry.messageId))
                _transferFailures.tryEmit(
                    DeliveryFailed(entry.messageId, DeliveryOutcome.TIMED_OUT)
                )
                continue
            }
            val nextHop = routeCoordinator.lookupNextHop(entry.recipientId) ?: continue
            it.remove()
            transferEngine.send(
                entry.messageId,
                entry.encodedRoutedMessage,
                nextHop,
                entry.priority,
            )
            ownUnicastSessions.add(MessageIdKey(entry.messageId))
        }
    }

    private fun evictBufferIfFull() {
        // Note: drainBuffer() is always called at the beginning of send(), so all expired entries
        // are already removed before evictBufferIfFull() is reached. Only capacity eviction needed.
        while (sendBuffer.size >= config.maxBufferedMessages) {
            val victim =
                sendBuffer.minWithOrNull(
                    compareBy<BufferedMessage> { it.priority.wire }.thenBy { it.enqueuedAt }
                ) ?: break
            sendBuffer.remove(victim)
            pendingDeliveries.remove(MessageIdKey(victim.messageId))
            _transferFailures.tryEmit(DeliveryFailed(victim.messageId, DeliveryOutcome.TIMED_OUT))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Called when the ack-deadline timer fires for a pending delivery. If the delivery has already
     * been confirmed (pendingDeliveries no longer contains [key]), this is a no-op. Otherwise the
     * pending delivery is timed out and a [DeliveryFailed] is emitted.
     *
     * Uses `remove()?.let {}` instead of `containsKey()+remove()` to avoid JaCoCo phantom branches
     * in the coroutine state machine bytecode.
     */
    private fun processAckDeadline(
        key: MessageIdKey,
        cb: PerDestinationCircuitBreaker,
        capturedMsgId: ByteArray,
    ) {
        pendingDeliveries.remove(key)?.let {
            // Delivery not yet confirmed — treat as timed out.
            ownUnicastSessions.remove(key)
            cb.onFailure(FailureReason.INACTIVITY_TIMEOUT)
            diagnosticSink.emit(DiagnosticCode.DELIVERY_TIMEOUT) {
                DiagnosticPayload.DeliveryTimeout(messageIdHex = capturedMsgId.toPeerIdHex().hex)
            }
            _transferFailures.tryEmit(DeliveryFailed(capturedMsgId, DeliveryOutcome.TIMED_OUT))
        }
    }

    /**
     * Forwards a [TransferEngine] outbound frame to the BLE transport. [TransferEngine] always
     * provides a non-null [OutboundFrame.peerId]; the `!!` assertion enforces this contract.
     */
    private suspend fun forwardOutboundChunk(frame: OutboundFrame) {
        val result = transport.sendToPeer(frame.peerId!!, WireCodec.encode(frame.message))
        if (result is ch.trancee.meshlink.transport.SendResult.Failure) {
            diagnosticSink.emit(DiagnosticCode.SEND_FAILED) {
                DiagnosticPayload.SendFailed(
                    peerId = frame.peerId.toPeerIdHex(),
                    reason = result.reason,
                )
            }
        }
    }

    private fun generateMessageId(): ByteArray {
        val ts = clock()
        val counter = msgIdCounter++
        return ByteArray(16).also { id ->
            for (i in 0 until 8) id[i] = ((ts shr (i * 8)) and 0xFF).toByte()
            for (i in 0 until 8) id[8 + i] = ((counter shr (i * 8)) and 0xFF).toByte()
        }
    }

    // ── Internal data structures ──────────────────────────────────────────────

    private class PendingDelivery(
        val recipientId: ByteArray,
        val recipientEdPubKey: ByteArray,
        val expiresAt: Long,
        val cb: PerDestinationCircuitBreaker,
    )

    private class BufferedMessage(
        val messageId: ByteArray,
        val recipientId: ByteArray,
        val encodedRoutedMessage: ByteArray,
        val priority: Priority,
        val enqueuedAt: Long,
        val expiresAt: Long,
    )

    private class AckRetryEntry(
        val originalMessageId: ByteArray,
        val encodedAck: ByteArray,
        val targetId: ByteArray,
    )

    private enum class CircuitBreakerState {
        CLOSED,
        OPEN,
        HALF_OPEN,
    }

    private class PerDestinationCircuitBreaker(
        private val cbConfig: MessagingConfig.CircuitBreakerConfig,
        private val clock: () -> Long,
    ) {
        private var state = CircuitBreakerState.CLOSED
        private var openedAt = 0L
        private val failureTimestamps = ArrayDeque<Long>()

        fun canSend(): Boolean {
            if (state == CircuitBreakerState.CLOSED) return true
            if (state == CircuitBreakerState.OPEN) {
                if (clock() - openedAt >= cbConfig.cooldownMillis) {
                    state = CircuitBreakerState.HALF_OPEN
                    return true
                }
                return false
            }
            // HALF_OPEN — allow one probe.
            return true
        }

        fun onSuccess() {
            state = CircuitBreakerState.CLOSED
            failureTimestamps.clear()
        }

        fun onFailure(reason: FailureReason) {
            if (reason == FailureReason.MEMORY_PRESSURE) return
            val now = clock()
            // Evict expired timestamps first so the list may become empty before we add the new
            // one.
            // (If addLast ran first, `now - now = 0` can never exceed windowMillis, making the
            // isNotEmpty()=false exit path unreachable in JaCoCo's coverage tracking.)
            while (
                failureTimestamps.isNotEmpty() &&
                    now - failureTimestamps.first() > cbConfig.windowMillis
            ) {
                failureTimestamps.removeFirst()
            }
            failureTimestamps.addLast(now)
            if (
                state == CircuitBreakerState.HALF_OPEN ||
                    failureTimestamps.size >= cbConfig.maxFailures
            ) {
                state = CircuitBreakerState.OPEN
                openedAt = now
                failureTimestamps.clear()
            }
        }
    }
}
