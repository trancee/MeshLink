package ch.trancee.meshlink.reference.advanced

import ch.trancee.meshlink.api.DeliveryPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdvancedControlsViewModelStateCommonTest {
    @Test
    fun exposesConfigAndFirstPeerSelection() {
        // Arrange
        val viewModel = AdvancedControlsViewModel(platformServices = advancedPlatformServices())

        // Act
        val uiState = viewModel.uiState.value

        // Assert
        assertEquals("demo.meshlink.reference", uiState.config.appId)
        assertEquals("abc123", uiState.peerRows.first().peerSuffix)
        assertEquals("peer-abc123", uiState.selectedPeerId)
    }

    @Test
    fun priorityChangeUpdatesComposerState() {
        // Arrange
        val viewModel = AdvancedControlsViewModel(platformServices = advancedPlatformServices())

        // Act
        viewModel.updatePriority(DeliveryPriority.HIGH)
        viewModel.updateComposerText("payload")

        // Assert
        assertEquals(DeliveryPriority.HIGH, viewModel.uiState.value.selectedPriority)
        assertTrue(viewModel.uiState.value.canSendMessage)
    }

    @Test
    fun oversizePayloadDisablesMessageSendButKeepsLargeTransferAvailable() {
        // Arrange
        val viewModel = AdvancedControlsViewModel(platformServices = advancedPlatformServices())
        val oversizePayload = "a".repeat(ADVANCED_PAYLOAD_LIMIT_BYTES + 1)

        // Act
        viewModel.updateComposerText(oversizePayload)
        val uiState = viewModel.uiState.value

        // Assert
        assertEquals(ADVANCED_PAYLOAD_LIMIT_BYTES + 1, uiState.payloadSizeBytes)
        assertFalse(uiState.canSendMessage)
        assertTrue(uiState.canSendLargeTransfer)
        assertNotNull(uiState.payloadValidationMessage)
        assertTrue(
            uiState.payloadValidationMessage.contains(ADVANCED_PAYLOAD_LIMIT_BYTES.toString())
        )
    }

    @Test
    fun mapsTechnicalOutcomeSummaryToOperatorFacingCopy() {
        // Arrange
        val viewModel =
            AdvancedControlsViewModel(
                platformServices =
                    advancedPlatformServices(
                        controller =
                            TestReferenceMeshLinkController(
                                initialSnapshot =
                                    advancedSnapshot(
                                        lastOutcomeSummary = "ForgetPeerResult.Forgotten"
                                    )
                            )
                    )
            )

        // Act
        val uiState = viewModel.uiState.value

        // Assert
        assertEquals("Trust reset", uiState.lastOutcomeDisplayText)
    }
}
