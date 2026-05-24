package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportSendResult

internal class IosSendDispatchDependencies
internal constructor(
    internal val sendToResolvedPeerOrNull: suspend () -> TransportSendResult?,
    internal val dropWhenPeerIsMissing: () -> TransportSendResult,
)

internal suspend fun dispatchIosSend(
    frame: OutboundFrame,
    dependencies: IosSendDispatchDependencies,
): TransportSendResult {
    return dependencies.sendToResolvedPeerOrNull() ?: dependencies.dropWhenPeerIsMissing()
}
