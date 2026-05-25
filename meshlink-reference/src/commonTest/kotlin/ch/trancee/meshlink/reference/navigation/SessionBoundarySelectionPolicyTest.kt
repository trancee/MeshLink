package ch.trancee.meshlink.reference.navigation

import ch.trancee.meshlink.reference.session.ReferenceSessionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SessionBoundarySelectionPolicyTest {
    @Test
    fun `supported live selection to solo requires a supported boundary`() {
        // Arrange
        val currentKind = ReferenceSessionKind.SUPPORTED_LIVE

        // Act
        val decision =
            determineSurfaceSelectionAction(
                currentKind = currentKind,
                activeRoute = ReferenceSurface.MAIN_GUIDED,
                targetSurface = ReferenceSurface.SOLO_EXPLORATION,
            )

        // Assert
        val boundaryDecision = assertIs<SurfaceSelectionAction.RequireBoundary>(decision)
        assertEquals(
            SessionBoundaryRequest.LeaveSupportedSession(ReferenceSurface.SOLO_EXPLORATION),
            boundaryDecision.request,
        )
    }

    @Test
    fun `supported live selection to lab requires a supported boundary`() {
        // Arrange
        val currentKind = ReferenceSessionKind.SUPPORTED_LIVE

        // Act
        val decision =
            determineSurfaceSelectionAction(
                currentKind = currentKind,
                activeRoute = ReferenceSurface.MAIN_GUIDED,
                targetSurface = ReferenceSurface.LAB,
            )

        // Assert
        val boundaryDecision = assertIs<SurfaceSelectionAction.RequireBoundary>(decision)
        assertEquals(
            SessionBoundaryRequest.LeaveSupportedSession(ReferenceSurface.LAB),
            boundaryDecision.request,
        )
    }

    @Test
    fun `alternative sessions require a boundary before switching to another primary surface`() {
        // Arrange
        val currentKind = ReferenceSessionKind.SOLO

        // Act
        val decision =
            determineSurfaceSelectionAction(
                currentKind = currentKind,
                activeRoute = ReferenceSurface.SOLO_EXPLORATION,
                targetSurface = ReferenceSurface.ADVANCED_CONTROLS,
            )

        // Assert
        val boundaryDecision = assertIs<SurfaceSelectionAction.RequireBoundary>(decision)
        assertEquals(
            SessionBoundaryRequest.LeaveAlternativeSession(ReferenceSurface.ADVANCED_CONTROLS),
            boundaryDecision.request,
        )
    }

    @Test
    fun `alternative sessions keep the current surface without a new boundary`() {
        // Arrange
        val currentKind = ReferenceSessionKind.LAB

        // Act
        val decision =
            determineSurfaceSelectionAction(
                currentKind = currentKind,
                activeRoute = ReferenceSurface.LAB,
                targetSurface = ReferenceSurface.LAB,
            )

        // Assert
        val selectionDecision = assertIs<SurfaceSelectionAction.Select>(decision)
        assertEquals(ReferenceSurface.LAB, selectionDecision.surface)
    }

    @Test
    fun `alternative sessions can open evidence surfaces without a new boundary`() {
        // Arrange
        val currentKind = ReferenceSessionKind.SOLO

        // Act
        val decision =
            determineSurfaceSelectionAction(
                currentKind = currentKind,
                activeRoute = ReferenceSurface.SOLO_EXPLORATION,
                targetSurface = ReferenceSurface.TECHNICAL_TIMELINE,
            )

        // Assert
        val selectionDecision = assertIs<SurfaceSelectionAction.Select>(decision)
        assertEquals(ReferenceSurface.TECHNICAL_TIMELINE, selectionDecision.surface)
    }

    @Test
    fun `supported ended selection to solo starts a new session immediately`() {
        // Arrange
        val currentKind = ReferenceSessionKind.SUPPORTED_ENDED

        // Act
        val decision =
            determineSurfaceSelectionAction(
                currentKind = currentKind,
                activeRoute = ReferenceSurface.MAIN_GUIDED,
                targetSurface = ReferenceSurface.SOLO_EXPLORATION,
            )

        // Assert
        val startDecision = assertIs<SurfaceSelectionAction.StartAlternativeSession>(decision)
        assertEquals(ReferenceSurface.SOLO_EXPLORATION, startDecision.surface)
    }

    @Test
    fun `supported ended selection to lab starts a new session immediately`() {
        // Arrange
        val currentKind = ReferenceSessionKind.SUPPORTED_ENDED

        // Act
        val decision =
            determineSurfaceSelectionAction(
                currentKind = currentKind,
                activeRoute = ReferenceSurface.MAIN_GUIDED,
                targetSurface = ReferenceSurface.LAB,
            )

        // Assert
        val startDecision = assertIs<SurfaceSelectionAction.StartAlternativeSession>(decision)
        assertEquals(ReferenceSurface.LAB, startDecision.surface)
    }

    @Test
    fun `supported routes stay in session when moving between guided and advanced surfaces`() {
        // Arrange
        val currentKind = ReferenceSessionKind.SUPPORTED_LIVE

        // Act
        val decision =
            determineSurfaceSelectionAction(
                currentKind = currentKind,
                activeRoute = ReferenceSurface.MAIN_GUIDED,
                targetSurface = ReferenceSurface.ADVANCED_CONTROLS,
            )

        // Assert
        val selectionDecision = assertIs<SurfaceSelectionAction.Select>(decision)
        assertEquals(ReferenceSurface.ADVANCED_CONTROLS, selectionDecision.surface)
    }
}
