package ch.trancee.meshlink.reference

import androidx.compose.ui.window.ComposeUIViewController
import ch.trancee.meshlink.reference.app.ReferenceApp
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.platform.DefaultPlatformServices
import ch.trancee.meshlink.reference.platform.IosReferenceDocumentStore
import ch.trancee.meshlink.reference.platform.iosReadinessGuidance
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIViewController
import platform.posix.time

/**
 * iOS entry point that wraps the shared Compose app in a UIKit controller.
 */
@OptIn(ExperimentalForeignApi::class)
public fun createReferenceRootViewController(): UIViewController {
    val platformServices =
        DefaultPlatformServices(
            platformName = "iOS",
            defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
            readinessGuidance = iosReadinessGuidance(),
            nowProvider = { time(null) * 1000L },
            documentStore = IosReferenceDocumentStore(),
        )
    return ComposeUIViewController {
        ReferenceApp(platformServices = platformServices)
    }
}
