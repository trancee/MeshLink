package ch.trancee.meshlink.reference

import androidx.compose.ui.window.ComposeUIViewController
import ch.trancee.meshlink.reference.app.ReferenceApp
import ch.trancee.meshlink.reference.platform.createIosPlatformServices
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIViewController

/**
 * iOS entry point that wraps the shared Compose app in a UIKit controller.
 */
@OptIn(ExperimentalForeignApi::class)
public fun createReferenceRootViewController(): UIViewController {
    val platformServices = createIosPlatformServices()
    return ComposeUIViewController {
        ReferenceApp(platformServices = platformServices)
    }
}
