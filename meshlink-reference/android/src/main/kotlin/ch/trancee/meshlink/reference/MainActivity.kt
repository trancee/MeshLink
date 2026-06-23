@file:Suppress("TooGenericExceptionCaught")

package ch.trancee.meshlink.reference

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ch.trancee.meshlink.reference.app.ReferenceApp

private const val EXTRA_UI_AUTOMATION = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION"
private const val EXTRA_MODE = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_MODE"
private const val EXTRA_STORAGE = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_STORAGE_SUBDIRECTORY"
private const val EXTRA_APP_ID = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_APP_ID"
private const val EXTRA_ROLE = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_ROLE"
private const val EXTRA_SCENARIO = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_SCENARIO"

/** Android entry point for the shared reference app harness. */
public class MainActivity : ComponentActivity() {
    private var activePlatformServices: AndroidPlatformServices? = null

    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        logActivityStage("onCreate")
        logAutomationStartupStage()

        val platformServices = createPlatformServices(applicationContext)
        activePlatformServices = platformServices
        logActivityStage("beforeSetContent")
        val meshLinkController = platformServices.meshLinkController
        logActivityStage("afterMeshLinkControllerAccess")
        logActivityStage("beforeReadinessBlockers")
        val readinessBlockers = platformServices.readinessBlockers
        logActivityStage("afterReadinessBlockers")
        logActivityStage("setContentBegin")

        setContent {
            logActivityStage("insideSetContent")
            platformServices.emitAutomationLog("REFERENCE_AUTOMATION app.compose.begin")
            ReferenceApp(
                platformName = platformServices.platformName,
                readinessGuidance = platformServices.readinessGuidance,
                readinessBlockers = readinessBlockers,
                powerMitigationStatus = platformServices.powerMitigationStatus,
                documentStore = platformServices.documentStore,
                meshLinkController = meshLinkController,
                stopPowerMitigation = { platformServices.stopPowerMitigation() },
                currentTimeMillis = { platformServices.currentTimeMillis() },
                emitAutomationLog = { message -> platformServices.emitAutomationLog(message) },
            )
            platformServices.emitAutomationLog("REFERENCE_AUTOMATION app.compose.end")
        }
    }

    override fun onDestroy() {
        logActivityStage("onDestroy")
        activePlatformServices?.stopPowerMitigation()
        activePlatformServices = null
        super.onDestroy()
    }

    private fun logActivityStage(stage: String): Unit {
        Log.i("MeshLinkReferenceAutomation", "REFERENCE_AUTOMATION activity.stage=$stage")
    }

    private fun logAutomationStartupStage(): Unit {
        val extras = intent?.extras
        if (extras?.getBoolean(EXTRA_UI_AUTOMATION, false) != true) {
            return
        }
        val mode = (extras.getString(EXTRA_MODE) ?: "unknown").uppercase().replace('-', '_')
        val role = (extras.getString(EXTRA_ROLE) ?: "unknown").uppercase().replace('-', '_')
        val scenario = extras.getString(EXTRA_SCENARIO) ?: "unknown"
        val appId = extras.getString(EXTRA_APP_ID) ?: "unknown"
        val storage = extras.getString(EXTRA_STORAGE) ?: "unknown"
        Log.i(
            "MeshLinkReferenceAutomation",
            buildString {
                append("REFERENCE_AUTOMATION startup stage=activity.onCreate mode=")
                append(mode)
                append(" role=")
                append(role)
                append(" scenario=")
                append(scenario)
                append(" appId=")
                append(appId)
                append(" storage=")
                append(storage)
            },
        )
    }
}
