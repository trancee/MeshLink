package ch.trancee.meshlink.reference

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ch.trancee.meshlink.reference.app.ReferenceApp
import ch.trancee.meshlink.reference.automation.ReferenceAutomationRole
import ch.trancee.meshlink.platform.android.AndroidDiscoveryAdvertisementConfig
import ch.trancee.meshlink.platform.android.DiscoveryAdvertisementCarrier
import ch.trancee.meshlink.reference.automation.ReferenceAutomationScenario
import ch.trancee.meshlink.reference.automation.ReferenceStartupCoordinator
import ch.trancee.meshlink.reference.automation.startupMarker
import ch.trancee.meshlink.reference.automation.toReferenceAutomationScenario
import ch.trancee.meshlink.reference.automation.wireValue
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
        Log.i(TAG, "REFERENCE_AUTOMATION activity.stage=onCreate directProof=$directProofEnabled screenOn=${isDeviceInteractive()}")
        val automationEnabled = intent?.getBooleanExtra(EXTRA_UI_AUTOMATION, false) == true
        val automationMode = intent?.getStringExtra(EXTRA_UI_AUTOMATION_MODE)
        directProofEnabled = automationEnabled && automationMode == AUTOMATION_MODE_LIVE_PROOF
        if (automationEnabled && automationMode == AUTOMATION_MODE_LIVE_PROOF) {
            AndroidDiscoveryAdvertisementConfig.carrier =
                intent?.getStringExtra(EXTRA_UI_AUTOMATION_ADVERTISEMENT_CARRIER)
                    .toDiscoveryAdvertisementCarrier()
        } else {
            AndroidDiscoveryAdvertisementConfig.carrier = DiscoveryAdvertisementCarrier.UUID_PAIR
        }
        if (directProofEnabled) {
            startDirectProofPowerService()
            keepScreenOn()
        }
        val platformServices =
            if (automationEnabled && automationMode == AUTOMATION_MODE_LIVE_PROOF) {
                createLiveAutomationPlatformServices(
                    context = applicationContext,
                    storageSubdirectory =
                        intent?.getStringExtra(EXTRA_UI_AUTOMATION_STORAGE_SUBDIRECTORY)
                            ?: DEFAULT_AUTOMATION_STORAGE_SUBDIRECTORY,
                    appId =
                        intent?.getStringExtra(EXTRA_UI_AUTOMATION_APP_ID)
                            ?: DEFAULT_LIVE_AUTOMATION_APP_ID,
                    role =
                        intent?.getStringExtra(EXTRA_UI_AUTOMATION_ROLE).toReferenceAutomationRole(),
                    requiredPeerCount =
                        intent?.getIntExtra(EXTRA_UI_AUTOMATION_REQUIRED_PEER_COUNT, 1) ?: 1,
                    targetPeerIndex =
                        intent?.getIntExtra(EXTRA_UI_AUTOMATION_TARGET_PEER_INDEX, 0) ?: 0,
                    targetPeerId = intent?.getStringExtra(EXTRA_UI_AUTOMATION_TARGET_PEER_ID),
                    scenario =
                        intent?.getStringExtra(EXTRA_UI_AUTOMATION_SCENARIO)
                            .toReferenceAutomationScenario(),
                )
            } else if (automationEnabled) {
                createAutomationPlatformServices(
                    context = applicationContext,
                    storageSubdirectory =
                        intent?.getStringExtra(EXTRA_UI_AUTOMATION_STORAGE_SUBDIRECTORY)
                            ?: DEFAULT_AUTOMATION_STORAGE_SUBDIRECTORY,
                    blocked = intent?.getBooleanExtra(EXTRA_UI_AUTOMATION_BLOCKED, false) == true,
                )
            } else {
                createPlatformServices(applicationContext)
            }
        activePlatformServices = platformServices
        startupCoordinator =
            if (directProofEnabled) {
                ReferenceStartupCoordinator(platformServices = platformServices, scope = lifecycleScope)
            } else {
                null
            }
        if (directProofEnabled) {
            platformServices.automationConfig?.let { automationConfig ->
                platformServices.emitAutomationLog(automationConfig.startupMarker())
            }
            emitDirectProofPowerState(platformServices, "onCreate")
            startupCoordinator?.startLiveProofIfNeeded()
        }
        setContent { ReferenceApp(platformServices = platformServices) }
    }

    override fun onResume() {
        super.onResume()
        if (directProofEnabled) {
            Log.i(TAG, "REFERENCE_AUTOMATION activity.stage=onResume directProof=$directProofEnabled screenOn=${isDeviceInteractive()}")
            emitDirectProofPowerState(activePlatformServices, "onResume")
        }
    }

    override fun onPause() {
        if (directProofEnabled) {
            Log.i(TAG, "REFERENCE_AUTOMATION activity.stage=onPause directProof=$directProofEnabled screenOn=${isDeviceInteractive()}")
            emitDirectProofPowerState(activePlatformServices, "onPause")
        }
        super.onPause()
    }

    override fun onStop() {
        if (directProofEnabled) {
            Log.i(TAG, "REFERENCE_AUTOMATION activity.stage=onStop directProof=$directProofEnabled screenOn=${isDeviceInteractive()}")
            emitDirectProofPowerState(activePlatformServices, "onStop")
        }
        if (!isChangingConfigurations && !directProofEnabled) {
            stopDirectProofPowerService()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (directProofEnabled) {
            Log.i(TAG, "REFERENCE_AUTOMATION activity.stage=onDestroy directProof=$directProofEnabled screenOn=${isDeviceInteractive()}")
            emitDirectProofPowerState(activePlatformServices, "onDestroy")
        }
        releaseScreenOn()
        stopDirectProofPowerService()
        super.onDestroy()
    }

    public companion object {
        private const val TAG = "MeshLinkReference"
        public const val EXTRA_UI_AUTOMATION: String =
            "ch.trancee.meshlink.reference.extra.UI_AUTOMATION"
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

        public fun automationIntent(
            context: Context,
            storageSubdirectory: String,
            blocked: Boolean = false,
        ): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_UI_AUTOMATION, true)
                putExtra(EXTRA_UI_AUTOMATION_STORAGE_SUBDIRECTORY, storageSubdirectory)
                putExtra(EXTRA_UI_AUTOMATION_BLOCKED, blocked)
                putExtra(EXTRA_UI_AUTOMATION_MODE, AUTOMATION_MODE_SCRIPTED)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        }

        public fun liveAutomationIntent(
            context: Context,
            storageSubdirectory: String,
            appId: String,
            role: ReferenceAutomationRole,
            scenario: ReferenceAutomationScenario = ReferenceAutomationScenario.DIRECT_GUIDED,
            targetPeerId: String? = null,
            advertisementCarrier: DiscoveryAdvertisementCarrier =
                DiscoveryAdvertisementCarrier.UUID_PAIR,
        ): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_UI_AUTOMATION, true)
                putExtra(EXTRA_UI_AUTOMATION_MODE, AUTOMATION_MODE_LIVE_PROOF)
                putExtra(EXTRA_UI_AUTOMATION_STORAGE_SUBDIRECTORY, storageSubdirectory)
                putExtra(EXTRA_UI_AUTOMATION_APP_ID, appId)
                putExtra(EXTRA_UI_AUTOMATION_ROLE, role.name.lowercase())
                putExtra(EXTRA_UI_AUTOMATION_SCENARIO, scenario.wireValue())
                putExtra(EXTRA_UI_AUTOMATION_ADVERTISEMENT_CARRIER, advertisementCarrier.name)
                targetPeerId?.let { putExtra(EXTRA_UI_AUTOMATION_TARGET_PEER_ID, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        }
    }

    private fun startDirectProofPowerService() {
        ContextCompat.startForegroundService(this, DirectProofPowerService.start(this))
    }

    private fun stopDirectProofPowerService() {
        stopService(DirectProofPowerService.start(this))
    }

    private fun keepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun releaseScreenOn() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun emitDirectProofPowerState(
        platformServices: PlatformServices?,
        stage: String,
    ) {
        if (platformServices == null) return
        platformServices.emitAutomationLog(
            "REFERENCE_AUTOMATION power.state stage=$stage interactive=${isDeviceInteractive()} powerSaveMode=${isPowerSaveMode()} directProof=$directProofEnabled",
        )
    }

    private fun isDeviceInteractive(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION") powerManager.isScreenOn
        }
    }

    private fun isPowerSaveMode(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            powerManager.isPowerSaveMode
        } else {
            false
        }
    }
}

private fun String?.toReferenceAutomationRole(): ReferenceAutomationRole {
    return when {
        this.equals("sender", ignoreCase = true) -> ReferenceAutomationRole.SENDER
        this.equals("relay", ignoreCase = true) -> ReferenceAutomationRole.RELAY
        else -> ReferenceAutomationRole.PASSIVE
    }
}

private fun String?.toDiscoveryAdvertisementCarrier(): DiscoveryAdvertisementCarrier {
    return when {
        this.equals(
            DiscoveryAdvertisementCarrier.UUID_PAIR_PLUS_SERVICE_DATA.name,
            ignoreCase = true,
        ) -> DiscoveryAdvertisementCarrier.UUID_PAIR_PLUS_SERVICE_DATA
        else -> DiscoveryAdvertisementCarrier.UUID_PAIR
    }
}
