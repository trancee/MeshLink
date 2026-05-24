package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportSendResult

internal class AndroidSendDispatchContext
internal constructor(internal val transportStarted: Boolean)

internal class AndroidSendDispatchDependencies
internal constructor(
    internal val sendToResolvedPeerOrNull: suspend () -> TransportSendResult?,
    internal val sendToTemporaryLinkOrNull: suspend () -> TransportSendResult?,
    internal val log: (String) -> Unit,
)

internal suspend fun dispatchAndroidSend(
    frame: OutboundFrame,
    context: AndroidSendDispatchContext,
    dependencies: AndroidSendDispatchDependencies,
): TransportSendResult {
    if (!context.transportStarted) {
        dependencies.log("send(${frame.peerId.value.takeLast(6)}) dropped: transport not started")
        return TransportSendResult.Dropped("Android BLE transport is not started")
    }

    dependencies.sendToResolvedPeerOrNull()?.let { result ->
        return result
    }
    dependencies.sendToTemporaryLinkOrNull()?.let { result ->
        return result
    }

    dependencies.log("send(${frame.peerId.value.takeLast(6)}) dropped: peer not discovered")
    return TransportSendResult.Dropped("Android BLE peer has not been discovered")
}
