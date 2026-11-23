package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.FailureReason
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeliveryOrderTest {
    @Test
    fun aStatusOnlyMovesForwardExceptThatSentAndHeldAlternate() {
        val random = Random(SEED)

        repeat(SEQUENCES) {
            // Arrange
            val requests = List(random.nextInt(1, LENGTH_MAX)) { STATUSES.random(random) }

            // Act
            val history =
                requests.runningFold<DeliveryStatus, DeliveryStatus>(DeliveryStatus.Queued) { status, next ->
                    status.then(next)
                }

            // Assert
            history.zipWithNext().zip(requests).forEach { (step, request) ->
                val (from, to) = step
                val allowed = !from.terminal && request != DeliveryStatus.Queued
                assertEquals(if (allowed) request else from, to, "$from then $request in $requests")
            }
            assertTrue(history.zipWithNext().all { (from, to) -> rank(from) <= rank(to) }, "$history")
        }
    }

    @Test
    fun deliveredExpiredAndFailedAreTerminal() {
        assertEquals(listOf(true, true, true, false, false, false), STATUSES.map { it.terminal })
    }

    private fun rank(status: DeliveryStatus): Int =
        when {
            status == DeliveryStatus.Queued -> 0
            status.terminal -> 2
            else -> 1
        }

    private companion object {
        const val SEED = 47L
        const val SEQUENCES = 500
        const val LENGTH_MAX = 12
        val STATUSES =
            listOf(
                DeliveryStatus.Delivered,
                DeliveryStatus.Expired,
                DeliveryStatus.Failed(FailureReason.Stopped),
                DeliveryStatus.Queued,
                DeliveryStatus.Held,
                DeliveryStatus.Sent,
            )
    }
}
