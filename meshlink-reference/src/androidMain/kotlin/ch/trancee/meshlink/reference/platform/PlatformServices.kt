@file:JvmName("PlatformServicesAndroidBindings")

package ch.trancee.meshlink.reference.platform

import android.content.Context
import android.content.Intent
import android.util.Log
import ch.trancee.meshlink.api.android.meshLinkBootstrap
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
                meshLinkBootstrap = meshLinkBootstrap(context)
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

public fun createLiveAutomationPlatformServices(
    context: Context,
    storageSubdirectory: String,
    appId: String,
    role: String,
    requiredPeerCount: Int = 1,
    targetPeerIndex: Int = 0,
    targetPeerId: String? = null,
    scenario: String = "direct-guided",
): PlatformServices {
    val clock = { System.currentTimeMillis() }
    val safeStorageSubdirectory =
        normalizeAutomationStorageSubdirectory(storageSubdirectory, "default")
    val automationDirectory =
        "${context.filesDir.absolutePath}/live-automation/" + safeStorageSubdirectory
    val automationAppId = appId
    return DefaultPlatformServices(
        platformName = "Android",
        defaultAuthorityMode = REFERENCE_AUTHORITY_MODE_LIVE,
        readinessGuidance = readinessGuidance(),
        options =
            DefaultPlatformServicesOptions().apply {
                readinessBlockers = readinessBlockers(context)
                nowProvider = clock
                this.appId = automationAppId
                meshLinkBootstrap = meshLinkBootstrap(context)
                automationTargetPeerId = targetPeerId
                documentStore = OkioReferenceDocumentStore(automationDirectory, FileSystem.SYSTEM)
                powerMitigationStatus =
                    "Foreground wake lock active for live-proof automation sessions."
                automationLogger = { message -> Log.i(AUTOMATION_LOG_TAG, message) }
                stopPowerMitigation = {
                    context.stopService(
                        Intent()
                            .setClassName(
                                context.packageName,
                                "ch.trancee.meshlink.reference.DirectProofPowerService",
                            )
                    )
                }
            },
    )
}

private const val AUTOMATION_LOG_TAG: String = "MeshLinkReferenceAutomation"
