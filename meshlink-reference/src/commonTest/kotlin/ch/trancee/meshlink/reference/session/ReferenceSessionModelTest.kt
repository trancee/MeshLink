package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReferenceSessionModelTest {
    @Test
    fun referenceSessionKindPrefersSoloAuthorityOverOtherSignals(): Unit {
        // Arrange
        val snapshot =
            referenceSnapshot(
                authorityMode = ReferenceAuthorityMode.SOLO,
                surface = "lab",
                endedAtEpochMillis = 42L,
            )

        // Act
        val actual = snapshot.referenceSessionKind()

        // Assert
        assertEquals(ReferenceSessionKind.SOLO, actual)
    }

    @Test
    fun referenceSessionKindReturnsLabForLiveLabSurface(): Unit {
        // Arrange
        val snapshot = referenceSnapshot(surface = "lab")

        // Act
        val actual = snapshot.referenceSessionKind()

        // Assert
        assertEquals(ReferenceSessionKind.LAB, actual)
    }

    @Test
    fun allowsFullPayloadExportOnlyForSupportedLiveSessions(): Unit {
        // Arrange
        val supportedLive = referenceSnapshot()
        val supportedEnded = referenceSnapshot(endedAtEpochMillis = 42L)
        val solo = referenceSnapshot(authorityMode = ReferenceAuthorityMode.SOLO)
        val lab = referenceSnapshot(surface = "lab")

        // Act
        val supportedLiveAllowed = supportedLive.allowsFullPayloadExport()
        val supportedEndedAllowed = supportedEnded.allowsFullPayloadExport()
        val soloAllowed = solo.allowsFullPayloadExport()
        val labAllowed = lab.allowsFullPayloadExport()

        // Assert
        assertTrue(supportedLiveAllowed)
        assertFalse(supportedEndedAllowed)
        assertFalse(soloAllowed)
        assertFalse(labAllowed)
    }

    @Test
    fun withSurfaceOfOriginReplacesTheSurfaceConfigurationValue(): Unit {
        // Arrange
        val snapshot = referenceSnapshot(surface = "main-guided")

        // Act
        val actual = snapshot.withSurfaceOfOrigin("advanced-controls")

        // Assert
        assertEquals("advanced-controls", actual.session.configurationSnapshot.getValue("surface"))
        assertEquals(
            snapshot.session.configurationSnapshot.getValue("platform"),
            actual.session.configurationSnapshot.getValue("platform"),
        )
    }

    @Test
    fun createStaticReferenceSessionSnapshotReusesRuntimeLabelsWhileResettingEvidence(): Unit {
        // Arrange
        val currentSnapshot =
            referenceSnapshot(
                meshStateLabel = "Running",
                activePowerModeLabel = "Performance",
                surface = "main-guided",
                timeline =
                    listOf(
                        TimelineEntry(
                            entryId = "old-1",
                            sessionId = "session-1",
                            occurredAtEpochMillis = 1L,
                            family = TimelineFamily.USER,
                            severity = TimelineSeverity.INFO,
                            title = "Existing",
                            detail = "Existing detail",
                        )
                    ),
            )
        val nowProvider = { 99L }

        // Act
        val actual =
            createStaticReferenceSessionSnapshot(
                platformName = "iOS",
                nowProvider = nowProvider,
                currentSnapshot = currentSnapshot,
                scenarioId = "solo-exploration",
                authorityMode = ReferenceAuthorityMode.SOLO,
                surfaceOfOrigin = "solo-exploration",
                title = "Solo exploration opened",
                detail = "Solo exploration is active on iOS.",
            )

        // Assert
        assertEquals("solo-exploration-ios-99", actual.session.sessionId)
        assertEquals("Running", actual.session.meshStateLabel)
        assertEquals("Performance", actual.activePowerModeLabel)
        assertEquals(ReferenceHistoryStatus.LIVE, actual.session.historyStatus)
        assertEquals("solo-exploration", actual.session.configurationSnapshot.getValue("surface"))
        assertTrue(actual.peers.isEmpty())
        assertEquals(1, actual.timeline.size)
        assertEquals("Solo exploration opened", actual.timeline.single().title)
    }
}

private fun referenceSnapshot(
    authorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE,
    surface: String = "main-guided",
    endedAtEpochMillis: Long? = null,
    meshStateLabel: String = "Running",
    activePowerModeLabel: String = "Automatic",
    timeline: List<TimelineEntry> = emptyList(),
): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = "session-1",
                scenarioId = "guided-first-exchange",
                authorityMode = authorityMode,
                startedAtEpochMillis = 1L,
                endedAtEpochMillis = endedAtEpochMillis,
                meshStateLabel = meshStateLabel,
                configurationSnapshot = mapOf("platform" to "iOS", "surface" to surface),
                historyStatus = ReferenceHistoryStatus.LIVE,
            ),
        peers = emptyList(),
        timeline = timeline,
        activePowerModeLabel = activePowerModeLabel,
    )
}
