package ch.trancee.meshlink.engine

import ch.trancee.meshlink.engine.internal.MeshEngineSequenceGenerator
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.identity.fromAppId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class MeshEngineSequenceGeneratorTest {
    @Test
    fun `sequence generator uses the peer suffix and shared counter for message and transfer ids`() {
        runBlocking<Unit> {
            // Arrange
            val generator = MeshEngineSequenceGenerator(LocalIdentity.fromAppId("peer-abcdef"))

            // Act
            val firstMessageId = generator.createMessageId()
            val transferId = generator.createTransferId()
            val secondMessageId = generator.createMessageId()

            // Assert
            assertEquals("abcdef-1", firstMessageId)
            assertEquals("transfer-abcdef-2", transferId)
            assertEquals("abcdef-3", secondMessageId)
        }
    }

    @Test
    fun `concurrent callers each observe a unique and monotonically increasing sequence number`() {
        runBlocking<Unit> {
            // Arrange -- this is the correctness property that must survive the
            // Mutex-to-AtomicLong swap in MeshEngineSequenceGenerator: many coroutines racing on
            // Dispatchers.Default must never observe a duplicate or out-of-order sequence number.
            val generator = MeshEngineSequenceGenerator(LocalIdentity.fromAppId("peer-concurrent"))
            val callerCount = 200

            // Act
            val messageIds =
                (1..callerCount)
                    .map { async(Dispatchers.Default) { generator.createMessageId() } }
                    .awaitAll()
            val sequenceNumbers = messageIds.map { messageId ->
                messageId.substringAfterLast('-').toInt()
            }

            // Assert
            assertEquals(
                callerCount,
                sequenceNumbers.toSet().size,
                "every sequence number must be unique",
            )
            assertEquals((1..callerCount).toList(), sequenceNumbers.sorted())
        }
    }
}
