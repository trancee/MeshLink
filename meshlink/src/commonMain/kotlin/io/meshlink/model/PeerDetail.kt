package io.meshlink.model

import io.meshlink.routing.PresenceState

/**
 * Detailed snapshot of a single peer's connection, routing, and identity state.
 *
 * Returned by [io.meshlink.MeshLinkApi.peerDetail] and
 * [io.meshlink.MeshLinkApi.allPeerDetails] for visualizers and diagnostics UIs.
 */
data class PeerDetail(
    val peerIdHex: String,
    val presenceState: PresenceState,
    val isDirectNeighbor: Boolean,
    val routeNextHop: String?,
    val routeCost: Double?,
    val routeSequenceNumber: UInt?,
    val publicKeyHex: String?,
    val nextHopFailureRate: Double,
    val nextHopFailureCount: Int,
)
