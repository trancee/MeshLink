package ch.trancee.lab

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.internal.LinkRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The `direct-message` scenario (ticket 16): starts [node], waits for its first peer, sends it one
 * message and listens for the peer's message until the run's duration ends. Records the link, the
 * send, every status change and every message received, each with the role this node linked in.
 */
internal class DirectMessage(
    private val node: MeshLink,
    private val args: LabArgs,
    private val recorder: Recorder,
) {
    private val failures = mutableListOf<String>()
    private val received = mutableListOf<Boolean>()
    private var sent = false
    private var role: LinkRole? = null

    /** Runs the scenario and returns why it failed; empty if it passed. */
    suspend fun run(): List<String> {
        var linked = false
        coroutineScope {
            launch { states() }
            launch { inbox() }
            node.restrictedLinkRole = args.role
            node.start()
            withTimeoutOrNull(args.duration) {
                linked(
                    node.peers
                        .first { it.isNotEmpty() }
                        .first()
                        .nodeId,
                )
                linked = true
                awaitCancellation()
            }
            coroutineContext.cancelChildren()
        }
        if (!linked) failures += "no peer linked"
        if (!sent) failures += "message never left this node"
        if (received.size != 1) failures += "received ${received.size} messages, expected 1"
        if (false in received) failures += "a received payload failed its hash check"
        return failures
    }

    private suspend fun states(): Nothing = node.state.collect { record("state", "state" to it.toString()) }

    private suspend fun inbox() =
        node.messages.collect { message ->
            val intact = Payload.intact(message.payload)
            received += intact
            record(
                "received",
                "from" to short(message.sender),
                "bytes" to message.payload.size,
                "sha256" to Payload.hex(sha256(message.payload)),
                "intact" to intact,
            )
        }

    private fun CoroutineScope.linked(peer: NodeId) {
        role = node.linkRoles[peer]
        record("link", "peer_node" to short(peer))
        if (args.role != null && role != args.role) failures += "linked as $role, not ${args.role}"
        val payload = Payload.create("meshlink-lab ${args.scenario} run=${args.run} to=${args.peer}")
        when (val result = node.send(peer, payload)) {
            is SendResult.Accepted -> {
                record(
                    "send",
                    "to" to short(peer),
                    "bytes" to payload.size,
                    "sha256" to Payload.hex(sha256(payload)),
                )
                launch { statuses(result) }
            }

            is SendResult.Rejected -> {
                failures += "send rejected: ${result.reason}"
            }
        }
    }

    private suspend fun statuses(result: SendResult.Accepted) {
        result.message.status
            .takeWhile { it !is DeliveryStatus.Expired && it !is DeliveryStatus.Failed }
            .collect { status ->
                record("status", "status" to status.toString())
                if (status == DeliveryStatus.Sent) sent = true
            }
        val final = result.message.status.value
        record("status", "status" to final.toString())
        failures += "message ended $final"
    }

    /** Records [event] with the role this node linked in, `null` before the link. */
    private fun record(
        event: String,
        vararg fields: Pair<String, Any?>,
    ) = recorder.record(event, *fields, "role" to role?.name?.lowercase())

    private fun short(node: NodeId): String = node.toString().take(SHORT_NODE_ID)

    private companion object {
        const val SHORT_NODE_ID = 16
    }
}
