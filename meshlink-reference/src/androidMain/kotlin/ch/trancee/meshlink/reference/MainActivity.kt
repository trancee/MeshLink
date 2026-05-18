package ch.trancee.meshlink.reference

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ch.trancee.meshlink.reference.app.ReferenceApp
import ch.trancee.meshlink.reference.automation.ReferenceAutomationRole
import ch.trancee.meshlink.reference.platform.createAndroidAutomationPlatformServices
import ch.trancee.meshlink.reference.platform.createAndroidLiveAutomationPlatformServices
import ch.trancee.meshlink.reference.platform.createAndroidPlatformServices

/** Android entry point for the shared reference app shell. */
public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        val automationEnabled = intent?.getBooleanExtra(EXTRA_UI_AUTOMATION, false) == true
        val automationMode = intent?.getStringExtra(EXTRA_UI_AUTOMATION_MODE)
        val platformServices =
            if (automationEnabled && automationMode == AUTOMATION_MODE_LIVE_PROOF) {
                createAndroidLiveAutomationPlatformServices(
                    context = applicationContext,
                    storageSubdirectory =
                        intent?.getStringExtra(EXTRA_UI_AUTOMATION_STORAGE_SUBDIRECTORY)
                            ?: DEFAULT_AUTOMATION_STORAGE_SUBDIRECTORY,
                    appId =
                        intent?.getStringExtra(EXTRA_UI_AUTOMATION_APP_ID)
                            ?: DEFAULT_LIVE_AUTOMATION_APP_ID,
                    role =
                        intent?.getStringExtra(EXTRA_UI_AUTOMATION_ROLE).toReferenceAutomationRole(),
                )
            } else if (automationEnabled) {
                createAndroidAutomationPlatformServices(
                    context = applicationContext,
                    storageSubdirectory =
                        intent?.getStringExtra(EXTRA_UI_AUTOMATION_STORAGE_SUBDIRECTORY)
                            ?: DEFAULT_AUTOMATION_STORAGE_SUBDIRECTORY,
                    blocked = intent?.getBooleanExtra(EXTRA_UI_AUTOMATION_BLOCKED, false) == true,
                )
            } else {
                createAndroidPlatformServices(applicationContext)
            }
        setContent { ReferenceApp(platformServices = platformServices) }
    }

    public companion object {
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
        ): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_UI_AUTOMATION, true)
                putExtra(EXTRA_UI_AUTOMATION_MODE, AUTOMATION_MODE_LIVE_PROOF)
                putExtra(EXTRA_UI_AUTOMATION_STORAGE_SUBDIRECTORY, storageSubdirectory)
                putExtra(EXTRA_UI_AUTOMATION_APP_ID, appId)
                putExtra(EXTRA_UI_AUTOMATION_ROLE, role.name.lowercase())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        }
    }
}

private fun String?.toReferenceAutomationRole(): ReferenceAutomationRole {
    return if (this.equals("sender", ignoreCase = true)) {
        ReferenceAutomationRole.SENDER
    } else {
        ReferenceAutomationRole.PASSIVE
    }
}
