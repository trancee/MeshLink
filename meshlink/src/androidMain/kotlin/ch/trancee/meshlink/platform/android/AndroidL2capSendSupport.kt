package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult

internal class AndroidL2capSendContext
internal constructor(
    internal val hintPeerId: PeerId,
    internal val transportMode: TransportMode,
    internal val advertisedL2capPsm: Int,
)

internal interface AndroidL2capSendLink {
    suspend fun send(frame: OutboundFrame): TransportSendResult
}

internal class AndroidL2capSendDependencies
internal constructor(
    internal val currentLink: () -> AndroidL2capSendLink?,
    internal val shouldInitiateL2cap: () -> Boolean,
    internal val triggerConnectIfNeeded: () -> Unit,
    internal val log: (String) -> Unit,
)

internal suspend fun sendViaAndroidL2capWhenReady(
    frame: OutboundFrame,
    context: AndroidL2capSendContext,
    dependencies: AndroidL2capSendDependencies,
): TransportSendResult {
    val directLink = dependencies.currentLink()
    if (context.transportMode != TransportMode.L2CAP) {
        dependencies.log("send(${context.hintPeerId.value.takeLast(6)}) dropped: peer is GATT-only")
        return TransportSendResult.Dropped("Android BLE GATT fallback transport is not implemented")
    }
    if (context.advertisedL2capPsm == 0 && directLink == null) {
        dependencies.log(
            "send(${context.hintPeerId.value.takeLast(6)}) waiting for inbound L2CAP link"
        )
        return TransportSendResult.Dropped("Android BLE L2CAP connection is not ready")
    }
    if (directLink == null) {
        if (dependencies.shouldInitiateL2cap()) {
            dependencies.log(
                "send(${context.hintPeerId.value.takeLast(6)}) no active link, triggering connect"
            )
            dependencies.triggerConnectIfNeeded()
        } else {
            dependencies.log(
                "send(${context.hintPeerId.value.takeLast(6)}) waiting for inbound L2CAP link"
            )
        }
        return TransportSendResult.Dropped("Android BLE L2CAP connection is not ready")
    }
    return directLink.send(frame)
}
