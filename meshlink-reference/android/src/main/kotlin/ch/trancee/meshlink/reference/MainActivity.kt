@file:Suppress("TooGenericExceptionCaught")

package ch.trancee.meshlink.reference

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import ch.trancee.meshlink.reference.app.ReferenceApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

private const val EXTRA_UI_AUTOMATION = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION"
private const val EXTRA_MODE = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_MODE"
private const val EXTRA_STORAGE = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_STORAGE_SUBDIRECTORY"
private const val EXTRA_APP_ID = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_APP_ID"
private const val EXTRA_ROLE = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_ROLE"
private const val EXTRA_SCENARIO = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_SCENARIO"
private const val EXTRA_TARGET_PEER_ID = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_TARGET_PEER_ID"
private const val EXTRA_DISABLE_AUTO_SEND = "meshlink.disableAutoSend"

private const val AUTOMATION_ENABLED_PREF_KEY = "automation:enabled"
private const val AUTOMATION_TARGET_PEER_ID_PREF_KEY = "automation:targetPeerId"

private fun logActivityStage(stage: String): Unit {
    Log.i("MeshLinkReferenceAutomation", "REFERENCE_AUTOMATION activity.stage=$stage")
}

private fun updateAutomationPreferences(
    context: Context,
    appId: String,
    extras: Bundle?,
): Unit {
    val isAutomation = extras?.getBoolean(EXTRA_UI_AUTOMATION, false) == true
    val targetPeerId = extras?.getString(EXTRA_TARGET_PEER_ID)?.trim()?.takeIf { it.isNotEmpty() }
    context.getSharedPreferences("meshlink-$appId", Context.MODE_PRIVATE)
        .edit()
        .apply {
            if (isAutomation) {
                putBoolean(AUTOMATION_ENABLED_PREF_KEY, true)
            } else {
                remove(AUTOMATION_ENABLED_PREF_KEY)
            }
            if (isAutomation && targetPeerId != null) {
                putString(AUTOMATION_TARGET_PEER_ID_PREF_KEY, targetPeerId)
            } else {
                remove(AUTOMATION_TARGET_PEER_ID_PREF_KEY)
            }
            apply()
        }
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION prefs enabled=$isAutomation targetPeerId=${targetPeerId ?: "none"} appId=$appId",
    )
}

private fun logAutomationStartupStage(extras: Bundle?): Unit {
    if (extras?.getBoolean(EXTRA_UI_AUTOMATION, false) != true) {
        return
    }
    val mode = (extras.getString(EXTRA_MODE) ?: "unknown").uppercase().replace('-', '_')
    val role = (extras.getString(EXTRA_ROLE) ?: "unknown").uppercase().replace('-', '_')
    val scenario = extras.getString(EXTRA_SCENARIO) ?: "unknown"
    val appId = extras.getString(EXTRA_APP_ID) ?: "unknown"
    val storage = extras.getString(EXTRA_STORAGE) ?: "unknown"
    val targetPeerId = extras.getString(EXTRA_TARGET_PEER_ID)
    val autoStartMesh = role == "SENDER" || role == "PASSIVE" || role == "RELAY"
    val autoSendHello = role == "SENDER"
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
            append(" targetPeerId=")
            append(targetPeerId ?: "none")
            append(" autoStartMesh=")
            append(autoStartMesh)
            append(" autoSendHello=")
            append(autoSendHello)
        },
    )
    Log.i(
        "MeshLinkReferenceAutomation",
        buildString {
            append("REFERENCE_AUTOMATION startup-state=activity.onCreate role=")
            append(role)
            append(" scenario=")
            append(scenario)
            append(" autoStartMesh=")
            append(autoStartMesh)
            append(" autoSendHello=")
            append(autoSendHello)
        },
    )
}

/** Android entry point for the shared reference app harness. */
public class MainActivity : ComponentActivity() {
    private val automationProbeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activePlatformServices: AndroidPlatformServices? = null

    @Suppress("LongMethod")
    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        logActivityStage("onCreate")
        initializeForIntent(intent)
    }

    /**
     * Re-initializes the activity for a new intent instead of relying on Android to spin up a
     * second Activity instance. MainActivity is `launchMode="singleTask"` specifically so that
     * the direct-proof test harnesses' practice of re-launching the same role (e.g.
     * `run_headless_reference_android_direct_proof.py` re-launching the passive/sender apps once
     * the target peer id is known) delivers here instead of creating a duplicate instance. Without
     * this, a second Activity/ViewModel/MeshLink instance under the same appId (and therefore the
     * same deterministic local peer identity) would end up running concurrently with the first,
     * racing it for the same BLE peer connection -- observed on hardware as a spurious
     * "transport.handshake.message2.unexpected"/"transport.handshake.timeout pendingHandshake"
     * pair alongside the real, successful handshake.
     */
    override fun onNewIntent(intent: Intent): Unit {
        super.onNewIntent(intent)
        setIntent(intent)
        logActivityStage("onNewIntent")
        initializeForIntent(intent)
    }

    @Suppress("LongMethod")
    private fun initializeForIntent(intent: Intent?): Unit {
        val extras = intent?.extras
        val automationAppId = extras?.getString(EXTRA_APP_ID) ?: "unknown"
        val targetPeerId = extras?.getString(EXTRA_TARGET_PEER_ID)
        val storageSubdirectory = extras?.getString(EXTRA_STORAGE) ?: "default"
        logAutomationStartupStage(extras)
        updateAutomationPreferences(applicationContext, automationAppId, extras)

        // Stop the previous instance's power mitigation eagerly; its MeshLink session and
        // ViewModel coroutine scope are torn down by GuidedFirstExchangeViewModel.close(), which
        // ReferenceApp's DisposableEffect triggers once setContent below replaces the composition.
        activePlatformServices?.stopPowerMitigation()

        val platformServices =
            createPlatformServices(
                applicationContext,
                automationAppId,
                targetPeerId,
                storageSubdirectory,
            )
        launchRetainedDiscoverySeedProbe(
            context = applicationContext,
            appId = automationAppId,
            emitAutomationLog = platformServices::emitAutomationLog,
            scope = automationProbeScope,
        )
        activePlatformServices = platformServices
        logActivityStage("beforeSetContent")
        logActivityStage("beforeReadinessBlockers")
        val readinessBlockers = platformServices.readinessBlockers
        logActivityStage("afterReadinessBlockers")
        logActivityStage("setContentBegin")

        val automationMode = (extras?.getString(EXTRA_MODE) ?: "unknown").uppercase().replace('-', '_')
        val automationRole = (extras?.getString(EXTRA_ROLE) ?: "unknown").uppercase().replace('-', '_')
        val automationScenario = extras?.getString(EXTRA_SCENARIO) ?: "unknown"
        val automationTargetPeerId = extras?.getString(EXTRA_TARGET_PEER_ID)
        val autoStartMesh = automationRole == "SENDER" || automationRole == "PASSIVE" || automationRole == "RELAY"
        val disableAutoSend = extras?.getBoolean(EXTRA_DISABLE_AUTO_SEND, false) == true
        val autoSendHello = automationRole == "SENDER" && !disableAutoSend

        setContent {
            logActivityStage("insideSetContent")
            platformServices.emitAutomationLog("REFERENCE_AUTOMATION app.compose.begin")
            val meshLinkController = androidx.compose.runtime.remember {
                mutableStateOf<ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController?>(null)
            }
            androidx.compose.runtime.LaunchedEffect(automationAppId) {
                logActivityStage("meshLinkControllerLoadBegin")
                meshLinkController.value =
                    withContext(Dispatchers.Default) { platformServices.meshLinkController }
                logActivityStage("meshLinkControllerLoadEnd")
            }
            if (meshLinkController.value == null) {
                platformServices.emitAutomationLog("REFERENCE_AUTOMATION app.compose.waiting controller")
                logActivityStage("controllerLoading")
            } else {
                val controller = requireNotNull(meshLinkController.value)
                logActivityStage("afterMeshLinkControllerAccess")
                ReferenceApp(
                    platformName = platformServices.platformName,
                    readinessGuidance = platformServices.readinessGuidance,
                    readinessBlockers = readinessBlockers,
                    powerMitigationStatus = platformServices.powerMitigationStatus,
                    documentStore = platformServices.documentStore,
                    meshLinkController = controller,
                    stopPowerMitigation = { platformServices.stopPowerMitigation() },
                    currentTimeMillis = { platformServices.currentTimeMillis() },
                    automationMode = automationMode,
                    automationRole = automationRole,
                    automationScenario = automationScenario,
                    automationTargetPeerId = automationTargetPeerId,
                    autoStartMesh = autoStartMesh,
                    autoSendHello = autoSendHello,
                    emitAutomationLog = { message -> platformServices.emitAutomationLog(message) },
                    diagnosticMinimalUi = false,
                )
                platformServices.emitAutomationLog("REFERENCE_AUTOMATION app.compose.end")
            }
        }
    }

    override fun onDestroy() {
        logActivityStage("onDestroy")
        activePlatformServices?.stopPowerMitigation()
        activePlatformServices = null
        automationProbeScope.cancel()
        super.onDestroy()
    }

    private fun logActivityStage(stage: String): Unit {
        Log.i("MeshLinkReferenceAutomation", "REFERENCE_AUTOMATION activity.stage=$stage")
    }

    private fun logAutomationStartupStage(extras: Bundle?): Unit {
        if (extras?.getBoolean(EXTRA_UI_AUTOMATION, false) != true) {
            return
        }
        val mode = (extras.getString(EXTRA_MODE) ?: "unknown").uppercase().replace('-', '_')
        val role = (extras.getString(EXTRA_ROLE) ?: "unknown").uppercase().replace('-', '_')
        val scenario = extras.getString(EXTRA_SCENARIO) ?: "unknown"
        val appId = extras.getString(EXTRA_APP_ID) ?: "unknown"
        val storage = extras.getString(EXTRA_STORAGE) ?: "unknown"
        val targetPeerId = extras.getString(EXTRA_TARGET_PEER_ID)
        val autoStartMesh = role == "SENDER" || role == "PASSIVE" || role == "RELAY"
        val disableAutoSend = extras.getBoolean(EXTRA_DISABLE_AUTO_SEND, false)
        val autoSendHello = role == "SENDER" && !disableAutoSend
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
                append(" targetPeerId=")
                append(targetPeerId ?: "none")
                append(" autoStartMesh=")
                append(autoStartMesh)
                append(" autoSendHello=")
                append(autoSendHello)
            },
        )
        Log.i(
            "MeshLinkReferenceAutomation",
            buildString {
                append("REFERENCE_AUTOMATION startup-state=activity.onCreate role=")
                append(role)
                append(" scenario=")
                append(scenario)
                append(" autoStartMesh=")
                append(autoStartMesh)
                append(" autoSendHello=")
                append(autoSendHello)
            },
        )
    }
}
