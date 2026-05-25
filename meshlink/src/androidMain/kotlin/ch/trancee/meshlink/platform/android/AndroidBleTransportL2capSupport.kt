package ch.trancee.meshlink.platform.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.ActivePeerHintResolutionRequest
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TemporaryPeerHintPromotionRequest
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.transport.resolveActivePeerHint
import ch.trancee.meshlink.transport.selectTemporaryPeerHintPromotion
import java.io.Closeable
import java.io.InputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal fun AndroidBleTransport.hasPendingConnect(hintPeer: String): Boolean {
    return synchronized(pendingConnectLock) { pendingConnectJobsByHint.containsKey(hintPeer) }
}

internal fun AndroidBleTransport.clearPendingConnect(hintPeer: String): Unit {
    synchronized(pendingConnectLock) { pendingConnectJobsByHint.remove(hintPeer) }
}

@SuppressLint("MissingPermission")
internal fun AndroidBleTransport.connectIfNeeded(peer: DiscoveredPeer): Unit {
    if (peer.l2capPsm == 0) {
        log("connectIfNeeded(${peer.hintPeerId.value.takeLast(6)}) skipped: no PSM")
        return
    }
    val adapter = bluetoothAdapter ?: return
    val device = peerBindings.deviceFor(peer.deviceAddress) ?: return
    val connectJob =
        coroutineScope.launch(start = CoroutineStart.LAZY) {
            runCatching {
                    log(
                        "connecting L2CAP to ${peer.hintPeerId.value.takeLast(6)} psm=${peer.l2capPsm} addr=${device.address}"
                    )
                    val socket =
                        AndroidL2capSocketFactory.createInsecure(
                            device = device,
                            psm = peer.l2capPsm,
                        ) { error ->
                            log(
                                "explicit insecure L2CAP client socket fallback for ${peer.hintPeerId.value.takeLast(6)}: ${error.message.orEmpty()}"
                            )
                        }
                    socket.connect()
                    log("L2CAP connect succeeded for ${peer.hintPeerId.value.takeLast(6)}")
                    peerRegistry.setRediscoveryLoggedWithoutLink(peer.hintPeerId.value, false)
                    registerConnectedSocket(peer.hintPeerId, socket)
                }
                .onFailure { error ->
                    log(
                        "L2CAP connect failed for ${peer.hintPeerId.value.takeLast(6)}: ${error.message.orEmpty()}"
                    )
                    peerRegistry.setRediscoveryLoggedWithoutLink(peer.hintPeerId.value, false)
                    closeQuietly(activeLinksByHint.remove(peer.hintPeerId.value))
                }
            clearPendingConnect(peer.hintPeerId.value)
        }
    val reserved =
        synchronized(pendingConnectLock) {
            if (
                activeLinksByHint.containsKey(peer.hintPeerId.value) ||
                    pendingConnectJobsByHint.containsKey(peer.hintPeerId.value)
            ) {
                false
            } else {
                pendingConnectJobsByHint[peer.hintPeerId.value] = connectJob
                true
            }
        }
    if (!reserved) {
        connectJob.cancel()
        return
    }
    adapter.cancelDiscovery()
    connectJob.start()
}

internal fun AndroidBleTransport.promoteTemporaryLink(address: String, hintPeerId: PeerId): Unit {
    val mappedTemporaryHint = peerBindings.temporaryHintForAddress(address) ?: return
    val temporaryHint =
        selectTemporaryPeerHintPromotion(
            TemporaryPeerHintPromotionRequest(
                temporaryHintPeerIdValue = mappedTemporaryHint,
                resolvedHintPeerIdValue = hintPeerId.value,
                activeHintIds = activeLinksByHint.keys,
                temporaryPeerPrefix = TEMPORARY_PEER_PREFIX,
            )
        )
    val promoted =
        synchronized(activeLinksByHint) {
            when {
                temporaryHint != null -> {
                    val link = activeLinksByHint.remove(temporaryHint) ?: return@synchronized false
                    link.peerHintId = hintPeerId
                    activeLinksByHint[hintPeerId.value] = link
                    true
                }

                activeLinksByHint.containsKey(mappedTemporaryHint) &&
                    activeLinksByHint.containsKey(hintPeerId.value) -> {
                    log(
                        "closing temporary L2CAP link ${mappedTemporaryHint.takeLast(6)} because ${hintPeerId.value.takeLast(6)} already has an active link"
                    )
                    closeQuietly(activeLinksByHint.remove(mappedTemporaryHint))
                    false
                }

                else -> false
            }
        }
    peerBindings.removeTemporaryHint(address)
    peerBindings.bindHintToAddress(address, hintPeerId.value)
    if (promoted) {
        log(
            "promoted temporary L2CAP link ${mappedTemporaryHint.takeLast(6)} -> ${hintPeerId.value.takeLast(6)} addr=$address"
        )
    }
}

internal fun AndroidBleTransport.launchAcceptLoop(serverSocket: BluetoothServerSocket): Unit {
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
                peerBindings.hintForAddress(socket.remoteDevice.address)?.let(::PeerId)
                    ?: peerBindings.temporaryPeerId(socket.remoteDevice.address)
            if (hintPeerId.value.startsWith(TEMPORARY_PEER_PREFIX)) {
                log("binding accepted socket to temporary peer ${hintPeerId.value}")
            }
            registerConnectedSocket(hintPeerId, socket)
        }
    }
}

internal fun AndroidBleTransport.registerConnectedSocket(
    hintPeerId: PeerId,
    socket: BluetoothSocket,
): Unit {
    synchronized(activeLinksByHint) {
        if (activeLinksByHint.containsKey(hintPeerId.value)) {
            log("ignoring duplicate L2CAP socket for ${hintPeerId.value.takeLast(6)}")
            closeQuietly(socket)
            return
        }
        val link =
            AndroidL2capLink(
                peerHintId = hintPeerId,
                socket = socket,
                incomingFrames = AndroidL2capFrameBuffer(),
            )
        activeLinksByHint[hintPeerId.value] = link
        peerBindings.retainDevice(socket.remoteDevice.address, socket.remoteDevice)
        peerBindings.bindHintToAddress(socket.remoteDevice.address, hintPeerId.value)
        peerRegistry.setRediscoveryLoggedWithoutLink(hintPeerId.value, false)
        log(
            "registered L2CAP link for ${hintPeerId.value.takeLast(6)} addr=${socket.remoteDevice.address}"
        )
        link.readLoopJob = coroutineScope.launch {
            val readBuffer =
                ByteArray(link.maxReceivePacketSize.coerceAtLeast(DEFAULT_SOCKET_READ_BUFFER_BYTES))
            try {
                while (true) {
                    val read = link.inputStream.read(readBuffer)
                    if (read < 0) {
                        log(
                            "L2CAP EOF from ${link.peerHintId.value.takeLast(6)} pendingFrameBytes=${link.incomingFrames.pendingBytes()} maxReceivePacketSize=${link.maxReceivePacketSize}"
                        )
                        break
                    }
                    if (read == 0) {
                        log(
                            "L2CAP zero-byte read from ${link.peerHintId.value.takeLast(6)} pendingFrameBytes=${link.incomingFrames.pendingBytes()} maxReceivePacketSize=${link.maxReceivePacketSize}"
                        )
                        continue
                    }
                    val appendResult = link.incomingFrames.appendDetailed(readBuffer.copyOf(read))
                    appendResult.frames.forEachIndexed { frameIndex, payload ->
                        val currentPeerId = link.peerHintId
                        if (payload.isEmpty()) {
                            logEmptyFrameObservation(
                                peerId = currentPeerId,
                                readBytes = read,
                                appendResult = appendResult,
                                observation = appendResult.observations.getOrNull(frameIndex),
                            )
                            return@forEachIndexed
                        }
                        mutableEvents.emit(
                            TransportEvent.FrameReceived(peerId = currentPeerId, payload = payload)
                        )
                    }
                }
            } finally {
                closeLink(hintPeer = link.peerHintId.value, reason = "socket closed")
            }
        }
    }
}

internal fun AndroidBleTransport.activeLinkFor(peer: DiscoveredPeer): AndroidL2capLink? {
    val activeHint =
        resolveActivePeerHint(
            ActivePeerHintResolutionRequest(
                hintPeerIdValue = peer.hintPeerId.value,
                temporaryHintPeerIdValue = peerBindings.temporaryHintForAddress(peer.deviceAddress),
                activeHintIds = activeLinksByHint.keys,
            )
        ) ?: return null
    return activeLinksByHint[activeHint]
}

internal suspend fun AndroidBleTransport.sendViaConnectedLink(
    frame: OutboundFrame,
    link: AndroidL2capLink,
): TransportSendResult {
    return runCatching {
            transportMutex.withLock { link.write(frame.payload) }
            TransportSendResult.Delivered
        }
        .getOrElse { error ->
            closeLink(
                hintPeer = link.peerHintId.value,
                reason = "send failed: ${error.message.orEmpty()}",
            )
            TransportSendResult.Dropped("Android BLE send failed: ${error.message.orEmpty()}")
        }
}

@SuppressLint("MissingPermission")
internal fun AndroidBleTransport.stopTransports(clearPeers: Boolean): Unit {
    scanner?.stopScan(scanCallback)
    advertiser?.stopAdvertising(advertiseCallback)
    acceptLoopJob?.cancel()
    acceptLoopJob = null
    synchronized(pendingConnectLock) {
        pendingConnectJobsByHint.values.forEach(Job::cancel)
        pendingConnectJobsByHint.clear()
    }
    closeQuietly(l2capServerSocket)
    l2capServerSocket = null
    val hintIds = activeLinksByHint.keys.toList()
    hintIds.forEach { hintPeer -> closeLink(hintPeer = hintPeer, reason = "transport stopped") }
    gattSideLinks.stopAll()
    inboundFrameQueue?.close()
    inboundFrameQueue = null
    coroutineScope.coroutineContext.cancelChildren()
    if (clearPeers) {
        peerRegistry.clear()
        peerBindings.clear()
    }
}

internal fun AndroidBleTransport.closeLink(hintPeer: String, reason: String): Unit {
    val link = synchronized(activeLinksByHint) { activeLinksByHint.remove(hintPeer) } ?: return
    peerRegistry.setRediscoveryLoggedWithoutLink(hintPeer, false)
    log(
        "closing L2CAP link ${hintPeer.takeLast(6)}: $reason discoveredPeerRetained=${peerRegistry.peer(hintPeer) != null} pendingConnect=${hasPendingConnect(hintPeer)}"
    )
    link.readLoopJob?.cancel()
    closeQuietly(link)
    if (gattSideLinks.hasReadyLink(hintPeer)) {
        log(
            "retaining peer ${hintPeer.takeLast(6)} after L2CAP close because the GATT side link is still active"
        )
        return
    }
    peerRegistry.setPresenceAnnounced(hintPeer, false)
    val peerId = PeerId(hintPeer)
    mutableEvents.tryEmit(TransportEvent.PeerLost(peerId))
}

internal class AndroidL2capLink(
    internal var peerHintId: PeerId,
    private val socket: BluetoothSocket,
    internal val incomingFrames: AndroidL2capFrameBuffer,
) : Closeable {
    internal val remoteDevice: android.bluetooth.BluetoothDevice = socket.remoteDevice
    internal val inputStream: InputStream = socket.inputStream
    internal val maxReceivePacketSize: Int = socket.maxReceivePacketSize
    internal val maxTransmitPacketSize: Int = socket.maxTransmitPacketSize
    internal var readLoopJob: Job? = null
    private val outputStream = socket.outputStream

    internal suspend fun write(payload: ByteArray): Unit {
        outputStream.write(incomingFrames.encode(payload))
    }

    override fun close(): Unit {
        socket.close()
    }
}

internal const val DEFAULT_SOCKET_READ_BUFFER_BYTES: Int = 1024
