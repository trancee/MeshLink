package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
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

internal fun resolveAndroidMaximumPayloadBytesPerDelivery(
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
        AndroidGattNotifyClient.maximumPayloadBytesPerDelivery()
    } else {
        l2capMaxTransmitPacketSize
    }
}

internal class AndroidBleTransport(
    internal val context: Context,
    private val appId: String,
    advertisementKeyHash: ByteArray,
) : BleTransport {
    internal val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 32)
    internal val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val localKeyHash: ByteArray = advertisementKeyHash.copyOf()
    internal val peerBindings = AndroidPeerBindings()
    internal val peerRegistry = AndroidPeerRegistry(bindings = peerBindings)
    internal val activeLinksByHint: MutableMap<String, AndroidL2capLink> = linkedMapOf()
    internal val gattSideLinks =
        AndroidGattSideLinkCoordinator(
            dependencies =
                AndroidGattSideLinkCoordinatorDependencies(
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
                        createAndroidGattSideLinkClient(
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
    internal var inboundFrameQueue: AndroidInboundFrameQueue? = null

    internal var bluetoothAdapter: BluetoothAdapter? = null
    internal var advertiser: BluetoothLeAdvertiser? = null
    internal var scanner: BluetoothLeScanner? = null
    internal var l2capServerSocket: android.bluetooth.BluetoothServerSocket? = null
    internal var acceptLoopJob: Job? = null
    internal var started: Boolean = false
    internal var discoverySuspended: Boolean = false
    internal var currentPowerProfile: AndroidPowerProfile = AndroidPowerMonitor.defaultProfile()
    internal var currentDiscoveryPayload: BleDiscoveryPayload =
        buildAndroidDiscoveryPayload(
            appId = appId,
            localKeyHash = localKeyHash,
            currentPowerProfile = currentPowerProfile,
            l2capPsm = 0u,
        )

    override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

    internal val advertiseCallback =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                log(
                    "advertising started mode=${settingsInEffect.mode} tx=${settingsInEffect.txPowerLevel} connectable=${settingsInEffect.isConnectable}"
                )
            }

            override fun onStartFailure(errorCode: Int) {
                log("advertising failed errorCode=$errorCode")
            }
        }

    internal val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handleScanResult)
            }
        }

    override suspend fun start(): Unit {
        ensurePermissionsGranted()
        val bluetoothManager =
            try {
                context.getSystemService(BluetoothManager::class.java)
                    ?: error("BluetoothManager is unavailable")
            } catch (exception: SecurityException) {
                throw androidPermissionDenied(exception)
            }
        val adapter =
            try {
                bluetoothManager.adapter ?: error("BluetoothAdapter is unavailable")
            } catch (exception: SecurityException) {
                throw androidPermissionDenied(exception)
            }
        bluetoothAdapter = adapter
        advertiser =
            try {
                adapter.bluetoothLeAdvertiser
            } catch (exception: SecurityException) {
                throw androidPermissionDenied(exception)
            }
        scanner =
            try {
                adapter.bluetoothLeScanner
            } catch (exception: SecurityException) {
                throw androidPermissionDenied(exception)
            }

        val serverSocket =
            runCatching {
                    AndroidL2capSocketFactory.listenInsecure(adapter) { error ->
                        log(
                            "explicit insecure L2CAP server socket fallback: ${error.message.orEmpty()}"
                        )
                    }
                }
                .onFailure { error ->
                    log("L2CAP server socket unavailable: ${error.message.orEmpty()}")
                }
                .getOrNull()
        l2capServerSocket = serverSocket
        currentDiscoveryPayload =
            buildAndroidDiscoveryPayload(
                appId = appId,
                localKeyHash = localKeyHash,
                currentPowerProfile = currentPowerProfile,
                l2capPsm = (serverSocket?.psm ?: 0).toUByte(),
            )
        log("start() with l2capPsm=${currentDiscoveryPayload.l2capPsm}")
        serverSocket?.let(::launchAcceptLoop)

        inboundFrameQueue?.close()
        inboundFrameQueue = createInboundFrameQueue()

        started = true
        refreshDiscoveryState()
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
        currentDiscoveryPayload =
            buildAndroidDiscoveryPayload(
                appId = appId,
                localKeyHash = localKeyHash,
                currentPowerProfile = currentPowerProfile,
                l2capPsm = currentDiscoveryPayload.l2capPsm,
            )
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
        return resolveAndroidMaximumPayloadBytesPerDelivery(
            localPlatformFamily = currentDiscoveryPayload.platformFamily,
            remotePlatformFamily = peer.platformFamily,
            l2capMaxTransmitPacketSize = activeLinkFor(peer)?.maxTransmitPacketSize,
        )
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        return dispatchAndroidSend(
            frame = frame,
            context = AndroidSendDispatchContext(transportStarted = started),
            dependencies =
                AndroidSendDispatchDependencies(
                    sendToResolvedPeerOrNull = {
                        val peer =
                            resolvePeer(frame.peerId) ?: return@AndroidSendDispatchDependencies null
                        tryPreferredGattSend(peer, frame)
                            ?: sendViaAndroidL2capWhenReady(
                                frame = frame,
                                context =
                                    AndroidL2capSendContext(
                                        hintPeerId = peer.hintPeerId,
                                        transportMode = peer.transportMode,
                                        advertisedL2capPsm = peer.l2capPsm,
                                    ),
                                dependencies =
                                    AndroidL2capSendDependencies(
                                        currentLink = {
                                            activeLinkFor(peer)?.let { link ->
                                                object : AndroidL2capSendLink {
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
                AndroidPreferredGattSendContext(
                    hintPeerId = peer.hintPeerId,
                    localPlatformFamily = currentDiscoveryPayload.platformFamily,
                    remotePlatformFamily = peer.platformFamily,
                ),
            dependencies =
                AndroidPreferredGattSendDependencies(
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

    private fun enqueueInboundFrame(peerId: PeerId, payload: ByteArray): Boolean {
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

    private fun createInboundFrameQueue(): AndroidInboundFrameQueue {
        return AndroidInboundFrameQueue(scope = coroutineScope) { event ->
            mutableEvents.emit(event)
        }
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
        appendResult: AndroidL2capFrameBuffer.AppendResult,
        observation: AndroidL2capFrameBuffer.DecodedFrameObservation?,
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

    internal fun log(message: String): Unit {
        Log.d(LOG_TAG, message)
    }

    private companion object {
        private const val LOG_TAG: String = "MeshLinkTransport"
    }
}
