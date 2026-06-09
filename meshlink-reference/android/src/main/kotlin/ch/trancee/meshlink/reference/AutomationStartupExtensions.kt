package ch.trancee.meshlink.reference

import ch.trancee.meshlink.reference.automation.startupMarker
import ch.trancee.meshlink.reference.platform.PlatformServices

internal fun MainActivity.emitStartupMarker(platformServices: PlatformServices) {
    platformServices.automationConfig?.let { automationConfig ->
        platformServices.emitAutomationLog(automationConfig.startupMarker())
    }
}
