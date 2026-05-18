package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.reference.meshlink.ScriptedReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.session.OkioReferenceDocumentStore
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.time

@OptIn(ExperimentalForeignApi::class)
internal fun createIosPlatformServices(): DefaultPlatformServices {
    val documentsDirectory = resolveIosDocumentsDirectory()
    return DefaultPlatformServices(
        platformName = "iOS",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = iosReadinessGuidance(),
        nowProvider = { time(null) * 1000L },
        documentStore = OkioReferenceDocumentStore(documentsDirectory),
    )
}

@OptIn(ExperimentalForeignApi::class)
internal fun createIosAutomationPlatformServices(
    storageSubdirectory: String,
    blocked: Boolean,
): DefaultPlatformServices {
    val baseDirectory = resolveAutomationDirectory(storageSubdirectory)
    val nowProvider = { time(null) * 1000L }
    return DefaultPlatformServices(
        platformName = "iOS",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = iosReadinessGuidance(),
        readinessBlockers =
            if (blocked) {
                listOf(
                    "Enable Bluetooth on the iPhone before starting the guided exchange.",
                    "Grant the required local Bluetooth permissions for MeshLink.",
                )
            } else {
                emptyList()
            },
        nowProvider = nowProvider,
        documentStore = OkioReferenceDocumentStore(baseDirectory),
        meshLinkControllerOverride =
            ScriptedReferenceMeshLinkController(
                platformName = "iOS",
                authorityMode = ReferenceAuthorityMode.LIVE,
                nowProvider = nowProvider,
            ),
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun resolveIosDocumentsDirectory(): String {
    return NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: error("Unable to resolve iOS documents directory")
}

private fun resolveAutomationDirectory(storageSubdirectory: String): String {
    return buildString {
        append(resolveIosDocumentsDirectory())
        append("/ui-automation")
        append('/')
        append(storageSubdirectory)
    }
}
