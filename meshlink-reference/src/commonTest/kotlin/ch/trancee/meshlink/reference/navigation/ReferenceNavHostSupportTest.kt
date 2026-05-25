package ch.trancee.meshlink.reference.navigation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ReferenceNavHostSupportTest {
    @Test
    fun followUpSupportedEntrySurfacePreservesAdvancedSurfaceOfOrigin() {
        // Arrange
        val snapshot =
            navigationSnapshot(surfaceOfOrigin = ReferenceSurfaceId.ADVANCED_CONTROLS.route)

        // Act
        val actual = followUpSupportedEntrySurface(snapshot)

        // Assert
        assertEquals(ReferenceSurfaceId.ADVANCED_CONTROLS, actual)
        assertEquals("Start new advanced session", followUpSupportedSessionLabel(snapshot))
    }

    @Test
    fun followUpSupportedEntrySurfaceDefaultsToGuidedForSoloSessions() {
        // Arrange
        val snapshot =
            navigationSnapshot(
                authorityMode = ReferenceAuthorityMode.SOLO,
                surfaceOfOrigin = ReferenceSurfaceId.SOLO_EXPLORATION.route,
            )

        // Act
        val actual = followUpSupportedEntrySurface(snapshot)

        // Assert
        assertEquals(ReferenceSurfaceId.MAIN_GUIDED, actual)
        assertEquals("Start new guided session", followUpSupportedSessionLabel(snapshot))
    }

    @Test
    fun startFollowUpSupportedSessionNavigatesBeforeStartingTheNewSession() = runTest {
        // Arrange
        val snapshot =
            navigationSnapshot(surfaceOfOrigin = ReferenceSurfaceId.ADVANCED_CONTROLS.route)
        val events = mutableListOf<String>()

        // Act
        startFollowUpSupportedSession(
            currentSnapshot = snapshot,
            applySurfaceSelection = { surface -> events += "route:${surface.route}" },
            startSupportedSession = { surface -> events += "session:${surface.route}" },
        )

        // Assert
        assertEquals(listOf("route:advanced-controls", "session:advanced-controls"), events)
    }
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
