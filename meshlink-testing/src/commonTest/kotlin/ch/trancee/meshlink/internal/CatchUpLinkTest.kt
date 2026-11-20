package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each side of a kept link sends one `CATCH_UP` on it, and a link closed as duplicate sends none (spec "Catch-up"). */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
class CatchUpLinkTest {
    @Test
    fun aSecondLinkClosedAsDuplicateAddsNoCatchUpToTheKeptLink() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            val first = connectFake(air, alice)
            first.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val second = connectFake(air, alice)

            // Act
            second.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            grantCredits(first, alice.radio.ends[0])
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(1, 0), alice.radio.ends.map { end -> clearFrames(end).count { it[0] == CATCH_UP } })
        }

    @Test
    fun aLinkReplacingADuplicateWhoseCatchUpWaitsSendsOneCatchUp() =
        runTest {
            // Arrange: the inbound link gets no credit, so its CATCH_UP waits in the lane.
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            connectFake(air, alice).write(record(LinkFrame.PlainHello(1, digest(MESH), HIGH_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val outbound = advertiseFake(air, versions = listOf(1))
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            outbound.ends.single().write(record(LinkFrame.PlainHello(1, digest(MESH), HIGH_ID)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(0, 1), alice.radio.ends.map { end -> clearFrames(end).count { it[0] == CATCH_UP } })
        }

    private companion object {
        const val CATCH_UP: Byte = 0x0A

        // Above any real NodeId, so this node keeps the link it dials to the fake.
        val HIGH_ID = ByteArray(32) { 0xFF.toByte() }
    }
}
