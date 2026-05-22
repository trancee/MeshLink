package ch.trancee.meshlink.reference.advanced

import ch.trancee.meshlink.api.DeliveryPriority

/** Operator-visible configuration summary for the advanced controls surface. */
public data class AdvancedConfigState(
    public val appId: String,
    public val regulatoryRegion: String,
    public val powerModeLabel: String,
    public val deliveryRetryDeadlineLabel: String,
    public val authorityModeLabel: String,
)

public data class AdvancedControlsUiState(
    public val config: AdvancedConfigState,
    public val meshStateLabel: String,
    public val activePowerModeLabel: String,
    public val isSessionEnded: Boolean,
    public val selectedPeerId: String? = null,
    public val composerText: String,
    public val selectedPriority: DeliveryPriority,
    public val peerRows: List<AdvancedPeerRow>,
    public val timelineHighlights: List<String>,
    public val lastOutcomeSummary: String? = null,
    public val lastOutcomeDisplayText: String? = null,
    public val payloadSizeBytes: Int,
    public val payloadLimitBytes: Int,
    public val payloadValidationMessage: String? = null,
) {
    public val canSendMessage: Boolean
        get() =
            !isSessionEnded &&
                selectedPeerId != null &&
                composerText.isNotBlank() &&
                payloadValidationMessage == null

    public val canSendLargeTransfer: Boolean
        get() = !isSessionEnded && selectedPeerId != null

    public val canForgetPeer: Boolean
        get() = !isSessionEnded && selectedPeerId != null
}

public data class AdvancedPeerRow(
    public val peerId: String,
    public val peerSuffix: String,
    public val trustLabel: String,
    public val connectionLabel: String,
    public val lastDeliveryOutcome: String? = null,
)
