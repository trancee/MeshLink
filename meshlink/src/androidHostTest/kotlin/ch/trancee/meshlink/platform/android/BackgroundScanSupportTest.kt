package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.platform.android.scan.backgroundScanAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BackgroundScanSupportTest {
    @Test
    fun backgroundScanActionIsScopedToThePackageName(): Unit {
        // Act
        val action = backgroundScanAction(packageName = "ch.trancee.meshlink.reference")

        // Assert
        assertEquals("ch.trancee.meshlink.reference.MESHLINK_BACKGROUND_SCAN_RESULT", action)
    }

    @Test
    fun backgroundScanActionDiffersAcrossPackagesToAvoidCrossAppDelivery(): Unit {
        // Act
        val first = backgroundScanAction(packageName = "ch.trancee.meshlink.reference")
        val second = backgroundScanAction(packageName = "ch.trancee.meshlink.proof")

        // Assert
        assertNotEquals(first, second)
    }
}
