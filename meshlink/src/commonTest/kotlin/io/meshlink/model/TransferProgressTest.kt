package io.meshlink.model

import kotlin.test.Test
import kotlin.test.assertEquals
import io.meshlink.model.MessageId


class TransferProgressTest {

    private val id = MessageId.random()

    // --- Batch 12 Cycle 3: fraction edge cases ---

    @Test
    fun fractionEdgeCases() {
        // Zero total → 1f (complete by convention)
        assertEquals(1f, TransferProgress(id, chunksAcked = 0, totalChunks = 0).fraction)

        // Normal progression
        assertEquals(0f, TransferProgress(id, chunksAcked = 0, totalChunks = 10).fraction)
        assertEquals(0.5f, TransferProgress(id, chunksAcked = 5, totalChunks = 10).fraction)
        assertEquals(1f, TransferProgress(id, chunksAcked = 10, totalChunks = 10).fraction)

        // Over-acked (defensive — can exceed 1.0)
        assertEquals(2f, TransferProgress(id, chunksAcked = 10, totalChunks = 5).fraction)

        // Single chunk
        assertEquals(1f, TransferProgress(id, chunksAcked = 1, totalChunks = 1).fraction)
    }
}
