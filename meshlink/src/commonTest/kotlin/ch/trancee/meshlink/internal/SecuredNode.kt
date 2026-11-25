package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.SendOptions
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A secured node of [keys]: its end, the lanes and inbox it uses, whom it heard from, the peers it opened
 * sessions with, the receipts it sent, and the delivery and read receipts that arrived for its messages.
 */
internal class SecuredNode(
    val keys: SecuredKeys,
) {
    lateinit var end: SecuredEnd
    lateinit var lookups: Lookups
    val lanes = Lanes()
    val inbox = Inbox()
    val heard = mutableListOf<NodeId>()
    val opened = mutableListOf<NodeId>()
    val replies = mutableListOf<Outgoing>()
    val arrived = mutableListOf<Pair<NodeId, List<List<Byte>>>>()
    val read = mutableListOf<Pair<NodeId, List<List<Byte>>>>()
    val nodeId = NodeId(keys.nodeId)
}

/** A [SecuredNode] of [keys] with a lane open to [neighbor], holding [keys] if [certified]. */
internal fun TestScope.securedNode(
    keys: SecuredKeys,
    neighbor: NodeId,
    certified: Boolean = true,
): SecuredNode {
    val crypto = SodiumCryptoProvider
    val clock = SchedulerClock(testScheduler)
    val node = SecuredNode(keys)
    node.lanes.open(neighbor)
    node.lookups = Lookups(crypto, clock, backgroundScope, WireProfile.CLASSICAL, node.lanes) { DensityClass.MEDIUM }
    node.lookups.begin {}
    val read = { from: NodeId, ids: List<ByteArray> -> node.read += from to ids.map { it.toList() } }
    val receipts =
        Receipts<Reply>(clock, backgroundScope, { reply, tombstones, expiry ->
            node.end.acknowledger.reply(reply, tombstones, expiry)
        }, { from, ids -> node.arrived += from to ids.map { it.toList() } }, reads(arrived = read)) {
            it is Reply.First
        }
    receipts.begin()
    val hooks = SecuredHooks({ node.heard += it }, {}, { node.opened += it }) { node.replies += it }
    node.end = SecuredEnd(crypto, clock, node.inbox, node.lookups, receipts, hooks)
    if (certified) node.end.keys.certify(node.keys)
    return node
}

/** [from] knows [to]'s certificate, as a link or lookup taught it. */
internal fun knows(
    from: SecuredNode,
    to: SecuredNode,
) = from.lookups.linked(to.nodeId, to.keys.certificate)

/** An outbox entry of [payload] to [to] that expires [lifetime] seconds from now. */
internal fun TestScope.entry(
    to: NodeId,
    payload: ByteArray = "hello".encodeToByteArray(),
    lifetime: Long = 60L,
): Outbox.Entry {
    val now = SchedulerClock(testScheduler).wallClockMillis()
    val status = MutableStateFlow<DeliveryStatus>(DeliveryStatus.Queued)
    return Outbox.Entry(to, payload, now / 1_000 + lifetime, status, now + 600_000, SendOptions())
}

/** [from] sends [to] a first message, which [to] opens and its app takes: [to]'s answer goes. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.firstTaken(
    from: SecuredNode,
    to: SecuredNode,
): Pair<Draft, Outgoing> {
    knows(from, to)
    val draft = assertNotNull(from.end.draft(entry(to.nodeId)))
    assertTrue(to.end.deliver(from.end.frame(draft, hopLimit = 5), nowSeconds()))
    to.inbox.messages.first()
    runCurrent()
    return draft to to.replies.last()
}

/** [from] and [to] open a session: [to] answers [from]'s first message and [from] adopts the answer. */
internal suspend fun TestScope.session(
    from: SecuredNode,
    to: SecuredNode,
) {
    val (_, answer) = firstTaken(from, to)
    from.end.acknowledger.acknowledged(answer.frame(5), nowSeconds())
}

private fun TestScope.nowSeconds() = SchedulerClock(testScheduler).wallClockMillis() / 1_000
