package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportSendResult

internal class SendDispatchDependencies
internal constructor(
    internal val sendToResolvedPeerOrNull: suspend () -> TransportSendResult?,
    internal val dropWhenPeerIsMissing: () -> TransportSendResult,
)

internal suspend fun dispatchSend(
    frame: OutboundFrame,
    dependencies: SendDispatchDependencies,
): TransportSendResult {
    return dependencies.sendToResolvedPeerOrNull() ?: dependencies.dropWhenPeerIsMissing()
}
