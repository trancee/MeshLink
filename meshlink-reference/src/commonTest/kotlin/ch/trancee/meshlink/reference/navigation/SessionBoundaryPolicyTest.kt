package ch.trancee.meshlink.reference.navigation

import ch.trancee.meshlink.reference.session.ReferenceSessionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SessionBoundaryPolicyTest {
    @Test
    fun `supported live selection to solo requires a supported boundary`() {
        // Arrange
        val request =
            ReferenceSurfaceSelectionRequest(
                currentKind = ReferenceSessionKind.SUPPORTED_LIVE,
                activeRoute = ReferenceSurfaceId.MAIN_GUIDED,
                targetSurface = ReferenceSurfaceId.SOLO_EXPLORATION,
            )

        // Act
        val decision = resolveSurfaceSelection(request)

        // Assert
        val boundaryDecision = assertIs<ReferenceSurfaceSelectionDecision.RequireBoundary>(decision)
        assertEquals(
            SessionBoundaryRequest.SupportedTo(ReferenceSurfaceId.SOLO_EXPLORATION),
            boundaryDecision.request,
        )
    }

    @Test
    fun `supported live selection to lab requires a supported boundary`() {
        // Arrange
        val request =
            ReferenceSurfaceSelectionRequest(
                currentKind = ReferenceSessionKind.SUPPORTED_LIVE,
                activeRoute = ReferenceSurfaceId.MAIN_GUIDED,
                targetSurface = ReferenceSurfaceId.LAB,
            )

        // Act
        val decision = resolveSurfaceSelection(request)

        // Assert
        val boundaryDecision = assertIs<ReferenceSurfaceSelectionDecision.RequireBoundary>(decision)
        assertEquals(
            SessionBoundaryRequest.SupportedTo(ReferenceSurfaceId.LAB),
            boundaryDecision.request,
        )
    }

    @Test
    fun `alternative sessions require a boundary before switching to another primary surface`() {
        // Arrange
        val request =
            ReferenceSurfaceSelectionRequest(
                currentKind = ReferenceSessionKind.SOLO,
                activeRoute = ReferenceSurfaceId.SOLO_EXPLORATION,
                targetSurface = ReferenceSurfaceId.ADVANCED_CONTROLS,
            )

        // Act
        val decision = resolveSurfaceSelection(request)

        // Assert
        val boundaryDecision = assertIs<ReferenceSurfaceSelectionDecision.RequireBoundary>(decision)
        assertEquals(
            SessionBoundaryRequest.AlternativeTo(ReferenceSurfaceId.ADVANCED_CONTROLS),
            boundaryDecision.request,
        )
    }

    @Test
    fun `alternative sessions keep the current surface without a new boundary`() {
        // Arrange
        val request =
            ReferenceSurfaceSelectionRequest(
                currentKind = ReferenceSessionKind.LAB,
                activeRoute = ReferenceSurfaceId.LAB,
                targetSurface = ReferenceSurfaceId.LAB,
            )

        // Act
        val decision = resolveSurfaceSelection(request)

        // Assert
        val selectionDecision = assertIs<ReferenceSurfaceSelectionDecision.SelectSurface>(decision)
        assertEquals(ReferenceSurfaceId.LAB, selectionDecision.surface)
    }

    @Test
    fun `alternative sessions can open evidence surfaces without a new boundary`() {
        // Arrange
        val request =
            ReferenceSurfaceSelectionRequest(
                currentKind = ReferenceSessionKind.SOLO,
                activeRoute = ReferenceSurfaceId.SOLO_EXPLORATION,
                targetSurface = ReferenceSurfaceId.TECHNICAL_TIMELINE,
            )

        // Act
        val decision = resolveSurfaceSelection(request)

        // Assert
        val selectionDecision = assertIs<ReferenceSurfaceSelectionDecision.SelectSurface>(decision)
        assertEquals(ReferenceSurfaceId.TECHNICAL_TIMELINE, selectionDecision.surface)
    }

    @Test
    fun `supported ended selection to solo starts a new session immediately`() {
        // Arrange
        val request =
            ReferenceSurfaceSelectionRequest(
                currentKind = ReferenceSessionKind.SUPPORTED_ENDED,
                activeRoute = ReferenceSurfaceId.MAIN_GUIDED,
                targetSurface = ReferenceSurfaceId.SOLO_EXPLORATION,
            )

        // Act
        val decision = resolveSurfaceSelection(request)

        // Assert
        val startDecision = assertIs<ReferenceSurfaceSelectionDecision.StartNewSession>(decision)
        assertEquals(ReferenceSurfaceId.SOLO_EXPLORATION, startDecision.surface)
    }

    @Test
    fun `supported ended selection to lab starts a new session immediately`() {
        // Arrange
        val request =
            ReferenceSurfaceSelectionRequest(
                currentKind = ReferenceSessionKind.SUPPORTED_ENDED,
                activeRoute = ReferenceSurfaceId.MAIN_GUIDED,
                targetSurface = ReferenceSurfaceId.LAB,
            )

        // Act
        val decision = resolveSurfaceSelection(request)

        // Assert
        val startDecision = assertIs<ReferenceSurfaceSelectionDecision.StartNewSession>(decision)
        assertEquals(ReferenceSurfaceId.LAB, startDecision.surface)
    }

    @Test
    fun `supported routes stay in session when moving between guided and advanced surfaces`() {
        // Arrange
        val request =
            ReferenceSurfaceSelectionRequest(
                currentKind = ReferenceSessionKind.SUPPORTED_LIVE,
                activeRoute = ReferenceSurfaceId.MAIN_GUIDED,
                targetSurface = ReferenceSurfaceId.ADVANCED_CONTROLS,
            )

        // Act
        val decision = resolveSurfaceSelection(request)

        // Assert
        val selectionDecision = assertIs<ReferenceSurfaceSelectionDecision.SelectSurface>(decision)
        assertEquals(ReferenceSurfaceId.ADVANCED_CONTROLS, selectionDecision.surface)
    }

    @Test
    fun `supported boundary dialog content explains the new solo session`() {
        // Arrange
        val request = SessionBoundaryRequest.SupportedTo(ReferenceSurfaceId.SOLO_EXPLORATION)

        // Act
        val dialogContent = request.toDialogContent()

        // Assert
        assertEquals("Start a new session", dialogContent.title)
        assertEquals(
            "This closes the current supported session and starts a new solo session.",
            dialogContent.body,
        )
        assertEquals("Export full and continue", dialogContent.exportLabel)
        assertEquals("Continue without export", dialogContent.continueLabel)
    }

    @Test
    fun `alternative boundary dialog content explains the new lab session`() {
        // Arrange
        val request = SessionBoundaryRequest.AlternativeTo(ReferenceSurfaceId.LAB)

        // Act
        val dialogContent = request.toDialogContent()

        // Assert
        assertEquals("Leave current session", dialogContent.title)
        assertEquals(
            "This closes the current solo or lab session and starts a new lab session.",
            dialogContent.body,
        )
        assertEquals("Export redacted and continue", dialogContent.exportLabel)
        assertEquals("Continue without export", dialogContent.continueLabel)
    }

    @Test
    fun `alternative boundary dialog treats guided return as a supported session`() {
        // Arrange
        val request = SessionBoundaryRequest.AlternativeTo(ReferenceSurfaceId.MAIN_GUIDED)

        // Act
        val dialogContent = request.toDialogContent()

        // Assert
        assertEquals(
            "This closes the current solo or lab session and starts a new supported session.",
            dialogContent.body,
        )
    }
}
