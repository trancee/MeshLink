package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.reference.meshlink.ScriptedReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.REFERENCE_AUTHORITY_MODE_LIVE
import ch.trancee.meshlink.reference.session.OkioReferenceDocumentStore
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.time

@OptIn(ExperimentalForeignApi::class)
internal fun createPlatformServices(): DefaultPlatformServices {
    return createPlatformServices(resolveDocumentsDirectory())
}

@OptIn(ExperimentalForeignApi::class)
internal fun createPlatformServices(documentsDirectory: String): DefaultPlatformServices {
    val clock = { (time(null) * 1000.0).toLong() }
    return DefaultPlatformServices(
        platformName = "iOS",
        defaultAuthorityMode = REFERENCE_AUTHORITY_MODE_LIVE,
        readinessGuidance = readinessGuidance(),
        options =
            DefaultPlatformServicesOptions().apply {
                nowProvider = clock
                appId = "demo.meshlink.reference.ios"
                documentStore = OkioReferenceDocumentStore(documentsDirectory, FileSystem.SYSTEM)
                meshLinkControllerFactory = { surfaceOfOrigin ->
                    ScriptedReferenceMeshLinkController(
                        platformName = "iOS",
                        authorityMode = REFERENCE_AUTHORITY_MODE_LIVE,
                        nowProvider = clock,
                        surfaceOfOrigin = surfaceOfOrigin,
                    )
                }
            },
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun resolveDocumentsDirectory(): String {
    val directories =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    return directories.firstOrNull() as? String ?: "/tmp"
}
