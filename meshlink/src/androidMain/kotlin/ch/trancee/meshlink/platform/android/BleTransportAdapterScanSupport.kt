package ch.trancee.meshlink.platform.android

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.RediscoveryWithoutLinkDecisionRequest
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.evaluateRediscoveryWithoutLink
import ch.trancee.meshlink.transport.shouldLocalPeerInitiateL2capConnection

internal fun BleTransportAdapter.handleScanResult(result: ScanResult): Unit {
    val scanNumber = scanResultCount.incrementAndGet()
    log(
        "scan result#$scanNumber addr=${result.device.address} rssi=${result.rssi} uuids=${result.scanRecord?.serviceUuids?.size ?: 0} targetPeerId=${automationTargetPeerId ?: "none"} knownPeers=${peerRegistry.discoveredPeerCount()} foreignIgnored=${foreignScanIgnoredCount.get()} accepted=${scanAcceptedCount.get()} parseSkipped=${scanParseSkippedCount.get()} targetMismatch=${scanTargetMismatchCount.get()}"
    )
    val discovery =
        parseDiscoveryScanResultOrNull(
            serviceUuids =
                result.scanRecord?.serviceUuids?.map { parcelUuid -> parcelUuid.uuid.toString() },
            deviceAddress = result.device.address,
            localMeshHash = currentDiscoveryPayload.meshHash,
            localKeyHash = localKeyHash,
            onForeignScanIgnored = { foreignScanIgnoredCount.incrementAndGet() },
            log = ::log,
        )
            ?: run {
                scanParseSkippedCount.incrementAndGet()
                log(
                    "scan discovery skipped addr=${result.device.address} rssi=${result.rssi} targetPeerId=${automationTargetPeerId ?: "none"} knownPeers=${peerRegistry.discoveredPeerCount()} foreignIgnored=${foreignScanIgnoredCount.get()} parseSkipped=${scanParseSkippedCount.get()}"
                )
                return
            }

    val targetPeerId = automationTargetPeerId
    if (targetPeerId != null && discovery.hintPeerId.value != targetPeerId) {
        scanTargetMismatchCount.incrementAndGet()
        log(
            "scan discovery target mismatch addr=${result.device.address} rssi=${result.rssi} targetPeerId=$targetPeerId peerId=${discovery.hintPeerId.value} mode=${discovery.transportMode} psm=${discovery.payload.l2capPsm} platform=${discovery.payload.platformFamily} targetMismatch=${scanTargetMismatchCount.get()}"
        )
        return
    }

    if (discovery.transportMode == TransportMode.L2CAP) {
        promoteTemporaryLink(address = result.device.address, hintPeerId = discovery.hintPeerId)
    }
    peerBindings.retainDevice(result.device.address, result.device)
    val discoveredPeer = peerRegistry.peer(discovery.hintPeerId.value)
    val sameTransportAdvertisement =
        discoveredPeer != null &&
            discoveredPeer.deviceAddress == result.device.address &&
            discoveredPeer.l2capPsm == discovery.payload.l2capPsm.toInt() &&
            discoveredPeer.transportMode == discovery.transportMode
    if (!sameTransportAdvertisement) {
        log(
            "scan found ${discovery.hintPeerId.value.takeLast(6)} mode=${discovery.transportMode} psm=${discovery.payload.l2capPsm} platform=${discovery.payload.platformFamily} addr=${result.device.address}"
        )
    }
    val update =
        peerRegistry.upsertDiscovery(
            hintPeerId = discovery.hintPeerId,
            discovery =
                DiscoveredPeerDiscovery(
                    address = result.device.address,
                    keyHash = discovery.payload.keyHash,
                    l2capPsm = discovery.payload.l2capPsm.toInt(),
                    transportMode = discovery.transportMode,
                    platformFamily = discovery.payload.platformFamily,
                ),
        )
    scanAcceptedCount.incrementAndGet()
    if (update.events.isEmpty()) {
        log(
            "scan accepted ${discovery.hintPeerId.value.takeLast(6)} mode=${discovery.transportMode} emitted=no-events peerId=${discovery.hintPeerId.value} addr=${result.device.address} knownPeers=${peerRegistry.discoveredPeerCount()} targetPeerId=${automationTargetPeerId ?: "none"} accepted=${scanAcceptedCount.get()}"
        )
    } else {
        log(
            "scan accepted ${discovery.hintPeerId.value.takeLast(6)} mode=${discovery.transportMode} emitted=${update.events.size} peerId=${discovery.hintPeerId.value} addr=${result.device.address} knownPeers=${peerRegistry.discoveredPeerCount()} targetPeerId=${automationTargetPeerId ?: "none"} accepted=${scanAcceptedCount.get()}"
        )
    }
    update.events.forEach(mutableEvents::tryEmit)
    val resolvedPeer = update.peer
    maybeLogRediscoveryWithoutLink(
        peer = resolvedPeer,
        transportMode = discovery.transportMode,
        address = result.device.address,
    )
    gattSideLinks.ensureStarted(
        peer = resolvedPeer,
        localPlatformFamily = currentDiscoveryPayload.platformFamily,
    )
    if (
        shouldConnectAfterDiscovery(
            transportMode = discovery.transportMode,
            localPlatformFamily = currentDiscoveryPayload.platformFamily,
            remotePlatformFamily = resolvedPeer.platformFamily,
            localL2capClientSocketsSupported = supportsL2capClientSockets(),
            shouldInitiateL2cap =
                shouldInitiateL2cap(discovery.payload.keyHash, discovery.payload.platformFamily),
            gattSideLinkReady = gattSideLinks.hasReadyLink(resolvedPeer.hintPeerId.value),
        )
    ) {
        connectIfNeeded(resolvedPeer)
    }
}

internal fun BleTransportAdapter.shouldInitiateL2cap(
    remoteKeyHash: ByteArray,
    remotePlatformFamily: BleDiscoveryPlatformFamily,
): Boolean {
    return supportsL2capClientSockets() &&
        shouldLocalPeerInitiateL2capConnection(
            localKeyHash = localKeyHash,
            localPlatformFamily = currentDiscoveryPayload.platformFamily,
            remoteKeyHash = remoteKeyHash,
            remotePlatformFamily = remotePlatformFamily,
        )
}

internal fun BleTransportAdapter.maybeLogRediscoveryWithoutLink(
    peer: DiscoveredPeer,
    transportMode: TransportMode,
    address: String,
): Unit {
    val hasPendingConnect = hasPendingConnect(peer.hintPeerId.value)
    val decision =
        evaluateRediscoveryWithoutLink(
            RediscoveryWithoutLinkDecisionRequest(
                transportMode = transportMode,
                hintPeerIdValue = peer.hintPeerId.value,
                temporaryHintPeerIdValue = peerBindings.temporaryHintForAddress(address),
                activeHintIds = linkRegistry.activeHintIds(),
                hasActiveSideLink = gattSideLinks.hasReadyLink(peer.hintPeerId.value),
                hasPendingConnect = hasPendingConnect,
                rediscoveryLoggedWithoutLink = peer.rediscoveryLoggedWithoutLink,
            )
        )
    if (decision.shouldLogRediscoveryWithoutLink) {
        log(
            "scan rediscovered ${peer.hintPeerId.value.takeLast(6)} with no active link pendingConnect=$hasPendingConnect addr=$address"
        )
    }
    peerRegistry.setRediscoveryLoggedWithoutLink(
        peer.hintPeerId.value,
        decision.rediscoveryLoggedWithoutLink,
    )
}

internal fun BleTransportAdapter.resolvePeer(peerId: PeerId): DiscoveredPeer? {
    return peerRegistry.resolve(peerId)
}

@SuppressLint("MissingPermission")
internal fun BleTransportAdapter.refreshDiscoveryState(): Unit {
    try {
        discoveryLifecycle.refresh(started = started, hardware = discoveryHardware())
    } catch (exception: SecurityException) {
        throw androidPermissionDenied(exception)
    }
}

internal fun BleTransportAdapter.ensurePermissionsGranted(): Unit {
    BlePermissionContract.ensureRequiredPermissionsGranted(
        sdkInt = Build.VERSION.SDK_INT,
        isGranted = { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        },
    )
}

internal fun BleTransportAdapter.androidPermissionDenied(cause: SecurityException): Throwable {
    return ch.trancee.meshlink.platform.PlatformPermissionDeniedException(
        message = "Android BLE permissions denied",
        cause = cause,
    )
}
