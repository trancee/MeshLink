package ch.trancee.meshlink.reference.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ReferencePresentationTest {
    @Test
    fun formatsKnownScenarioIdsForOperatorFacingSurfaces() {
        // Arrange
        val scenarioId = "guided-first-exchange"

        // Act
        val title = referenceScenarioTitle(scenarioId)

        // Assert
        assertEquals("Guided first exchange", title)
    }

    @Test
    fun formatsAuthorityModesForOperatorFacingSurfaces() {
        // Arrange
        val authorityMode = ReferenceAuthorityMode.SOLO

        // Act
        val label = referenceAuthorityLabel(authorityMode)

        // Assert
        assertEquals("Solo", label)
    }

    @Test
    fun formatsOutcomeSummariesForOperatorFacingSurfaces() {
        // Arrange
        val summary = "SendResult.NotSent(PAYLOAD_TOO_LARGE)"

        // Act
        val label = referenceOutcomeLabel(summary)

        // Assert
        assertEquals("Payload too large", label)
    }
}
