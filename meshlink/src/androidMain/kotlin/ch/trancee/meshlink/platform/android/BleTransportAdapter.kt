package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.DirectWireFrame
import ch.trancee.meshlink.engine.gattDataBearerDecisionLogLine
import ch.trancee.meshlink.engine.gattDataBearerResultLogLine
import ch.trancee.meshlink.engine.resolveGattDataBearerMode
import ch.trancee.meshlink.identity.toBytes
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.GattDataBearerMode
import ch.trancee.meshlink.transport.L2capReconnectGuard
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.transport.shouldUseMixedPlatformGattNotifyBearer
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
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

private const val AUTOMATION_ENABLED_PREF_KEY = "automation:enabled"
private const val AUTOMATION_TARGET_PEER_ID_PREF_KEY = "automation:targetPeerId"

internal class BleTransportAdapter(
    internal val context: Context,
    internal val appId: String,
    advertisementKeyHash: ByteArray,
) : BleTransport {
    internal val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 32)
    internal val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val localKeyHash: ByteArray = advertisementKeyHash.copyOf()
    private val automationPreferences =
        context.getSharedPreferences("meshlink-$appId", Context.MODE_PRIVATE)
    internal val automationEnabled: Boolean =
        automationPreferences.getBoolean(AUTOMATION_ENABLED_PREF_KEY, false)
    internal val automationTargetPeerId: String? =
        if (automationEnabled) {
            automationPreferences
                .getString(AUTOMATION_TARGET_PEER_ID_PREF_KEY, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
    internal val transportDebugLoggingEnabled: Boolean =
        Log.isLoggable(LOG_TAG, Log.DEBUG) ||
            ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    internal val peerBindings = PeerBindings()
    internal val peerRegistry = PeerRegistry(bindings = peerBindings)

    init {
        log(
            "automation mode enabled=${automationEnabled} target peer id=${automationTargetPeerId ?: "none"}"
        )
        peerBindings.attachRebindLogger { address, previousHint, newHint ->
            log(
                "peer binding conflict addr=$address previousPeerId=$previousHint newPeerId=$newHint"
            )
        }
    }

    internal val linkRegistry = BleTransportLinkRegistry<L2capLink>(bindings = peerBindings)
    internal val l2capReconnectGuard = L2capReconnectGuard()
    internal val gattSideLinks =
        GattSideLinkCoordinator(
            dependencies =
                GattSideLinkCoordinatorDependencies(
                    localHintPeerId = PeerId(localKeyHash.toHexString()),
                    deviceForPeer = { peer -> peerBindings.deviceFor(peer.deviceAddress) },
                    hasActiveL2capLink = linkRegistry::hasActiveLink,
                    setPresenceAnnounced = peerRegistry::setPresenceAnnounced,
                    onFrameReceived = ::enqueueInboundFrame,
                    onPeerLost = { peerId ->
                        mutableEvents.tryEmit(TransportEvent.PeerLost(peerId))
                    },
                    createClient = {
                        peerHintId,
                        localHintPeerId,
                        device,
                        onFrameReceived,
                        onDisconnected ->
                        createGattSideLinkClient(
                            context = context,
                            appId = appId,
                            peerHintId = peerHintId,
                            localHintPeerId = localHintPeerId,
                            device = device,
                            log = ::log,
                            onFrameReceived = onFrameReceived,
                            onDisconnected = onDisconnected,
                        )
                    },
                    log = ::log,
                )
        )
    internal var gattNotifyServer: GattNotifyServer? = null
    internal val transportMutex = Mutex()
    internal var inboundFrameQueue: InboundFrameQueue? = null

    internal var bluetoothAdapter: BluetoothAdapter? = null
    internal var advertiser: BluetoothLeAdvertiser? = null
    internal var scanner: BluetoothLeScanner? = null
    internal var l2capServerSocket: android.bluetooth.BluetoothServerSocket? = null
    internal var acceptLoopJob: Job? = null
    internal var started: Boolean = false
    internal val foreignScanIgnoredCount = AtomicInteger(0)
    internal val scanResultCount = AtomicInteger(0)
    internal val scanParseSkippedCount = AtomicInteger(0)
    internal val scanTargetMismatchCount = AtomicInteger(0)
    internal val scanAcceptedCount = AtomicInteger(0)
    internal val discoveryLifecycle =
        BleTransportDiscoveryLifecycle(
            appId = appId,
            localKeyHash = localKeyHash,
            handleScanResult = ::handleScanResult,
            ensurePermissionsGranted = ::ensurePermissionsGranted,
            foreignScanIgnoredCount = { foreignScanIgnoredCount.get() },
            log = ::log,
            scheduleAdvertiseRetry = { delayMillis, retry ->
                coroutineScope.launch {
                    delay(delayMillis)
                    retry()
                }
            },
            onAdvertiseFailed = { errorCode, errorName, willRetry, attempt ->
                mutableEvents.tryEmit(
                    TransportEvent.AdvertiseFailed(
                        errorCode = errorCode,
                        errorName = errorName,
                        willRetry = willRetry,
                        attempt = attempt,
                    )
                )
            },
            scheduleScanRetry = { delayMillis, retry ->
                coroutineScope.launch {
                    delay(delayMillis)
                    retry()
                }
            },
            onScanFailed = { errorCode, errorName, willRetry, attempt ->
                mutableEvents.tryEmit(
                    TransportEvent.ScanFailed(
                        errorCode = errorCode,
                        errorName = errorName,
                        willRetry = willRetry,
                        attempt = attempt,
                    )
                )
            },
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
            linkRegistry.rebindActiveLink(
                fromHintPeerIdValue = temporaryPeerId.value,
                toHintPeerIdValue = canonicalPeerId.value,
                updateHint = { link, promotedHintPeerId -> link.peerHintId = promotedHintPeerId },
                closeLink = ::closeQuietly,
            ) ?: return
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
                        sendToPeerUsingBearerPolicy(peer, frame)
                    },
                    sendToTemporaryLinkOrNull = {
                        linkRegistry.activeLink(frame.peerId.value)?.let { temporaryLink ->
                            sendViaConnectedLink(frame = frame, link = temporaryLink)
                        }
                    },
                    log = ::log,
                ),
        )
    }

    /**
     * Resolves the outbound frame's bearer mode (see
     * [ch.trancee.meshlink.engine.resolveGattDataBearerMode]) and routes it to exactly one bearer:
     * - [GattDataBearerMode.GATT_ONLY] (handshake/control frames): GATT is attempted first (with
     *   its own readiness wait), falling back to the blocking L2CAP connect-and-wait path only if
     *   GATT is genuinely unavailable.
     * - [GattDataBearerMode.L2CAP_PREFERRED_WITH_GATT_FALLBACK] (data frames): an already-connected
     *   L2CAP link is used immediately (non-blocking check - never waits for or initiates a new
     *   L2CAP connection here, since discovery-driven connects already handle that independently,
     *   see BleTransportAdapterScanSupport.kt). Otherwise GATT is used, falling back further to the
     *   blocking L2CAP connect-and-wait path only if GATT is also unavailable.
     */
    private suspend fun sendToPeerUsingBearerPolicy(
        peer: DiscoveredPeer,
        frame: OutboundFrame,
    ): TransportSendResult? {
        val directFrame = runCatching { DirectWireFrame.decode(frame.payload) }.getOrNull()
        val bearerMode = resolveGattDataBearerMode(directFrame = directFrame)
        val l2capContext =
            L2capSendContext(
                hintPeerId = peer.hintPeerId,
                transportMode = peer.transportMode,
                advertisedL2capPsm = peer.l2capPsm,
            )
        val l2capDependencies =
            L2capSendDependencies(
                currentLink = {
                    activeLinkFor(peer)?.let { link ->
                        object : L2capSendLink {
                            override suspend fun send(frame: OutboundFrame): TransportSendResult {
                                return sendViaConnectedLink(frame = frame, link = link)
                            }
                        }
                    }
                },
                shouldInitiateL2cap = { shouldInitiateL2cap(peer.keyHash, peer.platformFamily) },
                triggerConnectIfNeeded = { connectIfNeeded(peer) },
                log = ::log,
            )
        val readyLink = l2capDependencies.currentLink()
        log(
            gattDataBearerDecisionLogLine(
                directFrame = directFrame,
                bearerMode = bearerMode,
                l2capLinkAlreadyConnected = readyLink != null,
            )
        )

        return when (bearerMode) {
            GattDataBearerMode.GATT_ONLY ->
                tryPreferredGattSend(peer, frame)
                    ?.also { log(gattDataBearerResultLogLine("GATT")) }
                    ?: sendViaL2capWhenReady(
                            frame = frame,
                            context = l2capContext,
                            dependencies = l2capDependencies,
                        )
                        .also { log(gattDataBearerResultLogLine("L2CAP")) }
            GattDataBearerMode.L2CAP_PREFERRED_WITH_GATT_FALLBACK -> {
                if (readyLink != null) {
                    readyLink.send(frame).also { log(gattDataBearerResultLogLine("L2CAP")) }
                } else {
                    tryPreferredGattSend(peer, frame)
                        ?.also { log(gattDataBearerResultLogLine("GATT")) }
                        ?: sendViaL2capWhenReady(
                                frame = frame,
                                context = l2capContext,
                                dependencies = l2capDependencies,
                            )
                            .also { log(gattDataBearerResultLogLine("L2CAP")) }
                }
            }
        }
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

    internal fun registerProvisionalGattPeer(peerId: PeerId, address: String): Unit {
        registerGattPeer(peerId = peerId, address = address, announcePresence = false)
        log("registered provisional GATT peer ${peerId.value.takeLast(6)} addr=$address")
    }

    /**
     * Registers/refreshes a [DiscoveredPeer] entry for a peer id claimed via an inbound GATT
     * LinkIdentity announcement (see [resolveIncomingGattFrameDisposition]). Unlike
     * [registerProvisionalGattPeer]'s synthetic temporary-peer fallback, this uses the peer's real
     * claimed id and announces presence, since without it a device that only ever accepts inbound
     * GATT connections (never independently scan-discovers its peer) would have no route for
     * outbound replies - resolvePeer() would keep failing with "peer not discovered" - and the
     * guided reference UI would keep reporting a stalled/empty peer list even once the link is
     * fully bound and writable.
     */
    internal fun registerClaimedGattPeer(peerId: PeerId, address: String): Unit {
        registerGattPeer(peerId = peerId, address = address, announcePresence = true)
        log(
            "registered claimed GATT peer ${peerId.value.takeLast(6)} addr=$address via LinkIdentity"
        )
    }

    private fun registerGattPeer(peerId: PeerId, address: String, announcePresence: Boolean): Unit {
        val zeroKeyHash = ByteArray(BleDiscoveryPayload.KEY_HASH_SIZE_BYTES)
        val update =
            peerRegistry.upsertDiscovery(
                hintPeerId = peerId,
                discovery =
                    DiscoveredPeerDiscovery(
                        address = address,
                        keyHash = zeroKeyHash,
                        l2capPsm = 0,
                        transportMode = TransportMode.GATT,
                        platformFamily = currentDiscoveryPayload.platformFamily,
                    ),
                announcePresence = announcePresence,
            )
        peerBindings.bindHintToAddress(address, update.peer.hintPeerId.value)
        update.events.forEach(mutableEvents::tryEmit)
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
        Log.i(LOG_TAG, message())
    }

    internal fun log(message: String): Unit {
        log { message }
    }

    private companion object {
        private const val LOG_TAG: String = "MeshLinkReferenceAutomation"
    }
}
