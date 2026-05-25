package ch.trancee.meshlink.reference.navigation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.timeline.TimelineStoreHarness
import ch.trancee.meshlink.reference.timeline.timelineStoreEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class ReferenceNavHostSupportTest {
    @Test
    fun followUpSupportedEntrySurfacePreservesAdvancedSurfaceOfOrigin() = runTest {
        // Arrange
        val snapshot =
            navigationSnapshot(surfaceOfOrigin = ReferenceSurface.ADVANCED_CONTROLS.route)
        val harness = TimelineStoreHarness().createBoundaryCoordinatorHarness(scope = this)

        try {
            // Act
            val actual = followUpSupportedEntrySurface(snapshot)
            val label = followUpSupportedSessionLabel(snapshot)

            // Assert
            assertEquals(ReferenceSurface.ADVANCED_CONTROLS, actual)
            assertEquals("Start new advanced session", label)
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Test
    fun followUpSupportedEntrySurfaceDefaultsToGuidedForSoloSessions() = runTest {
        // Arrange
        val snapshot =
            navigationSnapshot(
                authorityMode = ReferenceAuthorityMode.SOLO,
                surfaceOfOrigin = ReferenceSurface.SOLO_EXPLORATION.route,
            )
        val harness = TimelineStoreHarness().createBoundaryCoordinatorHarness(scope = this)

        try {
            // Act
            val actual = followUpSupportedEntrySurface(snapshot)
            val label = followUpSupportedSessionLabel(snapshot)

            // Assert
            assertEquals(ReferenceSurface.MAIN_GUIDED, actual)
            assertEquals("Start new guided session", label)
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startFollowUpSupportedSessionNavigatesBeforeStartingTheNewSession() = runTest {
        // Arrange
        val harness = TimelineStoreHarness().createBoundaryCoordinatorHarness(scope = this)
        val snapshot =
            navigationSnapshot(surfaceOfOrigin = ReferenceSurface.ADVANCED_CONTROLS.route)
        val events = mutableListOf<String>()
        advanceUntilIdle()

        try {
            // Act
            harness.boundaryCoordinator.startFollowUpSupportedSession(
                currentSnapshot = snapshot,
                applySurfaceSelection = { surface ->
                    val surfaceOfOrigin =
                        harness.timelineStore.uiState.value.liveSnapshot.session
                            .configurationSnapshot
                            .getValue("surface")
                    events += "route:${surface.route}"
                    events += "state:$surfaceOfOrigin"
                },
            )
            advanceUntilIdle()

            // Assert
            assertEquals(listOf("route:advanced-controls", "state:main-guided"), events)
            assertEquals(
                ReferenceSurface.ADVANCED_CONTROLS.route,
                harness.timelineStore.uiState.value.liveSnapshot.session.configurationSnapshot
                    .getValue("surface"),
            )
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun confirmBoundaryTransitionsSupportedLiveSessionToSoloAndWritesFullExport() = runTest {
        // Arrange
        val harness = TimelineStoreHarness().createBoundaryCoordinatorHarness(scope = this)
        val routes = mutableListOf<String>()
        advanceUntilIdle()

        try {
            // Act
            harness.boundaryCoordinator.completeBoundary(
                request =
                    SessionBoundaryRequest.LeaveSupportedSession(ReferenceSurface.SOLO_EXPLORATION),
                continuation = BoundaryContinuation.EXPORT_AND_CONTINUE,
                applySurfaceSelection = { surface -> routes += surface.route },
            )
            advanceUntilIdle()

            // Assert
            assertEquals(listOf(ReferenceSurface.SOLO_EXPLORATION.route), routes)
            assertEquals(
                ReferenceSurface.SOLO_EXPLORATION.route,
                harness.timelineStore.uiState.value.liveSnapshot.session.configurationSnapshot
                    .getValue("surface"),
            )
            assertTrue(harness.timelineStore.uiState.value.lastExportPath!!.endsWith("-full.json"))
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun endSupportedSessionRetainsEndedEvidenceAndHonorsFullExport() = runTest {
        // Arrange
        val harness =
            TimelineStoreHarness(initialTimeline = listOf(timelineStoreEntry("live-1", "Live")))
                .createBoundaryCoordinatorHarness(scope = this)
        advanceUntilIdle()

        try {
            // Act
            harness.boundaryCoordinator.endSupportedSession(
                preEndExportPolicy = ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN
            )
            advanceUntilIdle()

            // Assert
            assertEquals(true, harness.timelineStore.uiState.value.isCurrentSessionEnded)
            assertEquals(1, harness.timelineStore.uiState.value.retainedSessions.size)
            assertTrue(harness.timelineStore.uiState.value.lastExportPath!!.endsWith("-full.json"))
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startAlternativeSessionUpdatesTheSessionBeforeRoutingToLab() = runTest {
        // Arrange
        val harness = TimelineStoreHarness().createBoundaryCoordinatorHarness(scope = this)
        val events = mutableListOf<String>()
        advanceUntilIdle()

        try {
            // Act
            harness.boundaryCoordinator.startAlternativeSession(
                surface = ReferenceSurface.LAB,
                applySurfaceSelection = { surface ->
                    val surfaceOfOrigin =
                        harness.timelineStore.uiState.value.liveSnapshot.session
                            .configurationSnapshot
                            .getValue("surface")
                    events += "route:${surface.route}"
                    events += "state:$surfaceOfOrigin"
                },
            )
            advanceUntilIdle()

            // Assert
            assertEquals(listOf("route:lab", "state:lab"), events)
            assertEquals(
                ReferenceSurface.LAB.route,
                harness.timelineStore.uiState.value.liveSnapshot.session.configurationSnapshot
                    .getValue("surface"),
            )
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun confirmBoundaryDoesNotRouteWhenTheSupportedSessionAlreadyEnded() = runTest {
        // Arrange
        val harness =
            TimelineStoreHarness(initialTimeline = listOf(timelineStoreEntry("live-1", "Live")))
                .createBoundaryCoordinatorHarness(scope = this)
        val routes = mutableListOf<String>()
        advanceUntilIdle()
        harness.boundaryCoordinator.endSupportedSession()
        advanceUntilIdle()

        try {
            // Act
            harness.boundaryCoordinator.completeBoundary(
                request =
                    SessionBoundaryRequest.LeaveSupportedSession(ReferenceSurface.SOLO_EXPLORATION),
                continuation = BoundaryContinuation.CONTINUE_WITHOUT_EXPORT,
                applySurfaceSelection = { surface -> routes += surface.route },
            )
            advanceUntilIdle()

            // Assert
            assertEquals(emptyList(), routes)
            assertEquals(true, harness.timelineStore.uiState.value.isCurrentSessionEnded)
            assertEquals(
                ReferenceSurface.MAIN_GUIDED.route,
                harness.timelineStore.uiState.value.liveSnapshot.session.configurationSnapshot
                    .getValue("surface"),
            )
        } finally {
            coroutineContext.cancelChildren()
        }
    }
}

private fun navigationSnapshot(
    authorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE,
    surfaceOfOrigin: String = ReferenceSurface.MAIN_GUIDED.route,
): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = "session-1",
                scenarioId = "guided-first-exchange",
                authorityMode = authorityMode,
                startedAtEpochMillis = 1L,
                configurationSnapshot = mapOf("surface" to surfaceOfOrigin),
            ),
        peers = emptyList(),
        timeline = emptyList(),
        activePowerModeLabel = "Automatic",
    )
}
