@file:Suppress("FunctionNaming")

package ch.trancee.meshlink.reference.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.trancee.meshlink.reference.design.ReferenceTheme
import ch.trancee.meshlink.reference.navigation.ReferenceNavHost
import ch.trancee.meshlink.reference.platform.PlatformServices

/** Root composable for the MeshLink reference app. */
@Composable
public fun ReferenceApp(platformServices: PlatformServices) {
    ReferenceTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ReferenceNavHost(platformServices = platformServices)
        }
    }
}
