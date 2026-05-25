package ch.trancee.meshlink.reference.advanced

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.model.referenceAuthorityLabel
import ch.trancee.meshlink.reference.model.referenceConnectionLabel
import ch.trancee.meshlink.reference.model.referenceOutcomeLabel
import ch.trancee.meshlink.reference.model.referencePeerTrustLabel
import ch.trancee.meshlink.reference.platform.PlatformServices

internal fun buildAdvancedControlsUiState(
    platformServices: PlatformServices,
    selectedPeerId: String?,
    composerText: String,
    selectedPriority: DeliveryPriority,
): AdvancedControlsUiState {
    val snapshot = platformServices.meshLinkController.snapshot.value
    val effectivePeerId = selectedPeerId ?: snapshot.peers.firstOrNull()?.peerId
    val configSnapshot = snapshot.session.configurationSnapshot
    val payloadSizeBytes = composerText.encodeToByteArray().size
    return AdvancedControlsUiState(
        config =
            AdvancedConfigState(
                appId = configSnapshot["appId"] ?: "demo.meshlink.reference",
                regulatoryRegion = configSnapshot["regulatoryRegion"] ?: "DEFAULT",
                powerModeLabel = configSnapshot["powerMode"] ?: snapshot.activePowerModeLabel,
                deliveryRetryDeadlineLabel = configSnapshot["deliveryRetryDeadline"] ?: "15s",
                authorityModeLabel = referenceAuthorityLabel(snapshot.session.authorityMode),
            ),
        meshStateLabel = snapshot.session.meshStateLabel,
        activePowerModeLabel = snapshot.activePowerModeLabel,
        isSessionEnded = snapshot.session.endedAtEpochMillis != null,
        selectedPeerId = effectivePeerId,
        composerText = composerText,
        selectedPriority = selectedPriority,
        peerRows =
            snapshot.peers.map { peer ->
                AdvancedPeerRow(
                    peerId = peer.peerId,
                    peerSuffix = peer.peerSuffix,
                    trustLabel = referencePeerTrustLabel(peer.trustState),
                    connectionLabel = referenceConnectionLabel(peer.connectionState),
                    lastDeliveryOutcome = peer.lastDeliveryOutcome,
                )
            },
        timelineHighlights =
            snapshot.timeline.takeLast(RECENT_TIMELINE_HIGHLIGHTS_COUNT).map { entry ->
                "${entry.title}: ${entry.detail}"
            },
        lastOutcomeSummary = snapshot.session.lastOutcomeSummary,
        lastOutcomeDisplayText = referenceOutcomeLabel(snapshot.session.lastOutcomeSummary),
        payloadSizeBytes = payloadSizeBytes,
        payloadLimitBytes = ADVANCED_PAYLOAD_LIMIT_BYTES,
        payloadValidationMessage = payloadValidationMessage(payloadSizeBytes),
    )
}

internal fun buildAdvancedLargeTransferPreviewPayload(): String {
    return buildString {
        repeat(LARGE_TRANSFER_PREVIEW_REPEAT_COUNT) { append(LARGE_TRANSFER_PREVIEW_SEGMENT) }
    }
}

internal const val ADVANCED_PAYLOAD_LIMIT_BYTES: Int = 64 * 1024

private fun payloadValidationMessage(payloadSizeBytes: Int): String? {
    return if (payloadSizeBytes > ADVANCED_PAYLOAD_LIMIT_BYTES) {
        "Payload is $payloadSizeBytes bytes. MeshLink currently supports up to " +
            "$ADVANCED_PAYLOAD_LIMIT_BYTES bytes per message. Shorten the text before sending."
    } else {
        null
    }
}

private const val LARGE_TRANSFER_PREVIEW_REPEAT_COUNT: Int = 256
private const val LARGE_TRANSFER_PREVIEW_SEGMENT: String =
    "MeshLink reference large transfer preview · "
private const val RECENT_TIMELINE_HIGHLIGHTS_COUNT: Int = 3
