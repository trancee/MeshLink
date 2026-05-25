package ch.trancee.meshlink.reference.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReferencePrimarySectionTest {
    @Test
    fun primarySectionForMapsEachSurfaceToItsOwningSection(): Unit {
        // Arrange
        val expectedSectionsBySurface =
            mapOf(
                ReferenceSurfaceId.MAIN_GUIDED to ReferencePrimarySection.EXCHANGE,
                ReferenceSurfaceId.SOLO_EXPLORATION to ReferencePrimarySection.EXCHANGE,
                ReferenceSurfaceId.ADVANCED_CONTROLS to ReferencePrimarySection.CONTROLS,
                ReferenceSurfaceId.TECHNICAL_TIMELINE to ReferencePrimarySection.EVIDENCE,
                ReferenceSurfaceId.RECENT_HISTORY to ReferencePrimarySection.EVIDENCE,
                ReferenceSurfaceId.LAB to ReferencePrimarySection.LAB,
            )

        // Act
        val actualSectionsBySurface =
            expectedSectionsBySurface.keys.associateWith { surface -> primarySectionFor(surface) }

        // Assert
        assertEquals(expectedSectionsBySurface, actualSectionsBySurface)
    }

    @Test
    fun defaultSurfaceMatchesTheFirstSurfaceForEachSection(): Unit {
        // Arrange
        val sections = ReferencePrimarySection.entries

        // Act
        val mismatchedSections = sections.filter { section ->
            section.defaultSurface != section.surfaces.first()
        }

        // Assert
        assertEquals(emptyList(), mismatchedSections)
    }

    @Test
    fun supportsSubsurfaceSelectionReflectsSurfaceCount(): Unit {
        // Arrange
        val multiSurfaceSections =
            listOf(ReferencePrimarySection.EXCHANGE, ReferencePrimarySection.EVIDENCE)
        val singleSurfaceSections =
            listOf(ReferencePrimarySection.CONTROLS, ReferencePrimarySection.LAB)

        // Act
        val multiSurfaceSupport = multiSurfaceSections.associateWith {
            it.supportsSubsurfaceSelection
        }
        val singleSurfaceSupport = singleSurfaceSections.associateWith {
            it.supportsSubsurfaceSelection
        }

        // Assert
        multiSurfaceSupport.values.forEach { supported -> assertTrue(supported) }
        singleSurfaceSupport.values.forEach { supported -> assertFalse(supported) }
    }
}
