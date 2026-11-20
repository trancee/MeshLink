package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The outbox's retry schedule, new-link retries, status and capacity, with a recording transmit.
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxTest {
    private val capacity = Capacity()
    private var density = DensityClass.LOW
    private val attempts = mutableListOf<Pair<Long, NodeId>>()

    private fun TestScope.outbox(): Outbox {
        val outbox =
            Outbox(SchedulerClock(testScheduler), backgroundScope, capacity, { density }) {
                attempts += testScheduler.currentTime to it.to
                it.sentMillis = testScheduler.currentTime
            }
        outbox.begin()
        return outbox
    }

    private fun TestScope.entry(
        to: NodeId = PEER,
        size: Int = 1,
        lifetimeMillis: Long = 600_000,
    ): Outbox.Entry {
        val draft = UnsecuredMessaging(SodiumCryptoProvider, SENDER).draft(to.toByteArray(), ByteArray(1), 1u, 1)
        val deadline = SchedulerClock(testScheduler).wallClockMillis() + lifetimeMillis
        return Outbox.Entry(to, draft, size, MutableStateFlow(DeliveryStatus.Queued), deadline)
    }

    private fun TestScope.times(untilMillis: Long): List<Long> {
        advanceTimeBy(untilMillis - testScheduler.currentTime)
        runCurrent()
        return attempts.map { it.first }
    }

    @Test
    fun lowAndMediumClassesRetryAfterIntervalsDoublingFromTwoToThirtySecondsThenAtThirty() =
        runTest {
            // Arrange
            val outbox = outbox()

            // Act
            outbox.add(entry())

            // Assert
            assertEquals(listOf(0L, 2_000, 6_000, 14_000, 30_000, 60_000, 90_000), times(90_000))
            density = DensityClass.MEDIUM
            assertEquals(120_000L, times(120_000).last())
        }

    @Test
    fun theHighClassRetriesAfterIntervalsDoublingFromFiveToSixtySecondsThenAtSixty() =
        runTest {
            // Arrange
            density = DensityClass.HIGH
            val outbox = outbox()

            // Act
            outbox.add(entry())

            // Assert
            assertEquals(listOf(0L, 5_000, 15_000, 35_000, 75_000, 135_000, 195_000), times(195_000))
        }

    @Test
    fun aMessageExpiresAtItsDeadlineStopsRetryingAndOnlyThenFreesItsBytes() =
        runTest {
            // Arrange
            val outbox = outbox()
            capacity.reserve(Capacity.LIMIT.toInt())
            val entry = entry(size = Capacity.LIMIT.toInt(), lifetimeMillis = 10_000)
            outbox.add(entry)
            entry.sent()

            // Act
            advanceTimeBy(9_999)
            val before = capacity.reserve(0)
            advanceTimeBy(1)
            runCurrent()

            // Assert
            assertFalse(before)
            assertEquals(DeliveryStatus.Expired, entry.status.value)
            assertTrue(capacity.reserve(0))
            assertEquals(listOf(0L, 2_000, 6_000), times(60_000))
        }

    @Test
    fun aNewLinkRetriesEachDestinationAtMostOncePerGapInLowAndMediumClasses() =
        runTest {
            // Arrange
            val outbox = outbox()
            outbox.add(entry(to = PEER))
            outbox.add(entry(to = PEER))
            outbox.add(entry(to = OTHER))
            attempts.clear()

            // Act
            outbox.linked()
            advanceTimeBy(Outbox.LINK_RETRY_GAP_MILLIS - 1)
            density = DensityClass.MEDIUM
            outbox.linked()
            advanceTimeBy(1)
            outbox.linked()

            // Assert
            assertEquals(listOf(PEER, PEER, OTHER), attempts.filter { it.first == 0L }.map { it.second })
            assertEquals(emptyList(), attempts.filter { it.first == Outbox.LINK_RETRY_GAP_MILLIS - 1 })
            assertEquals(3, attempts.count { it.first == Outbox.LINK_RETRY_GAP_MILLIS })
        }

    @Test
    fun aDestinationWithNothingWaitingAtANewLinkIsRetriedAgainOnTheNextOne() =
        runTest {
            // Arrange
            val outbox = outbox()
            outbox.add(entry(lifetimeMillis = 1_000))
            outbox.linked()
            advanceTimeBy(2_000)
            outbox.linked()
            outbox.add(entry())
            attempts.clear()

            // Act
            advanceTimeBy(1_000)
            outbox.linked()

            // Assert
            assertEquals(listOf(3_000L to PEER), attempts)
        }

    @Test
    fun theHighClassDoesNotRetryOnANewLink() =
        runTest {
            // Arrange
            density = DensityClass.HIGH
            val outbox = outbox()
            outbox.add(entry())
            attempts.clear()

            // Act
            outbox.linked()

            // Assert
            assertEquals(emptyList(), attempts)
        }

    @Test
    fun withNoLinkLeftAQueuedMessageIsHeldAndASentOneStaysSent() =
        runTest {
            // Arrange
            val outbox = outbox()
            val (queued, sent) = entry() to entry()
            outbox.add(queued)
            outbox.add(sent)
            sent.sent()

            // Act
            outbox.unreachable()

            // Assert
            assertEquals(DeliveryStatus.Held, queued.status.value)
            assertEquals(DeliveryStatus.Sent, sent.status.value)
            queued.sent()
            assertEquals(DeliveryStatus.Sent, queued.status.value)
        }

    @Test
    fun aWriteSettledAfterItsMessageFinishedLeavesTheFinalStatus() =
        runTest {
            // Arrange
            val outbox = outbox()
            val entry = entry()
            outbox.add(entry)

            // Act
            outbox.end()
            entry.sent()
            entry.hold()

            // Assert
            assertEquals(DeliveryStatus.Failed(FailureReason.Stopped), entry.status.value)
        }

    @Test
    fun aMessageAddedAfterTheRunEndedFailsWithStoppedAndFreesItsBytes() =
        runTest {
            // Arrange
            val outbox = outbox()
            outbox.end()
            capacity.reserve(Capacity.LIMIT.toInt())
            val entry = entry(size = Capacity.LIMIT.toInt())

            // Act
            outbox.add(entry)

            // Assert
            assertEquals(DeliveryStatus.Failed(FailureReason.Stopped), entry.status.value)
            assertTrue(capacity.reserve(1))
            assertEquals(emptyList(), attempts)
        }

    @Test
    fun aFoundRouteSendsTheMatchingMessagesOnceEachWhenTheGapSinceTheirLastSendHasPassed() =
        runTest {
            // Arrange
            density = DensityClass.HIGH
            val outbox = outbox()
            outbox.add(entry())
            outbox.add(entry(to = OTHER))
            advanceTimeBy(FOUND_MILLIS)

            // Act: found twice before the gap has passed, then again after the deferred send's gap.
            outbox.found(GAP_MILLIS) { it.to == PEER }
            outbox.found(GAP_MILLIS) { it.to == PEER }
            times(AGAIN_MILLIS)
            outbox.found(GAP_MILLIS) { it.to == PEER }

            // Assert
            times(BEFORE_RETRY_MILLIS)
            assertEquals(listOf(0L, GAP_MILLIS, AGAIN_MILLIS), attempts.filter { it.second == PEER }.map { it.first })
            assertEquals(listOf(0L), attempts.filter { it.second == OTHER }.map { it.first })
        }

    @Test
    fun aMessageThatFinishesBeforeItsDeferredSendIsNotSentAgain() =
        runTest {
            // Arrange
            density = DensityClass.HIGH
            val outbox = outbox()
            outbox.add(entry(lifetimeMillis = GAP_MILLIS - FOUND_MILLIS))
            advanceTimeBy(FOUND_MILLIS)

            // Act
            outbox.found(GAP_MILLIS) { true }

            // Assert
            assertEquals(listOf(0L), times(BEFORE_RETRY_MILLIS))
        }

    private companion object {
        val SENDER = ByteArray(32)
        val PEER = nodeId(1)
        val OTHER = nodeId(2)
        const val FOUND_MILLIS = 100L
        const val GAP_MILLIS = 1_200L
        const val AGAIN_MILLIS = 2_600L
        const val BEFORE_RETRY_MILLIS = 4_000L
    }
}
