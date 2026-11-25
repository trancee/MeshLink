package ch.trancee.meshlink.internal

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ReassemblyTest {
    private val clock = ManualClock()
    private val budget = ReassemblyBudget()

    @Test
    fun interleavedStreamsUpToFrameMaxArriveIntact() {
        val frames = listOf(Reassembly.FRAME_MAX, Reassembly.FRAME_SMALL_MAX, DATA + 1, SOME).map(::frame)
        val reassembly = Reassembly(budget, clock)
        val pieces =
            frames.mapIndexed { stream, frame ->
                val fragments = LinkFrame.Fragment.split(stream, frame, DATA)
                fragments.toMutableList()
            }
        val done = arrayOfNulls<ByteArray>(frames.size)

        while (pieces.any { it.isNotEmpty() }) {
            pieces.forEachIndexed { stream, left ->
                if (left.isNotEmpty()) {
                    val result = reassembly.accept(left.removeAt(0))
                    if (result is Reassembled.Complete) done[stream] = result.frame
                }
            }
        }

        frames.forEachIndexed { stream, frame -> assertContentEquals(frame, done[stream], "stream $stream") }
        assertEquals(0, budget.used)
    }

    @Test
    fun splitCutsDataMaxPiecesAndMarksOnlyTheLast() {
        val pieces = LinkFrame.Fragment.split(STREAM, frame(2 * DATA + 1), DATA)

        assertEquals(listOf(DATA, DATA, 1), pieces.map { it.data.size })
        assertEquals(listOf(0, 1, 2), pieces.map { it.number })
        assertEquals(listOf(false, false, true), pieces.map { it.last })
        assertEquals(listOf(DATA), LinkFrame.Fragment.split(STREAM, frame(DATA), DATA).map { it.data.size })
    }

    @Test
    fun aFragmentThatIsNotTheNextOneIsFatal() {
        val pieces = split(STREAM, 3 * DATA)

        assertSame(Reassembled.Fatal, Reassembly(budget, clock).accept(pieces[1]))
        assertSame(Reassembled.Fatal, feed(Reassembly(budget, clock), pieces[0], pieces[2]))
        assertSame(Reassembled.Fatal, feed(Reassembly(budget, clock), pieces[0], pieces[0]))
        assertSame(Reassembled.Fatal, feed(Reassembly(budget, clock), *pieces.toTypedArray(), pieces[1]))
    }

    @Test
    fun aStreamAboveItsLimitIsFatalAndClearFreesItsBudget() {
        val reassembly = Reassembly(budget, clock)
        val handshake = Reassembly(budget, clock)

        val tooLong = feed(reassembly, *split(STREAM, Reassembly.FRAME_MAX + 1).toTypedArray())
        val tooLongHandshake = split(STREAM, LIMIT + 1).map { handshake.accept(it, LIMIT) }.last()
        reassembly.clear()

        assertSame(Reassembled.Fatal, tooLong)
        assertSame(Reassembled.Fatal, tooLongHandshake)
        assertEquals(0, budget.used)
    }

    @Test
    fun aNestedFragmentIsFatal() {
        val nested = LinkFrame.Fragment(STREAM, 0, true, ByteArray(DATA)).encode()

        val result = Reassembly(budget, clock).accept(LinkFrame.Fragment.split(STREAM, nested, DATA)[0])

        assertSame(Reassembled.Fatal, result)
    }

    @Test
    fun aFifthStreamIsRefusedOnceAndItsIdIsFreeAgainForANewStream() {
        val reassembly = Reassembly(budget, clock)
        (0 until Reassembly.SLOTS).forEach { reassembly.accept(split(it, SOME)[0]) }
        val fifth = split(STREAM, 3 * DATA)

        val busy = assertIs<Reassembled.Refused>(reassembly.accept(fifth[0])).busy
        val dropped = fifth.drop(1).map { reassembly.accept(it) }
        val afterLast = reassembly.accept(fifth[1])
        reassembly.accept(LinkFrame.Fragment(0, 1, true, ByteArray(1)))
        val retried = feed(reassembly, *fifth.toTypedArray())

        assertEquals(listOf(STREAM, BusyReason.REASSEMBLY_SLOTS.code), listOf(busy.streamId, busy.code))
        assertEquals(listOf(Reassembled.Waiting, Reassembled.Waiting), dropped)
        assertSame(Reassembled.Fatal, afterLast)
        assertContentEquals(frame(3 * DATA), assertIs<Reassembled.Complete>(retried).frame)
    }

    @Test
    fun aSecondLargeStreamOnOneLinkIsRefused() {
        val reassembly = Reassembly(budget, clock)
        val largeAt = Reassembly.FRAME_SMALL_MAX / DATA

        feed(reassembly, *split(0, LARGE).take(largeAt + 1).toTypedArray())
        val second = feed(reassembly, *split(1, LARGE).take(largeAt + 1).toTypedArray())

        val busy = assertIs<Reassembled.Refused>(second).busy
        assertEquals(listOf(1, BusyReason.REASSEMBLY_SLOTS.code), listOf(busy.streamId, busy.code))
        assertEquals((largeAt + 1) * DATA, budget.used)
    }

    @Test
    fun largeStreamsAcrossLinksShareTheNodeBudget() {
        val links = List(LINKS) { Reassembly(budget, clock) }
        val big = split(STREAM, Reassembly.FRAME_MAX)
        val held = big.dropLast(1).toTypedArray()

        val results = links.map { link -> held.map { link.accept(it) }.firstOrNull { it is Reassembled.Refused } }
        links.first().clear()
        val retried = feed(links.last(), *big.toTypedArray())

        assertEquals(List(LINKS - 1) { null }, results.dropLast(1))
        assertEquals(BusyReason.LARGE_BUDGET, assertIs<Reassembled.Refused>(results.last()).busy.reason)
        assertIs<Reassembled.Complete>(retried)
    }

    @Test
    fun anIdleStreamIsDiscardedAndItsIdIsFreeAgain() {
        val reassembly = Reassembly(budget, clock)
        val early = Reassembly(budget, clock)
        val pieces = split(STREAM, 3 * DATA)
        reassembly.accept(pieces[0])
        early.accept(pieces[0])

        clock.now += Reassembly.IDLE_TIMEOUT_MILLIS - 1
        val beforeTimeout = early.accept(pieces[0])
        clock.now += 1
        val afterTimeout = reassembly.accept(pieces[0])

        assertSame(Reassembled.Fatal, beforeTimeout)
        assertSame(Reassembled.Waiting, afterTimeout)
    }

    @Test
    fun theBudgetDiscardsIdleStreamsOnOtherLinksBeforeRefusing() {
        val big = split(STREAM, Reassembly.FRAME_MAX)
        val held = big.dropLast(1).toTypedArray()
        feed(Reassembly(budget, clock), *held)
        clock.now += Reassembly.IDLE_TIMEOUT_MILLIS
        repeat(LINKS - 2) { feed(Reassembly(budget, clock), *held) }

        val result = feed(Reassembly(budget, clock), *big.toTypedArray())

        assertIs<Reassembled.Complete>(result)
    }

    @Test
    fun clearFreesEveryStream() {
        val reassembly = Reassembly(budget, clock)
        val big = split(STREAM, Reassembly.FRAME_MAX)
        feed(reassembly, *big.dropLast(1).toTypedArray())
        reassembly.accept(split(1, SOME)[0])

        reassembly.clear()

        assertEquals(0, budget.used)
        assertSame(Reassembled.Waiting, reassembly.accept(big[0]))
    }

    private fun split(
        stream: Int,
        size: Int,
    ): List<LinkFrame.Fragment> = LinkFrame.Fragment.split(stream, frame(size), DATA)

    private fun feed(
        reassembly: Reassembly,
        vararg pieces: LinkFrame.Fragment,
    ): Reassembled = pieces.map { reassembly.accept(it) }.last()

    private fun frame(size: Int): ByteArray = Random(size).nextBytes(size).also { it[0] = TYPE }

    private class ManualClock : Clock {
        var now = 0L

        override fun monotonicMillis(): Long = now

        override fun wallClockMillis(): Long = now
    }

    private companion object {
        const val DATA = 1_004
        const val STREAM = 7
        const val SOME = 5_000
        const val LARGE = 20_000
        const val LIMIT = 4_099

        /** Links enough that their largest streams, one each, pass the budget only at the last. */
        const val LINKS = ReassemblyBudget.BYTES / Reassembly.FRAME_MAX + 1
        const val TYPE: Byte = 0x40
    }
}
