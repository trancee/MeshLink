package ch.trancee.meshlink.reference

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ch.trancee.meshlink.reference.app.ReferenceApp
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.platform.DefaultPlatformServices

/**
 * Android entry point for the shared reference app shell.
 */
public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        val platformServices =
            DefaultPlatformServices(
                platformName = "Android",
                defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
                nowProvider = { System.currentTimeMillis() },
            )
        setContent {
            ReferenceApp(platformServices = platformServices)
        }
    }
}
