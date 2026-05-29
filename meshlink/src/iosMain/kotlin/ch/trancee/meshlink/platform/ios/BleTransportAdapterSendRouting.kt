package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.DirectWireFrame
import ch.trancee.meshlink.transport.GattDataBearerMode
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult

internal suspend fun BleTransportAdapter.sendWhenStarted(
    frame: OutboundFrame
): TransportSendResult {
    return dispatchSend(
        frame = frame,
        dependencies =
            SendDispatchDependencies(
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

internal suspend fun BleTransportAdapter.sendToPeer(
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

internal fun BleTransportAdapter.resolveSendDataBearerMode(
    frame: OutboundFrame,
    peer: DiscoveredPeer,
    directFrame: DirectWireFrame?,
): GattDataBearerMode {
    return resolveIosGattDataBearerMode(
        directFrame = directFrame,
        localPlatformFamily = currentDiscoveryPayload.platformFamily,
        remotePlatformFamily = peer.platformFamily,
        preferredMode = frame.preferredMode,
    )
}

internal suspend fun BleTransportAdapter.sendViaGattNotifyLinkOrNull(
    frame: OutboundFrame,
    peer: DiscoveredPeer,
    directFrame: DirectWireFrame?,
): TransportSendResult? {
    return sendViaPreferredGattNotifyLinkOrNull(
        frame = frame,
        context =
            PreferredGattSendContext(
                hintPeerId = peer.hintPeerId,
                localPlatformFamily = currentDiscoveryPayload.platformFamily,
                remotePlatformFamily = peer.platformFamily,
            ),
        dependencies =
            PreferredGattSendDependencies(
                currentLink = {
                    activeGattNotifyLinkFor(peer)
                        ?.takeIf { directFrame is DirectWireFrame.Data }
                        ?.let { link ->
                            object : PreferredGattSendLink {
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

internal suspend fun BleTransportAdapter.sendViaL2capWhenReady(
    frame: OutboundFrame,
    peer: DiscoveredPeer,
): TransportSendResult {
    return sendViaL2capWhenReady(
        frame = frame,
        context = L2capSendContext(hintPeerId = frame.peerId),
        dependencies =
            L2capSendDependencies(
                currentLink = {
                    activeLinkFor(peer)?.let { link ->
                        object : L2capSendLink {
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

internal fun BleTransportAdapter.dropSend(
    frame: OutboundFrame,
    message: String,
    detail: String,
): TransportSendResult {
    log("send(${frame.peerId.logSuffix()}) dropped: $detail")
    return TransportSendResult.Dropped(message)
}

internal fun BleTransportAdapter.resolvePeer(peerId: PeerId): DiscoveredPeer? {
    return peerRegistry.resolve(peerId)
}
