package ch.trancee.meshlink.engine

import ch.trancee.meshlink.identity.LocalIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

class MeshEngineSequenceGeneratorTest {
    @Test
    fun `sequence generator uses the peer suffix and shared counter for message and transfer ids`() {
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
