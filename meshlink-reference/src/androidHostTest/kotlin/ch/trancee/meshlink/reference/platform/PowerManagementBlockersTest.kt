package ch.trancee.meshlink.reference.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PowerManagementBlockersTest {
    @Test
    fun absentWithoutAPowerManager() {
        // Arrange / Act
        val blockers =
            powerManagementBlockers(
                hasPowerManager = false,
                idleMode = true,
                interactive = false,
                ignoringOptimizations = false,
            )

        // Assert
        assertEquals(expected = emptyList(), actual = blockers)
    }

    @Test
    fun presentWhenDeviceIsIdle() {
        // Arrange / Act
        val blockers =
            powerManagementBlockers(
                hasPowerManager = true,
                idleMode = true,
                interactive = true,
                ignoringOptimizations = true,
            )

        // Assert
        assertTrue(blockers.single().contains("Keep the screen awake"))
    }

    @Test
    fun presentWhenScreenIsOffAndOptimizationsAreNotIgnored() {
        // Arrange / Act
        val blockers =
            powerManagementBlockers(
                hasPowerManager = true,
                idleMode = false,
                interactive = false,
                ignoringOptimizations = false,
            )

        // Assert
        assertTrue(blockers.single().contains("Keep the screen awake"))
    }

    @Test
    fun absentWhenInteractiveAndNotIdle() {
        // Arrange / Act
        val blockers =
            powerManagementBlockers(
                hasPowerManager = true,
                idleMode = false,
                interactive = true,
                ignoringOptimizations = false,
            )

        // Assert
        assertEquals(expected = emptyList(), actual = blockers)
    }

    @Test
    fun absentWhenNotIdleAndOptimizationsAreIgnored() {
        // Arrange / Act
        val blockers =
            powerManagementBlockers(
                hasPowerManager = true,
                idleMode = false,
                interactive = false,
                ignoringOptimizations = true,
            )

        // Assert
        assertEquals(expected = emptyList(), actual = blockers)
    }
}
