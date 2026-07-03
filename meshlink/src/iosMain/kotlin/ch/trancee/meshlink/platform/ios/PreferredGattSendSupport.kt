package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.DirectWireFrame
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.GattDataBearerMode
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.transport.resolveGattDataBearerMode

internal class PreferredGattSendContext
internal constructor(
    internal val hintPeerId: PeerId,
    internal val localPlatformFamily: BleDiscoveryPlatformFamily,
    internal val remotePlatformFamily: BleDiscoveryPlatformFamily,
)

internal interface PreferredGattSendLink {
    suspend fun enqueue(payload: ByteArray): Boolean
}

internal class PreferredGattSendDependencies
internal constructor(
    internal val currentLink: () -> PreferredGattSendLink?,
    internal val log: (String) -> Unit,
)

internal suspend fun sendViaPreferredGattNotifyLinkOrNull(
    frame: OutboundFrame,
    context: PreferredGattSendContext,
    dependencies: PreferredGattSendDependencies,
): TransportSendResult? {
    // Unlike the Android GATT side link (see `platform.android.PreferredGattSendSupport`), this
    // path does not poll a readiness flag before handing the frame off: `GattNotifyLink.enqueue`
    // already owns an internal pump queue that holds frames and drains them once the remote
    // central subscribes, so an external readiness wait here would be redundant.
    val directFrame = runCatching { DirectWireFrame.decode(frame.payload) }.getOrNull()
    if (directFrame == null) {
        return null
    }

    val dataBearerMode =
        resolveGattDataBearerMode(
            localPlatformFamily = context.localPlatformFamily,
            remotePlatformFamily = context.remotePlatformFamily,
            preferredMode = frame.preferredMode,
        )
    if (dataBearerMode == GattDataBearerMode.L2CAP_ONLY) {
        return null
    }

    val link = dependencies.currentLink() ?: return null
    return runCatching {
            dependencies.log(
                "sending ${frame.payload.size} bytes via GATT notify side link for ${context.hintPeerId.logSuffix()}"
            )
            if (!link.enqueue(frame.payload)) {
                dependencies.log(
                    "preferred GATT notify send returned false for ${context.hintPeerId.logSuffix()} bytes=${frame.payload.size}"
                )
                return@runCatching null
            }
            TransportSendResult.Delivered
        }
        .getOrElse { error ->
            dependencies.log(
                "preferred GATT notify send failed for ${context.hintPeerId.logSuffix()}: ${error.message.orEmpty()}"
            )
            null
        }
}
