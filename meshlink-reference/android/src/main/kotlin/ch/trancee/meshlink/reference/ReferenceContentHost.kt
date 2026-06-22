package ch.trancee.meshlink.reference

import androidx.activity.compose.setContent
import ch.trancee.meshlink.reference.app.ReferenceApp
import ch.trancee.meshlink.reference.model.REFERENCE_AUTHORITY_MODE_LIVE
import ch.trancee.meshlink.reference.platform.PlatformServices

internal fun AndroidLiveAutomationState.toPlatformServices(): PlatformServices {
    return object : PlatformServices {
        override val platformName: String = "Android"
        override val defaultAuthorityMode: String = REFERENCE_AUTHORITY_MODE_LIVE
        override val readinessGuidance: List<String> = this@toPlatformServices.readinessGuidance
        override val readinessBlockers: List<String> = this@toPlatformServices.readinessBlockers
        override val powerMitigationStatus: String? =
            "Foreground wake lock active for live-proof automation sessions."
        override val documentStore: Any? = null
        override val meshLinkController = this@toPlatformServices.meshLinkController
        override fun stopPowerMitigation(): Unit = this@toPlatformServices.stopPowerMitigation()
        override fun currentTimeMillis(): Long = this@toPlatformServices.currentTimeMillis()
        override fun emitAutomationLog(message: String): Unit =
            this@toPlatformServices.emitAutomationLog(message)
    }
}

internal fun MainActivity.bindReferenceContent(
    platformServices: AndroidLiveAutomationState,
    automationConfig: AutomationConfig,
) {
    setContent {
        ReferenceApp(
            platformServices = platformServices.toPlatformServices(),
            automationConfig = automationConfig.toAndroidAutomationConfigView(),
        )
    }
}
