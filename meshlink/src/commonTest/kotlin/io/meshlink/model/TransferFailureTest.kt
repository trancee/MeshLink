package io.meshlink.model

import io.meshlink.util.DeliveryOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import io.meshlink.model.MessageId


class TransferFailureTest {

    @Test
    fun constructionPreservesFields() {
        val id = MessageId.random()
        val reason = DeliveryOutcome.FAILED_ACK_TIMEOUT
        val failure = TransferFailure(id, reason)
        assertEquals(id, failure.messageId)
        assertEquals(reason, failure.reason)
    }

    @Test
    fun equalityComparesAllFields() {
        val id = MessageId.random()
        val a = TransferFailure(id, DeliveryOutcome.FAILED_ACK_TIMEOUT)
        val b = TransferFailure(id, DeliveryOutcome.FAILED_ACK_TIMEOUT)
        assertEquals(a, b)
    }

    @Test
    fun differentReasonNotEqual() {
        val id = MessageId.random()
        val a = TransferFailure(id, DeliveryOutcome.FAILED_ACK_TIMEOUT)
        val b = TransferFailure(id, DeliveryOutcome.FAILED_NO_ROUTE)
        assertNotEquals(a, b)
    }

    @Test
    fun differentIdNotEqual() {
        val a = TransferFailure(MessageId.random(), DeliveryOutcome.FAILED_BUFFER_FULL)
        val b = TransferFailure(MessageId.random(), DeliveryOutcome.FAILED_BUFFER_FULL)
        assertNotEquals(a, b)
    }

    @Test
    fun allDeliveryOutcomesCanBeUsed() {
        val id = MessageId.random()
        for (outcome in DeliveryOutcome.entries) {
            val failure = TransferFailure(id, outcome)
            assertEquals(outcome, failure.reason)
        }
    }
}
