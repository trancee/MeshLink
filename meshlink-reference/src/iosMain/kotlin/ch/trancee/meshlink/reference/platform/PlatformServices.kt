package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfig
import ch.trancee.meshlink.reference.automation.ReferenceAutomationMode
import ch.trancee.meshlink.reference.automation.ReferenceAutomationRole
import ch.trancee.meshlink.reference.automation.ReferenceAutomationScenario
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
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = readinessGuidance(),
        options =
            DefaultPlatformServicesOptions().apply {
                this.nowProvider = nowProvider
                documentStore = OkioReferenceDocumentStore(documentsDirectory, fileSystem)
            },
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
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
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
                documentStore = OkioReferenceDocumentStore(baseDirectory, FileSystem.SYSTEM)
                automationConfig =
                    ReferenceAutomationConfig(
                        mode = ReferenceAutomationMode.SCRIPTED_UI,
                        role = ReferenceAutomationRole.PASSIVE,
                        appId = "demo.meshlink.reference.automation",
                        storageSubdirectory = storageSubdirectory,
                    )
                automationLogger = ::println
                meshLinkControllerFactory = { surfaceOfOrigin ->
                    ScriptedReferenceMeshLinkController(
                        platformName = "iOS",
                        authorityMode = ReferenceAuthorityMode.LIVE,
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
    role: ReferenceAutomationRole,
    requiredPeerCount: Int = 1,
    targetPeerIndex: Int = 0,
    targetPeerId: String? = null,
    scenario: ReferenceAutomationScenario = ReferenceAutomationScenario.DIRECT_GUIDED,
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
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = readinessGuidance(),
        options =
            DefaultPlatformServicesOptions().apply {
                nowProvider = clock
                this.appId = automationAppId
                documentStore = OkioReferenceDocumentStore(baseDirectory, FileSystem.SYSTEM)
                automationConfig =
                    ReferenceAutomationConfig(
                        mode = ReferenceAutomationMode.LIVE_PROOF,
                        role = role,
                        appId = automationAppId,
                        storageSubdirectory = storageSubdirectory,
                        requiredPeerCount = requiredPeerCount,
                        targetPeerIndex = targetPeerIndex,
                        targetPeerId = targetPeerId,
                        scenario = scenario,
                    )
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
