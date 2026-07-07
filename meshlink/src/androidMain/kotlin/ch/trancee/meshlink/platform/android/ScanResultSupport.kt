package ch.trancee.meshlink.platform.android

import android.os.Build
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.shouldInitiateDiscoveryDrivenL2capConnection

internal class DiscoveryScanResult
internal constructor(
    internal val payload: BleDiscoveryPayload,
    internal val hintPeerId: PeerId,
    internal val transportMode: TransportMode,
)

internal fun supportsL2capClientSockets(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
    return sdkInt >= L2capSupportedSdkInt
}

internal fun supportsL2capClientSockets(clientSocketSupported: Boolean): Boolean {
    return clientSocketSupported
}

internal fun supportsL2capServerSockets(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
    return sdkInt >= L2capSupportedSdkInt
}

internal fun supportsL2capServerSockets(serverSocketSupported: Boolean): Boolean {
    return serverSocketSupported
}

private const val L2capSupportedSdkInt: Int = 34

internal fun parseDiscoveryScanResultOrNull(
    serviceUuids: List<String>?,
    serviceData: Map<String, ByteArray>? = null,
    deviceAddress: String,
    localMeshHash: UShort,
    localKeyHash: ByteArray,
    onForeignScanIgnored: () -> Int = { 0 },
    log: (String) -> Unit,
): DiscoveryScanResult? {
    val payloadUuid =
        serviceUuids?.firstOrNull { uuid -> !BleDiscoveryContract.isAdvertisementServiceUuid(uuid) }
            ?: serviceData?.keys?.firstOrNull { uuid ->
                !BleDiscoveryContract.isAdvertisementServiceUuid(uuid)
            }
            ?: run {
                log("ignoring scan result without discovery payload addr=$deviceAddress")
                return null
            }
    // Cheap pre-check: decode only the meshHash bytes before paying for a full payload parse
    // (16-byte array allocation + keyHash copy). In a BLE-dense environment the overwhelming
    // majority of scan results carry a foreign meshHash and are rejected here -- `onScanResult`
    // is delivered on the main looper with no way to redirect it to a background thread via the
    // public API, so avoiding this allocation directly reduces main-thread contention with
    // connection-critical BLE callback delivery (see BleDiscoveryContract.peekMeshHashOrNull).
    val peekedMeshHash = BleDiscoveryContract.peekMeshHashOrNull(payloadUuid)
    if (peekedMeshHash != null && peekedMeshHash != localMeshHash) {
        // See BleTransportAdapterScanSupport's existing sampling of its own per-result logs for
        // why this rejection path is sampled rather than logged unconditionally.
        val ignoredCount = onForeignScanIgnored()
        if (ignoredCount % SCAN_RESULT_LOG_SAMPLE_INTERVAL == 1) {
            log(
                "ignoring discovery payload with mismatched meshHash addr=$deviceAddress payloadUuid=$payloadUuid localMeshHash=$localMeshHash remoteMeshHash=$peekedMeshHash foreignIgnoredCount=$ignoredCount"
            )
        }
        return null
    }
    val payload =
        runCatching { BleDiscoveryPayload.fromUuidString(payloadUuid) }.getOrNull()
            ?: run {
                log(
                    "ignoring discovery payload with invalid encoding addr=$deviceAddress payloadUuid=$payloadUuid"
                )
                return null
            }
    if (payload.meshHash != localMeshHash) {
        // Reached only when the cheap pre-check above couldn't decode a meshHash (malformed
        // payload) but the full parse still succeeded and disagrees -- extremely rare, but keep
        // this as a correctness backstop with the same sampled logging behavior.
        val ignoredCount = onForeignScanIgnored()
        if (ignoredCount % SCAN_RESULT_LOG_SAMPLE_INTERVAL == 1) {
            log(
                "ignoring discovery payload with mismatched meshHash addr=$deviceAddress payloadUuid=$payloadUuid localMeshHash=$localMeshHash remoteMeshHash=${payload.meshHash} foreignIgnoredCount=$ignoredCount"
            )
        }
        return null
    }
    if (payload.keyHash.contentEquals(localKeyHash)) {
        log("ignoring self-discovery payload addr=$deviceAddress payloadUuid=$payloadUuid")
        return null
    }
    if (!BleDiscoveryContract.isSupportedProtocolVersion(payload.protocolVersion)) {
        log(
            "ignoring discovery payload with unsupported protocolVersion=${payload.protocolVersion} addr=$deviceAddress"
        )
        return null
    }
    val transportMode =
        if (payload.l2capPsm.toInt() == 0) TransportMode.GATT else TransportMode.L2CAP
    val hintPeerId = PeerId(payload.keyHash.toHexString())
    log(
        "accepted discovery payload addr=$deviceAddress protocolVersion=${payload.protocolVersion} meshHash=${payload.meshHash} platform=${payload.platformFamily} mode=$transportMode psm=${payload.l2capPsm} peerId=${hintPeerId.value}"
    )
    return DiscoveryScanResult(
        payload = payload,
        hintPeerId = hintPeerId,
        transportMode = transportMode,
    )
}

internal fun shouldConnectAfterDiscovery(
    transportMode: TransportMode,
    localPlatformFamily: BleDiscoveryPlatformFamily,
    remotePlatformFamily: BleDiscoveryPlatformFamily,
    localL2capClientSocketsSupported: Boolean,
    shouldInitiateL2cap: Boolean,
    gattSideLinkReady: Boolean,
): Boolean {
    return when (transportMode) {
        TransportMode.GATT -> gattSideLinkReady
        TransportMode.L2CAP ->
            localL2capClientSocketsSupported &&
                shouldInitiateL2cap &&
                shouldInitiateDiscoveryDrivenL2capConnection(
                    localPlatformFamily = localPlatformFamily,
                    remotePlatformFamily = remotePlatformFamily,
                    gattSideLinkReady = gattSideLinkReady,
                )
    }
}
