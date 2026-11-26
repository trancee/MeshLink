package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DensityClass
import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The broadcast tree of spec "Plumtree" (ADR 0008 decision 3, D-26) over this node's [lanes]. Every link starts
 * eager. In the medium and high classes a broadcast goes in full on eager links and as a one-ID `IHAVE` on lazy
 * ones; in the low class ([density]) it goes in full on every link, and this node never prunes. A duplicate on an
 * eager link answers with `PRUNE`, and the link becomes lazy; a `PRUNE` from the peer makes it lazy too.
 *
 * An ID learned from `IHAVE` is grafted from its first announcer after [GRAFT_BASE_MILLIS], then from each further
 * one [GRAFT_PER_ANNOUNCER_MILLIS] later, until the frame arrives. Sending or receiving `GRAFT` makes the link
 * eager, and a `GRAFT` is answered with the frame if this node still holds it.
 *
 * Bounds under any remote input: at most [HELD_MAX] frames held for grafts, each for [HOLD_MILLIS] at most and
 * never past its expiry, and at most [PENDING_MAX] IDs pending a graft, each with linked announcers only; the
 * oldest goes first. Both bounds are `catch-up-offered-max` (ADR 0014 decision 7, D-25): a node holds what one
 * `CATCH_UP` offers, and grafts no more than one offer's worth. Lazy links are linked peers only.
 *
 * Runs on [scope]'s serial dispatcher.
 */
internal class Plumtree(
    private val lanes: Lanes,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val density: () -> DensityClass,
) {
    private val lazy = mutableSetOf<NodeId>()
    private val held = LinkedHashMap<String, Held>()
    private val pending = LinkedHashMap<String, Pending>()

    /**
     * Sends [parcel], a broadcast expiring at Unix second [expiry], to every linked peer but [from], and holds it
     * for grafts.
     */
    fun spread(
        from: NodeId?,
        parcel: Parcel,
        expiry: Long,
    ) {
        val key = parcel.messageId.toHexString()
        held -= key
        held[key] = Held(parcel, minOf(clock.wallClockMillis() + HOLD_MILLIS, expiry * MILLIS_PER_SECOND))
        if (held.size > HELD_MAX) held -= held.keys.first()
        val flooding = density() == DensityClass.Low
        val ihave = LinkFrame.Ihave(listOf(parcel.messageId)).encode()
        val announcement = Parcel(ihave, relayed = true, { ihave }) {}
        lanes.peers.filter { it != from }.forEach { peer ->
            lanes.put(peer, if (flooding || peer !in lazy) parcel else announcement)
        }
    }

    /** The IDs of the frames held for grafts now, oldest first: what a `CATCH_UP` offers. */
    fun offered(): List<ByteArray> {
        val nowMillis = clock.wallClockMillis()
        return held.values.filter { it.untilMillis > nowMillis }.map { it.parcel.messageId }
    }

    /** The broadcast [id] arrived or was sent: no graft for it is pending any more. */
    fun arrived(id: ByteArray) {
        pending[id.toHexString()]?.cancel()
    }

    /** A broadcast this node has seen came again from [peer]: an eager link answers with `PRUNE`, unless low. */
    fun duplicate(peer: NodeId) {
        if (density() == DensityClass.Low || peer in lazy) return
        if (lanes.put(peer, Parcel(PRUNE, relayed = false, { PRUNE }) {})) lazy += peer
    }

    /** [peer] sent `PRUNE`: its link is lazy. */
    fun pruned(peer: NodeId) {
        if (peer in lanes.peers) lazy += peer
    }

    /**
     * [peer] sent `GRAFT` for [id]: its link is eager, and it gets the frame if this node holds it.
     * Returns whether it did.
     */
    fun grafted(
        peer: NodeId,
        id: ByteArray,
    ): Boolean {
        lazy -= peer
        val frame = held[id.toHexString()]?.takeIf { it.untilMillis > clock.wallClockMillis() } ?: return false
        lanes.put(peer, frame.parcel)
        return true
    }

    /** [peer] announced [id], a broadcast this node has not seen. */
    fun announced(
        peer: NodeId,
        id: ByteArray,
    ) {
        val key = id.toHexString()
        val known = pending[key]
        if (known != null) return known.add(peer)
        if (pending.size == PENDING_MAX) pending.getValue(pending.keys.first()).cancel()
        pending[key] = Pending(key, id, peer)
    }

    /** The link to [peer] closed: a new one starts eager, and [peer] announces nothing any more. */
    fun unlinked(peer: NodeId) {
        lazy -= peer
        pending.values.forEach { it.remove(peer) }
    }

    private suspend fun graft(
        key: String,
        id: ByteArray,
        graft: Pending,
    ) {
        val bytes = LinkFrame.Graft(id).encode()
        delay(GRAFT_BASE_MILLIS)
        var peer = graft.next()
        while (peer != null) {
            lanes.put(peer, Parcel(bytes, relayed = false, { bytes }) {})
            lazy -= peer
            delay(GRAFT_PER_ANNOUNCER_MILLIS)
            peer = graft.next()
        }
        pending -= key
    }

    // A frame held for grafts until a wall-clock millisecond.
    private class Held(
        val parcel: Parcel,
        val untilMillis: Long,
    )

    // An ID awaiting its frame since [first] announced it, and its grafts: the announcers not yet grafted, in the
    // order they announced it.
    private inner class Pending(
        private val key: String,
        id: ByteArray,
        first: NodeId,
    ) {
        private val heard = mutableSetOf(first)
        private val untried = ArrayDeque(listOf(first))
        private val job: Job = scope.launch { graft(key, id, this@Pending) }

        // Ends its grafts and forgets it.
        fun cancel() {
            job.cancel()
            pending -= key
        }

        fun add(peer: NodeId) {
            if (heard.add(peer)) untried += peer
        }

        fun remove(peer: NodeId) {
            heard -= peer
            untried -= peer
        }

        fun next(): NodeId? = untried.removeFirstOrNull()
    }

    companion object {
        /** `catch-up-offered-max` (ADR 0014 decision 7): the most frames held for grafts. */
        const val HELD_MAX: Int = 128

        /** `catch-up-offered-max` (ADR 0014 decision 7): the most IDs pending a graft. */
        const val PENDING_MAX: Int = 128

        /** The message cache for grafts (v1 parameters, "Mesh broadcast"), capped by each message's expiry. */
        const val HOLD_MILLIS: Long = 60_000

        /** `graft-timeout-base`. */
        const val GRAFT_BASE_MILLIS: Long = 500

        /** `graft-timeout-per-announcer`. */
        const val GRAFT_PER_ANNOUNCER_MILLIS: Long = 250

        private const val MILLIS_PER_SECOND = 1_000L
        private val PRUNE = LinkFrame.Prune.encode()
    }
}
