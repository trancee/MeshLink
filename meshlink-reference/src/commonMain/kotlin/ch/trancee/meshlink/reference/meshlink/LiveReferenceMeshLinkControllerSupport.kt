package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.meshLink
import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun createLiveReferenceInitialSnapshot(
    platformName: String,
    authorityMode: ReferenceAuthorityMode,
    nowProvider: () -> Long,
    appId: String,
    surfaceOfOrigin: String,
    sessionId: String,
): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = sessionId,
                scenarioId = "guided-first-exchange",
                authorityMode = authorityMode,
                startedAtEpochMillis = nowProvider(),
                meshStateLabel = MeshLinkState.Uninitialized.toString(),
                configurationSnapshot =
                    mapOf(
                        "platform" to platformName,
                        "surface" to surfaceOfOrigin,
                        "appId" to appId,
                        "regulatoryRegion" to RegulatoryRegion.DEFAULT.name,
                        "powerMode" to PowerMode.Automatic.toString(),
                        "deliveryRetryDeadline" to "15s",
                    ),
                historyStatus = ReferenceHistoryStatus.LIVE,
            ),
        peers = emptyList(),
        timeline =
            listOf(
                ReferenceTimelineEvent(
                        family = TimelineFamily.USER,
                        severity = TimelineSeverity.INFO,
                        title = "Reference session created",
                        detail = "The guided first-exchange controller is ready on $platformName.",
                    )
                    .toTimelineEntry(
                        sessionId = sessionId,
                        entryIndex = 1,
                        occurredAtEpochMillis = nowProvider(),
                    )
            ),
        activePowerModeLabel = "Automatic",
    )
}

internal fun createLiveReferenceMeshLinkApi(
    appId: String,
    meshLinkBootstrap: MeshLinkBootstrap?,
): MeshLinkApi {
    val config = meshLinkConfig {
        this.appId = appId
        regulatoryRegion = RegulatoryRegion.DEFAULT
        powerMode = PowerMode.Automatic
    }
    return if (meshLinkBootstrap != null) {
        meshLink(config = config, bootstrap = meshLinkBootstrap)
    } else {
        meshLink(config = config)
    }
}

internal fun bindLiveReferenceControllerFlows(
    scope: CoroutineScope,
    meshLinkApi: MeshLinkApi,
    stateStore: ReferenceControllerStateStore,
    nowProvider: () -> Long,
    sessionProjector: LiveReferenceSessionProjector,
): Unit {
    scope.launch {
        meshLinkApi.state.collect { meshState ->
            stateStore.updateSession(meshStateLabel = meshState.toString())
        }
    }
    scope.launch {
        meshLinkApi.peerEvents.collect { event ->
            applyPeerEvent(stateStore = stateStore, nowProvider = nowProvider, event = event)
        }
    }
    scope.launch {
        meshLinkApi.diagnosticEvents.collect { event -> sessionProjector.recordDiagnostic(event) }
    }
    scope.launch {
        meshLinkApi.messages.collect { message -> sessionProjector.recordInboundMessage(message) }
    }
}
