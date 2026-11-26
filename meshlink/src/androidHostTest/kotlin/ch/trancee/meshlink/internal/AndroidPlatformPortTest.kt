package ch.trancee.meshlink.internal

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import kotlin.test.assertEquals

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
}
