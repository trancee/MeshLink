package ch.trancee.meshlink.internal

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSThread
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosAppLifecycleTest {
    @Test
    fun theAppIsInTheBackgroundFromWhenItEntersUntilItReturnsToTheForeground() {
        // Arrange
        val center = NSNotificationCenter()
        val lifecycle = IosAppLifecycle(center)

        // Act
        center.postNotificationName(UIApplicationDidEnterBackgroundNotification, null)
        val entered = lifecycle.background.value
        center.postNotificationName(UIApplicationWillEnterForegroundNotification, null)

        // Assert
        assertEquals(listOf(true, false), listOf(entered, lifecycle.background.value))
    }

    @Test
    fun theContinuousClockCountsTheTimeThatPassed() {
        // Arrange
        val lifecycle = IosAppLifecycle(NSNotificationCenter())
        val start = lifecycle.continuousMillis()

        // Act
        NSThread.sleepForTimeInterval(PAUSE_SECONDS)

        // Assert
        val passed = lifecycle.continuousMillis() - start
        assertTrue(passed >= PAUSE_MILLIS, "$passed ms")
    }

    private companion object {
        const val PAUSE_SECONDS = 0.05
        const val PAUSE_MILLIS = 50L
    }
}
