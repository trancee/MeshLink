package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestCoroutineScheduler

/** A clock on [scheduler]'s virtual time; the wall clock starts at [WALL_START_MILLIS]. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SchedulerClock(
    private val scheduler: TestCoroutineScheduler,
) : Clock {
    override fun monotonicMillis(): Long = scheduler.currentTime

    override fun wallClockMillis(): Long = WALL_START_MILLIS + scheduler.currentTime

    companion object {
        /** 2026-01-01T00:00:00Z, the start of an epoch. */
        const val WALL_START_MILLIS: Long = 1_767_225_600_000
    }
}

/** The frames now waiting in [peer]'s lane, taken in order. */
internal suspend fun Lanes.drain(peer: NodeId): List<Parcel> =
    coroutineScope {
        val taken = mutableListOf<Parcel>()
        while (true) {
            val next = async(start = CoroutineStart.UNDISPATCHED) { next(peer) }
            if (!next.isCompleted) {
                next.cancel()
                break
            }
            taken += next.await()
        }
        taken
    }

/** A NodeId of 32 bytes [n]. */
internal fun nodeId(n: Int): NodeId = NodeId(ByteArray(32) { n.toByte() })

/** A tag of 8 bytes [n]. */
internal fun tag(n: Int): ByteArray = ByteArray(8) { n.toByte() }

/** A message_id of 12 bytes [n]. */
internal fun messageId(n: Int): ByteArray = ByteArray(12) { n.toByte() }

/** The bytes of a unicast frame of [type] with [body]. */
internal fun unicast(
    type: Int,
    hopLimit: Int,
    id: ByteArray,
    expiry: Long,
    destination: ByteArray,
    source: ByteArray,
    body: ByteArray = byteArrayOf(),
): ByteArray = UnicastFrame(type, UnicastHeader(hopLimit, id, expiry.toUInt(), destination, source), body).encode()

/** The body of a `MESSAGE` frame with [container]: a zero commitment, then the container. */
internal fun messageBody(container: Container): ByteArray = ByteArray(16) + container.encode()
