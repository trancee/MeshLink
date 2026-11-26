package ch.trancee.meshlink.internal

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 37])
class AndroidPlatformPortTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun thePortNamesAndroidAndItsReleaseWithTheApiLevel() {
        // Arrange
        ShadowBuild.setVersionRelease("15")

        // Act
        val port = AndroidPlatformPort(app)

        // Assert
        assertEquals("Android", port.platformName)
        assertEquals("15 (API ${Build.VERSION.SDK_INT})", port.osVersion)
    }

    @Test
    fun thePortReportsDozeNowAndOnEachChangeButNeverTheBackgroundOrASuspension() =
        runTest {
            // Arrange
            val power = shadowOf(app.getSystemService(PowerManager::class.java))
            power.setIsDeviceIdleMode(true)
            val port = AndroidPlatformPort(app)
            val before = port.doze.first()

            // Act
            power.setIsDeviceIdleMode(false)
            app.sendBroadcast(Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()

            // Assert
            assertEquals(listOf(true, false), listOf(before, port.doze.first()))
            assertFalse(port.background.first())
            assertEquals(emptyList(), port.suspensions.toList())
        }
}
