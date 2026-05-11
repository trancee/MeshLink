package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
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
import java.io.Closeable
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidBleTransport(
    private val context: Context,
    private val appId: String,
    advertisementKeyHash: ByteArray,
) : BleTransport {
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 32)
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val localKeyHash: ByteArray = advertisementKeyHash.copyOf()
    private val localKeyHashHex: String = localKeyHash.toHexString()
    private val discoveredPeers: MutableMap<String, DiscoveredPeer> = linkedMapOf()
    private val peerHintByAddress: MutableMap<String, String> = linkedMapOf()
    private val activeLinksByHint: MutableMap<String, L2capLink> = linkedMapOf()
    private val temporaryHintByAddress: MutableMap<String, String> = linkedMapOf()
    private val pendingConnectJobsByHint: MutableMap<String, Job> = linkedMapOf()
    private val transportMutex = Mutex()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var l2capServerSocket: BluetoothServerSocket? = null
    private var acceptLoopJob: Job? = null
    private var started: Boolean = false
    private var currentPowerProfile: AndroidPowerProfile = AndroidPowerMonitor.defaultProfile()
    private var currentDiscoveryPayload: BleDiscoveryPayload = discoveryPayload(l2capPsm = 0u)

    override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

    private val advertiseCallback = object : AdvertiseCallback() {}

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handleScanResult)
            }
        }

    override suspend fun start(): Unit {
        val bluetoothManager =
            context.getSystemService(BluetoothManager::class.java)
                ?: error("BluetoothManager is unavailable")
        val adapter = bluetoothManager.adapter ?: error("BluetoothAdapter is unavailable")
        bluetoothAdapter = adapter
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner

        val serverSocket =
            runCatching { adapter.listenUsingL2capChannel() }
                .onFailure { error ->
                    log("L2CAP server socket unavailable: ${error.message.orEmpty()}")
                }
                .getOrNull()
        l2capServerSocket = serverSocket
        currentDiscoveryPayload = discoveryPayload(l2capPsm = (serverSocket?.psm ?: 0).toUByte())
        log("start() with l2capPsm=${currentDiscoveryPayload.l2capPsm}")
        serverSocket?.let(::launchAcceptLoop)

        scanner?.startScan(scanFilters(), scanSettings(), scanCallback)
        advertiser?.startAdvertising(
            advertiseSettings(),
            advertiseData(currentDiscoveryPayload),
            advertiseCallback,
        )
        started = true
    }

    override suspend fun pause(): Unit {
        stopTransports(clearPeers = false)
        started = false
    }

    override suspend fun resume(): Unit {
        if (!started) {
            start()
        }
    }

    override suspend fun stop(): Unit {
        stopTransports(clearPeers = true)
        started = false
    }

    override suspend fun updatePowerPolicy(policy: PowerPolicy): Unit {
        currentPowerProfile = AndroidPowerMonitor.profileFor(policy)
        currentDiscoveryPayload = discoveryPayload(currentDiscoveryPayload.l2capPsm)
        if (!started) {
            return
        }
        scanner?.stopScan(scanCallback)
        advertiser?.stopAdvertising(advertiseCallback)
        scanner?.startScan(scanFilters(), scanSettings(), scanCallback)
        advertiser?.startAdvertising(
            advertiseSettings(),
            advertiseData(currentDiscoveryPayload),
            advertiseCallback,
        )
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        if (!started) {
            log("send(${frame.peerId.value.takeLast(6)}) dropped: transport not started")
            return TransportSendResult.Dropped("Android BLE transport is not started")
        }

        val peer =
            resolvePeer(frame.peerId)
                ?: return TransportSendResult.Dropped("Android BLE peer has not been discovered")
                    .also {
                        log("send(${frame.peerId.value.takeLast(6)}) dropped: peer not discovered")
                    }
        if (peer.transportMode != TransportMode.L2CAP || peer.l2capPsm == 0) {
            log("send(${frame.peerId.value.takeLast(6)}) dropped: peer is GATT-only")
            return TransportSendResult.Dropped(
                "Android BLE GATT fallback transport is not implemented"
            )
        }

        val link = activeLinksByHint[peer.hintPeerId.value]
        if (link == null) {
            if (shouldInitiateL2cap(peer.keyHash)) {
                log("send(${frame.peerId.value.takeLast(6)}) no active link, triggering connect")
                connectIfNeeded(peer)
            } else {
                log("send(${frame.peerId.value.takeLast(6)}) waiting for inbound L2CAP link")
            }
            return TransportSendResult.Dropped("Android BLE L2CAP connection is not ready")
        }

        return runCatching {
                transportMutex.withLock { link.write(frame.payload) }
                log("send(${frame.peerId.value.takeLast(6)}) delivered ${frame.payload.size} bytes")
                TransportSendResult.Delivered
            }
            .getOrElse { error ->
                closeLink(
                    hintPeer = peer.hintPeerId.value,
                    reason = "send failed: ${error.message.orEmpty()}",
                )
                TransportSendResult.Dropped("Android BLE send failed: ${error.message.orEmpty()}")
            }
    }

    private fun handleScanResult(result: ScanResult): Unit {
        val serviceUuids =
            result.scanRecord?.serviceUuids?.map { parcelUuid -> parcelUuid.uuid.toString() }
                ?: return
        val payloadUuid =
            serviceUuids.firstOrNull { uuid ->
                uuid != BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID
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
            "scan found ${hintPeerId.value.takeLast(6)} mode=$transportMode psm=${payload.l2capPsm} addr=${result.device.address}"
        )
        if (transportMode == TransportMode.L2CAP) {
            promoteTemporaryLink(address = result.device.address, hintPeerId = hintPeerId)
        }
        val discoveredPeer = discoveredPeers[hintPeerId.value]
        if (discoveredPeer == null) {
            discoveredPeers[hintPeerId.value] =
                DiscoveredPeer(
                    hintPeerId = hintPeerId,
                    keyHash = payload.keyHash,
                    device = result.device,
                    l2capPsm = payload.l2capPsm.toInt(),
                    transportMode = transportMode,
                )
            peerHintByAddress[result.device.address] = hintPeerId.value
            mutableEvents.tryEmit(
                TransportEvent.PeerDiscovered(peerId = hintPeerId, transportMode = transportMode)
            )
        } else {
            discoveredPeer.device = result.device
            discoveredPeer.l2capPsm = payload.l2capPsm.toInt()
            peerHintByAddress[result.device.address] = hintPeerId.value
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

    private fun shouldInitiateL2cap(remoteKeyHash: ByteArray): Boolean {
        return compareUnsigned(localKeyHash, remoteKeyHash) < 0
    }

    private fun connectIfNeeded(peer: DiscoveredPeer): Unit {
        if (peer.l2capPsm == 0) {
            log("connectIfNeeded(${peer.hintPeerId.value.takeLast(6)}) skipped: no PSM")
            return
        }
        if (
            activeLinksByHint.containsKey(peer.hintPeerId.value) ||
                pendingConnectJobsByHint.containsKey(peer.hintPeerId.value)
        ) {
            log(
                "connectIfNeeded(${peer.hintPeerId.value.takeLast(6)}) skipped: already active or pending"
            )
            return
        }
        val adapter = bluetoothAdapter ?: return
        pendingConnectJobsByHint[peer.hintPeerId.value] = coroutineScope.launch {
            runCatching {
                    log(
                        "connecting L2CAP to ${peer.hintPeerId.value.takeLast(6)} psm=${peer.l2capPsm} addr=${peer.device.address}"
                    )
                    val socket = peer.device.createL2capChannel(peer.l2capPsm)
                    socket.connect()
                    log("L2CAP connect succeeded for ${peer.hintPeerId.value.takeLast(6)}")
                    registerConnectedSocket(peer.hintPeerId, socket)
                }
                .onFailure { error ->
                    log(
                        "L2CAP connect failed for ${peer.hintPeerId.value.takeLast(6)}: ${error.message.orEmpty()}"
                    )
                    closeQuietly(activeLinksByHint.remove(peer.hintPeerId.value))
                }
            pendingConnectJobsByHint.remove(peer.hintPeerId.value)
        }
        adapter.cancelDiscovery()
    }

    private fun promoteTemporaryLink(address: String, hintPeerId: PeerId): Unit {
        val temporaryHint = temporaryHintByAddress[address] ?: return
        val promoted =
            synchronized(activeLinksByHint) {
                val link = activeLinksByHint.remove(temporaryHint) ?: return@synchronized false
                if (activeLinksByHint.containsKey(hintPeerId.value)) {
                    log(
                        "closing temporary L2CAP link ${temporaryHint.takeLast(6)} because ${hintPeerId.value.takeLast(6)} already has an active link"
                    )
                    closeQuietly(link)
                    return@synchronized false
                }
                link.peerHintId = hintPeerId
                activeLinksByHint[hintPeerId.value] = link
                true
            }
        temporaryHintByAddress.remove(address)
        peerHintByAddress[address] = hintPeerId.value
        if (promoted) {
            log(
                "promoted temporary L2CAP link ${temporaryHint.takeLast(6)} -> ${hintPeerId.value.takeLast(6)} addr=$address"
            )
        }
    }

    private fun launchAcceptLoop(serverSocket: BluetoothServerSocket): Unit {
        acceptLoopJob = coroutineScope.launch {
            while (true) {
                val socket =
                    runCatching { serverSocket.accept() }
                        .getOrElse { error ->
                            log("L2CAP accept loop stopped: ${error.message.orEmpty()}")
                            break
                        }
                log("accepted L2CAP socket from ${socket.remoteDevice.address}")
                val hintPeerId =
                    peerHintByAddress[socket.remoteDevice.address]?.let(::PeerId)
                        ?: temporaryPeerId(socket.remoteDevice.address)
                if (hintPeerId.value.startsWith(TEMPORARY_PEER_PREFIX)) {
                    log("binding accepted socket to temporary peer ${hintPeerId.value}")
                }
                registerConnectedSocket(hintPeerId, socket)
            }
        }
    }

    private fun registerConnectedSocket(hintPeerId: PeerId, socket: BluetoothSocket): Unit {
        synchronized(activeLinksByHint) {
            if (activeLinksByHint.containsKey(hintPeerId.value)) {
                log("ignoring duplicate L2CAP socket for ${hintPeerId.value.takeLast(6)}")
                closeQuietly(socket)
                return
            }
            val link =
                L2capLink(
                    peerHintId = hintPeerId,
                    socket = socket,
                    incomingFrames = AndroidL2capFrameBuffer(),
                )
            activeLinksByHint[hintPeerId.value] = link
            peerHintByAddress[socket.remoteDevice.address] = hintPeerId.value
            log(
                "registered L2CAP link for ${hintPeerId.value.takeLast(6)} addr=${socket.remoteDevice.address}"
            )
            link.readLoopJob = coroutineScope.launch {
                val readBuffer = ByteArray(DEFAULT_SOCKET_READ_BUFFER_BYTES)
                try {
                    while (true) {
                        val read = link.inputStream.read(readBuffer)
                        if (read < 0) {
                            break
                        }
                        val decodedFrames = link.incomingFrames.append(readBuffer.copyOf(read))
                        decodedFrames.forEach { payload ->
                            val currentPeerId = link.peerHintId
                            if (payload.isEmpty()) {
                                log(
                                    "ignoring empty frame from ${currentPeerId.value.takeLast(6)}"
                                )
                                return@forEach
                            }
                            log(
                                "received ${payload.size} bytes from ${currentPeerId.value.takeLast(6)}"
                            )
                            mutableEvents.emit(
                                TransportEvent.FrameReceived(
                                    peerId = currentPeerId,
                                    payload = payload,
                                )
                            )
                        }
                    }
                } finally {
                    closeLink(hintPeer = link.peerHintId.value, reason = "socket closed")
                }
            }
        }
    }

    private fun resolvePeer(peerId: PeerId): DiscoveredPeer? {
        discoveredPeers[peerId.value]?.let {
            return it
        }
        return discoveredPeers.values.firstOrNull { discoveredPeer ->
            peerId.value.startsWith(discoveredPeer.hintPeerId.value)
        }
    }

    private fun advertiseData(payload: BleDiscoveryPayload): AdvertiseData {
        return AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid.fromString(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID))
            .addServiceUuid(ParcelUuid.fromString(payload.payloadUuidString()))
            .build()
    }

    private fun advertiseSettings(): AdvertiseSettings {
        return AdvertiseSettings.Builder()
            .setAdvertiseMode(currentPowerProfile.advertiseMode)
            .setConnectable(true)
            .setTxPowerLevel(currentPowerProfile.txPowerLevel)
            .build()
    }

    private fun scanFilters(): List<ScanFilter> {
        return listOf(
            ScanFilter.Builder()
                .setServiceUuid(
                    ParcelUuid.fromString(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID)
                )
                .build()
        )
    }

    private fun scanSettings(): ScanSettings {
        return ScanSettings.Builder().setScanMode(currentPowerProfile.scanMode).build()
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

    private fun stopTransports(clearPeers: Boolean): Unit {
        scanner?.stopScan(scanCallback)
        advertiser?.stopAdvertising(advertiseCallback)
        acceptLoopJob?.cancel()
        acceptLoopJob = null
        pendingConnectJobsByHint.values.forEach(Job::cancel)
        pendingConnectJobsByHint.clear()
        closeQuietly(l2capServerSocket)
        l2capServerSocket = null
        val hintIds = activeLinksByHint.keys.toList()
        hintIds.forEach { hintPeer -> closeLink(hintPeer = hintPeer, reason = "transport stopped") }
        coroutineScope.coroutineContext.cancelChildren()
        if (clearPeers) {
            discoveredPeers.clear()
            peerHintByAddress.clear()
            temporaryHintByAddress.clear()
        }
    }

    private fun closeLink(hintPeer: String, reason: String): Unit {
        val link = synchronized(activeLinksByHint) { activeLinksByHint.remove(hintPeer) } ?: return
        log("closing L2CAP link ${hintPeer.takeLast(6)}: $reason")
        link.readLoopJob?.cancel()
        closeQuietly(link)
        val peerId = PeerId(hintPeer)
        mutableEvents.tryEmit(TransportEvent.PeerLost(peerId))
    }

    private fun closeQuietly(closeable: Closeable?): Unit {
        runCatching { closeable?.close() }
    }

    private fun closeQuietly(socket: BluetoothSocket?): Unit {
        runCatching { socket?.close() }
    }

    private fun temporaryPeerId(address: String): PeerId {
        val temporaryHint =
            temporaryHintByAddress.getOrPut(address) {
                TEMPORARY_PEER_PREFIX + address.lowercase().replace(":", "")
            }
        return PeerId(temporaryHint)
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

    private class DiscoveredPeer(
        val hintPeerId: PeerId,
        keyHash: ByteArray,
        var device: BluetoothDevice,
        var l2capPsm: Int,
        var transportMode: TransportMode,
    ) {
        val keyHash: ByteArray = keyHash.copyOf()
    }

    private fun log(message: String): Unit {
        Log.d(LOG_TAG, message)
    }

    private class L2capLink(
        var peerHintId: PeerId,
        val socket: BluetoothSocket,
        val incomingFrames: AndroidL2capFrameBuffer,
    ) : Closeable {
        val inputStream: InputStream = socket.inputStream
        private val outputStream = socket.outputStream
        var readLoopJob: Job? = null

        suspend fun write(payload: ByteArray): Unit {
            outputStream.write(incomingFrames.encode(payload))
            outputStream.flush()
        }

        override fun close(): Unit {
            socket.close()
        }
    }

    private companion object {
        private const val DEFAULT_SOCKET_READ_BUFFER_BYTES: Int = 1024
        private const val LOG_TAG: String = "MeshLinkTransport"
        private const val TEMPORARY_PEER_PREFIX: String = "bt-"
    }
}
