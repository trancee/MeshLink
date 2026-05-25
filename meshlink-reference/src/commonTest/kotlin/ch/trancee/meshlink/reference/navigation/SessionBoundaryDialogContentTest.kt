package ch.trancee.meshlink.reference.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionBoundaryDialogContentTest {
    @Test
    fun `supported boundary dialog content explains the new solo session`() {
        // Arrange
        val request =
            SessionBoundaryRequest.LeaveSupportedSession(ReferenceSurfaceId.SOLO_EXPLORATION)

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
        val request = SessionBoundaryRequest.LeaveAlternativeSession(ReferenceSurfaceId.LAB)

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
        val request = SessionBoundaryRequest.LeaveAlternativeSession(ReferenceSurfaceId.MAIN_GUIDED)

        // Act
        val dialogContent = request.toDialogContent()

        // Assert
        assertEquals(
            "This closes the current solo or lab session and starts a new supported session.",
            dialogContent.body,
        )
    }
}
