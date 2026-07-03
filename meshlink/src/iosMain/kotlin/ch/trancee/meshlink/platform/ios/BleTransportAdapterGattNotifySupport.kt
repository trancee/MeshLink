@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.apple.BleTransportBridgeRegistry
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.TransportEvent
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTErrorUnlikelyError
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCharacteristic

internal fun BleTransportAdapter.handleGattNotifySubscribed(
    central: CBCentral,
    characteristic: CBCharacteristic,
): Unit {
    if (
        characteristic.UUID.UUIDString.lowercase() !=
            BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID
    ) {
        return
    }
    ensureGattNotifyLink(central = central, replaceExisting = true)
}

internal fun BleTransportAdapter.handleGattNotifyUnsubscribed(
    central: CBCentral,
    characteristic: CBCharacteristic,
): Unit {
    val isNotifyCharacteristic =
        characteristic.UUID.UUIDString.lowercase() ==
            BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID
    val identifier = central.identifier.UUIDString.lowercase()
    pendingGattWriteBuffersByIdentifier.remove(identifier)
    val hintPeerIdValue =
        if (isNotifyCharacteristic) peerBindings.hintForIdentifier(identifier) else null
    if (hintPeerIdValue != null) {
        removeGattNotifyLink(identifier = identifier, hintPeerIdValue = hintPeerIdValue)
    }
}

internal fun BleTransportAdapter.handleGattWriteRequests(requests: List<*>): Unit {
    val typedRequests = requests.filterIsInstance<CBATTRequest>()
    typedRequests.firstOrNull()?.let { firstRequest ->
        processGattWriteRequests(typedRequests = typedRequests, firstRequest = firstRequest)
    }
}

internal fun BleTransportAdapter.pumpGattNotifyLinks(): Unit {
    gattNotifyRegistry.pumpAll()
}

internal fun BleTransportAdapter.ensureGattNotifyLink(
    central: CBCentral,
    replaceExisting: Boolean,
): GattNotifyLink? {
    val identifier = central.identifier.UUIDString.lowercase()
    val hintPeerIdValue =
        if (BleTransportBridgeRegistry.isGattNotifyBearerEnabled()) {
            resolveGattNotifyHintPeerIdValue(identifier)
        } else {
            null
        }
    return if (hintPeerIdValue != null) {
        peerBindings.bindHintToIdentifier(identifier, hintPeerIdValue)
        promoteTemporaryL2capLinkIfPossible(
            identifier = identifier,
            resolvedHintPeerIdValue = hintPeerIdValue,
        )
        reuseOrCreateGattNotifyLink(
            central = central,
            identifier = identifier,
            hintPeerIdValue = hintPeerIdValue,
            replaceExisting = replaceExisting,
        )
    } else {
        null
    }
}

internal fun BleTransportAdapter.removeGattNotifyLink(
    identifier: String,
    hintPeerIdValue: String,
): Unit {
    gattNotifyRegistry.removeLink(hintPeerIdValue)?.close()
    reportLog("removed GATT notify side link for ${hintPeerIdValue.logSuffix()} id=$identifier")
    if (!activeLinksByHint.containsKey(hintPeerIdValue)) {
        peerRegistry.setPresenceAnnounced(hintPeerIdValue, announced = false)
        mutableEvents.tryEmit(TransportEvent.PeerLost(PeerId(hintPeerIdValue)))
    }
}

internal fun BleTransportAdapter.processGattWriteRequests(
    typedRequests: List<CBATTRequest>,
    firstRequest: CBATTRequest,
): Unit {
    val central = firstRequest.central
    val identifier = central.identifier.UUIDString.lowercase()
    val boundHintPeerIdValue =
        peerBindings.hintForIdentifier(identifier)
            ?: peerBindings.temporaryHintForIdentifier(identifier)
    val knownLink =
        if (boundHintPeerIdValue != null) {
            ensureGattNotifyLink(central = central, replaceExisting = false)
        } else {
            null
        }
    val decodedFrames = mutableListOf<ByteArray>()
    var resolvedPeerId: PeerId? = knownLink?.hintPeerId
    val allRequestsAccepted =
        if (knownLink != null) {
            typedRequests.all { request ->
                acceptsGattWriteRequest(request, knownLink, decodedFrames)
            }
        } else {
            val requestChunks = typedRequests.mapNotNull { request ->
                if (
                    request.characteristic.UUID.UUIDString.lowercase() ==
                        BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID &&
                        request.offset.toInt() == 0
                ) {
                    request.value?.toByteArray()
                } else {
                    null
                }
            }
            if (requestChunks.size != typedRequests.size) {
                false
            } else {
                val pendingBuffer =
                    pendingGattWriteBuffersByIdentifier.getOrPut(identifier) { L2capFrameBuffer() }
                val result =
                    processUnknownGattWriteChunks(
                        identifier = identifier,
                        chunks = requestChunks,
                        buffer = pendingBuffer,
                        peerBindings = peerBindings,
                        log = ::reportLog,
                    )
                if (!result.accepted) {
                    pendingGattWriteBuffersByIdentifier.remove(identifier)
                    false
                } else {
                    result.claimedHintPeerIdValue?.let { claimedHintPeerIdValue ->
                        promoteTemporaryL2capLinkIfPossible(
                            identifier = identifier,
                            resolvedHintPeerIdValue = claimedHintPeerIdValue,
                        )
                        gattNotifyRegistry.removeLinkForCentralIdentifier(identifier)?.close()
                        val link =
                            reuseOrCreateGattNotifyLink(
                                central = central,
                                identifier = identifier,
                                hintPeerIdValue = claimedHintPeerIdValue,
                                replaceExisting = false,
                                incomingFrames = pendingBuffer,
                            )
                        resolvedPeerId = link.hintPeerId
                        pendingGattWriteBuffersByIdentifier.remove(identifier)
                    }
                    decodedFrames += result.decodedFrames
                    true
                }
            }
        }
    reportLog(
        "GATT write request for " +
            "${peerBindings.hintForIdentifier(identifier)?.logSuffix() ?: identifier.takeLast(6)} " +
            "requests=${typedRequests.size} decodedFrames=${decodedFrames.size} accepted=$allRequestsAccepted"
    )
    peripheralManager?.respondToRequest(
        firstRequest,
        withResult = if (allRequestsAccepted) CBATTErrorSuccess else CBATTErrorUnlikelyError,
    )
    if (allRequestsAccepted && decodedFrames.isNotEmpty()) {
        resolvedPeerId?.let { peerId ->
            emitDecodedGattFrames(peerId = peerId, decodedFrames = decodedFrames)
        }
    }
}

internal fun BleTransportAdapter.acceptsGattWriteRequest(
    request: CBATTRequest,
    link: GattNotifyLink,
    decodedFrames: MutableList<ByteArray>,
): Boolean {
    return request.characteristic.UUID.UUIDString.lowercase() ==
        BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID &&
        request.offset.toInt() == 0 &&
        request.value?.let { value ->
            decodedFrames += link.appendIncomingWrite(value)
            true
        } == true
}

internal fun BleTransportAdapter.emitDecodedGattFrames(
    peerId: PeerId,
    decodedFrames: List<ByteArray>,
): Unit {
    coroutineScope.launch {
        decodedFrames.forEach { payload ->
            mutableEvents.emit(TransportEvent.FrameReceived(peerId = peerId, payload = payload))
        }
    }
}

internal fun BleTransportAdapter.reuseOrCreateGattNotifyLink(
    central: CBCentral,
    identifier: String,
    hintPeerIdValue: String,
    replaceExisting: Boolean,
    incomingFrames: L2capFrameBuffer = L2capFrameBuffer(),
): GattNotifyLink {
    if (!replaceExisting) {
        gattNotifyRegistry.currentLink(hintPeerIdValue)?.let { existingLink ->
            return existingLink
        }
    }
    val hintPeerId = PeerId(hintPeerIdValue)
    gattNotifyRegistry.removeLink(hintPeerId.value)?.close()
    return GattNotifyLink(
            peer =
                GattNotifyPeer(
                    hintPeerId = hintPeerId,
                    centralIdentifier = identifier,
                    maximumUpdateValueLength = central.maximumUpdateValueLength.toInt(),
                ),
            dependencies =
                GattNotifyDependencies(
                    incomingFrames = incomingFrames,
                    peripheralAdapterProvider = {
                        val manager = peripheralManager
                        val characteristic = gattNotifyServiceCharacteristic
                        if (manager == null || characteristic == null) {
                            null
                        } else {
                            CoreBluetoothGattNotifyPeripheralAdapter(
                                peripheralManager = manager,
                                notifyCharacteristic = characteristic,
                                central = central,
                            )
                        }
                    },
                    runPump = { block ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            block()
                        }
                    },
                    logger = { message -> log(message) },
                    schedulePumpRetry = {},
                ),
        )
        .also { link ->
            gattNotifyRegistry.replaceLink(hintPeerId.value, link)
            reportLog(
                "registered GATT notify side link for ${hintPeerId.logSuffix()} id=$identifier " +
                    "maxUpdateValueLength=${central.maximumUpdateValueLength}"
            )
        }
}

internal fun BleTransportAdapter.resolveGattNotifyHintPeerIdValue(identifier: String): String? {
    return selectGattNotifyHintPeerIdValue(
        GattNotifyHintSelectionRequest(
            boundHintPeerIdValue = peerBindings.hintForIdentifier(identifier),
            localPlatformFamily = currentDiscoveryPayload.platformFamily,
            discoveredPeers = peerRegistry.peers(),
        )
    )
}

internal fun BleTransportAdapter.hasActiveGattNotifyLink(hintPeer: String): Boolean {
    return gattNotifyRegistry.hasLink(hintPeer)
}

internal fun BleTransportAdapter.activeGattNotifyLinkFor(peer: DiscoveredPeer): GattNotifyLink? {
    return gattNotifyRegistry.resolveActiveLink(
        peer = peer,
        temporaryHintPeerIdValue =
            peerBindings.temporaryHintForIdentifier(peer.peripheralIdentifier),
        supportsGattNotifyBearer =
            supportsIosGattNotifyBearer(
                localPlatformFamily = currentDiscoveryPayload.platformFamily,
                remotePlatformFamily = peer.platformFamily,
                hasBridge = BleTransportBridgeRegistry.isGattNotifyBearerEnabled(),
            ),
    )
}
