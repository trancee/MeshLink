package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.transport.DirectWireFrame
import ch.trancee.meshlink.engine.transport.gattDataBearerDecisionLogLine
import ch.trancee.meshlink.engine.transport.gattDataBearerResultLogLine
import ch.trancee.meshlink.engine.transport.resolveGattDataBearerMode
import ch.trancee.meshlink.power.hasConnectionBudget
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
                sendToTemporaryLinkOrNull = {
                    activeLinksByHint[frame.peerId.value]?.let { temporaryLink ->
                        sendViaConnectedRawLink(frame = frame, link = temporaryLink)
                    }
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

/**
 * Sends [frame] directly over an already-active [L2capLink] found by raw hint id, without going
 * through peer resolution or bearer-mode selection -- the recovery path used when no
 * [DiscoveredPeer] record exists yet under [frame]'s peer id (see
 * [SendDispatchDependencies.sendToTemporaryLinkOrNull]). Mirrors Android's
 * `platform.android.sendViaConnectedLink`.
 */
internal suspend fun BleTransportAdapter.sendViaConnectedRawLink(
    frame: OutboundFrame,
    link: L2capLink,
): TransportSendResult {
    return runCatching {
            if (!link.enqueue(frame.payload)) {
                closeLink(hintPeer = link.hintPeerId.value, reason = "send queue closed")
                return@runCatching TransportSendResult.Dropped(
                    "iOS BLE send queue is not accepting frames"
                )
            }
            TransportSendResult.Delivered
        }
        .getOrElse { error ->
            closeLink(
                hintPeer = link.hintPeerId.value,
                reason = "send failed: ${error.message.orEmpty()}",
            )
            TransportSendResult.Dropped("iOS BLE send failed: ${error.message.orEmpty()}")
        }
}

/**
 * Resolves the outbound frame's bearer mode (see
 * [ch.trancee.meshlink.engine.resolveGattDataBearerMode]) and routes it to exactly one bearer:
 * - [GattDataBearerMode.GATT_ONLY] (handshake/control frames): the GATT notify link is attempted
 *   first, falling back to the L2CAP connect-and-wait path only if GATT is unavailable.
 * - [GattDataBearerMode.L2CAP_PREFERRED_WITH_GATT_FALLBACK] (data frames): an already-connected
 *   L2CAP link is used immediately (non-blocking check - `sendViaL2capWhenReady` checks for an
 *   existing link before ever waiting). Otherwise GATT is used, falling back further to the
 *   blocking L2CAP connect-and-wait path only if GATT is also unavailable.
 */
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
    val bearerMode = resolveGattDataBearerMode(directFrame = directFrame)
    val readyLink = activeLinkFor(peer)
    log(
        gattDataBearerDecisionLogLine(
            directFrame = directFrame,
            bearerMode = bearerMode,
            l2capLinkAlreadyConnected = readyLink != null,
        )
    )

    return when (bearerMode) {
        GattDataBearerMode.GATT_ONLY ->
            sendViaGattNotifyLinkOrNull(frame = frame, peer = peer)?.also {
                log(gattDataBearerResultLogLine("GATT"))
            }
                ?: sendViaL2capWhenReady(frame = frame, peer = peer).also {
                    log(gattDataBearerResultLogLine("L2CAP"))
                }
        GattDataBearerMode.L2CAP_PREFERRED_WITH_GATT_FALLBACK -> {
            if (readyLink != null) {
                sendViaL2capWhenReady(frame = frame, peer = peer).also {
                    log(gattDataBearerResultLogLine("L2CAP"))
                }
            } else {
                sendViaGattNotifyLinkOrNull(frame = frame, peer = peer)?.also {
                    log(gattDataBearerResultLogLine("GATT"))
                }
                    ?: sendViaL2capWhenReady(frame = frame, peer = peer).also {
                        log(gattDataBearerResultLogLine("L2CAP"))
                    }
            }
        }
    }
}

internal suspend fun BleTransportAdapter.sendViaGattNotifyLinkOrNull(
    frame: OutboundFrame,
    peer: DiscoveredPeer,
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
                    activeGattNotifyLinkFor(peer)?.let { link ->
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
                hasConnectionBudget = {
                    val activeHintIds = activeLinksByHint.keys + gattNotifyRegistry.activeHintIds()
                    hasConnectionBudget(
                        peerAlreadyConnected = peer.hintPeerId.value in activeHintIds,
                        activeConnectionCount = activeHintIds.size,
                        maxConnections = currentPowerProfile.maxConnections,
                    )
                },
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
