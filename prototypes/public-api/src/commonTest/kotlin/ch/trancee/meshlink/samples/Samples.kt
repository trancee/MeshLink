package ch.trancee.meshlink.samples

import ch.trancee.meshlink.BroadcastOptions
import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkConfig
import ch.trancee.meshlink.MeshLinkPlatform
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.ProximityBucket
import ch.trancee.meshlink.Reachability
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendOptions
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.meshLinkConfig
import ch.trancee.meshlink.presetOverrides
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

suspend fun createAndStart(platform: MeshLinkPlatform): MeshLink {
    val config = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) { meshId = "family-chat" }
    val meshLink = MeshLink.create(config, platform)
    meshLink.start()
    return meshLink
}

fun configureWithDsl(): MeshLinkConfig = meshLinkConfig(Preset.FieldTeams, SecurityProfile.PostQuantum) {
    meshId = "search-team"
    meshKey = ByteArray(MeshLinkConfig.MESH_KEY_BYTES) { it.toByte() }
}

fun configureWithBuilder(): MeshLinkConfig = MeshLinkConfig.Builder(Preset.FieldTeams, SecurityProfile.PostQuantum)
    .meshId("search-team")
    .debugDiagnostics(true)
    .build()

fun customPreset(): Preset = Preset.Custom(
    name = "Arena",
    base = Preset.LocalMultiplayer,
    overrides = presetOverrides { maxPayloadBytes = 64 },
)

suspend fun observeState(meshLink: MeshLink): String = when (val state = meshLink.state.first { it != MeshState.Starting }) {
    MeshState.Running -> "running"
    is MeshState.Blocked -> "blocked by ${state.requirements}"
    MeshState.Stopped, MeshState.Starting, MeshState.Stopping -> "not running"
}

fun listNearbyPeers(meshLink: MeshLink): List<NodeId> = meshLink.peers.value
    .filter { it.reachability == Reachability.Direct && it.proximity.bucket == ProximityBucket.Immediate }
    .map { it.nodeId }

suspend fun sendAndTrack(meshLink: MeshLink, to: NodeId): String =
    when (val result = meshLink.send(to, "Hello".encodeToByteArray(), SendOptions(deliveryReceipt = true))) {
        is SendResult.Accepted -> when (val status = result.message.status.first { it.isFinal() }) {
            DeliveryStatus.Delivered, DeliveryStatus.Read -> "delivered"
            is DeliveryStatus.Failed -> "failed: ${status.reason}"
            else -> "expired"
        }
        is SendResult.Rejected -> "rejected: ${result.reason} (${result.reason.code})"
    }

private fun DeliveryStatus.isFinal(): Boolean = when (this) {
    DeliveryStatus.Queued, DeliveryStatus.Sent, DeliveryStatus.Held -> false
    DeliveryStatus.Delivered, DeliveryStatus.Read, DeliveryStatus.Expired, is DeliveryStatus.Failed -> true
}

suspend fun receiveAndMarkRead(meshLink: MeshLink): String {
    val message = meshLink.messages.first()
    message.markRead()
    return message.payload.decodeToString()
}

fun broadcastAlert(meshLink: MeshLink): SendResult =
    meshLink.broadcast("Evacuate".encodeToByteArray(), hopLimit = 4, options = BroadcastOptions.Default)

suspend fun watchDiagnostics(meshLink: MeshLink): String =
    meshLink.diagnostics.map { "${it.code.id} ${it.code.name} ${it.severity} ${it.fields}" }.first()

fun nodeIdRoundTrip(nodeId: NodeId): Boolean = NodeId.fromHex(nodeId.toHex()) == nodeId
