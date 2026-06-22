package ch.trancee.meshlink.reference.navigation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.platform.LiveProofPlatformServices
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.ReferenceSessionController
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import ch.trancee.meshlink.reference.timeline.endedBoundarySnapshot
import ch.trancee.meshlink.reference.timeline.normalizeExportPolicy

/**
 * Single transition execution module for reference-app session changes.
 *
 * Navigation still owns the visible route through `applySurfaceSelection`, and
 * `ReferenceSessionController` still owns session boundaries. This service owns only the ordering
 * of exports, retention, session replacement, and follow-up supported-session starts.
 */
internal class SessionTransitionService(
    private val timelineStore: TechnicalTimelineStore,
    private val sessionController: ReferenceSessionController,
    private val platformServices: LiveProofPlatformServices,
    private val currentTimeMillis: () -> Long,
) {
    fun canExecuteBoundary(request: SessionBoundaryRequest): Boolean {
        val current = timelineStore.uiState.value
        return when (request) {
            is SessionBoundaryRequest.LeaveSupportedSession ->
                current.isSupportedLiveSession &&
                    !current.viewingRetained &&
                    (request.targetSurface == ReferenceSurface.SOLO_EXPLORATION ||
                        request.targetSurface == ReferenceSurface.LAB)

            is SessionBoundaryRequest.LeaveAlternativeSession ->
                current.isAlternativeSession && !current.viewingRetained
        }
    }

    suspend fun startAlternativeSession(
        surface: ReferenceSurface,
        applySurfaceSelection: (ReferenceSurface) -> Unit,
    ): Unit {
        val snapshot = startAlternativeSnapshot(surface) ?: return
        timelineStore.publishLiveSnapshot(snapshot)
        applySurfaceSelection(surface)
    }

    suspend fun startFollowUpSupportedSession(
        currentSnapshot: ReferenceControllerSnapshot,
        applySurfaceSelection: (ReferenceSurface) -> Unit,
    ): Unit {
        val targetSurface = followUpSupportedEntrySurface(currentSnapshot)
        applySurfaceSelection(targetSurface)
        startSupportedSession(targetSurface.route)
    }

    suspend fun startSupportedSession(
        surfaceOfOrigin: String = ReferenceSurface.MAIN_GUIDED.route
    ): Unit {
        val snapshot = sessionController.startNewSupportedSession(surfaceOfOrigin)
        timelineStore.publishLiveSnapshot(snapshot)
    }

    suspend fun endSupportedSession(preEndExportPolicy: ExportPayloadPolicy? = null): Unit {
        val current = timelineStore.uiState.value
        if (!current.isSupportedLiveSession || current.viewingRetained) {
            return
        }

        val snapshotBeforeEnd = current.liveSnapshot
        val exportPath =
            writeOptionalExport(snapshotBeforeEnd, preEndExportPolicy, current.lastExportPath)
        val endedSnapshot = sessionController.endSupportedSession()
        timelineStore.publishLiveSnapshot(endedSnapshot)
        timelineStore.retainEndedSnapshotIfEligible(endedSnapshot)
        timelineStore.refreshRetainedSessionList(lastExportPath = exportPath)
        platformServices.stopPowerMitigation()
    }

    suspend fun completeBoundary(
        request: SessionBoundaryRequest,
        continuation: BoundaryContinuation,
        applySurfaceSelection: (ReferenceSurface) -> Unit,
    ): Unit {
        if (!canExecuteBoundary(request)) {
            return
        }
        when (request) {
            is SessionBoundaryRequest.LeaveSupportedSession -> {
                applySurfaceSelection(request.targetSurface)
                transitionSupportedSession(
                    targetSurface = request.targetSurface,
                    preBoundaryExportPolicy = supportedBoundaryExportPolicy(continuation),
                )
            }

            is SessionBoundaryRequest.LeaveAlternativeSession -> {
                applySurfaceSelection(request.targetSurface)
                transitionAlternativeSession(
                    targetSurface = request.targetSurface,
                    continuation = continuation,
                )
            }
        }
    }

    private suspend fun transitionSupportedSession(
        targetSurface: ReferenceSurface,
        preBoundaryExportPolicy: ExportPayloadPolicy? = null,
    ): Unit {
        val current = timelineStore.uiState.value
        if (!current.isSupportedLiveSession || current.viewingRetained) {
            return
        }

        val supportedSnapshot = current.liveSnapshot
        val exportPath =
            writeOptionalExport(supportedSnapshot, preBoundaryExportPolicy, current.lastExportPath)
        timelineStore.retainEndedSnapshotIfEligible(
            endedBoundarySnapshot(supportedSnapshot, currentTimeMillis())
        )
        val snapshot = startAlternativeSnapshot(targetSurface) ?: return
        timelineStore.publishLiveSnapshot(snapshot)
        timelineStore.refreshRetainedSessionList(lastExportPath = exportPath)
    }

    private suspend fun transitionAlternativeSession(
        targetSurface: ReferenceSurface,
        continuation: BoundaryContinuation,
    ): Unit {
        val current = timelineStore.uiState.value
        if (!current.isAlternativeSession || current.viewingRetained) {
            return
        }

        val exportPath =
            if (continuation == BoundaryContinuation.EXPORT_AND_CONTINUE) {
                timelineStore.exportSnapshot(
                    current.liveSnapshot,
                    ExportPayloadPolicy.REDACTED_PREVIEW,
                )
            } else {
                current.lastExportPath
            }
        when (targetSurface) {
            ReferenceSurface.SOLO_EXPLORATION,
            ReferenceSurface.LAB -> {
                val snapshot = startAlternativeSnapshot(targetSurface) ?: return
                timelineStore.publishLiveSnapshot(snapshot)
            }

            ReferenceSurface.ADVANCED_CONTROLS ->
                startSupportedSession(ReferenceSurface.ADVANCED_CONTROLS.route)

            else -> startSupportedSession(ReferenceSurface.MAIN_GUIDED.route)
        }
        timelineStore.refreshRetainedSessionList(lastExportPath = exportPath)
    }

    private suspend fun startAlternativeSnapshot(
        surface: ReferenceSurface
    ): ReferenceControllerSnapshot? {
        return when (surface) {
            ReferenceSurface.SOLO_EXPLORATION -> sessionController.startSoloSession()
            ReferenceSurface.LAB -> sessionController.startLabSession()
            else -> null
        }
    }

    private suspend fun writeOptionalExport(
        snapshot: ReferenceControllerSnapshot,
        policy: ExportPayloadPolicy?,
        fallbackPath: String?,
    ): String? {
        return policy?.let { requestedPolicy ->
            timelineStore.exportSnapshot(snapshot, normalizeExportPolicy(snapshot, requestedPolicy))
        } ?: fallbackPath
    }
}

private fun supportedBoundaryExportPolicy(
    continuation: BoundaryContinuation
): ExportPayloadPolicy? {
    return if (continuation == BoundaryContinuation.EXPORT_AND_CONTINUE) {
        ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN
    } else {
        null
    }
}
