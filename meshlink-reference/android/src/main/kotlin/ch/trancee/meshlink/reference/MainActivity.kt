@file:Suppress("TooGenericExceptionCaught")

package ch.trancee.meshlink.reference

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ch.trancee.meshlink.reference.app.ReferenceApp

/** Android entry point for the shared reference app harness. */
public class MainActivity : ComponentActivity() {
    private var activePlatformServices: AndroidPlatformServices? = null

    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        logActivityStage("onCreate")

        val platformServices = createPlatformServices(applicationContext)
        activePlatformServices = platformServices
        logActivityStage("beforeSetContent")
        val meshLinkController = platformServices.meshLinkController
        logActivityStage("afterMeshLinkControllerAccess")
        logActivityStage("setContentBegin")

        setContent {
            logActivityStage("insideSetContent")
            ReferenceApp(
                platformName = platformServices.platformName,
                readinessGuidance = platformServices.readinessGuidance,
                readinessBlockers = platformServices.readinessBlockers,
                powerMitigationStatus = platformServices.powerMitigationStatus,
                documentStore = platformServices.documentStore,
                meshLinkController = meshLinkController,
                stopPowerMitigation = { platformServices.stopPowerMitigation() },
                currentTimeMillis = { platformServices.currentTimeMillis() },
            )
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
}
