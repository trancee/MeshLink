@file:Suppress("TooGenericExceptionCaught")

package ch.trancee.meshlink.reference

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

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
        logActivityStage("beforeReadinessBlockers")
        val readinessBlockers = platformServices.readinessBlockers
        logActivityStage("afterReadinessBlockers")
        logActivityStage("setContentBegin")

        setContent {
            logActivityStage("insideSetContent")
            logActivityStage("placeholderBeforeEmit")
            platformServices.emitAutomationLog("REFERENCE_AUTOMATION activity.placeholder.rendered")
            logActivityStage("placeholderAfterEmit")
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
