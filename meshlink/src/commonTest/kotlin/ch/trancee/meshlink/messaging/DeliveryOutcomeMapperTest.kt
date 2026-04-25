package ch.trancee.meshlink.messaging

import ch.trancee.meshlink.transfer.FailureReason
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct unit tests for [mapDeliveryFailureReason]. Exercising all 5 enum arms here avoids the need
 * for complex pipeline wiring that would race against the ack-deadline timer.
 */
class DeliveryOutcomeMapperTest {

    @Test
    fun `mapDeliveryFailureReason INACTIVITY_TIMEOUT returns TIMED_OUT`() {
        assertEquals(
            DeliveryOutcome.TIMED_OUT,
            mapDeliveryFailureReason(FailureReason.INACTIVITY_TIMEOUT),
        )
    }

    @Test
    fun `mapDeliveryFailureReason MEMORY_PRESSURE returns SEND_FAILED`() {
        assertEquals(
            DeliveryOutcome.SEND_FAILED,
            mapDeliveryFailureReason(FailureReason.MEMORY_PRESSURE),
        )
    }

    @Test
    fun `mapDeliveryFailureReason BUFFER_FULL_RETRY_EXHAUSTED returns SEND_FAILED`() {
        assertEquals(
            DeliveryOutcome.SEND_FAILED,
            mapDeliveryFailureReason(FailureReason.BUFFER_FULL_RETRY_EXHAUSTED),
        )
    }

    @Test
    fun `mapDeliveryFailureReason DEGRADATION_PROBE_FAILED returns SEND_FAILED`() {
        assertEquals(
            DeliveryOutcome.SEND_FAILED,
            mapDeliveryFailureReason(FailureReason.DEGRADATION_PROBE_FAILED),
        )
    }

    @Test
    fun `mapDeliveryFailureReason RESUME_FAILED returns SEND_FAILED`() {
        assertEquals(
            DeliveryOutcome.SEND_FAILED,
            mapDeliveryFailureReason(FailureReason.RESUME_FAILED),
        )
    }
}
