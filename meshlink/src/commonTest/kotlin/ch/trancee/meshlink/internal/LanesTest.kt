package ch.trancee.meshlink.internal

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LanesTest {
    private val lanes = Lanes()
    private val peer = nodeId(1)

    private fun parcel(
        id: Int,
        relayed: Boolean = true,
        kind: Parcel.Kind = Parcel.Kind.OTHER,
    ) = Parcel(messageId(id), relayed, { byteArrayOf(id.toByte()) }, kind = kind) {}

    private suspend fun Lanes.order(): List<Int> = drain(peer).map { it.render()[0].toInt() }

    @Test
    fun aFullLaneDropsANewFrame() =
        runTest {
            // Arrange
            lanes.open(peer)
            repeat(Lanes.CAPACITY) { assertTrue(lanes.put(peer, parcel(it))) }

            // Act
            val taken = lanes.put(peer, parcel(Lanes.CAPACITY))

            // Assert
            assertFalse(taken)
            assertEquals((0 until Lanes.CAPACITY).toList(), lanes.drain(peer).map { it.render()[0].toInt() })
        }

    @Test
    fun aFullLaneStillTakesThisNodesOwnFrame() =
        runTest {
            // Arrange
            lanes.open(peer)
            repeat(Lanes.CAPACITY) { lanes.put(peer, parcel(it)) }

            // Act
            val taken = lanes.put(peer, parcel(Lanes.CAPACITY, relayed = false))

            // Assert
            assertTrue(taken)
            assertEquals(Lanes.CAPACITY + 1, lanes.drain(peer).size)
        }

    @Test
    fun ownFramesDoNotCountAgainstTheRelayCap() =
        runTest {
            // Arrange
            lanes.open(peer)
            repeat(Lanes.CAPACITY) { lanes.put(peer, parcel(it, relayed = false)) }

            // Act
            val taken = lanes.put(peer, parcel(Lanes.CAPACITY))

            // Assert
            assertTrue(taken)
        }

    @Test
    fun aParcelPutBackIsTakenFirst() =
        runTest {
            // Arrange
            lanes.open(peer)
            lanes.put(peer, parcel(1))
            val failed = parcel(2)

            // Act
            lanes.putBack(peer, failed)

            // Assert
            assertEquals(listOf(2, 1), lanes.drain(peer).map { it.render()[0].toInt() })
        }

    @Test
    fun aParcelPutBackOnAClosedLaneIsDropped() =
        runTest {
            // Arrange
            lanes.open(peer)
            lanes.close(peer)

            // Act
            lanes.putBack(peer, parcel(1))

            // Assert
            assertEquals(emptyList(), lanes.peers)
        }

    @Test
    fun aFrameWhoseMessageIdIsWaitingReplacesItInPlaceEvenInAFullLane() =
        runTest {
            // Arrange
            lanes.open(peer)
            repeat(Lanes.CAPACITY) { lanes.put(peer, parcel(it)) }
            val replacement = Parcel(messageId(1), relayed = true, { byteArrayOf(-1) }) {}

            // Act
            val taken = lanes.put(peer, replacement)

            // Assert
            assertTrue(taken)
            val drained = lanes.drain(peer)
            assertEquals(Lanes.CAPACITY, drained.size)
            assertSame(replacement, drained[1])
        }

    @Test
    fun aClosedLaneTakesNoFrameAndDropsItsFrames() =
        runTest {
            // Arrange
            lanes.open(peer)
            lanes.put(peer, parcel(1))

            // Act
            lanes.close(peer)

            // Assert
            assertFalse(lanes.put(peer, parcel(2)))
            assertEquals(emptyList(), lanes.peers)
            lanes.open(peer)
            assertEquals(emptyList(), lanes.drain(peer))
        }

    @Test
    fun openingAnOpenLaneKeepsItsFrames() =
        runTest {
            // Arrange
            lanes.open(peer)
            lanes.put(peer, parcel(1))

            // Act
            lanes.open(peer)

            // Assert
            assertEquals(listOf(peer), lanes.peers)
            assertEquals(1, lanes.drain(peer).size)
        }

    @Test
    fun nextWaitsForTheLaneToOpenAndForAFrame() =
        runTest {
            // Arrange
            val next = async(start = CoroutineStart.UNDISPATCHED) { lanes.next(peer) }
            lanes.open(peer)
            runCurrent()
            assertFalse(next.isCompleted)
            val parcel = parcel(1)

            // Act
            lanes.put(peer, parcel)
            runCurrent()

            // Assert
            assertSame(parcel, next.await())
        }

    @Test
    fun aFrameNoLongerWantedIsSkipped() =
        runTest {
            // Arrange
            lanes.open(peer)
            lanes.put(peer, Parcel(messageId(1), relayed = false, { byteArrayOf(1) }, wanted = { false }) {})
            val wanted = parcel(2)
            lanes.put(peer, wanted)

            // Act
            val next = lanes.next(peer)

            // Assert
            assertSame(wanted, next)
        }

    @Test
    fun aTakenFrameIsBeingWrittenUntilItSettles() =
        runTest {
            // Arrange
            lanes.open(peer)
            lanes.put(peer, parcel(1))
            lanes.next(peer)
            val taken = lanes.writing(messageId(1))

            // Act
            lanes.written(peer)

            // Assert
            assertTrue(taken)
            assertFalse(lanes.writing(messageId(1)))
        }

    @Test
    fun aWaitingFrameIsNotBeingWritten() =
        runTest {
            // Arrange
            lanes.open(peer)

            // Act
            lanes.put(peer, parcel(1))

            // Assert
            assertFalse(lanes.writing(messageId(1)))
        }

    @Test
    fun aClosedLaneWritesNothing() =
        runTest {
            // Arrange
            lanes.open(peer)
            lanes.put(peer, parcel(1))
            lanes.next(peer)

            // Act
            lanes.close(peer)

            // Assert
            assertFalse(lanes.writing(messageId(1)))
        }

    @Test
    fun withPriorityABroadcastGoesAheadOfWaitingMessagesBehindOtherFrames() =
        runTest {
            // Arrange
            val lanes = Lanes(broadcastPriority = true).apply { open(peer) }
            lanes.put(peer, parcel(1))
            lanes.put(peer, parcel(2, kind = Parcel.Kind.MESSAGE))
            lanes.put(peer, parcel(3, kind = Parcel.Kind.BROADCAST))
            lanes.put(peer, parcel(4, kind = Parcel.Kind.MESSAGE))

            // Act
            lanes.put(peer, parcel(5, relayed = false, kind = Parcel.Kind.BROADCAST))

            // Assert
            assertEquals(listOf(1, 3, 5, 2, 4), lanes.order())
        }

    @Test
    fun withoutPriorityABroadcastWaitsBehindMessages() =
        runTest {
            // Arrange
            lanes.open(peer)
            lanes.put(peer, parcel(1, kind = Parcel.Kind.MESSAGE))

            // Act
            lanes.put(peer, parcel(2, kind = Parcel.Kind.BROADCAST))

            // Assert
            assertEquals(listOf(1, 2), lanes.order())
        }

    @Test
    fun aWaitingMessageIsOvertakenByAtMostThreeBroadcasts() =
        runTest {
            // Arrange
            val lanes = Lanes(broadcastPriority = true).apply { open(peer) }
            lanes.put(peer, parcel(0, kind = Parcel.Kind.MESSAGE))

            // Act
            (1..5).forEach { lanes.put(peer, parcel(it, kind = Parcel.Kind.BROADCAST)) }

            // Assert
            assertEquals(listOf(1, 2, 3, 0, 4, 5), lanes.order())
        }

    @Test
    fun aMessageQueuedLaterCanStillBeOvertaken() =
        runTest {
            // Arrange
            val lanes = Lanes(broadcastPriority = true).apply { open(peer) }
            lanes.put(peer, parcel(0, kind = Parcel.Kind.MESSAGE))
            (1..3).forEach { lanes.put(peer, parcel(it, kind = Parcel.Kind.BROADCAST)) }
            lanes.put(peer, parcel(4, kind = Parcel.Kind.MESSAGE))

            // Act
            lanes.put(peer, parcel(5, kind = Parcel.Kind.BROADCAST))

            // Assert
            assertEquals(listOf(1, 2, 3, 0, 5, 4), lanes.order())
        }

    @Test
    fun aMessageReplacedInPlaceKeepsItsOvertakenCount() =
        runTest {
            // Arrange
            val lanes = Lanes(broadcastPriority = true).apply { open(peer) }
            lanes.put(peer, parcel(0, kind = Parcel.Kind.MESSAGE))
            (1..3).forEach { lanes.put(peer, parcel(it, kind = Parcel.Kind.BROADCAST)) }

            // Act
            lanes.put(peer, parcel(0, kind = Parcel.Kind.MESSAGE))
            lanes.put(peer, parcel(4, kind = Parcel.Kind.BROADCAST))

            // Assert
            assertEquals(listOf(1, 2, 3, 0, 4), lanes.order())
        }
}
