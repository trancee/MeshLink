package ch.trancee.meshlink.reference.platform

import android.content.Context
import ch.trancee.meshlink.reference.meshlink.ScriptedReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.session.OkioReferenceDocumentStore

internal fun createAndroidPlatformServices(context: Context): DefaultPlatformServices {
    return DefaultPlatformServices(
        platformName = "Android",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = androidReadinessGuidance(),
        nowProvider = { System.currentTimeMillis() },
        platformContext = context,
        documentStore = OkioReferenceDocumentStore(context.filesDir.absolutePath),
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
        documentStore = OkioReferenceDocumentStore(automationDirectory),
        meshLinkControllerOverride =
            ScriptedReferenceMeshLinkController(
                platformName = "Android",
                authorityMode = ReferenceAuthorityMode.LIVE,
                nowProvider = nowProvider,
            ),
    )
}
