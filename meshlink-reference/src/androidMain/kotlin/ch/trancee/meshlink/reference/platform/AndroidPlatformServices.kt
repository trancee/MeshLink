package ch.trancee.meshlink.reference.platform

import android.content.Context
import android.util.Log
import ch.trancee.meshlink.api.androidMeshLinkBootstrap
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfig
import ch.trancee.meshlink.reference.automation.ReferenceAutomationMode
import ch.trancee.meshlink.reference.automation.ReferenceAutomationRole
import ch.trancee.meshlink.reference.automation.ReferenceAutomationScenario
import ch.trancee.meshlink.reference.meshlink.ScriptedReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.session.OkioReferenceDocumentStore
import okio.FileSystem

public fun createAndroidPlatformServices(context: Context): PlatformServices {
    return DefaultPlatformServices(
        platformName = "Android",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = androidReadinessGuidance(),
        options =
            DefaultPlatformServicesOptions().apply {
                readinessBlockers = androidReadinessBlockers(context)
                nowProvider = { System.currentTimeMillis() }
                meshLinkBootstrap = androidMeshLinkBootstrap(context)
                documentStore =
                    OkioReferenceDocumentStore(context.filesDir.absolutePath, FileSystem.SYSTEM)
            },
    )
}

public fun createAndroidAutomationPlatformServices(
    context: Context,
    storageSubdirectory: String,
    blocked: Boolean,
): PlatformServices {
    val clock = { System.currentTimeMillis() }
    val automationDirectory = "${context.filesDir.absolutePath}/ui-automation/$storageSubdirectory"
    return DefaultPlatformServices(
        platformName = "Android",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = androidReadinessGuidance(),
        options =
            DefaultPlatformServicesOptions().apply {
                readinessBlockers =
                    if (blocked) {
                        listOf(
                            "Enable Bluetooth on Android before starting the guided exchange.",
                            "Grant the required nearby-device permissions for MeshLink.",
                        )
                    } else {
                        emptyList()
                    }
                nowProvider = clock
                documentStore = OkioReferenceDocumentStore(automationDirectory, FileSystem.SYSTEM)
                automationConfig =
                    ReferenceAutomationConfig(
                        mode = ReferenceAutomationMode.SCRIPTED_UI,
                        role = ReferenceAutomationRole.PASSIVE,
                        appId = "demo.meshlink.reference.automation",
                        storageSubdirectory = storageSubdirectory,
                    )
                automationLogger = { message -> Log.i(AUTOMATION_LOG_TAG, message) }
                meshLinkControllerFactory = { surfaceOfOrigin ->
                    ScriptedReferenceMeshLinkController(
                        platformName = "Android",
                        authorityMode = ReferenceAuthorityMode.LIVE,
                        nowProvider = clock,
                        surfaceOfOrigin = surfaceOfOrigin,
                    )
                }
            },
    )
}

public fun createAndroidLiveAutomationPlatformServices(
    context: Context,
    storageSubdirectory: String,
    appId: String,
    role: ReferenceAutomationRole,
    requiredPeerCount: Int = 1,
    targetPeerIndex: Int = 0,
    targetPeerId: String? = null,
    scenario: ReferenceAutomationScenario = ReferenceAutomationScenario.DIRECT_GUIDED,
): PlatformServices {
    val clock = { System.currentTimeMillis() }
    val automationDirectory =
        "${context.filesDir.absolutePath}/live-automation/$storageSubdirectory"
    val automationAppId = appId
    return DefaultPlatformServices(
        platformName = "Android",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = androidReadinessGuidance(),
        options =
            DefaultPlatformServicesOptions().apply {
                readinessBlockers = androidReadinessBlockers(context)
                nowProvider = clock
                this.appId = automationAppId
                meshLinkBootstrap = androidMeshLinkBootstrap(context)
                documentStore = OkioReferenceDocumentStore(automationDirectory, FileSystem.SYSTEM)
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
                automationLogger = { message -> Log.i(AUTOMATION_LOG_TAG, message) }
            },
    )
}

private const val AUTOMATION_LOG_TAG: String = "MeshLinkReferenceAutomation"
