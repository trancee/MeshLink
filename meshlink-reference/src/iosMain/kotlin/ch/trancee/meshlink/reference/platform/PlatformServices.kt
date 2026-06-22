package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.reference.meshlink.ScriptedReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.REFERENCE_AUTHORITY_MODE_LIVE
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
internal fun createPlatformServices(
    documentsDirectory: String,
    fileSystem: FileSystem = FileSystem.SYSTEM,
    nowProvider: () -> Long = { time(null) * 1000L },
): DefaultPlatformServices {
    return DefaultPlatformServices(
        platformName = "iOS",
        defaultAuthorityMode = REFERENCE_AUTHORITY_MODE_LIVE,
        readinessGuidance = readinessGuidance(),
        options = DefaultPlatformServicesOptions().apply { this.nowProvider = nowProvider },
    )
}

@OptIn(ExperimentalForeignApi::class)
internal fun createAutomationPlatformServices(
    storageSubdirectory: String,
    blocked: Boolean,
): DefaultPlatformServices {
    val baseDirectory = resolveAutomationDirectory(storageSubdirectory)
    val clock = { time(null) * 1000L }
    return DefaultPlatformServices(
        platformName = "iOS",
        defaultAuthorityMode = REFERENCE_AUTHORITY_MODE_LIVE,
        readinessGuidance = readinessGuidance(),
        options =
            DefaultPlatformServicesOptions().apply {
                readinessBlockers =
                    if (blocked) {
                        listOf(
                            "Enable Bluetooth on the iPhone before starting the guided exchange.",
                            "Grant the required local Bluetooth permissions for MeshLink.",
                        )
                    } else {
                        emptyList()
                    }
                nowProvider = clock
                automationLogger = ::println
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
internal fun createLiveAutomationPlatformServices(
    storageSubdirectory: String,
    appId: String,
    role: String,
    requiredPeerCount: Int = 1,
    targetPeerIndex: Int = 0,
    targetPeerId: String? = null,
    scenario: String = "direct-guided",
): DefaultPlatformServices {
    val baseDirectory = buildString {
        append(resolveDocumentsDirectory())
        append("/live-automation")
        append('/')
        append(storageSubdirectory)
    }
    val clock = { time(null) * 1000L }
    val automationAppId = appId
    return DefaultPlatformServices(
        platformName = "iOS",
        defaultAuthorityMode = REFERENCE_AUTHORITY_MODE_LIVE,
        readinessGuidance = readinessGuidance(),
        options =
            DefaultPlatformServicesOptions().apply {
                nowProvider = clock
                this.appId = automationAppId
                automationLogger = ::println
            },
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun resolveDocumentsDirectory(): String {
    return NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: error("Unable to resolve iOS documents directory")
}

private fun resolveAutomationDirectory(storageSubdirectory: String): String {
    return buildString {
        append(resolveDocumentsDirectory())
        append("/ui-automation")
        append('/')
        append(storageSubdirectory)
    }
}
