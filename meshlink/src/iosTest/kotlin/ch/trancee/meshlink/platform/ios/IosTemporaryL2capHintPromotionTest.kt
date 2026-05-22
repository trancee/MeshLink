package ch.trancee.meshlink.platform.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosTemporaryL2capHintPromotionTest {
    @Test
    fun returnsTemporaryHintWhenIdentifierMapsToAnActiveTemporaryLink(): Unit {
        // Arrange
        val identifier = "central-id"
        val resolvedHintPeerIdValue = "peer12345678"
        val temporaryHintPeerIdValue = "cb-temporary1234"

        // Act
        val selectedHint =
            selectTemporaryL2capHintPromotion(
                TemporaryL2capHintPromotionRequest(
                    identifier = identifier,
                    resolvedHintPeerIdValue = resolvedHintPeerIdValue,
                    temporaryHintByIdentifier = mapOf(identifier to temporaryHintPeerIdValue),
                    activeHintIds = setOf(temporaryHintPeerIdValue),
                )
            )

        // Assert
        assertEquals(temporaryHintPeerIdValue, selectedHint)
    }

    @Test
    fun returnsNullWhenIdentifierAlreadyMapsToTheResolvedHint(): Unit {
        // Arrange
        val identifier = "central-id"
        val resolvedHintPeerIdValue = "peer12345678"

        // Act
        val selectedHint =
            selectTemporaryL2capHintPromotion(
                TemporaryL2capHintPromotionRequest(
                    identifier = identifier,
                    resolvedHintPeerIdValue = resolvedHintPeerIdValue,
                    temporaryHintByIdentifier = mapOf(identifier to resolvedHintPeerIdValue),
                    activeHintIds = setOf(resolvedHintPeerIdValue),
                )
            )

        // Assert
        assertNull(selectedHint)
    }

    @Test
    fun returnsNullWhenIdentifierDoesNotMapToATemporaryHint(): Unit {
        // Arrange
        val identifier = "central-id"
        val resolvedHintPeerIdValue = "peer12345678"
        val mappedHintPeerIdValue = "peer87654321"

        // Act
        val selectedHint =
            selectTemporaryL2capHintPromotion(
                TemporaryL2capHintPromotionRequest(
                    identifier = identifier,
                    resolvedHintPeerIdValue = resolvedHintPeerIdValue,
                    temporaryHintByIdentifier = mapOf(identifier to mappedHintPeerIdValue),
                    activeHintIds = setOf(mappedHintPeerIdValue),
                )
            )

        // Assert
        assertNull(selectedHint)
    }

    @Test
    fun returnsNullWhenResolvedHintAlreadyHasAnActiveLink(): Unit {
        // Arrange
        val identifier = "central-id"
        val resolvedHintPeerIdValue = "peer12345678"
        val temporaryHintPeerIdValue = "cb-temporary1234"

        // Act
        val selectedHint =
            selectTemporaryL2capHintPromotion(
                TemporaryL2capHintPromotionRequest(
                    identifier = identifier,
                    resolvedHintPeerIdValue = resolvedHintPeerIdValue,
                    temporaryHintByIdentifier = mapOf(identifier to temporaryHintPeerIdValue),
                    activeHintIds = setOf(temporaryHintPeerIdValue, resolvedHintPeerIdValue),
                )
            )

        // Assert
        assertNull(selectedHint)
    }
}
