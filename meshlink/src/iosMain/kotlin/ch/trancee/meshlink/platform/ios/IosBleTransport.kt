@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject

internal class IosBleTransport(private val appId: String, advertisementKeyHash: ByteArray) :
    BleTransport {
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 32)
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sendMutex = Mutex()
    private val localKeyHash: ByteArray = advertisementKeyHash.copyOf()
    private val localKeyHashHex: String = localKeyHash.toHexString()
    private val discoveredPeers: MutableMap<String, DiscoveredPeer> = linkedMapOf()
    private val peerHintByIdentifier: MutableMap<String, String> = linkedMapOf()
    private val activeLinksByHint: MutableMap<String, IosL2capLink> = linkedMapOf()
    private val pendingConnectionsByHint: MutableMap<String, String> = linkedMapOf()
    private val temporaryHintByIdentifier: MutableMap<String, String> = linkedMapOf()

    private var currentPowerProfile: IosPowerProfile = IosPowerMonitor.defaultProfile()
    private var currentDiscoveryPayload: BleDiscoveryPayload = discoveryPayload(l2capPsm = 0u)
    private var centralManager: CBCentralManager? = null
    private var peripheralManager: CBPeripheralManager? = null
    private var started: Boolean = false
    private var discoverySuspended: Boolean = false

    private val centralDelegate = IosCentralDelegate(this)
    private val peripheralClientDelegate = IosPeripheralClientDelegate(this)
    private val peripheralManagerDelegate = IosPeripheralManagerDelegate(this)

    override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

    override suspend fun start(): Unit {
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
        val peer = resolvePeer(peerId) ?: return null
        return if (activeLinkFor(peer) != null) {
            LARGE_DELIVERY_PAYLOAD_BUDGET_BYTES
        } else {
            null
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

        val link = activeLinkFor(peer)
        if (link == null) {
            connectIfNeeded(peer)
            log("send(${frame.peerId.value.takeLast(6)}) no active link, triggering connect")
            return TransportSendResult.Dropped("iOS BLE L2CAP connection is not ready")
        }

        return runCatching {
                sendMutex.withLock { link.write(frame.payload) }
                log("send(${frame.peerId.value.takeLast(6)}) delivered ${frame.payload.size} bytes")
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

    internal fun handlePublishedL2capChannel(psm: UShort, error: NSError?): Unit {
        if (error != null) {
            log("publish L2CAP failed: ${error.localizedDescription}")
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

        val hintPeerId = PeerId(payload.keyHash.toHexString())
        val transportMode =
            if (payload.l2capPsm.toInt() == 0) TransportMode.GATT else TransportMode.L2CAP
        log(
            "scan found ${hintPeerId.value.takeLast(6)} mode=$transportMode psm=${payload.l2capPsm} id=${peripheral.identifier.UUIDString}"
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
                )
            peerHintByIdentifier[identifier] = hintPeerId.value
            mutableEvents.tryEmit(
                TransportEvent.PeerDiscovered(peerId = hintPeerId, transportMode = transportMode)
            )
        } else {
            discoveredPeer.peripheral = peripheral
            discoveredPeer.l2capPsm = payload.l2capPsm.toInt()
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
        }

        if (transportMode == TransportMode.L2CAP && shouldInitiateL2cap(payload.keyHash)) {
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
        log(
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
            log(
                "didOpenL2CAPChannel failed for ${hint.takeLast(6)}: ${error?.localizedDescription.orEmpty()}"
            )
            return
        }
        log("didOpenL2CAPChannel succeeded for ${hint.takeLast(6)}")
        registerConnectedChannel(PeerId(hint), identifier, channel)
    }

    internal fun handleOpenedIncomingChannel(channel: CBL2CAPChannel?, error: NSError?): Unit {
        if (channel == null) {
            log("incoming L2CAP channel failed: ${error?.localizedDescription.orEmpty()}")
            return
        }
        val identifier = channel.peer?.identifier?.UUIDString?.lowercase() ?: return
        val hintPeerId =
            peerHintByIdentifier[identifier]?.let(::PeerId) ?: temporaryPeerId(identifier)
        if (hintPeerId.value.startsWith(TEMPORARY_PEER_PREFIX)) {
            log("binding incoming L2CAP channel to temporary peer ${hintPeerId.value}")
        }
        registerConnectedChannel(hintPeerId, identifier, channel)
    }

    private fun registerConnectedChannel(
        hintPeerId: PeerId,
        peripheralIdentifier: String,
        channel: CBL2CAPChannel,
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
            )
        activeLinksByHint[hintPeerId.value] = link
        temporaryHintByIdentifier[peripheralIdentifier] = hintPeerId.value
        log("registered L2CAP link for ${hintPeerId.value.takeLast(6)} id=$peripheralIdentifier")
        link.readLoopJob = coroutineScope.launch {
            try {
                while (true) {
                    val decodedFrames = link.readFrames()
                    if (decodedFrames.isEmpty()) {
                        delay(STREAM_POLL_INTERVAL_MS)
                        continue
                    }
                    decodedFrames.forEach { payload ->
                        log("received ${payload.size} bytes from ${hintPeerId.value.takeLast(6)}")
                        mutableEvents.emit(
                            TransportEvent.FrameReceived(peerId = hintPeerId, payload = payload)
                        )
                    }
                }
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

    private fun shouldInitiateL2cap(remoteKeyHash: ByteArray): Boolean {
        return compareUnsigned(localKeyHash, remoteKeyHash) < 0
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
        pendingConnectionsByHint.clear()
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
        log("closing L2CAP link ${hintPeer.takeLast(6)}: $reason")
        link.readLoopJob?.cancel()
        link.close()
        mutableEvents.tryEmit(TransportEvent.PeerLost(PeerId(hintPeer)))
    }

    private fun refreshDiscoveryState(): Unit {
        centralManager?.stopScan()
        peripheralManager?.stopAdvertising()
        centralManager?.let(::startScanIfReady)
        startAdvertisingIfReady()
    }

    private fun discoveryPayload(l2capPsm: UByte): BleDiscoveryPayload {
        return BleDiscoveryPayload(
            protocolVersion = 1,
            powerMode = currentPowerProfile.discoveryPowerMode,
            meshHash = BleDiscoveryContract.computeMeshHash(appId),
            l2capPsm = l2capPsm,
            keyHash = localKeyHash,
        )
    }

    private fun advertisedPsm(psm: UShort): UByte {
        return if (psm.toInt() in 128..255) psm.toUByte() else 0u
    }

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        val length = minOf(left.size, right.size)
        for (index in 0 until length) {
            val leftByte = left[index].toInt() and 0xFF
            val rightByte = right[index].toInt() and 0xFF
            if (leftByte != rightByte) {
                return leftByte.compareTo(rightByte)
            }
        }
        return left.size.compareTo(right.size)
    }

    private fun log(message: String): Unit {
        println("MeshLinkTransport $message")
    }

    private class DiscoveredPeer(
        val hintPeerId: PeerId,
        keyHash: ByteArray,
        var peripheral: CBPeripheral,
        var l2capPsm: Int,
        var transportMode: TransportMode,
    ) {
        val keyHash: ByteArray = keyHash.copyOf()
    }

    private class IosL2capLink(
        val hintPeerId: PeerId,
        val peripheralIdentifier: String,
        channel: CBL2CAPChannel,
        private val incomingFrames: IosL2capFrameBuffer,
    ) {
        private val inputStream = checkNotNull(channel.inputStream).apply { open() }
        private val outputStream = checkNotNull(channel.outputStream).apply { open() }
        var readLoopJob: Job? = null

        suspend fun readFrames(): List<ByteArray> {
            if (!inputStream.hasBytesAvailable()) {
                return emptyList()
            }
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            val bytesRead = buffer.usePinned { pinned ->
                inputStream.read(pinned.addressOf(0).reinterpret(), buffer.size.convert())
            }
            if (bytesRead <= 0) {
                return emptyList()
            }
            return incomingFrames.append(buffer.copyOf(bytesRead.toInt()))
        }

        suspend fun write(payload: ByteArray): Unit {
            val encoded = incomingFrames.encode(payload)
            var offset = 0
            while (offset < encoded.size) {
                val written = encoded.usePinned { pinned ->
                    outputStream.write(
                        pinned.addressOf(offset).reinterpret(),
                        (encoded.size - offset).convert(),
                    )
                }
                if (written <= 0) {
                    delay(STREAM_POLL_INTERVAL_MS)
                    continue
                }
                offset += written.toInt()
            }
        }

        fun close(): Unit {
            inputStream.close()
            outputStream.close()
        }

        private companion object {
            private const val STREAM_BUFFER_BYTES: Int = 16 * 1024
        }
    }

    private companion object {
        private const val LARGE_DELIVERY_PAYLOAD_BUDGET_BYTES: Int = 128 * 1024
        private const val STREAM_POLL_INTERVAL_MS: Long = 5
        private const val TEMPORARY_PEER_PREFIX: String = "cb-"
    }
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
        val serviceUuids =
            advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<CBUUID> ?: return
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
        owner.publishL2capChannelIfReady(peripheral)
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
