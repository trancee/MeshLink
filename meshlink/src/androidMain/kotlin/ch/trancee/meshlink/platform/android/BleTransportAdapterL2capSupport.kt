package ch.trancee.meshlink.platform.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import ch.trancee.meshlink.wire.decodeLinkIdentityPeerIdOrNull
import java.io.Closeable
import java.io.InputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal fun BleTransportAdapter.hasPendingConnect(hintPeer: String): Boolean {
    return linkRegistry.hasPendingConnect(hintPeer)
}

internal fun BleTransportAdapter.clearPendingConnect(hintPeer: String): Unit {
    linkRegistry.clearPendingConnect(hintPeer)
}

@SuppressLint("MissingPermission")
internal fun BleTransportAdapter.connectIfNeeded(peer: DiscoveredPeer): Unit {
    if (peer.l2capPsm == 0) {
        log("connectIfNeeded(${peer.hintPeerId.value.takeLast(6)}) skipped: no PSM")
        return
    }
    val adapter = bluetoothAdapter ?: return
    val device =
        peerBindings.deviceFor(peer.deviceAddress) ?: adapter.getRemoteDevice(peer.deviceAddress)
    val connectJob =
        coroutineScope.launch(start = CoroutineStart.LAZY) {
            runCatching {
                    log(
                        "connecting L2CAP to ${peer.hintPeerId.value.takeLast(6)} psm=${peer.l2capPsm} addr=${device.address}"
                    )
                    val socket =
                        L2capSocketFactory.createInsecure(device = device, psm = peer.l2capPsm)
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
                    closeQuietly(linkRegistry.removeActiveLink(peer.hintPeerId.value))
                }
            clearPendingConnect(peer.hintPeerId.value)
        }
    val reserved = linkRegistry.reservePendingConnect(peer.hintPeerId.value, connectJob)
    if (!reserved) {
        connectJob.cancel()
        return
    }
    adapter.cancelDiscovery()
    connectJob.start()
}

internal fun BleTransportAdapter.scheduleL2capReconnect(peer: DiscoveredPeer): Unit {
    val backoffMillis = l2capReconnectGuard.backoffMillisFor(peer.hintPeerId.value)
    coroutineScope.launch {
        delay(backoffMillis)
        val retryPeer = peerRegistry.peer(peer.hintPeerId.value) ?: return@launch
        log(
            "retrying L2CAP connect for ${retryPeer.hintPeerId.value.takeLast(6)} after transient close (backoffMs=$backoffMillis)"
        )
        connectIfNeeded(retryPeer)
    }
}

internal fun BleTransportAdapter.promoteTemporaryLink(address: String, hintPeerId: PeerId): Unit {
    val mappedTemporaryHint = peerBindings.temporaryHintForAddress(address) ?: return
    when (
        linkRegistry.promoteTemporaryLink(
            address = address,
            resolvedHintPeerIdValue = hintPeerId.value,
            temporaryPeerPrefix = TEMPORARY_PEER_PREFIX,
            updateHint = { link, promotedHintPeerId -> link.peerHintId = promotedHintPeerId },
            closeLink = ::closeQuietly,
        )
    ) {
        TemporaryLinkPromotionOutcome.PROMOTED -> {
            log(
                "promoted temporary L2CAP link ${mappedTemporaryHint.takeLast(6)} -> ${hintPeerId.value.takeLast(6)} addr=$address"
            )
        }

        TemporaryLinkPromotionOutcome.CLOSED_DUPLICATE -> {
            log(
                "closing temporary L2CAP link ${mappedTemporaryHint.takeLast(6)} because ${hintPeerId.value.takeLast(6)} already has an active link"
            )
        }

        TemporaryLinkPromotionOutcome.NONE -> Unit
    }
}

internal fun BleTransportAdapter.launchAcceptLoop(serverSocket: BluetoothServerSocket): Unit {
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

internal fun BleTransportAdapter.registerConnectedSocket(
    hintPeerId: PeerId,
    socket: BluetoothSocket,
): Unit {
    val link =
        L2capLink(
            peerHintId = hintPeerId,
            localHintPeerId = PeerId(localKeyHash.toHexString()),
            socket = socket,
            incomingFrames = L2capFrameBuffer(),
            log = ::log,
        )
    if (!linkRegistry.registerActiveLink(hintPeerId.value, link)) {
        log("ignoring duplicate L2CAP socket for ${hintPeerId.value.takeLast(6)}")
        closeQuietly(socket)
        return
    }
    peerBindings.retainDevice(socket.remoteDevice.address, socket.remoteDevice)
    peerBindings.bindHintToAddress(socket.remoteDevice.address, hintPeerId.value)
    peerRegistry.setRediscoveryLoggedWithoutLink(hintPeerId.value, false)
    // A successful (re)connection restores the peer's full automatic-retry budget -- otherwise a
    // peer that had exhausted its retries during one rough patch would stay permanently
    // retry-starved for any later transient disconnect, even after a long healthy connection.
    l2capReconnectGuard.resetSuccess(hintPeerId.value)
    log(
        "registered L2CAP link for ${hintPeerId.value.takeLast(6)} addr=${socket.remoteDevice.address}"
    )
    link.readLoopJob = coroutineScope.launch {
        val readBuffer =
            ByteArray(link.maxReceivePacketSize.coerceAtLeast(DEFAULT_SOCKET_READ_BUFFER_BYTES))
        var consecutiveZeroByteReads = 0
        try {
            while (true) {
                val read = link.inputStream.read(readBuffer)
                if (read < 0) {
                    log {
                        "L2CAP EOF from ${link.peerHintId.value.takeLast(6)} pendingFrameBytes=${link.incomingFrames.pendingBytes()} maxReceivePacketSize=${link.maxReceivePacketSize}"
                    }
                    break
                }
                if (read == 0) {
                    consecutiveZeroByteReads += 1
                    val peerSuffix = link.peerHintId.value.takeLast(6)
                    if (consecutiveZeroByteReads >= MAX_CONSECUTIVE_ZERO_BYTE_READS) {
                        log {
                            "L2CAP zero-byte read threshold reached for $peerSuffix pendingFrameBytes=${link.incomingFrames.pendingBytes()} maxReceivePacketSize=${link.maxReceivePacketSize}"
                        }
                        break
                    }
                    log {
                        "L2CAP zero-byte read from $peerSuffix pendingFrameBytes=${link.incomingFrames.pendingBytes()} maxReceivePacketSize=${link.maxReceivePacketSize} consecutive=$consecutiveZeroByteReads"
                    }
                    delay(ZERO_BYTE_READ_BACKOFF_MS)
                    continue
                }
                consecutiveZeroByteReads = 0
                log(
                    "L2CAP read ${link.peerHintId.value.takeLast(6)} bytes=$read prefix=${readBuffer.copyOf(minOf(read, 8)).joinToString(separator = "") { byte -> "%02x".format(byte) }}"
                )
                val appendResult =
                    link.incomingFrames.appendDetailed(source = readBuffer, length = read)
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
                    val claimedPeerId = decodeLinkIdentityPeerIdOrNull(payload)
                    if (claimedPeerId != null) {
                        bindL2capLinkIdentity(link = link, claimedPeerId = claimedPeerId)
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

/**
 * Consumes an in-band [WireFrame.LinkIdentity] announcement received over an L2CAP socket.
 *
 * BLE address rotation can cause a connection's socket-level address to differ from the address a
 * peer was originally scan-discovered under, leaving [launchAcceptLoop] unable to resolve the real
 * peer id and falling back to a synthetic temporary one. Mirroring the GATT notify-side mechanism
 * (see [GattLinkIdentitySupport]), both ends of an L2CAP socket announce their own hint peer id as
 * the first frame written (see [L2capLink.write]); this lets the accepting side self-correct the
 * address binding once the claim arrives, independent of which address the socket connected on.
 */
internal fun BleTransportAdapter.bindL2capLinkIdentity(
    link: L2capLink,
    claimedPeerId: PeerId,
): Unit {
    if (link.peerHintId == claimedPeerId) {
        return
    }
    val address = link.remoteDevice.address
    peerBindings.bindHintToAddress(address, claimedPeerId.value)
    log(
        "bound L2CAP link ${link.peerHintId.value.takeLast(6)} -> ${claimedPeerId.value.takeLast(6)} addr=$address via LinkIdentity"
    )
    promoteTemporaryLink(address = address, hintPeerId = claimedPeerId)
}

internal fun BleTransportAdapter.activeLinkFor(peer: DiscoveredPeer): L2capLink? {
    return linkRegistry.resolveActiveLink(peer)
}

internal suspend fun BleTransportAdapter.sendViaConnectedLink(
    frame: OutboundFrame,
    link: L2capLink,
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
internal fun BleTransportAdapter.stopTransports(clearPeers: Boolean): Unit {
    discoveryLifecycle.stop(discoveryHardware())
    acceptLoopJob?.cancel()
    acceptLoopJob = null
    linkRegistry.cancelPendingConnects()
    gattNotifyServer?.close()
    gattNotifyServer = null
    closeQuietly(l2capServerSocket)
    l2capServerSocket = null
    val hintIds = linkRegistry.activeHintIdsSnapshot()
    hintIds.forEach { hintPeer -> closeLink(hintPeer = hintPeer, reason = "transport stopped") }
    l2capReconnectGuard.clear()
    gattSideLinks.stopAll()
    inboundFrameQueue?.close()
    inboundFrameQueue = null
    coroutineScope.coroutineContext.cancelChildren()
    if (clearPeers) {
        peerRegistry.clear()
        peerBindings.clear()
    }
}

internal fun BleTransportAdapter.closeLink(hintPeer: String, reason: String): Unit {
    val link = linkRegistry.removeActiveLink(hintPeer) ?: return
    val retryPeer = peerRegistry.peer(hintPeer)
    val retryRequested =
        retryPeer != null &&
            !hasPendingConnect(hintPeer) &&
            !gattSideLinks.hasReadyLink(hintPeer) &&
            l2capReconnectGuard.shouldRetry(hintPeerIdValue = hintPeer, reason = reason)
    peerRegistry.setRediscoveryLoggedWithoutLink(hintPeer, false)
    log(
        "closing L2CAP link ${hintPeer.takeLast(6)}: $reason discoveredPeerRetained=${retryPeer != null} pendingConnect=${hasPendingConnect(hintPeer)} retryRequested=$retryRequested"
    )
    link.readLoopJob?.cancel()
    closeQuietly(link)
    if (retryRequested) {
        scheduleL2capReconnect(requireNotNull(retryPeer))
        return
    }
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

private const val ZERO_BYTE_READ_BACKOFF_MS: Long = 5L
private const val MAX_CONSECUTIVE_ZERO_BYTE_READS: Int = 3

internal class L2capLink(
    internal var peerHintId: PeerId,
    private val localHintPeerId: PeerId,
    private val socket: BluetoothSocket,
    internal val incomingFrames: L2capFrameBuffer,
    private val log: (String) -> Unit,
) : Closeable {
    internal val remoteDevice: android.bluetooth.BluetoothDevice = socket.remoteDevice
    internal val inputStream: InputStream = socket.inputStream
    internal val maxReceivePacketSize: Int = socket.maxReceivePacketSize
    internal val maxTransmitPacketSize: Int = socket.maxTransmitPacketSize
    internal var readLoopJob: Job? = null
    private val outputStream = socket.outputStream
    @Volatile private var identityAnnounced: Boolean = false

    internal suspend fun write(payload: ByteArray): Unit {
        if (!identityAnnounced) {
            writeFrame(WireCodec.encode(WireFrame.LinkIdentity(localHintPeerId)))
            identityAnnounced = true
            log(
                "L2CAP link ${peerHintId.value.takeLast(6)} announced local LinkIdentity=${localHintPeerId.value.takeLast(6)}"
            )
        }
        writeFrame(payload)
    }

    private fun writeFrame(payload: ByteArray): Unit {
        val encoded = L2capFrameBuffer().encode(payload)
        log(
            "L2CAP write ${peerHintId.value.takeLast(6)} payloadBytes=${payload.size} encodedBytes=${encoded.size} payloadPrefix=${payload.copyOf(minOf(payload.size, 8)).joinToString(separator = "") { byte -> "%02x".format(byte) }}"
        )
        outputStream.write(encoded)
    }

    override fun close(): Unit {
        socket.close()
    }
}

internal const val DEFAULT_SOCKET_READ_BUFFER_BYTES: Int = 1024
