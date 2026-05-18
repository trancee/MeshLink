package ch.trancee.meshlink.reference

import androidx.compose.ui.window.ComposeUIViewController
import ch.trancee.meshlink.reference.app.ReferenceApp
import ch.trancee.meshlink.reference.platform.createIosAutomationPlatformServices
import ch.trancee.meshlink.reference.platform.createIosPlatformServices
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIViewController

/** iOS entry point that wraps the shared Compose app in a UIKit controller. */
@OptIn(ExperimentalForeignApi::class)
public fun createReferenceRootViewController(): UIViewController {
    val platformServices = createIosPlatformServices()
    return ComposeUIViewController { ReferenceApp(platformServices = platformServices) }
}

/** iOS UI-automation entry point using deterministic scripted platform services. */
@OptIn(ExperimentalForeignApi::class)
public fun createReferenceAutomationRootViewController(
    storageSubdirectory: String,
    blocked: Boolean,
): UIViewController {
    val platformServices =
        createIosAutomationPlatformServices(
            storageSubdirectory = storageSubdirectory,
            blocked = blocked,
        )
    return ComposeUIViewController { ReferenceApp(platformServices = platformServices) }
}
