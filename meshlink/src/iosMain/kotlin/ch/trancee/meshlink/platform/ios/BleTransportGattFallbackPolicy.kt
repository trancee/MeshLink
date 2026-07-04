package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.shouldUseMixedPlatformGattNotifyBearer

internal fun supportsIosGattNotifyBearer(
    localPlatformFamily: BleDiscoveryPlatformFamily,
    remotePlatformFamily: BleDiscoveryPlatformFamily,
    hasBridge: Boolean,
): Boolean {
    return hasBridge &&
        shouldUseMixedPlatformGattNotifyBearer(
            localPlatformFamily = localPlatformFamily,
            remotePlatformFamily = remotePlatformFamily,
        )
}

internal class GattNotifyHintSelectionRequest
internal constructor(
    internal val boundHintPeerIdValue: String?,
    internal val localPlatformFamily: BleDiscoveryPlatformFamily,
    internal val discoveredPeers: Collection<DiscoveredPeer>,
)

internal fun selectGattNotifyHintPeerIdValue(request: GattNotifyHintSelectionRequest): String? {
    return request.boundHintPeerIdValue
        ?: request.discoveredPeers
            .firstOrNull { peer ->
                shouldUseMixedPlatformGattNotifyBearer(
                    localPlatformFamily = request.localPlatformFamily,
                    remotePlatformFamily = peer.platformFamily,
                )
            }
            ?.hintPeerId
            ?.value
}
