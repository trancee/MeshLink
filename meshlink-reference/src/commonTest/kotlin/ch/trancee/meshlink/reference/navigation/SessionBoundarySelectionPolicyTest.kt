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
                activeRoute = ReferenceSurfaceId.MAIN_GUIDED,
                targetSurface = ReferenceSurfaceId.SOLO_EXPLORATION,
            )

        // Assert
        val boundaryDecision = assertIs<SurfaceSelectionAction.RequireBoundary>(decision)
        assertEquals(
            SessionBoundaryRequest.LeaveSupportedSession(ReferenceSurfaceId.SOLO_EXPLORATION),
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
                activeRoute = ReferenceSurfaceId.MAIN_GUIDED,
                targetSurface = ReferenceSurfaceId.LAB,
            )

        // Assert
        val boundaryDecision = assertIs<SurfaceSelectionAction.RequireBoundary>(decision)
        assertEquals(
            SessionBoundaryRequest.LeaveSupportedSession(ReferenceSurfaceId.LAB),
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
                activeRoute = ReferenceSurfaceId.SOLO_EXPLORATION,
                targetSurface = ReferenceSurfaceId.ADVANCED_CONTROLS,
            )

        // Assert
        val boundaryDecision = assertIs<SurfaceSelectionAction.RequireBoundary>(decision)
        assertEquals(
            SessionBoundaryRequest.LeaveAlternativeSession(ReferenceSurfaceId.ADVANCED_CONTROLS),
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
                activeRoute = ReferenceSurfaceId.LAB,
                targetSurface = ReferenceSurfaceId.LAB,
            )

        // Assert
        val selectionDecision = assertIs<SurfaceSelectionAction.Select>(decision)
        assertEquals(ReferenceSurfaceId.LAB, selectionDecision.surface)
    }

    @Test
    fun `alternative sessions can open evidence surfaces without a new boundary`() {
        // Arrange
        val currentKind = ReferenceSessionKind.SOLO

        // Act
        val decision =
            determineSurfaceSelectionAction(
                currentKind = currentKind,
                activeRoute = ReferenceSurfaceId.SOLO_EXPLORATION,
                targetSurface = ReferenceSurfaceId.TECHNICAL_TIMELINE,
            )

        // Assert
        val selectionDecision = assertIs<SurfaceSelectionAction.Select>(decision)
        assertEquals(ReferenceSurfaceId.TECHNICAL_TIMELINE, selectionDecision.surface)
    }

    @Test
    fun `supported ended selection to solo starts a new session immediately`() {
        // Arrange
        val currentKind = ReferenceSessionKind.SUPPORTED_ENDED

        // Act
        val decision =
            determineSurfaceSelectionAction(
                currentKind = currentKind,
                activeRoute = ReferenceSurfaceId.MAIN_GUIDED,
                targetSurface = ReferenceSurfaceId.SOLO_EXPLORATION,
            )

        // Assert
        val startDecision = assertIs<SurfaceSelectionAction.StartAlternativeSession>(decision)
        assertEquals(ReferenceSurfaceId.SOLO_EXPLORATION, startDecision.surface)
    }

    @Test
    fun `supported ended selection to lab starts a new session immediately`() {
        // Arrange
        val currentKind = ReferenceSessionKind.SUPPORTED_ENDED

        // Act
        val decision =
            determineSurfaceSelectionAction(
                currentKind = currentKind,
                activeRoute = ReferenceSurfaceId.MAIN_GUIDED,
                targetSurface = ReferenceSurfaceId.LAB,
            )

        // Assert
        val startDecision = assertIs<SurfaceSelectionAction.StartAlternativeSession>(decision)
        assertEquals(ReferenceSurfaceId.LAB, startDecision.surface)
    }

    @Test
    fun `supported routes stay in session when moving between guided and advanced surfaces`() {
        // Arrange
        val currentKind = ReferenceSessionKind.SUPPORTED_LIVE

        // Act
        val decision =
            determineSurfaceSelectionAction(
                currentKind = currentKind,
                activeRoute = ReferenceSurfaceId.MAIN_GUIDED,
                targetSurface = ReferenceSurfaceId.ADVANCED_CONTROLS,
            )

        // Assert
        val selectionDecision = assertIs<SurfaceSelectionAction.Select>(decision)
        assertEquals(ReferenceSurfaceId.ADVANCED_CONTROLS, selectionDecision.surface)
    }
}
