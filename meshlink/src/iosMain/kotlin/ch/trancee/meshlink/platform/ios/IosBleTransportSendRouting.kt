package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.DirectWireFrame
import ch.trancee.meshlink.transport.GattDataBearerMode
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.transport.resolveGattDataBearerMode

internal suspend fun IosBleTransport.sendWhenStarted(frame: OutboundFrame): TransportSendResult {
    return dispatchIosSend(
        frame = frame,
        dependencies =
            IosSendDispatchDependencies(
                sendToResolvedPeerOrNull = {
                    resolvePeer(frame.peerId)?.let { peer -> sendToPeer(frame, peer) }
                },
                dropWhenPeerIsMissing = {
                    dropSend(
                        frame,
                        message = "iOS BLE peer has not been discovered",
                        detail = "peer not discovered",
                    )
                },
            ),
    )
}

internal suspend fun IosBleTransport.sendToPeer(
    frame: OutboundFrame,
    peer: DiscoveredPeer,
): TransportSendResult {
    if (peer.transportMode != TransportMode.L2CAP || peer.l2capPsm == NO_L2CAP_PSM) {
        return dropSend(
            frame,
            message = "iOS BLE GATT fallback transport is not implemented",
            detail = "peer is GATT-only",
        )
    }

    val directFrame = runCatching { DirectWireFrame.decode(frame.payload) }.getOrNull()
    val gattSendResult =
        sendViaGattNotifyLinkOrNull(frame = frame, peer = peer, directFrame = directFrame)
    return if (gattSendResult != null) {
        gattSendResult
    } else {
        sendViaL2capWhenReady(frame = frame, peer = peer)
    }
}

internal fun IosBleTransport.resolveSendDataBearerMode(
    frame: OutboundFrame,
    peer: DiscoveredPeer,
    directFrame: DirectWireFrame?,
): GattDataBearerMode {
    return if (directFrame is DirectWireFrame.Data) {
        resolveGattDataBearerMode(
            localPlatformFamily = currentDiscoveryPayload.platformFamily,
            remotePlatformFamily = peer.platformFamily,
            preferredMode = frame.preferredMode,
        )
    } else {
        GattDataBearerMode.L2CAP_ONLY
    }
}

internal suspend fun IosBleTransport.sendViaGattNotifyLinkOrNull(
    frame: OutboundFrame,
    peer: DiscoveredPeer,
    directFrame: DirectWireFrame?,
): TransportSendResult? {
    return sendViaPreferredGattNotifyLinkOrNull(
        frame = frame,
        context =
            IosPreferredGattSendContext(
                hintPeerId = peer.hintPeerId,
                localPlatformFamily = currentDiscoveryPayload.platformFamily,
                remotePlatformFamily = peer.platformFamily,
            ),
        dependencies =
            IosPreferredGattSendDependencies(
                currentLink = {
                    activeGattNotifyLinkFor(peer)
                        ?.takeIf { directFrame is DirectWireFrame.Data }
                        ?.let { link ->
                            object : IosPreferredGattSendLink {
                                override suspend fun enqueue(payload: ByteArray): Boolean {
                                    return link.enqueue(payload)
                                }
                            }
                        }
                },
                log = ::log,
            ),
    )
}

internal suspend fun IosBleTransport.sendViaL2capWhenReady(
    frame: OutboundFrame,
    peer: DiscoveredPeer,
): TransportSendResult {
    return sendViaIosL2capWhenReady(
        frame = frame,
        context = IosL2capSendContext(hintPeerId = frame.peerId),
        dependencies =
            IosL2capSendDependencies(
                currentLink = {
                    activeLinkFor(peer)?.let { link ->
                        object : IosL2capSendLink {
                            override val hintPeerId: PeerId = link.hintPeerId

                            override suspend fun enqueue(payload: ByteArray): Boolean {
                                return link.enqueue(payload)
                            }
                        }
                    }
                },
                ensureConnectAttempt = { connectIfNeeded(peer) },
                shouldInitiateL2cap = { shouldInitiateL2cap(peer.keyHash, peer.platformFamily) },
                closeLink = ::closeLink,
                log = ::log,
            ),
    )
}

internal fun IosBleTransport.dropSend(
    frame: OutboundFrame,
    message: String,
    detail: String,
): TransportSendResult {
    log("send(${frame.peerId.logSuffix()}) dropped: $detail")
    return TransportSendResult.Dropped(message)
}

internal fun IosBleTransport.resolvePeer(peerId: PeerId): DiscoveredPeer? {
    return peerRegistry.resolve(peerId)
}
