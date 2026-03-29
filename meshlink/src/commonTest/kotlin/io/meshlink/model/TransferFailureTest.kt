package io.meshlink.model

import io.meshlink.util.DeliveryOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class TransferFailureTest {

    @Test
    fun constructionPreservesFields() {
        val id = Uuid.random()
        val reason = DeliveryOutcome.FAILED_ACK_TIMEOUT
        val failure = TransferFailure(id, reason)
        assertEquals(id, failure.messageId)
        assertEquals(reason, failure.reason)
    }

    @Test
    fun equalityComparesAllFields() {
        val id = Uuid.random()
        val a = TransferFailure(id, DeliveryOutcome.FAILED_ACK_TIMEOUT)
        val b = TransferFailure(id, DeliveryOutcome.FAILED_ACK_TIMEOUT)
        assertEquals(a, b)
    }

    @Test
    fun differentReasonNotEqual() {
        val id = Uuid.random()
        val a = TransferFailure(id, DeliveryOutcome.FAILED_ACK_TIMEOUT)
        val b = TransferFailure(id, DeliveryOutcome.FAILED_NO_ROUTE)
        assertNotEquals(a, b)
    }

    @Test
    fun differentIdNotEqual() {
        val a = TransferFailure(Uuid.random(), DeliveryOutcome.FAILED_BUFFER_FULL)
        val b = TransferFailure(Uuid.random(), DeliveryOutcome.FAILED_BUFFER_FULL)
        assertNotEquals(a, b)
    }

    @Test
    fun allDeliveryOutcomesCanBeUsed() {
        val id = Uuid.random()
        for (outcome in DeliveryOutcome.entries) {
            val failure = TransferFailure(id, outcome)
            assertEquals(outcome, failure.reason)
        }
    }
}
