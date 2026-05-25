@file:Suppress("TooManyFunctions")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.DirectWireFrame
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.GattDataBearerMode
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.transport.resolveGattDataBearerMode
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.cancelChildren
import platform.Foundation.NSData
import platform.Foundation.NSStreamStatusAtEnd
import platform.Foundation.NSStreamStatusClosed
import platform.Foundation.NSStreamStatusError
import platform.posix.getenv
import platform.posix.memcpy

private const val PEER_LOG_SUFFIX_CHARS: Int = 6
internal const val NO_L2CAP_PSM: Int = 0
private const val ADVERTISED_PSM_MIN: Int = 128
private const val ADVERTISED_PSM_MAX: Int = 255
internal const val NO_ADVERTISED_L2CAP_PSM: UByte = 0u
private val ADVERTISED_PSM_RANGE: IntRange = ADVERTISED_PSM_MIN..ADVERTISED_PSM_MAX
internal const val NO_GATT_CHARACTERISTIC_PERMISSIONS: ULong = 0u
private const val NO_DATA_BYTES: Int = 0
private const val MILLIS_PER_SECOND: Double = 1000.0
private const val ENV_VALUE_NUMERIC_TRUE: String = "1"
private const val ENV_VALUE_BOOLEAN_TRUE: String = "true"
private const val ENV_VALUE_YES: String = "yes"

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

internal fun IosBleTransport.stopTransport(clearPeers: Boolean): Unit {
    reportLog(
        "stopTransport clearPeers=$clearPeers activeLinks=${activeLinksByHint.size} activeGatt=${activeGattNotifyLinksByHint.size} pending=${pendingConnectionsByHint.size}"
    )
    discoverySuspended = false
    centralManager?.stopScan()
    peripheralManager?.stopAdvertising()
    val psm = currentDiscoveryPayload.l2capPsm.toInt()
    if (psm in ADVERTISED_PSM_RANGE) {
        peripheralManager?.unpublishL2CAPChannel(psm.convert())
    }
    peripheralManager?.removeAllServices()
    gattNotifyServiceInstalled = false
    gattNotifyServiceCharacteristic = null
    pendingConnectionsByHint.clear()
    activeGattNotifyLinksByHint.values.forEach { link -> link.close() }
    activeGattNotifyLinksByHint.clear()
    activeLinksByHint.keys.toList().forEach { hint ->
        closeLink(hintPeer = hint, reason = "transport stopped")
    }
    coroutineScope.coroutineContext.cancelChildren()
    if (clearPeers) {
        peerRegistry.clear()
        peerBindings.clear()
    }
}

internal fun IosBleTransport.refreshDiscoveryState(): Unit {
    reportLog(
        "refreshDiscoveryState started=$started suspended=$discoverySuspended centralState=${centralManager?.state} peripheralState=${peripheralManager?.state}"
    )
    centralManager?.stopScan()
    peripheralManager?.stopAdvertising()
    centralManager?.let(::startScanIfReady)
    startAdvertisingIfReady()
}

internal fun IosBleTransport.discoveryPayload(l2capPsm: UByte): BleDiscoveryPayload {
    return BleDiscoveryPayload(
        protocolVersion = BleDiscoveryContract.CURRENT_PROTOCOL_VERSION,
        powerMode = currentPowerProfile.discoveryPowerMode,
        meshHash = BleDiscoveryContract.computeMeshHash(appId),
        l2capPsm = l2capPsm,
        keyHash = localKeyHash,
        platformFamily = BleDiscoveryPlatformFamily.IOS,
    )
}

internal fun IosBleTransport.advertisedPsm(psm: UShort): UByte {
    return if (psm.toInt() in ADVERTISED_PSM_RANGE) psm.toUByte() else NO_ADVERTISED_L2CAP_PSM
}

internal fun IosBleTransport.log(message: String): Unit {
    if (transportDebugLoggingEnabled) {
        emitTransportLog(message)
    }
}

internal fun IosBleTransport.reportLog(message: String): Unit {
    emitTransportLog(message)
}

internal fun IosBleTransport.emitTransportLog(message: String): Unit {
    println("MeshLinkTransport $message")
}

internal const val TRANSPORT_TELEMETRY_ENV: String = "MESHLINK_TRANSPORT_TELEMETRY"
internal const val TRANSPORT_DEBUG_ENV: String = "MESHLINK_TRANSPORT_DEBUG"

internal fun readEnvironmentFlag(name: String): Boolean {
    return getenv(name)?.toKString()?.lowercase()?.let { value ->
        value == ENV_VALUE_NUMERIC_TRUE || value == ENV_VALUE_BOOLEAN_TRUE || value == ENV_VALUE_YES
    } ?: false
}

internal fun PeerId.logSuffix(): String {
    return value.takeLast(PEER_LOG_SUFFIX_CHARS)
}

internal fun String.logSuffix(): String {
    return takeLast(PEER_LOG_SUFFIX_CHARS)
}

internal fun isStreamClosed(streamStatus: ULong, hasError: Boolean): Boolean {
    return hasError ||
        streamStatus == NSStreamStatusAtEnd ||
        streamStatus == NSStreamStatusClosed ||
        streamStatus == NSStreamStatusError
}

internal fun isWriteStalled(lastProgressAtMs: Long, nowMs: Long, stallTimeoutMs: Long): Boolean {
    return nowMs - lastProgressAtMs >= stallTimeoutMs
}

internal fun isStreamClosed(inputStream: platform.Foundation.NSInputStream): Boolean {
    return isStreamClosed(
        streamStatus = inputStream.streamStatus,
        hasError = inputStream.streamError != null,
    )
}

internal fun NSData.toByteArray(): ByteArray {
    val lengthInt = length.toInt()
    if (lengthInt == NO_DATA_BYTES) {
        return ByteArray(0)
    }
    return ByteArray(lengthInt).also { output ->
        output.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}
