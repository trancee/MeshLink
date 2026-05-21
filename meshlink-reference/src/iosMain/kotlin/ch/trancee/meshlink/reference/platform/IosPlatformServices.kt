package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfig
import ch.trancee.meshlink.reference.automation.ReferenceAutomationMode
import ch.trancee.meshlink.reference.automation.ReferenceAutomationRole
import ch.trancee.meshlink.reference.meshlink.ScriptedReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.session.OkioReferenceDocumentStore
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
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
        documentStore = OkioReferenceDocumentStore(documentsDirectory, FileSystem.SYSTEM),
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
        documentStore = OkioReferenceDocumentStore(baseDirectory, FileSystem.SYSTEM),
        automationConfig =
            ReferenceAutomationConfig(
                mode = ReferenceAutomationMode.SCRIPTED_UI,
                role = ReferenceAutomationRole.PASSIVE,
                appId = "demo.meshlink.reference.automation",
                storageSubdirectory = storageSubdirectory,
            ),
        automationLogger = ::println,
        meshLinkControllerOverride =
            ScriptedReferenceMeshLinkController(
                platformName = "iOS",
                authorityMode = ReferenceAuthorityMode.LIVE,
                nowProvider = nowProvider,
            ),
    )
}

@OptIn(ExperimentalForeignApi::class)
internal fun createIosLiveAutomationPlatformServices(
    storageSubdirectory: String,
    appId: String,
    role: ReferenceAutomationRole,
): DefaultPlatformServices {
    val baseDirectory = buildString {
        append(resolveIosDocumentsDirectory())
        append("/live-automation")
        append('/')
        append(storageSubdirectory)
    }
    val nowProvider = { time(null) * 1000L }
    return DefaultPlatformServices(
        platformName = "iOS",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = iosReadinessGuidance(),
        nowProvider = nowProvider,
        appId = appId,
        documentStore = OkioReferenceDocumentStore(baseDirectory, FileSystem.SYSTEM),
        automationConfig =
            ReferenceAutomationConfig(
                mode = ReferenceAutomationMode.LIVE_PROOF,
                role = role,
                appId = appId,
                storageSubdirectory = storageSubdirectory,
            ),
        automationLogger = ::println,
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
