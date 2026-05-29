package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.identity.toBytes
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.transport.shouldUseMixedPlatformGattNotifyBearer
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex

internal fun resolveMaximumPayloadBytesPerDelivery(
    localPlatformFamily: BleDiscoveryPlatformFamily,
    remotePlatformFamily: BleDiscoveryPlatformFamily,
    l2capMaxTransmitPacketSize: Int?,
): Int? {
    return if (
        shouldUseMixedPlatformGattNotifyBearer(
            localPlatformFamily = localPlatformFamily,
            remotePlatformFamily = remotePlatformFamily,
        )
    ) {
        GattNotifyClient.maximumPayloadBytesPerDelivery()
    } else {
        l2capMaxTransmitPacketSize
    }
}

internal class BleTransportAdapter(
    internal val context: Context,
    internal val appId: String,
    advertisementKeyHash: ByteArray,
) : BleTransport {
    internal val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 32)
    internal val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val localKeyHash: ByteArray = advertisementKeyHash.copyOf()
    internal val transportDebugLoggingEnabled: Boolean =
        Log.isLoggable(LOG_TAG, Log.DEBUG) ||
            ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    internal val peerBindings = PeerBindings()
    internal val peerRegistry = PeerRegistry(bindings = peerBindings)
    internal val activeLinksByHint: MutableMap<String, L2capLink> = linkedMapOf()
    internal val gattSideLinks =
        GattSideLinkCoordinator(
            dependencies =
                GattSideLinkCoordinatorDependencies(
                    deviceForPeer = { peer -> peerBindings.deviceFor(peer.deviceAddress) },
                    hasActiveL2capLink = { hintPeerIdValue ->
                        synchronized(activeLinksByHint) {
                            activeLinksByHint.containsKey(hintPeerIdValue)
                        }
                    },
                    setPresenceAnnounced = peerRegistry::setPresenceAnnounced,
                    onFrameReceived = ::enqueueInboundFrame,
                    onPeerLost = { peerId ->
                        mutableEvents.tryEmit(TransportEvent.PeerLost(peerId))
                    },
                    createClient = { peerHintId, device, onFrameReceived, onDisconnected ->
                        createGattSideLinkClient(
                            context = context,
                            appId = appId,
                            peerHintId = peerHintId,
                            device = device,
                            log = ::log,
                            onFrameReceived = onFrameReceived,
                            onDisconnected = onDisconnected,
                        )
                    },
                    log = ::log,
                )
        )
    internal val pendingConnectJobsByHint: MutableMap<String, Job> = linkedMapOf()
    internal val pendingConnectLock = Any()
    internal val transportMutex = Mutex()
    internal var inboundFrameQueue: InboundFrameQueue? = null

    internal var bluetoothAdapter: BluetoothAdapter? = null
    internal var advertiser: BluetoothLeAdvertiser? = null
    internal var scanner: BluetoothLeScanner? = null
    internal var l2capServerSocket: android.bluetooth.BluetoothServerSocket? = null
    internal var acceptLoopJob: Job? = null
    internal var started: Boolean = false
    internal val discoveryLifecycle =
        BleTransportDiscoveryLifecycle(
            appId = appId,
            localKeyHash = localKeyHash,
            handleScanResult = ::handleScanResult,
            ensurePermissionsGranted = ::ensurePermissionsGranted,
            log = ::log,
        )

    internal val currentDiscoveryPayload: BleDiscoveryPayload
        get() = discoveryLifecycle.currentDiscoveryPayload

    internal val currentPowerProfile: PowerProfile
        get() = discoveryLifecycle.currentPowerProfile

    override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

    override suspend fun start(): Unit {
        startTransport()
    }

    override suspend fun pause(): Unit {
        pauseTransport()
    }

    override suspend fun resume(): Unit {
        resumeTransport()
    }

    override suspend fun stop(): Unit {
        stopTransport()
    }

    override suspend fun updatePowerPolicy(policy: PowerPolicy): Unit {
        updatePowerPolicyState(policy)
    }

    override suspend fun setDiscoverySuspended(suspended: Boolean): Unit {
        setDiscoverySuspendedState(suspended)
    }

    override suspend fun promoteTemporaryPeer(
        temporaryPeerId: PeerId,
        canonicalPeerId: PeerId,
    ): Unit {
        if (
            !temporaryPeerId.value.startsWith(TEMPORARY_PEER_PREFIX) ||
                temporaryPeerId.value == canonicalPeerId.value
        ) {
            return
        }
        val activeLink =
            synchronized(activeLinksByHint) {
                val link =
                    activeLinksByHint.remove(temporaryPeerId.value) ?: return@synchronized null
                if (activeLinksByHint.containsKey(canonicalPeerId.value)) {
                    closeQuietly(link)
                    return@synchronized null
                }
                link.peerHintId = canonicalPeerId
                activeLinksByHint[canonicalPeerId.value] = link
                link
            } ?: return
        val remoteDevice = activeLink.remoteDevice
        val existingPeer = peerRegistry.peer(canonicalPeerId.value)
        val keyHash = canonicalPeerId.value.toBytes() ?: existingPeer?.keyHash
        peerBindings.removeTemporaryHint(remoteDevice.address)
        peerBindings.retainDevice(remoteDevice.address, remoteDevice)
        peerRegistry.removePeer(temporaryPeerId.value)
        if (keyHash != null && keyHash.size == BleDiscoveryPayload.KEY_HASH_SIZE_BYTES) {
            val update =
                peerRegistry.upsertDiscovery(
                    hintPeerId = canonicalPeerId,
                    discovery =
                        DiscoveredPeerDiscovery(
                            address = remoteDevice.address,
                            keyHash = keyHash,
                            l2capPsm = existingPeer?.l2capPsm ?: 0,
                            transportMode = TransportMode.L2CAP,
                            platformFamily =
                                existingPeer?.platformFamily ?: BleDiscoveryPlatformFamily.UNKNOWN,
                        ),
                )
            update.events.forEach(mutableEvents::tryEmit)
        }
        gattSideLinks.promoteHint(
            temporaryHintPeerIdValue = temporaryPeerId.value,
            canonicalHintPeerIdValue = canonicalPeerId.value,
        )
        log(
            "promoted temporary peer ${temporaryPeerId.value} -> ${canonicalPeerId.value} addr=${remoteDevice.address}"
        )
    }

    override fun maximumPayloadBytesPerDelivery(peerId: PeerId): Int? {
        val peer = resolvePeer(peerId) ?: return null
        return resolveMaximumPayloadBytesPerDelivery(
            localPlatformFamily = currentDiscoveryPayload.platformFamily,
            remotePlatformFamily = peer.platformFamily,
            l2capMaxTransmitPacketSize = activeLinkFor(peer)?.maxTransmitPacketSize,
        )
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        return dispatchSend(
            frame = frame,
            context = SendDispatchContext(transportStarted = started),
            dependencies =
                SendDispatchDependencies(
                    sendToResolvedPeerOrNull = {
                        val peer = resolvePeer(frame.peerId) ?: return@SendDispatchDependencies null
                        tryPreferredGattSend(peer, frame)
                            ?: sendViaL2capWhenReady(
                                frame = frame,
                                context =
                                    L2capSendContext(
                                        hintPeerId = peer.hintPeerId,
                                        transportMode = peer.transportMode,
                                        advertisedL2capPsm = peer.l2capPsm,
                                    ),
                                dependencies =
                                    L2capSendDependencies(
                                        currentLink = {
                                            activeLinkFor(peer)?.let { link ->
                                                object : L2capSendLink {
                                                    override suspend fun send(
                                                        frame: OutboundFrame
                                                    ): TransportSendResult {
                                                        return sendViaConnectedLink(
                                                            frame = frame,
                                                            link = link,
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        shouldInitiateL2cap = {
                                            shouldInitiateL2cap(peer.keyHash, peer.platformFamily)
                                        },
                                        triggerConnectIfNeeded = { connectIfNeeded(peer) },
                                        log = ::log,
                                    ),
                            )
                    },
                    sendToTemporaryLinkOrNull = {
                        activeLinksByHint[frame.peerId.value]?.let { temporaryLink ->
                            sendViaConnectedLink(frame = frame, link = temporaryLink)
                        }
                    },
                    log = ::log,
                ),
        )
    }

    private suspend fun tryPreferredGattSend(
        peer: DiscoveredPeer,
        frame: OutboundFrame,
    ): TransportSendResult? {
        return sendViaPreferredGattSideLinkOrNull(
            frame = frame,
            context =
                PreferredGattSendContext(
                    hintPeerId = peer.hintPeerId,
                    localPlatformFamily = currentDiscoveryPayload.platformFamily,
                    remotePlatformFamily = peer.platformFamily,
                ),
            dependencies =
                PreferredGattSendDependencies(
                    ensureSideLink = {
                        gattSideLinks.ensureStarted(
                            peer = peer,
                            localPlatformFamily = currentDiscoveryPayload.platformFamily,
                        )
                    },
                    currentClient = { gattSideLinks.currentClient(peer.hintPeerId.value) },
                    restartSideLink = { reason ->
                        gattSideLinks.restart(
                            peer = peer,
                            localPlatformFamily = currentDiscoveryPayload.platformFamily,
                            reason = reason,
                        )
                    },
                    log = ::log,
                ),
        )
    }

    internal fun enqueueInboundFrame(peerId: PeerId, payload: ByteArray): Boolean {
        val queue = inboundFrameQueue
        if (queue == null) {
            log(
                "closing GATT side link ${peerId.value.takeLast(6)}: inbound frame queue unavailable"
            )
            return false
        }
        val enqueued = queue.enqueue(peerId = peerId, payload = payload)
        if (!enqueued) {
            log("closing GATT side link ${peerId.value.takeLast(6)}: inbound frame queue overflow")
        }
        return enqueued
    }

    internal fun createInboundFrameQueue(): InboundFrameQueue {
        return InboundFrameQueue(scope = coroutineScope) { event -> mutableEvents.emit(event) }
    }

    internal fun closeQuietly(closeable: Closeable?): Unit {
        runCatching { closeable?.close() }
    }

    internal fun closeQuietly(socket: android.bluetooth.BluetoothSocket?): Unit {
        runCatching { socket?.close() }
    }

    internal fun logEmptyFrameObservation(
        peerId: PeerId,
        readBytes: Int,
        appendResult: L2capFrameBuffer.AppendResult,
        observation: L2capFrameBuffer.DecodedFrameObservation?,
    ): Unit {
        val peerSuffix = peerId.value.takeLast(6)
        val headerHex = observation?.headerHex ?: "unknown"
        val frameIndex = observation?.frameIndexInAppend ?: -1
        val readOffset = observation?.readOffsetBeforeFrame ?: -1
        val frameEndOffset = observation?.frameEndOffset ?: -1
        val totalBuffered = observation?.totalBufferedBytesAfterAppend ?: -1
        val remainingBuffered = observation?.remainingBufferedBytesAfterFrame ?: -1
        val headerFromPriorBuffer = observation?.headerStartsInPreviouslyBufferedBytes ?: false
        val frameReachedCurrentChunk = observation?.frameEndsBeyondPreviouslyBufferedBytes ?: false
        log(
            "ignoring empty frame from $peerSuffix readBytes=$readBytes frameIndex=$frameIndex decodedFrames=${appendResult.frames.size} headerHex=$headerHex readOffset=$readOffset frameEndOffset=$frameEndOffset bufferedBeforeAppend=${appendResult.bufferedBytesBeforeAppend} totalBufferedAfterAppend=$totalBuffered pendingAfterAppend=${appendResult.pendingBytesAfterAppend} remainingBufferedAfterFrame=$remainingBuffered headerFromPriorBuffer=$headerFromPriorBuffer frameReachedCurrentChunk=$frameReachedCurrentChunk chunkPrefixHex=${appendResult.appendedChunkPrefixHex} chunkSuffixHex=${appendResult.appendedChunkSuffixHex}"
        )
    }

    internal fun log(message: () -> String): Unit {
        if (transportDebugLoggingEnabled) {
            Log.d(LOG_TAG, message())
        }
    }

    internal fun log(message: String): Unit {
        log { message }
    }

    private companion object {
        private const val LOG_TAG: String = "MeshLinkTransport"
    }
}
