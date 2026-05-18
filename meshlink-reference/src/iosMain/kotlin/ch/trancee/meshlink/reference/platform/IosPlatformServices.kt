package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.session.OkioReferenceDocumentStore
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.time

@OptIn(ExperimentalForeignApi::class)
internal fun createIosPlatformServices(): DefaultPlatformServices {
    val documentsDirectory =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: error("Unable to resolve iOS documents directory")
    return DefaultPlatformServices(
        platformName = "iOS",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = iosReadinessGuidance(),
        nowProvider = { time(null) * 1000L },
        documentStore = OkioReferenceDocumentStore(documentsDirectory),
    )
}
