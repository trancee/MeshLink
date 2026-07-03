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

internal data class MeshEngineInboundRoutingContext(
    val routeCoordinator: RouteCoordinator,
    val routingSupport: MeshEngineRoutingSupport,
)

internal data class MeshEngineInboundTransport(
    val emitHopSessionFailed:
        suspend (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
    val decryptHopPayload: (HopSession, ByteArray) -> ByteArray,
)

internal data class MeshEngineInboundMessageCallbacks(
    val captureHardRunToken: () -> MeshEngineHardRunToken,
    val forwardMessageToNextHop: suspend (WireFrame.Message, MeshEngineHardRunToken) -> Unit,
    val deliverInnerEnvelope:
        suspend (PeerId, PeerId, ByteArray, DeliveryPriority, MeshEngineHardRunToken) -> Unit,
)

/**
 * Callbacks for the relayed end-to-end (multi-hop) Noise XX handshake frame family. Frames not
 * addressed to the local peer are relayed hop-by-hop toward [WireFrame.EndToEndHandshakeFrame]'s
 * `destinationPeerId`; frames addressed to the local peer are handed to the end-to-end handshake
 * processor to advance (or complete) the destination-keyed session, per
 * [handleLocalEndToEndHandshakeFrame].
 */
internal data class MeshEngineInboundEndToEndHandshakeCallbacks(
    val forwardEndToEndHandshakeFrame:
        suspend (WireFrame.EndToEndHandshakeFrame, MeshEngineHardRunToken) -> Unit,
    val handleLocalEndToEndHandshakeFrame:
        suspend (PeerId, WireFrame.EndToEndHandshakeFrame) -> Unit,
)

internal data class MeshEngineInboundTransferCallbacks(
    val handleTransferStart: suspend (PeerId, WireFrame.TransferStart) -> Unit,
    val handleTransferChunk: suspend (PeerId, WireFrame.TransferChunk) -> Unit,
    val handleTransferAck: suspend (PeerId, WireFrame.TransferAck) -> Unit,
    val handleTransferComplete: suspend (PeerId, WireFrame.TransferComplete) -> Unit,
    val handleTransferAbort: suspend (PeerId, WireFrame.TransferAbort) -> Unit,
)

internal class MeshEngineInboundSupport(
    private val localIdentity: LocalIdentity,
    private val sessionRegistry: MeshEngineSessionRegistry,
    private val routingContext: MeshEngineInboundRoutingContext,
    private val transport: MeshEngineInboundTransport,
    private val messageCallbacks: MeshEngineInboundMessageCallbacks,
    private val endToEndHandshakeCallbacks: MeshEngineInboundEndToEndHandshakeCallbacks,
    private val transferCallbacks: MeshEngineInboundTransferCallbacks,
) {
    suspend fun handleEncryptedDataFrame(peerId: PeerId, payload: ByteArray): Unit {
        val canonicalPeerId = sessionRegistry.resolvePeerId(peerId)
        val session = activeHopSession(canonicalPeerId)
        if (session != null) {
            val decryptedEnvelopeBytes = decryptInboundWireFrame(canonicalPeerId, session, payload)
            if (decryptedEnvelopeBytes != null) {
                val frame = decodeInboundWireFrame(canonicalPeerId, decryptedEnvelopeBytes)
                if (frame != null) {
                    dispatchInboundWireFrame(canonicalPeerId, frame)
                }
            }
        }
    }

    private suspend fun activeHopSession(peerId: PeerId): HopSession? {
        val session = sessionRegistry.hopSession(peerId)
        if (session == null) {
            transport.emitHopSessionFailed(
                peerId,
                "transport.data.noSession",
                DiagnosticReason.DELIVERY_FAILURE,
                emptyMap(),
            )
        }
        return session
    }

    private suspend fun decryptInboundWireFrame(
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

    private suspend fun decodeInboundWireFrame(peerId: PeerId, payload: ByteArray): WireFrame? {
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
                routingContext.routingSupport.dispatchMutation(
                    mutation = routingContext.routeCoordinator.onRouteUpdate(peerId, frame),
                    stage = "routing.routeUpdate",
                    metadata = mapOf("advertisedByPeerId" to peerId.value),
                )
            is WireFrame.RouteRetraction ->
                routingContext.routingSupport.dispatchMutation(
                    mutation = routingContext.routeCoordinator.onRouteRetraction(peerId, frame),
                    stage = "routing.routeRetraction",
                    removalCode = DiagnosticCode.ROUTE_RETRACTED,
                    metadata = mapOf("retractedByPeerId" to peerId.value),
                )
            is WireFrame.RouteDigest -> routingContext.routeCoordinator.onRouteDigest(peerId, frame)
            is WireFrame.Hello -> Unit
            is WireFrame.LinkIdentity -> Unit
            // Platform transports must consume cleartext LinkIdentity frames before they ever reach
            // the encrypted direct-frame pipeline. If one shows up here, the transport leaked a
            // local BLE-correlation hint into the hop-session dispatch path.
            is WireFrame.Ihu -> Unit
            is WireFrame.SeqNoRequest -> Unit
            is WireFrame.TransferStart -> transferCallbacks.handleTransferStart(peerId, frame)
            is WireFrame.TransferChunk -> transferCallbacks.handleTransferChunk(peerId, frame)
            is WireFrame.TransferAck -> transferCallbacks.handleTransferAck(peerId, frame)
            is WireFrame.TransferComplete -> transferCallbacks.handleTransferComplete(peerId, frame)
            is WireFrame.TransferAbort -> transferCallbacks.handleTransferAbort(peerId, frame)
            is WireFrame.EndToEndHandshakeFrame ->
                handleEndToEndHandshakeFrame(peerId = peerId, frame = frame)
        }
    }

    private suspend fun handleEndToEndHandshakeFrame(
        peerId: PeerId,
        frame: WireFrame.EndToEndHandshakeFrame,
    ): Unit {
        val hardRunToken = messageCallbacks.captureHardRunToken()
        if (!isLocalPeerId(frame.destinationPeerId)) {
            endToEndHandshakeCallbacks.forwardEndToEndHandshakeFrame(frame, hardRunToken)
            return
        }
        endToEndHandshakeCallbacks.handleLocalEndToEndHandshakeFrame(peerId, frame)
    }

    private suspend fun handleRoutedMessageFrame(peerId: PeerId, frame: WireFrame.Message): Unit {
        val hardRunToken = messageCallbacks.captureHardRunToken()
        if (!isLocalPeerId(frame.destinationPeerId)) {
            messageCallbacks.forwardMessageToNextHop(frame, hardRunToken)
            return
        }

        messageCallbacks.deliverInnerEnvelope(
            peerId,
            frame.originPeerId,
            frame.encryptedPayload,
            frame.priority,
            hardRunToken,
        )
    }

    private fun isLocalPeerId(peerId: PeerId): Boolean {
        return peerId.value == localIdentity.peerId.value ||
            peerId.value.hexContentEquals(localIdentity.advertisementKeyHash)
    }
}

internal fun buildMeshEngineRuntimeInboundSupport(
    localIdentity: LocalIdentity,
    sessionRegistry: MeshEngineSessionRegistry,
    routeCoordinator: RouteCoordinator,
    routingSupport: MeshEngineRoutingSupport,
    emitHopSessionFailed: suspend (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
    decryptHopPayload: (HopSession, ByteArray) -> ByteArray,
    captureHardRunToken: () -> MeshEngineHardRunToken,
    forwardMessageToNextHop: suspend (WireFrame.Message, MeshEngineHardRunToken) -> Unit,
    deliverInnerEnvelope:
        suspend (PeerId, PeerId, ByteArray, DeliveryPriority, MeshEngineHardRunToken) -> Unit,
    forwardEndToEndHandshakeFrame:
        suspend (WireFrame.EndToEndHandshakeFrame, MeshEngineHardRunToken) -> Unit,
    handleLocalEndToEndHandshakeFrame: suspend (PeerId, WireFrame.EndToEndHandshakeFrame) -> Unit,
    transferSupport: MeshEngineTransferSupport,
): MeshEngineInboundSupport {
    return MeshEngineInboundSupport(
        localIdentity = localIdentity,
        sessionRegistry = sessionRegistry,
        routingContext =
            MeshEngineInboundRoutingContext(
                routeCoordinator = routeCoordinator,
                routingSupport = routingSupport,
            ),
        transport =
            MeshEngineInboundTransport(
                emitHopSessionFailed = emitHopSessionFailed,
                decryptHopPayload = decryptHopPayload,
            ),
        messageCallbacks =
            MeshEngineInboundMessageCallbacks(
                captureHardRunToken = captureHardRunToken,
                forwardMessageToNextHop = forwardMessageToNextHop,
                deliverInnerEnvelope = deliverInnerEnvelope,
            ),
        endToEndHandshakeCallbacks =
            MeshEngineInboundEndToEndHandshakeCallbacks(
                forwardEndToEndHandshakeFrame = forwardEndToEndHandshakeFrame,
                handleLocalEndToEndHandshakeFrame = handleLocalEndToEndHandshakeFrame,
            ),
        transferCallbacks =
            MeshEngineInboundTransferCallbacks(
                handleTransferStart = transferSupport::handleTransferStart,
                handleTransferChunk = transferSupport::handleTransferChunk,
                handleTransferAck = transferSupport::handleTransferAck,
                handleTransferComplete = transferSupport::handleTransferComplete,
                handleTransferAbort = transferSupport::handleTransferAbort,
            ),
    )
}
