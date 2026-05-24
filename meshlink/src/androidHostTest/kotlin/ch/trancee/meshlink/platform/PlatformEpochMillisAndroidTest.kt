package ch.trancee.meshlink.platform

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformEpochMillisAndroidTest {
    @Test
    fun `currentEpochMillis returns a positive nondecreasing value on Android`() {
        // Arrange / Act
        val first = currentEpochMillis()
        val second = currentEpochMillis()

        // Assert
        assertTrue(first > 0L)
        assertTrue(second >= first)
    }
}
