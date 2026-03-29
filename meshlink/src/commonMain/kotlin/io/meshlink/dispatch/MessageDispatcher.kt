package io.meshlink.dispatch

import io.meshlink.config.MeshLinkConfig
import io.meshlink.wire.RotationAnnouncement
import io.meshlink.crypto.RotationResult
import io.meshlink.crypto.SecurityEngine
import io.meshlink.delivery.AckResult
import io.meshlink.delivery.DeliveryPipeline
import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.diagnostics.Severity
import io.meshlink.routing.LearnedRoute
import io.meshlink.routing.NextHopResult
import io.meshlink.routing.RouteLearnResult
import io.meshlink.routing.RoutingEngine
import io.meshlink.transfer.ChunkAcceptResult
import io.meshlink.transfer.TransferEngine
import io.meshlink.transfer.TransferUpdate
import io.meshlink.util.DeliveryOutcome
import io.meshlink.util.PauseManager
import io.meshlink.util.hexToBytes
import io.meshlink.util.toHex
import io.meshlink.wire.WireCodec

/**
 * Dispatches inbound BLE frames to typed handlers. Owns the dispatch table,
 * decode pipeline, and handler implementations. Pre-dispatch validation
 * (signatures, replay, loops, rate limits) is delegated to [InboundValidator].
 * Side-effectful actions (flow emissions, transport sends) are delegated to
 * [DispatchSink].
 */
class MessageDispatcher(
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
) {
    suspend fun dispatch(fromPeerId: ByteArray, data: ByteArray) {
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
                    diagnosticSink.emit(
                        DiagnosticCode.UNKNOWN_MESSAGE_TYPE, Severity.WARN,
                        "type=0x${data[0].toUByte().toString(16).padStart(2, '0')}, size=${data.size}",
                    )
                }
            }
        } catch (e: Exception) {
            diagnosticSink.emit(
                DiagnosticCode.MALFORMED_DATA, Severity.WARN,
                "type=0x${data[0].toUByte().toString(16)}, size=${data.size}, error=${e.message}",
            )
        }
    }

    private fun handleKeepalive(fromPeerId: ByteArray, data: ByteArray) {
        WireCodec.decodeKeepalive(data)
        routingEngine.peerSeen(fromPeerId.toHex())
    }

    private suspend fun handleHandshake(fromPeerId: ByteArray, data: ByteArray) {
        val se = securityEngine ?: return
        val response = se.handleHandshakeMessage(fromPeerId, data)
        if (response != null) {
            sink.sendFrame(fromPeerId, response)
        }
    }

    private suspend fun handleRouteUpdate(fromPeerId: ByteArray, data: ByteArray) {
        val update = WireCodec.decodeRouteUpdate(data)
        if (validator.cryptoRequired) {
            val signedData = data.copyOfRange(0, data.size - 64)
            if (!validator.validateRouteUpdateSignature(
                    fromPeerId.toHex(), update.signature, update.signerPublicKey, signedData,
                )
            ) return
        }

        val learned = update.entries.map { entry ->
            LearnedRoute(entry.destination.toHex(), entry.cost, entry.sequenceNumber)
        }
        val result = routingEngine.learnRoutes(fromPeerId.toHex(), learned)
        when (result) {
            is RouteLearnResult.SignificantChange -> {
                for (change in result.routeChanges) {
                    diagnosticSink.emit(
                        DiagnosticCode.ROUTE_CHANGED, Severity.INFO,
                        "dest=${change.destination}, oldNextHop=${change.oldNextHop}, newNextHop=${change.newNextHop}",
                    )
                }
                if (config.gossipIntervalMs > 0) {
                    sink.triggerGossipUpdate()
                }
            }
            is RouteLearnResult.NoSignificantChange -> {}
        }
    }

    private suspend fun handleChunk(fromPeerId: ByteArray, data: ByteArray) {
        val chunk = WireCodec.decodeChunk(data)
        val key = chunk.messageId.toHex()

        val result = transferEngine.onChunkReceived(
            key, chunk.sequenceNumber.toInt(), chunk.totalChunks.toInt(), chunk.payload,
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
                )
                sink.sendFrame(fromPeerId, ack)
            }
            is ChunkAcceptResult.MessageComplete -> {
                val ack = WireCodec.encodeChunkAck(
                    messageId = chunk.messageId,
                    ackSequence = result.ackSeq.toUShort(),
                    sackBitmask = result.sackBitmask,
                )
                sink.sendFrame(fromPeerId, ack)

                if (routingEngine.isDuplicate(key)) return

                val decrypted = validator.unsealPayload(result.reassembledPayload, "chunk reassembly") ?: return
                sink.onMessageReceived(fromPeerId, decrypted)
            }
        }
    }

    private suspend fun handleChunkAck(data: ByteArray) {
        val ack = WireCodec.decodeChunkAck(data)
        val key = ack.messageId.toHex()
        val recipient = outboundTracker.recipient(key)

        val update = transferEngine.onAck(key, ack.ackSequence.toInt(), ack.sackBitmask)

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
        val key = broadcast.messageId.toHex()

        if (!validator.checkAppId(key, broadcast.appIdHash)) return

        val signedData = broadcast.messageId + broadcast.origin + broadcast.appIdHash + broadcast.payload
        if (!validator.validateBroadcastSignature(broadcast.signature, broadcast.signerPublicKey, signedData)) return

        if (routingEngine.isDuplicate(key)) return

        sink.onMessageReceived(broadcast.origin, broadcast.payload)

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
            for (peerHex in routingEngine.allPeerIds()) {
                if (peerHex != senderHex) {
                    sink.sendFrame(hexToBytes(peerHex), reflooded)
                }
            }
        }
    }

    private suspend fun handleRoutedMessage(fromPeerId: ByteArray, data: ByteArray) {
        val routed = WireCodec.decodeRoutedMessage(data)
        val key = routed.messageId.toHex()
        val originHex = routed.origin.toHex()

        if (routingEngine.isDuplicate(key)) return
        if (!validator.checkReplay(key, originHex, routed.replayCounter)) return
        if (!validator.checkLoop(key, routed.visitedList, originHex)) return

        if (routed.destination.contentEquals(localPeerId)) {
            if (!validator.checkInboundRate(originHex)) return
            val deliveredPayload = validator.unsealPayload(routed.payload, "routed message") ?: return
            sink.onMessageReceived(routed.origin, deliveredPayload)
            val signed = securityEngine?.sign(routed.messageId + localPeerId)
            val ackFrame = WireCodec.encodeDeliveryAck(
                routed.messageId, localPeerId,
                signature = signed?.signature ?: ByteArray(0),
                signerPublicKey = signed?.signerPublicKey ?: ByteArray(0),
            )
            sink.sendFrame(fromPeerId, ackFrame)
            return
        }

        if (!validator.checkHopLimit(key, routed.hopLimit, originHex)) return

        val destHex = routed.destination.toHex()
        val nextHop = when (val hop = routingEngine.resolveNextHop(destHex)) {
            is NextHopResult.Direct -> routed.destination
            is NextHopResult.ViaRoute -> hexToBytes(hop.nextHop)
            is NextHopResult.Unreachable -> return
        }
        val neighborHex = nextHop.toHex()
        if (!validator.checkRelayRate(originHex, neighborHex)) return

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
        )

        if (pauseManager.isPaused) {
            pauseManager.queueRelay(nextHop, relayed)
        } else {
            sink.sendFrame(nextHop, relayed)
        }
    }

    @Suppress("UnusedParameter")
    private suspend fun handleResumeRequest(fromPeerId: ByteArray, data: ByteArray) {
        val request = WireCodec.decodeResumeRequest(data)
        diagnosticSink.emit(
            DiagnosticCode.TRANSPORT_MODE_CHANGED, Severity.INFO,
            "resume_request: messageId=${request.messageId.toHex()}, bytesReceived=${request.bytesReceived}",
        )
    }

    @Suppress("UnusedParameter")
    private suspend fun handleDeliveryAck(fromPeerId: ByteArray, data: ByteArray) {
        val ack = WireCodec.decodeDeliveryAck(data)
        val key = ack.messageId.toHex()

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
        val peerHex = fromPeerId.toHex()
        when (val result = se.handleRotationAnnouncement(peerHex, msg)) {
            is RotationResult.Accepted -> sink.onKeyChanged(result.event)
            is RotationResult.Rejected -> diagnosticSink.emit(
                DiagnosticCode.MALFORMED_DATA, Severity.WARN,
                "rotation announcement signature verification failed from $peerHex",
            )
            is RotationResult.Stale -> diagnosticSink.emit(
                DiagnosticCode.REPLAY_REJECTED, Severity.WARN,
                "stale rotation announcement from $peerHex",
            )
            is RotationResult.UnknownPeer -> {}
        }
    }
}
