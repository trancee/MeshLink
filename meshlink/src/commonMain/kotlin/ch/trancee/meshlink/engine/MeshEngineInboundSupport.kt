package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.identity.hexContentEquals
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame

internal interface MeshEngineInboundTransport {
    fun emitHopSessionFailed(
        peerId: PeerId,
        stage: String,
        reason: DiagnosticReason,
        metadata: Map<String, String> = emptyMap(),
    )

    fun decryptHopPayload(session: HopSession, payload: ByteArray): ByteArray
}

internal interface MeshEngineInboundCallbacks {
    fun forwardMessageToNextHop(frame: WireFrame.Message)

    suspend fun deliverInnerEnvelope(
        immediatePeerId: PeerId,
        originPeerId: PeerId,
        encryptedPayload: ByteArray,
        priority: DeliveryPriority,
    )

    suspend fun handleTransferStart(peerId: PeerId, frame: WireFrame.TransferStart)

    suspend fun handleTransferChunk(peerId: PeerId, frame: WireFrame.TransferChunk)

    fun handleTransferAck(peerId: PeerId, frame: WireFrame.TransferAck)

    suspend fun handleTransferComplete(peerId: PeerId, frame: WireFrame.TransferComplete)

    suspend fun handleTransferAbort(peerId: PeerId, frame: WireFrame.TransferAbort)
}

internal class MeshEngineInboundSupport(
    private val localIdentity: LocalIdentity,
    private val hopSessions: MutableMap<String, HopSession>,
    private val routeCoordinator: RouteCoordinator,
    private val routingSupport: MeshEngineRoutingSupport,
    private val transport: MeshEngineInboundTransport,
    private val callbacks: MeshEngineInboundCallbacks,
) {
    suspend fun handleEncryptedDataFrame(peerId: PeerId, payload: ByteArray): Unit {
        val session = activeHopSession(peerId)
        if (session != null) {
            val decryptedEnvelopeBytes = decryptInboundWireFrame(peerId, session, payload)
            if (decryptedEnvelopeBytes != null) {
                val frame = decodeInboundWireFrame(peerId, decryptedEnvelopeBytes)
                if (frame != null) {
                    dispatchInboundWireFrame(peerId, frame)
                }
            }
        }
    }

    private fun activeHopSession(peerId: PeerId): HopSession? {
        val session = hopSessions[peerId.value]
        if (session == null) {
            transport.emitHopSessionFailed(
                peerId,
                "transport.data.noSession",
                DiagnosticReason.DELIVERY_FAILURE,
            )
        }
        return session
    }

    private fun decryptInboundWireFrame(
        peerId: PeerId,
        session: HopSession,
        payload: ByteArray,
    ): ByteArray? {
        return runCatching { transport.decryptHopPayload(session, payload) }
            .getOrElse { exception ->
                transport.emitHopSessionFailed(
                    peerId,
                    "transport.data.decrypt",
                    DiagnosticReason.DELIVERY_FAILURE,
                    mapOf("cause" to exception::class.simpleName.orEmpty()),
                )
                null
            }
    }

    private fun decodeInboundWireFrame(peerId: PeerId, payload: ByteArray): WireFrame? {
        return runCatching { WireCodec.decode(payload) }
            .getOrElse { exception ->
                transport.emitHopSessionFailed(
                    peerId,
                    "transport.data.decode",
                    DiagnosticReason.DELIVERY_FAILURE,
                    mapOf("cause" to exception::class.simpleName.orEmpty()),
                )
                null
            }
    }

    private suspend fun dispatchInboundWireFrame(peerId: PeerId, frame: WireFrame): Unit {
        when (frame) {
            is WireFrame.Message -> handleRoutedMessageFrame(peerId = peerId, frame = frame)
            is WireFrame.RouteUpdate ->
                routingSupport.dispatchMutation(
                    mutation = routeCoordinator.onRouteUpdate(peerId, frame),
                    stage = "routing.routeUpdate",
                    metadata = mapOf("advertisedByPeerId" to peerId.value),
                )
            is WireFrame.RouteRetraction ->
                routingSupport.dispatchMutation(
                    mutation = routeCoordinator.onRouteRetraction(peerId, frame),
                    stage = "routing.routeRetraction",
                    removalCode = DiagnosticCode.ROUTE_RETRACTED,
                    metadata = mapOf("retractedByPeerId" to peerId.value),
                )
            is WireFrame.RouteDigest -> routeCoordinator.onRouteDigest(peerId, frame)
            is WireFrame.Hello -> Unit
            is WireFrame.Ihu -> Unit
            is WireFrame.SeqNoRequest -> Unit
            is WireFrame.TransferStart -> callbacks.handleTransferStart(peerId, frame)
            is WireFrame.TransferChunk -> callbacks.handleTransferChunk(peerId, frame)
            is WireFrame.TransferAck -> callbacks.handleTransferAck(peerId, frame)
            is WireFrame.TransferComplete -> callbacks.handleTransferComplete(peerId, frame)
            is WireFrame.TransferAbort -> callbacks.handleTransferAbort(peerId, frame)
        }
    }

    private suspend fun handleRoutedMessageFrame(peerId: PeerId, frame: WireFrame.Message): Unit {
        if (!isLocalPeerId(frame.destinationPeerId)) {
            callbacks.forwardMessageToNextHop(frame)
            return
        }

        callbacks.deliverInnerEnvelope(
            peerId,
            frame.originPeerId,
            frame.encryptedPayload,
            frame.priority,
        )
    }

    private fun isLocalPeerId(peerId: PeerId): Boolean {
        return peerId.value == localIdentity.peerId.value ||
            peerId.value.hexContentEquals(localIdentity.advertisementKeyHash)
    }
}
