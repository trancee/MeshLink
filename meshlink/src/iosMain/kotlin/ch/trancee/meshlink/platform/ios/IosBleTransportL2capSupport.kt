@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.ActivePeerHintResolutionRequest
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.RediscoveryWithoutLinkDecisionRequest
import ch.trancee.meshlink.transport.TemporaryPeerHintPromotionRequest
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.evaluateRediscoveryWithoutLink
import ch.trancee.meshlink.transport.resolveActivePeerHint
import ch.trancee.meshlink.transport.selectTemporaryPeerHintPromotion
import ch.trancee.meshlink.transport.shouldLocalPeerInitiateL2capConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBPeripheralManagerConnectionLatencyLow
import platform.Foundation.NSProcessInfo

private const val MILLIS_PER_SECOND: Double = 1000.0

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

private fun monotonicNowMillis(): Long {
    return (NSProcessInfo.processInfo.systemUptime * MILLIS_PER_SECOND).toLong()
}
