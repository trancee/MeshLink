package ch.trancee.meshlink.reference.model

import ch.trancee.meshlink.api.DeliveryPriority
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

    @Test
    fun formatsEnumBackedLabelsForOperatorFacingSurfaces() {
        // Arrange
        val trustState = PeerTrustState.CHANGED
        val connectionState = PeerConnectionSnapshotState.DISCONNECTED
        val family = TimelineFamily.DIAGNOSTIC
        val severity = TimelineSeverity.WARNING
        val priority = DeliveryPriority.HIGH

        // Act
        val trustLabel = referencePeerTrustLabel(trustState)
        val connectionLabel = referenceConnectionLabel(connectionState)
        val familyLabel = referenceTimelineFamilyLabel(family)
        val severityLabel = referenceTimelineSeverityLabel(severity)
        val priorityLabel = referencePriorityLabel(priority)

        // Assert
        assertEquals("Changed", trustLabel)
        assertEquals("Disconnected", connectionLabel)
        assertEquals("Diagnostic", familyLabel)
        assertEquals("Warning", severityLabel)
        assertEquals("High", priorityLabel)
    }
}
