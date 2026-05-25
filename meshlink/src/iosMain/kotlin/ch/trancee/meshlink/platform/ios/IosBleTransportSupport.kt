@file:Suppress("TooManyFunctions")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.DirectWireFrame
import ch.trancee.meshlink.transport.ActivePeerHintResolutionRequest
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.GattDataBearerMode
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.RediscoveryWithoutLinkDecisionRequest
import ch.trancee.meshlink.transport.TemporaryPeerHintPromotionRequest
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.transport.evaluateRediscoveryWithoutLink
import ch.trancee.meshlink.transport.resolveActivePeerHint
import ch.trancee.meshlink.transport.resolveGattDataBearerMode
import ch.trancee.meshlink.transport.selectTemporaryPeerHintPromotion
import ch.trancee.meshlink.transport.shouldLocalPeerInitiateL2capConnection
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBPeripheralManagerConnectionLatencyLow
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSStreamStatusAtEnd
import platform.Foundation.NSStreamStatusClosed
import platform.Foundation.NSStreamStatusError
import platform.posix.getenv
import platform.posix.memcpy

private const val PEER_LOG_SUFFIX_CHARS: Int = 6
internal const val NO_L2CAP_PSM: Int = 0
private const val ADVERTISED_PSM_MIN: Int = 128
private const val ADVERTISED_PSM_MAX: Int = 255
internal const val NO_ADVERTISED_L2CAP_PSM: UByte = 0u
private val ADVERTISED_PSM_RANGE: IntRange = ADVERTISED_PSM_MIN..ADVERTISED_PSM_MAX
internal const val NO_GATT_CHARACTERISTIC_PERMISSIONS: ULong = 0u
private const val NO_DATA_BYTES: Int = 0
private const val MILLIS_PER_SECOND: Double = 1000.0
private const val ENV_VALUE_NUMERIC_TRUE: String = "1"
private const val ENV_VALUE_BOOLEAN_TRUE: String = "true"
private const val ENV_VALUE_YES: String = "yes"

internal suspend fun IosBleTransport.sendWhenStarted(frame: OutboundFrame): TransportSendResult {
    return dispatchIosSend(
        frame = frame,
        dependencies =
            IosSendDispatchDependencies(
                sendToResolvedPeerOrNull = {
                    resolvePeer(frame.peerId)?.let { peer -> sendToPeer(frame, peer) }
                },
                dropWhenPeerIsMissing = {
                    dropSend(
                        frame,
                        message = "iOS BLE peer has not been discovered",
                        detail = "peer not discovered",
                    )
                },
            ),
    )
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
    val gattSendResult =
        sendViaGattNotifyLinkOrNull(frame = frame, peer = peer, directFrame = directFrame)
    return if (gattSendResult != null) {
        gattSendResult
    } else {
        sendViaL2capWhenReady(frame = frame, peer = peer)
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
    return sendViaPreferredGattNotifyLinkOrNull(
        frame = frame,
        context =
            IosPreferredGattSendContext(
                hintPeerId = peer.hintPeerId,
                localPlatformFamily = currentDiscoveryPayload.platformFamily,
                remotePlatformFamily = peer.platformFamily,
            ),
        dependencies =
            IosPreferredGattSendDependencies(
                currentLink = {
                    activeGattNotifyLinkFor(peer)
                        ?.takeIf { directFrame is DirectWireFrame.Data }
                        ?.let { link ->
                            object : IosPreferredGattSendLink {
                                override suspend fun enqueue(payload: ByteArray): Boolean {
                                    return link.enqueue(payload)
                                }
                            }
                        }
                },
                log = ::log,
            ),
    )
}

internal suspend fun IosBleTransport.sendViaL2capWhenReady(
    frame: OutboundFrame,
    peer: DiscoveredPeer,
): TransportSendResult {
    return sendViaIosL2capWhenReady(
        frame = frame,
        context = IosL2capSendContext(hintPeerId = frame.peerId),
        dependencies =
            IosL2capSendDependencies(
                currentLink = {
                    activeLinkFor(peer)?.let { link ->
                        object : IosL2capSendLink {
                            override val hintPeerId: PeerId = link.hintPeerId

                            override suspend fun enqueue(payload: ByteArray): Boolean {
                                return link.enqueue(payload)
                            }
                        }
                    }
                },
                ensureConnectAttempt = { connectIfNeeded(peer) },
                shouldInitiateL2cap = { shouldInitiateL2cap(peer.keyHash, peer.platformFamily) },
                closeLink = ::closeLink,
                log = ::log,
            ),
    )
}

internal fun IosBleTransport.dropSend(
    frame: OutboundFrame,
    message: String,
    detail: String,
): TransportSendResult {
    log("send(${frame.peerId.logSuffix()}) dropped: $detail")
    return TransportSendResult.Dropped(message)
}

internal fun IosBleTransport.promoteTemporaryL2capLinkIfPossible(
    identifier: String,
    resolvedHintPeerIdValue: String,
): Unit {
    val temporaryHintPeerIdValue =
        selectTemporaryPeerHintPromotion(
            TemporaryPeerHintPromotionRequest(
                temporaryHintPeerIdValue = peerBindings.temporaryHintForIdentifier(identifier),
                resolvedHintPeerIdValue = resolvedHintPeerIdValue,
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
    val hasPendingConnect = pendingConnectionsByHint.containsKey(peer.hintPeerId.value)
    val decision =
        evaluateRediscoveryWithoutLink(
            RediscoveryWithoutLinkDecisionRequest(
                transportMode = transportMode,
                hintPeerIdValue = peer.hintPeerId.value,
                temporaryHintPeerIdValue = peerBindings.temporaryHintForIdentifier(identifier),
                activeHintIds = activeLinksByHint.keys,
                hasActiveSideLink = hasActiveGattNotifyLink(peer.hintPeerId.value),
                hasPendingConnect = hasPendingConnect,
                rediscoveryLoggedWithoutLink = peer.rediscoveryLoggedWithoutLink,
            )
        )
    if (decision.shouldLogRediscoveryWithoutLink) {
        log(
            "scan rediscovered ${peer.hintPeerId.logSuffix()} with no active link " +
                "pendingConnect=$hasPendingConnect id=$identifier"
        )
    }
    peer.rediscoveryLoggedWithoutLink = decision.rediscoveryLoggedWithoutLink
}

internal fun IosBleTransport.activeLinkFor(peer: DiscoveredPeer): IosL2capLink? {
    val activeHint =
        resolveActivePeerHint(
            ActivePeerHintResolutionRequest(
                hintPeerIdValue = peer.hintPeerId.value,
                temporaryHintPeerIdValue =
                    peerBindings.temporaryHintForIdentifier(peer.peripheralIdentifier),
                activeHintIds = activeLinksByHint.keys,
            )
        ) ?: return null
    return activeLinksByHint[activeHint]
}

internal fun IosBleTransport.resolvePeer(peerId: PeerId): DiscoveredPeer? {
    return peerRegistry.resolve(peerId)
}

internal fun IosBleTransport.stopTransport(clearPeers: Boolean): Unit {
    reportLog(
        "stopTransport clearPeers=$clearPeers activeLinks=${activeLinksByHint.size} activeGatt=${activeGattNotifyLinksByHint.size} pending=${pendingConnectionsByHint.size}"
    )
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
    reportLog(
        "refreshDiscoveryState started=$started suspended=$discoverySuspended centralState=${centralManager?.state} peripheralState=${peripheralManager?.state}"
    )
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

internal fun NSData.toByteArray(): ByteArray {
    val lengthInt = length.toInt()
    if (lengthInt == NO_DATA_BYTES) {
        return ByteArray(0)
    }
    return ByteArray(lengthInt).also { output ->
        output.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}
