package ch.trancee.meshlink.wire

import kotlin.test.Test
import kotlin.test.assertEquals

class TransferAbortReasonCodeTest {
    @Test
    fun `fromCode returns runtime stopped for the known code`() {
        // Arrange / Act
        val reason = TransferAbortReasonCode.fromCode(1)

        // Assert
        assertEquals(TransferAbortReasonCode.RUNTIME_STOPPED, reason)
    }

    @Test
    fun `fromCode returns null for unknown codes`() {
        // Arrange / Act
        val reason = TransferAbortReasonCode.fromCode(99)

        // Assert
        assertEquals(null, reason)
    }
}
