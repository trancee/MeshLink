@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.ActivePeerHintResolutionRequest
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.L2CAP_KEEPALIVE_INTERVAL_MILLIS
import ch.trancee.meshlink.transport.RediscoveryWithoutLinkDecisionRequest
import ch.trancee.meshlink.transport.TemporaryPeerHintPromotionRequest
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.evaluateRediscoveryWithoutLink
import ch.trancee.meshlink.transport.resolveActivePeerHint
import ch.trancee.meshlink.transport.selectTemporaryPeerHintPromotion
import ch.trancee.meshlink.transport.shouldLocalPeerInitiateL2capConnection
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import ch.trancee.meshlink.wire.decodeIsKeepAlive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBPeripheralManagerConnectionLatencyLow
import platform.Foundation.NSProcessInfo

private const val MILLIS_PER_SECOND: Double = 1000.0

internal fun BleTransportAdapter.promoteTemporaryL2capLinkIfPossible(
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

internal fun BleTransportAdapter.registerConnectedChannel(
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
    startConnectedChannelHeartbeatLoop(link)
}

internal fun BleTransportAdapter.createConnectedChannelLink(
    hintPeerId: PeerId,
    peripheralIdentifier: String,
    channel: CBL2CAPChannel,
    connectedCentral: CBCentral?,
): L2capLink {
    var createdLink: L2capLink? = null
    return L2capLink(
            hintPeerId = hintPeerId,
            peripheralIdentifier = peripheralIdentifier,
            channel = channel,
            dependencies =
                L2capLinkDependencies(
                    incomingFrames = L2capFrameBuffer(),
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

internal fun BleTransportAdapter.startConnectedChannelWriteLoop(link: L2capLink): Unit {
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

internal fun BleTransportAdapter.startConnectedChannelReadLoop(link: L2capLink): Unit {
    link.readLoopJob = coroutineScope.launch {
        try {
            link.runReadLoop { payload ->
                if (decodeIsKeepAlive(payload)) {
                    return@runReadLoop
                }
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

/**
 * Periodically enqueues an empty [WireFrame.KeepAlive] control frame on an otherwise idle L2CAP
 * link so the peer's platform BLE stack does not tear the channel down for inactivity (see
 * [L2CAP_KEEPALIVE_INTERVAL_MILLIS] for the rationale). The frame is consumed at the transport
 * layer by the read loop above and never surfaces as a [TransportEvent.FrameReceived].
 */
internal fun BleTransportAdapter.startConnectedChannelHeartbeatLoop(link: L2capLink): Unit {
    link.heartbeatJob = coroutineScope.launch {
        val keepAliveFrame = WireCodec.encode(WireFrame.KeepAlive())
        while (true) {
            delay(L2CAP_KEEPALIVE_INTERVAL_MILLIS)
            if (!link.enqueue(keepAliveFrame)) {
                closeLink(hintPeer = link.hintPeerId.value, reason = "keepalive enqueue failed")
                return@launch
            }
        }
    }
}

internal fun BleTransportAdapter.connectIfNeeded(peer: DiscoveredPeer): Unit {
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

internal fun BleTransportAdapter.shouldInitiateL2cap(
    remoteKeyHash: ByteArray,
    remotePlatformFamily: BleDiscoveryPlatformFamily,
): Boolean {
    // See FORCE_L2CAP_INITIATOR_ENV's doc comment (BleTransportAdapterLogging.kt): diagnostic-only
    // escape hatch so physical connection-admission-control validation isn't at the mercy of the
    // normal key-hash tie-break below.
    if (forceL2capInitiatorEnabled) {
        return true
    }
    return shouldLocalPeerInitiateL2capConnection(
        localKeyHash = localKeyHash,
        localPlatformFamily = currentDiscoveryPayload.platformFamily,
        remoteKeyHash = remoteKeyHash,
        remotePlatformFamily = remotePlatformFamily,
    )
}

internal fun BleTransportAdapter.maybeLogRediscoveryWithoutLink(
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

internal fun BleTransportAdapter.activeLinkFor(peer: DiscoveredPeer): L2capLink? {
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

internal fun BleTransportAdapter.closeLink(hintPeer: String, reason: String): Unit {
    val link = activeLinksByHint.remove(hintPeer) ?: return
    peerRegistry.setRediscoveryLoggedWithoutLink(hintPeer, logged = false)
    reportLog(
        "closing L2CAP link ${hintPeer.logSuffix()}: $reason " +
            "discoveredPeerRetained=${peerRegistry.peer(hintPeer) != null} " +
            "pendingConnect=${pendingConnectionsByHint.containsKey(hintPeer)}"
    )
    link.readLoopJob?.cancel()
    link.writeLoopJob?.cancel()
    link.heartbeatJob?.cancel()
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

internal fun BleTransportAdapter.requestLowConnectionLatency(
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

internal class L2capLinkDependencies(
    val incomingFrames: L2capFrameBuffer,
    val telemetryEnabled: Boolean,
    val telemetryLogger: (String) -> Unit,
    val promoteActiveWriteLatency: () -> Unit,
    val nowMillis: () -> Long,
)

internal class L2capLink(
    var hintPeerId: PeerId,
    val peripheralIdentifier: String,
    channel: CBL2CAPChannel,
    dependencies: L2capLinkDependencies,
) {
    private val incomingFrames: L2capFrameBuffer = dependencies.incomingFrames
    private val telemetryEnabled: Boolean = dependencies.telemetryEnabled
    private val telemetryLogger: (String) -> Unit = dependencies.telemetryLogger
    private val nowMillis: () -> Long = dependencies.nowMillis
    private val inputStream = checkNotNull(channel.inputStream).apply { open() }
    private val outputStream = checkNotNull(channel.outputStream).apply { open() }
    private val inputReadiness = StreamReadinessBinding.forInputStream(inputStream)
    private val outputReadiness = StreamReadinessBinding.forOutputStream(outputStream)
    private val readPump =
        L2capReadPump(
            inputStream = inputStream,
            frameBuffer = incomingFrames,
            dependencies =
                L2capReadPumpDependencies(
                    hintPeerIdProvider = { hintPeerId },
                    telemetryEnabled = telemetryEnabled,
                    telemetryLogger = telemetryLogger,
                    timing =
                        L2capReadTiming(
                            nowMillis = nowMillis,
                            activePollIntervalMs = ACTIVE_STREAM_POLL_INTERVAL_MS,
                            idlePollIntervalMs = IDLE_STREAM_POLL_INTERVAL_MS,
                        ),
                    awaitReadable = { inputReadiness.await() },
                ),
        )
    private val writePump =
        L2capWritePump(
            outputStream = outputStream,
            frameCodec = incomingFrames,
            dependencies =
                L2capWritePumpDependencies(
                    hintPeerIdProvider = { hintPeerId },
                    telemetryEnabled = telemetryEnabled,
                    telemetryLogger = telemetryLogger,
                    promoteActiveWriteLatency = dependencies.promoteActiveWriteLatency,
                    timing =
                        L2capWriteTiming(
                            nowMillis = nowMillis,
                            activePollIntervalMs = ACTIVE_STREAM_POLL_INTERVAL_MS,
                        ),
                    awaitWritable = { timeoutMs -> outputReadiness.await(timeoutMs) },
                ),
        )
    var readLoopJob: Job? = null
    var writeLoopJob: Job? = null
    var heartbeatJob: Job? = null

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
        inputReadiness.close()
        outputReadiness.close()
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
