package ch.trancee.meshlink.platform.android.scan

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.platform.android.BlePermissionContract
import ch.trancee.meshlink.platform.android.BleTransportAdapter
import ch.trancee.meshlink.platform.android.DiscoveredPeer
import ch.trancee.meshlink.platform.android.DiscoveredPeerDiscovery
import ch.trancee.meshlink.platform.android.discoveryHardware
import ch.trancee.meshlink.platform.android.l2cap.connectIfNeeded
import ch.trancee.meshlink.platform.android.l2cap.hasPendingConnect
import ch.trancee.meshlink.platform.android.l2cap.promoteTemporaryLink
import ch.trancee.meshlink.power.hasConnectionBudget
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.RediscoveryWithoutLinkDecisionRequest
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.evaluateRediscoveryWithoutLink
import ch.trancee.meshlink.transport.shouldLocalPeerInitiateL2capConnection

// Every scan callback previously logged unconditionally at INFO level. In a
// BLE-dense environment (many nearby devices/apps) this can produce
// thousands of lines within seconds, which evicts one-time lifecycle logs
// (advertise/route/DIAG) from the logcat ring buffer before they can be
// captured for diagnosis. Sample routine per-result lines instead; the
// cumulative counters embedded in each line still make sampled lines useful.
internal const val SCAN_RESULT_LOG_SAMPLE_INTERVAL = 50

// Precomputed once instead of re-parsed from `ADVERTISEMENT_SERVICE_UUID_EXPANDED` per scan
// result. Comparing candidate `ParcelUuid`s against this directly (see
// [firstNonMarkerServiceUuidString]/[firstNonMarkerServiceDataKeyString]) lets the marker
// advertisement UUID -- present in essentially every scan result carrying our payload -- be
// rejected via a cheap UUID equality check instead of paying for `.uuid.toString()` formatting
// first. `ScanCallback.onScanResult` is delivered on the main looper with no way to redirect it to
// a background thread via the public API, so avoiding this formatting work directly reduces
// main-thread contention with connection-critical BLE callback delivery.
private val MARKER_PARCEL_UUID: ParcelUuid by lazy {
    ParcelUuid.fromString(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID_EXPANDED)
}

// parseDiscoveryScanResultOrNull's serviceData parameter only ever inspects `.keys`, never the
// byte values, so the singleton map built at the call site below never needs a real value -- this
// shared empty array avoids allocating a fresh zero-length ByteArray per scan result that takes
// the serviceData fallback path.
private val EMPTY_SERVICE_DATA: ByteArray = ByteArray(0)

/**
 * Finds the first advertised service UUID that isn't the fixed marker UUID, converting only that
 * one candidate to a string. The previous implementation called `.map { it.uuid.toString() }`
 * unconditionally on the full `serviceUuids` list -- formatting every UUID (including the marker,
 * present in nearly every result) into a string and allocating an intermediate list -- before ever
 * checking which one was the actual payload.
 */
private fun firstNonMarkerServiceUuidString(serviceUuids: List<ParcelUuid>?): String? {
    return serviceUuids
        ?.firstOrNull { parcelUuid -> parcelUuid != MARKER_PARCEL_UUID }
        ?.uuid
        ?.toString()
}

/** Same rationale as [firstNonMarkerServiceUuidString], for the `serviceData` fallback path. */
private fun firstNonMarkerServiceDataKeyString(serviceData: Map<ParcelUuid, ByteArray>?): String? {
    return serviceData
        ?.keys
        ?.firstOrNull { parcelUuid -> parcelUuid != MARKER_PARCEL_UUID }
        ?.uuid
        ?.toString()
}

internal fun BleTransportAdapter.handleScanResult(result: ScanResult): Unit {
    val scanNumber = scanResultCount.incrementAndGet()
    if (scanNumber % SCAN_RESULT_LOG_SAMPLE_INTERVAL == 1) {
        log(
            "scan result#$scanNumber addr=${result.device.address} rssi=${result.rssi} uuids=${result.scanRecord?.serviceUuids?.size ?: 0} targetPeerId=${automationTargetPeerId ?: "none"} knownPeers=${peerRegistry.discoveredPeerCount()} foreignIgnored=${foreignScanIgnoredCount.get()} accepted=${scanAcceptedCount.get()} parseSkipped=${scanParseSkippedCount.get()} targetMismatch=${scanTargetMismatchCount.get()}"
        )
    }
    // Resolve the payload-carrying UUID candidate from serviceUuids first, falling back to
    // serviceData only when serviceUuids didn't yield one -- matching
    // parseDiscoveryScanResultOrNull's own fallback order (see below) but stopping short of
    // formatting/allocating the serviceData map at all when it won't even be consulted.
    val serviceUuidsCandidate = firstNonMarkerServiceUuidString(result.scanRecord?.serviceUuids)
    val serviceDataCandidateKey =
        if (serviceUuidsCandidate == null) {
            firstNonMarkerServiceDataKeyString(result.scanRecord?.serviceData)
        } else {
            null
        }
    val discovery =
        parseDiscoveryScanResultOrNull(
            serviceUuids = serviceUuidsCandidate?.let(::listOf),
            serviceData = serviceDataCandidateKey?.let { key -> mapOf(key to EMPTY_SERVICE_DATA) },
            deviceAddress = result.device.address,
            localMeshHash = currentDiscoveryPayload.meshHash,
            localKeyHash = localKeyHash,
            onForeignScanIgnored = { foreignScanIgnoredCount.incrementAndGet() },
            log = ::log,
        )
            ?: run {
                val skippedCount = scanParseSkippedCount.incrementAndGet()
                if (skippedCount % SCAN_RESULT_LOG_SAMPLE_INTERVAL == 1) {
                    log(
                        "scan discovery skipped addr=${result.device.address} rssi=${result.rssi} targetPeerId=${automationTargetPeerId ?: "none"} knownPeers=${peerRegistry.discoveredPeerCount()} foreignIgnored=${foreignScanIgnoredCount.get()} parseSkipped=$skippedCount"
                    )
                }
                return
            }

    val targetPeerId = automationTargetPeerId
    if (automationEnabled && targetPeerId != null && discovery.hintPeerId.value != targetPeerId) {
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
    scanAcceptedCount.incrementAndGet()
    val resolvedPeer: DiscoveredPeer
    val emittedEventCount: Int
    if (sameTransportAdvertisement && discoveredPeer.presenceAnnounced) {
        // The advertisement is byte-for-byte identical to what's already recorded (same address,
        // PSM, transport mode) and presence was already announced for this peer -- upsertDiscovery
        // would be a pure no-op here (unchanged fields, no events; see
        // PeerRegistry.refreshDiscoveredPeer), so skip its allocation, registry lock, and
        // PeerBindings address-rebind rather than repeating that work for every duplicate
        // advertisement from an already-known, already-announced peer in a stable environment.
        resolvedPeer = discoveredPeer
        emittedEventCount = 0
    } else {
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
        update.events.forEach(mutableEvents::tryEmit)
        resolvedPeer = update.peer
        emittedEventCount = update.events.size
    }
    if (emittedEventCount == 0) {
        log(
            "scan accepted ${discovery.hintPeerId.value.takeLast(6)} mode=${discovery.transportMode} emitted=no-events peerId=${discovery.hintPeerId.value} addr=${result.device.address} knownPeers=${peerRegistry.discoveredPeerCount()} targetPeerId=${automationTargetPeerId ?: "none"} accepted=${scanAcceptedCount.get()}"
        )
    } else {
        log(
            "scan accepted ${discovery.hintPeerId.value.takeLast(6)} mode=${discovery.transportMode} emitted=${emittedEventCount} peerId=${discovery.hintPeerId.value} addr=${result.device.address} knownPeers=${peerRegistry.discoveredPeerCount()} targetPeerId=${automationTargetPeerId ?: "none"} accepted=${scanAcceptedCount.get()}"
        )
    }
    maybeLogRediscoveryWithoutLink(
        peer = resolvedPeer,
        transportMode = discovery.transportMode,
        address = result.device.address,
    )
    val activeHintIds = linkRegistry.activeHintIds() + gattSideLinks.activeHintIds()
    if (
        hasConnectionBudget(
            peerAlreadyConnected = resolvedPeer.hintPeerId.value in activeHintIds,
            activeConnectionCount = activeHintIds.size,
            maxConnections = currentPowerProfile.maxConnections,
        )
    ) {
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
                    shouldInitiateL2cap(
                        discovery.payload.keyHash,
                        discovery.payload.platformFamily,
                    ),
                gattSideLinkReady = gattSideLinks.hasReadyLink(resolvedPeer.hintPeerId.value),
            )
        ) {
            connectIfNeeded(resolvedPeer)
        }
    } else {
        log(
            "connection budget exhausted, deferring connect for " +
                "${resolvedPeer.hintPeerId.value.takeLast(6)} " +
                "activeConnections=${activeHintIds.size} maxConnections=${currentPowerProfile.maxConnections}"
        )
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
