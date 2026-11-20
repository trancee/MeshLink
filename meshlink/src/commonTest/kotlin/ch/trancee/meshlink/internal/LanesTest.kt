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
    ) = Parcel(messageId(id), relayed, { byteArrayOf(id.toByte()) }) {}

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
}
