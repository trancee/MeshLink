@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.IosBleTransportBridgeRegistry
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.DirectWireFrame
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.GattDataBearerMode
import ch.trancee.meshlink.transport.shouldLocalPeerInitiateL2capConnection
import ch.trancee.meshlink.transport.shouldUseMixedPlatformGattNotifyBearer
import ch.trancee.meshlink.transport.resolveGattDataBearerMode
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.PendingFrameWindow
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTErrorUnlikelyError
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBManager
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

internal class IosBleTransport(private val appId: String, advertisementKeyHash: ByteArray) :
    BleTransport {
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 32)
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val localKeyHash: ByteArray = advertisementKeyHash.copyOf()
    private val localKeyHashHex: String = localKeyHash.toHexString()
    private val telemetryEnabled: Boolean = readEnvironmentFlag(TRANSPORT_TELEMETRY_ENV)
    private val transportDebugLoggingEnabled: Boolean = readEnvironmentFlag(TRANSPORT_DEBUG_ENV)
    private val discoveredPeers: MutableMap<String, DiscoveredPeer> = linkedMapOf()
    private val peerHintByIdentifier: MutableMap<String, String> = linkedMapOf()
    private val activeLinksByHint: MutableMap<String, IosL2capLink> = linkedMapOf()
    private val activeGattNotifyLinksByHint: MutableMap<String, IosGattNotifyLink> = linkedMapOf()
    private val pendingConnectionsByHint: MutableMap<String, String> = linkedMapOf()
    private val temporaryHintByIdentifier: MutableMap<String, String> = linkedMapOf()

    private var currentPowerProfile: IosPowerProfile = IosPowerMonitor.defaultProfile()
    private var currentDiscoveryPayload: BleDiscoveryPayload = discoveryPayload(l2capPsm = 0u)
    private var centralManager: CBCentralManager? = null
    private var peripheralManager: CBPeripheralManager? = null
    private var gattNotifyServiceInstalled: Boolean = false
    private var gattNotifyServiceCharacteristic: CBMutableCharacteristic? = null
    private var started: Boolean = false
    private var discoverySuspended: Boolean = false

    private val centralDelegate = IosCentralDelegate(this)
    private val peripheralClientDelegate = IosPeripheralClientDelegate(this)
    private val peripheralManagerDelegate = IosPeripheralManagerDelegate(this)

    override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

    override suspend fun start(): Unit {
        IosBlePermissionContract.ensureBluetoothAuthorized(CBManager.authorization)
        started = true
        centralManager = CBCentralManager(delegate = centralDelegate, queue = null)
        peripheralManager = CBPeripheralManager(delegate = peripheralManagerDelegate, queue = null)
    }

    override suspend fun pause(): Unit {
        stopTransport(clearPeers = false)
        started = false
    }

    override suspend fun resume(): Unit {
        if (!started) {
            start()
        }
    }

    override suspend fun stop(): Unit {
        stopTransport(clearPeers = true)
        started = false
    }

    override suspend fun updatePowerPolicy(policy: PowerPolicy): Unit {
        currentPowerProfile = IosPowerMonitor.profileFor(policy)
        currentDiscoveryPayload = discoveryPayload(currentDiscoveryPayload.l2capPsm)
        if (!started) {
            return
        }
        refreshDiscoveryState()
    }

    override suspend fun setDiscoverySuspended(suspended: Boolean): Unit {
        if (discoverySuspended == suspended) {
            return
        }
        discoverySuspended = suspended
        if (!started) {
            return
        }
        log("discovery suspended=$suspended")
        refreshDiscoveryState()
    }

    override fun maximumPayloadBytesPerDelivery(peerId: PeerId): Int? {
        if (IosBleTransportBridgeRegistry.currentCallbacksOrNull() == null) {
            return null
        }
        val peer = resolvePeer(peerId) ?: return null
        if (
            !shouldUseMixedPlatformGattNotifyBearer(
                localPlatformFamily = currentDiscoveryPayload.platformFamily,
                remotePlatformFamily = peer.platformFamily,
            )
        ) {
            return null
        }
        return IosGattNotifyLink.maximumPayloadBytesPerDelivery()
    }

    override suspend fun clearQueuedOutboundFrames(peerId: PeerId): Unit {
        val peer = resolvePeer(peerId) ?: return
        val discardedL2capFrames = activeLinkFor(peer)?.discardQueuedFrames() ?: 0
        if (discardedL2capFrames > 0) {
            log(
                "discarded $discardedL2capFrames queued L2CAP frames for ${peer.hintPeerId.value.takeLast(6)}"
            )
        }
        val discardedGattFrames = activeGattNotifyLinkFor(peer)?.discardQueuedFrames() ?: 0
        if (discardedGattFrames > 0) {
            log(
                "discarded $discardedGattFrames queued GATT notify frames for ${peer.hintPeerId.value.takeLast(6)}"
            )
        }
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        if (!started) {
            log("send(${frame.peerId.value.takeLast(6)}) dropped: transport not started")
            return TransportSendResult.Dropped("iOS BLE transport is not started")
        }

        val peer =
            resolvePeer(frame.peerId)
                ?: return TransportSendResult.Dropped("iOS BLE peer has not been discovered").also {
                    log("send(${frame.peerId.value.takeLast(6)}) dropped: peer not discovered")
                }
        if (peer.transportMode != TransportMode.L2CAP || peer.l2capPsm == 0) {
            log("send(${frame.peerId.value.takeLast(6)}) dropped: peer is GATT-only")
            return TransportSendResult.Dropped("iOS BLE GATT fallback transport is not implemented")
        }

        val directFrame = runCatching { DirectWireFrame.decode(frame.payload) }.getOrNull()
        val dataBearerMode =
            if (directFrame is DirectWireFrame.Data) {
                resolveGattDataBearerMode(
                    localPlatformFamily = currentDiscoveryPayload.platformFamily,
                    remotePlatformFamily = peer.platformFamily,
                    preferredMode = frame.preferredMode,
                )
            } else {
                GattDataBearerMode.L2CAP_ONLY
            }
        activeGattNotifyLinkFor(peer)
            ?.takeIf { directFrame is DirectWireFrame.Data }
            ?.let { gattNotifyLink ->
                return runCatching {
                        log(
                            "sending ${frame.payload.size} bytes via GATT notify side link for ${frame.peerId.value.takeLast(6)}"
                        )
                        if (!gattNotifyLink.enqueue(frame.payload)) {
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
        if (directFrame is DirectWireFrame.Data && dataBearerMode == GattDataBearerMode.GATT_REQUIRED) {
            log(
                "send(${frame.peerId.value.takeLast(6)}) dropped: required GATT notify side link not ready"
            )
            return TransportSendResult.Dropped("iOS BLE GATT notify side link is not ready")
        }

        val link = activeLinkFor(peer)
        if (link == null) {
            if (shouldInitiateL2cap(peer.keyHash, peer.platformFamily)) {
                connectIfNeeded(peer)
                log("send(${frame.peerId.value.takeLast(6)}) no active link, triggering connect")
            } else {
                log("send(${frame.peerId.value.takeLast(6)}) waiting for inbound L2CAP link")
            }
            return TransportSendResult.Dropped("iOS BLE L2CAP connection is not ready")
        }

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

    internal fun startScanIfReady(central: CBCentralManager): Unit {
        if (!started || discoverySuspended || central.state != CBManagerStatePoweredOn) {
            return
        }
        central.scanForPeripheralsWithServices(
            serviceUUIDs =
                listOf(CBUUID.UUIDWithString(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID)),
            options = null,
        )
    }

    internal fun publishL2capChannelIfReady(peripheral: CBPeripheralManager): Unit {
        if (!started || peripheral.state != CBManagerStatePoweredOn) {
            return
        }
        peripheral.publishL2CAPChannelWithEncryption(encryptionRequired = false)
    }

    internal fun installGattNotifyServiceIfReady(peripheral: CBPeripheralManager): Unit {
        if (
            !started ||
                peripheral.state != CBManagerStatePoweredOn ||
                gattNotifyServiceInstalled ||
                IosBleTransportBridgeRegistry.currentCallbacksOrNull() == null
        ) {
            return
        }
        val notifyCharacteristic =
            CBMutableCharacteristic(
                type = CBUUID.UUIDWithString(BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID),
                properties = CBCharacteristicPropertyNotify,
                value = null,
                permissions = 0u,
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

    internal fun handlePublishedL2capChannel(psm: UShort, error: NSError?): Unit {
        if (error != null) {
            reportLog("publish L2CAP failed: ${error.localizedDescription}")
            currentDiscoveryPayload = discoveryPayload(l2capPsm = 0u)
        } else {
            currentDiscoveryPayload = discoveryPayload(l2capPsm = advertisedPsm(psm))
            log("published L2CAP channel psm=$psm advertised=${currentDiscoveryPayload.l2capPsm}")
        }
        startAdvertisingIfReady()
    }

    internal fun startAdvertisingIfReady(): Unit {
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

    internal fun handleDiscoveredPeripheral(
        peripheral: CBPeripheral,
        serviceUuids: List<CBUUID>,
    ): Unit {
        val encodedUuids = serviceUuids.map { uuid -> uuid.UUIDString.lowercase() }
        val payloadUuid =
            encodedUuids.firstOrNull { uuid ->
                !BleDiscoveryContract.isAdvertisementServiceUuid(uuid)
            } ?: return
        val payload =
            runCatching { BleDiscoveryPayload.fromUuidString(payloadUuid) }.getOrNull() ?: return
        if (payload.meshHash != currentDiscoveryPayload.meshHash) {
            return
        }
        if (payload.keyHash.contentEquals(localKeyHash)) {
            return
        }
        if (!BleDiscoveryContract.isSupportedProtocolVersion(payload.protocolVersion)) {
            log(
                "ignoring discovery payload with unsupported protocolVersion=${payload.protocolVersion} id=${peripheral.identifier.UUIDString}"
            )
            return
        }

        val hintPeerId = PeerId(payload.keyHash.toHexString())
        val transportMode =
            if (payload.l2capPsm.toInt() == 0) TransportMode.GATT else TransportMode.L2CAP
        log(
            "scan found ${hintPeerId.value.takeLast(6)} mode=$transportMode psm=${payload.l2capPsm} platform=${payload.platformFamily} id=${peripheral.identifier.UUIDString}"
        )

        val identifier = peripheral.identifier.UUIDString.lowercase()
        val discoveredPeer = discoveredPeers[hintPeerId.value]
        if (discoveredPeer == null) {
            discoveredPeers[hintPeerId.value] =
                DiscoveredPeer(
                    hintPeerId = hintPeerId,
                    keyHash = payload.keyHash,
                    peripheral = peripheral,
                    l2capPsm = payload.l2capPsm.toInt(),
                    transportMode = transportMode,
                    platformFamily = payload.platformFamily,
                    presenceAnnounced = true,
                )
            peerHintByIdentifier[identifier] = hintPeerId.value
            mutableEvents.tryEmit(
                TransportEvent.PeerDiscovered(peerId = hintPeerId, transportMode = transportMode)
            )
        } else {
            discoveredPeer.peripheral = peripheral
            discoveredPeer.l2capPsm = payload.l2capPsm.toInt()
            discoveredPeer.platformFamily = payload.platformFamily
            peerHintByIdentifier[identifier] = hintPeerId.value
            if (discoveredPeer.transportMode != transportMode) {
                discoveredPeer.transportMode = transportMode
                mutableEvents.tryEmit(
                    TransportEvent.TransportModeChanged(
                        peerId = hintPeerId,
                        transportMode = transportMode,
                    )
                )
            }
            if (!discoveredPeer.presenceAnnounced) {
                discoveredPeer.presenceAnnounced = true
                mutableEvents.tryEmit(
                    TransportEvent.PeerDiscovered(peerId = hintPeerId, transportMode = transportMode)
                )
            }
        }

        maybeLogRediscoveryWithoutLink(
            peer = discoveredPeers.getValue(hintPeerId.value),
            transportMode = transportMode,
            identifier = identifier,
        )

        if (
            transportMode == TransportMode.L2CAP &&
                shouldInitiateL2cap(payload.keyHash, payload.platformFamily)
        ) {
            log("initiating L2CAP connect to ${hintPeerId.value.takeLast(6)}")
            connectIfNeeded(discoveredPeers.getValue(hintPeerId.value))
        }
    }

    internal fun handleConnectedPeripheral(peripheral: CBPeripheral): Unit {
        val identifier = peripheral.identifier.UUIDString.lowercase()
        val hint = peerHintByIdentifier[identifier] ?: return
        val peer = discoveredPeers[hint] ?: return
        pendingConnectionsByHint[peer.hintPeerId.value] = identifier
        peripheral.delegate = peripheralClientDelegate
        peripheral.openL2CAPChannel(peer.l2capPsm.convert())
    }

    internal fun handleFailedConnection(peripheral: CBPeripheral, error: NSError?): Unit {
        val identifier = peripheral.identifier.UUIDString.lowercase()
        val hint = peerHintByIdentifier[identifier] ?: return
        pendingConnectionsByHint.remove(hint)
        discoveredPeers[hint]?.rediscoveryLoggedWithoutLink = false
        reportLog(
            "L2CAP connect failed for ${hint.takeLast(6)}: ${error?.localizedDescription.orEmpty()}"
        )
    }

    internal fun handleDisconnectedPeripheral(peripheral: CBPeripheral): Unit {
        val identifier = peripheral.identifier.UUIDString.lowercase()
        val hint =
            peerHintByIdentifier[identifier] ?: temporaryHintByIdentifier[identifier] ?: return
        pendingConnectionsByHint.remove(hint)
        closeLink(hintPeer = hint, reason = "peripheral disconnected")
    }

    internal fun handleOpenedOutgoingChannel(
        peripheral: CBPeripheral,
        channel: CBL2CAPChannel?,
        error: NSError?,
    ): Unit {
        val identifier = peripheral.identifier.UUIDString.lowercase()
        val hint = peerHintByIdentifier[identifier] ?: return
        pendingConnectionsByHint.remove(hint)
        if (channel == null) {
            reportLog(
                "didOpenL2CAPChannel failed for ${hint.takeLast(6)}: ${error?.localizedDescription.orEmpty()}"
            )
            return
        }
        log("didOpenL2CAPChannel succeeded for ${hint.takeLast(6)}")
        discoveredPeers[hint]?.rediscoveryLoggedWithoutLink = false
        registerConnectedChannel(PeerId(hint), identifier, channel)
    }

    internal fun handleOpenedIncomingChannel(channel: CBL2CAPChannel?, error: NSError?): Unit {
        if (channel == null) {
            reportLog("incoming L2CAP channel failed: ${error?.localizedDescription.orEmpty()}")
            return
        }
        val identifier = channel.peer?.identifier?.UUIDString?.lowercase() ?: return
        val connectedCentral = channel.peer as? CBCentral
        val selectedHintPeerIdValue =
            selectIncomingL2capHintPeerId(
                peripheralIdentifier = identifier,
                peerHintByIdentifier = peerHintByIdentifier,
                discoveredPeers =
                    discoveredPeers.values.map { peer ->
                        IncomingL2capHintCandidate(
                            hintPeerIdValue = peer.hintPeerId.value,
                            keyHash = peer.keyHash,
                            platformFamily = peer.platformFamily,
                            transportMode = peer.transportMode,
                        )
                    },
                activeHintIds = activeLinksByHint.keys,
                pendingHintIds = pendingConnectionsByHint.keys,
                localKeyHash = localKeyHash,
                localPlatformFamily = currentDiscoveryPayload.platformFamily,
            )
        val hintPeerId = selectedHintPeerIdValue?.let(::PeerId) ?: temporaryPeerId(identifier)
        if (selectedHintPeerIdValue != null) {
            peerHintByIdentifier[identifier] = selectedHintPeerIdValue
            log(
                "binding incoming L2CAP channel to discovered peer ${hintPeerId.value.takeLast(6)} id=$identifier"
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

    internal fun handleGattNotifySubscribed(
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

    internal fun handleGattNotifyUnsubscribed(
        central: CBCentral,
        characteristic: CBCharacteristic,
    ): Unit {
        if (
            characteristic.UUID.UUIDString.lowercase() !=
                BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID
        ) {
            return
        }
        val identifier = central.identifier.UUIDString.lowercase()
        val hintPeerIdValue = peerHintByIdentifier[identifier] ?: return
        activeGattNotifyLinksByHint.remove(hintPeerIdValue)?.close()
        reportLog(
            "removed GATT notify side link for ${hintPeerIdValue.takeLast(6)} id=$identifier"
        )
        if (activeLinksByHint.containsKey(hintPeerIdValue)) {
            return
        }
        discoveredPeers[hintPeerIdValue]?.presenceAnnounced = false
        mutableEvents.tryEmit(TransportEvent.PeerLost(PeerId(hintPeerIdValue)))
    }

    internal fun handleGattWriteRequests(requests: List<*>): Unit {
        val typedRequests = requests.filterIsInstance<CBATTRequest>()
        val firstRequest = typedRequests.firstOrNull() ?: return
        val central = firstRequest.central
        val link = ensureGattNotifyLink(central = central, replaceExisting = false)
        if (link == null) {
            reportLog(
                "GATT write request rejected: no active side link for central=${central.identifier.UUIDString.lowercase()} requests=${typedRequests.size}"
            )
            peripheralManager?.respondToRequest(firstRequest, withResult = CBATTErrorUnlikelyError)
            return
        }
        val decodedFrames = mutableListOf<ByteArray>()
        val allRequestsAccepted =
            typedRequests.all { request ->
                request.characteristic.UUID.UUIDString.lowercase() ==
                    BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID &&
                    request.offset.toInt() == 0 &&
                    request.value?.let { value ->
                        decodedFrames += link.appendIncomingWrite(value.toByteArray())
                        true
                    } == true
            }
        reportLog(
            "GATT write request for ${link.hintPeerId.value.takeLast(6)} requests=${typedRequests.size} decodedFrames=${decodedFrames.size} accepted=$allRequestsAccepted"
        )
        peripheralManager?.respondToRequest(
            firstRequest,
            withResult = if (allRequestsAccepted) CBATTErrorSuccess else CBATTErrorUnlikelyError,
        )
        if (!allRequestsAccepted || decodedFrames.isEmpty()) {
            return
        }
        coroutineScope.launch {
            decodedFrames.forEach { payload ->
                mutableEvents.emit(
                    TransportEvent.FrameReceived(peerId = link.hintPeerId, payload = payload)
                )
            }
        }
    }

    internal fun pumpGattNotifyLinks(): Unit {
        activeGattNotifyLinksByHint.values.forEach { link -> link.pump() }
    }

    private fun ensureGattNotifyLink(
        central: CBCentral,
        replaceExisting: Boolean,
    ): IosGattNotifyLink? {
        if (IosBleTransportBridgeRegistry.currentCallbacksOrNull() == null) {
            return null
        }
        val identifier = central.identifier.UUIDString.lowercase()
        val hintPeerIdValue = resolveGattNotifyHintPeerIdValue(identifier) ?: return null
        peerHintByIdentifier[identifier] = hintPeerIdValue
        if (!replaceExisting) {
            activeGattNotifyLinksByHint[hintPeerIdValue]?.let { existingLink ->
                return existingLink
            }
        }
        val hintPeerId = PeerId(hintPeerIdValue)
        activeGattNotifyLinksByHint.remove(hintPeerId.value)?.close()
        var createdLink: IosGattNotifyLink? = null
        return IosGattNotifyLink(
                hintPeerId = hintPeerId,
                centralIdentifier = identifier,
                central = central,
                peripheralManagerProvider = { peripheralManager },
                notifyCharacteristicProvider = { gattNotifyServiceCharacteristic },
                logger = ::log,
                schedulePumpRetry = { delayMillis ->
                    coroutineScope.launch {
                        delay(delayMillis)
                        createdLink
                            ?.takeIf { activeGattNotifyLinksByHint[hintPeerId.value] === it }
                            ?.pumpOnMain()
                    }
                },
            )
            .also { link ->
                createdLink = link
                activeGattNotifyLinksByHint[hintPeerId.value] = link
                reportLog(
                    "registered GATT notify side link for ${hintPeerId.value.takeLast(6)} id=$identifier maxUpdateValueLength=${central.maximumUpdateValueLength}"
                )
            }
    }

    private fun resolveGattNotifyHintPeerIdValue(identifier: String): String? {
        return peerHintByIdentifier[identifier]
            ?: discoveredPeers.values.firstOrNull { peer ->
                shouldUseMixedPlatformGattNotifyBearer(
                    localPlatformFamily = currentDiscoveryPayload.platformFamily,
                    remotePlatformFamily = peer.platformFamily,
                )
            }?.hintPeerId?.value
    }

    private fun hasActiveGattNotifyLink(hintPeer: String): Boolean {
        return activeGattNotifyLinksByHint.containsKey(hintPeer)
    }

    private fun registerConnectedChannel(
        hintPeerId: PeerId,
        peripheralIdentifier: String,
        channel: CBL2CAPChannel,
        connectedCentral: CBCentral? = null,
    ): Unit {
        if (activeLinksByHint.containsKey(hintPeerId.value)) {
            log("ignoring duplicate L2CAP channel for ${hintPeerId.value.takeLast(6)}")
            return
        }
        val link =
            IosL2capLink(
                hintPeerId = hintPeerId,
                peripheralIdentifier = peripheralIdentifier,
                channel = channel,
                incomingFrames = IosL2capFrameBuffer(),
                telemetryEnabled = telemetryEnabled,
                telemetryLogger = ::emitTransportLog,
                promoteActiveWriteLatency = {
                    connectedCentral?.let { central ->
                        requestLowConnectionLatency(hintPeerId = hintPeerId, central = central)
                    }
                },
            )
        activeLinksByHint[hintPeerId.value] = link
        temporaryHintByIdentifier[peripheralIdentifier] = hintPeerId.value
        discoveredPeers[hintPeerId.value]?.rediscoveryLoggedWithoutLink = false
        log("registered L2CAP link for ${hintPeerId.value.takeLast(6)} id=$peripheralIdentifier")
        link.writeLoopJob = coroutineScope.launch {
            try {
                link.runWriteLoop()
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                reportLog(
                    "L2CAP write loop failed for ${hintPeerId.value.takeLast(6)}: ${error.message.orEmpty()}"
                )
            } finally {
                closeLink(hintPeer = hintPeerId.value, reason = "write loop stopped")
            }
        }
        link.readLoopJob = coroutineScope.launch {
            try {
                while (true) {
                    val drainedFrames = link.drainReadableFrames()
                    if (drainedFrames.frames.isEmpty()) {
                        if (drainedFrames.streamClosed) {
                            break
                        }
                        delay(
                            if (drainedFrames.readBytes > 0) {
                                ACTIVE_STREAM_POLL_INTERVAL_MS
                            } else {
                                IDLE_STREAM_POLL_INTERVAL_MS
                            }
                        )
                        continue
                    }
                    drainedFrames.frames.forEach { payload ->
                        mutableEvents.emit(
                            TransportEvent.FrameReceived(peerId = hintPeerId, payload = payload)
                        )
                    }
                    if (drainedFrames.streamClosed) {
                        break
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                reportLog(
                    "L2CAP read loop failed for ${hintPeerId.value.takeLast(6)}: ${error.message.orEmpty()}"
                )
            } finally {
                closeLink(hintPeer = hintPeerId.value, reason = "channel closed")
            }
        }
    }

    private fun connectIfNeeded(peer: DiscoveredPeer): Unit {
        if (peer.l2capPsm == 0) {
            log("connectIfNeeded(${peer.hintPeerId.value.takeLast(6)}) skipped: no PSM")
            return
        }
        if (
            activeLinksByHint.containsKey(peer.hintPeerId.value) ||
                pendingConnectionsByHint.containsKey(peer.hintPeerId.value)
        ) {
            log(
                "connectIfNeeded(${peer.hintPeerId.value.takeLast(6)}) skipped: already active or pending"
            )
            return
        }
        pendingConnectionsByHint[peer.hintPeerId.value] =
            peer.peripheral.identifier.UUIDString.lowercase()
        centralManager?.connectPeripheral(peer.peripheral, options = null)
    }

    private fun shouldInitiateL2cap(
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

    private fun maybeLogRediscoveryWithoutLink(
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
                (temporaryHintByIdentifier[identifier]?.let(activeLinksByHint::containsKey) == true)
        val hasPendingConnect = pendingConnectionsByHint.containsKey(peer.hintPeerId.value)
        if (hasActiveLink || hasPendingConnect) {
            peer.rediscoveryLoggedWithoutLink = false
            return
        }
        if (!peer.rediscoveryLoggedWithoutLink) {
            log(
                "scan rediscovered ${peer.hintPeerId.value.takeLast(6)} with no active link pendingConnect=$hasPendingConnect id=$identifier"
            )
            peer.rediscoveryLoggedWithoutLink = true
        }
    }

    private fun activeLinkFor(peer: DiscoveredPeer): IosL2capLink? {
        activeLinksByHint[peer.hintPeerId.value]?.let { activeLink ->
            return activeLink
        }
        val temporaryHint =
            temporaryHintByIdentifier[peer.peripheral.identifier.UUIDString.lowercase()]
                ?: return null
        return activeLinksByHint[temporaryHint]
    }

    private fun activeGattNotifyLinkFor(peer: DiscoveredPeer): IosGattNotifyLink? {
        if (
            !shouldUseMixedPlatformGattNotifyBearer(
                localPlatformFamily = currentDiscoveryPayload.platformFamily,
                remotePlatformFamily = peer.platformFamily,
            ) || IosBleTransportBridgeRegistry.currentCallbacksOrNull() == null
        ) {
            return null
        }
        activeGattNotifyLinksByHint[peer.hintPeerId.value]?.let { activeLink ->
            return activeLink
        }
        val temporaryHint =
            temporaryHintByIdentifier[peer.peripheral.identifier.UUIDString.lowercase()]
                ?: return null
        return activeGattNotifyLinksByHint[temporaryHint]
    }

    private fun resolvePeer(peerId: PeerId): DiscoveredPeer? {
        discoveredPeers[peerId.value]?.let {
            return it
        }
        return discoveredPeers.values.firstOrNull { discoveredPeer ->
            peerId.value.startsWith(discoveredPeer.hintPeerId.value)
        }
    }

    private fun temporaryPeerId(identifier: String): PeerId {
        val temporaryHint =
            temporaryHintByIdentifier.getOrPut(identifier) {
                TEMPORARY_PEER_PREFIX + identifier.replace("-", "")
            }
        return PeerId(temporaryHint)
    }

    private fun stopTransport(clearPeers: Boolean): Unit {
        discoverySuspended = false
        centralManager?.stopScan()
        peripheralManager?.stopAdvertising()
        val psm = currentDiscoveryPayload.l2capPsm.toInt()
        if (psm in 128..255) {
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
            discoveredPeers.clear()
            peerHintByIdentifier.clear()
            temporaryHintByIdentifier.clear()
        }
    }

    private fun closeLink(hintPeer: String, reason: String): Unit {
        val link = activeLinksByHint.remove(hintPeer) ?: return
        discoveredPeers[hintPeer]?.rediscoveryLoggedWithoutLink = false
        reportLog(
            "closing L2CAP link ${hintPeer.takeLast(6)}: $reason discoveredPeerRetained=${discoveredPeers.containsKey(hintPeer)} pendingConnect=${pendingConnectionsByHint.containsKey(hintPeer)}"
        )
        link.readLoopJob?.cancel()
        link.writeLoopJob?.cancel()
        link.close()
        if (hasActiveGattNotifyLink(hintPeer)) {
            reportLog(
                "retaining peer ${hintPeer.takeLast(6)} after L2CAP close because the GATT side link is still active"
            )
            return
        }
        discoveredPeers[hintPeer]?.presenceAnnounced = false
        mutableEvents.tryEmit(TransportEvent.PeerLost(PeerId(hintPeer)))
    }

    private fun refreshDiscoveryState(): Unit {
        centralManager?.stopScan()
        peripheralManager?.stopAdvertising()
        centralManager?.let(::startScanIfReady)
        startAdvertisingIfReady()
    }

    private fun requestLowConnectionLatency(hintPeerId: PeerId, central: CBCentral): Unit {
        peripheralManager?.setDesiredConnectionLatency(
            CBPeripheralManagerConnectionLatencyLow,
            forCentral = central,
        )
        reportLog(
            "requested low connection latency for ${hintPeerId.value.takeLast(6)} central=${central.identifier.UUIDString.lowercase()}"
        )
    }

    private fun discoveryPayload(l2capPsm: UByte): BleDiscoveryPayload {
        return BleDiscoveryPayload(
            protocolVersion = BleDiscoveryContract.CURRENT_PROTOCOL_VERSION,
            powerMode = currentPowerProfile.discoveryPowerMode,
            meshHash = BleDiscoveryContract.computeMeshHash(appId),
            l2capPsm = l2capPsm,
            keyHash = localKeyHash,
            platformFamily = BleDiscoveryPlatformFamily.IOS,
        )
    }

    private fun advertisedPsm(psm: UShort): UByte {
        return if (psm.toInt() in 128..255) psm.toUByte() else 0u
    }

    private fun log(message: String): Unit {
        if (transportDebugLoggingEnabled) {
            emitTransportLog(message)
        }
    }

    private fun reportLog(message: String): Unit {
        emitTransportLog(message)
    }

    private fun emitTransportLog(message: String): Unit {
        println("MeshLinkTransport $message")
    }

    private class DiscoveredPeer(
        val hintPeerId: PeerId,
        keyHash: ByteArray,
        var peripheral: CBPeripheral,
        var l2capPsm: Int,
        var transportMode: TransportMode,
        var platformFamily: BleDiscoveryPlatformFamily,
        var rediscoveryLoggedWithoutLink: Boolean = false,
        var presenceAnnounced: Boolean = false,
    ) {
        val keyHash: ByteArray = keyHash.copyOf()
    }

    private class IosL2capLink(
        val hintPeerId: PeerId,
        val peripheralIdentifier: String,
        channel: CBL2CAPChannel,
        private val incomingFrames: IosL2capFrameBuffer,
        private val telemetryEnabled: Boolean,
        private val telemetryLogger: (String) -> Unit,
        private val promoteActiveWriteLatency: () -> Unit,
    ) {
        private val inputStream = checkNotNull(channel.inputStream).apply { open() }
        private val outputStream = checkNotNull(channel.outputStream).apply { open() }
        private val readBuffer = ByteArray(STREAM_BUFFER_BYTES)
        private val enqueueMutex = Mutex()
        private val pendingFrameWindow =
            PendingFrameWindow(
                maxPendingFrames = PENDING_FRAME_WINDOW_FRAMES,
                maxPendingBytes = PENDING_FRAME_WINDOW_BYTES,
            )
        private val outboundFrames = Channel<QueuedFrame>(capacity = Channel.UNLIMITED)
        private var readSequence: Long = 0L
        private var writeSequence: Long = 0L
        private var lastReadFrameAtMs: Long? = null
        private var cumulativeEncodedBytesWritten: Long = 0L
        private var activeWriteLatencyPromoted: Boolean = false
        var readLoopJob: Job? = null
        var writeLoopJob: Job? = null

        suspend fun drainReadableFrames(): ReadDrainResult {
            if (!inputStream.hasBytesAvailable()) {
                return ReadDrainResult(streamClosed = isStreamClosed(inputStream))
            }
            val decodedFrames = mutableListOf<ByteArray>()
            var readBytes = 0
            var readCalls = 0
            var streamClosed = false
            while (inputStream.hasBytesAvailable()) {
                val bytesRead = readBuffer.usePinned { pinned ->
                    inputStream.read(pinned.addressOf(0).reinterpret(), readBuffer.size.convert())
                }
                if (bytesRead < 0) {
                    streamClosed = true
                    break
                }
                if (bytesRead == 0L) {
                    break
                }
                val readCount = bytesRead.toInt()
                readBytes += readCount
                readCalls += 1
                decodedFrames += incomingFrames.append(readBuffer.copyOf(readCount))
                if (readCount < readBuffer.size && !inputStream.hasBytesAvailable()) {
                    break
                }
            }
            if (telemetryEnabled && decodedFrames.isNotEmpty()) {
                decodedFrames.forEachIndexed { frameIndex, payload ->
                    val classification = classifyFrame(payload)
                    val nowMs = monotonicNowMillis()
                    val interarrivalMs =
                        lastReadFrameAtMs?.let { previousAtMs -> nowMs - previousAtMs } ?: -1L
                    lastReadFrameAtMs = nowMs
                    emitTelemetry(
                        event = "read.frame",
                        fields =
                            mapOf(
                                "seq" to (++readSequence).toString(),
                                "peer" to hintPeerId.value.takeLast(6),
                                "batchBytes" to readBytes.toString(),
                                "batchFrames" to decodedFrames.size.toString(),
                                "readCalls" to readCalls.toString(),
                                "frameIndex" to (frameIndex + 1).toString(),
                                "streamReady" to inputStream.hasBytesAvailable().toString(),
                                "directType" to classification.directType,
                                "dataClass" to classification.dataClass,
                                "frameBytes" to payload.size.toString(),
                                "innerBytes" to classification.innerBytes.toString(),
                                "interarrivalMs" to interarrivalMs.toString(),
                            ),
                    )
                }
            }
            return ReadDrainResult(
                frames = decodedFrames,
                readBytes = readBytes,
                readCalls = readCalls,
                streamClosed = streamClosed,
            )
        }

        suspend fun enqueue(payload: ByteArray): Boolean {
            val classification = classifyFrame(payload)
            val encoded = incomingFrames.encode(payload)
            if (!pendingFrameWindow.acquire(encoded.size)) {
                return false
            }
            val queuedFrame =
                QueuedFrame(
                    sequence = nextWriteSequence(),
                    payloadSize = payload.size,
                    encoded = encoded,
                    classification = classification,
                    enqueuedAtMs = monotonicNowMillis(),
                )
            val queued = outboundFrames.trySend(queuedFrame).isSuccess
            if (!queued) {
                pendingFrameWindow.release(encoded.size)
            }
            return queued
        }

        suspend fun runWriteLoop(): Unit {
            try {
                while (true) {
                    val firstQueuedFrame = outboundFrames.receiveCatching().getOrNull() ?: break
                    val queuedFrames = mutableListOf(firstQueuedFrame)
                    var coalescedBytes = firstQueuedFrame.encoded.size
                    while (queuedFrames.size < MAX_COALESCED_FRAMES) {
                        val nextQueuedFrame = outboundFrames.tryReceive().getOrNull() ?: break
                        queuedFrames += nextQueuedFrame
                        coalescedBytes += nextQueuedFrame.encoded.size
                        if (coalescedBytes >= MAX_COALESCED_BATCH_BYTES) {
                            break
                        }
                    }
                    try {
                        val batchStats = writeCoalescedBatch(queuedFrames)
                        if (telemetryEnabled) {
                            emitBatchTelemetry(queuedFrames, batchStats)
                            emitQueuedFrameTelemetry(queuedFrames, batchStats)
                        }
                    } finally {
                        queuedFrames.forEach { queuedFrame ->
                            pendingFrameWindow.release(queuedFrame.encoded.size)
                        }
                    }
                }
            } finally {
                pendingFrameWindow.close()
                while (true) {
                    val queuedFrame = outboundFrames.tryReceive().getOrNull() ?: break
                    pendingFrameWindow.release(queuedFrame.encoded.size)
                }
            }
        }

        fun close(): Unit {
            outboundFrames.close()
            pendingFrameWindow.close()
            inputStream.close()
            outputStream.close()
        }

        suspend fun discardQueuedFrames(): Int {
            var discardedFrames = 0
            while (true) {
                val queuedFrame = outboundFrames.tryReceive().getOrNull() ?: break
                pendingFrameWindow.release(queuedFrame.encoded.size)
                discardedFrames += 1
            }
            return discardedFrames
        }

        private suspend fun nextWriteSequence(): Long {
            return enqueueMutex.withLock { ++writeSequence }
        }

        private suspend fun writeCoalescedBatch(queuedFrames: List<QueuedFrame>): BatchWriteStats {
            if (!activeWriteLatencyPromoted) {
                promoteActiveWriteLatency()
                activeWriteLatencyPromoted = true
            }
            val startedAtMs = monotonicNowMillis()
            var lastWriteProgressAtMs = startedAtMs
            val coalescedBuffer =
                ByteArray(queuedFrames.sumOf { queuedFrame -> queuedFrame.encoded.size })
            var copyOffset = 0
            queuedFrames.forEach { queuedFrame ->
                queuedFrame.encoded.copyInto(coalescedBuffer, destinationOffset = copyOffset)
                copyOffset += queuedFrame.encoded.size
            }
            val batchHeadHex =
                coalescedBuffer.copyOf(minOf(coalescedBuffer.size, TELEMETRY_HEX_SNIPPET_BYTES))
                    .toHexString()
            val batchTailHex =
                coalescedBuffer
                    .copyOfRange(
                        maxOf(0, coalescedBuffer.size - TELEMETRY_HEX_SNIPPET_BYTES),
                        coalescedBuffer.size,
                    ).toHexString()

            var offset = 0
            var writeCalls = 0
            var writeBatches = 0
            var backpressureSpins = 0
            var readyFalseCount = 0
            var maxWriteChunkBytes = 0
            var minWriteChunkBytes = Int.MAX_VALUE
            var maxWriteBatchBytes = 0
            var minWriteBatchBytes = Int.MAX_VALUE
            var previousPositiveWriteAtMs: Long? = null
            var maxInterWriteGapMs = 0L
            while (offset < coalescedBuffer.size) {
                val batchBytes = minOf(WRITE_BATCH_BYTES, coalescedBuffer.size - offset)
                writeBatches += 1
                maxWriteBatchBytes = maxOf(maxWriteBatchBytes, batchBytes)
                minWriteBatchBytes = minOf(minWriteBatchBytes, batchBytes)
                var batchOffset = 0
                while (batchOffset < batchBytes) {
                    if (
                        isStreamClosed(
                            streamStatus = outputStream.streamStatus,
                            hasError = outputStream.streamError != null,
                        )
                    ) {
                        throw IllegalStateException("iOS L2CAP output stream closed")
                    }
                    if (!outputStream.hasSpaceAvailable()) {
                        readyFalseCount += 1
                        backpressureSpins += 1
                        if (
                            isWriteStalled(
                                lastProgressAtMs = lastWriteProgressAtMs,
                                nowMs = monotonicNowMillis(),
                                stallTimeoutMs = WRITE_STALL_TIMEOUT_MS,
                            )
                        ) {
                            throw IllegalStateException("iOS L2CAP output stream stalled")
                        }
                        delay(ACTIVE_STREAM_POLL_INTERVAL_MS)
                        continue
                    }
                    val attemptAtMs = monotonicNowMillis()
                    val written = coalescedBuffer.usePinned { pinned ->
                        outputStream.write(
                            pinned.addressOf(offset + batchOffset).reinterpret(),
                            (batchBytes - batchOffset).convert(),
                        )
                    }
                    writeCalls += 1
                    if (written < 0) {
                        throw IllegalStateException("iOS L2CAP output stream closed")
                    }
                    if (written == 0L) {
                        backpressureSpins += 1
                        if (
                            isWriteStalled(
                                lastProgressAtMs = lastWriteProgressAtMs,
                                nowMs = monotonicNowMillis(),
                                stallTimeoutMs = WRITE_STALL_TIMEOUT_MS,
                            )
                        ) {
                            throw IllegalStateException("iOS L2CAP output stream stalled")
                        }
                        delay(ACTIVE_STREAM_POLL_INTERVAL_MS)
                        continue
                    }
                    val writtenBytes = written.toInt()
                    maxWriteChunkBytes = maxOf(maxWriteChunkBytes, writtenBytes)
                    minWriteChunkBytes = minOf(minWriteChunkBytes, writtenBytes)
                    previousPositiveWriteAtMs?.let { previousAtMs ->
                        maxInterWriteGapMs = maxOf(maxInterWriteGapMs, attemptAtMs - previousAtMs)
                    }
                    previousPositiveWriteAtMs = attemptAtMs
                    lastWriteProgressAtMs = attemptAtMs
                    batchOffset += writtenBytes
                }
                offset += batchBytes
            }
            val streamByteStart = cumulativeEncodedBytesWritten
            cumulativeEncodedBytesWritten += coalescedBuffer.size.toLong()
            return BatchWriteStats(
                writeCalls = writeCalls,
                writeBatches = writeBatches,
                backpressureSpins = backpressureSpins,
                readyFalseCount = readyFalseCount,
                minWriteChunkBytes =
                    if (minWriteChunkBytes == Int.MAX_VALUE) 0 else minWriteChunkBytes,
                maxWriteChunkBytes = maxWriteChunkBytes,
                minWriteBatchBytes =
                    if (minWriteBatchBytes == Int.MAX_VALUE) 0 else minWriteBatchBytes,
                maxWriteBatchBytes = maxWriteBatchBytes,
                maxInterWriteGapMs = maxInterWriteGapMs,
                totalElapsedMs = monotonicNowMillis() - startedAtMs,
                coalescedFrames = queuedFrames.size,
                coalescedBytes = coalescedBuffer.size,
                batchStartedAtMs = startedAtMs,
                streamByteStart = streamByteStart,
                streamByteEndExclusive = cumulativeEncodedBytesWritten,
                batchHeadHex = batchHeadHex,
                batchTailHex = batchTailHex,
            )
        }

        private fun emitBatchTelemetry(
            queuedFrames: List<QueuedFrame>,
            batchStats: BatchWriteStats,
        ): Unit {
            emitTelemetry(
                event = "write.batch",
                fields =
                    mapOf(
                        "peer" to hintPeerId.value.takeLast(6),
                        "seqStart" to queuedFrames.first().sequence.toString(),
                        "seqEnd" to queuedFrames.last().sequence.toString(),
                        "coalescedFrames" to batchStats.coalescedFrames.toString(),
                        "coalescedBytes" to batchStats.coalescedBytes.toString(),
                        "streamByteStart" to batchStats.streamByteStart.toString(),
                        "streamByteEndExclusive" to batchStats.streamByteEndExclusive.toString(),
                        "frameHeaders" to
                            queuedFrames.joinToString(separator = ",") { queuedFrame ->
                                queuedFrame
                                    .encoded
                                    .copyOf(
                                        minOf(queuedFrame.encoded.size, FRAME_PREFIX_BYTES)
                                    ).toHexString()
                            },
                        "batchHeadHex" to batchStats.batchHeadHex,
                        "batchTailHex" to batchStats.batchTailHex,
                        "writeCalls" to batchStats.writeCalls.toString(),
                        "writeBatches" to batchStats.writeBatches.toString(),
                        "backpressureSpins" to batchStats.backpressureSpins.toString(),
                        "readyFalseCount" to batchStats.readyFalseCount.toString(),
                    ),
            )
        }

        private fun emitQueuedFrameTelemetry(
            queuedFrames: List<QueuedFrame>,
            batchStats: BatchWriteStats,
        ): Unit {
            queuedFrames.forEachIndexed { frameIndex, queuedFrame ->
                emitTelemetry(
                    event = "write.frame",
                    fields =
                        mapOf(
                            "seq" to queuedFrame.sequence.toString(),
                            "peer" to hintPeerId.value.takeLast(6),
                            "directType" to queuedFrame.classification.directType,
                            "dataClass" to queuedFrame.classification.dataClass,
                            "frameBytes" to queuedFrame.payloadSize.toString(),
                            "innerBytes" to queuedFrame.classification.innerBytes.toString(),
                            "encodedBytes" to queuedFrame.encoded.size.toString(),
                            "frameIndex" to (frameIndex + 1).toString(),
                            "coalescedFrames" to batchStats.coalescedFrames.toString(),
                            "coalescedBytes" to batchStats.coalescedBytes.toString(),
                            "queueDelayMs" to
                                (batchStats.batchStartedAtMs - queuedFrame.enqueuedAtMs).toString(),
                            "writeCalls" to batchStats.writeCalls.toString(),
                            "writeBatches" to batchStats.writeBatches.toString(),
                            "backpressureSpins" to batchStats.backpressureSpins.toString(),
                            "readyFalseCount" to batchStats.readyFalseCount.toString(),
                            "minWriteChunkBytes" to batchStats.minWriteChunkBytes.toString(),
                            "maxWriteChunkBytes" to batchStats.maxWriteChunkBytes.toString(),
                            "minWriteBatchBytes" to batchStats.minWriteBatchBytes.toString(),
                            "maxWriteBatchBytes" to batchStats.maxWriteBatchBytes.toString(),
                            "maxInterWriteGapMs" to batchStats.maxInterWriteGapMs.toString(),
                            "totalElapsedMs" to batchStats.totalElapsedMs.toString(),
                        ),
                )
            }
        }

        private fun emitTelemetry(event: String, fields: Map<String, String>): Unit {
            val body =
                fields.entries.joinToString(separator = " ") { entry ->
                    "${entry.key}=${entry.value}"
                }
            telemetryLogger("MeshLinkTransportTelemetry event=$event $body")
        }

        private fun classifyFrame(payload: ByteArray): FrameTelemetry {
            return runCatching { DirectWireFrame.decode(payload) }
                .getOrNull()
                ?.let { frame ->
                    when (frame) {
                        is DirectWireFrame.HandshakeMessage1 ->
                            FrameTelemetry(
                                directType = "HANDSHAKE_MESSAGE_1",
                                dataClass = "handshake",
                                innerBytes = frame.payload.size,
                            )
                        is DirectWireFrame.HandshakeMessage2 ->
                            FrameTelemetry(
                                directType = "HANDSHAKE_MESSAGE_2",
                                dataClass = "handshake",
                                innerBytes = frame.payload.size,
                            )
                        is DirectWireFrame.HandshakeMessage3 ->
                            FrameTelemetry(
                                directType = "HANDSHAKE_MESSAGE_3",
                                dataClass = "handshake",
                                innerBytes = frame.payload.size,
                            )
                        is DirectWireFrame.Data ->
                            FrameTelemetry(
                                directType = "DATA",
                                dataClass =
                                    if (frame.payload.size <= ACK_LIKELY_ENCRYPTED_BYTES) {
                                        "ackLikely"
                                    } else {
                                        "bulkLikely"
                                    },
                                innerBytes = frame.payload.size,
                            )
                    }
                }
                ?: FrameTelemetry(
                    directType = "UNKNOWN",
                    dataClass = "unknown",
                    innerBytes = payload.size,
                )
        }

        private data class QueuedFrame(
            val sequence: Long,
            val payloadSize: Int,
            val encoded: ByteArray,
            val classification: FrameTelemetry,
            val enqueuedAtMs: Long,
        )

        private data class FrameTelemetry(
            val directType: String,
            val dataClass: String,
            val innerBytes: Int,
        )

        private data class BatchWriteStats(
            val writeCalls: Int,
            val writeBatches: Int,
            val backpressureSpins: Int,
            val readyFalseCount: Int,
            val minWriteChunkBytes: Int,
            val maxWriteChunkBytes: Int,
            val minWriteBatchBytes: Int,
            val maxWriteBatchBytes: Int,
            val maxInterWriteGapMs: Long,
            val totalElapsedMs: Long,
            val coalescedFrames: Int,
            val coalescedBytes: Int,
            val batchStartedAtMs: Long,
            val streamByteStart: Long,
            val streamByteEndExclusive: Long,
            val batchHeadHex: String,
            val batchTailHex: String,
        )

        data class ReadDrainResult(
            val frames: List<ByteArray> = emptyList(),
            val readBytes: Int = 0,
            val readCalls: Int = 0,
            val streamClosed: Boolean = false,
        )

        private companion object {
            private const val STREAM_BUFFER_BYTES: Int = 16 * 1024
            private const val WRITE_BATCH_BYTES: Int = 4 * 1024
            private const val PENDING_FRAME_WINDOW_FRAMES: Int = 16
            private const val PENDING_FRAME_WINDOW_BYTES: Int = 16 * 1024
            private const val MAX_COALESCED_FRAMES: Int = 16
            private const val MAX_COALESCED_BATCH_BYTES: Int = 16 * 1024
            private const val ACK_LIKELY_ENCRYPTED_BYTES: Int = 192
            private const val WRITE_STALL_TIMEOUT_MS: Long = 10_000L
            private const val FRAME_PREFIX_BYTES: Int = 4
            private const val TELEMETRY_HEX_SNIPPET_BYTES: Int = 16
        }
    }

    private companion object {
        private const val IDLE_STREAM_POLL_INTERVAL_MS: Long = 5
        private const val ACTIVE_STREAM_POLL_INTERVAL_MS: Long = 1
        private const val GATT_NOTIFY_PUMP_RETRY_POLL_INTERVAL_MS: Long = 2
        private const val TEMPORARY_PEER_PREFIX: String = "cb-"
        private const val TRANSPORT_TELEMETRY_ENV: String = "MESHLINK_TRANSPORT_TELEMETRY"
        private const val TRANSPORT_DEBUG_ENV: String = "MESHLINK_TRANSPORT_DEBUG"

        private fun monotonicNowMillis(): Long {
            return (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()
        }

        private fun readEnvironmentFlag(name: String): Boolean {
            return getenv(name)?.toKString()?.lowercase()?.let { value ->
                value == "1" || value == "true" || value == "yes"
            } ?: false
        }
    }
}

internal data class IncomingL2capHintCandidate(
    internal val hintPeerIdValue: String,
    internal val keyHash: ByteArray,
    internal val platformFamily: BleDiscoveryPlatformFamily,
    internal val transportMode: TransportMode,
)

internal fun isStreamClosed(streamStatus: ULong, hasError: Boolean): Boolean {
    return hasError || streamStatus == NSStreamStatusAtEnd ||
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
    if (lengthInt == 0) {
        return ByteArray(0)
    }
    return ByteArray(lengthInt).also { output ->
        output.usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length)
        }
    }
}

internal fun selectIncomingL2capHintPeerId(
    peripheralIdentifier: String,
    peerHintByIdentifier: Map<String, String>,
    discoveredPeers: List<IncomingL2capHintCandidate>,
    activeHintIds: Collection<String>,
    pendingHintIds: Collection<String>,
    localKeyHash: ByteArray,
    localPlatformFamily: BleDiscoveryPlatformFamily,
): String? {
    peerHintByIdentifier[peripheralIdentifier]?.let { mappedHint ->
        return mappedHint
    }
    val waitingCandidates =
        discoveredPeers.filter { candidate ->
            candidate.transportMode == TransportMode.L2CAP &&
                candidate.hintPeerIdValue !in activeHintIds &&
                candidate.hintPeerIdValue !in pendingHintIds &&
                !shouldLocalPeerInitiateL2capConnection(
                    localKeyHash = localKeyHash,
                    localPlatformFamily = localPlatformFamily,
                    remoteKeyHash = candidate.keyHash,
                    remotePlatformFamily = candidate.platformFamily,
                )
        }
    return waitingCandidates.singleOrNull()?.hintPeerIdValue
}

private class IosCentralDelegate(private val owner: IosBleTransport) :
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

private class IosPeripheralClientDelegate(private val owner: IosBleTransport) :
    NSObject(), CBPeripheralDelegateProtocol {
    override fun peripheral(
        peripheral: CBPeripheral,
        didOpenL2CAPChannel: CBL2CAPChannel?,
        error: NSError?,
    ) {
        owner.handleOpenedOutgoingChannel(peripheral, didOpenL2CAPChannel, error)
    }
}

private class IosPeripheralManagerDelegate(private val owner: IosBleTransport) :
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
