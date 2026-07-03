@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.apple.BleTransportBridgeRegistry
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.TransportMode
import kotlinx.cinterop.convert
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError

internal fun BleTransportAdapter.startScanIfReady(central: CBCentralManager): Unit {
    if (!started || discoverySuspended || central.state != CBManagerStatePoweredOn) {
        log(
            "startScanIfReady skipped started=$started suspended=$discoverySuspended state=${central.state}"
        )
        return
    }
    reportLog("start scanning state=${central.state}")
    central.scanForPeripheralsWithServices(
        serviceUUIDs =
            listOf(CBUUID.UUIDWithString(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID)),
        options = null,
    )
}

internal fun BleTransportAdapter.publishL2capChannelIfReady(peripheral: CBPeripheralManager): Unit {
    if (!started || peripheral.state != CBManagerStatePoweredOn) {
        log("publishL2capChannelIfReady skipped started=$started state=${peripheral.state}")
        return
    }
    reportLog("publishing L2CAP channel state=${peripheral.state}")
    peripheral.publishL2CAPChannelWithEncryption(encryptionRequired = false)
}

internal fun BleTransportAdapter.installGattNotifyServiceIfReady(
    peripheral: CBPeripheralManager
): Unit {
    val bluetoothReady = peripheral.state == CBManagerStatePoweredOn
    val gattNotifyBearerEnabled = BleTransportBridgeRegistry.isGattNotifyBearerEnabled()
    val canInstallGattNotifyService =
        started && bluetoothReady && !gattNotifyServiceInstalled && gattNotifyBearerEnabled
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

internal fun BleTransportAdapter.handlePublishedL2capChannel(psm: UShort, error: NSError?): Unit {
    if (error != null) {
        reportLog("publish L2CAP failed: ${error.localizedDescription}")
        currentDiscoveryPayload = discoveryPayload(l2capPsm = NO_ADVERTISED_L2CAP_PSM)
    } else {
        currentDiscoveryPayload = discoveryPayload(l2capPsm = advertisedPsm(psm))
        log("published L2CAP channel psm=$psm advertised=${currentDiscoveryPayload.l2capPsm}")
    }
    startAdvertisingIfReady()
}

internal fun BleTransportAdapter.startAdvertisingIfReady(): Unit {
    val peripheral = peripheralManager ?: return
    if (!started || discoverySuspended || peripheral.state != CBManagerStatePoweredOn) {
        log(
            "startAdvertisingIfReady skipped started=$started suspended=$discoverySuspended state=${peripheral.state}"
        )
        return
    }
    reportLog(
        "start advertising payload=${currentDiscoveryPayload.payloadUuidString()} state=${peripheral.state}"
    )
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

internal fun BleTransportAdapter.handleDiscoveredPeripheral(
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

internal fun BleTransportAdapter.decodeDiscoveryPayloadOrNull(
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

internal fun BleTransportAdapter.upsertDiscoveredPeer(
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

internal fun BleTransportAdapter.handleL2capDiscovery(
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

internal fun BleTransportAdapter.handleConnectedPeripheral(peripheral: CBPeripheral): Unit {
    val identifier = peripheral.identifier.UUIDString.lowercase()
    val hint = peerBindings.hintForIdentifier(identifier) ?: return
    val peer = peerRegistry.peer(hint) ?: return
    pendingConnectionsByHint[peer.hintPeerId.value] = identifier
    peripheral.delegate = peripheralClientDelegate
    peripheral.openL2CAPChannel(peer.l2capPsm.convert())
}

internal fun BleTransportAdapter.handleFailedConnection(
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

internal fun BleTransportAdapter.handleDisconnectedPeripheral(peripheral: CBPeripheral): Unit {
    val identifier = peripheral.identifier.UUIDString.lowercase()
    val hint =
        peerBindings.hintForIdentifier(identifier)
            ?: peerBindings.temporaryHintForIdentifier(identifier)
            ?: return
    pendingConnectionsByHint.remove(hint)
    closeLink(hintPeer = hint, reason = "peripheral disconnected")
}

internal fun BleTransportAdapter.handleOpenedOutgoingChannel(
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

internal fun BleTransportAdapter.handleOpenedIncomingChannel(
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
