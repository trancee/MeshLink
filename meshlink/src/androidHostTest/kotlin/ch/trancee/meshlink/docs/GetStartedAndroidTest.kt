package ch.trancee.meshlink.docs

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.android
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertSame

// The Android-only code of docs/tutorials/get-started.md; ./gradlew checkDocRegions keeps the two equal.
// It never creates a node: MeshLink.create loads libsodium's JNI library, which a JVM loads into one
// classloader only, so a Robolectric sandbox can't load it after a plain JVM test did.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class GetStartedAndroidTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    @Test
    fun anyContextOfTheAppGivesTheAppsOnePlatform() {
        // Arrange
        val app = ApplicationProvider.getApplicationContext<Application>()

        // Act
        val platform = platform(ContextWrapper(app))

        // Assert
        assertSame(MeshPlatform.android(app), platform)
    }

    private fun platform(context: Context): MeshPlatform {
        // region platform-android
        val platform = MeshPlatform.android(context)
        // endregion
        return platform
    }
}
