package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportSendResult

internal class SendDispatchDependencies
internal constructor(
    internal val sendToResolvedPeerOrNull: suspend () -> TransportSendResult?,
    // Recovers a send when no DiscoveredPeer record exists yet under frame.peerId (so
    // sendToResolvedPeerOrNull's resolvePeer(...) lookup returns null) but an L2CAP link is
    // already active under the raw, not-yet-canonicalized hint id -- e.g. an inbound L2CAP
    // channel accepted via registerConnectedChannel before the LinkIdentity control-frame
    // exchange (or discovery-driven upsertDiscovery) has registered a DiscoveredPeer for this
    // peer. Mirrors Android's identical SendDispatchDependencies.sendToTemporaryLinkOrNull (see
    // platform.android.SendDispatchSupport).
    internal val sendToTemporaryLinkOrNull: suspend () -> TransportSendResult?,
    internal val dropWhenPeerIsMissing: () -> TransportSendResult,
)

internal suspend fun dispatchSend(
    frame: OutboundFrame,
    dependencies: SendDispatchDependencies,
): TransportSendResult {
    val resolvedResult = dependencies.sendToResolvedPeerOrNull()
    if (resolvedResult != null) {
        return resolvedResult
    }
    return dependencies.sendToTemporaryLinkOrNull() ?: dependencies.dropWhenPeerIsMissing()
}
