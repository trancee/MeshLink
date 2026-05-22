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
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.PendingFrameWindow
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.transport.resolveGattDataBearerMode
import ch.trancee.meshlink.transport.shouldLocalPeerInitiateL2capConnection
import ch.trancee.meshlink.transport.shouldUseMixedPlatformGattNotifyBearer
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
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
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
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

internal class IosBleTransport(private val appId: String, advertisementKeyHash: ByteArray) :
    BleTransport {
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 32)
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val localKeyHash: ByteArray = advertisementKeyHash.copyOf()
    private val telemetryEnabled: Boolean = readEnvironmentFlag(TRANSPORT_TELEMETRY_ENV)
    private val transportDebugLoggingEnabled: Boolean = readEnvironmentFlag(TRANSPORT_DEBUG_ENV)
    private val peerBindings = IosPeerBindings()
    private val peerRegistry = IosPeerRegistry(peerBindings)
    private val activeLinksByHint: MutableMap<String, IosL2capLink> = linkedMapOf()
    private val activeGattNotifyLinksByHint: MutableMap<String, IosGattNotifyLink> = linkedMapOf()
    private val pendingConnectionsByHint: MutableMap<String, String> = linkedMapOf()

    private var currentPowerProfile: IosPowerProfile = IosPowerMonitor.defaultProfile()
    private var currentDiscoveryPayload: BleDiscoveryPayload =
        discoveryPayload(l2capPsm = NO_ADVERTISED_L2CAP_PSM)
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
        val gattCallbacksInstalled = IosBleTransportBridgeRegistry.currentCallbacksOrNull() != null
        val peer = if (gattCallbacksInstalled) resolvePeer(peerId) else null
        val supportsGattNotifyBearer =
            peer != null &&
                shouldUseMixedPlatformGattNotifyBearer(
                    localPlatformFamily = currentDiscoveryPayload.platformFamily,
                    remotePlatformFamily = peer.platformFamily,
                )
        return if (supportsGattNotifyBearer) {
            IosGattNotifyLink.maximumPayloadBytesPerDelivery()
        } else {
            null
        }
    }

    override suspend fun clearQueuedOutboundFrames(peerId: PeerId): Unit {
        val peer = resolvePeer(peerId) ?: return
        val discardedL2capFrames = activeLinkFor(peer)?.discardQueuedFrames() ?: 0
        if (discardedL2capFrames > 0) {
            log(
                "discarded $discardedL2capFrames queued L2CAP frames for ${peer.hintPeerId.logSuffix()}"
            )
        }
        val discardedGattFrames = activeGattNotifyLinkFor(peer)?.discardQueuedFrames() ?: 0
        if (discardedGattFrames > 0) {
            log(
                "discarded $discardedGattFrames queued GATT notify frames for ${peer.hintPeerId.logSuffix()}"
            )
        }
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        return if (!started) {
            dropSend(
                frame,
                message = "iOS BLE transport is not started",
                detail = "transport not started",
            )
        } else {
            sendWhenStarted(frame)
        }
    }

    private suspend fun sendWhenStarted(frame: OutboundFrame): TransportSendResult {
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

    private suspend fun sendToPeer(
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
            directFrame is DirectWireFrame.Data &&
                dataBearerMode == GattDataBearerMode.GATT_REQUIRED ->
                dropSend(
                    frame,
                    message = "iOS BLE GATT notify side link is not ready",
                    detail = "required GATT notify side link not ready",
                )
            else -> sendViaL2capWhenReady(frame = frame, peer = peer)
        }
    }

    private fun resolveSendDataBearerMode(
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

    private suspend fun sendViaGattNotifyLinkOrNull(
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

    private suspend fun sendViaL2capWhenReady(
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

    private fun dropSendWhileWaitingForL2cap(
        frame: OutboundFrame,
        peer: DiscoveredPeer,
    ): TransportSendResult {
        if (shouldInitiateL2cap(peer.keyHash, peer.platformFamily)) {
            connectIfNeeded(peer)
            log("send(${frame.peerId.logSuffix()}) no active link, triggering connect")
        } else {
            log("send(${frame.peerId.logSuffix()}) waiting for inbound L2CAP link")
        }
        return TransportSendResult.Dropped("iOS BLE L2CAP connection is not ready")
    }

    private fun dropSend(
        frame: OutboundFrame,
        message: String,
        detail: String,
    ): TransportSendResult {
        log("send(${frame.peerId.logSuffix()}) dropped: $detail")
        return TransportSendResult.Dropped(message)
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

    internal fun handlePublishedL2capChannel(psm: UShort, error: NSError?): Unit {
        if (error != null) {
            reportLog("publish L2CAP failed: ${error.localizedDescription}")
            currentDiscoveryPayload = discoveryPayload(l2capPsm = NO_ADVERTISED_L2CAP_PSM)
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
        val payload =
            decodeDiscoveryPayloadOrNull(peripheral = peripheral, serviceUuids = serviceUuids)
                ?: return
        val hintPeerId = PeerId(payload.keyHash.toHexString())
        val transportMode =
            if (payload.l2capPsm.toInt() == NO_L2CAP_PSM) TransportMode.GATT
            else TransportMode.L2CAP
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

    private fun decodeDiscoveryPayloadOrNull(
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

    private fun upsertDiscoveredPeer(
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

    private fun handleL2capDiscovery(
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

    internal fun handleConnectedPeripheral(peripheral: CBPeripheral): Unit {
        val identifier = peripheral.identifier.UUIDString.lowercase()
        val hint = peerBindings.hintForIdentifier(identifier) ?: return
        val peer = peerRegistry.peer(hint) ?: return
        pendingConnectionsByHint[peer.hintPeerId.value] = identifier
        peripheral.delegate = peripheralClientDelegate
        peripheral.openL2CAPChannel(peer.l2capPsm.convert())
    }

    internal fun handleFailedConnection(peripheral: CBPeripheral, error: NSError?): Unit {
        val identifier = peripheral.identifier.UUIDString.lowercase()
        val hint = peerBindings.hintForIdentifier(identifier) ?: return
        pendingConnectionsByHint.remove(hint)
        peerRegistry.setRediscoveryLoggedWithoutLink(hint, logged = false)
        reportLog(
            "L2CAP connect failed for ${hint.logSuffix()}: ${error?.localizedDescription.orEmpty()}"
        )
    }

    internal fun handleDisconnectedPeripheral(peripheral: CBPeripheral): Unit {
        val identifier = peripheral.identifier.UUIDString.lowercase()
        val hint =
            peerBindings.hintForIdentifier(identifier)
                ?: peerBindings.temporaryHintForIdentifier(identifier)
                ?: return
        pendingConnectionsByHint.remove(hint)
        closeLink(hintPeer = hint, reason = "peripheral disconnected")
    }

    internal fun handleOpenedOutgoingChannel(
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

    internal fun handleOpenedIncomingChannel(channel: CBL2CAPChannel?, error: NSError?): Unit {
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

    internal fun handleGattWriteRequests(requests: List<*>): Unit {
        val typedRequests = requests.filterIsInstance<CBATTRequest>()
        typedRequests.firstOrNull()?.let { firstRequest ->
            processGattWriteRequests(typedRequests = typedRequests, firstRequest = firstRequest)
        }
    }

    internal fun pumpGattNotifyLinks(): Unit {
        activeGattNotifyLinksByHint.values.forEach { link -> link.pump() }
    }

    private fun ensureGattNotifyLink(
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

    private fun removeGattNotifyLink(identifier: String, hintPeerIdValue: String): Unit {
        activeGattNotifyLinksByHint.remove(hintPeerIdValue)?.close()
        reportLog("removed GATT notify side link for ${hintPeerIdValue.logSuffix()} id=$identifier")
        if (!activeLinksByHint.containsKey(hintPeerIdValue)) {
            peerRegistry.setPresenceAnnounced(hintPeerIdValue, announced = false)
            mutableEvents.tryEmit(TransportEvent.PeerLost(PeerId(hintPeerIdValue)))
        }
    }

    private fun processGattWriteRequests(
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

    private fun acceptsGattWriteRequest(
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

    private fun emitDecodedGattFrames(peerId: PeerId, decodedFrames: List<ByteArray>): Unit {
        coroutineScope.launch {
            decodedFrames.forEach { payload ->
                mutableEvents.emit(TransportEvent.FrameReceived(peerId = peerId, payload = payload))
            }
        }
    }

    private fun reuseOrCreateGattNotifyLink(
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
                                    ?.takeIf {
                                        activeGattNotifyLinksByHint[hintPeerId.value] === it
                                    }
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

    private fun resolveGattNotifyHintPeerIdValue(identifier: String): String? {
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

    private fun hasActiveGattNotifyLink(hintPeer: String): Boolean {
        return activeGattNotifyLinksByHint.containsKey(hintPeer)
    }

    private fun promoteTemporaryL2capLinkIfPossible(
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

    private fun registerConnectedChannel(
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

    private fun createConnectedChannelLink(
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
                    ),
            )
            .also { link -> createdLink = link }
    }

    private fun startConnectedChannelWriteLoop(link: IosL2capLink): Unit {
        link.writeLoopJob = coroutineScope.launch {
            try {
                link.runWriteLoop()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportLog(
                    "L2CAP write loop failed for ${link.hintPeerId.logSuffix()}: ${error.message.orEmpty()}"
                )
            } finally {
                closeLink(hintPeer = link.hintPeerId.value, reason = "write loop stopped")
            }
        }
    }

    private fun startConnectedChannelReadLoop(link: IosL2capLink): Unit {
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
                            TransportEvent.FrameReceived(
                                peerId = link.hintPeerId,
                                payload = payload,
                            )
                        )
                    }
                    if (drainedFrames.streamClosed) {
                        break
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportLog(
                    "L2CAP read loop failed for ${link.hintPeerId.logSuffix()}: ${error.message.orEmpty()}"
                )
            } finally {
                closeLink(hintPeer = link.hintPeerId.value, reason = "channel closed")
            }
        }
    }

    private fun connectIfNeeded(peer: DiscoveredPeer): Unit {
        if (peer.l2capPsm == NO_L2CAP_PSM) {
            log("connectIfNeeded(${peer.hintPeerId.logSuffix()}) skipped: no PSM")
            return
        }
        val isActiveOrPending =
            activeLinksByHint.containsKey(peer.hintPeerId.value) ||
                pendingConnectionsByHint.containsKey(peer.hintPeerId.value)
        if (isActiveOrPending) {
            log(
                "connectIfNeeded(${peer.hintPeerId.logSuffix()}) skipped: already active or pending"
            )
        } else {
            peerBindings.peripheralFor(peer.peripheralIdentifier)?.let { peripheral ->
                pendingConnectionsByHint[peer.hintPeerId.value] = peer.peripheralIdentifier
                centralManager?.connectPeripheral(peripheral, options = null)
            }
        }
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

    private fun activeLinkFor(peer: DiscoveredPeer): IosL2capLink? {
        val directLink = activeLinksByHint[peer.hintPeerId.value]
        val temporaryHint = peerBindings.temporaryHintForIdentifier(peer.peripheralIdentifier)
        return directLink ?: temporaryHint?.let(activeLinksByHint::get)
    }

    private fun activeGattNotifyLinkFor(peer: DiscoveredPeer): IosGattNotifyLink? {
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

    private fun resolvePeer(peerId: PeerId): DiscoveredPeer? {
        return peerRegistry.resolve(peerId)
    }

    private fun stopTransport(clearPeers: Boolean): Unit {
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

    private fun closeLink(hintPeer: String, reason: String): Unit {
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
            "requested low connection latency for ${hintPeerId.logSuffix()} " +
                "central=${central.identifier.UUIDString.lowercase()}"
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
        return if (psm.toInt() in ADVERTISED_PSM_RANGE) psm.toUByte() else NO_ADVERTISED_L2CAP_PSM
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

    private class IosL2capLinkDependencies(
        val incomingFrames: IosL2capFrameBuffer,
        val telemetryEnabled: Boolean,
        val telemetryLogger: (String) -> Unit,
        val promoteActiveWriteLatency: () -> Unit,
    )

    private class IosL2capLink(
        var hintPeerId: PeerId,
        val peripheralIdentifier: String,
        channel: CBL2CAPChannel,
        dependencies: IosL2capLinkDependencies,
    ) {
        private val incomingFrames: IosL2capFrameBuffer = dependencies.incomingFrames
        private val telemetryEnabled: Boolean = dependencies.telemetryEnabled
        private val telemetryLogger: (String) -> Unit = dependencies.telemetryLogger
        private val promoteActiveWriteLatency: () -> Unit = dependencies.promoteActiveWriteLatency
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
            emitReadTelemetry(
                decodedFrames = decodedFrames,
                readBytes = readBytes,
                readCalls = readCalls,
            )
            return ReadDrainResult(
                frames = decodedFrames,
                readBytes = readBytes,
                readCalls = readCalls,
                streamClosed = streamClosed,
            )
        }

        private fun emitReadTelemetry(
            decodedFrames: List<ByteArray>,
            readBytes: Int,
            readCalls: Int,
        ): Unit {
            if (!telemetryEnabled || decodedFrames.isEmpty()) {
                return
            }
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
                            "peer" to hintPeerId.logSuffix(),
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
                coalescedBuffer
                    .copyOf(minOf(coalescedBuffer.size, TELEMETRY_HEX_SNIPPET_BYTES))
                    .toHexString()
            val batchTailHex =
                coalescedBuffer
                    .copyOfRange(
                        maxOf(0, coalescedBuffer.size - TELEMETRY_HEX_SNIPPET_BYTES),
                        coalescedBuffer.size,
                    )
                    .toHexString()

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
                    check(
                        !isStreamClosed(
                            streamStatus = outputStream.streamStatus,
                            hasError = outputStream.streamError != null,
                        )
                    ) {
                        "iOS L2CAP output stream closed"
                    }
                    if (!outputStream.hasSpaceAvailable()) {
                        readyFalseCount += 1
                        backpressureSpins += 1
                        check(
                            !isWriteStalled(
                                lastProgressAtMs = lastWriteProgressAtMs,
                                nowMs = monotonicNowMillis(),
                                stallTimeoutMs = WRITE_STALL_TIMEOUT_MS,
                            )
                        ) {
                            "iOS L2CAP output stream stalled"
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
                    check(written >= 0) { "iOS L2CAP output stream closed" }
                    if (written == 0L) {
                        backpressureSpins += 1
                        check(
                            !isWriteStalled(
                                lastProgressAtMs = lastWriteProgressAtMs,
                                nowMs = monotonicNowMillis(),
                                stallTimeoutMs = WRITE_STALL_TIMEOUT_MS,
                            )
                        ) {
                            "iOS L2CAP output stream stalled"
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
                        "peer" to hintPeerId.logSuffix(),
                        "seqStart" to queuedFrames.first().sequence.toString(),
                        "seqEnd" to queuedFrames.last().sequence.toString(),
                        "coalescedFrames" to batchStats.coalescedFrames.toString(),
                        "coalescedBytes" to batchStats.coalescedBytes.toString(),
                        "streamByteStart" to batchStats.streamByteStart.toString(),
                        "streamByteEndExclusive" to batchStats.streamByteEndExclusive.toString(),
                        "frameHeaders" to
                            queuedFrames.joinToString(separator = ",") { queuedFrame ->
                                queuedFrame.encoded
                                    .copyOf(minOf(queuedFrame.encoded.size, FRAME_PREFIX_BYTES))
                                    .toHexString()
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
                            "peer" to hintPeerId.logSuffix(),
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
        private const val TRANSPORT_TELEMETRY_ENV: String = "MESHLINK_TRANSPORT_TELEMETRY"
        private const val TRANSPORT_DEBUG_ENV: String = "MESHLINK_TRANSPORT_DEBUG"

        private fun monotonicNowMillis(): Long {
            return (NSProcessInfo.processInfo.systemUptime * MILLIS_PER_SECOND).toLong()
        }

        private fun readEnvironmentFlag(name: String): Boolean {
            return getenv(name)?.toKString()?.lowercase()?.let { value ->
                value == ENV_VALUE_NUMERIC_TRUE ||
                    value == ENV_VALUE_BOOLEAN_TRUE ||
                    value == ENV_VALUE_YES
            } ?: false
        }
    }
}

private fun PeerId.logSuffix(): String {
    return value.takeLast(PEER_LOG_SUFFIX_CHARS)
}

private fun String.logSuffix(): String {
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
