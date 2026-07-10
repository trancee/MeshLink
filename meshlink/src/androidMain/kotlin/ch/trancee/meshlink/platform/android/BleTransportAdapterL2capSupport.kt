package ch.trancee.meshlink.platform.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothSocketException
import android.os.Build
import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.L2CAP_KEEPALIVE_INTERVAL_MILLIS
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import ch.trancee.meshlink.wire.decodeIsKeepAlive
import ch.trancee.meshlink.wire.decodeLinkIdentityPeerIdOrNull
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun BleTransportAdapter.hasPendingConnect(hintPeer: String): Boolean {
    return linkRegistry.hasPendingConnect(hintPeer)
}

internal fun BleTransportAdapter.clearPendingConnect(hintPeer: String): Unit {
    linkRegistry.clearPendingConnect(hintPeer)
}

@SuppressLint("MissingPermission")
internal fun BleTransportAdapter.connectIfNeeded(peer: DiscoveredPeer): Unit {
    if (transportStopping) {
        log("connectIfNeeded(${peer.hintPeerId.value.takeLast(6)}) skipped: transport stopping")
        return
    }
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
                    // Registered before the blocking connect() call so a concurrent teardown can
                    // force-close this socket (via cancelPendingConnects()) to unblock it -- a
                    // blocking socket connect() has no suspension point, so Job cancellation alone
                    // cannot interrupt it once this coroutine is already running.
                    linkRegistry.registerPendingConnectSocket(peer.hintPeerId.value, socket)
                    socket.connect()
                    log("L2CAP connect succeeded for ${peer.hintPeerId.value.takeLast(6)}")
                    peerRegistry.setRediscoveryLoggedWithoutLink(peer.hintPeerId.value, false)
                    registerConnectedSocket(peer.hintPeerId, socket)
                }
                .onFailure { error -> handleL2capConnectFailure(peer, error) }
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

/**
 * Handles an outbound L2CAP connect() failure: logs the error (with its machine-readable
 * [BluetoothSocketException] code when available), tears down any partial link state, and schedules
 * a backoff retry only when the failure is classified retryable AND the peer isn't already
 * reachable over a ready GATT side-link fallback -- mirroring [closeLink]'s existing
 * `!gattSideLinks.hasReadyLink(...)` gate for post-connect teardowns, so an initial connect failure
 * doesn't schedule a redundant L2CAP retry for a peer that's already fine over GATT.
 */
private fun BleTransportAdapter.handleL2capConnectFailure(
    peer: DiscoveredPeer,
    error: Throwable,
): Unit {
    val errorCodeSuffix =
        l2capConnectFailureErrorCode(error)?.let { code -> " errorCode=$code" }.orEmpty()
    log(
        "L2CAP connect failed for ${peer.hintPeerId.value.takeLast(6)}: " +
            "${error.message.orEmpty()}$errorCodeSuffix"
    )
    peerRegistry.setRediscoveryLoggedWithoutLink(peer.hintPeerId.value, false)
    closeQuietly(linkRegistry.removeActiveLink(peer.hintPeerId.value))
    val retryableFailure = isRetryableL2capConnectFailure(error)
    val gattFallbackReady = gattSideLinks.hasReadyLink(peer.hintPeerId.value)
    if (!shouldAttemptL2capConnectRetry(retryableFailure, gattFallbackReady)) {
        if (retryableFailure) {
            log(
                "skipping L2CAP connect retry for ${peer.hintPeerId.value.takeLast(6)}: " +
                    "GATT side-link already ready"
            )
        }
        return
    }
    val guardAllowsRetry =
        l2capReconnectGuard.shouldRetry(
            hintPeerIdValue = peer.hintPeerId.value,
            reason = "connect failed: ${error.message.orEmpty()}",
        )
    if (guardAllowsRetry) {
        scheduleL2capReconnect(peer)
    }
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
                    if (decodeIsKeepAlive(payload)) {
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
        } catch (e: IOException) {
            // Android 17 migration note: a real read error (broken pipe, ACL disconnect, stack
            // teardown) throws here rather than returning -1 -- without this catch it propagates
            // uncaught out of this coroutineScope.launch (a bare SupervisorJob with no installed
            // CoroutineExceptionHandler), which crashes the process on the default thread-level
            // handler instead of being treated as an ordinary transient link failure.
            log { "L2CAP read error from ${link.peerHintId.value.takeLast(6)}: ${e.message}" }
        } catch (e: MeshLinkException.TransportFailure) {
            // A malformed/oversized length-prefixed frame (see L2capFrameBuffer.appendDetailed)
            // must not crash the transport -- treat it the same as any other unrecoverable link
            // failure and tear the link down so the reconnect guard can decide whether to retry.
            log {
                "L2CAP frame decode error from ${link.peerHintId.value.takeLast(6)}: ${e.message}"
            }
        } finally {
            closeLink(hintPeer = link.peerHintId.value, reason = "socket closed")
        }
    }
    link.heartbeatJob = launchL2capKeepAliveLoop(link)
}

/**
 * Periodically writes an empty [WireFrame.KeepAlive] control frame on an otherwise idle L2CAP link
 * so the peer's platform BLE stack does not tear the channel down for inactivity (see
 * [L2CAP_KEEPALIVE_INTERVAL_MILLIS] for the rationale). The frame is consumed at the transport
 * layer by the read loop above and never surfaces as a [TransportEvent.FrameReceived].
 */
internal fun BleTransportAdapter.launchL2capKeepAliveLoop(link: L2capLink): Job {
    return coroutineScope.launch {
        val keepAliveFrame = WireCodec.encode(WireFrame.KeepAlive())
        while (true) {
            delay(L2CAP_KEEPALIVE_INTERVAL_MILLIS)
            runCatching { link.write(keepAliveFrame) }
                .onFailure {
                    closeLink(
                        hintPeer = link.peerHintId.value,
                        // Prefixed with "send failed:" (not a bespoke "keepalive write failed"
                        // string) so this is classified by L2capReconnectGuard.
                        // isTransientL2capDisconnect the same way as any other transient write
                        // I/O error -- a keepalive write is not meaningfully different from a
                        // payload write for retry purposes, and a mismatched prefix here silently
                        // excluded keepalive-triggered disconnects from automatic reconnect.
                        reason = "send failed: keepalive write failed",
                    )
                    return@launch
                }
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
            link.write(frame.payload)
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
internal suspend fun BleTransportAdapter.stopTransports(clearPeers: Boolean): Unit {
    // Set first, before any other teardown step: connectIfNeeded() (and therefore
    // scheduleL2capReconnect()'s delayed retry) checks this to refuse new/resumed connection
    // attempts for the remainder of teardown, including ones triggered by a straggler
    // scan-processing coroutine or by a "socket closed" retry scheduled from a force-close inside
    // this very function (see cancelPendingConnects() below).
    transportStopping = true
    unregisterBluetoothStateChangeReceiver()
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
    // Cancel and join every outstanding child (including background scan-processing work
    // dispatched via scanProcessingDispatcher), then clear peer state. Run this under
    // NonCancellable: once teardown has started (hardware/sockets/links above are already torn
    // down), cancelling the caller of stopTransports() must not be allowed to abort mid-loop and
    // leave peerRegistry/peerBindings stale relative to the already-stopped hardware.
    withContext(NonCancellable) {
        // A single cancelAndJoin pass is not enough: an in-flight handleScanResult call has no
        // suspension points, so cancelling it does not stop it, and it may itself launch a new
        // connectIfNeeded child after this pass's snapshot was taken. Loop until no children
        // remain so late-spawned children can't mutate peer state after this function clears it
        // below. (scanCallback also gates on isStopped so no further scan-processing children can
        // be launched once discoveryLifecycle.stop() above has run.)
        //
        // Bounded with a timeout: a readLoopJob registered by a straggler connectIfNeeded can be
        // blocked in a plain synchronous BluetoothSocket.inputStream.read() with no timeout and no
        // suspension point either, so cancellation cannot interrupt it -- only closing its socket
        // can. Don't let stopTransports() hang forever waiting on cancellation alone.
        withTimeoutOrNull(SCAN_TEARDOWN_DRAIN_TIMEOUT_MILLIS) { drainCoroutineScopeChildren() }
        // Force-close any link that a straggler connectIfNeeded registered after the hintIds
        // snapshot above (e.g. from a scan-processing coroutine that was already dispatched
        // before discoveryLifecycle.stop() flipped isStopped). Closing the socket unblocks any
        // readLoopJob still stuck in a blocking read with an IOException instead of relying on
        // cancellation, which cannot interrupt synchronous socket I/O.
        linkRegistry.activeHintIdsSnapshot().forEach { hintPeer ->
            closeLink(hintPeer = hintPeer, reason = "transport stopped (post-drain)")
        }
        // A straggler handleScanResult call can just as easily reach gattSideLinks.ensureStarted()
        // instead of (or in addition to) connectIfNeeded(); that path isn't a coroutineScope child
        // at all (it's driven by Android's own GATT callback thread), so
        // drainCoroutineScopeChildren()
        // can't observe or wait for it. stopAll() is a safe snapshot-and-clear, so re-running it
        // here
        // closes any side link created after the first call above.
        gattSideLinks.stopAll()
        // Likewise, a straggler connectIfNeeded() may have reserved a new pending connect after the
        // cancelPendingConnects() call above; cancel it too so it can't proceed if it hasn't
        // started
        // its blocking socket.connect() call yet.
        linkRegistry.cancelPendingConnects()
        // Now that any straggler sockets are closed, their readLoopJobs are unblocked and should
        // finish quickly; drain them too, still bounded in case something unexpected stalls.
        withTimeoutOrNull(SCAN_TEARDOWN_DRAIN_TIMEOUT_MILLIS) { drainCoroutineScopeChildren() }
        if (clearPeers) {
            peerRegistry.clear()
            peerBindings.clear()
        }
    }
}

private suspend fun BleTransportAdapter.drainCoroutineScopeChildren(): Unit {
    while (true) {
        val children = coroutineScope.coroutineContext.job.children.toList()
        if (children.isEmpty()) break
        children.forEach { it.cancelAndJoin() }
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
    link.heartbeatJob?.cancel()
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
private const val SCAN_TEARDOWN_DRAIN_TIMEOUT_MILLIS: Long = 3_000L

/**
 * Error codes (API 34+ [BluetoothSocketException]) that indicate a connect failure will not resolve
 * by simply retrying the same PSM/device -- e.g. missing authentication/encryption, an invalid PSM,
 * or malformed parameters. Everything else (including plain [java.io.IOException]s on pre-34
 * devices, where no machine-readable error code is available at all) is treated as transient and
 * eligible for the existing backoff-based retry in [scheduleL2capReconnect]. See the
 * android-bluetooth-sockets skill: "branch on errorCode instead of string-matching the message."
 *
 * Split into a pure [isTerminalL2capErrorCode] (plain [Int] in, testable without a real
 * [BluetoothSocketException] instance -- Android's unit-test stub jar throws on
 * [BluetoothSocketException]'s own constructor/methods, but its `errorCode` constants are plain
 * compile-time `Int` constants and remain usable) plus this thin dispatcher that only needs to
 * decide whether an error code is even available to look at.
 */
internal fun isRetryableL2capConnectFailure(
    error: Throwable,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Boolean {
    if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // No machine-readable error code is available at all below API 34 -- fall back to
        // treating the failure as transient/retryable rather than silently giving up on the
        // first failure.
        return true
    }
    return when (error) {
        is BluetoothSocketException -> !isTerminalL2capErrorCode(error.errorCode)
        // Non-BluetoothSocketException IOExceptions carry no error code either -- same fallback.
        else -> true
    }
}

/**
 * Pure, host-JVM-testable classification of a [BluetoothSocketException.getErrorCode] value. See
 * [isRetryableL2capConnectFailure] for how this fits into the overall retry decision.
 */
internal fun isTerminalL2capErrorCode(errorCode: Int): Boolean {
    return errorCode in TERMINAL_L2CAP_CONNECT_ERROR_CODES
}

private val TERMINAL_L2CAP_CONNECT_ERROR_CODES: Set<Int> =
    setOf(
        BluetoothSocketException.BLUETOOTH_OFF_FAILURE,
        BluetoothSocketException.NULL_DEVICE,
        BluetoothSocketException.L2CAP_CLIENT_SECURITY_FAILURE,
        BluetoothSocketException.L2CAP_INSUFFICIENT_AUTHENTICATION,
        BluetoothSocketException.L2CAP_INSUFFICIENT_AUTHORIZATION,
        BluetoothSocketException.L2CAP_INSUFFICIENT_ENCRYPTION,
        BluetoothSocketException.L2CAP_INSUFFICIENT_ENCRYPT_KEY_SIZE,
        BluetoothSocketException.L2CAP_NO_PSM_AVAILABLE,
        BluetoothSocketException.L2CAP_INVALID_PARAMETERS,
        BluetoothSocketException.L2CAP_UNACCEPTABLE_PARAMETERS,
    )

/** Machine-readable error code for logging, when the platform and exception type provide one. */
internal fun l2capConnectFailureErrorCode(
    error: Throwable,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Int? {
    if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return null
    }
    return (error as? BluetoothSocketException)?.errorCode
}

/**
 * Pure decision of whether an L2CAP connect failure is even eligible to consult
 * [L2capReconnectGuard.shouldRetry] for a backoff-scheduled retry -- kept separate from the guard
 * call itself (which has the side effect of consuming a retry-budget attempt) so that gate can be
 * unit-tested directly, and so a failure that's ineligible here never spends retry budget it won't
 * use. Mirrors [closeLink]'s existing `!gattSideLinks.hasReadyLink(...)` gate for post-connect
 * teardowns, so an initial connect failure doesn't schedule a redundant L2CAP retry for a peer
 * that's already reachable over its GATT side-link fallback.
 */
internal fun shouldAttemptL2capConnectRetry(
    retryableFailure: Boolean,
    gattFallbackReady: Boolean,
): Boolean {
    return retryableFailure && !gattFallbackReady
}

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
    internal var heartbeatJob: Job? = null
    private val outputStream = socket.outputStream
    private val writeMutex = Mutex()
    @Volatile private var identityAnnounced: Boolean = false

    internal suspend fun write(payload: ByteArray): Unit {
        writeMutex.withLock {
            if (!identityAnnounced) {
                writeFrame(WireCodec.encode(WireFrame.LinkIdentity(localHintPeerId)))
                identityAnnounced = true
                log(
                    "L2CAP link ${peerHintId.value.takeLast(6)} announced local LinkIdentity=${localHintPeerId.value.takeLast(6)}"
                )
            }
            writeFrame(payload)
        }
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
