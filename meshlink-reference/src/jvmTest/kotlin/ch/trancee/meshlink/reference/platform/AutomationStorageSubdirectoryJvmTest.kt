package ch.trancee.meshlink.reference.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class AutomationStorageSubdirectoryJvmTest {
    @Test
    fun rejectsTraversalAndBlankInputs() {
        // Arrange
        val inputs = listOf("", "   ", "..", "../secrets", "..\\secrets", "nested/escape")

        // Act
        val normalized = inputs.map { normalizeAutomationStorageSubdirectory(it) }

        // Assert
        assertEquals(List(inputs.size) { "default" }, normalized)
    }
}
