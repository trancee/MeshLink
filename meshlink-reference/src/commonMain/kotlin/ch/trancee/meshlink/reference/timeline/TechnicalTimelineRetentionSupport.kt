package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ArtifactPayloadPolicy
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.SessionArtifact
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.model.referenceAuthorityLabel
import ch.trancee.meshlink.reference.model.referenceConnectionLabel
import ch.trancee.meshlink.reference.model.referenceOutcomeLabel
import ch.trancee.meshlink.reference.model.referencePeerTrustLabel
import ch.trancee.meshlink.reference.model.referenceScenarioTitle
import ch.trancee.meshlink.reference.model.withoutSensitivePayload
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.ReferenceSessionKind
import ch.trancee.meshlink.reference.session.allowsFullPayloadExport
import ch.trancee.meshlink.reference.session.referenceSessionKind

internal suspend fun TechnicalTimelineStore.writeExport(
    snapshot: ReferenceControllerSnapshot,
    policy: ExportPayloadPolicy,
): String {
    val createdAtEpochMillis = platformServices.currentTimeMillis()
    val artifactPolicy =
        if (policy == ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN) {
            ArtifactPayloadPolicy.FULL_OPT_IN
        } else {
            ArtifactPayloadPolicy.REDACTED_PREVIEW
        }
    val artifactSuffix =
        if (policy == ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN) {
            "full"
        } else {
            "redacted"
        }
    val artifact =
        SessionArtifact(
            artifactId =
                "artifact-${snapshot.session.sessionId}-$createdAtEpochMillis-$artifactSuffix",
            sourceSessionId = snapshot.session.sessionId,
            createdAtEpochMillis = createdAtEpochMillis,
            payloadPolicy = artifactPolicy,
            includesFullPayload =
                policy == ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN &&
                    snapshot.timeline.any { entry -> entry.fullPayload != null },
            scenarioSummary =
                mapOf(
                    "scenarioId" to snapshot.session.scenarioId,
                    "title" to referenceScenarioTitle(snapshot.session.scenarioId),
                    "surface" to
                        (snapshot.session.configurationSnapshot["surface"] ?: "main-guided"),
                    "authorityMode" to referenceAuthorityLabel(snapshot.session.authorityMode),
                ),
            peerSummaries =
                snapshot.peers.map { peer ->
                    buildMap {
                        put("peerSuffix", peer.peerSuffix)
                        put("trustState", referencePeerTrustLabel(peer.trustState))
                        put("connectionState", referenceConnectionLabel(peer.connectionState))
                        peer.lastDeliveryOutcome?.let { outcome ->
                            put("lastDeliveryOutcome", referenceOutcomeLabel(outcome) ?: outcome)
                        }
                    }
                },
            timelineEntries = snapshot.timeline,
            storagePath =
                "reference/exports/${snapshot.session.sessionId}-$createdAtEpochMillis-$artifactSuffix.json",
        )
    val serialized =
        if (policy == ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN) {
            artifactSerializer.serializeWithFullPayload(
                artifact,
                snapshot.session,
                snapshot.peers,
                snapshot.timeline,
            )
        } else {
            artifactSerializer.serializeRedacted(
                artifact,
                snapshot.session,
                snapshot.peers,
                snapshot.timeline,
            )
        }
    return artifactSerializer.writeArtifact(artifact, serialized)
}

internal suspend fun TechnicalTimelineStore.refreshRetainedSessions(lastExportPath: String?): Unit {
    val retainedSessions = historyRepository.loadRetainedSessions()
    updateState { current ->
        current.copy(
            retainedSessions = retainedSessions,
            lastExportPath = lastExportPath ?: current.lastExportPath,
            visibleEntries = current.filters.apply(current.currentSnapshot.timeline),
        )
    }
}

internal suspend fun TechnicalTimelineStore.retainIfEligible(
    endedSnapshot: ReferenceControllerSnapshot
): Unit {
    if (!endedSnapshot.isEligibleForAutomaticRetention(platformServices.readinessBlockers)) {
        return
    }
    historyRepository.retainSnapshot(endedSnapshot.redactedRetainedSnapshot())
}

internal fun normalizeExportPolicy(
    snapshot: ReferenceControllerSnapshot,
    requestedPolicy: ExportPayloadPolicy,
): ExportPayloadPolicy {
    return if (snapshot.allowsFullPayloadExport()) {
        requestedPolicy
    } else {
        ExportPayloadPolicy.REDACTED_PREVIEW
    }
}

internal fun endedBoundarySnapshot(
    snapshot: ReferenceControllerSnapshot,
    endedAtEpochMillis: Long,
): ReferenceControllerSnapshot {
    return snapshot.copy(session = snapshot.session.copy(endedAtEpochMillis = endedAtEpochMillis))
}

internal fun ReferenceControllerSnapshot.redactedRetainedSnapshot(): ReferenceControllerSnapshot {
    return copy(
        session = session.copy(historyStatus = ReferenceHistoryStatus.RETAINED),
        timeline = timeline.map { entry -> entry.withoutSensitivePayload() },
    )
}

internal fun ReferenceControllerSnapshot.isEligibleForAutomaticRetention(
    readinessBlockers: List<String>
): Boolean {
    if (session.authorityMode != ReferenceAuthorityMode.LIVE) {
        return false
    }
    if (referenceSessionKind() == ReferenceSessionKind.LAB) {
        return false
    }
    if (readinessBlockers.isNotEmpty()) {
        return true
    }
    return timeline.any { entry -> entry.isReviewableEvidenceEntry() }
}

private fun TimelineEntry.isReviewableEvidenceEntry(): Boolean {
    if (title == "Reference session created" || title == "Automation session created") {
        return false
    }
    return family != TimelineFamily.USER || severity != TimelineSeverity.INFO
}
