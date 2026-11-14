package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The orderings a radio can't force on demand: a send's add racing stop, and a write ending after
// its message finished or its peer unlinked.
class OutboxTest {
    @Test
    fun messageAddedAfterTheRunEndedFailsWithStoppedAndFreesItsBytes() =
        runTest {
            // Arrange
            val capacity = Capacity()
            val outbox = outbox(backgroundScope, capacity)
            outbox.begin()
            outbox.end()
            capacity.reserve(Capacity.LIMIT.toInt())
            val entry = entry(size = Capacity.LIMIT.toInt())

            // Act
            outbox.add(entry)

            // Assert
            assertEquals(DeliveryStatus.Failed(FailureReason.Stopped), entry.status.value)
            assertTrue(capacity.reserve(1))
        }

    @Test
    fun writeSettledAfterItsMessageFailedLeavesTheFailure() =
        runTest {
            // Arrange
            val outbox = outbox(backgroundScope, Capacity())
            outbox.begin()
            outbox.linked(PEER)
            outbox.add(entry())
            val entry = outbox.next(PEER)
            outbox.end()

            // Act
            outbox.settle(entry, sent = true)

            // Assert
            assertEquals(DeliveryStatus.Failed(FailureReason.Stopped), entry.status.value)
        }

    @Test
    fun writeRefusedAfterItsPeerUnlinkedRequeuesTheMessageAsHeld() =
        runTest {
            // Arrange
            val outbox = outbox(backgroundScope, Capacity())
            outbox.begin()
            outbox.linked(PEER)
            outbox.add(entry())
            val entry = outbox.next(PEER)
            outbox.unlinked(PEER)

            // Act
            outbox.settle(entry, sent = false)

            // Assert
            assertEquals(DeliveryStatus.Held, entry.status.value)
            assertSame(entry, outbox.next(PEER))
        }

    private fun outbox(
        scope: CoroutineScope,
        capacity: Capacity,
    ): Outbox = Outbox(FixedClock, scope, capacity)

    private fun entry(size: Int = 1): Outbox.Entry {
        val draft = UnsecuredMessaging(SodiumCryptoProvider, SENDER).draft(PEER.toByteArray(), ByteArray(1), EXPIRY, 1)
        return Outbox.Entry(PEER, draft, size, MutableStateFlow(DeliveryStatus.Queued), DEADLINE_MILLIS)
    }

    private object FixedClock : Clock {
        override fun monotonicMillis(): Long = 0

        override fun wallClockMillis(): Long = 0
    }

    private companion object {
        val SENDER = ByteArray(NODE_ID_LENGTH)
        val PEER = NodeId(ByteArray(NODE_ID_LENGTH) { 1 })
        const val NODE_ID_LENGTH = 32
        const val EXPIRY = 1_800u
        const val DEADLINE_MILLIS = 1_830_000L
    }
}
