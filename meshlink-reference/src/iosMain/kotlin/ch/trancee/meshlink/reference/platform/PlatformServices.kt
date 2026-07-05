package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.reference.model.REFERENCE_AUTHORITY_MODE_LIVE
import ch.trancee.meshlink.reference.session.OkioReferenceDocumentStore
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.time

@OptIn(ExperimentalForeignApi::class)
public fun createPlatformServices(
    appId: String = "demo.meshlink.reference.ios",
    targetPeerId: String? = null,
    storageSubdirectory: String = "default",
): DefaultPlatformServices {
    val clock = { (time(null) * 1000.0).toLong() }
    val documentsDirectory = "${resolveDocumentsDirectory()}/live-automation/$storageSubdirectory"
    return DefaultPlatformServices(
        platformName = "iOS",
        defaultAuthorityMode = REFERENCE_AUTHORITY_MODE_LIVE,
        readinessGuidance = readinessGuidance(),
        options =
            DefaultPlatformServicesOptions().apply {
                nowProvider = clock
                this.appId = appId
                automationTargetPeerId = targetPeerId
                automationLogger = { message -> println(message) }
                documentStore = OkioReferenceDocumentStore(documentsDirectory, FileSystem.SYSTEM)
            },
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun resolveDocumentsDirectory(): String {
    val directories =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    return directories.firstOrNull() as? String ?: "/tmp"
}
