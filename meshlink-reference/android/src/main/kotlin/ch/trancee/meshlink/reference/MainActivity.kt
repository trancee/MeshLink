@file:Suppress("LongMethod", "TooManyFunctions", "MaxLineLength", "TooGenericExceptionCaught")

package ch.trancee.meshlink.reference

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ch.trancee.meshlink.reference.app.ReferenceApp
import ch.trancee.meshlink.platform.android.AndroidDiscoveryAdvertisementConfig
import ch.trancee.meshlink.platform.android.DiscoveryAdvertisementCarrier
import ch.trancee.meshlink.reference.automation.ReferenceAutomationRole
import ch.trancee.meshlink.reference.automation.ReferenceStartupCoordinator
import ch.trancee.meshlink.reference.automation.toReferenceAutomationScenario
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.platform.createAutomationPlatformServices
import ch.trancee.meshlink.reference.platform.createLiveAutomationPlatformServices
import ch.trancee.meshlink.reference.platform.createPlatformServices
import kotlinx.coroutines.launch

/** Android entry point for the shared reference app shell. */
public class MainActivity : ComponentActivity() {
    private var activePlatformServices: PlatformServices? = null
    private var directProofEnabled: Boolean = false
    private var startupCoordinator: ReferenceStartupCoordinator? = null

    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        logActivityStage("onCreate", directProofEnabled)
        val automationConfig = readAutomationConfig()
        directProofEnabled = automationConfig.mode == AUTOMATION_MODE_LIVE_PROOF
        configureDiscoveryCarrier(automationConfig.advertisementCarrier, directProofEnabled)
        if (directProofEnabled) {
            startDirectProofPowerService()
            keepScreenOn()
        }
        val platformServices = createPlatformServicesForAutomation(automationConfig)
        activePlatformServices = platformServices
        startupCoordinator =
            if (directProofEnabled) {
                ReferenceStartupCoordinator(platformServices = platformServices, scope = lifecycleScope)
            } else {
                null
            }
        if (directProofEnabled) {
            emitStartupMarker(platformServices)
            emitDirectProofPowerState(platformServices, "onCreate", directProofEnabled)
            Log.i(
                "MeshLinkReferenceAutomation",
                "REFERENCE_AUTOMATION directProof.transport role=${automationConfig.role} benchmarkTransport=${automationConfig.benchmarkTransport}",
            )
            writeDirectProofProbeMarker(
                "onCreate.txt",
                "role=${automationConfig.role} benchmarkTransport=${automationConfig.benchmarkTransport} directProofEnabled=$directProofEnabled\n",
            )
        }
        setContent { ReferenceApp(platformServices = platformServices) }
    }

    override fun onResume() {
        super.onResume()
        if (directProofEnabled) {
            logActivityStage("onResume", directProofEnabled)
            emitDirectProofPowerState(activePlatformServices, "onResume", directProofEnabled)
        }
    }

    override fun onPause() {
        if (directProofEnabled) {
            logActivityStage("onPause", directProofEnabled)
            emitDirectProofPowerState(activePlatformServices, "onPause", directProofEnabled)
        }
        super.onPause()
    }

    override fun onStop() {
        if (directProofEnabled) {
            logActivityStage("onStop", directProofEnabled)
            emitDirectProofPowerState(activePlatformServices, "onStop", directProofEnabled)
        }
        if (!isChangingConfigurations && !directProofEnabled) {
            stopDirectProofPowerService()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (directProofEnabled) {
            logActivityStage("onDestroy", directProofEnabled)
            emitDirectProofPowerState(activePlatformServices, "onDestroy", directProofEnabled)
        }
        releaseScreenOn()
        stopDirectProofPowerService()
        super.onDestroy()
    }

    public companion object {
        public const val EXTRA_UI_AUTOMATION: String =
            "ch.trancee.meshlink.reference.extra.UI_AUTOMATION"
        public const val EXTRA_UI_AUTOMATION_BENCHMARK_TRANSPORT: String =
            "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_BENCHMARK_TRANSPORT"
        public const val EXTRA_UI_AUTOMATION_STORAGE_SUBDIRECTORY: String =
            "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_STORAGE_SUBDIRECTORY"
        public const val EXTRA_UI_AUTOMATION_BLOCKED: String =
            "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_BLOCKED"
        public const val EXTRA_UI_AUTOMATION_MODE: String =
            "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_MODE"
        public const val EXTRA_UI_AUTOMATION_APP_ID: String =
            "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_APP_ID"
        public const val EXTRA_UI_AUTOMATION_ROLE: String =
            "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_ROLE"
        public const val EXTRA_UI_AUTOMATION_REQUIRED_PEER_COUNT: String =
            "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_REQUIRED_PEER_COUNT"
        public const val EXTRA_UI_AUTOMATION_TARGET_PEER_INDEX: String =
            "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_TARGET_PEER_INDEX"
        public const val EXTRA_UI_AUTOMATION_TARGET_PEER_ID: String =
            "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_TARGET_PEER_ID"
        public const val EXTRA_UI_AUTOMATION_SCENARIO: String =
            "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_SCENARIO"
        public const val EXTRA_UI_AUTOMATION_ADVERTISEMENT_CARRIER: String =
            "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_ADVERTISEMENT_CARRIER"
        public const val DEFAULT_AUTOMATION_STORAGE_SUBDIRECTORY: String = "default"
        public const val DEFAULT_LIVE_AUTOMATION_APP_ID: String = "demo.meshlink.reference.live"
        public const val AUTOMATION_MODE_SCRIPTED: String = "scripted"
        public const val AUTOMATION_MODE_LIVE_PROOF: String = "live-proof"
    }

}

private fun MainActivity.writeDirectProofProbeMarker(name: String, content: String) {
    runCatching {
        val dir = File(applicationContext.filesDir, "direct-proof-probe")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        File(dir, name).writeText(content)
    }.onFailure { throwable ->
        Log.e(
            "MeshLinkReferenceAutomation",
            "REFERENCE_AUTOMATION probe.write.failed name=$name",
            throwable,
        )
    }
}

private fun String?.toReferenceAutomationRole(): ReferenceAutomationRole {
    return when {
        this.equals("sender", ignoreCase = true) -> ReferenceAutomationRole.SENDER
        this.equals("relay", ignoreCase = true) -> ReferenceAutomationRole.RELAY
        else -> ReferenceAutomationRole.PASSIVE
    }
}

private fun MainActivity.readAutomationConfig(): AutomationConfig {
    val automationEnabled =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra(MainActivity.EXTRA_UI_AUTOMATION, false) == true
    val automationMode = intent?.getStringExtra(MainActivity.EXTRA_UI_AUTOMATION_MODE)
    val mode = if (automationEnabled) automationMode else null
    val benchmarkTransport =
        intent?.getStringExtra(MainActivity.EXTRA_UI_AUTOMATION_BENCHMARK_TRANSPORT)
            ?: "meshlink"
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION readAutomationConfig enabled=$automationEnabled mode=$mode role=${intent?.getStringExtra(MainActivity.EXTRA_UI_AUTOMATION_ROLE)} benchmarkTransport=$benchmarkTransport",
    )
    return AutomationConfig(
        enabled = automationEnabled,
        mode = mode,
        storageSubdirectory =
            intent?.getStringExtra(MainActivity.EXTRA_UI_AUTOMATION_STORAGE_SUBDIRECTORY)
                ?: MainActivity.DEFAULT_AUTOMATION_STORAGE_SUBDIRECTORY,
        appId =
            intent?.getStringExtra(MainActivity.EXTRA_UI_AUTOMATION_APP_ID)
                ?: MainActivity.DEFAULT_LIVE_AUTOMATION_APP_ID,
        role = intent?.getStringExtra(MainActivity.EXTRA_UI_AUTOMATION_ROLE).toReferenceAutomationRole(),
        requiredPeerCount = intent?.getIntExtra(MainActivity.EXTRA_UI_AUTOMATION_REQUIRED_PEER_COUNT, 1) ?: 1,
        targetPeerIndex = intent?.getIntExtra(MainActivity.EXTRA_UI_AUTOMATION_TARGET_PEER_INDEX, 0) ?: 0,
        targetPeerId = intent?.getStringExtra(MainActivity.EXTRA_UI_AUTOMATION_TARGET_PEER_ID),
        benchmarkTransport = benchmarkTransport,
        scenario =
            intent?.getStringExtra(MainActivity.EXTRA_UI_AUTOMATION_SCENARIO)
                .toReferenceAutomationScenario(),
        blocked = intent?.getBooleanExtra(MainActivity.EXTRA_UI_AUTOMATION_BLOCKED, false) == true,
        advertisementCarrier =
            intent?.getStringExtra(MainActivity.EXTRA_UI_AUTOMATION_ADVERTISEMENT_CARRIER)
                .toDiscoveryAdvertisementCarrier(),
    )
}

