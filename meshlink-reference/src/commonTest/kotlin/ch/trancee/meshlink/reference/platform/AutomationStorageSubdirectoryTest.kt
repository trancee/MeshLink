package ch.trancee.meshlink.reference.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class AutomationStorageSubdirectoryTest {
    @Test
    fun preservesSafeSubdirectoryNames() {
        // Arrange
        val input = "session-01.alpha_2"

        // Act
        val actual = normalizeAutomationStorageSubdirectory(input)

        // Assert
        assertEquals(input, actual)
    }

    @Test
    fun fallsBackToDefaultForBlankOrTraversalInputs() {
        // Arrange
        val maliciousInputs = listOf("", "   ", "..", "../secrets", "..\\secrets", "nested/escape")

        // Act
        val actual = maliciousInputs.map { normalizeAutomationStorageSubdirectory(it) }

        // Assert
        assertEquals(List(maliciousInputs.size) { "default" }, actual)
    }
}
