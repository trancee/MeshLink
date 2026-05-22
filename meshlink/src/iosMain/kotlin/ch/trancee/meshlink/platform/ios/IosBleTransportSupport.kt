@file:Suppress("TooManyFunctions")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.IosBleTransportBridgeRegistry
import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.DirectWireFrame
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.GattDataBearerMode
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.transport.resolveGattDataBearerMode
import ch.trancee.meshlink.transport.shouldLocalPeerInitiateL2capConnection
import ch.trancee.meshlink.transport.shouldUseMixedPlatformGattNotifyBearer
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTErrorUnlikelyError
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerConnectionLatencyLow
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSStreamStatusAtEnd
import platform.Foundation.NSStreamStatusClosed
import platform.Foundation.NSStreamStatusError
import platform.darwin.NSObject
import platform.posix.getenv
import platform.posix.memcpy

private const val PEER_LOG_SUFFIX_CHARS: Int = 6
private const val NO_L2CAP_PSM: Int = 0
private const val ADVERTISED_PSM_MIN: Int = 128
private const val ADVERTISED_PSM_MAX: Int = 255
private const val NO_ADVERTISED_L2CAP_PSM: UByte = 0u
private val ADVERTISED_PSM_RANGE: IntRange = ADVERTISED_PSM_MIN..ADVERTISED_PSM_MAX
private const val NO_GATT_CHARACTERISTIC_PERMISSIONS: ULong = 0u
private const val NO_DATA_BYTES: Int = 0
private const val MILLIS_PER_SECOND: Double = 1000.0
private const val ENV_VALUE_NUMERIC_TRUE: String = "1"
private const val ENV_VALUE_BOOLEAN_TRUE: String = "true"
private const val ENV_VALUE_YES: String = "yes"

internal suspend fun IosBleTransport.sendWhenStarted(frame: OutboundFrame): TransportSendResult {
    val peer = resolvePeer(frame.peerId)
    return if (peer == null) {
        dropSend(
            frame,
            message = "iOS BLE peer has not been discovered",
            detail = "peer not discovered",
        )
    } else {
        sendToPeer(frame, peer)
    }
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
    val dataBearerMode =
        resolveSendDataBearerMode(frame = frame, peer = peer, directFrame = directFrame)
    val gattSendResult =
        sendViaGattNotifyLinkOrNull(frame = frame, peer = peer, directFrame = directFrame)
    return when {
        gattSendResult != null -> gattSendResult
        directFrame is DirectWireFrame.Data && dataBearerMode == GattDataBearerMode.GATT_REQUIRED ->
            dropSend(
                frame,
                message = "iOS BLE GATT notify side link is not ready",
                detail = "required GATT notify side link not ready",
            )
        else -> sendViaL2capWhenReady(frame = frame, peer = peer)
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
    val gattNotifyLink =
        activeGattNotifyLinkFor(peer)?.takeIf { directFrame is DirectWireFrame.Data }
    return gattNotifyLink?.let { link ->
        runCatching {
                log(
                    "sending ${frame.payload.size} bytes via GATT notify side link for ${frame.peerId.logSuffix()}"
                )
                if (!link.enqueue(frame.payload)) {
                    return@runCatching TransportSendResult.Dropped(
                        "iOS BLE GATT notify side link is not accepting frames"
                    )
                }
                TransportSendResult.Delivered
            }
            .getOrElse { error ->
                TransportSendResult.Dropped(
                    "iOS BLE GATT notify send failed: ${error.message.orEmpty()}"
                )
            }
    }
}

internal suspend fun IosBleTransport.sendViaL2capWhenReady(
    frame: OutboundFrame,
    peer: DiscoveredPeer,
): TransportSendResult {
    val link = activeLinkFor(peer)
    return if (link == null) {
        dropSendWhileWaitingForL2cap(frame = frame, peer = peer)
    } else {
        runCatching {
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
}

internal fun IosBleTransport.dropSendWhileWaitingForL2cap(
    frame: OutboundFrame,
    peer: DiscoveredPeer,
): TransportSendResult {
    connectIfNeeded(peer)
    if (shouldInitiateL2cap(peer.keyHash, peer.platformFamily)) {
        log("send(${frame.peerId.logSuffix()}) no active link, triggering connect")
    } else {
        log(
            "send(${frame.peerId.logSuffix()}) waiting for inbound L2CAP link; requesting outbound connect for explicit send"
        )
    }
    return TransportSendResult.Dropped("iOS BLE L2CAP connection is not ready")
}

internal fun IosBleTransport.dropSend(
    frame: OutboundFrame,
    message: String,
    detail: String,
): TransportSendResult {
    log("send(${frame.peerId.logSuffix()}) dropped: $detail")
    return TransportSendResult.Dropped(message)
}

internal fun IosBleTransport.startScanIfReady(central: CBCentralManager): Unit {
    if (!started || discoverySuspended || central.state != CBManagerStatePoweredOn) {
        return
    }
    central.scanForPeripheralsWithServices(
        serviceUUIDs =
            listOf(CBUUID.UUIDWithString(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID)),
        options = null,
    )
}

internal fun IosBleTransport.publishL2capChannelIfReady(peripheral: CBPeripheralManager): Unit {
    if (!started || peripheral.state != CBManagerStatePoweredOn) {
        return
    }
    peripheral.publishL2CAPChannelWithEncryption(encryptionRequired = false)
}

internal fun IosBleTransport.installGattNotifyServiceIfReady(
    peripheral: CBPeripheralManager
): Unit {
    val bluetoothReady = peripheral.state == CBManagerStatePoweredOn
    val hasCryptoBridge = IosBleTransportBridgeRegistry.currentCallbacksOrNull() != null
    val canInstallGattNotifyService =
        started && bluetoothReady && !gattNotifyServiceInstalled && hasCryptoBridge
    if (!canInstallGattNotifyService) {
        return
    }
    val notifyCharacteristic =
        CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID),
            properties = CBCharacteristicPropertyNotify,
            value = null,
            permissions = NO_GATT_CHARACTERISTIC_PERMISSIONS,
        )
    val writeCharacteristic =
        CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID),
            properties = CBCharacteristicPropertyWrite,
            value = null,
            permissions = CBAttributePermissionsWriteable,
        )
    val service =
        CBMutableService(
            type = CBUUID.UUIDWithString(BleDiscoveryContract.GATT_FALLBACK_SERVICE_UUID),
            primary = true,
        )
    service.setCharacteristics(listOf(writeCharacteristic, notifyCharacteristic))
    gattNotifyServiceCharacteristic = notifyCharacteristic
    gattNotifyServiceInstalled = true
    peripheral.addService(service)
    log("installed GATT notify side-channel service")
}

internal fun IosBleTransport.handlePublishedL2capChannel(psm: UShort, error: NSError?): Unit {
    if (error != null) {
        reportLog("publish L2CAP failed: ${error.localizedDescription}")
        currentDiscoveryPayload = discoveryPayload(l2capPsm = NO_ADVERTISED_L2CAP_PSM)
    } else {
        currentDiscoveryPayload = discoveryPayload(l2capPsm = advertisedPsm(psm))
        log("published L2CAP channel psm=$psm advertised=${currentDiscoveryPayload.l2capPsm}")
    }
    startAdvertisingIfReady()
}

internal fun IosBleTransport.startAdvertisingIfReady(): Unit {
    val peripheral = peripheralManager ?: return
    if (!started || discoverySuspended || peripheral.state != CBManagerStatePoweredOn) {
        return
    }
    peripheral.stopAdvertising()
    peripheral.startAdvertising(
        advertisementData =
            mapOf(
                CBAdvertisementDataServiceUUIDsKey to
                    listOf(
                        CBUUID.UUIDWithString(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID),
                        CBUUID.UUIDWithString(currentDiscoveryPayload.payloadUuidString()),
                    )
            )
    )
}

internal fun IosBleTransport.handleDiscoveredPeripheral(
    peripheral: CBPeripheral,
    serviceUuids: List<CBUUID>,
): Unit {
    val payload =
        decodeDiscoveryPayloadOrNull(peripheral = peripheral, serviceUuids = serviceUuids) ?: return
    val hintPeerId = PeerId(payload.keyHash.toHexString())
    val transportMode =
        if (payload.l2capPsm.toInt() == NO_L2CAP_PSM) TransportMode.GATT else TransportMode.L2CAP
    log(
        "scan found ${hintPeerId.logSuffix()} mode=$transportMode psm=${payload.l2capPsm} " +
            "platform=${payload.platformFamily} id=${peripheral.identifier.UUIDString}"
    )

    val identifier = peripheral.identifier.UUIDString.lowercase()
    val discoveredPeer =
        upsertDiscoveredPeer(
            hintPeerId = hintPeerId,
            identifier = identifier,
            peripheral = peripheral,
            payload = payload,
            transportMode = transportMode,
        )

    if (transportMode == TransportMode.L2CAP) {
        handleL2capDiscovery(
            identifier = identifier,
            hintPeerId = hintPeerId,
            payload = payload,
            peer = discoveredPeer,
        )
    }
}

internal fun IosBleTransport.decodeDiscoveryPayloadOrNull(
    peripheral: CBPeripheral,
    serviceUuids: List<CBUUID>,
): BleDiscoveryPayload? {
    val payloadUuid =
        serviceUuids
            .map { uuid -> uuid.UUIDString.lowercase() }
            .firstOrNull { uuid -> !BleDiscoveryContract.isAdvertisementServiceUuid(uuid) }
    val payload = payloadUuid?.let { uuid ->
        runCatching { BleDiscoveryPayload.fromUuidString(uuid) }.getOrNull()
    }
    val isPayloadRelevant =
        payload != null &&
            payload.meshHash == currentDiscoveryPayload.meshHash &&
            !payload.keyHash.contentEquals(localKeyHash)
    val supportsProtocolVersion =
        isPayloadRelevant &&
            BleDiscoveryContract.isSupportedProtocolVersion(payload.protocolVersion)
    if (isPayloadRelevant && !supportsProtocolVersion) {
        log(
            "ignoring discovery payload with unsupported protocolVersion=${payload.protocolVersion} " +
                "id=${peripheral.identifier.UUIDString}"
        )
    }
    return if (supportsProtocolVersion) payload else null
}

internal fun IosBleTransport.upsertDiscoveredPeer(
    hintPeerId: PeerId,
    identifier: String,
    peripheral: CBPeripheral,
    payload: BleDiscoveryPayload,
    transportMode: TransportMode,
): DiscoveredPeer {
    peerBindings.retainPeripheral(identifier, peripheral)
    val discoveryUpdate =
        peerRegistry.upsertDiscovery(
            hintPeerId = hintPeerId,
            discovery =
                DiscoveredPeerDiscovery(
                    identifier = identifier,
                    keyHash = payload.keyHash,
                    l2capPsm = payload.l2capPsm.toInt(),
                    transportMode = transportMode,
                    platformFamily = payload.platformFamily,
                ),
        )
    discoveryUpdate.events.forEach { event -> mutableEvents.tryEmit(event) }
    return discoveryUpdate.peer
}

internal fun IosBleTransport.handleL2capDiscovery(
    identifier: String,
    hintPeerId: PeerId,
    payload: BleDiscoveryPayload,
    peer: DiscoveredPeer,
): Unit {
    if (!shouldInitiateL2cap(payload.keyHash, payload.platformFamily)) {
        promoteTemporaryL2capLinkIfPossible(
            identifier = identifier,
            resolvedHintPeerIdValue = hintPeerId.value,
        )
    }
    maybeLogRediscoveryWithoutLink(
        peer = peer,
        transportMode = TransportMode.L2CAP,
        identifier = identifier,
    )
    if (shouldInitiateL2cap(payload.keyHash, payload.platformFamily)) {
        log("initiating L2CAP connect to ${hintPeerId.logSuffix()}")
        connectIfNeeded(peer)
    }
}

internal fun IosBleTransport.handleConnectedPeripheral(peripheral: CBPeripheral): Unit {
    val identifier = peripheral.identifier.UUIDString.lowercase()
    val hint = peerBindings.hintForIdentifier(identifier) ?: return
    val peer = peerRegistry.peer(hint) ?: return
    pendingConnectionsByHint[peer.hintPeerId.value] = identifier
    peripheral.delegate = peripheralClientDelegate
    peripheral.openL2CAPChannel(peer.l2capPsm.convert())
}

internal fun IosBleTransport.handleFailedConnection(
    peripheral: CBPeripheral,
    error: NSError?,
): Unit {
    val identifier = peripheral.identifier.UUIDString.lowercase()
    val hint = peerBindings.hintForIdentifier(identifier) ?: return
    pendingConnectionsByHint.remove(hint)
    peerRegistry.setRediscoveryLoggedWithoutLink(hint, logged = false)
    reportLog(
        "L2CAP connect failed for ${hint.logSuffix()}: ${error?.localizedDescription.orEmpty()}"
    )
}

internal fun IosBleTransport.handleDisconnectedPeripheral(peripheral: CBPeripheral): Unit {
    val identifier = peripheral.identifier.UUIDString.lowercase()
    val hint =
        peerBindings.hintForIdentifier(identifier)
            ?: peerBindings.temporaryHintForIdentifier(identifier)
            ?: return
    pendingConnectionsByHint.remove(hint)
    closeLink(hintPeer = hint, reason = "peripheral disconnected")
}

internal fun IosBleTransport.handleOpenedOutgoingChannel(
    peripheral: CBPeripheral,
    channel: CBL2CAPChannel?,
    error: NSError?,
): Unit {
    val identifier = peripheral.identifier.UUIDString.lowercase()
    val hint = peerBindings.hintForIdentifier(identifier) ?: return
    pendingConnectionsByHint.remove(hint)
    if (channel == null) {
        reportLog(
            "didOpenL2CAPChannel failed for ${hint.logSuffix()}: ${error?.localizedDescription.orEmpty()}"
        )
        return
    }
    log("didOpenL2CAPChannel succeeded for ${hint.logSuffix()}")
    peerRegistry.setRediscoveryLoggedWithoutLink(hint, logged = false)
    registerConnectedChannel(PeerId(hint), identifier, channel)
}

internal fun IosBleTransport.handleOpenedIncomingChannel(
    channel: CBL2CAPChannel?,
    error: NSError?,
): Unit {
    if (channel == null) {
        reportLog("incoming L2CAP channel failed: ${error?.localizedDescription.orEmpty()}")
        return
    }
    val identifier = channel.peer?.identifier?.UUIDString?.lowercase() ?: return
    val connectedCentral = channel.peer as? CBCentral
    val selectedHintPeerIdValue =
        selectIncomingL2capHintPeerId(
            IncomingL2capHintSelectionRequest(
                peripheralIdentifier = identifier,
                peerHintByIdentifier = peerBindings.hintBindings,
                discoveredPeers = peerRegistry.incomingL2capHintCandidates(),
                ignoredHints =
                    IncomingL2capSelectionIgnoredHints(
                        activeHintIds = activeLinksByHint.keys,
                        pendingHintIds = pendingConnectionsByHint.keys,
                    ),
                localPeer =
                    IncomingL2capSelectionLocalPeer(
                        localKeyHash = localKeyHash,
                        platformFamily = currentDiscoveryPayload.platformFamily,
                    ),
            )
        )
    val hintPeerId =
        selectedHintPeerIdValue?.let(::PeerId) ?: peerBindings.temporaryPeerId(identifier)
    if (selectedHintPeerIdValue != null) {
        peerBindings.bindHintToIdentifier(identifier, selectedHintPeerIdValue)
        log(
            "binding incoming L2CAP channel to discovered peer ${hintPeerId.logSuffix()} id=$identifier"
        )
    } else if (hintPeerId.value.startsWith(TEMPORARY_PEER_PREFIX)) {
        log("binding incoming L2CAP channel to temporary peer ${hintPeerId.value}")
    }
    registerConnectedChannel(
        hintPeerId = hintPeerId,
        peripheralIdentifier = identifier,
        channel = channel,
        connectedCentral = connectedCentral,
    )
}

internal fun IosBleTransport.handleGattNotifySubscribed(
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

internal fun IosBleTransport.handleGattNotifyUnsubscribed(
    central: CBCentral,
    characteristic: CBCharacteristic,
): Unit {
    val isNotifyCharacteristic =
        characteristic.UUID.UUIDString.lowercase() ==
            BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID
    val identifier = central.identifier.UUIDString.lowercase()
    val hintPeerIdValue =
        if (isNotifyCharacteristic) peerBindings.hintForIdentifier(identifier) else null
    if (hintPeerIdValue != null) {
        removeGattNotifyLink(identifier = identifier, hintPeerIdValue = hintPeerIdValue)
    }
}

internal fun IosBleTransport.handleGattWriteRequests(requests: List<*>): Unit {
    val typedRequests = requests.filterIsInstance<CBATTRequest>()
    typedRequests.firstOrNull()?.let { firstRequest ->
        processGattWriteRequests(typedRequests = typedRequests, firstRequest = firstRequest)
    }
}

internal fun IosBleTransport.pumpGattNotifyLinks(): Unit {
    activeGattNotifyLinksByHint.values.forEach { link -> link.pump() }
}

internal fun IosBleTransport.ensureGattNotifyLink(
    central: CBCentral,
    replaceExisting: Boolean,
): IosGattNotifyLink? {
    val identifier = central.identifier.UUIDString.lowercase()
    val hintPeerIdValue =
        if (IosBleTransportBridgeRegistry.currentCallbacksOrNull() != null) {
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

internal fun IosBleTransport.removeGattNotifyLink(
    identifier: String,
    hintPeerIdValue: String,
): Unit {
    activeGattNotifyLinksByHint.remove(hintPeerIdValue)?.close()
    reportLog("removed GATT notify side link for ${hintPeerIdValue.logSuffix()} id=$identifier")
    if (!activeLinksByHint.containsKey(hintPeerIdValue)) {
        peerRegistry.setPresenceAnnounced(hintPeerIdValue, announced = false)
        mutableEvents.tryEmit(TransportEvent.PeerLost(PeerId(hintPeerIdValue)))
    }
}

internal fun IosBleTransport.processGattWriteRequests(
    typedRequests: List<CBATTRequest>,
    firstRequest: CBATTRequest,
): Unit {
    val central = firstRequest.central
    val link = ensureGattNotifyLink(central = central, replaceExisting = false)
    if (link == null) {
        reportLog(
            "GATT write request rejected: no active side link for " +
                "central=${central.identifier.UUIDString.lowercase()} requests=${typedRequests.size}"
        )
        peripheralManager?.respondToRequest(firstRequest, withResult = CBATTErrorUnlikelyError)
        return
    }
    val decodedFrames = mutableListOf<ByteArray>()
    val allRequestsAccepted = typedRequests.all { request ->
        acceptsGattWriteRequest(request, link, decodedFrames)
    }
    reportLog(
        "GATT write request for ${link.hintPeerId.logSuffix()} requests=${typedRequests.size} " +
            "decodedFrames=${decodedFrames.size} accepted=$allRequestsAccepted"
    )
    peripheralManager?.respondToRequest(
        firstRequest,
        withResult = if (allRequestsAccepted) CBATTErrorSuccess else CBATTErrorUnlikelyError,
    )
    if (allRequestsAccepted && decodedFrames.isNotEmpty()) {
        emitDecodedGattFrames(peerId = link.hintPeerId, decodedFrames = decodedFrames)
    }
}

internal fun IosBleTransport.acceptsGattWriteRequest(
    request: CBATTRequest,
    link: IosGattNotifyLink,
    decodedFrames: MutableList<ByteArray>,
): Boolean {
    return request.characteristic.UUID.UUIDString.lowercase() ==
        BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID &&
        request.offset.toInt() == 0 &&
        request.value?.let { value ->
            decodedFrames += link.appendIncomingWrite(value.toByteArray())
            true
        } == true
}

internal fun IosBleTransport.emitDecodedGattFrames(
    peerId: PeerId,
    decodedFrames: List<ByteArray>,
): Unit {
    coroutineScope.launch {
        decodedFrames.forEach { payload ->
            mutableEvents.emit(TransportEvent.FrameReceived(peerId = peerId, payload = payload))
        }
    }
}

internal fun IosBleTransport.reuseOrCreateGattNotifyLink(
    central: CBCentral,
    identifier: String,
    hintPeerIdValue: String,
    replaceExisting: Boolean,
): IosGattNotifyLink {
    if (!replaceExisting) {
        activeGattNotifyLinksByHint[hintPeerIdValue]?.let { existingLink ->
            return existingLink
        }
    }
    val hintPeerId = PeerId(hintPeerIdValue)
    activeGattNotifyLinksByHint.remove(hintPeerId.value)?.close()
    var createdLink: IosGattNotifyLink? = null
    return IosGattNotifyLink(
            peer =
                IosGattNotifyPeer(
                    hintPeerId = hintPeerId,
                    centralIdentifier = identifier,
                    central = central,
                ),
            dependencies =
                IosGattNotifyDependencies(
                    peripheralManagerProvider = { peripheralManager },
                    notifyCharacteristicProvider = { gattNotifyServiceCharacteristic },
                    logger = ::log,
                    schedulePumpRetry = {
                        coroutineScope.launch {
                            delay(GATT_NOTIFY_PUMP_RETRY_POLL_INTERVAL_MS)
                            createdLink
                                ?.takeIf { activeGattNotifyLinksByHint[hintPeerId.value] === it }
                                ?.pumpOnMain()
                        }
                    },
                ),
        )
        .also { link ->
            createdLink = link
            activeGattNotifyLinksByHint[hintPeerId.value] = link
            reportLog(
                "registered GATT notify side link for ${hintPeerId.logSuffix()} id=$identifier " +
                    "maxUpdateValueLength=${central.maximumUpdateValueLength}"
            )
        }
}

internal fun IosBleTransport.resolveGattNotifyHintPeerIdValue(identifier: String): String? {
    return peerBindings.hintForIdentifier(identifier)
        ?: peerRegistry
            .peers()
            .firstOrNull { peer ->
                shouldUseMixedPlatformGattNotifyBearer(
                    localPlatformFamily = currentDiscoveryPayload.platformFamily,
                    remotePlatformFamily = peer.platformFamily,
                )
            }
            ?.hintPeerId
            ?.value
}

internal fun IosBleTransport.hasActiveGattNotifyLink(hintPeer: String): Boolean {
    return activeGattNotifyLinksByHint.containsKey(hintPeer)
}

internal fun IosBleTransport.promoteTemporaryL2capLinkIfPossible(
    identifier: String,
    resolvedHintPeerIdValue: String,
): Unit {
    val temporaryHintPeerIdValue =
        selectTemporaryL2capHintPromotion(
            TemporaryL2capHintPromotionRequest(
                identifier = identifier,
                resolvedHintPeerIdValue = resolvedHintPeerIdValue,
                temporaryHintByIdentifier = peerBindings.temporaryHintBindings,
                activeHintIds = activeLinksByHint.keys,
                temporaryPeerPrefix = TEMPORARY_PEER_PREFIX,
            )
        ) ?: return
    val link = activeLinksByHint.remove(temporaryHintPeerIdValue) ?: return
    val resolvedHintPeerId = PeerId(resolvedHintPeerIdValue)
    link.hintPeerId = resolvedHintPeerId
    activeLinksByHint[resolvedHintPeerIdValue] = link
    peerBindings.bindTemporaryHint(identifier, resolvedHintPeerIdValue)
    peerRegistry.setRediscoveryLoggedWithoutLink(resolvedHintPeerIdValue, logged = false)
    reportLog(
        "promoted temporary L2CAP link ${temporaryHintPeerIdValue.logSuffix()} -> " +
            "${resolvedHintPeerIdValue.logSuffix()} id=$identifier"
    )
}

internal fun IosBleTransport.registerConnectedChannel(
    hintPeerId: PeerId,
    peripheralIdentifier: String,
    channel: CBL2CAPChannel,
    connectedCentral: CBCentral? = null,
): Unit {
    if (activeLinksByHint.containsKey(hintPeerId.value)) {
        log("ignoring duplicate L2CAP channel for ${hintPeerId.logSuffix()}")
        return
    }
    val link =
        createConnectedChannelLink(hintPeerId, peripheralIdentifier, channel, connectedCentral)
    activeLinksByHint[hintPeerId.value] = link
    peerBindings.bindTemporaryHint(peripheralIdentifier, hintPeerId.value)
    peerRegistry.setRediscoveryLoggedWithoutLink(hintPeerId.value, logged = false)
    log("registered L2CAP link for ${hintPeerId.logSuffix()} id=$peripheralIdentifier")
    startConnectedChannelWriteLoop(link)
    startConnectedChannelReadLoop(link)
}

internal fun IosBleTransport.createConnectedChannelLink(
    hintPeerId: PeerId,
    peripheralIdentifier: String,
    channel: CBL2CAPChannel,
    connectedCentral: CBCentral?,
): IosL2capLink {
    var createdLink: IosL2capLink? = null
    return IosL2capLink(
            hintPeerId = hintPeerId,
            peripheralIdentifier = peripheralIdentifier,
            channel = channel,
            dependencies =
                IosL2capLinkDependencies(
                    incomingFrames = IosL2capFrameBuffer(),
                    telemetryEnabled = telemetryEnabled,
                    telemetryLogger = ::emitTransportLog,
                    promoteActiveWriteLatency = {
                        connectedCentral?.let { central ->
                            requestLowConnectionLatency(
                                hintPeerId = createdLink?.hintPeerId ?: hintPeerId,
                                central = central,
                            )
                        }
                    },
                    nowMillis = ::monotonicNowMillis,
                ),
        )
        .also { link -> createdLink = link }
}

internal fun IosBleTransport.startConnectedChannelWriteLoop(link: IosL2capLink): Unit {
    link.writeLoopJob = coroutineScope.launch {
        try {
            link.runWriteLoop()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            reportLog(
                "L2CAP write loop failed for ${link.hintPeerId.logSuffix()}: ${error.message.orEmpty()}"
            )
        } finally {
            closeLink(hintPeer = link.hintPeerId.value, reason = "write loop stopped")
        }
    }
}

internal fun IosBleTransport.startConnectedChannelReadLoop(link: IosL2capLink): Unit {
    link.readLoopJob = coroutineScope.launch {
        try {
            link.runReadLoop { payload ->
                mutableEvents.emit(
                    TransportEvent.FrameReceived(peerId = link.hintPeerId, payload = payload)
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: MeshLinkException) {
            reportLog(
                "L2CAP read loop failed for ${link.hintPeerId.logSuffix()}: ${error.message.orEmpty()}"
            )
        } finally {
            closeLink(hintPeer = link.hintPeerId.value, reason = "channel closed")
        }
    }
}

internal fun IosBleTransport.connectIfNeeded(peer: DiscoveredPeer): Unit {
    if (peer.l2capPsm == NO_L2CAP_PSM) {
        log("connectIfNeeded(${peer.hintPeerId.logSuffix()}) skipped: no PSM")
        return
    }
    val isActiveOrPending =
        activeLinksByHint.containsKey(peer.hintPeerId.value) ||
            pendingConnectionsByHint.containsKey(peer.hintPeerId.value)
    if (isActiveOrPending) {
        log("connectIfNeeded(${peer.hintPeerId.logSuffix()}) skipped: already active or pending")
    } else {
        peerBindings.peripheralFor(peer.peripheralIdentifier)?.let { peripheral ->
            pendingConnectionsByHint[peer.hintPeerId.value] = peer.peripheralIdentifier
            centralManager?.connectPeripheral(peripheral, options = null)
        }
    }
}

internal fun IosBleTransport.shouldInitiateL2cap(
    remoteKeyHash: ByteArray,
    remotePlatformFamily: BleDiscoveryPlatformFamily,
): Boolean {
    return shouldLocalPeerInitiateL2capConnection(
        localKeyHash = localKeyHash,
        localPlatformFamily = currentDiscoveryPayload.platformFamily,
        remoteKeyHash = remoteKeyHash,
        remotePlatformFamily = remotePlatformFamily,
    )
}

internal fun IosBleTransport.maybeLogRediscoveryWithoutLink(
    peer: DiscoveredPeer,
    transportMode: TransportMode,
    identifier: String,
): Unit {
    if (transportMode != TransportMode.L2CAP) {
        peer.rediscoveryLoggedWithoutLink = false
        return
    }
    val hasActiveLink =
        activeLinksByHint.containsKey(peer.hintPeerId.value) ||
            hasActiveGattNotifyLink(peer.hintPeerId.value) ||
            (peerBindings
                .temporaryHintForIdentifier(identifier)
                ?.let(activeLinksByHint::containsKey) == true)
    val hasPendingConnect = pendingConnectionsByHint.containsKey(peer.hintPeerId.value)
    if (hasActiveLink || hasPendingConnect) {
        peer.rediscoveryLoggedWithoutLink = false
        return
    }
    if (!peer.rediscoveryLoggedWithoutLink) {
        log(
            "scan rediscovered ${peer.hintPeerId.logSuffix()} with no active link " +
                "pendingConnect=$hasPendingConnect id=$identifier"
        )
        peer.rediscoveryLoggedWithoutLink = true
    }
}

internal fun IosBleTransport.activeLinkFor(peer: DiscoveredPeer): IosL2capLink? {
    val directLink = activeLinksByHint[peer.hintPeerId.value]
    val temporaryHint = peerBindings.temporaryHintForIdentifier(peer.peripheralIdentifier)
    return directLink ?: temporaryHint?.let(activeLinksByHint::get)
}

internal fun IosBleTransport.activeGattNotifyLinkFor(peer: DiscoveredPeer): IosGattNotifyLink? {
    val supportsMixedGattNotifyBearer =
        shouldUseMixedPlatformGattNotifyBearer(
            localPlatformFamily = currentDiscoveryPayload.platformFamily,
            remotePlatformFamily = peer.platformFamily,
        ) && IosBleTransportBridgeRegistry.currentCallbacksOrNull() != null
    val temporaryHint = peerBindings.temporaryHintForIdentifier(peer.peripheralIdentifier)
    return if (supportsMixedGattNotifyBearer) {
        activeGattNotifyLinksByHint[peer.hintPeerId.value]
            ?: temporaryHint?.let(activeGattNotifyLinksByHint::get)
    } else {
        null
    }
}

internal fun IosBleTransport.resolvePeer(peerId: PeerId): DiscoveredPeer? {
    return peerRegistry.resolve(peerId)
}

internal fun IosBleTransport.stopTransport(clearPeers: Boolean): Unit {
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

internal fun IosBleTransport.closeLink(hintPeer: String, reason: String): Unit {
    val link = activeLinksByHint.remove(hintPeer) ?: return
    peerRegistry.setRediscoveryLoggedWithoutLink(hintPeer, logged = false)
    reportLog(
        "closing L2CAP link ${hintPeer.logSuffix()}: $reason " +
            "discoveredPeerRetained=${peerRegistry.peer(hintPeer) != null} " +
            "pendingConnect=${pendingConnectionsByHint.containsKey(hintPeer)}"
    )
    link.readLoopJob?.cancel()
    link.writeLoopJob?.cancel()
    link.close()
    if (hasActiveGattNotifyLink(hintPeer)) {
        reportLog(
            "retaining peer ${hintPeer.logSuffix()} after L2CAP close because the GATT side link is still active"
        )
        return
    }
    peerRegistry.setPresenceAnnounced(hintPeer, announced = false)
    mutableEvents.tryEmit(TransportEvent.PeerLost(PeerId(hintPeer)))
}

internal fun IosBleTransport.refreshDiscoveryState(): Unit {
    centralManager?.stopScan()
    peripheralManager?.stopAdvertising()
    centralManager?.let(::startScanIfReady)
    startAdvertisingIfReady()
}

internal fun IosBleTransport.requestLowConnectionLatency(
    hintPeerId: PeerId,
    central: CBCentral,
): Unit {
    peripheralManager?.setDesiredConnectionLatency(
        CBPeripheralManagerConnectionLatencyLow,
        forCentral = central,
    )
    reportLog(
        "requested low connection latency for ${hintPeerId.logSuffix()} " +
            "central=${central.identifier.UUIDString.lowercase()}"
    )
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

internal class IosL2capLinkDependencies(
    val incomingFrames: IosL2capFrameBuffer,
    val telemetryEnabled: Boolean,
    val telemetryLogger: (String) -> Unit,
    val promoteActiveWriteLatency: () -> Unit,
    val nowMillis: () -> Long,
)

internal class IosL2capLink(
    var hintPeerId: PeerId,
    val peripheralIdentifier: String,
    channel: CBL2CAPChannel,
    dependencies: IosL2capLinkDependencies,
) {
    private val incomingFrames: IosL2capFrameBuffer = dependencies.incomingFrames
    private val telemetryEnabled: Boolean = dependencies.telemetryEnabled
    private val telemetryLogger: (String) -> Unit = dependencies.telemetryLogger
    private val nowMillis: () -> Long = dependencies.nowMillis
    private val inputStream = checkNotNull(channel.inputStream).apply { open() }
    private val outputStream = checkNotNull(channel.outputStream).apply { open() }
    private val readPump =
        IosL2capReadPump(
            inputStream = inputStream,
            frameBuffer = incomingFrames,
            dependencies =
                IosL2capReadPumpDependencies(
                    hintPeerIdProvider = { hintPeerId },
                    telemetryEnabled = telemetryEnabled,
                    telemetryLogger = telemetryLogger,
                    timing =
                        IosL2capReadTiming(
                            nowMillis = nowMillis,
                            activePollIntervalMs = ACTIVE_STREAM_POLL_INTERVAL_MS,
                            idlePollIntervalMs = IDLE_STREAM_POLL_INTERVAL_MS,
                        ),
                ),
        )
    private val writePump =
        IosL2capWritePump(
            outputStream = outputStream,
            frameCodec = incomingFrames,
            dependencies =
                IosL2capWritePumpDependencies(
                    hintPeerIdProvider = { hintPeerId },
                    telemetryEnabled = telemetryEnabled,
                    telemetryLogger = telemetryLogger,
                    promoteActiveWriteLatency = dependencies.promoteActiveWriteLatency,
                    timing =
                        IosL2capWriteTiming(
                            nowMillis = nowMillis,
                            activePollIntervalMs = ACTIVE_STREAM_POLL_INTERVAL_MS,
                        ),
                ),
        )
    var readLoopJob: Job? = null
    var writeLoopJob: Job? = null

    suspend fun runReadLoop(onFrameReceived: suspend (ByteArray) -> Unit): Unit {
        readPump.runLoop(onFrameReceived)
    }

    suspend fun enqueue(payload: ByteArray): Boolean {
        return writePump.enqueue(payload)
    }

    suspend fun runWriteLoop(): Unit {
        writePump.runLoop()
    }

    fun close(): Unit {
        writePump.close()
        inputStream.close()
        outputStream.close()
    }

    suspend fun discardQueuedFrames(): Int {
        return writePump.discardQueuedFrames()
    }
}

private const val IDLE_STREAM_POLL_INTERVAL_MS: Long = 5
private const val ACTIVE_STREAM_POLL_INTERVAL_MS: Long = 1
private const val GATT_NOTIFY_PUMP_RETRY_POLL_INTERVAL_MS: Long = 2
internal const val TRANSPORT_TELEMETRY_ENV: String = "MESHLINK_TRANSPORT_TELEMETRY"
internal const val TRANSPORT_DEBUG_ENV: String = "MESHLINK_TRANSPORT_DEBUG"

private fun monotonicNowMillis(): Long {
    return (NSProcessInfo.processInfo.systemUptime * MILLIS_PER_SECOND).toLong()
}

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

internal data class IncomingL2capHintCandidate(
    internal val hintPeerIdValue: String,
    internal val keyHash: ByteArray,
    internal val platformFamily: BleDiscoveryPlatformFamily,
    internal val transportMode: TransportMode,
)

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

private fun NSData.toByteArray(): ByteArray {
    val lengthInt = length.toInt()
    if (lengthInt == NO_DATA_BYTES) {
        return ByteArray(0)
    }
    return ByteArray(lengthInt).also { output ->
        output.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}

internal class IncomingL2capSelectionIgnoredHints(
    val activeHintIds: Collection<String>,
    val pendingHintIds: Collection<String>,
)

internal class IncomingL2capSelectionLocalPeer(
    localKeyHash: ByteArray,
    val platformFamily: BleDiscoveryPlatformFamily,
) {
    val keyHash: ByteArray = localKeyHash.copyOf()
}

internal class IncomingL2capHintSelectionRequest(
    val peripheralIdentifier: String,
    val peerHintByIdentifier: Map<String, String>,
    val discoveredPeers: List<IncomingL2capHintCandidate>,
    val ignoredHints: IncomingL2capSelectionIgnoredHints,
    val localPeer: IncomingL2capSelectionLocalPeer,
)

internal fun selectIncomingL2capHintPeerId(request: IncomingL2capHintSelectionRequest): String? {
    request.peerHintByIdentifier[request.peripheralIdentifier]?.let { mappedHint ->
        return mappedHint
    }
    val waitingCandidates =
        request.discoveredPeers.filter { candidate ->
            candidate.transportMode == TransportMode.L2CAP &&
                candidate.hintPeerIdValue !in request.ignoredHints.activeHintIds &&
                candidate.hintPeerIdValue !in request.ignoredHints.pendingHintIds &&
                !shouldLocalPeerInitiateL2capConnection(
                    localKeyHash = request.localPeer.keyHash,
                    localPlatformFamily = request.localPeer.platformFamily,
                    remoteKeyHash = candidate.keyHash,
                    remotePlatformFamily = candidate.platformFamily,
                )
        }
    return waitingCandidates.singleOrNull()?.hintPeerIdValue
}

internal class TemporaryL2capHintPromotionRequest(
    val identifier: String,
    val resolvedHintPeerIdValue: String,
    val temporaryHintByIdentifier: Map<String, String>,
    val activeHintIds: Collection<String>,
    val temporaryPeerPrefix: String = "cb-",
)

internal fun selectTemporaryL2capHintPromotion(
    request: TemporaryL2capHintPromotionRequest
): String? {
    val temporaryHintPeerIdValue = request.temporaryHintByIdentifier[request.identifier]
    val canPromote =
        temporaryHintPeerIdValue != null &&
            temporaryHintPeerIdValue != request.resolvedHintPeerIdValue &&
            temporaryHintPeerIdValue.startsWith(request.temporaryPeerPrefix) &&
            temporaryHintPeerIdValue in request.activeHintIds &&
            request.resolvedHintPeerIdValue !in request.activeHintIds
    return if (canPromote) temporaryHintPeerIdValue else null
}

internal class IosCentralDelegate(private val owner: IosBleTransport) :
    NSObject(), CBCentralManagerDelegateProtocol {
    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        owner.startScanIfReady(central)
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        val rawServiceUuids = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<*>
        val serviceUuids = rawServiceUuids?.filterIsInstance<CBUUID>() ?: return
        if (serviceUuids.size != rawServiceUuids.size) {
            return
        }
        owner.handleDiscoveredPeripheral(didDiscoverPeripheral, serviceUuids)
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        owner.handleConnectedPeripheral(didConnectPeripheral)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        owner.handleFailedConnection(didFailToConnectPeripheral, error)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        owner.handleDisconnectedPeripheral(didDisconnectPeripheral)
    }
}

internal class IosPeripheralClientDelegate(private val owner: IosBleTransport) :
    NSObject(), CBPeripheralDelegateProtocol {
    override fun peripheral(
        peripheral: CBPeripheral,
        didOpenL2CAPChannel: CBL2CAPChannel?,
        error: NSError?,
    ) {
        owner.handleOpenedOutgoingChannel(peripheral, didOpenL2CAPChannel, error)
    }
}

internal class IosPeripheralManagerDelegate(private val owner: IosBleTransport) :
    NSObject(), CBPeripheralManagerDelegateProtocol {
    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        owner.installGattNotifyServiceIfReady(peripheral)
        owner.publishL2capChannelIfReady(peripheral)
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeToCharacteristic: CBCharacteristic,
    ) {
        owner.handleGattNotifySubscribed(central, didSubscribeToCharacteristic)
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFromCharacteristic: CBCharacteristic,
    ) {
        owner.handleGattNotifyUnsubscribed(central, didUnsubscribeFromCharacteristic)
    }

    override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
        owner.pumpGattNotifyLinks()
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didReceiveWriteRequests: List<*>,
    ) {
        owner.handleGattWriteRequests(didReceiveWriteRequests)
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didPublishL2CAPChannel: UShort,
        error: NSError?,
    ) {
        owner.handlePublishedL2capChannel(didPublishL2CAPChannel, error)
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didOpenL2CAPChannel: CBL2CAPChannel?,
        error: NSError?,
    ) {
        owner.handleOpenedIncomingChannel(didOpenL2CAPChannel, error)
    }
}
