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
    return sdkInt >= 34
}

internal fun parseDiscoveryScanResultOrNull(
    serviceUuids: List<String>?,
    deviceAddress: String,
    localMeshHash: UShort,
    localKeyHash: ByteArray,
    log: (String) -> Unit,
): DiscoveryScanResult? {
    val payloadUuid =
        serviceUuids?.firstOrNull { uuid -> !BleDiscoveryContract.isAdvertisementServiceUuid(uuid) }
            ?: return null
    val payload =
        runCatching { BleDiscoveryPayload.fromUuidString(payloadUuid) }.getOrNull() ?: return null
    if (payload.meshHash != localMeshHash) {
        return null
    }
    if (payload.keyHash.contentEquals(localKeyHash)) {
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
    return DiscoveryScanResult(
        payload = payload,
        hintPeerId = PeerId(payload.keyHash.toHexString()),
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
    return localL2capClientSocketsSupported &&
        transportMode == TransportMode.L2CAP &&
        shouldInitiateL2cap &&
        shouldInitiateDiscoveryDrivenL2capConnection(
            localPlatformFamily = localPlatformFamily,
            remotePlatformFamily = remotePlatformFamily,
            gattSideLinkReady = gattSideLinkReady,
        )
}
