package ch.trancee.meshlink.reference.platform

import android.content.Context
import android.util.Log
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfig
import ch.trancee.meshlink.reference.automation.ReferenceAutomationMode
import ch.trancee.meshlink.reference.automation.ReferenceAutomationRole
import ch.trancee.meshlink.reference.meshlink.ScriptedReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.session.OkioReferenceDocumentStore
import okio.FileSystem

internal fun createAndroidPlatformServices(context: Context): DefaultPlatformServices {
    return DefaultPlatformServices(
        platformName = "Android",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = androidReadinessGuidance(),
        readinessBlockers = androidReadinessBlockers(context),
        nowProvider = { System.currentTimeMillis() },
        platformContext = context,
        documentStore = OkioReferenceDocumentStore(context.filesDir.absolutePath, FileSystem.SYSTEM),
    )
}

internal fun createAndroidAutomationPlatformServices(
    context: Context,
    storageSubdirectory: String,
    blocked: Boolean,
): DefaultPlatformServices {
    val nowProvider = { System.currentTimeMillis() }
    val automationDirectory = "${context.filesDir.absolutePath}/ui-automation/$storageSubdirectory"
    return DefaultPlatformServices(
        platformName = "Android",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = androidReadinessGuidance(),
        readinessBlockers =
            if (blocked) {
                listOf(
                    "Enable Bluetooth on Android before starting the guided exchange.",
                    "Grant the required nearby-device permissions for MeshLink.",
                )
            } else {
                emptyList()
            },
        nowProvider = nowProvider,
        documentStore = OkioReferenceDocumentStore(automationDirectory, FileSystem.SYSTEM),
        automationConfig =
            ReferenceAutomationConfig(
                mode = ReferenceAutomationMode.SCRIPTED_UI,
                role = ReferenceAutomationRole.PASSIVE,
                appId = "demo.meshlink.reference.automation",
                storageSubdirectory = storageSubdirectory,
            ),
        automationLogger = { message -> Log.i(AUTOMATION_LOG_TAG, message) },
        meshLinkControllerOverride =
            ScriptedReferenceMeshLinkController(
                platformName = "Android",
                authorityMode = ReferenceAuthorityMode.LIVE,
                nowProvider = nowProvider,
            ),
    )
}

internal fun createAndroidLiveAutomationPlatformServices(
    context: Context,
    storageSubdirectory: String,
    appId: String,
    role: ReferenceAutomationRole,
): DefaultPlatformServices {
    val nowProvider = { System.currentTimeMillis() }
    val automationDirectory =
        "${context.filesDir.absolutePath}/live-automation/$storageSubdirectory"
    return DefaultPlatformServices(
        platformName = "Android",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = androidReadinessGuidance(),
        readinessBlockers = androidReadinessBlockers(context),
        nowProvider = nowProvider,
        appId = appId,
        platformContext = context,
        documentStore = OkioReferenceDocumentStore(automationDirectory, FileSystem.SYSTEM),
        automationConfig =
            ReferenceAutomationConfig(
                mode = ReferenceAutomationMode.LIVE_PROOF,
                role = role,
                appId = appId,
                storageSubdirectory = storageSubdirectory,
            ),
        automationLogger = { message -> Log.i(AUTOMATION_LOG_TAG, message) },
    )
}

private const val AUTOMATION_LOG_TAG: String = "MeshLinkReferenceAutomation"
