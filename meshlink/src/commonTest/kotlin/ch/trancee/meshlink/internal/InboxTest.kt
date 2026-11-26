package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReceivedMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InboxTest {
    private val diagnosed = mutableListOf<DiagnosticEvent>()

    @Test
    fun holdsAtMost1024Messages() {
        // Arrange
        val inbox = Inbox(diagnosed::add)
        val accepted = List(Inbox.MAX_COUNT) { inbox.offer(message(senderId = it % SENDERS, size = 0)) }

        // Act
        val extra = inbox.offer(message(senderId = 0, size = 0))

        // Assert
        assertTrue(accepted.all { it })
        assertFalse(extra)
        assertEquals(listOf("ML7003 InboxFull(bound=Count) x1"), diagnosed.map { it.toString() })
    }

    @Test
    fun holdsAtMost1MiBOfPayload() {
        // Arrange
        val inbox = Inbox(diagnosed::add)
        val accepted = List(SENDERS) { inbox.offer(message(senderId = it, size = Inbox.SENDER_BYTES.toInt())) }

        // Act
        val extra = inbox.offer(message(senderId = SENDERS, size = 1))

        // Assert
        assertTrue(accepted.all { it })
        assertFalse(extra)
        assertEquals(listOf("ML7003 InboxFull(bound=Bytes) x1"), diagnosed.map { it.toString() })
    }

    @Test
    fun oneSenderHoldsAtMostAQuarterOfThePayloadBytes() {
        // Arrange
        val inbox = Inbox(diagnosed::add)
        val filled = inbox.offer(message(senderId = 0, size = Inbox.SENDER_BYTES.toInt()))

        // Act
        val sameSender = inbox.offer(message(senderId = 0, size = 1))
        val otherSender = inbox.offer(message(senderId = 1, size = 1))

        // Assert
        assertEquals(listOf(true, false, true), listOf(filled, sameSender, otherSender))
        assertEquals(listOf("ML7003 InboxFull(bound=Sender) x1"), diagnosed.map { it.toString() })
    }

    @Test
    fun collectingAMessageFreesItsRoomForItsSender() =
        runTest {
            // Arrange
            val inbox = Inbox()
            inbox.offer(message(senderId = 0, size = Inbox.SENDER_BYTES.toInt()))

            // Act
            inbox.messages.first()
            val again = inbox.offer(message(senderId = 0, size = Inbox.SENDER_BYTES.toInt()))

            // Assert
            assertTrue(again)
        }

    @Test
    fun collectingAMessageFreesItsCount() =
        runTest {
            // Arrange
            val inbox = Inbox()
            repeat(Inbox.MAX_COUNT) { inbox.offer(message(senderId = 0, size = 0)) }

            // Act
            inbox.messages.first()
            val again = inbox.offer(message(senderId = 0, size = 0))

            // Assert
            assertTrue(again)
        }

    @Test
    fun aMessageIsTakenWhenItsCollectorReceivesItAndARefusedOneNever() =
        runTest {
            // Arrange
            val inbox = Inbox()
            val taken = mutableListOf<Int>()
            inbox.offer(message(senderId = 0, size = Inbox.SENDER_BYTES.toInt())) { taken += 0 }
            inbox.offer(message(senderId = 0, size = 1)) { taken += 1 }
            inbox.offer(message(senderId = 1, size = 1)) { taken += 2 }

            // Act
            val before = taken.toList()
            inbox.messages.first()

            // Assert
            assertEquals(emptyList(), before)
            assertEquals(listOf(0), taken)
        }

    private fun message(
        senderId: Int,
        size: Int,
    ): ReceivedMessage {
        val sender = NodeId(ByteArray(NODE_ID_LENGTH) { senderId.toByte() })
        return ReceivedMessage(sender, ByteArray(size), broadcast = false)
    }

    private companion object {
        const val NODE_ID_LENGTH = 32

        // Senders needed to fill the inbox, each up to its quarter.
        const val SENDERS = 4
    }
}
