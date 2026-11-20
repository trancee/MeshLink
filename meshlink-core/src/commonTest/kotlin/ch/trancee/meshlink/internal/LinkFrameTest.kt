package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LinkFrameTest {
    @Test
    fun anUnknownCloseReasonMeansNormal() {
        assertEquals(CloseReason.DUPLICATE_LINK, LinkFrame.Close(1).reason)
        assertEquals(CloseReason.NORMAL, LinkFrame.Close(UNKNOWN_REASON).reason)
        assertEquals(CloseReason.NORMAL, CloseReason.of(-1))
        CloseReason.entries.forEach { assertEquals(it, LinkFrame.Close(it).reason) }
    }

    @Test
    fun anUnknownBusyReasonMeansReassemblySlots() {
        assertEquals(BusyReason.LARGE_BUDGET, LinkFrame.Busy(1, BusyReason.LARGE_BUDGET).reason)
        assertEquals(BusyReason.REASSEMBLY_SLOTS, LinkFrame.Busy(1, 0).reason)
        assertEquals(BusyReason.REASSEMBLY_SLOTS, LinkFrame.Busy(1, UNKNOWN_REASON).reason)
        BusyReason.entries.forEach { assertEquals(it, BusyReason.of(it.code)) }
    }

    @Test
    fun fragmentsAndBusyRoundTrip() {
        val data = ByteArray(LinkFrame.Fragment.DATA_MAX)
        val fragment = LinkFrame.Fragment(STREAM, LinkFrame.Fragment.NUMBER_MAX, true, data)
        val busy = LinkFrame.Busy(STREAM, BusyReason.LARGE_BUDGET)

        val decoded = (LinkFrame.decode(fragment.encode()) as Decoded.Valid).value as LinkFrame.Fragment
        val busyDecoded = (LinkFrame.decode(busy.encode()) as Decoded.Valid).value as LinkFrame.Busy

        assertEquals(listOf(STREAM, LinkFrame.Fragment.NUMBER_MAX), listOf(decoded.streamId, decoded.number))
        assertEquals(true, decoded.last)
        assertEquals(LinkFrame.Fragment.DATA_MAX, decoded.data.size)
        assertEquals(listOf(STREAM, BusyReason.LARGE_BUDGET.code), listOf(busyDecoded.streamId, busyDecoded.code))
    }

    @Test
    fun typesThisDecoderDoesNotHandleAreUnknownAndAnEmptyFrameIsTruncated() {
        listOf("1100", "40", "80", "bf").forEach {
            assertEquals(DecodeError.UNKNOWN_TYPE, (LinkFrame.decode(it.hex()) as Decoded.Invalid).error, it)
        }
        assertEquals(DecodeError.TRUNCATED, (LinkFrame.decode(ByteArray(0)) as Decoded.Invalid).error)
    }

    @Test
    fun framesRejectOutOfRangeFields() {
        val hello = LinkFrame.PlainHello(1, ByteArray(DIGEST), ByteArray(DIGEST)).encode()
        hello[1] = 0

        assertEquals(DecodeError.OUT_OF_RANGE, (LinkFrame.decode(hello) as Decoded.Invalid).error)
        assertFailsWith<IllegalArgumentException> { LinkFrame.PlainHello(0, ByteArray(DIGEST), ByteArray(DIGEST)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.PlainHello(256, ByteArray(DIGEST), ByteArray(DIGEST)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.PlainHello(1, ByteArray(DIGEST - 1), ByteArray(DIGEST)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.PlainHello(1, ByteArray(DIGEST), ByteArray(DIGEST + 1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Close(-1) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Credit(0) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Credit(CREDITS_MAX + 1) }
        val credit = (LinkFrame.decode(LinkFrame.Credit(CREDITS_MAX).encode()) as Decoded.Valid).value
        assertEquals(CREDITS_MAX, (credit as LinkFrame.Credit).credits)
        assertFailsWith<IllegalArgumentException> { LinkFrame.Handshake(0, 1, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Handshake(256, 1, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Handshake(1, 0, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Handshake(1, 4, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Handshake(1, 1, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> {
            LinkFrame.Handshake(1, 1, ByteArray(LinkFrame.Handshake.NOISE_MESSAGE_MAX + 1))
        }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Close(256) }
        assertFailsWith<IllegalArgumentException> { ResolvingKey(ByteArray(ResolvingKey.KEY_LENGTH - 1), 0u) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Fragment(-1, 0, false, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Fragment(256, 0, false, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Fragment(0, -1, false, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> {
            LinkFrame.Fragment(0, LinkFrame.Fragment.NUMBER_MAX + 1, false, ByteArray(1))
        }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Fragment(0, 0, false, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> {
            LinkFrame.Fragment(0, 0, false, ByteArray(LinkFrame.Fragment.DATA_MAX + 1))
        }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Busy(-1, 1) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Busy(256, 1) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Busy(0, 256) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Busy(0, -1) }
    }

    @Test
    fun identifierFramesRejectWrongSizesAndCounts() {
        val id = ByteArray(ID)
        val full = List(LinkFrame.NeighborList.NEIGHBORS_MAX) { ByteArray(ID) { _ -> it.toByte() } }

        val list = (LinkFrame.decode(LinkFrame.NeighborList(id, id, full).encode()) as Decoded.Valid).value

        assertEquals(full.map { it.toList() }, (list as LinkFrame.NeighborList).neighbors.map { it.toList() })
        assertFailsWith<IllegalArgumentException> { LinkFrame.NeighborList(ByteArray(ID - 1), id, emptyList()) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.NeighborList(id, ByteArray(ID + 1), emptyList()) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.NeighborList(id, id, full + listOf(id)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.NeighborList(id, id, listOf(ByteArray(ID - 1))) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Shuffle(emptyList()) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Shuffle(List(LinkFrame.Shuffle.ENTRIES_MAX + 1) { id }) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Shuffle(listOf(ByteArray(ID + 1))) }
        assertFailsWith<IllegalArgumentException> {
            LinkFrame.ShuffleReply(List(LinkFrame.Shuffle.ENTRIES_MAX + 1) { id })
        }
        assertFailsWith<IllegalArgumentException> { LinkFrame.ShuffleReply(listOf(ByteArray(ID - 1))) }
    }

    @Test
    fun plumtreeFramesRejectWrongSizesAndCounts() {
        val full = List(LinkFrame.Ihave.IDS_MAX) { ByteArray(MESSAGE_ID) { _ -> it.toByte() } }

        val ihave = (LinkFrame.decode(LinkFrame.Ihave(full).encode()) as Decoded.Valid).value

        assertEquals(full.map { it.toList() }, (ihave as LinkFrame.Ihave).messageIds.map { it.toList() })
        assertFailsWith<IllegalArgumentException> { LinkFrame.Ihave(emptyList()) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Ihave(full + listOf(ByteArray(MESSAGE_ID))) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Ihave(listOf(ByteArray(MESSAGE_ID + 1))) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Graft(ByteArray(MESSAGE_ID - 1)) }
    }

    @Test
    fun aCatchUpOverItsMaximumKeepsTheNewestEntriesOfEachList() {
        val offered = List(LinkFrame.CatchUp.OFFERED_MAX + 2) { id(it, MESSAGE_ID) }
        val held = List(LinkFrame.CatchUp.HELD_MAX + 2) { LinkFrame.CatchUp.Held(id(it, MESSAGE_ID), id(it, ID)) }
        val tombstones =
            List(LinkFrame.CatchUp.TOMBSTONES_MAX + 2) { LinkFrame.CatchUp.Tombstone(id(it, MESSAGE_ID), id(it, K)) }

        val frame = LinkFrame.CatchUp.newest(offered, held, tombstones)

        assertEquals(offered.drop(2).map { it.toList() }, frame.offered.map { it.toList() })
        assertEquals(held.drop(2).map { it.destinationTag.toList() }, frame.held.map { it.destinationTag.toList() })
        assertEquals(tombstones.drop(2).map { it.k.toList() }, frame.tombstones.map { it.k.toList() })
        assertEquals(CATCH_UP_MAX, frame.encode().size)
    }

    @Test
    fun aCatchUpRejectsListsOverTheirMaximumAndEntriesOfTheWrongSize() {
        val empty = LinkFrame.CatchUp(emptyList(), emptyList(), emptyList()).encode()
        val heldOver = byteArrayOf(CATCH_UP, 0, 0, 0, (LinkFrame.CatchUp.HELD_MAX + 1).toByte())
        val tombstonesOver = byteArrayOf(CATCH_UP, 0, 0, 0, 0, 0, (LinkFrame.CatchUp.TOMBSTONES_MAX + 1).toByte())

        val decoded = listOf(heldOver, tombstonesOver).map { (LinkFrame.decode(it) as Decoded.Invalid).error }

        assertEquals(listOf(DecodeError.OUT_OF_RANGE, DecodeError.OUT_OF_RANGE), decoded)
        assertEquals(listOf(CATCH_UP, 0, 0, 0, 0, 0, 0), empty.toList())
        assertFailsWith<IllegalArgumentException> {
            LinkFrame.CatchUp(List(LinkFrame.CatchUp.OFFERED_MAX + 1) { id(0, MESSAGE_ID) }, emptyList(), emptyList())
        }
        assertFailsWith<IllegalArgumentException> { LinkFrame.CatchUp(listOf(id(0, ID)), emptyList(), emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            LinkFrame.CatchUp(
                emptyList(),
                List(LinkFrame.CatchUp.HELD_MAX + 1) { LinkFrame.CatchUp.Held(id(0, MESSAGE_ID), id(0, ID)) },
                emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            val tombstone = LinkFrame.CatchUp.Tombstone(id(0, MESSAGE_ID), id(0, K))
            LinkFrame.CatchUp(emptyList(), emptyList(), List(LinkFrame.CatchUp.TOMBSTONES_MAX + 1) { tombstone })
        }
        assertFailsWith<IllegalArgumentException> { LinkFrame.CatchUp.Held(id(0, ID), id(0, ID)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.CatchUp.Held(id(0, MESSAGE_ID), id(0, MESSAGE_ID)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.CatchUp.Tombstone(id(0, ID), id(0, K)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.CatchUp.Tombstone(id(0, MESSAGE_ID), id(0, ID)) }
    }

    // [size] bytes of [value].
    private fun id(
        value: Int,
        size: Int,
    ) = ByteArray(size) { value.toByte() }

    private companion object {
        const val ID = 8
        const val MESSAGE_ID = 12
        const val CREDITS_MAX = 0xFFFF
        const val UNKNOWN_REASON = 0x7F
        const val DIGEST = 32
        const val STREAM = 0xFE
        const val K = 16
        const val CATCH_UP: Byte = 0x0A
        const val CATCH_UP_MAX = 7_127
    }
}
