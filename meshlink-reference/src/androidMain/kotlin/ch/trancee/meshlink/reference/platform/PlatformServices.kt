@file:JvmName("PlatformServicesAndroidBindings")

package ch.trancee.meshlink.reference.platform

import android.content.Context
import android.util.Log
import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.api.android.meshLinkBootstrap as androidMeshLinkBootstrap
import ch.trancee.meshlink.reference.meshlink.ScriptedReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.REFERENCE_AUTHORITY_MODE_LIVE
import ch.trancee.meshlink.reference.session.OkioReferenceDocumentStore
import okio.FileSystem

public fun createPlatformServices(context: Context): PlatformServices {
    return DefaultPlatformServices(
        platformName = "Android",
        defaultAuthorityMode = REFERENCE_AUTHORITY_MODE_LIVE,
        readinessGuidance = readinessGuidance(),
        options =
            DefaultPlatformServicesOptions().apply {
                readinessBlockers = readinessBlockers(context)
                nowProvider = { System.currentTimeMillis() }
                meshLinkBootstrap = createAndroidBootstrap(context)
                documentStore =
                    OkioReferenceDocumentStore(context.filesDir.absolutePath, FileSystem.SYSTEM)
            },
    )
}

public fun createAutomationPlatformServices(
    context: Context,
    storageSubdirectory: String,
    blocked: Boolean,
): PlatformServices {
    val clock = { System.currentTimeMillis() }
    val safeStorageSubdirectory =
        normalizeAutomationStorageSubdirectory(storageSubdirectory, "default")
    val automationDirectory =
        "${context.filesDir.absolutePath}/ui-automation/" + safeStorageSubdirectory
    return DefaultPlatformServices(
        platformName = "Android",
        defaultAuthorityMode = REFERENCE_AUTHORITY_MODE_LIVE,
        readinessGuidance = readinessGuidance(),
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
                automationLogger = { message -> Log.i(AUTOMATION_LOG_TAG, message) }
                meshLinkControllerFactory = { surfaceOfOrigin ->
                    ScriptedReferenceMeshLinkController(
                        platformName = "Android",
                        authorityMode = REFERENCE_AUTHORITY_MODE_LIVE,
                        nowProvider = clock,
                        surfaceOfOrigin = surfaceOfOrigin,
                    )
                }
            },
    )
}

private fun createAndroidBootstrap(context: Context): MeshLinkBootstrap {
    Log.i(AUTOMATION_LOG_TAG, "REFERENCE_AUTOMATION android.bootstrap.applicationContext.begin")
    val appContext = context.applicationContext
    Log.i(AUTOMATION_LOG_TAG, "REFERENCE_AUTOMATION android.bootstrap.applicationContext.end")
    Log.i(AUTOMATION_LOG_TAG, "REFERENCE_AUTOMATION android.bootstrap.object.begin")
    val bootstrap = androidMeshLinkBootstrap(appContext)
    Log.i(AUTOMATION_LOG_TAG, "REFERENCE_AUTOMATION android.bootstrap.object.end")
    return bootstrap
}

private const val AUTOMATION_LOG_TAG: String = "MeshLinkReferenceAutomation"
