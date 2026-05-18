package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.time

@OptIn(ExperimentalForeignApi::class)
internal fun createIosPlatformServices(): DefaultPlatformServices {
    return DefaultPlatformServices(
        platformName = "iOS",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = iosReadinessGuidance(),
        nowProvider = { time(null) * 1000L },
        documentStore = IosReferenceDocumentStore(),
    )
}
