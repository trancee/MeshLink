package io.meshlink.dispatch

import io.meshlink.config.MeshLinkConfig
import io.meshlink.crypto.RotationResult
import io.meshlink.crypto.SecurityEngine
import io.meshlink.delivery.AckResult
import io.meshlink.delivery.DeliveryPipeline
import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.diagnostics.Severity
import io.meshlink.routing.NextHopResult
import io.meshlink.routing.RoutingEngine
import io.meshlink.transfer.ChunkAcceptResult
import io.meshlink.transfer.TransferEngine
import io.meshlink.transfer.TransferUpdate
import io.meshlink.util.DeliveryOutcome
import io.meshlink.util.PauseManager
import io.meshlink.util.toHex
import io.meshlink.util.toKey
import io.meshlink.wire.RotationAnnouncement
import io.meshlink.wire.WireCodec

/**
 * Dispatches inbound BLE frames to typed handlers. Owns the dispatch table,
 * decode pipeline, and handler implementations. Pre-dispatch validation
 * (signatures, replay, loops, rate limits) is delegated to [InboundValidator].
 * Side-effectful actions (flow emissions, transport sends) are delegated to
 * [DispatchSink].
 */
internal class MessageDispatcher(
    private val securityEngine: SecurityEngine?,
    private val routingEngine: RoutingEngine,
    private val transferEngine: TransferEngine,
    private val deliveryPipeline: DeliveryPipeline,
    private val validator: InboundValidator,
    private val pauseManager: PauseManager,
    private val diagnosticSink: DiagnosticSink,
    private val localPeerId: ByteArray,
    private val config: MeshLinkConfig,
    private val outboundTracker: OutboundTracker,
    private val sink: DispatchSink,
    private val unwrapPayload: (ByteArray) -> ByteArray = { it },
) {
    suspend fun dispatch(fromPeerId: ByteArray, data: ByteArray) {
        if (data.isEmpty()) return
        try {
            when (data[0]) {
                WireCodec.TYPE_HANDSHAKE -> handleHandshake(fromPeerId, data)
                WireCodec.TYPE_CHUNK -> handleChunk(fromPeerId, data)
                WireCodec.TYPE_CHUNK_ACK -> handleChunkAck(data)
                WireCodec.TYPE_BROADCAST -> handleBroadcast(fromPeerId, data)
                WireCodec.TYPE_ROUTE_REQUEST -> handleHello(fromPeerId, data)
                WireCodec.TYPE_ROUTE_REPLY -> handleUpdate(fromPeerId, data)
                WireCodec.TYPE_ROUTED_MESSAGE -> handleRoutedMessage(fromPeerId, data)
                WireCodec.TYPE_DELIVERY_ACK -> handleDeliveryAck(data)
                WireCodec.TYPE_RESUME_REQUEST -> handleResumeRequest(data)
                WireCodec.TYPE_KEEPALIVE -> handleKeepalive(fromPeerId, data)
                WireCodec.TYPE_NACK -> { /* NACK received — no-op for now */ }
                WireCodec.TYPE_ROTATION -> handleRotationAnnouncement(fromPeerId, data)
                else -> {
                    diagnosticSink.emit(
                        DiagnosticCode.UNKNOWN_MESSAGE_TYPE,
                        Severity.WARN,
                        "type=0x${data[0].toUByte().toString(16).padStart(2, '0')}, size=${data.size}",
                    )
                }
            }
        } catch (e: Exception) {
            diagnosticSink.emit(
                DiagnosticCode.MALFORMED_DATA,
                Severity.WARN,
                "type=0x${data[0].toUByte().toString(16)}, size=${data.size}, error=${e.message}",
            )
        }
    }

    private fun handleKeepalive(fromPeerId: ByteArray, data: ByteArray) {
        WireCodec.decodeKeepalive(data)
        routingEngine.peerSeen(fromPeerId.toKey())
    }

    private suspend fun handleHandshake(fromPeerId: ByteArray, data: ByteArray) {
        val se = securityEngine ?: return
        val response = se.handleHandshakeMessage(fromPeerId, data)
        if (response != null) {
            sink.sendFrame(fromPeerId, response)
        }
    }

    private suspend fun handleHello(fromPeerId: ByteArray, data: ByteArray) {
        val hello = WireCodec.decodeHello(data)
        val updates = routingEngine.handleHello(
            fromPeerId = fromPeerId.toKey(),
            senderSeqNo = hello.seqNo,
        )
        // Send routing table updates back to the new neighbor
        for (update in updates) {
            sink.sendFrame(fromPeerId, update)
        }
    }

    private suspend fun handleUpdate(fromPeerId: ByteArray, data: ByteArray) {
        val update = WireCodec.decodeUpdate(data)
        val destKey = update.destination.toKey()
        val propagatedKey = routingEngine.handleUpdate(
            fromPeerId = fromPeerId.toKey(),
            destination = destKey,
            metric = update.metric,
            seqNo = update.seqNo,
            publicKey = update.publicKey,
        )
        // If a new public key was propagated, register it with the security engine
        if (propagatedKey != null) {
            securityEngine?.registerPeerKey(destKey, propagatedKey)
        }
    }

    private suspend fun handleChunk(fromPeerId: ByteArray, data: ByteArray) {
        val chunk = WireCodec.decodeChunk(data)
        val key = chunk.messageId.toKey()

        val totalChunks = chunk.totalChunks?.toInt()
            ?: transferEngine.getInboundTotalChunks(key)
            ?: run {
                diagnosticSink.emit(DiagnosticCode.MALFORMED_DATA, Severity.WARN, "non-first chunk without prior first chunk")
                return
            }

        val result = transferEngine.onChunkReceived(
            key,
            chunk.sequenceNumber.toInt(),
            totalChunks,
            chunk.payload,
        )

        when (result) {
            is ChunkAcceptResult.Rejected -> {
                diagnosticSink.emit(DiagnosticCode.BUFFER_PRESSURE, Severity.WARN, "inbound session limit reached")
                return
            }
            is ChunkAcceptResult.Ack -> {
                val ack = WireCodec.encodeChunkAck(
                    messageId = chunk.messageId,
                    ackSequence = result.ackSeq.toUShort(),
                    sackBitmask = result.sackBitmask,
                    sackBitmaskHigh = result.sackBitmaskHigh,
                )
                sink.sendFrame(fromPeerId, ack)
            }
            is ChunkAcceptResult.MessageComplete -> {
                val ack = WireCodec.encodeChunkAck(
                    messageId = chunk.messageId,
                    ackSequence = result.ackSeq.toUShort(),
                    sackBitmask = result.sackBitmask,
                    sackBitmaskHigh = result.sackBitmaskHigh,
                )
                sink.sendFrame(fromPeerId, ack)

                if (routingEngine.isDuplicate(key)) return

                val decrypted = validator.unsealPayload(
                    result.reassembledPayload,
                    "chunk reassembly",
                    fromPeerId.toKey(),
                ) ?: return
                sink.onMessageReceived(fromPeerId, unwrapPayload(decrypted))
            }
        }
    }

    private suspend fun handleChunkAck(data: ByteArray) {
        val ack = WireCodec.decodeChunkAck(data)
        val key = ack.messageId.toKey()
        val recipient = outboundTracker.recipient(key)

        val update = transferEngine.onAck(key, ack.ackSequence.toInt(), ack.sackBitmask, ack.sackBitmaskHigh)

        when (update) {
            is TransferUpdate.Complete -> {
                outboundTracker.removeRecipient(key)
                deliveryPipeline.cancelDeadline(key)
                sink.onOutboundComplete(key, ack.messageId)
                sink.onTransferProgress(ack.messageId, update.ackedCount, update.totalChunks)
                if (deliveryPipeline.recordFailure(key, DeliveryOutcome.CONFIRMED)) {
                    sink.onDeliveryConfirmed(ack.messageId)
                }
            }
            is TransferUpdate.Progress -> {
                sink.onTransferProgress(ack.messageId, update.ackedCount, update.totalChunks)
                if (recipient != null) {
                    sink.dispatchChunks(recipient, update.chunksToSend, ack.messageId)
                }
            }
            is TransferUpdate.Unknown -> {
                sink.onDeliveryConfirmed(ack.messageId)
            }
        }
    }

    private suspend fun handleBroadcast(fromPeerId: ByteArray, data: ByteArray) {
        val broadcast = WireCodec.decodeBroadcast(data)
        val key = broadcast.messageId.toKey()

        if (!validator.checkAppId(key, broadcast.appIdHash)) return

        val signedData = broadcast.messageId + broadcast.origin + broadcast.appIdHash + broadcast.payload
        if (!validator.validateBroadcastSignature(broadcast.signature, broadcast.signerPublicKey, signedData)) return

        if (routingEngine.isDuplicate(key)) return

        sink.onMessageReceived(broadcast.origin, broadcast.payload)

        if (broadcast.remainingHops > 0u && !pauseManager.isPaused) {
            val reflooded = WireCodec.encodeBroadcast(
                messageId = broadcast.messageId,
                origin = broadcast.origin,
                remainingHops = minOf((broadcast.remainingHops - 1u).toUByte(), config.broadcastTtl),
                appIdHash = broadcast.appIdHash,
                payload = broadcast.payload,
                signature = broadcast.signature,
                signerPublicKey = broadcast.signerPublicKey,
                priority = broadcast.priority,
            )
            val senderId = fromPeerId.toKey()
            for (peerId in routingEngine.allPeerIds()) {
                if (peerId != senderId) {
                    sink.sendFrame(peerId.bytes, reflooded)
                }
            }
        }
    }

    private suspend fun handleRoutedMessage(fromPeerId: ByteArray, data: ByteArray) {
        val routed = WireCodec.decodeRoutedMessage(data)
        val key = routed.messageId.toKey()
        val originId = routed.origin.toKey()

        if (routingEngine.isDuplicate(key)) return
        if (!validator.checkReplay(key, originId, routed.replayCounter)) return
        if (!validator.checkLoop(key, routed.visitedList, originId)) return

        if (routed.destination.contentEquals(localPeerId)) {
            if (!validator.checkInboundRate(originId)) return
            // Try E2E decryption.  When the sender had our public key
            // (e.g. via key distribution), the payload is Noise-K sealed.
            // When the sender did NOT have our key (non-adjacent peer
            // without key distribution), the payload is plaintext.
            // Accept both: try unseal → on failure, deliver as-is.
            val deliveredPayload = validator.unsealOrPassthrough(
                routed.payload,
                routed.origin.toKey(),
            )
            sink.onMessageReceived(routed.origin, unwrapPayload(deliveredPayload))
            if (config.deliveryAckEnabled) {
                val signed = securityEngine?.sign(routed.messageId + localPeerId)
                val ackFrame = WireCodec.encodeDeliveryAck(
                    routed.messageId,
                    localPeerId,
                    signature = signed?.signature ?: ByteArray(0),
                    signerPublicKey = signed?.signerPublicKey ?: ByteArray(0),
                )
                sink.sendFrame(fromPeerId, ackFrame)
            }
            return
        }

        if (!validator.checkHopLimit(key, routed.hopLimit, originId)) return

        val destId = routed.destination.toKey()
        val nextHop = when (val hop = routingEngine.resolveNextHop(destId)) {
            is NextHopResult.Direct -> routed.destination
            is NextHopResult.ViaRoute -> hop.nextHop.bytes
            is NextHopResult.Unreachable -> return
        }
        val neighborId = nextHop.toKey()
        if (!validator.checkRelayRate(originId, neighborId)) return

        deliveryPipeline.recordReversePath(key, fromPeerId)

        val newVisited = routed.visitedList + listOf(localPeerId)
        val relayed = WireCodec.encodeRoutedMessage(
            messageId = routed.messageId,
            origin = routed.origin,
            destination = routed.destination,
            hopLimit = (routed.hopLimit - 1u).toUByte(),
            visitedList = newVisited,
            payload = routed.payload,
            replayCounter = routed.replayCounter,
            priority = routed.priority,
        )

        if (pauseManager.isPaused) {
            pauseManager.queueRelay(nextHop, relayed, routed.priority)
        } else {
            sink.sendFrame(nextHop, relayed)
        }
    }

    private suspend fun handleResumeRequest(data: ByteArray) {
        val request = WireCodec.decodeResumeRequest(data)
        diagnosticSink.emit(
            DiagnosticCode.TRANSPORT_MODE_CHANGED,
            Severity.INFO,
            "resume_request: messageId=${request.messageId.toHex()}, bytesReceived=${request.bytesReceived}",
        )
    }

    private suspend fun handleDeliveryAck(data: ByteArray) {
        val ack = WireCodec.decodeDeliveryAck(data)
        val key = ack.messageId.toKey()

        val signedData = ack.messageId + ack.recipientId
        if (!validator.validateDeliveryAckSignature(ack.signature, ack.signerPublicKey, signedData)) return

        when (val result = deliveryPipeline.processAck(key)) {
            is AckResult.Confirmed -> {
                sink.onDeliveryConfirmed(ack.messageId)
            }
            is AckResult.ConfirmedAndRelay -> {
                sink.onDeliveryConfirmed(ack.messageId)
                sink.sendFrame(result.relayTo, data)
            }
            is AckResult.Late -> {
                diagnosticSink.emit(DiagnosticCode.LATE_DELIVERY_ACK, Severity.INFO, "messageId=$key")
            }
        }
    }

    private fun handleRotationAnnouncement(fromPeerId: ByteArray, data: ByteArray) {
        val se = securityEngine ?: return
        val msg = RotationAnnouncement.decode(data)
        val peerId = fromPeerId.toKey()
        when (val result = se.handleRotationAnnouncement(peerId, msg)) {
            is RotationResult.Accepted -> sink.onKeyChanged(result.event)
            is RotationResult.Rejected -> diagnosticSink.emit(
                DiagnosticCode.MALFORMED_DATA,
                Severity.WARN,
                "rotation announcement signature verification failed from $peerId",
            )
            is RotationResult.Stale -> diagnosticSink.emit(
                DiagnosticCode.REPLAY_REJECTED,
                Severity.WARN,
                "stale rotation announcement from $peerId",
            )
            is RotationResult.UnknownPeer -> {}
        }
    }
}
