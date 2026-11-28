package ch.trancee.meshlink.samples

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkConfig
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Power
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.ReadReceiptRequest
import ch.trancee.meshlink.RejectionReason
import ch.trancee.meshlink.Requirement
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendOptions
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.flow.first

// The API reference's samples (`@sample`, read by Dokka) and the Kotlin code of the docs/how-to pages:
// each `// region` is copied into a page, and ./gradlew checkDocRegions fails when the two differ
// (ADR 0006 decision 5). HowToTest runs every sample on VirtualAir.

/** Picks a built-in preset and a security profile. */
fun choosePreset(): MeshLinkConfig {
    // region choose-preset
    val config =
        meshLinkConfig(Preset.FieldTeams, SecurityProfile.PostQuantum) {
            meshId = "Rescue team"
        }.build()
    // endregion
    return config
}

/** Builds an app's own preset from a built-in one, then overrides one more value for this node. */
fun customizePreset(): MeshLinkConfig {
    // region custom-preset
    val quietChat =
        Preset.Custom("Quiet chat", Preset.OfflineChat) {
            readReceipts.send = false
            messageLifetimeSeconds = 3_600
        }
    val config =
        meshLinkConfig(quietChat, SecurityProfile.Classical) {
            meshId = "Quiet chat"
            power = Power.Responsive
        }.build()
    // endregion
    return config
}

/** What to ask of the user while [node] is blocked; empty once it runs. */
fun prompts(node: MeshLink): List<String> {
    // region blocked
    val state = node.state.value
    val prompts =
        if (state is MeshState.Blocked) {
            state.requirements.map { requirement ->
                when (requirement) {
                    is Requirement.PermissionDenied -> "Allow ${requirement.kind} for this app in Settings"
                    Requirement.BluetoothOff -> "Turn on Bluetooth"
                    Requirement.Unsupported -> "This device can't join the mesh"
                    Requirement.RadioInUse -> "Another part of this app uses the mesh"
                    Requirement.IdentityUnavailable -> "Unlock the device and try again"
                }
            }
        } else {
            emptyList()
        }
    // endregion
    return prompts
}

/** Waits until [node] leaves [MeshState.Blocked] and runs. */
suspend fun awaitRunning(node: MeshLink) {
    // region leave-blocked
    node.state.first { it is MeshState.Running }
    // endregion
}

/** Sends a message to [to] that asks for a read receipt, and returns its final status. */
suspend fun sendWithReadReceipt(
    node: MeshLink,
    to: NodeId,
): DeliveryStatus {
    // region send-receipt
    val options = SendOptions(readReceipt = ReadReceiptRequest.Request)
    val status =
        when (val result = node.send(to, "Are you safe?".encodeToByteArray(), options)) {
            is SendResult.Accepted -> {
                result.message.status.first {
                    it == DeliveryStatus.Read || it == DeliveryStatus.Expired || it is DeliveryStatus.Failed
                }
            }

            is SendResult.Rejected -> {
                error("Not sent: ${result.reason}")
            }
        }
    // endregion
    return status
}

/** Takes the next message and marks it read, which sends the read receipt its sender asked for. */
suspend fun readNextMessage(node: MeshLink): String {
    // region mark-read
    val message = node.messages.first()
    val text = message.payload.decodeToString()
    message.markRead()
    // endregion
    return text
}

/** Sends [payload] to [to]; when the queue is full, waits for room once and sends again. */
suspend fun sendWhenThereIsRoom(
    node: MeshLink,
    to: NodeId,
    payload: ByteArray,
): SendResult {
    // region queue-full
    var result = node.send(to, payload)
    if (result is SendResult.Rejected && result.reason == RejectionReason.QueueFull) {
        node.awaitSendCapacity()
        result = node.send(to, payload)
    }
    // endregion
    return result
}

/** Broadcasts an alert to the nodes within 4 hops, and returns once its first copy is on air. */
suspend fun broadcastAlert(node: MeshLink): DeliveryStatus {
    // region broadcast
    val alert = "Water at the school".encodeToByteArray()
    val status =
        when (val result = node.broadcast(alert, hopLimit = 4)) {
            is SendResult.Accepted -> result.message.status.first { it != DeliveryStatus.Queued }
            is SendResult.Rejected -> error("Not broadcast: ${result.reason}")
        }
    // endregion
    return status
}

/** Takes the next broadcast, skipping direct messages. */
suspend fun nextBroadcast(node: MeshLink): String {
    // region receive-broadcast
    val alert = node.messages.first { it.broadcast }
    val text = alert.payload.decodeToString()
    // endregion
    return text
}

/** The JSON report of [node]'s diagnostics, ready to attach to a bug report. */
suspend fun exportDiagnostics(node: MeshLink): String {
    // region export
    val report = node.diagnostics.export()
    // endregion
    return report
}
