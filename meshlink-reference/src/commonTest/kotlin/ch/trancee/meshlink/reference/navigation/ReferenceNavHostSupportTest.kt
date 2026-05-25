package ch.trancee.meshlink.reference.navigation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import ch.trancee.meshlink.reference.timeline.TimelineStoreHarness
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
            navigationSnapshot(surfaceOfOrigin = ReferenceSurfaceId.ADVANCED_CONTROLS.route)
        val harness = transitionServiceHarness(scope = this)

        try {
            // Act
            val actual = followUpSupportedEntrySurface(snapshot)
            val label = harness.transitionService.followUpSupportedSessionLabel(snapshot)

            // Assert
            assertEquals(ReferenceSurfaceId.ADVANCED_CONTROLS, actual)
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
                surfaceOfOrigin = ReferenceSurfaceId.SOLO_EXPLORATION.route,
            )
        val harness = transitionServiceHarness(scope = this)

        try {
            // Act
            val actual = followUpSupportedEntrySurface(snapshot)
            val label = harness.transitionService.followUpSupportedSessionLabel(snapshot)

            // Assert
            assertEquals(ReferenceSurfaceId.MAIN_GUIDED, actual)
            assertEquals("Start new guided session", label)
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startFollowUpSupportedSessionNavigatesBeforeStartingTheNewSession() = runTest {
        // Arrange
        val harness = transitionServiceHarness(scope = this)
        val snapshot =
            navigationSnapshot(surfaceOfOrigin = ReferenceSurfaceId.ADVANCED_CONTROLS.route)
        val events = mutableListOf<String>()
        advanceUntilIdle()

        try {
            // Act
            harness.transitionService.startFollowUpSupportedSession(
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
                ReferenceSurfaceId.ADVANCED_CONTROLS.route,
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
        val harness = transitionServiceHarness(scope = this)
        val routes = mutableListOf<String>()
        advanceUntilIdle()

        try {
            // Act
            harness.transitionService.confirmBoundary(
                request = SessionBoundaryRequest.SupportedTo(ReferenceSurfaceId.SOLO_EXPLORATION),
                exportFirst = true,
                applySurfaceSelection = { surface -> routes += surface.route },
            )
            advanceUntilIdle()

            // Assert
            assertEquals(listOf(ReferenceSurfaceId.SOLO_EXPLORATION.route), routes)
            assertEquals(
                ReferenceSurfaceId.SOLO_EXPLORATION.route,
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
    fun startAlternativeSessionUpdatesTheSessionBeforeRoutingToLab() = runTest {
        // Arrange
        val harness = transitionServiceHarness(scope = this)
        val events = mutableListOf<String>()
        advanceUntilIdle()

        try {
            // Act
            harness.transitionService.startAlternativeSession(
                surface = ReferenceSurfaceId.LAB,
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
                ReferenceSurfaceId.LAB.route,
                harness.timelineStore.uiState.value.liveSnapshot.session.configurationSnapshot
                    .getValue("surface"),
            )
        } finally {
            coroutineContext.cancelChildren()
        }
    }
}

private data class SessionTransitionServiceHarness(
    val timelineStore: TechnicalTimelineStore,
    val transitionService: SessionTransitionService,
)

private fun transitionServiceHarness(
    scope: kotlinx.coroutines.CoroutineScope
): SessionTransitionServiceHarness {
    val timelineStore = TimelineStoreHarness().createStore(scope)
    return SessionTransitionServiceHarness(
        timelineStore = timelineStore,
        transitionService = SessionTransitionService(timelineStore),
    )
}

private fun navigationSnapshot(
    authorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE,
    surfaceOfOrigin: String = ReferenceSurfaceId.MAIN_GUIDED.route,
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
